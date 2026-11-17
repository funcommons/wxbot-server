package com.wechat.sync.server.task.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.support.MpTestTables;
import com.wechat.sync.server.task.dto.TaskAdminRequests.ScheduleCreateRequest;
import com.wechat.sync.server.task.dto.TaskAdminRequests.ScheduleUpdateRequest;
import com.wechat.sync.server.task.dto.TaskAdminRequests.TaskCreateRequest;
import com.wechat.sync.server.task.dto.TaskAdminVOs.ScheduleVO;
import com.wechat.sync.server.task.dto.TaskAdminVOs.TaskRunVO;
import com.wechat.sync.server.task.dto.TaskAdminVOs.TaskVO;
import com.wechat.sync.server.task.dto.TaskClaimVO;
import com.wechat.sync.server.task.dto.TaskClientRequests.ClaimRequest;
import com.wechat.sync.server.task.dto.TaskClientRequests.CompleteRequest;
import com.wechat.sync.server.task.dto.TaskClientRequests.FailRequest;
import com.wechat.sync.server.task.dto.TaskClientRequests.RenewRequest;
import com.wechat.sync.server.task.dto.TaskReportResult;
import com.wechat.sync.server.task.entity.WxTaskEntity;
import com.wechat.sync.server.task.entity.WxTaskRunEntity;
import com.wechat.sync.server.task.entity.WxTaskScheduleEntity;
import com.wechat.sync.server.task.mapper.WxTaskMapper;
import com.wechat.sync.server.task.mapper.WxTaskRunMapper;
import com.wechat.sync.server.task.service.TaskService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 任务分发 HTTP 层单测（不起 Spring 上下文，鉴权拦截器属框架行为不测——QueryControllerTest 先例）：
 * TaskIdParser 口径、类级 @RequiresToken 防线与代表路由、client 四端点的 TokenContext 账号透传、
 * admin 端点转发与列表映射、lease_id 必填注解存在性。
 */
@ExtendWith(MockitoExtension.class)
class TaskControllersTest {

    private static final String ACCOUNT = "wxid_t9test_a";

    @Mock private TaskService taskService;
    @Mock private WxTaskMapper taskMapper;
    @Mock private WxTaskRunMapper taskRunMapper;

    private TaskClientController client;
    private TaskAdminController admin;

    @BeforeAll
    static void initTables() {
        MpTestTables.init(WxTaskEntity.class, WxTaskRunEntity.class,
                WxTaskScheduleEntity.class, com.wechat.sync.server.account.entity.WxAccountEntity.class);
    }

