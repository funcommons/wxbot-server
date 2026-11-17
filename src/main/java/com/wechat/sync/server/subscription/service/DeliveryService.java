package com.wechat.sync.server.subscription.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.sync.server.common.WxErrorCode;
import com.wechat.sync.server.message.entity.WxMessageAttachmentEntity;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.mapper.WxMessageAttachmentMapper;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import com.wechat.sync.server.subscription.entity.WxConsumerEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionDeliveryEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.mapper.WxConsumerMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 投递服务（SUBSCRIPTION_DESIGN §4 状态机 / §5 WEBHOOK / §6 POLL）——队列原语全部复用
 * wx_task 验证过的语义：{@code lease_id} 汇报凭据、终态保留凭据、条件 UPDATE 0 行 →
 * 终态重放判定（同 lease 幂等 / 异 lease 10606，口径同 TaskService.replayOrLeaseLost）。
 *
 * <p>职责边界：本类只做「认领 + 组装 + 汇报 + 救济」——HTTP 外呼与 HMAC 签名属 T10-5
 * {@code WebhookDispatcher}，@Scheduled 拨轮属 T10-5 {@code SubscriptionSchedulerConfig}；
 * consumer Bearer 鉴权入口在本类（{@link #authenticateConsumer}），Controller（T10-6）调用后
 * 把 consumer 实体传进来，不重复查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    /** 状态闭集（§4 状态机；DB 列为大写英文值） */
    private static final String ST_PENDING = "PENDING";
    private static final String ST_DISPATCHED = "DISPATCHED";
    private static final String ST_DELIVERED = "DELIVERED";
    private static final String ST_DEAD = "DEAD";

    /** 指数退避封顶（§5.2：min(base × 2^(n-1), 3600)） */
    static final int BACKOFF_CAP_SECONDS = 3600;
    /** 缺省退避基数（订阅行缺失/字段空时兜底，§2.2 缺省列） */
    private static final int DEFAULT_BACKOFF_BASE = 30;
    /** webhook_url 未解析时的重试间隔（配置错不该立即重烧，也不该死信——等运营补 URL） */
    private static final int NO_URL_RETRY_SECONDS = 60;
    /** POLL 单次拉取上限（§6 示例 max=50；硬顶防一次性拖走过量） */
    private static final int PULL_MAX_CAP = 500;
    /** error 落库长度约定（§5.2：异常摘要 ≤512 字符） */
    private static final int ERROR_MAX_LEN = 512;

    private final WxSubscriptionDeliveryMapper deliveryMapper;
    private final WxSubscriptionMapper subscriptionMapper;
    private final WxConsumerMapper consumerMapper;
    private final WxMessageMapper messageMapper;
    private final WxMessageAttachmentMapper attachmentMapper;
    private final ObjectMapper objectMapper;

    // ============================================================ consumer 鉴权

    /**
     * POLL Bearer 令牌 → 消费者实体（§6：订阅域独立主体，不走 @RequiresToken）。
     * sha256(所持令牌) 与 {@code token_hash} 比对（哈希入库口径，同 wx_account.api_key_hash）。
     *
     * @throws ApiException 10300（FORBIDDEN，消息点名 {@code consumer_token}；设计 §6 的
     *                      error[].field 粒度由 Controller 层补齐，服务层统一消息口径）
     */
    public WxConsumerEntity authenticateConsumer(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isEmpty()) {
            throw consumerTokenRejected();
        }
        WxConsumerEntity consumer = consumerMapper.selectOne(
                new LambdaQueryWrapper<WxConsumerEntity>()
                        .eq(WxConsumerEntity::getTokenHash, SubscriptionFilter.sha256Hex(trimmed))
                        .eq(WxConsumerEntity::getIsDeleted, 0)
                        .last("LIMIT 1"));
        if (consumer == null) {
            throw consumerTokenRejected();
        }
        return consumer;
    }

    private ApiException consumerTokenRejected() {
        return new ApiException(ApiCode.FORBIDDEN, "consumer_token 无效或该消费者未启用 POLL（error[].field=consumer_token）");
    }

    // ============================================================ WEBHOOK（§5）

    /**
     * 认领到期 WEBHOOK 行并组装投递载荷（§5.3 认领 SQL + §5.1 载荷）。
     * 返回项已带最终序列化 payloadJson 与解析后的 url（subscription 覆写 consumer；
     * 两者皆空 → 60s 退避重排并跳过——配置错不打死信，等运营补 URL）。
     * T10-5 WebhookDispatcher 只消费本方法产出（认领 lease 语义已落库）。
     */
    public List<DeliveryVOs.WebhookDeliveryTask> dispatchDue(int limit, int perSubInflight) {
        List<WxSubscriptionDeliveryEntity> claimed = deliveryMapper.claimDueWebhook(limit, perSubInflight);
        if (claimed.isEmpty()) {
            return List.of();
        }
        Map<Long, WxMessageEntity> messages = selectMessages(messageIds(claimed));
        Map<Long, List<DeliveryVOs.DeliveryAttachmentVO>> attachments = selectAttachments(messageIds(claimed));
        Map<Long, WxSubscriptionEntity> subscriptions = selectSubscriptions(subscriptionIds(claimed));
        Map<Long, WxConsumerEntity> consumers = selectConsumers(consumerIds(claimed));

        List<DeliveryVOs.WebhookDeliveryTask> tasks = new ArrayList<>(claimed.size());
        long epochSecond = Instant.now().getEpochSecond();
        for (WxSubscriptionDeliveryEntity row : claimed) {
            String url = resolveUrl(subscriptions.get(row.getSubscriptionId()),
                    consumers.get(row.getConsumerId()));
            if (url == null) {
                deliveryMapper.requeueForRetry(row.getId(), row.getLeaseId(), NO_URL_RETRY_SECONDS,
                        null, "webhook_url 未配置（订阅与消费者均空）");
                log.warn("[Delivery] 订阅 {} 的投递 {} 无 webhook_url，{}s 后重试",
                        row.getSubscriptionId(), row.getId(), NO_URL_RETRY_SECONDS);
                continue;
            }
            tasks.add(new DeliveryVOs.WebhookDeliveryTask(row.getId(), row.getLeaseId(),
                    row.getAttempts() == null ? 1 : row.getAttempts(), row.getConsumerId(), url,
                    buildPayloadJson(row, messages.get(row.getMessageId()),
                            attachments.get(row.getMessageId()), epochSecond),
                    consumerSecretHash(consumers, row)));
        }
        return tasks;
    }

    /**
     * 取消费者 WEBHOOK 密钥的 sha256hex（T10-5 HMAC 签名密钥 = 库存 hash，§5.1 v1.1 口径）。
     * 消费者行缺失（认领后被软删/物理删除，映射查无）→ null——dispatcher 侧按配置错走
     * 失败汇报退避，与「URL 双空」同款兜底（不打 complete、不打死信直通车）。
     */
    private String consumerSecretHash(Map<Long, WxConsumerEntity> consumers,
                                      WxSubscriptionDeliveryEntity row) {
        WxConsumerEntity consumer = consumers.get(row.getConsumerId());
        return consumer == null ? null : consumer.getSecretHash();
    }

    /**
     * WEBHOOK 2xx 成功汇报（§4：DELIVERED 终态）。条件 UPDATE 0 行 → 终态重放判定：
     * 同 lease 的 DELIVERED 重放幂等成功；异 lease/已离开 DISPATCHED → 10606。
     */
    public WebhookReportResult completeWebhook(long deliveryId, String leaseId, Integer responseCode) {
        WxSubscriptionDeliveryEntity row = requireDelivery(deliveryId);
        WebhookReportResult replay = replayOrLeaseLost(row, deliveryId, leaseId);
        if (replay != null) {
            return replay;
        }
        if (deliveryMapper.completeDelivered(deliveryId, leaseId, responseCode) > 0) {
            return new WebhookReportResult(false, ST_DELIVERED);
        }
        WebhookReportResult after = replayOrLeaseLost(requireDelivery(deliveryId), deliveryId, leaseId);
        if (after != null) {
            return after;
        }
        throw WxErrorCode.deliveryLeaseLost(deliveryId);
    }

    /**
     * WEBHOOK 失败汇报（§5.2）：attempts（认领时已 +1）≥ maxAttempts → DEAD（DLQ）；
     * 否则按指数退避重排 {@code next_attempt_at = now() + nextBackoffSeconds(base, attempts)}。
     * 重放判定口径同 {@link #completeWebhook}。
     */
    public WebhookReportResult failWebhook(long deliveryId, String leaseId, Integer responseCode, String error) {
        WxSubscriptionDeliveryEntity row = requireDelivery(deliveryId);
        WebhookReportResult replay = replayOrLeaseLost(row, deliveryId, leaseId);
        if (replay != null) {
            return replay;
        }
        int attempts = row.getAttempts() == null ? 1 : row.getAttempts();
        int maxAttempts = row.getMaxAttempts() == null ? 1 : row.getMaxAttempts();
        String trimmedError = trimError(error);
        int updated;
        if (attempts >= maxAttempts) {
            updated = deliveryMapper.failDead(deliveryId, leaseId, responseCode, trimmedError);
        } else {
            int backoff = nextBackoffSeconds(resolveBackoffBase(row.getSubscriptionId()), attempts);
            updated = deliveryMapper.requeueForRetry(deliveryId, leaseId, backoff, responseCode, trimmedError);
        }
        if (updated == 0) {
            WebhookReportResult after = replayOrLeaseLost(requireDelivery(deliveryId), deliveryId, leaseId);
            if (after != null) {
                return after;
            }
            throw WxErrorCode.deliveryLeaseLost(deliveryId);
        }
        log.info("[Delivery] webhook 失败汇报 delivery={} attempts={}/{}（{}）",
                deliveryId, attempts, maxAttempts,
                attempts >= maxAttempts ? "DEAD" : "退避重排");
        return new WebhookReportResult(false, attempts >= maxAttempts ? ST_DEAD : ST_PENDING);
    }

    /** 过期在飞租约回收（§4，30s 调度接线属 T10-5）。@return 回收行数 */
    public int sweepExpired() {
        return deliveryMapper.sweepExpiredLeases();
    }

    // ============================================================ POLL（§6）

    /**
     * 拉取（SQS 式可见性语义）：归属校验 → 仅 POLL 订阅 → 认领 → 组装消息摘要。
     *
     * <p>错误码裁决：订阅不存在/已软删 → 10605（资源缺失）；<b>归属不符 → 10300</b>
     * （设计 §6 把 consumer 鉴权失败归 10300，越权访问同族）；WEBHOOK 订阅 pull → 10402
     * （状态冲突：该订阅的行只能被 dispatcher 推送，两形互斥 §5.3/§6）。
     *
     * @param max 1..{@value PULL_MAX_CAP}（越界 10102；硬顶防一次性拖走过量）
     */
    public DeliveryVOs.PullResultVO pull(long subscriptionId, long consumerId, int max) {
        WxSubscriptionEntity sub = requireOwnedSubscription(subscriptionId, consumerId);
        requirePollMode(sub);
        int capped = requirePullMax(max);
        List<WxSubscriptionDeliveryEntity> rows = deliveryMapper.claimForPull(subscriptionId, capped);
        Map<Long, WxMessageEntity> messages = selectMessages(messageIds(rows));
        Map<Long, List<DeliveryVOs.DeliveryAttachmentVO>> attachments = selectAttachments(messageIds(rows));
        boolean hasMore = deliveryMapper.countDuePending(subscriptionId) > 0;
        List<DeliveryVOs.PullItemVO> items = new ArrayList<>(rows.size());
        for (WxSubscriptionDeliveryEntity row : rows) {
            items.add(new DeliveryVOs.PullItemVO(row.getId(), row.getLeaseId(),
                    row.getAttempts() == null ? 1 : row.getAttempts(),
                    buildMessageVO(messages.get(row.getMessageId()), attachments.get(row.getMessageId()))));
        }
        return new DeliveryVOs.PullResultVO(subscriptionId,
                sub.getVisibleSeconds() == null ? 60 : sub.getVisibleSeconds(), items, hasMore);
    }

    /**
     * ack 批量（§4）：逐条条件 UPDATE → DELIVERED。批量逐条处理、逐条回结果——
     * 部分失败不整批炸（10700 部分成功口径，data 非 null，mc-api-spec 例外）。
     * 同 lease 的 DELIVERED 重放幂等成功（网络重试 ack 不报错）；异 lease/已被重派 → 该条 10606。
     */
    public List<DeliveryVOs.AckItemResult> ack(long subscriptionId, long consumerId,
                                               List<DeliveryVOs.DeliveryAckCommand> items) {
        requireOwnedSubscription(subscriptionId, consumerId);
        List<DeliveryVOs.AckItemResult> results = new ArrayList<>(items == null ? 0 : items.size());
        if (items == null) {
            return results;
        }
        for (DeliveryVOs.DeliveryAckCommand item : items) {
            results.add(ackOne(subscriptionId, item));
        }
        return results;
    }

    private DeliveryVOs.AckItemResult ackOne(long subscriptionId, DeliveryVOs.DeliveryAckCommand item) {
        WxSubscriptionDeliveryEntity row = verifyDeliveryInScope(subscriptionId, item.deliveryId());
        WebhookReportResult replay = replayOrLeaseLost(row, item.deliveryId(), item.leaseId());
        if (replay != null) {
            return new DeliveryVOs.AckItemResult(item.deliveryId(), true, true, null);
        }
        // POLL ack 无 HTTP 响应 → responseCode 传 null（该列保持 NULL，与 WEBHOOK 成功行可区分）
        if (deliveryMapper.completeDelivered(item.deliveryId(), item.leaseId(), null) > 0) {
            return new DeliveryVOs.AckItemResult(item.deliveryId(), true, false, null);
        }
        WebhookReportResult after = replayOrLeaseLost(requireDelivery(item.deliveryId()),
                item.deliveryId(), item.leaseId());
        if (after != null) {
            return new DeliveryVOs.AckItemResult(item.deliveryId(), true, true, null);
        }
        return new DeliveryVOs.AckItemResult(item.deliveryId(), false, false,
                "10606 投递租约已失效（delivery_id=" + item.deliveryId() + "）");
    }

    /**
     * nack 批量（§4/§7「nack 立即重试」）：attempts 已在认领时 +1——
     * <b>首 nack（attempts≤1）立即重试</b>（backoff=0 → next_attempt_at=now()）；
     * 非首按指数退避；attempts ≥ maxAttempts → DEAD（DLQ）。重放/失效判定同 ack。
     */
    public List<DeliveryVOs.AckItemResult> nack(long subscriptionId, long consumerId,
                                                List<DeliveryVOs.DeliveryAckCommand> items) {
        WxSubscriptionEntity sub = requireOwnedSubscription(subscriptionId, consumerId);
        int base = resolveBackoffBase(sub.getBackoffBaseSeconds());
        List<DeliveryVOs.AckItemResult> results = new ArrayList<>(items == null ? 0 : items.size());
        if (items == null) {
            return results;
        }
        for (DeliveryVOs.DeliveryAckCommand item : items) {
            results.add(nackOne(subscriptionId, base, item));
        }
        return results;
    }

    private DeliveryVOs.AckItemResult nackOne(long subscriptionId, int base, DeliveryVOs.DeliveryAckCommand item) {
        WxSubscriptionDeliveryEntity row = verifyDeliveryInScope(subscriptionId, item.deliveryId());
        WebhookReportResult replay = replayOrLeaseLost(row, item.deliveryId(), item.leaseId());
        if (replay != null) {
            return new DeliveryVOs.AckItemResult(item.deliveryId(), true, true, null);
        }
        int attempts = row.getAttempts() == null ? 1 : row.getAttempts();
        int maxAttempts = row.getMaxAttempts() == null ? 1 : row.getMaxAttempts();
        int updated;
        if (attempts >= maxAttempts) {
            updated = deliveryMapper.failDead(item.deliveryId(), item.leaseId(), null, "nack: 重试额度用尽");
        } else {
            int backoff = attempts <= 1 ? 0 : nextBackoffSeconds(base, attempts);
            updated = deliveryMapper.requeueForRetry(item.deliveryId(), item.leaseId(), backoff, null, "nack");
        }
        if (updated == 0) {
            WebhookReportResult after = replayOrLeaseLost(requireDelivery(item.deliveryId()),
                    item.deliveryId(), item.leaseId());
            if (after != null) {
                return new DeliveryVOs.AckItemResult(item.deliveryId(), true, true, null);
            }
            return new DeliveryVOs.AckItemResult(item.deliveryId(), false, false,
                    "10606 投递租约已失效（delivery_id=" + item.deliveryId() + "）");
        }
        return new DeliveryVOs.AckItemResult(item.deliveryId(), true, false, null);
    }

    /**
     * 死信重排（§4 DLQ 救济）：整订阅 DEAD → PENDING（attempts=0、清凭据、立即到期）。
     * @return 重排行数
     */
    public int requeueDead(long subscriptionId, long consumerId) {
        requireOwnedSubscription(subscriptionId, consumerId);
        int rows = deliveryMapper.requeueDeadRows(subscriptionId);
        log.info("[Delivery] 死信重排 subscription={} rows={}", subscriptionId, rows);
        return rows;
    }

    /**
     * 误投重投（§4 v1.1 救济）：DELIVERED → PENDING（attempts 续算、立即到期）。
     * 仅 DELIVERED 可 redeliver（PENDING/DISPATCHED 本就未投 / DEAD 走 requeue-dead）→ 10402。
     */
    public long redeliver(long deliveryId, long consumerId) {
        WxSubscriptionDeliveryEntity row = requireDelivery(deliveryId);
        if (row.getConsumerId() == null || row.getConsumerId() != consumerId) {
            throw new ApiException(ApiCode.FORBIDDEN, "投递行不属于该消费者: " + deliveryId);
        }
        if (!ST_DELIVERED.equals(row.getStatus())) {
            throw new ApiException(ApiCode.STATE_CONFLICT,
                    "仅 DELIVERED 可 redeliver，当前 " + row.getStatus() + ": " + deliveryId);
        }
        if (deliveryMapper.redeliver(deliveryId) == 0) {
            // 并发竞态：已不再是 DELIVERED（被并发 redeliver / 清理）——按当前态定性
            throw new ApiException(ApiCode.STATE_CONFLICT,
                    "redeliver 竞态失败，行已离开 DELIVERED: " + deliveryId);
        }
        log.info("[Delivery] 误投重投 delivery={}（attempts 续算 {}）", deliveryId, row.getAttempts());
        return deliveryId;
    }

    /**
     * 投递行只读（admin redeliver 入口先取行自带 consumer_id 回填归属校验用）。
     * 不存在/物理缺失 → 10605（与 {@link #requireDelivery} 同口径，仅改public 可见性）。
     */
    public WxSubscriptionDeliveryEntity getDelivery(long deliveryId) {
        return requireDelivery(deliveryId);
    }

    // ============================================================ 终态重放判定（口径同 TaskService.replayOrLeaseLost）

    /**
     * <ul>
     *   <li>终态（DELIVERED/DEAD）且 lease 一致 → 返回重放结果（幂等成功，不报错）</li>
     *   <li>终态但 lease 不一致 → 10606（租约已易主）</li>
     *   <li>DISPATCHED → null（活动态，调用方继续条件 UPDATE）</li>
     *   <li>PENDING（租约已被 sweep 清掉/重派）→ 10606</li>
     * </ul>
     */
    private WebhookReportResult replayOrLeaseLost(WxSubscriptionDeliveryEntity row, long deliveryId, String leaseId) {
        String status = row.getStatus();
        if (ST_DISPATCHED.equals(status)) {
            return null;
        }
        if ((ST_DELIVERED.equals(status) || ST_DEAD.equals(status))
                && leaseId != null && leaseId.equals(row.getLeaseId())) {
            return new WebhookReportResult(true, status);
        }
        throw WxErrorCode.deliveryLeaseLost(deliveryId);
    }

    /** webhook 汇报结果：replayed=true 表示同 lease 终态重放（幂等，status 为既有终态）。 */
    public record WebhookReportResult(boolean replayed, String status) {
    }

    // ============================================================ 退避（纯函数）

    /**
     * 指数退避（§5.2）：{@code min(base × 2^(attempts-1), 3600)}。attempts 是认领后已 +1 的值
     * （第一次失败 attempts=1 → base）。倍增循环 + 途中钳制，attempts 再大也不溢出。
     * 样例（base=30）：1→30s，2→60s，3→120s，4→240s，5→480s，6→960s，7→1920s，8→3600s（封顶）。
     */
    static int nextBackoffSeconds(int baseSeconds, int attempts) {
        int base = Math.max(1, baseSeconds);
        long value = base;
        for (int i = 1; i < Math.max(1, attempts) && value < BACKOFF_CAP_SECONDS; i++) {
            value = Math.min(value * 2, BACKOFF_CAP_SECONDS);
        }
        return (int) Math.min(value, BACKOFF_CAP_SECONDS);
    }

    // ============================================================ 组装与内部查询

    private String buildPayloadJson(WxSubscriptionDeliveryEntity row, WxMessageEntity message,
                                    List<DeliveryVOs.DeliveryAttachmentVO> attachments, long epochSecond) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delivery_id", row.getId());
        payload.put("subscription_id", row.getSubscriptionId());
        payload.put("consumer_id", row.getConsumerId());
        payload.put("attempt", row.getAttempts() == null ? 1 : row.getAttempts());
        payload.put("timestamp", epochSecond);
        payload.put("message", buildMessageVO(message, attachments));
        try {
            return objectMapper.writeValueAsString(payload);   // 全局 Long→String 策略在此生效
        } catch (Exception e) {
            throw new IllegalStateException("投递载荷序列化失败: " + e, e);
        }
    }

    /** §5.1 message 摘要：content_raw/content_sha256 不投（大字段按需回查查询 API）。 */
    private DeliveryVOs.DeliveryMessageVO buildMessageVO(WxMessageEntity message,
                                                         List<DeliveryVOs.DeliveryAttachmentVO> attachments) {
        if (message == null) {
            return null;    // 消息被物理清理的极端场景：载荷 message=null，消费方按幂等键跳过
        }
        return new DeliveryVOs.DeliveryMessageVO(message.getId(), message.getDedupKey(),
                message.getChatUsername(), message.getIsGroup(), message.getSenderUsername(),
                message.getTypeCode(), message.getTypeName(), message.getBody(),
                message.getCreateTimeRaw(), message.getIsRevoked(),
                attachments == null ? List.of() : attachments);
    }

    private WxSubscriptionDeliveryEntity verifyDeliveryInScope(long subscriptionId, long deliveryId) {
        WxSubscriptionDeliveryEntity row = requireDelivery(deliveryId);
        if (row.getSubscriptionId() == null || row.getSubscriptionId() != subscriptionId) {
            throw WxErrorCode.subscriptionNotFound("该订阅下的投递行", deliveryId);
        }
        return row;
    }

    private WxSubscriptionDeliveryEntity requireDelivery(long deliveryId) {
        WxSubscriptionDeliveryEntity row = deliveryMapper.selectById(deliveryId);
        if (row == null) {
            throw WxErrorCode.subscriptionNotFound("投递行", deliveryId);
        }
        return row;
    }

    /** 订阅归属校验（拉取/ack/nack/requeue-dead 共用）：不存在 10605；非本人 → 10300（§6 口径）。 */
    private WxSubscriptionEntity requireOwnedSubscription(long subscriptionId, long consumerId) {
        WxSubscriptionEntity sub = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<WxSubscriptionEntity>()
                        .eq(WxSubscriptionEntity::getId, subscriptionId)
                        .eq(WxSubscriptionEntity::getIsDeleted, 0)
                        .last("LIMIT 1"));
        if (sub == null) {
            throw WxErrorCode.subscriptionNotFound("订阅", subscriptionId);
        }
        if (sub.getConsumerId() == null || sub.getConsumerId() != consumerId) {
            throw new ApiException(ApiCode.FORBIDDEN, "订阅不属于该消费者（error[].field=consumer_id）: " + subscriptionId);
        }
        return sub;
    }

    private void requirePollMode(WxSubscriptionEntity sub) {
        if (!"POLL".equals(sub.getDeliveryMode())) {
            throw new ApiException(ApiCode.STATE_CONFLICT,
                    "订阅投递形式为 " + sub.getDeliveryMode() + "，只能由 dispatcher 推送，不可 pull（两形互斥 §5.3/§6）");
        }
    }

    private int requirePullMax(int max) {
        if (max < 1 || max > PULL_MAX_CAP) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "max 必须 1.." + PULL_MAX_CAP + ": " + max);
        }
        return max;
    }

    /**
     * 退避基数：认领行只有 subscriptionId，回订阅表取 {@code backoff_base_seconds}。
     * **宽版读取（selectById，不过滤软删）**——订阅被软删后其存量在飞行仍必须能正常 fail/requeue
     * （否则失败汇报被 10605 炸掉，行只能等 sweep 兜底）；缺失兜底 30。
     */
    private int resolveBackoffBase(Long subscriptionId) {
        if (subscriptionId == null) {
            return DEFAULT_BACKOFF_BASE;
        }
        WxSubscriptionEntity sub = subscriptionMapper.selectById(subscriptionId);
        return resolveBackoffBase(sub == null ? null : sub.getBackoffBaseSeconds());
    }

    private int resolveBackoffBase(Integer baseSeconds) {
        return baseSeconds == null || baseSeconds <= 0 ? DEFAULT_BACKOFF_BASE : baseSeconds;
    }

    /** webhook 目标 URL：subscription.webhook_url 覆写 consumer.webhook_url（§2.2），皆空 → null。 */
    private String resolveUrl(WxSubscriptionEntity sub, WxConsumerEntity consumer) {
        if (sub != null && sub.getWebhookUrl() != null && !sub.getWebhookUrl().isBlank()) {
            return sub.getWebhookUrl();
        }
        if (consumer != null && consumer.getWebhookUrl() != null && !consumer.getWebhookUrl().isBlank()) {
            return consumer.getWebhookUrl();
        }
        return null;
    }

    private Map<Long, WxMessageEntity> selectMessages(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return messageMapper.selectBatchIds(messageIds).stream()
                .collect(Collectors.toMap(WxMessageEntity::getId, Function.identity()));
    }

    /** 批量取附件元数据并按 messageId 分组（§5.1 attachments 仅 kind/tier/md5/size）。 */
    private Map<Long, List<DeliveryVOs.DeliveryAttachmentVO>> selectAttachments(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        List<WxMessageAttachmentEntity> rows = attachmentMapper.selectList(
                new LambdaQueryWrapper<WxMessageAttachmentEntity>()
                        .in(WxMessageAttachmentEntity::getMessageId, messageIds));
        return rows.stream()
                .collect(Collectors.groupingBy(WxMessageAttachmentEntity::getMessageId,
                        Collectors.mapping(r -> new DeliveryVOs.DeliveryAttachmentVO(
                                r.getKind(), r.getTier(), r.getMd5(), r.getSize()),
                                Collectors.toList())));
    }

    private Map<Long, WxSubscriptionEntity> selectSubscriptions(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return subscriptionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(WxSubscriptionEntity::getId, Function.identity()));
    }

    private Map<Long, WxConsumerEntity> selectConsumers(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return consumerMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(WxConsumerEntity::getId, Function.identity()));
    }

    private List<Long> messageIds(List<WxSubscriptionDeliveryEntity> rows) {
        return rows.stream().map(WxSubscriptionDeliveryEntity::getMessageId)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    private List<Long> subscriptionIds(List<WxSubscriptionDeliveryEntity> rows) {
        return rows.stream().map(WxSubscriptionDeliveryEntity::getSubscriptionId)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    private List<Long> consumerIds(List<WxSubscriptionDeliveryEntity> rows) {
        return rows.stream().map(WxSubscriptionDeliveryEntity::getConsumerId)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    private String trimError(String error) {
        if (error == null) {
            return null;
        }
        String trimmed = error.trim();
        return trimmed.isEmpty() ? null
                : trimmed.length() <= ERROR_MAX_LEN ? trimmed : trimmed.substring(0, ERROR_MAX_LEN);
    }
}
