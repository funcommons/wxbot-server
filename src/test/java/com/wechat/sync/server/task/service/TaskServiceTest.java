package com.wechat.sync.server.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.relation.entity.WxConversationEntity;
import com.wechat.sync.server.relation.mapper.WxConversationMapper;
import com.wechat.sync.server.task.dto.TaskClaimVO;
import com.wechat.sync.server.task.dto.TaskReportResult;
import com.wechat.sync.server.task.entity.WxTaskEntity;
import com.wechat.sync.server.task.entity.WxTaskRunEntity;
import com.wechat.sync.server.task.entity.WxTaskScheduleEntity;
import com.wechat.sync.server.task.mapper.WxTaskMapper;
import com.wechat.sync.server.task.mapper.WxTaskRunMapper;
import com.wechat.sync.server.task.mapper.WxTaskScheduleMapper;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * TaskService 单测（mock mapper，只测编排与判定逻辑；SQL 语义归真库 IT）：
 * 纯函数落点判定 / claim 钳制与 run 日志 / complete-fail-renew 的终态重放与 10603 /
 * 回收巡检 PENDING-DEAD 分流 / 物化展开（活动窗口、全扫轮转、上限、推进）/
 * SEND_MESSAGE 两键 payload 校验与禁计划（T11）。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final String ACCOUNT = "wxid_t9test_a";
    private static final long TASK_ID = 410L;
    private static final String LEASE = "lease-1";
    private static final OffsetDateTime T = OffsetDateTime.parse("2026-11-10T08:00:00+08:00");

    /** SEND_MESSAGE payload 断言用：落库 JSON 解析回读（键集合 / 值 / 键序） */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private WxTaskMapper taskMapper;
    @Mock private WxTaskRunMapper taskRunMapper;
    @Mock private WxTaskScheduleMapper scheduleMapper;
    @Mock private WxAccountMapper accountMapper;
    @Mock private WxConversationMapper conversationMapper;
    @Mock private AccountBinding accountBinding;

    private TaskService service;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxTaskEntity.class, WxTaskRunEntity.class, WxTaskScheduleEntity.class,
                WxAccountEntity.class, WxConversationEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new TaskService(taskMapper, taskRunMapper, scheduleMapper, accountMapper,
                conversationMapper, accountBinding, new ObjectMapper(), 600, 20, 2);
    }

    // ------------------------------------------------------------ 测试数据

    private WxTaskEntity task(String status, String leaseId, int attempts, int maxAttempts) {
        WxTaskEntity e = new WxTaskEntity();
        e.setId(TASK_ID);
        e.setTaskType(TaskService.TT_SYNC_MESSAGES);
        e.setAccount(ACCOUNT);
        e.setTarget("t9test_chat1");
        e.setStatus(status);
        e.setAttempts(attempts);
        e.setMaxAttempts(maxAttempts);
        e.setLeaseId(leaseId);
        e.setResult("{\"n\":1}");
        e.setLastError("boom");
        return e;
    }

    private WxTaskScheduleEntity schedule(String type, String target, Integer intervalSeconds) {
        WxTaskScheduleEntity s = new WxTaskScheduleEntity();
        s.setId(1L);
        s.setAccount(ACCOUNT);
        s.setTaskType(type);
        s.setTarget(target);
        s.setIntervalSeconds(intervalSeconds);
        s.setDailyAt(null);
        s.setPriority(0);
        s.setPayload("{}");
        s.setActivityWindowDays(30);
        s.setEnabled(1);
        s.setIsDeleted(0);
        s.setNextMaterializeAt(T);
        return s;
    }

    private WxConversationEntity convo(String username) {
        WxConversationEntity c = new WxConversationEntity();
        c.setAccount(ACCOUNT);
        c.setUsername(username);
        return c;
    }

    private WxAccountEntity acct(String name) {
        WxAccountEntity a = new WxAccountEntity();
        a.setAccount(name);
        return a;
    }

    /** SEND_MESSAGE payload 构造：只拼给定键（null = 不放该键），供缺键 / 非法形态用例 */
    private String sendMessagePayload(String chatUsername, String text) {
        ObjectNode node = JSON.createObjectNode();
        if (chatUsername != null) {
            node.put("chat_username", chatUsername);
        }
        if (text != null) {
            node.put("text", text);
        }
        return node.toString();
    }

    /** 落库 payload 回读（SEND_MESSAGE 落库形必是 JSON 对象，解析失败即用例自身错误） */
    private JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试构造/捕获的 JSON 解析失败: " + json, e);
        }
    }

    /** SEND_MESSAGE 建任务前置第一关：账号存在（requireAccountExists 走 accountMapper.selectCount） */
    private void stubAccountExists() {
        given(accountMapper.selectCount(any())).willReturn(1L);
    }

    /** 防堆积插入一次成功：返回行 id（createTask 透传给调用方） */
    private void stubInsertAccepted() {
        given(taskMapper.insertTaskIgnoreReturnIdMax(any(), any(), any(), anyInt(), any(), any(), any()))
                .willReturn(List.of(TASK_ID));
    }

    /** SEND_MESSAGE payload 违规统一断言：10102 + 消息点名违规点（防静默吞错 / 防误报其它码） */
    private void assertPayloadRejected(ThrowingCallable call, String... expectedHints) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).as("SEND_MESSAGE payload 违规 → 10102").isEqualTo(10102);
            assertThat(e.getMessage()).as("错误消息须点名违规点").contains(expectedHints);
        });
    }

    // ================================================================ A. 纯函数

    @Test
    void decideFailTargetRetryableWithBudgetLeftGoesBackToPending() {
        assertThat(TaskService.decideFailTarget(true, 1, 3))
                .as("可重试且 attempts=max-1 → 回 PENDING").isEqualTo(TaskService.ST_PENDING);
    }

    @Test
    void decideFailTargetRetryableButBudgetExhaustedGoesDead() {
        assertThat(TaskService.decideFailTarget(true, 3, 3))
                .as("可重试但 attempts=max → DEAD").isEqualTo(TaskService.ST_DEAD);
    }

    @Test
    void decideFailTargetNotRetryableGoesDeadImmediately() {
        assertThat(TaskService.decideFailTarget(false, 0, 3))
                .as("不可重试（契约错误）→ 直接 DEAD").isEqualTo(TaskService.ST_DEAD);
    }

    @Test
    void computeNextMaterializeAtIntervalAddsSeconds() {
        assertThat(TaskService.computeNextMaterializeAt(schedule("*", "*", 300), T))
                .as("interval 路径 = now + interval").isEqualTo(T.plusSeconds(300));
    }

    @Test
    void computeNextMaterializeAtDailyLaterTodayStaysToday() {
        WxTaskScheduleEntity s = schedule("*", "*", null);
        s.setDailyAt(LocalTime.of(9, 0));
        assertThat(TaskService.computeNextMaterializeAt(s, T))
                .as("now=08:00、daily=09:00 → 今天 09:00")
                .isEqualTo(OffsetDateTime.parse("2026-11-10T09:00:00+08:00"));
    }

    @Test
    void computeNextMaterializeAtDailyPastRollsToTomorrow() {
        WxTaskScheduleEntity s = schedule("*", "*", null);
        s.setDailyAt(LocalTime.of(7, 0));
        assertThat(TaskService.computeNextMaterializeAt(s, T))
                .as("now=08:00、daily=07:00 → 明天 07:00")
                .isEqualTo(OffsetDateTime.parse("2026-11-11T07:00:00+08:00"));
    }

    @Test
    void computeNextMaterializeAtManualOnlyStaysNull() {
        assertThat(TaskService.computeNextMaterializeAt(schedule("*", "*", null), T))
                .as("interval/daily 皆空 = 仅手工 → null（不推进）").isNull();
    }

    // ================================================================ B. claim

    @Test
    void claimMapsVoFieldsAndInsertsOneRunRowPerClaimedTask() {
        WxTaskEntity t1 = task(TaskService.ST_PENDING, LEASE, 1, 3);
        t1.setId(101L);
        t1.setLeaseUntil(T);
        WxTaskEntity t2 = task(TaskService.ST_PENDING, "lease-2", 2, 3);
        t2.setId(102L);
        given(taskMapper.claimTasks(eq(ACCOUNT), eq(2), eq(600), isNull()))
                .willReturn(List.of(t1, t2));

        List<TaskClaimVO> vos = service.claim(ACCOUNT, 2, null);

        assertThat(vos).hasSize(2).as("认领两条");
        assertThat(vos.get(0).taskId()).isEqualTo(101L);
        assertThat(vos.get(0).taskType()).isEqualTo(TaskService.TT_SYNC_MESSAGES);
        assertThat(vos.get(0).target()).isEqualTo("t9test_chat1");
        assertThat(vos.get(0).leaseId()).isEqualTo(LEASE);
        assertThat(vos.get(0).leaseUntil()).isEqualTo(T);
        assertThat(vos.get(0).attempts()).isEqualTo(1);
        ArgumentCaptor<WxTaskRunEntity> runs = ArgumentCaptor.forClass(WxTaskRunEntity.class);
        verify(taskRunMapper, times(2)).insert(runs.capture());
        assertThat(runs.getAllValues()).extracting(WxTaskRunEntity::getTaskId, WxTaskRunEntity::getAttemptNo,
                        WxTaskRunEntity::getLeaseId, WxTaskRunEntity::getAccount, WxTaskRunEntity::getResult)
                .satisfies(vs -> assertThat(vs.get(0)).isEqualTo(tuple(101L, 1, LEASE, ACCOUNT, "{}")))
                .satisfies(vs -> assertThat(vs.get(1)).isEqualTo(tuple(102L, 2, "lease-2", ACCOUNT, "{}")));
    }

    @Test
    void claimClampsMaxTasksToDefaultsAndCeiling() {
        given(taskMapper.claimTasks(any(), anyInt(), anyInt(), isNull())).willReturn(List.of());
        service.claim(ACCOUNT, null, null);
        verify(taskMapper).claimTasks(ACCOUNT, 1, 600, null);
        service.claim(ACCOUNT, 0, null);
        verify(taskMapper, times(2)).claimTasks(ACCOUNT, 1, 600, null);
        service.claim(ACCOUNT, -3, null);
        verify(taskMapper, times(3)).claimTasks(ACCOUNT, 1, 600, null);
        // Mockito 累计计数：15→钳到 10 是 (…,10,600,null) 形状的首次调用，默认 times(1)
        service.claim(ACCOUNT, 15, null);
        verify(taskMapper).claimTasks(ACCOUNT, 10, 600, null);
    }

    @Test
    void claimPassesWhitelistedTypesAsCsv() {
        given(taskMapper.claimTasks(any(), anyInt(), anyInt(), any())).willReturn(List.of());
        service.claim(ACCOUNT, 1, List.of(TaskService.TT_SYNC_MESSAGES, TaskService.TT_PROBE));
        verify(taskMapper).claimTasks(ACCOUNT, 1, 600, "SYNC_MESSAGES,PROBE");
    }

    @Test
    void claimRejectsUnknownTaskTypeBeforeTouchingQueue() {
        assertThatThrownBy(() -> service.claim(ACCOUNT, 1, List.of("NOPE")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).as("未知 task_type → 10102").isEqualTo(10102));
        verifyNoInteractions(taskMapper, taskRunMapper);
    }

    // ================================================================ C. complete

    @Test
    void completeThrowsNotFoundWhenTaskMissing() {
        given(taskMapper.selectById(TASK_ID)).willReturn(null);
        assertThatThrownBy(() -> service.complete(ACCOUNT, TASK_ID, LEASE, "{}"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).as("任务不存在 → 10400").isEqualTo(10400));
    }

    @Test
    void completeReplaysSameLeaseTerminalWithoutUpdateOrRunClose() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task(TaskService.ST_SUCCEEDED, LEASE, 1, 3));

        TaskReportResult r = service.complete(ACCOUNT, TASK_ID, LEASE, "{\"n\":9}");

        assertThat(r.replayed()).as("同 lease 终态 → 重放").isTrue();
        assertThat(r.taskStatus()).isEqualTo(TaskService.ST_SUCCEEDED);
        assertThat(r.result()).as("重放返回既有 result").isEqualTo("{\"n\":1}");
        verify(taskMapper, never()).reportTerminal(any(), anyLong(), any(), any(), any());
        verifyNoInteractions(taskRunMapper);
    }

    @Test
    void completeThrowsLeaseLostWhenTerminalUnderDifferentLease() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task(TaskService.ST_SUCCEEDED, "other", 1, 3));
        assertThatThrownBy(() -> service.complete(ACCOUNT, TASK_ID, LEASE, "{}"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).as("异 lease 摸终态行 → 10603").isEqualTo(10603));
    }

    @Test
    void completeWritesTerminalAndClosesRunWhenConditionalUpdateHits() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task(TaskService.ST_DISPATCHED, LEASE, 1, 3));
        given(taskMapper.reportTerminal(eq(TaskService.ST_SUCCEEDED), eq(TASK_ID), eq(LEASE),
                eq("{\"ok\":true}"), isNull())).willReturn(1);

        TaskReportResult r = service.complete(ACCOUNT, TASK_ID, LEASE, "{\"ok\":true}");

        assertThat(r.replayed()).isFalse();
        assertThat(r.taskStatus()).isEqualTo(TaskService.ST_SUCCEEDED);
        assertThat(r.result()).isEqualTo("{\"ok\":true}");
        verify(taskRunMapper).closeRun(TASK_ID, LEASE, TaskService.OUTCOME_SUCCEEDED, "{\"ok\":true}", null);
    }

    @Test
    void completeRequalifiesToLeaseLostWhenUpdateMissedAndRowReassigned() {
        given(taskMapper.selectById(TASK_ID)).willReturn(
                task(TaskService.ST_DISPATCHED, LEASE, 1, 3),
                task(TaskService.ST_RUNNING, "new-lease", 2, 3));
        given(taskMapper.reportTerminal(any(), anyLong(), any(), any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.complete(ACCOUNT, TASK_ID, LEASE, "{}"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).as("条件未命中且已易主 → 10603").isEqualTo(10603));
        verify(taskRunMapper, never()).closeRun(anyLong(), any(), any(), any(), any());
    }

    @Test
    void completeRejectsNonObjectJsonBeforeReadingTask() {
        assertThatThrownBy(() -> service.complete(ACCOUNT, TASK_ID, LEASE, "[1,2]"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).as("result 非 JSON 对象 → 10102").isEqualTo(10102));
        verifyNoInteractions(taskMapper, taskRunMapper);
    }

    // ================================================================ D. fail

    @Test
    void failWithRetryBudgetLeftBacksToPendingAndClearsLease() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task(TaskService.ST_DISPATCHED, LEASE, 1, 3));
        given(taskMapper.failBackToPending(TASK_ID, LEASE, "io")).willReturn(1);

        TaskReportResult r = service.fail(ACCOUNT, TASK_ID, LEASE, "io", true);

        assertThat(r.replayed()).isFalse();
        assertThat(r.taskStatus()).as("重试额度未用尽 → 回队").isEqualTo(TaskService.ST_PENDING);
        assertThat(r.error()).isEqualTo("io");
        verify(taskRunMapper).closeRun(TASK_ID, LEASE, TaskService.OUTCOME_FAILED, null, "io");
    }

    @Test
    void failWithoutRetryBudgetGoesDeadAndKeepsLeaseForReplay() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task(TaskService.ST_DISPATCHED, LEASE, 3, 3));
        given(taskMapper.failToDead(TASK_ID, LEASE, "io")).willReturn(1);

        TaskReportResult r = service.fail(ACCOUNT, TASK_ID, LEASE, "io", true);

        assertThat(r.taskStatus()).as("attempts=max → DEAD").isEqualTo(TaskService.ST_DEAD);
        verify(taskRunMapper).closeRun(TASK_ID, LEASE, TaskService.OUTCOME_FAILED, null, "io");
        verify(taskMapper, never()).failBackToPending(anyLong(), any(), any());
    }

    @Test
    void failFallsBackToDeadWhenPendingUpdateMissesBudget() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task(TaskService.ST_DISPATCHED, LEASE, 1, 3));
        given(taskMapper.failBackToPending(TASK_ID, LEASE, "io")).willReturn(0);
        given(taskMapper.failToDead(TASK_ID, LEASE, "io")).willReturn(1);

        TaskReportResult r = service.fail(ACCOUNT, TASK_ID, LEASE, "io", true);

        assertThat(r.taskStatus()).as("回队条件未命中（并发推线）→ 防御改判 DEAD").isEqualTo(TaskService.ST_DEAD);
        verify(taskRunMapper).closeRun(TASK_ID, LEASE, TaskService.OUTCOME_FAILED, null, "io");
    }

    @Test
    void failReportsReplayWhenBothUpdatesMissAndRowWasAlreadyTerminal() {
        given(taskMapper.selectById(TASK_ID)).willReturn(
                task(TaskService.ST_DISPATCHED, LEASE, 1, 3),
                task(TaskService.ST_DEAD, LEASE, 3, 3));
        given(taskMapper.failBackToPending(anyLong(), any(), any())).willReturn(0);
        given(taskMapper.failToDead(anyLong(), any(), any())).willReturn(0);

        TaskReportResult r = service.fail(ACCOUNT, TASK_ID, LEASE, "io", true);

        assertThat(r.replayed()).as("两次条件 UPDATE 都 0 行且行已终态（同 lease）→ 重放").isTrue();
        assertThat(r.taskStatus()).isEqualTo(TaskService.ST_DEAD);
        assertThat(r.error()).as("重放返回既有 error").isEqualTo("boom");
    }

    // ================================================================ E. renew

    @Test
    void renewTreatsSameLeaseTerminalAsNoOpReplay() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task(TaskService.ST_SUCCEEDED, LEASE, 1, 3));

        TaskReportResult r = service.renew(ACCOUNT, TASK_ID, LEASE);

        assertThat(r.replayed()).as("终态后迟到 renew（网络重试）→ no-op 成功").isTrue();
        verify(taskMapper, never()).renewLease(anyLong(), any(), anyInt());
    }

    @Test
    void renewExtendsLeaseAndMarksRunningWhenActive() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task(TaskService.ST_DISPATCHED, LEASE, 1, 3));
        given(taskMapper.renewLease(TASK_ID, LEASE, 600)).willReturn(1);

        TaskReportResult r = service.renew(ACCOUNT, TASK_ID, LEASE);

        assertThat(r.replayed()).isFalse();
        assertThat(r.taskStatus()).as("续租后观测态 RUNNING").isEqualTo(TaskService.ST_RUNNING);
    }

    @Test
    void renewThrowsLeaseLostWhenConditionalUpdateMisses() {
        given(taskMapper.selectById(TASK_ID)).willReturn(
                task(TaskService.ST_DISPATCHED, LEASE, 1, 3),
                task(TaskService.ST_DISPATCHED, "new-lease", 2, 3));
        given(taskMapper.renewLease(anyLong(), any(), anyInt())).willReturn(0);

        assertThatThrownBy(() -> service.renew(ACCOUNT, TASK_ID, LEASE))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).as("租约已易主 → 10603").isEqualTo(10603));
    }

    // ================================================================ F. 回收巡检

    @Test
    void sweepSplitsExpiredRowsBetweenPendingAndDeadAndMarksRunsLost() {
        WxTaskEntity e1 = task(TaskService.ST_DISPATCHED, "lease-a", 1, 3);
        e1.setId(501L);
        WxTaskEntity e2 = task(TaskService.ST_DISPATCHED, "lease-b", 3, 3);
        e2.setId(502L);
        given(taskMapper.selectExpiredForUpdate()).willReturn(List.of(e1, e2));

        int swept = service.sweepExpiredLeases();

        assertThat(swept).as("回收行数 = 过期行数").isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<WxTaskEntity>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskMapper, times(2)).update(isNull(), updates.capture());
        assertThat(updates.getAllValues().get(0).getParamNameValuePairs())
                .as("第一行 attempts<max → 打回 PENDING + 记 lease expired").containsValue(TaskService.ST_PENDING)
                .containsValue("lease expired");
        assertThat(updates.getAllValues().get(1).getParamNameValuePairs())
                .as("第二行 attempts=max → 置 DEAD").containsValue(TaskService.ST_DEAD);
        verify(taskRunMapper).markRunLost(501L, "lease-a", "lease expired");
        verify(taskRunMapper).markRunLost(502L, "lease-b", "lease expired");
        verify(taskRunMapper, never()).closeRun(anyLong(), any(), any(), any(), any());
    }

    @Test
    void sweepReturnsZeroWithoutTouchingMappersWhenNothingExpired() {
        given(taskMapper.selectExpiredForUpdate()).willReturn(List.of());
        assertThat(service.sweepExpiredLeases()).isZero();
        verify(taskMapper, never()).update(any(), any());
        verifyNoInteractions(taskRunMapper);
    }

    // ================================================================ G. 账号归属

    @Test
    void claimFastFailsWhenTokenAccountMismatched() {
        doThrow(new ApiException(ApiCode.FORBIDDEN, "账号不一致")).when(accountBinding).checkBound("ghost");
        assertThatThrownBy(() -> service.claim("ghost", 1, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).as("token↔account 不一致 → 10300").isEqualTo(10300));
        verifyNoInteractions(taskMapper, taskRunMapper);
    }

    @Test
    void completeFastFailsWhenTokenAccountMismatched() {
        doThrow(new ApiException(ApiCode.FORBIDDEN, "账号不一致")).when(accountBinding).checkBound("ghost");
        assertThatThrownBy(() -> service.complete("ghost", TASK_ID, LEASE, "{}"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(10300));
        verifyNoInteractions(taskMapper, taskRunMapper);
    }

    // ================================================================ H. 物化

    @Test
    void materializeSyncMessagesExpandsActiveChatsWithChatPayload() {
        given(scheduleMapper.selectList(any())).willReturn(List.of(schedule(
                TaskService.TT_SYNC_MESSAGES, TaskService.TARGET_ALL, 300)));
        given(conversationMapper.selectList(any()))
                .willReturn(List.of(convo("t9test_c1"), convo("t9test_c2"), convo("t9test_c3")));
        given(taskMapper.insertTaskIgnoreReturnId(any(), any(), any(), anyInt(), any(), any()))
                .willReturn(List.of(11L), List.of(12L), List.of(13L));

        int produced = service.materializeDueSchedules(T);

        assertThat(produced).as("活跃会话逐个物化").isEqualTo(3);
        ArgumentCaptor<String> chats = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(taskMapper, times(3)).insertTaskIgnoreReturnId(eq(TaskService.TT_SYNC_MESSAGES),
                eq(ACCOUNT), chats.capture(), anyInt(), payloads.capture(), eq(1L));
        assertThat(chats.getAllValues()).containsExactly("t9test_c1", "t9test_c2", "t9test_c3");
        assertThat(payloads.getAllValues()).allSatisfy(p -> assertThat(p).as("payload 并入 chat").contains("\"chat\":\"t9test_c"));
    }

    @Test
    void materializeFullScanRotatesOldestScannedFirst() {
        given(scheduleMapper.selectList(any())).willReturn(List.of(schedule(
                TaskService.TT_FULL_SCAN, TaskService.TARGET_ALL, null)));
        given(taskMapper.selectChatsForFullScan(ACCOUNT, 2))
                .willReturn(List.of("t9test_f1", "t9test_f2"));
        given(taskMapper.insertTaskIgnoreReturnId(any(), any(), any(), anyInt(), any(), any()))
                .willReturn(List.of(21L), List.of(22L));

        int produced = service.materializeDueSchedules(T);

        assertThat(produced).as("FULL_SCAN '*' 每账号每轮取 K 个").isEqualTo(2);
        verify(taskMapper).selectChatsForFullScan(ACCOUNT, 2);
        verify(taskMapper, times(2)).insertTaskIgnoreReturnId(eq(TaskService.TT_FULL_SCAN),
                eq(ACCOUNT), any(), anyInt(), any(), eq(1L));
    }

    @Test
    void materializeSyncRelationsIsOneDomainWideTask() {
        given(scheduleMapper.selectList(any())).willReturn(List.of(schedule(
                TaskService.TT_SYNC_RELATIONS, TaskService.TARGET_ALL, 86400)));
        given(taskMapper.insertTaskIgnoreReturnId(any(), any(), any(), anyInt(), any(), any()))
                .willReturn(List.of(31L));

        int produced = service.materializeDueSchedules(T);

        assertThat(produced).as("全域任务一条").isEqualTo(1);
        verify(taskMapper).insertTaskIgnoreReturnId(eq(TaskService.TT_SYNC_RELATIONS),
                eq(ACCOUNT), eq(TaskService.TARGET_ALL), anyInt(), any(), eq(1L));
        verifyNoInteractions(conversationMapper);
    }

    @Test
    void materializeLiteralTargetSkipsExpansionAndInsertsOnce() {
        given(scheduleMapper.selectList(any())).willReturn(List.of(schedule(
                TaskService.TT_BLOB_UPLOAD, "t9test_chat9", 3600)));
        given(taskMapper.insertTaskIgnoreReturnId(any(), any(), any(), anyInt(), any(), any()))
                .willReturn(List.of(41L));

        int produced = service.materializeDueSchedules(T);

        assertThat(produced).isEqualTo(1);
        verify(taskMapper).insertTaskIgnoreReturnId(eq(TaskService.TT_BLOB_UPLOAD),
                eq(ACCOUNT), eq("t9test_chat9"), anyInt(), any(), eq(1L));
        verifyNoInteractions(conversationMapper);
        verify(taskMapper, never()).selectChatsForFullScan(any(), anyInt());
    }

    @Test
    void materializeStopsExpandingAccountsOnceBatchCapReached() {
        TaskService tight = new TaskService(taskMapper, taskRunMapper, scheduleMapper, accountMapper,
                conversationMapper, accountBinding, new ObjectMapper(), 600, 2, 2);
        WxTaskScheduleEntity s = schedule(TaskService.TT_SYNC_MESSAGES, TaskService.TARGET_ALL, 300);
        s.setAccount(null);
        given(scheduleMapper.selectList(any())).willReturn(List.of(s));
        given(accountMapper.selectList(any())).willReturn(List.of(acct("wxid_t9test_a"), acct("wxid_t9test_b")));
        given(conversationMapper.selectList(any()))
                .willReturn(List.of(convo("t9test_c1"), convo("t9test_c2"), convo("t9test_c3")));
        given(taskMapper.insertTaskIgnoreReturnId(any(), any(), any(), anyInt(), any(), any()))
                .willReturn(List.of(51L), List.of(52L), List.of(53L));

        int produced = tight.materializeDueSchedules(T);

        assertThat(produced).isEqualTo(3);
        verify(conversationMapper, times(1)).selectList(any());
    }

    @Test
    void materializeIntervalSchedulePushesNextMaterializeAtWithOptimisticLock() {
        given(scheduleMapper.selectList(any())).willReturn(List.of(schedule(
                TaskService.TT_SYNC_RELATIONS, TaskService.TARGET_ALL, 86400)));
        given(taskMapper.insertTaskIgnoreReturnId(any(), any(), any(), anyInt(), any(), any()))
                .willReturn(List.of(61L));

        service.materializeDueSchedules(T);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<WxTaskScheduleEntity>> push =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(scheduleMapper).update(isNull(), push.capture());
        assertThat(push.getValue().getParamNameValuePairs())
                .as("next_materialize_at = now + interval（旧值一致性乐观锁另含旧值参数）")
                .containsValue(T.plusSeconds(86400));
    }

    @Test
    void materializeManualOnlyScheduleDoesNotPushNextMaterializeAt() {
        given(scheduleMapper.selectList(any())).willReturn(List.of(schedule(
                TaskService.TT_PROBE, TaskService.TARGET_ALL, null)));
        given(taskMapper.insertTaskIgnoreReturnId(any(), any(), any(), anyInt(), any(), any()))
                .willReturn(List.of(71L));

        assertThat(service.materializeDueSchedules(T)).isEqualTo(1);
        verify(scheduleMapper, never()).update(any(), any());
    }

    // ================================================================ I. SEND_MESSAGE（T11 发消息任务）

    @Test
    void sendMessageTypeJoinsTaskTypeUniverse() {
        assertThat(TaskService.TT_SEND_MESSAGE).as("常量值钉死（客户端按字面量认领）").isEqualTo("SEND_MESSAGE");
        assertThat(TaskService.TASK_TYPES).as("类型全域（claim 白名单与建任务共用）须纳入 SEND_MESSAGE")
                .contains(TaskService.TT_SEND_MESSAGE);
    }

    @Test
    void claimAcceptsSendMessageTypeFilter() {
        given(taskMapper.claimTasks(any(), anyInt(), anyInt(), any())).willReturn(List.of());

        assertThatCode(() -> service.claim(ACCOUNT, 1, List.of(TaskService.TT_SEND_MESSAGE)))
                .as("SEND_MESSAGE 已入全域 → claim 过滤合法（不抛 10102）").doesNotThrowAnyException();
        verify(taskMapper).claimTasks(ACCOUNT, 1, 600, "SEND_MESSAGE");
    }

    @Test
    void createSendMessageTaskStoresCanonicalTwoKeyPayload() {
        stubAccountExists();
        stubInsertAccepted();

        long id = service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "47931406650@chatroom", 0,
                "{\"chat_username\":\"47931406650@chatroom\",\"text\":\"收到\"}", 3);

        assertThat(id).as("防堆积插入命中 → 返回新任务 id").isEqualTo(TASK_ID);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).insertTaskIgnoreReturnIdMax(eq(TaskService.TT_SEND_MESSAGE), eq(ACCOUNT),
                eq("47931406650@chatroom"), eq(0), payloads.capture(), isNull(), eq(3));
        assertThat(payloads.getValue())
                .as("落库规范形：紧凑 JSON、键序确定（chat_username → text）")
                .isEqualTo("{\"chat_username\":\"47931406650@chatroom\",\"text\":\"收到\"}");
        assertThat(parse(payloads.getValue()).fieldNames()).as("只允许两键")
                .toIterable().containsExactlyInAnyOrder("chat_username", "text");
    }

    @Test
    void createSendMessageTaskAcceptsFilehelperAndWxidFormsAndTrimsChatOnly() {
        given(accountMapper.selectCount(any())).willReturn(1L);
        given(taskMapper.insertTaskIgnoreReturnIdMax(any(), any(), any(), anyInt(), any(), any(), any()))
                .willReturn(List.of(123L), List.of(124L));

        service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                sendMessagePayload("  filehelper ", " 收到，请查收 "), 3);
        service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "wxid_abc123", 0,
                sendMessagePayload("wxid_abc123", "你好"), 3);

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(taskMapper, times(2)).insertTaskIgnoreReturnIdMax(eq(TaskService.TT_SEND_MESSAGE), eq(ACCOUNT),
                any(), anyInt(), payloads.capture(), isNull(), eq(3));
        JsonNode filehelper = parse(payloads.getAllValues().get(0));
        assertThat(filehelper.path("chat_username").asText())
                .as("chat_username 落库前去首尾空白（校验与存储同口径）").isEqualTo("filehelper");
        assertThat(filehelper.path("text").asText())
                .as("text 存储保留原文——校验才用 trim").isEqualTo(" 收到，请查收 ");
        JsonNode wxid = parse(payloads.getAllValues().get(1));
        assertThat(wxid.path("chat_username").asText())
                .as("wxid_ 前缀 = 第三种可定位形态").isEqualTo("wxid_abc123");
        assertThat(wxid.path("text").asText()).isEqualTo("你好");
    }

    @Test
    void createSendMessageTaskAcceptsChatAt64CharUpperBound() {
        stubAccountExists();
        stubInsertAccepted();
        String chat = "wxid_" + "a".repeat(59);   // 恰 64 字符

        long id = service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, chat, 0,
                sendMessagePayload(chat, "ok"), 3);

        assertThat(id).isEqualTo(TASK_ID);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).insertTaskIgnoreReturnIdMax(eq(TaskService.TT_SEND_MESSAGE), eq(ACCOUNT),
                eq(chat), eq(0), payloads.capture(), isNull(), eq(3));
        assertThat(parse(payloads.getValue()).path("chat_username").asText()).isEqualTo(chat);
    }

    @Test
    void createSendMessageTaskRejectsChatOver64Chars() {
        stubAccountExists();
        String chat = "wxid_" + "a".repeat(60);   // 65 字符

        assertPayloadRejected(
                () -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, chat, 0,
                        sendMessagePayload(chat, "ok"), 3),
                "chat_username", "64");

        verifyNoInteractions(taskMapper);
    }

    @Test
    void createSendMessageTaskRejectsUnaddressableChatForm() {
        stubAccountExists();

        // 数字开头 / 含空格 / 含点号——明显非 WeChat id 形态仍拒绝
        assertPayloadRejected(
                () -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "1chat", 0,
                        sendMessagePayload("1chat", "ok"), 3),
                "chat_username", "1chat");
        assertPayloadRejected(
                () -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "bad chat", 0,
                        sendMessagePayload("bad chat", "ok"), 3),
                "chat_username", "bad chat");
        assertPayloadRejected(
                () -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "foo.bar", 0,
                        sendMessagePayload("foo.bar", "ok"), 3),
                "chat_username", "foo.bar");

        verifyNoInteractions(taskMapper);
    }

    /** 老式自定义 id（无 wxid_ 前缀）可下发——live 2026-11-23: p5soft/朱家田 触发回复被 10102 挡。 */
    @Test
    void createSendMessageTaskAcceptsLegacyCustomId() {
        stubAccountExists();
        stubInsertAccepted();

        long id = service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "p5soft", 0,
                sendMessagePayload("p5soft", "好的"), 3);

        assertThat(id).isEqualTo(TASK_ID);
        verify(taskMapper).insertTaskIgnoreReturnIdMax(eq(TaskService.TT_SEND_MESSAGE), eq(ACCOUNT),
                eq("p5soft"), eq(0), anyString(), isNull(), eq(3));
    }

    @Test
    void createSendMessageTaskRejectsMissingBlankOrWhitespaceChatUsername() {
        stubAccountExists();

        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "*", 0,
                sendMessagePayload(null, "ok"), 3), "chat_username");
        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "*", 0,
                sendMessagePayload("", "ok"), 3), "chat_username");
        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "*", 0,
                sendMessagePayload("   \n\t ", "ok"), 3), "chat_username");
        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "*", 0,
                null, 3), "chat_username");

        verifyNoInteractions(taskMapper);
    }

    @Test
    void createSendMessageTaskRejectsMissingBlankOrWhitespaceText() {
        stubAccountExists();

        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                sendMessagePayload("filehelper", null), 3), "text");
        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                sendMessagePayload("filehelper", ""), 3), "text");
        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                sendMessagePayload("filehelper", " \t \n"), 3), "text");

        verifyNoInteractions(taskMapper);
    }

    @Test
    void createSendMessageTaskRejectsTextOver2000CharsAfterTrim() {
        stubAccountExists();

        assertPayloadRejected(
                () -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                        sendMessagePayload("filehelper", "a".repeat(2001)), 3),
                "text");

        verifyNoInteractions(taskMapper);
    }

    @Test
    void createSendMessageTaskKeepsTextVerbatimAt2000CharTrimBoundary() {
        stubAccountExists();
        stubInsertAccepted();
        String text = " " + "a".repeat(2000) + " ";   // 原文 2002、trim 后恰 2000

        long id = service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                sendMessagePayload("filehelper", text), 3);

        assertThat(id).isEqualTo(TASK_ID);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).insertTaskIgnoreReturnIdMax(eq(TaskService.TT_SEND_MESSAGE), eq(ACCOUNT),
                eq("filehelper"), eq(0), payloads.capture(), isNull(), eq(3));
        assertThat(parse(payloads.getValue()).path("text").asText())
                .as("边界合法且存储不 trim——首尾空白可能是发送意图的一部分").isEqualTo(text);
    }

    @Test
    void createSendMessageTaskRejectsNonObjectPayload() {
        stubAccountExists();

        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                "[1,2]", 3), "JSON 对象");
        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                "\"hi\"", 3), "JSON 对象");
        assertPayloadRejected(() -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                "not-json", 3), "不是合法 JSON");

        verifyNoInteractions(taskMapper);
    }

    @Test
    void createSendMessageTaskRejectsUnknownPayloadKeysAndNamesThem() {
        stubAccountExists();

        assertPayloadRejected(
                () -> service.createTask(ACCOUNT, TaskService.TT_SEND_MESSAGE, "filehelper", 0,
                        "{\"chat_username\":\"filehelper\",\"text\":\"hi\",\"extra\":1,\"oops\":2}", 3),
                "extra", "oops");

        verifyNoInteractions(taskMapper);
    }

    @Test
    void createScheduleRejectsSendMessageTypeBeforeTouchingPersistence() {
        assertThatThrownBy(() -> service.createSchedule(ACCOUNT, TaskService.TT_SEND_MESSAGE, "*",
                300, null, 0, "{}", 30, true))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).as("SEND_MESSAGE 禁入计划 → 10102").isEqualTo(10102);
                    assertThat(e.getMessage()).as("点名该类型不支持计划").contains("SEND_MESSAGE 不支持计划");
                });
        verifyNoInteractions(scheduleMapper, accountMapper, taskMapper);
    }

    @Test
    void createTaskKeepsLoosePayloadValidationForLegacyTypes() {
        stubAccountExists();
        stubInsertAccepted();

        long id = service.createTask(ACCOUNT, TaskService.TT_SYNC_MESSAGES, "t9test_chat1", 0, "{}", 3);

        assertThat(id).isEqualTo(TASK_ID);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).insertTaskIgnoreReturnIdMax(eq(TaskService.TT_SYNC_MESSAGES), eq(ACCOUNT),
                eq("t9test_chat1"), eq(0), payloads.capture(), isNull(), eq(3));
        assertThat(payloads.getValue())
                .as("旧类型 payload 原样透传，不受 SEND_MESSAGE 两键校验误伤").isEqualTo("{}");
    }
}
