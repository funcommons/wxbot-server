package com.wechat.sync.server.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.entity.WxRegCodeEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.account.mapper.WxRegCodeMapper;
import com.wechat.sync.server.account.service.RegCodeService;
import com.wechat.sync.server.account.dto.RegCodeIssueVO;
import com.wechat.sync.server.auth.dto.RegisterRequest;
import com.wechat.sync.server.message.entity.WxSyncCursorEntity;
import com.wechat.sync.server.message.mapper.WxSyncCursorMapper;
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
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务分发真库集成测试（直连真实 PG 5433 / Redis 6379，走真实 Mapper + Service，不起 HTTP）。
 *
 * <p>验证单测 mock 不到的持久化语义与并发原子性：claim 的 FOR UPDATE SKIP LOCKED 原子认领（恰一
 * 胜者、不重复派发）、物化撞 uk_wx_task_active_target 的 ON CONFLICT 防堆积、物化上限与
 * FULL_SCAN 轮转排序（NULLS FIRST + 最久未全扫）、回收巡检的 PENDING/DEAD 分流与 run 日志
 * LOST（finished_at 保持 NULL，schema §2.3 口径）、注册码并发双消费恰一成功、终态行保留
 * lease_id 的重放判定（TASK_DISPATCH §4 v1.1）。
 *
 * <p><b>数据卫生（最高优先级）</b>：
 * <ul>
 *   <li>前缀隔离：账号 {@code wxid_t9it_}、会话 {@code t9it_}、注册码 label {@code t9it}；</li>
 *   <li>wx_account 只删 {@code LIKE 'wxid_t9it_%'}——真库有 5 行真实账号，绝不可全删；</li>
 *   <li>wx_task_schedule 只删 {@code id > 3}——种子计划 id 1/2/3 是生产计划；</li>
 *   <li>种子在 @BeforeAll 临时 {@code enabled=0}（materializeDueSchedules 会命中到期种子并把
 *       next_materialize_at 推走 = 改生产状态；测试期间旧版服务无调度器，停用无副作用），
 *       @AfterAll 恢复 enabled=1 并把 next_materialize_at 拨到 {@code now()+interval}（一个自然
 *       周期后首跑——语义等价于正常物化节奏，且避免本 JVM 残留调度线程立即物化生产种子）；</li>
 *   <li>wx_reg_code 只删 {@code label LIKE 't9it%'}（签发时统一打此 label）；</li>
 *   <li>不 TRUNCATE、不动 v4 表的既有行；@BeforeEach 全量清测试前缀，保证物化三连测互不污染。</li>
 * </ul>
 *
 * <p><b>调度竞态容忍</b>：本上下文里 TaskSchedulerConfig 的 @Scheduled 是活的（15s/30s 首跑），
 * 业务代码不可改 → 回收/物化用例一律断言<b>最终库态</b>而非返回值；测试计划的到期时间先插成
 * 未来、造数完再拨 now()，把「调度线程抢先物化」的窗口压到亚毫秒（两路径产出确定性一致）。
 * 客户端路径（claim/complete）需要 token claims：测试线程/工作线程各自
 * {@code TokenContext.set("client", Map.of("account", ACC))}，finally clear。
 */