    @BeforeEach
    void setUp() {
        client = new TaskClientController(taskService);
        admin = new TaskAdminController(taskService, taskMapper, taskRunMapper);
        TokenContext.set(TokenService.CLIENT_TYPE, Map.of(TokenService.CLAIM_ACCOUNT, ACCOUNT));
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    // ------------------------------------------------------------ TaskIdParser

    @Test
    void parserParsesNumericAndRejectsElseWith10102() {
        assertThat(TaskIdParser.parse("42", "task_id")).as("数字正常解析").isEqualTo(42L);
        assertThatThrownBy(() -> TaskIdParser.parse("abc", "task_id"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        assertThat(TaskIdParser.parseOptional(null, "task_id")).as("null 透传不过滤").isNull();
        assertThat(TaskIdParser.parseOptional("  ", "task_id")).as("空白等价 null").isNull();
        assertThat(TaskIdParser.parseOptional("7", "task_id")).isEqualTo(7L);
        assertThatThrownBy(() -> TaskIdParser.parseOptional("x", "task_id"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
    }

    // ------------------------------------------------------------ 注解防线与路由

    @Test
    void classGuardsAndRepresentativeRoutes() throws NoSuchMethodException {
        RequiresToken clientGuard = TaskClientController.class.getAnnotation(RequiresToken.class);
        assertThat(clientGuard).as("client 类级防线").isNotNull();
        assertThat(clientGuard.value()).isEqualTo(TokenService.CLIENT_TYPE);
        assertThat(clientGuard.roles()).containsExactly(TokenService.ROLE_CLIENT);

        RequiresToken adminGuard = TaskAdminController.class.getAnnotation(RequiresToken.class);
        assertThat(adminGuard).as("admin 类级防线").isNotNull();
        assertThat(adminGuard.value()).isEqualTo(TokenService.ADMIN_TYPE);
        assertThat(adminGuard.roles()).containsExactly(TokenService.ROLE_ADMIN);

        assertThat(TaskClientController.class.getMethod("claim", ClaimRequest.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/claim");
        assertThat(TaskClientController.class.getMethod("complete", String.class, CompleteRequest.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/{task_id}/complete");
        assertThat(TaskAdminController.class.getMethod("create", TaskCreateRequest.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/v1/tasks");
        assertThat(TaskAdminController.class.getMethod("cancel", String.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/v1/tasks/{task_id}/cancel");
        assertThat(TaskAdminController.class.getMethod("reset", String.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/v1/tasks/{task_id}/reset");
        assertThat(TaskAdminController.class.getMethod("listRuns", long.class, long.class, String.class, String.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/v1/task-runs");
        assertThat(TaskAdminController.class.getMethod("scheduleDetail", String.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/v1/task-schedules/{task_schedule_id}");
        assertThat(TaskAdminController.class.getMethod("updateSchedule", String.class, ScheduleUpdateRequest.class)
                .getAnnotation(PutMapping.class).value()).containsExactly("/v1/task-schedules/{id}");
    }

    /** lease_id 是汇报凭据（§4.2 以它为条件）：三个汇报 record 的该字段必须有 @NotBlank。 */
    @Test
    void reportRequestsDeclareNotBlankLeaseId() {
        for (Class<?> type : List.of(CompleteRequest.class, FailRequest.class, RenewRequest.class)) {
            // @NotBlank 的 target 不含 RECORD_COMPONENT：record 上它落在 backing field，须走字段反射
            assertThat(java.util.Arrays.stream(type.getDeclaredFields())
                    .filter(f -> f.getName().equals("leaseId")).findFirst().orElseThrow()
                    .getAnnotation(NotBlank.class))
                    .as(type.getSimpleName() + ".leaseId 必填注解").isNotNull();
        }
    }

    // ------------------------------------------------------------ client 端点

    @Test
    void claimForwardsTokenAccountAndNullDefaults() {
        given(taskService.claim(ACCOUNT, null, null)).willReturn(List.of());

        ApiResponse<List<TaskClaimVO>> resp = client.claim(null);

        assertThat(resp.getCode()).as("空手也是成功信封").isZero();
        assertThat(resp.getData()).isEmpty();
        verify(taskService).claim(ACCOUNT, null, null);

        given(taskService.claim(ACCOUNT, 3, List.of("SYNC_MESSAGES"))).willReturn(List.of());
        client.claim(new ClaimRequest(3, List.of("SYNC_MESSAGES")));
        verify(taskService).claim(ACCOUNT, 3, List.of("SYNC_MESSAGES"));
    }

    @Test
    void claimRejectsMissingAccountClaimAsForbidden() {
        TokenContext.set(TokenService.CLIENT_TYPE, Map.of());

        assertThatThrownBy(() -> client.claim(null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiCode.FORBIDDEN.getCode()));
        verifyNoInteractions(taskService);
    }

    @Test
    void completeWrapsEffectAndReplayAsZeroCode() {
        given(taskService.complete(ACCOUNT, 42L, "lease-1", "{\"n\":1}"))
                .willReturn(new TaskReportResult(false, "SUCCEEDED", "{\"n\":1}", null));
        ApiResponse<TaskReportResult> resp = client.complete("42", new CompleteRequest("lease-1", "{\"n\":1}"));
        assertThat(resp.getCode()).as("生效也是 0").isZero();
        assertThat(resp.getData().replayed()).isFalse();
        assertThat(resp.getData().taskStatus()).isEqualTo("SUCCEEDED");

        given(taskService.complete(ACCOUNT, 43L, "lease-2", "{}"))
                .willReturn(new TaskReportResult(true, "SUCCEEDED", "{\"n\":0}", null));
        ApiResponse<TaskReportResult> replay = client.complete("43", new CompleteRequest("lease-2", "{}"));
        assertThat(replay.getCode()).as("同 lease 终态重放不报错（§4.2）").isZero();
        assertThat(replay.getData().replayed()).isTrue();
    }

    @Test
    void completePropagatesLeaseLost() {
        given(taskService.complete(any(), anyLong(), any(), any()))
                .willThrow(new ApiException(10603, "租约已失效"));

        assertThatThrownBy(() -> client.complete("9", new CompleteRequest("lease-x", "{}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10603));
    }

    @Test
    void failForwardsRetryableDefaultingToFalse() {
        given(taskService.fail(ACCOUNT, 9L, "lease-1", "boom", false))
                .willReturn(new TaskReportResult(false, "PENDING", null, "boom"));
        ApiResponse<TaskReportResult> resp = client.fail("9", new FailRequest("lease-1", "boom", null));
        assertThat(resp.getData().taskStatus()).as("retryable 缺省 false").isEqualTo("PENDING");

        client.fail("9", new FailRequest("lease-1", "boom", true));
        verify(taskService).fail(ACCOUNT, 9L, "lease-1", "boom", true);
    }

    @Test
    void renewForwardsLeaseId() {
        given(taskService.renew(ACCOUNT, 5L, "lease-9"))
                .willReturn(new TaskReportResult(false, "RUNNING", null, null));
        ApiResponse<TaskReportResult> resp = client.renew("5", new RenewRequest("lease-9"));
        assertThat(resp.getData().taskStatus()).isEqualTo("RUNNING");
        verify(taskService).renew(ACCOUNT, 5L, "lease-9");
    }

    // ------------------------------------------------------------ admin 端点

    @Test
    void adminCreateForwardsAllFieldsIncludingMaxAttempts() {
        given(taskService.createTask(ACCOUNT, "PROBE", "t9test_x", 2, "{}", 5)).willReturn(77L);

        ApiResponse<Long> resp = admin.create(
                new TaskCreateRequest("PROBE", ACCOUNT, "t9test_x", 2, "{}", 5));

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData()).isEqualTo(77L);
        verify(taskService).createTask(ACCOUNT, "PROBE", "t9test_x", 2, "{}", 5);
    }

    @Test
    void adminCancelAndResetForwardParsedId() {
        ApiResponse<Void> cancelled = admin.cancel("3");
        assertThat(cancelled.getCode()).isZero();
        verify(taskService).cancelTask(3L);

        ApiResponse<Void> reset = admin.reset("4");
        assertThat(reset.getCode()).isZero();
        verify(taskService).resetDeadTask(4L);
    }

    @Test
    void adminListMapsEntitiesToVoAndClampsPagination() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WxTaskEntity> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(task()));
        page.setTotal(1);
        given(taskMapper.selectPage(any(), any())).willReturn(page);

        ApiResponse<PageVO<TaskVO>> resp = admin.list(1, 20, "PENDING", ACCOUNT);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().total()).isEqualTo(1);
        assertThat(resp.getData().list()).hasSize(1);
        assertThat(resp.getData().list().get(0).status()).isEqualTo("PENDING");
        assertThat(resp.getData().list().get(0).account()).isEqualTo(ACCOUNT);
        assertThat(resp.getData().hasMore()).as("total=1 无下一页").isFalse();
    }

    @Test
    void adminListRejectsUnknownStatusBeforeQuerying() {
        assertThatThrownBy(() -> admin.list(1, 20, "WHAT", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(taskMapper, never()).selectPage(any(), any());
    }

    @Test
    void adminListRunsParsesOptionalTaskIdAndRejectsGarbage() {
        assertThatThrownBy(() -> admin.listRuns(1, 20, "abc", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(taskRunMapper, never()).selectPage(any(), any());

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WxTaskRunEntity> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(run()));
        page.setTotal(1);
        given(taskRunMapper.selectPage(any(), any())).willReturn(page);

        ApiResponse<PageVO<TaskRunVO>> resp = admin.listRuns(1, 20, "41", ACCOUNT);
        assertThat(resp.getData().list().get(0).outcome()).isEqualTo("LOST");
        assertThat(resp.getData().list().get(0).taskId()).isEqualTo(41L);
    }

    @Test
    void adminSchedulesListDetailAndUpdateForward() {
        given(taskService.listSchedules()).willReturn(List.of(sched()));
        ApiResponse<List<ScheduleVO>> list = admin.listSchedules();
        assertThat(list.getData()).hasSize(1);
        assertThat(list.getData().get(0).intervalSeconds()).isEqualTo(86400);

        given(taskService.scheduleDetail(9L)).willReturn(sched());
        ApiResponse<ScheduleVO> detail = admin.scheduleDetail("9");
        assertThat(detail.getData().id()).isEqualTo(9L);

        given(taskService.scheduleDetail(8L))
                .willThrow(new ApiException(ApiCode.NOT_FOUND, "计划不存在: 8"));
        assertThatThrownBy(() -> admin.scheduleDetail("8"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        // 空体 PUT 已定夺为 10101 拒绝（业务口径）——转发例只传真实 body
        admin.updateSchedule("6", new ScheduleUpdateRequest(Boolean.TRUE, 300, null, 1, "{\"k\":1}", 15));
        verify(taskService).updateSchedule(6L, Boolean.TRUE, 300, null, 1, "{\"k\":1}", 15);
    }

    /** 空体 PUT → 10101 拒绝、非数字 id → 10102：两者都在入口拦截，service 零交互（口径：拒绝在 service 之前）。 */
    @Test
    void adminUpdateScheduleRejectsEmptyBodyAndGarbageIdWithoutServiceCall() {
        assertThatThrownBy(() -> admin.updateSchedule("6", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
        assertThatThrownBy(() -> admin.updateSchedule("abc", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(taskService, never()).updateSchedule(anyLong(), any(), any(), any(), any(), any(), any());
    }

    /** createSchedule 端点也在此文件覆盖（ScheduleCreateRequest 字段全透传）。 */
    @Test
    void adminCreateScheduleForwardsAllFields() {
        given(taskService.createSchedule(null, "SYNC_RELATIONS", null, 86400, null,
                null, null, null, null)).willReturn(9L);

        ApiResponse<Long> resp = admin.createSchedule(
                new ScheduleCreateRequest(null, "SYNC_RELATIONS", null, 86400, null, null, null, null, null));

        assertThat(resp.getData()).isEqualTo(9L);
        verify(taskService).createSchedule(null, "SYNC_RELATIONS", null, 86400, null, null, null, null, null);
    }

    // ------------------------------------------------------------ 夹具

    private WxTaskEntity task() {
        WxTaskEntity t = new WxTaskEntity();
        t.setId(41L);
        t.setTaskType("PROBE");
        t.setAccount(ACCOUNT);
        t.setTarget("t9test_x");
        t.setStatus("PENDING");
        t.setAttempts(0);
        t.setMaxAttempts(3);
        return t;
    }

    private WxTaskRunEntity run() {
        WxTaskRunEntity r = new WxTaskRunEntity();
        r.setId(7L);
        r.setTaskId(41L);
        r.setAttemptNo(1);
        r.setLeaseId("lease-1");
        r.setAccount(ACCOUNT);
        r.setOutcome("LOST");
        return r;
    }

    private WxTaskScheduleEntity sched() {
        WxTaskScheduleEntity s = new WxTaskScheduleEntity();
        s.setId(9L);
        s.setAccount(null);
        s.setTaskType("SYNC_RELATIONS");
        s.setTarget("*");
        s.setIntervalSeconds(86400);
        s.setEnabled(1);
        return s;
    }
}
