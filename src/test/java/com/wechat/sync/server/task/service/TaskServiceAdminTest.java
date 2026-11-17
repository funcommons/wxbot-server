package com.wechat.sync.server.task.service;

import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.relation.mapper.WxConversationMapper;
import com.wechat.sync.server.support.MpTestTables;
import com.wechat.sync.server.task.entity.WxTaskEntity;
import com.wechat.sync.server.task.entity.WxTaskScheduleEntity;
import com.wechat.sync.server.task.mapper.WxTaskMapper;
import com.wechat.sync.server.task.mapper.WxTaskRunMapper;
import com.wechat.sync.server.task.mapper.WxTaskScheduleMapper;
import com.wechat.sync.server.task.scheduler.TaskSchedulerConfig;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * TaskService admin 辅助补测（T9-3 测试轮缺口）：createTask 重载口径（max_attempts 钳制/缺省 3）、
 * cancelTask/resetDeadTask 状态机、schedule CRUD（next_materialize_at 强制排期）、
 * TaskSchedulerConfig 直接调用的 catch-all 兜底。物化/认领/汇报在 TaskServiceTest 与真库 IT。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceAdminTest {

    private static final String ACCOUNT = "wxid_t9test_a";
    private static final long TASK_ID = 41L;

    @Mock private WxTaskMapper taskMapper;
    @Mock private WxTaskRunMapper taskRunMapper;
    @Mock private WxTaskScheduleMapper scheduleMapper;
    @Mock private WxAccountMapper accountMapper;
    @Mock private WxConversationMapper conversationMapper;
    @Mock private AccountBinding accountBinding;
    @Mock private TaskService schedulerTarget;

    private TaskService service;

    @BeforeAll
    static void initTables() {
        MpTestTables.init(WxTaskEntity.class, WxTaskScheduleEntity.class,
                com.wechat.sync.server.account.entity.WxAccountEntity.class);
    }

    private TaskService service() {
        if (service == null) {
            service = new TaskService(taskMapper, taskRunMapper, scheduleMapper, accountMapper,
                    conversationMapper, accountBinding, new com.fasterxml.jackson.databind.ObjectMapper(),
                    600, 20, 5);
        }
        return service;
    }

    // ------------------------------------------------------------ createTask

    @Test
    void createTaskDefaultsTargetPayloadAndMaxAttempts() {
        given(accountMapper.selectCount(any())).willReturn(1L);
        given(taskMapper.insertTaskIgnoreReturnIdMax(any(), any(), any(), anyInt(), any(), any(), any()))
                .willReturn(List.of(77L));

        long id = service().createTask(ACCOUNT, "PROBE", null, null, null, null);

        assertThat(id).isEqualTo(77L);
        verify(taskMapper).insertTaskIgnoreReturnIdMax(eq("PROBE"), eq(ACCOUNT), eq("*"), eq(0),
                eq("{}"), isNull(), isNull());
    }

    @Test
    void createTaskAcceptsBoundaryMaxAttempts() {
        given(accountMapper.selectCount(any())).willReturn(1L);
        given(taskMapper.insertTaskIgnoreReturnIdMax(any(), any(), any(), anyInt(), any(), any(), any()))
                .willReturn(List.of(1L), List.of(2L));

        service().createTask(ACCOUNT, "PROBE", "t9test_x", null, "{}", 1);
        service().createTask(ACCOUNT, "PROBE", "t9test_x", null, "{}", 10);

        verify(taskMapper).insertTaskIgnoreReturnIdMax(eq("PROBE"), eq(ACCOUNT), eq("t9test_x"),
                eq(0), eq("{}"), isNull(), eq(1));
        verify(taskMapper).insertTaskIgnoreReturnIdMax(eq("PROBE"), eq(ACCOUNT), eq("t9test_x"),
                eq(0), eq("{}"), isNull(), eq(10));
    }

    @Test
    void createTaskRejectsOutOfRangeMaxAttemptsBeforeAnyQuery() {
        assertThatThrownBy(() -> service().createTask(ACCOUNT, "PROBE", null, null, "{}", 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        assertThatThrownBy(() -> service().createTask(ACCOUNT, "PROBE", null, null, "{}", 11))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verifyNoInteractions(accountMapper, taskMapper);
    }

    @Test
    void createTaskRejectsInvalidPayloadAfterAccountCheck() {
        given(accountMapper.selectCount(any())).willReturn(1L);

        assertThatThrownBy(() -> service().createTask(ACCOUNT, "PROBE", null, null, "[1,2]", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        assertThatThrownBy(() -> service().createTask(ACCOUNT, "PROBE", null, null, "not-json", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(taskMapper, never()).insertTaskIgnoreReturnIdMax(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void createTaskRejectsUnknownAccountWith10601AndDuplicateActiveWith10402() {
        given(accountMapper.selectCount(any())).willReturn(0L);
        assertThatThrownBy(() -> service().createTask(ACCOUNT, "PROBE", null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10601));

        given(accountMapper.selectCount(any())).willReturn(1L);
        given(taskMapper.insertTaskIgnoreReturnIdMax(any(), any(), any(), anyInt(), any(), any(), any()))
                .willReturn(List.of());
        assertThatThrownBy(() -> service().createTask(ACCOUNT, "PROBE", null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
    }

    @Test
    void createTaskRejectsUnknownTypeBeforeAccountQuery() {
        assertThatThrownBy(() -> service().createTask(ACCOUNT, "NOPE", null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verifyNoInteractions(accountMapper, taskMapper);
    }

    // ------------------------------------------------------------ cancel / reset

    @Test
    void cancelTaskOnDispatchedAlsoMarksRunLost() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("DISPATCHED", "lease-1"));

        service().cancelTask(TASK_ID);

        verify(taskMapper).cancelTaskRow(TASK_ID);
        verify(taskRunMapper).markRunLost(TASK_ID, "lease-1", "cancelled by admin");
    }

    @Test
    void cancelTaskOnPendingSkipsRunLost() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("PENDING", null));

        service().cancelTask(TASK_ID);

        verify(taskMapper).cancelTaskRow(TASK_ID);
        verifyNoInteractions(taskRunMapper);
    }

    @Test
    void cancelTaskRejectsTerminalAndMissing() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("SUCCEEDED", null), null);

        assertThatThrownBy(() -> service().cancelTask(TASK_ID))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
        assertThatThrownBy(() -> service().cancelTask(TASK_ID))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        verify(taskMapper, never()).cancelTaskRow(anyLong());
    }

    @Test
    void resetDeadTaskOnlyFromDeadState() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("DEAD", "lease-9"));

        service().resetDeadTask(TASK_ID);
        verify(taskMapper).resetDeadRow(TASK_ID);

        clearInvocations(taskMapper);   // 清计数：后半段只验证 PENDING 分支不再新增 resetDeadRow
        given(taskMapper.selectById(TASK_ID)).willReturn(task("PENDING", null));
        assertThatThrownBy(() -> service().resetDeadTask(TASK_ID))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
        verify(taskMapper, never()).resetDeadRow(anyLong());
    }

    // ------------------------------------------------------------ schedule CRUD

    @Test
    void createScheduleForcesNextMaterializeAtWhenSchedulable() {
        ArgumentCaptor<WxTaskScheduleEntity> captor = ArgumentCaptor.forClass(WxTaskScheduleEntity.class);
        // mock 回填 id（模拟真库 ASSIGN_ID）：createSchedule 返回 getId().longValue()，不回填必 NPE
        given(scheduleMapper.insert(any(WxTaskScheduleEntity.class))).willAnswer(inv -> {
            inv.getArgument(0, WxTaskScheduleEntity.class).setId(301L);
            return 1;
        });

        service().createSchedule(null, "SYNC_RELATIONS", null, 86400, null, null, null, null, null);

        verify(scheduleMapper).insert(captor.capture());
        WxTaskScheduleEntity row = captor.getValue();
        assertThat(row.getNextMaterializeAt()).as("可调度计划强制排期=now").isNotNull();
        assertThat(row.getAccount()).isNull();
        assertThat(row.getTarget()).isEqualTo("*");
        assertThat(row.getPriority()).isEqualTo(0);
        assertThat(row.getActivityWindowDays()).isEqualTo(30);
        assertThat(row.getPayload()).isEqualTo("{}");
        assertThat(row.getEnabled()).isEqualTo(1);
        assertThat(row.getIsDeleted()).isEqualTo(0);
    }

    @Test
    void createScheduleManualOnlyAndDisabledLeaveNextNull() {
        ArgumentCaptor<WxTaskScheduleEntity> captor = ArgumentCaptor.forClass(WxTaskScheduleEntity.class);
        given(accountMapper.selectCount(any())).willReturn(1L);   // 非空 account 走存在性检查（同 :78 先例）
        given(scheduleMapper.insert(any(WxTaskScheduleEntity.class))).willAnswer(inv -> {
            inv.getArgument(0, WxTaskScheduleEntity.class).setId(302L);   // 同上：回填防 NPE
            return 1;
        });

        service().createSchedule(ACCOUNT, "PROBE", "t9test_x", null, null, 2, "{\"k\":1}", 7, null);

        service().createSchedule(ACCOUNT, "PROBE", "t9test_x", 300, null, null, null, null, Boolean.FALSE);

        // 同一用例两次 createSchedule → insert 2 次：verify 必须 times(2)（默认 times(1) 会 TooManyActualInvocations）
        verify(scheduleMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getNextMaterializeAt()).as("仅手工不排期").isNull();
        assertThat(captor.getAllValues().get(0).getEnabled()).isEqualTo(1);
        assertThat(captor.getAllValues().get(0).getActivityWindowDays()).isEqualTo(7);
        assertThat(captor.getAllValues().get(1).getEnabled()).isEqualTo(0);
        assertThat(captor.getAllValues().get(1).getNextMaterializeAt()).as("停用不排期").isNull();
    }

    @Test
    void createScheduleRejectsBothPeriodsBadTypeAndBadPayload() {
        assertThatThrownBy(() -> service().createSchedule(null, "SYNC_RELATIONS", null,
                300, java.time.LocalTime.NOON, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        assertThatThrownBy(() -> service().createSchedule(null, "NOPE", null, 300, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        assertThatThrownBy(() -> service().createSchedule(null, "SYNC_RELATIONS", null,
                300, null, null, "[1]", null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(scheduleMapper, never()).insert(any(WxTaskScheduleEntity.class));
    }

    @Test
    void scheduleDetailRejectsMissingAndSoftDeleted() {
        WxTaskScheduleEntity live = sched();
        given(scheduleMapper.selectById(9L)).willReturn(live);
        assertThat(service().scheduleDetail(9L)).isSameAs(live);

        WxTaskScheduleEntity deleted = sched();
        deleted.setIsDeleted(1);
        given(scheduleMapper.selectById(9L)).willReturn(deleted);
        assertThatThrownBy(() -> service().scheduleDetail(9L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        given(scheduleMapper.selectById(8L)).willReturn(null);
        assertThatThrownBy(() -> service().scheduleDetail(8L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void updateScheduleReschedulesEnabledWithNullNextAndRejectsOthers() {
        WxTaskScheduleEntity paused = sched();
        paused.setNextMaterializeAt(null);
        given(scheduleMapper.selectById(9L)).willReturn(paused);

        service().updateSchedule(9L, Boolean.TRUE, null, null, 3, "{\"k\":1}", 15);

        verify(scheduleMapper).update(isNull(), any());

        given(scheduleMapper.selectById(8L)).willReturn(null);
        assertThatThrownBy(() -> service().updateSchedule(8L, Boolean.TRUE, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        given(scheduleMapper.selectById(9L)).willReturn(paused);
        assertThatThrownBy(() -> service().updateSchedule(9L, null, null, null, null, "[1]", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(scheduleMapper).update(isNull(), any());   // 仍是第 1 次那次
    }

    @Test
    void listSchedulesReturnsSelectedRows() {
        // 复用同一实例比对：sched() 每次调用 now() 纳秒不同，两个实例的实体全等必失败（修复口径）
        WxTaskScheduleEntity row = sched();
        given(scheduleMapper.selectList(any())).willReturn(List.of(row));
        assertThat(service().listSchedules()).containsExactly(row);
    }

    // ------------------------------------------------------------ TaskSchedulerConfig catch-all

    @Test
    void schedulerSweepsAndMaterializesWithoutThrowingOnFailure() {
        TaskSchedulerConfig config = new TaskSchedulerConfig(schedulerTarget);

        given(schedulerTarget.sweepExpiredLeases()).willReturn(3);
        config.sweepExpiredLeases();
        verify(schedulerTarget).sweepExpiredLeases();

        willThrow(new RuntimeException("pg down")).given(schedulerTarget).sweepExpiredLeases();
        assertThatCode(config::sweepExpiredLeases).as("回收失败不外溢（下轮重试）").doesNotThrowAnyException();

        given(schedulerTarget.materializeDueSchedules(any(OffsetDateTime.class))).willReturn(2);
        config.materializeDueSchedules();
        verify(schedulerTarget).materializeDueSchedules(any(OffsetDateTime.class));

        willThrow(new RuntimeException("pg down")).given(schedulerTarget)
                .materializeDueSchedules(any(OffsetDateTime.class));
        assertThatCode(config::materializeDueSchedules).as("物化失败不外溢").doesNotThrowAnyException();
    }

    // ------------------------------------------------------------ 夹具

    private WxTaskEntity task(String status, String leaseId) {
        WxTaskEntity t = new WxTaskEntity();
        t.setId(TASK_ID);
        t.setTaskType("PROBE");
        t.setAccount(ACCOUNT);
        t.setTarget("t9test_x");
        t.setStatus(status);
        t.setAttempts(0);
        t.setMaxAttempts(3);
        t.setLeaseId(leaseId);
        t.setIsDeleted(0);
        return t;
    }

    private WxTaskScheduleEntity sched() {
        WxTaskScheduleEntity s = new WxTaskScheduleEntity();
        s.setId(9L);
        s.setTaskType("SYNC_RELATIONS");
        s.setTarget("*");
        s.setIntervalSeconds(86400);
        s.setEnabled(1);
        s.setIsDeleted(0);
        s.setNextMaterializeAt(OffsetDateTime.now());
        return s;
    }
}
