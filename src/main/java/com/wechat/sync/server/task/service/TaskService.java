package com.wechat.sync.server.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.common.WxErrorCode;
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
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 任务分发核心（TASK_DISPATCH §3~§5）：认领 / 汇报 / 回收 / 物化四个原子操作 + admin 辅助。
 *
 * <p>协议要点（v1.1）：认领 = SKIP LOCKED 一条 SQL（{@link WxTaskMapper#claimTasks}）；
 * 汇报先做<b>终态重放判定</b>再条件 UPDATE（终态行保留 lease_id 作重放凭据）；回收与 run 日志
 * 同事务；物化撞 uk_wx_task_active_target 即 DO NOTHING（防堆积），全部重派由 ingest 天然幂等
 * 兜底——整个方案不需要分布式锁（§8/§12）。
 *
 * <p>账号归属校验：client 汇报路径（claim/complete/fail/renew）先走
 * {@link AccountBinding#checkBound}（token↔入参一致，10300），再查行比对归属（10300）；
 * admin 路径（create/cancel/reset/计划 CRUD）不做绑定校验——admin 令牌 claims 无 account
 * 互斥键，角色校验由 Controller 层 {@code @RequiresToken(roles="admin")} 承担（T9-5）。
 */
@Slf4j
@Service
public class TaskService {

    /** 任务类型全域（§5）；claim 的 task_types 过滤与建任务校验共用 */
    public static final String TT_SYNC_MESSAGES = "SYNC_MESSAGES";
    public static final String TT_SYNC_RELATIONS = "SYNC_RELATIONS";
    public static final String TT_FULL_SCAN = "FULL_SCAN";
    public static final String TT_BLOB_UPLOAD = "BLOB_UPLOAD";
    public static final String TT_PROBE = "PROBE";
    /** T11：发送消息（可执行任务——客户端 claim 后驱动 wechatauto GUI 自动化发送；v1 仅手工派发）。 */
    public static final String TT_SEND_MESSAGE = "SEND_MESSAGE";
    public static final Set<String> TASK_TYPES =
            Set.of(TT_SYNC_MESSAGES, TT_SYNC_RELATIONS, TT_FULL_SCAN, TT_BLOB_UPLOAD, TT_PROBE,
                    TT_SEND_MESSAGE);

    /** wx_task.status 取值域（§3 状态机） */
    public static final String ST_PENDING = "PENDING";
    public static final String ST_DISPATCHED = "DISPATCHED";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_SUCCEEDED = "SUCCEEDED";
    public static final String ST_DEAD = "DEAD";
    public static final String ST_CANCELLED = "CANCELLED";

    /** 活动态：唯一索引防堆积与「可取消」的判定域 */
    private static final Set<String> ACTIVE_STATUSES = Set.of(ST_PENDING, ST_DISPATCHED, ST_RUNNING);

    /**
     * 可重放终态（§4.2 v1.1）：complete/fail 的「同 lease 重放返回同一终态」只对<b>客户端写下的</b>
     * 终态生效。CANCELLED 不在其中——管理员取消的行没有客户端结果可重放，迟到回报一律 10603（§6）。
     */
    private static final Set<String> REPLAYABLE_TERMINALS = Set.of(ST_SUCCEEDED, ST_DEAD);

    /** '*' 全域目标 */
    public static final String TARGET_ALL = "*";

    /** claim 单次上限（§6 body {max_tasks:1} 缺省；防一个客户端一轮独吞整队） */
    private static final int CLAIM_MAX_TASKS = 10;

    /** run 日志 outcome 取值域（§2.3） */
    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_LOST = "LOST";

    private final WxTaskMapper taskMapper;
    private final WxTaskRunMapper taskRunMapper;
    private final WxTaskScheduleMapper scheduleMapper;
    private final WxAccountMapper accountMapper;
    private final WxConversationMapper conversationMapper;
    private final AccountBinding accountBinding;
    private final ObjectMapper objectMapper;

    /** 租约时长秒（§10：FULL_SCAN 大会话靠客户端按 lease/3 主动 renew，不靠调大它） */
    private final int taskLeaseSeconds;

    /** 单计划单轮物化产出上限（§10 任务风暴兜底之一） */
    private final int materializeBatchCap;

    /** FULL_SCAN '*' 轮转每账号每轮取「最久未全扫」会话数 */
    private final int fullScanBatch;

    public TaskService(WxTaskMapper taskMapper,
                       WxTaskRunMapper taskRunMapper,
                       WxTaskScheduleMapper scheduleMapper,
                       WxAccountMapper accountMapper,
                       WxConversationMapper conversationMapper,
                       AccountBinding accountBinding,
                       ObjectMapper objectMapper,
                       @Value("${wx-sync.task-lease-seconds:600}") int taskLeaseSeconds,
                       @Value("${wx-sync.task-materialize-batch-cap:20}") int materializeBatchCap,
                       @Value("${wx-sync.task-full-scan-batch:5}") int fullScanBatch) {
        this.taskMapper = taskMapper;
        this.taskRunMapper = taskRunMapper;
        this.scheduleMapper = scheduleMapper;
        this.accountMapper = accountMapper;
        this.conversationMapper = conversationMapper;
        this.accountBinding = accountBinding;
        this.objectMapper = objectMapper;
        this.taskLeaseSeconds = taskLeaseSeconds;
        this.materializeBatchCap = materializeBatchCap;
        this.fullScanBatch = fullScanBatch;
    }

    // ------------------------------------------------------------ 认领

    /**
     * 认领（§4.1/§6）：原子认领至多 {@code maxTasks}（缺省 1、上限 {@value #CLAIM_MAX_TASKS}）条，
     * 同事务为每条插 wx_task_run 历史行（attempt_no = 认领时点 attempts）。
     *
     * @throws ApiException 10300 token↔account 不一致；10102 task_types 含未知类型
     */
    @Transactional
    public List<TaskClaimVO> claim(String account, Integer maxTasks, List<String> taskTypes) {
        accountBinding.checkBound(account);
        validateTaskTypes(taskTypes);
        int n = maxTasks == null || maxTasks <= 0 ? 1 : Math.min(maxTasks, CLAIM_MAX_TASKS);
        // task_types 过滤参数化为 CSV（白名单校验在前，无注入面；null = 不过滤，避开 <script> 动态 SQL）
        String csv = (taskTypes == null || taskTypes.isEmpty()) ? null : String.join(",", taskTypes);
        List<WxTaskEntity> claimed = taskMapper.claimTasks(account, n, taskLeaseSeconds, csv);
        OffsetDateTime startedAt = OffsetDateTime.now();
        for (WxTaskEntity task : claimed) {
            WxTaskRunEntity run = new WxTaskRunEntity();
            run.setTaskId(task.getId());
            run.setAttemptNo(task.getAttempts());
            run.setLeaseId(task.getLeaseId());
            run.setAccount(account);
            run.setStartedAt(startedAt);
            run.setResult("{}");
            taskRunMapper.insert(run);
        }
        if (!claimed.isEmpty()) {
            log.info("[Task] 认领 account={} count={}", account, claimed.size());
        }
        return claimed.stream().map(TaskClaimVO::from).toList();
    }

    // ------------------------------------------------------------ 汇报

    /**
     * complete（§4.2/§6）：终态重放判定先行，条件 UPDATE 落 SUCCEEDED，同事务关 run 行。
     * 返回 REPLAYED（同 lease 重放，result 为既有值）或 COMPLETED。
     *
     * @throws ApiException 10400 任务不存在；10300 越权；10102 result 非法 JSON；10603 租约失效
     */
    @Transactional
    public TaskReportResult complete(String account, long taskId, String leaseId, String resultJson) {
        accountBinding.checkBound(account);
        String result = requireValidJsonObject(resultJson, "result");
        WxTaskEntity row = requireOwnedTask(account, taskId);
        TaskReportResult replay = replayOrLeaseLost(row, taskId, leaseId);
        if (replay != null) {
            return replay;
        }
        int updated = taskMapper.reportTerminal(ST_SUCCEEDED, taskId, leaseId, result, null);
        if (updated == 0) {
            // complete 与回收/取消竞态（§8）：条件未命中，重查定性（重放或 10603，必居其一）
            TaskReportResult after = replayOrLeaseLost(requireOwnedTask(account, taskId), taskId, leaseId);
            if (after != null) {
                return after;
            }
            throw WxErrorCode.taskLeaseLost(taskId);
        }
        taskRunMapper.closeRun(taskId, leaseId, OUTCOME_SUCCEEDED, result, null);
        return new TaskReportResult(false, ST_SUCCEEDED, result, null);
    }

    /**
     * fail（§3/§4.2/§6）：落点由 {@link #decideFailTarget} 判定——PENDING（清租约回队）或 DEAD 终态。
     * 两条路径同事务关 run 行（outcome=FAILED，error 落 run.error + task.last_error）。
     *
     * @throws ApiException 10400 任务不存在；10300 越权；10603 租约失效
     */
    @Transactional
    public TaskReportResult fail(String account, long taskId, String leaseId, String error, boolean retryable) {
        accountBinding.checkBound(account);
        WxTaskEntity row = requireOwnedTask(account, taskId);
        TaskReportResult replay = replayOrLeaseLost(row, taskId, leaseId);
        if (replay != null) {
            return replay;
        }
        String target = decideFailTarget(retryable, row.getAttempts(), row.getMaxAttempts());
        int updated = ST_PENDING.equals(target)
                ? taskMapper.failBackToPending(taskId, leaseId, error)
                : taskMapper.failToDead(taskId, leaseId, error);
        if (updated == 0 && ST_PENDING.equals(target)) {
            // 防御：回队条件含 attempts<max，若并发把额度推过线（同租约自洽下不该发生）→ 改判 DEAD
            updated = taskMapper.failToDead(taskId, leaseId, error);
            if (updated > 0) {
                target = ST_DEAD;   // 返回值必须带上改判后的终态，客户端拿到的状态与库一致
            }
        }
        if (updated == 0) {
            TaskReportResult after = replayOrLeaseLost(requireOwnedTask(account, taskId), taskId, leaseId);
            if (after != null) {
                return after;
            }
            throw WxErrorCode.taskLeaseLost(taskId);
        }
        taskRunMapper.closeRun(taskId, leaseId, OUTCOME_FAILED, null, error);
        return new TaskReportResult(false, target, null, error);
    }

    /**
     * renew（§4.2/§7）：续租并置 RUNNING（每次都置，观测态口径 §3）。终态同 lease 重放按 no-op
     * 成功处理（客户端网络重试 renew 不报错）。
     *
     * @throws ApiException 10400 任务不存在；10300 越权；10603 租约失效
     */
    @Transactional
    public TaskReportResult renew(String account, long taskId, String leaseId) {
        accountBinding.checkBound(account);
        WxTaskEntity row = requireOwnedTask(account, taskId);
        TaskReportResult replay = replayOrLeaseLost(row, taskId, leaseId);
        if (replay != null) {
            return replay;
        }
        int updated = taskMapper.renewLease(taskId, leaseId, taskLeaseSeconds);
        if (updated == 0) {
            TaskReportResult after = replayOrLeaseLost(requireOwnedTask(account, taskId), taskId, leaseId);
            if (after != null) {
                return after;
            }
            throw WxErrorCode.taskLeaseLost(taskId);
        }
        return new TaskReportResult(false, ST_RUNNING, null, null);
    }

    /**
     * 可重试失败的落点判定（纯函数，便于单测）：retryable=false（契约错误 101xx/106xx 不该进重试
     * 循环）或重试额度用尽 → DEAD；否则回 PENDING 等重派。回收巡检复用它判过期租约的归宿。
     */
    static String decideFailTarget(boolean retryable, int attempts, int maxAttempts) {
        if (!retryable) {
            return ST_DEAD;
        }
        return attempts >= maxAttempts ? ST_DEAD : ST_PENDING;
    }

    /**
     * 终态重放判定（§4.2 v1.1，UPDATE 之前查）：
     * <ul>
     *   <li>可重放终态（SUCCEEDED/DEAD）且 lease 一致 → 返回既有 result/error（重放成功，不报错）</li>
     *   <li>可重放终态但 lease 不一致 → 10603（租约已易主）</li>
     *   <li>CANCELLED → 10603（管理员取消的行无客户端结果可重放，§6）</li>
     *   <li>活动态 → 返回 null，调用方继续条件 UPDATE</li>
     * </ul>
     */
    private TaskReportResult replayOrLeaseLost(WxTaskEntity row, long taskId, String leaseId) {
        String status = row.getStatus();
        if (!REPLAYABLE_TERMINALS.contains(status)) {
            if (!ACTIVE_STATUSES.contains(status)) {
                throw WxErrorCode.taskLeaseLost(taskId);
            }
            return null;
        }
        if (leaseId != null && leaseId.equals(row.getLeaseId())) {
            return new TaskReportResult(true, status, row.getResult(), row.getLastError());
        }
        throw WxErrorCode.taskLeaseLost(taskId);
    }

    // ------------------------------------------------------------ 回收巡检

    /**
     * 回收过期租约（§4.3，T9-5 @Scheduled 接线，30s 一轮）：过期行打回 PENDING（额度用尽→DEAD，
     * 复用 {@link #decideFailTarget}）+ 同事务落 run 日志 LOST（{@code finished_at} 保持 NULL，
     * §2.3 口径）。重派后重跑无害——ingest 天然幂等（§8）。
     *
     * @return 回收行数
     */
    @Transactional
    public int sweepExpiredLeases() {
        List<WxTaskEntity> expired = taskMapper.selectExpiredForUpdate();
        if (expired.isEmpty()) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (WxTaskEntity task : expired) {
            String target = decideFailTarget(true, task.getAttempts(), task.getMaxAttempts());
            taskMapper.update(null, new LambdaUpdateWrapper<WxTaskEntity>()
                    .eq(WxTaskEntity::getId, task.getId())
                    .set(WxTaskEntity::getStatus, target)
                    .set(WxTaskEntity::getLeaseId, null)
                    .set(WxTaskEntity::getLeaseUntil, null)
                    .set(WxTaskEntity::getLastError, "lease expired")
                    .set(WxTaskEntity::getUpdatedAt, now));
            taskRunMapper.markRunLost(task.getId(), task.getLeaseId(), "lease expired");
        }
        log.info("[Task] 回收过期租约 count={}", expired.size());
        return expired.size();
    }

    // ------------------------------------------------------------ 物化

    /**
     * 物化到期计划（§4.3，T9-5 @Scheduled 接线）：逐计划展开账号→生成 wx_task（撞
     * uk_wx_task_active_target 即 DO NOTHING 防堆积，§8）→ 推 next_materialize_at。
     * 推进带「旧值一致」乐观锁（§8 多实例不重复物化；竞争失败时已产出的任务无害——幂等跳过）。
     *
     * @return 本轮新建任务数（ON CONFLICT 跳过的不计）
     */
    @Transactional
    public int materializeDueSchedules(OffsetDateTime now) {
        List<WxTaskScheduleEntity> due = scheduleMapper.selectList(new LambdaQueryWrapper<WxTaskScheduleEntity>()
                .eq(WxTaskScheduleEntity::getEnabled, 1)
                .eq(WxTaskScheduleEntity::getIsDeleted, 0)
                .isNotNull(WxTaskScheduleEntity::getNextMaterializeAt)
                .le(WxTaskScheduleEntity::getNextMaterializeAt, now));
        int produced = 0;
        for (WxTaskScheduleEntity sched : due) {
            int cap = Math.max(1, materializeBatchCap);
            int made = 0;
            for (String account : expandAccounts(sched.getAccount())) {
                if (made >= cap) {
                    break;
                }
                made += materializeForAccount(sched, account, cap - made, now);
            }
            pushNextMaterializeAt(sched, now);
            produced += made;
        }
        if (produced > 0) {
            log.info("[Task] 物化 schedules={} produced={}", due.size(), produced);
        }
        return produced;
    }

    /**
     * 单账号展开物化：target 非 '*' 按字面一条；'*' 时按任务类型展开——
     * SYNC_MESSAGES 按活动窗口逐会话（§4.3）、FULL_SCAN 按「最久未全扫」轮转取 K 个（v1.1）、
     * 其余（SYNC_RELATIONS/BLOB_UPLOAD/PROBE）账号全域一条。
     */
    private int materializeForAccount(WxTaskScheduleEntity sched, String account, int remaining, OffsetDateTime now) {
        String type = sched.getTaskType();
        String target = sched.getTarget();
        if (!TARGET_ALL.equals(target)) {
            return insertIgnore(type, account, target, sched.getPriority(), sched.getPayload(), sched.getId());
        }
        int made = 0;
        if (TT_SYNC_MESSAGES.equals(type)) {
            int window = sched.getActivityWindowDays() == null ? 30 : sched.getActivityWindowDays();
            List<WxConversationEntity> convos = conversationMapper.selectList(
                    new LambdaQueryWrapper<WxConversationEntity>()
                            .select(WxConversationEntity::getUsername)
                            .eq(WxConversationEntity::getAccount, account)
                            .eq(WxConversationEntity::getIsDeleted, 0)
                            .gt(WxConversationEntity::getLastMessageAt, now.minusDays(window))
                            .orderByDesc(WxConversationEntity::getLastMessageAt)
                            .last("LIMIT " + remaining));
            for (WxConversationEntity convo : convos) {
                made += insertIgnore(type, account, convo.getUsername(), sched.getPriority(),
                        payloadWithChat(sched.getPayload(), convo.getUsername()), sched.getId());
            }
            return made;
        }
        if (TT_FULL_SCAN.equals(type)) {
            int take = Math.max(1, Math.min(fullScanBatch, remaining));
            for (String chat : taskMapper.selectChatsForFullScan(account, take)) {
                made += insertIgnore(type, account, chat, sched.getPriority(),
                        payloadWithChat(sched.getPayload(), chat), sched.getId());
            }
            return made;
        }
        return insertIgnore(type, account, TARGET_ALL, sched.getPriority(), sched.getPayload(), sched.getId());
    }

    /** 会话级 payload = 计划 payload（JSON 对象）并入 {@code {"chat": <username>}}；非法 JSON → 10102。 */
    private String payloadWithChat(String schedulePayload, String chat) {
        ObjectNode node;
        try {
            node = schedulePayload == null || schedulePayload.isBlank()
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(schedulePayload);
        } catch (Exception e) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "计划 payload 必须是 JSON 对象: " + e.getMessage());
        }
        node.put("chat", chat);
        return node.toString();
    }

    /** 防堆积插入：撞活动态同键任务返回 0（§8）。 */
    private int insertIgnore(String type, String account, String target, Integer priority, String payload, Long scheduleId) {
        List<Long> ids = taskMapper.insertTaskIgnoreReturnId(type, account, target,
                priority == null ? 0 : priority,
                payload == null || payload.isBlank() ? "{}" : payload,
                scheduleId);
        return ids.isEmpty() ? 0 : 1;
    }

    /** 推进 next_materialize_at：interval→now+interval；daily_at→下一个时刻；仅手工→不动（§2.4）。 */
    private void pushNextMaterializeAt(WxTaskScheduleEntity sched, OffsetDateTime now) {
        OffsetDateTime next = computeNextMaterializeAt(sched, now);
        if (next == null) {
            return;
        }
        scheduleMapper.update(null, new LambdaUpdateWrapper<WxTaskScheduleEntity>()
                .eq(WxTaskScheduleEntity::getId, sched.getId())
                .eq(WxTaskScheduleEntity::getNextMaterializeAt, sched.getNextMaterializeAt()) // §8 乐观锁
                .set(WxTaskScheduleEntity::getNextMaterializeAt, next)
                .set(WxTaskScheduleEntity::getLastMaterializedAt, now)
                .set(WxTaskScheduleEntity::getUpdatedAt, now));
    }

    /** 下次物化时点（纯函数便于单测）：interval 直加；daily_at 取今天/明天的该时刻。 */
    static OffsetDateTime computeNextMaterializeAt(WxTaskScheduleEntity sched, OffsetDateTime now) {
        if (sched.getIntervalSeconds() != null) {
            return now.plusSeconds(sched.getIntervalSeconds());
        }
        if (sched.getDailyAt() != null) {
            OffsetDateTime todayAt = OffsetDateTime.of(now.toLocalDate(), sched.getDailyAt(), now.getOffset());
            return todayAt.isAfter(now) ? todayAt : todayAt.plusDays(1);
        }
        return null;
    }

    /** 计划生效账号展开：NULL = 全部未删注册账号（§2.4）。 */
    private List<String> expandAccounts(String account) {
        if (account != null && !account.isBlank()) {
            return List.of(account);
        }
        return accountMapper.selectList(new LambdaQueryWrapper<WxAccountEntity>()
                        .select(WxAccountEntity::getAccount)
                        .eq(WxAccountEntity::getIsDeleted, 0))
                .stream().map(WxAccountEntity::getAccount).toList();
    }

    // ------------------------------------------------------------ admin：任务 / 计划

    /**
     * 手工建任务（旧口径，max_attempts 走 DDL 缺省 3）：保留旧签名委托，行为不变。
     */
    @Transactional
    public long createTask(String account, String taskType, String target, Integer priority, String payloadJson) {
        return createTask(account, taskType, target, priority, payloadJson, null);
    }

    /**
     * 手工建任务（schedule_id=NULL）：payload 校验 + 账号存在校验；同键活动任务已存在 → 10402
     * （uk_wx_task_active_target 语义前移到响应语义层）；max_attempts 可覆盖（1..10，null = 缺省 3）。
     *
     * @throws ApiException 10102 类型/payload 非法或 max_attempts 越界；10601 账号未注册；10402 活动任务已存在
     */
    @Transactional
    public long createTask(String account, String taskType, String target, Integer priority,
                           String payloadJson, Integer maxAttempts) {
        if (maxAttempts != null && (maxAttempts < 1 || maxAttempts > 10)) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "max_attempts 必须 1..10: " + maxAttempts);
        }
        validateTaskTypes(List.of(taskType));
        requireAccountExists(account);
        String t = (target == null || target.isBlank()) ? TARGET_ALL : target;
        String payload = TT_SEND_MESSAGE.equals(taskType)
                ? requireSendMessagePayload(payloadJson)
                : requireValidJsonObject(payloadJson, "payload");
        List<Long> ids = taskMapper.insertTaskIgnoreReturnIdMax(taskType, account, t,
                priority == null ? 0 : priority, payload, null, maxAttempts);
        if (ids.isEmpty()) {
            throw new ApiException(ApiCode.STATE_CONFLICT,
                    "同 (account, task_type, target) 已有活动任务: " + account + "/" + taskType + "/" + t);
        }
        return ids.get(0);
    }

    /**
     * 取消任务（§6）：PENDING/DISPATCHED → CANCELLED（终态保留 lease_id，§2.2）；DISPATCHED 被取消
     * 时同事务把 open 的 run 行落 LOST（finished_at 保持 NULL，§2.3）。其它状态 → 10402。
     */
    @Transactional
    public void cancelTask(long taskId) {
        WxTaskEntity row = requireTask(taskId);
        if (!ACTIVE_STATUSES.contains(row.getStatus())) {
            throw new ApiException(ApiCode.STATE_CONFLICT, "任务不可取消（非活动态）: " + row.getStatus());
        }
        taskMapper.cancelTaskRow(taskId);
        if (ST_DISPATCHED.equals(row.getStatus()) && row.getLeaseId() != null) {
            taskRunMapper.markRunLost(taskId, row.getLeaseId(), "cancelled by admin");
        }
        log.info("[Task] 取消任务 id={} from={}", taskId, row.getStatus());
    }

    /** 重置死信（§10）：仅 DEAD → PENDING（attempts 归零清租约）；其它状态 → 10402。 */
    @Transactional
    public void resetDeadTask(long taskId) {
        WxTaskEntity row = requireTask(taskId);
        if (!ST_DEAD.equals(row.getStatus())) {
            throw new ApiException(ApiCode.STATE_CONFLICT, "仅 DEAD 任务可重置: " + row.getStatus());
        }
        taskMapper.resetDeadRow(taskId);
        log.info("[Task] 重置死信任务 id={}", taskId);
    }

    /**
     * 建计划（§2.4）：interval 与 daily 二选一（10102）；可调度（enabled 且有周期）强制
     * {@code next_materialize_at = now()}——NULL 永不被调度器命中，只允许「仅手工」语义显式为 NULL。
     */
    @Transactional
    public long createSchedule(String account, String taskType, String target, Integer intervalSeconds,
                               LocalTime dailyAt, Integer priority, String payloadJson,
                               Integer activityWindowDays, Boolean enabled) {
        validateTaskTypes(List.of(taskType));
        if (TT_SEND_MESSAGE.equals(taskType)) {
            // 定时群发 = 无人在环的自动外发，风险面大；v1 刻意只许手工派发（admin 每次 POST 一次）
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "SEND_MESSAGE 不支持计划（v1 仅手工派发）");
        }
        if (intervalSeconds != null && dailyAt != null) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "interval_seconds 与 daily_at 二选一");
        }
        String acct = (account == null || account.isBlank()) ? null : account;
        if (acct != null) {
            requireAccountExists(acct);
        }
        boolean on = enabled == null || enabled;
        boolean schedulable = on && (intervalSeconds != null || dailyAt != null);
        WxTaskScheduleEntity row = new WxTaskScheduleEntity();
        row.setAccount(acct);
        row.setTaskType(taskType);
        row.setTarget((target == null || target.isBlank()) ? TARGET_ALL : target);
        row.setIntervalSeconds(intervalSeconds);
        row.setDailyAt(dailyAt);
        row.setPriority(priority == null ? 0 : priority);
        row.setPayload(requireValidJsonObject(payloadJson, "payload"));
        row.setActivityWindowDays(activityWindowDays == null ? 30 : activityWindowDays);
        row.setEnabled(on ? 1 : 0);
        row.setIsDeleted(0);
        row.setNextMaterializeAt(schedulable ? OffsetDateTime.now() : null);
        scheduleMapper.insert(row);
        return row.getId();
    }

    /** 改计划（null=不改）；周期字段变更或从暂停恢复且从未排期 → next_materialize_at 重新起算 = now()。 */
    @Transactional
    public void updateSchedule(long id, Boolean enabled, Integer intervalSeconds, LocalTime dailyAt,
                               Integer priority, String payloadJson, Integer activityWindowDays) {
        WxTaskScheduleEntity row = scheduleMapper.selectById(id);
        if (row == null || (row.getIsDeleted() != null && row.getIsDeleted() != 0)) {
            throw new ApiException(ApiCode.NOT_FOUND, "计划不存在: " + id);
        }
        boolean periodChanged = (intervalSeconds != null && !intervalSeconds.equals(row.getIntervalSeconds()))
                || (dailyAt != null && !dailyAt.equals(row.getDailyAt()));
        boolean reschedule = periodChanged
                || (Boolean.TRUE.equals(enabled) && row.getNextMaterializeAt() == null);
        LambdaUpdateWrapper<WxTaskScheduleEntity> w = new LambdaUpdateWrapper<WxTaskScheduleEntity>()
                .eq(WxTaskScheduleEntity::getId, id)
                .set(WxTaskScheduleEntity::getUpdatedAt, OffsetDateTime.now());
        if (enabled != null) {
            w.set(WxTaskScheduleEntity::getEnabled, enabled ? 1 : 0);
        }
        if (intervalSeconds != null) {
            w.set(WxTaskScheduleEntity::getIntervalSeconds, intervalSeconds);
        }
        if (dailyAt != null) {
            w.set(WxTaskScheduleEntity::getDailyAt, dailyAt);
        }
        if (priority != null) {
            w.set(WxTaskScheduleEntity::getPriority, priority);
        }
        if (payloadJson != null) {
            // jsonb 列不能走默认 setString（PG 拒 varchar 表达式），wrapper 路径用 mapping 串挂 JsonbTypeHandler
            w.set(WxTaskScheduleEntity::getPayload, requireValidJsonObject(payloadJson, "payload"),
                    "typeHandler=com.wechat.sync.server.common.JsonbTypeHandler");
        }
        if (activityWindowDays != null) {
            w.set(WxTaskScheduleEntity::getActivityWindowDays, activityWindowDays);
        }
        if (reschedule) {
            w.set(WxTaskScheduleEntity::getNextMaterializeAt, OffsetDateTime.now());
        }
        scheduleMapper.update(null, w);
    }

    /** 计划列表（配置表量级小，全量返回非软删行，按 id 升序，防御上限 200）。 */
    public List<WxTaskScheduleEntity> listSchedules() {
        return scheduleMapper.selectList(new LambdaQueryWrapper<WxTaskScheduleEntity>()
                .select(WxTaskScheduleEntity::getId, WxTaskScheduleEntity::getAccount,
                        WxTaskScheduleEntity::getTaskType, WxTaskScheduleEntity::getTarget,
                        WxTaskScheduleEntity::getIntervalSeconds, WxTaskScheduleEntity::getDailyAt,
                        WxTaskScheduleEntity::getPriority, WxTaskScheduleEntity::getPayload,
                        WxTaskScheduleEntity::getActivityWindowDays, WxTaskScheduleEntity::getEnabled,
                        WxTaskScheduleEntity::getLastMaterializedAt, WxTaskScheduleEntity::getNextMaterializeAt)
                .eq(WxTaskScheduleEntity::getIsDeleted, 0)
                .orderByAsc(WxTaskScheduleEntity::getId)
                .last("LIMIT 200"));
    }

    /** 计划详情（§6 GET /v1/task-schedules/{id}）：不存在/软删 → 10400。 */
    public WxTaskScheduleEntity scheduleDetail(long id) {
        WxTaskScheduleEntity row = scheduleMapper.selectById(id);
        if (row == null || (row.getIsDeleted() != null && row.getIsDeleted() != 0)) {
            throw new ApiException(ApiCode.NOT_FOUND, "计划不存在: " + id);
        }
        return row;
    }

    // ------------------------------------------------------------ 内部工具

    /** 查任务行（admin 路径）：不存在/软删 → 10400。 */
    private WxTaskEntity requireTask(long taskId) {
        WxTaskEntity row = taskMapper.selectById(taskId);
        if (row == null || (row.getIsDeleted() != null && row.getIsDeleted() != 0)) {
            throw new ApiException(ApiCode.NOT_FOUND, "任务不存在: " + taskId);
        }
        return row;
    }

    /** 查任务行（client 汇报路径）：10400 之上再做归属比对（task.account ≠ token.account → 10300，§6）。 */
    private WxTaskEntity requireOwnedTask(String account, long taskId) {
        WxTaskEntity row = requireTask(taskId);
        if (!account.equals(row.getAccount())) {
            throw new ApiException(ApiCode.FORBIDDEN,
                    "任务归属账号不符: task.account=" + row.getAccount() + " token=" + account);
        }
        return row;
    }

    private void requireAccountExists(String account) {
        Long count = accountMapper.selectCount(new LambdaQueryWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, account)
                .eq(WxAccountEntity::getIsDeleted, 0));
        if (count == null || count == 0) {
            throw WxErrorCode.accountNotRegistered(account);
        }
    }

    /** claim task_types 过滤白名单校验：未知类型 → 10102（null/空 = 不过滤）。 */
    private void validateTaskTypes(List<String> taskTypes) {
        if (taskTypes == null || taskTypes.isEmpty()) {
            return;
        }
        for (String type : taskTypes) {
            if (type == null || !TASK_TYPES.contains(type)) {
                throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "未知 task_type: " + type);
            }
        }
    }

    /** JSONB 入参校验：空 → "{}"；必须是 JSON 对象（列默认 '{}'::jsonb），非法 → 10102。 */
    private String requireValidJsonObject(String json, String field) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, field + " 不是合法 JSON: " + e.getMessage());
        }
        if (!node.isObject()) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, field + " 必须是 JSON 对象");
        }
        return json;
    }

    /**
     * SEND_MESSAGE chat_username 的接受面：{@code @chatroom} 后缀 + WeChat id 通用形态
     * {@code ^[A-Za-z][A-Za-z0-9_-]{3,63}$}。v1 的三形态白名单
     * （{@code @chatroom}/{@code filehelper}/{@code wxid_} 前缀）把老式自定义 id 挡在门外
     * （live 2026-11-23: 用户 p5soft/朱家田 的会话触发回复即 10102）——而客户端按显示名
     * 解析目标（群名/备注/昵称回退），并不要求 wxid_ 前缀。防呆底线：拒绝空白/点号/
     * 数字开头/超长等明显非 id 形态。
     */
    private static boolean isAddressableChat(String chat) {
        if (chat.endsWith("@chatroom")) {
            return true;
        }
        return chat.matches("^[A-Za-z][A-Za-z0-9_-]{3,63}$");
    }

    /**
     * SEND_MESSAGE payload 校验（T11）：只允许 {@code chat_username}/{@code text} 两键
     * （未知键 10102——防拼写错误静默失效，SubscriptionFilter 同哲学）。
     * <ul>
     *   <li>{@code chat_username}：必填，trim 后非空且 ≤64，形态必须可被客户端定位
     *       （{@code @chatroom} 后缀，或字母开头的 WeChat id——wxid_ 前缀、filehelper、
     *       gh_ 公众号、老式自定义 id 同属 {@code ^[A-Za-z][A-Za-z0-9_-]{3,63}$}）；</li>
     *   <li>{@code text}：必填，trim 后长度 1..2000（<b>存储保留原文不 trim</b>——首尾空白
     *       可能是发送意图的一部分，校验才用 trim）。</li>
     * </ul>
     *
     * @return 规范形紧凑 JSON（键排序确定）：{@code {"chat_username":"<trim后>","text":"<原样>"}}
     */
    private String requireSendMessagePayload(String payloadJson) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
        } catch (Exception e) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "payload 不是合法 JSON: " + e.getMessage());
        }
        if (!node.isObject()) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "SEND_MESSAGE payload 必须是 JSON 对象");
        }
        List<String> unknown = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(k -> {
            if (!"chat_username".equals(k) && !"text".equals(k)) {
                unknown.add(k);
            }
        });
        if (!unknown.isEmpty()) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR,
                    "SEND_MESSAGE payload 不支持键: " + String.join(", ", unknown));
        }
        JsonNode chatNode = node.get("chat_username");
        String chat = chatNode == null || chatNode.isNull() ? "" : chatNode.asText().trim();
        if (chat.isEmpty()) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "SEND_MESSAGE payload 缺 chat_username");
        }
        if (chat.length() > 64) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "SEND_MESSAGE chat_username 超过 64 字符");
        }
        if (!isAddressableChat(chat)) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR,
                    "SEND_MESSAGE chat_username 形态不可定位（@chatroom 后缀，或字母开头的 WeChat id 形态）: "
                            + chat);
        }
        JsonNode textNode = node.get("text");
        String text = textNode == null || textNode.isNull() ? "" : textNode.asText();
        if (text.trim().isEmpty()) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "SEND_MESSAGE payload 缺 text（正文不能为空白）");
        }
        if (text.trim().length() > 2000) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "SEND_MESSAGE text 超过 2000 字符");
        }
        try {
            java.util.Map<String, String> canonical = new java.util.TreeMap<>();
            canonical.put("chat_username", chat);
            canonical.put("text", text);
            return objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {                         // 两键纯文本，理论不可达
            throw new IllegalStateException("SEND_MESSAGE payload 序列化失败", e);
        }
    }
}