@SpringBootTest(properties = "spring.test.context.failure.threshold=2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskDispatchIntegrationTest {

    private static final String ACC_PREFIX = "wxid_t9it_";
    private static final String ACC = ACC_PREFIX + "main";
    private static final String CHAT_PREFIX = "t9it_";
    /** 注册码统一 label：purge 的唯一安全锚点（真库管理员的码不会有此 label） */
    private static final String REG_LABEL = "t9it";

    @Autowired private TaskService taskService;
    @Autowired private RegCodeService regCodeService;
    @Autowired private WxTaskMapper taskMapper;
    @Autowired private WxTaskRunMapper taskRunMapper;
    @Autowired private WxTaskScheduleMapper scheduleMapper;
    @Autowired private WxAccountMapper accountMapper;
    @Autowired private WxConversationMapper conversationMapper;
    @Autowired private WxSyncCursorMapper cursorMapper;
    @Autowired private WxRegCodeMapper regCodeMapper;
    @Autowired private DataSource dataSource;

    @BeforeAll
    void setUpClass() throws SQLException {
        purge();
        disableSeeds();
    }

    @AfterAll
    void tearDownClass() throws SQLException {
        restoreSeeds();
        purge();
    }

    /** 用例间全量隔离：物化三连测共用 wx_conversation，不清会互相污染轮转候选集。 */
    @BeforeEach
    void fresh() throws SQLException {
        purge();
        TokenContext.clear();
    }

    // ================================================================ 1. claim 并发原子性

    @Test
    void claimConcurrentSingleTaskHasExactlyOneWinner() throws Exception {
        insertTask(TaskService.ST_PENDING, CHAT_PREFIX + "solo", 0, 3, null, null);

        List<Object> outcomes = race(
                () -> claimAsClient(1),
                () -> claimAsClient(1));

        long nonEmpty = outcomes.stream()
                .filter(o -> o instanceof List<?> l && !l.isEmpty())
                .count();
        assertThat(nonEmpty).as("两条并发认领恰好一条拿到任务（SKIP LOCKED 不重复派发）").isEqualTo(1);

        WxTaskEntity row = myTasks().get(0);
        assertThat(row.getStatus()).as("被认领后进入 DISPATCHED").isEqualTo(TaskService.ST_DISPATCHED);
        assertThat(row.getAttempts()).as("认领即 attempts+1").isEqualTo(1);
        assertThat(row.getLeaseId()).as("租约凭据非空").isNotBlank();
        assertThat(row.getLeaseUntil()).as("租约到期非空（claim 响应带 lease_until，v1.1）").isNotNull();

        Long runCount = taskRunMapper.selectCount(new LambdaQueryWrapper<WxTaskRunEntity>()
                .eq(WxTaskRunEntity::getTaskId, row.getId()));
        assertThat(runCount).as("认领事务内恰插 1 条 run 历史行").isEqualTo(1L);
        WxTaskRunEntity run = taskRunMapper.selectOne(new LambdaQueryWrapper<WxTaskRunEntity>()
                .eq(WxTaskRunEntity::getTaskId, row.getId()));
        assertThat(run.getAttemptNo()).as("attempt_no = 认领时点 attempts").isEqualTo(1);
        assertThat(run.getLeaseId()).as("run 行租约与任务一致").isEqualTo(row.getLeaseId());
    }

    @Test
    void claimConcurrentThreeTasksNoDoubleDispatch() throws Exception {
        for (int i = 1; i <= 3; i++) {
            insertTask(TaskService.ST_PENDING, CHAT_PREFIX + "multi" + i, 0, 3, null, null);
        }

        List<Object> outcomes = race(
                () -> claimAsClient(3),
                () -> claimAsClient(3));

        List<Long> claimedIds = new ArrayList<>();
        for (Object o : outcomes) {
            assertThat(o).as("两线程认领都不应报错（SKIP LOCKED 空手而归也是正常返回）")
                    .isInstanceOf(List.class);
            for (Object vo : (List<?>) o) {
                claimedIds.add(((TaskClaimVO) vo).taskId());
            }
        }
        assertThat(claimedIds).as("并集 = 3 条任务全被派出").hasSize(3);
        assertThat(claimedIds.stream().distinct().count())
                .as("交集 = 0：同一任务绝不会被两个客户端同时拿到").isEqualTo(3);

        for (WxTaskEntity row : myTasks()) {
            assertThat(row.getAttempts()).as("每条任务 attempts 恰 +1").isEqualTo(1);
            assertThat(row.getStatus()).isEqualTo(TaskService.ST_DISPATCHED);
        }
        Long runCount = taskRunMapper.selectCount(new LambdaQueryWrapper<WxTaskRunEntity>()
                .in(WxTaskRunEntity::getTaskId, claimedIds));
        assertThat(runCount).as("3 次派发 = 3 条 run 历史").isEqualTo(3L);
    }

    // ================================================================ 2. 物化：防堆积 / 上限 / 轮转

    @Test
    void materializeOnConflictDoesNotStack() throws SQLException {
        long schedId = insertSchedule(TaskService.TT_SYNC_MESSAGES, 300);
        conversation(CHAT_PREFIX + "oncon-a");
        conversation(CHAT_PREFIX + "oncon-b");
        armSchedule(schedId);   // 造数完才拨到期（缩小调度线程抢先窗口）

        taskService.materializeDueSchedules(OffsetDateTime.now());

        assertThat(activeTaskCount(TaskService.TT_SYNC_MESSAGES))
                .as("首轮物化：窗口内 2 会话各 1 条任务").isEqualTo(2);

        // 第二轮：同键活动任务已存在 → ON CONFLICT DO NOTHING → 不新增
        armSchedule(schedId);
        taskService.materializeDueSchedules(OffsetDateTime.now());

        assertThat(activeTaskCount(TaskService.TT_SYNC_MESSAGES))
                .as("重跑物化不堆积（uk_wx_task_active_target 部分唯一索引兜底）").isEqualTo(2);
    }

    @Test
    void materializeRespectsBatchCap() throws SQLException {
        long schedId = insertSchedule(TaskService.TT_SYNC_MESSAGES, 300);
        for (int i = 1; i <= 25; i++) {
            conversation(CHAT_PREFIX + "cap" + i);
        }
        armSchedule(schedId);

        taskService.materializeDueSchedules(OffsetDateTime.now());

        // yml task-materialize-batch-cap=20：25 个候选只产 20（任务风暴兜底，§10）
        assertThat(activeTaskCount(TaskService.TT_SYNC_MESSAGES))
                .as("单计划单轮产出被 batch-cap 钳到 20").isEqualTo(20);
    }

    @Test
    void fullScanRotationPicksNeverScannedThenOldest() throws SQLException {
        long schedId = insertSchedule(TaskService.TT_FULL_SCAN, 604800);
        // 3 个无游标会话（从未全扫 → NULLS FIRST）+ 3 个有游标会话（last_full_scan 新旧各异）
        conversation(CHAT_PREFIX + "nc1");
        conversation(CHAT_PREFIX + "nc2");
        conversation(CHAT_PREFIX + "nc3");
        conversation(CHAT_PREFIX + "sc1");
        conversation(CHAT_PREFIX + "sc2");
        conversation(CHAT_PREFIX + "sc3");
        cursor(CHAT_PREFIX + "sc1", OffsetDateTime.now().minusDays(10));
        cursor(CHAT_PREFIX + "sc2", OffsetDateTime.now().minusDays(5));
        cursor(CHAT_PREFIX + "sc3", OffsetDateTime.now().minusDays(1));
        armSchedule(schedId);

        taskService.materializeDueSchedules(OffsetDateTime.now());

        Set<String> targets = new java.util.HashSet<>();
        for (WxTaskEntity row : myTasks(TaskService.TT_FULL_SCAN)) {
            targets.add(row.getTarget());
        }
        assertThat(targets).as("轮转取 5 个（full-scan-batch）：从未全扫 3 个 + 最久未全扫 2 个"
                        + "（sc3 最近扫过被跳过）")
                .containsExactlyInAnyOrder(
                        CHAT_PREFIX + "nc1", CHAT_PREFIX + "nc2", CHAT_PREFIX + "nc3",
                        CHAT_PREFIX + "sc1", CHAT_PREFIX + "sc2");
    }

    // ================================================================ 3. 回收巡检

    @Test
    void sweepExpiredLeasesSplitsPendingAndDead() throws SQLException {
        // 一条额度未尽（回 PENDING 清租约）+ 一条额度用尽（DEAD）；各自预插 open 的 run 行
        WxTaskEntity pending = insertTask(TaskService.ST_DISPATCHED, CHAT_PREFIX + "sweep-p",
                1, 3, OffsetDateTime.now().minusSeconds(60), "lease-sweep-p");
        WxTaskEntity dead = insertTask(TaskService.ST_DISPATCHED, CHAT_PREFIX + "sweep-d",
                3, 3, OffsetDateTime.now().minusSeconds(60), "lease-sweep-d");
        openRun(pending.getId(), 1, "lease-sweep-p");
        openRun(dead.getId(), 3, "lease-sweep-d");

        taskService.sweepExpiredLeases();   // 断库态不断返回值：调度线程可能抢先回收

        WxTaskEntity afterP = taskMapper.selectById(pending.getId());
        assertThat(afterP.getStatus()).as("额度未尽 → 打回 PENDING 重派").isEqualTo(TaskService.ST_PENDING);
        assertThat(afterP.getLeaseId()).as("回队清租约凭据").isNull();
        assertThat(afterP.getLeaseUntil()).as("回队清租约到期").isNull();
        assertThat(afterP.getLastError()).as("回收标记").isEqualTo("lease expired");

        WxTaskEntity afterD = taskMapper.selectById(dead.getId());
        assertThat(afterD.getStatus()).as("额度用尽 → DEAD 死信").isEqualTo(TaskService.ST_DEAD);

        for (WxTaskEntity t : List.of(afterP, afterD)) {
            WxTaskRunEntity run = taskRunMapper.selectOne(new LambdaQueryWrapper<WxTaskRunEntity>()
                    .eq(WxTaskRunEntity::getTaskId, t.getId()));
            assertThat(run.getOutcome()).as("回收落 run 日志 LOST").isEqualTo(TaskService.OUTCOME_LOST);
            assertThat(run.getFinishedAt()).as("LOST 行 finished_at 保持 NULL（schema §2.3 口径）").isNull();
        }
    }

    // ================================================================ 4. 注册码并发双消费

    @Test
    void registerCodeConcurrentConsumeExactlyOneWinner() throws Exception {
        RegCodeIssueVO issued = regCodeService.issueDetailed(REG_LABEL, null, null);
        String winnerHost = "t9it-host-a";
        String loserHost = "t9it-host-b";

        List<Object> outcomes = race(
                () -> regCodeService.registerWithCode(
                        new RegisterRequest(issued.code(), ACC, "wxid_t9itmain", winnerHost, "it-1.0")),
                () -> regCodeService.registerWithCode(
                        new RegisterRequest(issued.code(), ACC, "wxid_t9itmain", loserHost, "it-1.0")));

        long successes = outcomes.stream().filter(o -> o instanceof String).count();
        assertThat(successes).as("同一注册码并发消费恰一成功").isEqualTo(1);
        Object loser = outcomes.stream().filter(o -> !(o instanceof String)).findFirst().orElseThrow();
        assertThat(loser).as("失败方是 ApiException（并发输家：10401 撞已注册 或 10402 已被并发使用）")
                .isInstanceOf(ApiException.class);
        int loserCode = ((ApiException) loser).getCode();
        assertThat(loserCode).as("失败码在 10401/10402 之内").isIn(10401, 10402);

        Long accountRows = accountMapper.selectCount(new LambdaQueryWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, ACC));
        assertThat(accountRows).as("账号只建一行（失败方整体回滚，不留半行）").isEqualTo(1L);
        WxAccountEntity account = accountMapper.selectOne(new LambdaQueryWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, ACC));
        String usedBy = account.getHostname();
        assertThat(usedBy).as("hostname 是胜者的").isIn(winnerHost, loserHost);

        WxRegCodeEntity code = regCodeMapper.selectById(issued.id());
        assertThat(code.getUsedAt()).as("注册码已消费打点").isNotNull();
        assertThat(code.getUsedBy()).as("消费方与胜者一致").isEqualTo(usedBy);
    }

    // ================================================================ 5. 终态保留 lease_id + 重放

    @Test
    void completeTerminalKeepsLeaseIdAndReplays() throws Exception {
        insertTask(TaskService.ST_PENDING, CHAT_PREFIX + "replay", 0, 3, null, null);
        TokenContext.set("client", Map.of("account", ACC));
        try {
            List<TaskClaimVO> claimed = taskService.claim(ACC, 1, null);
            assertThat(claimed).as("先认领到目标任务").hasSize(1);
            long taskId = claimed.get(0).taskId();
            String leaseId = claimed.get(0).leaseId();

            String result = "{\"messages\":13,\"cursors\":1}";
            TaskReportResult first = taskService.complete(ACC, taskId, leaseId, result);
            assertThat(first.replayed()).as("首次 complete 是真实写入").isFalse();
            assertThat(first.taskStatus()).as("落 SUCCEEDED 终态").isEqualTo(TaskService.ST_SUCCEEDED);
            assertThat(first.result()).isEqualTo(result);

            TaskReportResult replay = taskService.complete(ACC, taskId, leaseId, result);
            assertThat(replay.replayed()).as("同 lease 重放按幂等返回既有 result，不报错").isTrue();
            assertThat(replay.taskStatus()).isEqualTo(TaskService.ST_SUCCEEDED);
            // jsonb 不保键序/空白：重放的 result 来自库回读，按树语义比较而非文本全等
            ObjectMapper jsonb = new ObjectMapper();
            assertThat(jsonb.readTree(replay.result()))
                    .as("重放返回既有 result（语义等价，v1.1 §4.2）")
                    .isEqualTo(jsonb.readTree(result));

            WxTaskEntity row = taskMapper.selectById(taskId);
            assertThat(row.getStatus()).isEqualTo(TaskService.ST_SUCCEEDED);
            assertThat(row.getLeaseId()).as("终态行保留 lease_id（重放判定凭据，v1.1 §2.2）").isEqualTo(leaseId);
            assertThat(row.getFinishedAt()).as("终态时点落库").isNotNull();

            WxTaskRunEntity run = taskRunMapper.selectOne(new LambdaQueryWrapper<WxTaskRunEntity>()
                    .eq(WxTaskRunEntity::getTaskId, taskId));
            assertThat(run.getOutcome()).as("run 行关闭为 SUCCEEDED").isEqualTo(TaskService.OUTCOME_SUCCEEDED);
            assertThat(run.getFinishedAt()).as("closeRun 路径写回报时点").isNotNull();
        } finally {
            TokenContext.clear();
        }
    }

    // ================================================================ 造数与清理

    /** 造一条 wx_task（直接 mapper insert，字段显式 given，不依赖 DDL 缺省）。 */
    private WxTaskEntity insertTask(String status, String target, int attempts, int maxAttempts,
                                    OffsetDateTime leaseUntil, String leaseId) {
        WxTaskEntity task = new WxTaskEntity();
        task.setTaskType(TaskService.TT_SYNC_MESSAGES);
        task.setAccount(ACC);
        task.setTarget(target);
        task.setPriority(0);
        task.setStatus(status);
        task.setAttempts(attempts);
        task.setMaxAttempts(maxAttempts);
        task.setLeaseId(leaseId);
        task.setLeaseUntil(leaseUntil);
        task.setPayload("{}");
        task.setIsDeleted(0);
        taskMapper.insert(task);
        return task;
    }

    /** 造一条 open 的 run 历史行（回收用例的 LOST 断言需要已存在的行）。 */
    private void openRun(long taskId, int attemptNo, String leaseId) {
        WxTaskRunEntity run = new WxTaskRunEntity();
        run.setTaskId(taskId);
        run.setAttemptNo(attemptNo);
        run.setLeaseId(leaseId);
        run.setAccount(ACC);
        run.setStartedAt(OffsetDateTime.now().minusMinutes(1));
        run.setResult("{}");
        taskRunMapper.insert(run);
    }

    /** 造测试计划（默认未到期——由 armSchedule 拨 now() 才参与物化，缩小调度竞态窗口）。 */
    private long insertSchedule(String taskType, int intervalSeconds) throws SQLException {
        WxTaskScheduleEntity sched = new WxTaskScheduleEntity();
        sched.setAccount(ACC);
        sched.setTaskType(taskType);
        sched.setTarget(TaskService.TARGET_ALL);
        sched.setIntervalSeconds(intervalSeconds);
        sched.setPriority(0);
        sched.setPayload("{}");
        sched.setActivityWindowDays(30);
        sched.setEnabled(1);
        sched.setIsDeleted(0);
        sched.setNextMaterializeAt(OffsetDateTime.now().plusHours(1));
        scheduleMapper.insert(sched);
        return sched.getId();
    }

    /** 拨计划到期：next_materialize_at = now()（造数全部就绪后才调）。 */
    private void armSchedule(long schedId) throws SQLException {
        exec("UPDATE wx_task_schedule SET next_materialize_at = now() WHERE id = " + schedId);
    }

    private void conversation(String username) {
        WxConversationEntity convo = new WxConversationEntity();
        convo.setAccount(ACC);
        convo.setUsername(username);
        convo.setIsGroup(Boolean.FALSE);
        convo.setLastMessageAt(OffsetDateTime.now());
        convo.setIsDeleted(0);
        conversationMapper.insert(convo);
    }

    private void cursor(String chat, OffsetDateTime lastFullScan) {
        WxSyncCursorEntity cur = new WxSyncCursorEntity();
        cur.setAccount(ACC);
        cur.setChatUsername(chat);
        cur.setShard("db/msg_0.db");
        cur.setMaxLocalId(1L);
        cur.setMaxSortSeq(1L);
        cur.setLastFullScan(lastFullScan);
        cursorMapper.insert(cur);
    }

    /** 工作线程内完成 token 绑定 → 认领 → 解绑（AccountBinding.checkBound 读 ThreadLocal claims）。 */
    private List<TaskClaimVO> claimAsClient(int maxTasks) {
        TokenContext.set("client", Map.of("account", ACC));
        try {
            return taskService.claim(ACC, maxTasks, null);
        } finally {
            TokenContext.clear();
        }
    }

    private List<WxTaskEntity> myTasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<WxTaskEntity>()
                .eq(WxTaskEntity::getAccount, ACC));
    }

    private List<WxTaskEntity> myTasks(String taskType) {
        return taskMapper.selectList(new LambdaQueryWrapper<WxTaskEntity>()
                .eq(WxTaskEntity::getAccount, ACC)
                .eq(WxTaskEntity::getTaskType, taskType));
    }

    private long activeTaskCount(String taskType) {
        return taskMapper.selectCount(new LambdaQueryWrapper<WxTaskEntity>()
                .eq(WxTaskEntity::getAccount, ACC)
                .eq(WxTaskEntity::getTaskType, taskType)
                .in(WxTaskEntity::getStatus, TaskService.ST_PENDING, TaskService.ST_DISPATCHED,
                        TaskService.ST_RUNNING));
    }

    /**
     * 两线程同起跑线的并发执行器：submit 两 worker → 主线程 ready 门等就位 → go 门同时放行
     * （原 invokeAll 版无人放行 go 门，两 worker 必在 await(5s) 上互相等死——「起步门超时」根因）；
     * 异常当结果带回由主线程分类断言；就绪门 5s、future 10s 双超时兜底防死挂。
     */
    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);   // 池容量=worker 数，杜绝排队饥饿
        try {
            Future<Object> f1 = pool.submit(wrap(first, ready, go));
            Future<Object> f2 = pool.submit(wrap(second, ready, go));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发就绪门超时（线程池容量 < worker 数？）");
            }
            go.countDown();   // 两 worker 已在起跑线，同一时刻放行
            List<Object> out = new ArrayList<>();
            out.add(f1.get(10, TimeUnit.SECONDS));
            out.add(f2.get(10, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Object> wrap(Callable<?> body, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发起步门超时");
            }
            try {
                return body.call();
            } catch (Exception e) {
                return e;   // 异常按结果带回，主线程按类型断言（胜者/败家语义）
            }
        };
    }

    /** 只清测试前缀（口径见类注释）：种子 1/2/3 与真实数据永不触碰。 */
    private void purge() throws SQLException {
        // run 先于 task 删（应用层关联，无外键也按依赖序）
        exec("DELETE FROM wx_task_run WHERE task_id IN "
                + "(SELECT id FROM wx_task WHERE account LIKE '" + ACC_PREFIX + "%')");
        exec("DELETE FROM wx_task WHERE account LIKE '" + ACC_PREFIX + "%'");
        // 计划只删 id > 3（种子 id 1/2/3 是生产计划，绝不可动）
        exec("DELETE FROM wx_task_schedule WHERE id > 3");
        // 账号只删测试前缀（真库 wx_account 有 5 行真实数据，绝不可全删）
        exec("DELETE FROM wx_account WHERE account LIKE '" + ACC_PREFIX + "%'");
        exec("DELETE FROM wx_conversation WHERE account LIKE '" + ACC_PREFIX + "%'");
        exec("DELETE FROM wx_sync_cursor WHERE account LIKE '" + ACC_PREFIX + "%'");
        // 注册码按签发时统一 label 锚点删（真库管理员的码不会有 t9it label）
        exec("DELETE FROM wx_reg_code WHERE label LIKE '" + REG_LABEL + "%'");
    }

    /** 测试期间停用生产种子（防 materializeDueSchedules 命中到期种子改生产状态）。 */
    private void disableSeeds() throws SQLException {
        exec("UPDATE wx_task_schedule SET enabled = 0 WHERE id IN (1, 2, 3)");
    }

    /**
     * 恢复种子并拨到下一自然周期：enabled=1 + next_materialize_at = now()+interval。
     * 拨周期的原因：本 JVM 后续还可能复用这个 Spring 上下文（残留调度线程），
     * 立即到期的种子会被测试期外的 tick 物化掉——推迟一个自然周期语义无损（正常物化节奏本就如此）。
     */
    private void restoreSeeds() throws SQLException {
        exec("UPDATE wx_task_schedule SET enabled = 1, "
                + "next_materialize_at = now() + (interval_seconds * interval '1 second') "
                + "WHERE id IN (1, 2, 3) AND interval_seconds IS NOT NULL");
        exec("UPDATE wx_task_schedule SET enabled = 1 WHERE id IN (1, 2, 3) AND interval_seconds IS NULL");
    }

    private void exec(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
