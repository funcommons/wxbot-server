package com.wechat.sync.server.subscription.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.common.WxErrorCode;
import com.wechat.sync.server.subscription.entity.WxConsumerEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.mapper.WxConsumerMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 订阅 CRUD（SUBSCRIPTION_DESIGN §2.2/§9）：filter 校验/规范化/hash、重复订阅幂等、软删。
 *
 * <p><b>防重复订阅（API 层闸门）</b>：{@code uk(consumer_id, filter_hash)}（部分唯一，软删行除外）——
 * 同 consumer 同条件重复 POST <b>不报错</b>，返回既有行 + {@code already_exists=true}；
 * 「先查后插」竞态由 INSERT 撞 {@link DuplicateKeyException} 后复查兜底（AccountService 10401 先例）。
 * 队列层闸门是投递行的 {@code uk(subscription_id, message_id)}（触发/补扫两侧 ON CONFLICT）。
 *
 * <p><b>filter 不可变</b>：更新命令在类型层面就不携带 filter 字段（{@link UpdateCommand}）——
 * 改条件 = 删旧建新（§2.2 uk 以 filter_hash 为键，原地改 hash 会让「重复订阅幂等」语义失效）；
 * 若上层 DTO（T10-6）误收 filter 键，须在 Controller 层 10604 拒绝。
 *
 * <p><b>边界（§9，文档明示）</b>：订阅只管创建之后，<b>无回填</b>——条件订阅从创建时刻起生效
 * （匹配器 30s 快照刷新，最早晚一个周期），历史消息走查询 API。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    /** delivery_mode / order_mode 闭集 */
    private static final Set<String> DELIVERY_MODES = Set.of("POLL", "WEBHOOK");
    private static final Set<String> ORDER_MODES = Set.of("PARALLEL", "STRICT_FIFO");

    /** 数值参数界（缺省=设计 §2.2 缺省列；越界 10102，照任务域 max_attempts 口径） */
    private static final int DEFAULT_VISIBLE_SECONDS = 60;
    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    private static final int DEFAULT_BACKOFF_BASE_SECONDS = 30;
    private static final int VISIBLE_SECONDS_MAX = 86400;
    private static final int MAX_ATTEMPTS_MAX = 100;
    private static final int BACKOFF_BASE_MAX = 3600;
    private static final int WEBHOOK_URL_MAX = 512;

    private final WxSubscriptionMapper subscriptionMapper;
    private final WxConsumerMapper consumerMapper;

    // ==================== create ====================

    /**
     * 创建订阅（重复条件幂等返回既有行）。filter 走 {@link SubscriptionFilter#normalize} 校验 +
     * 规范化（非法 10604），hash=规范形 JSON sha256；参数校验（枚举/越界）10102，消费者缺失 10605。
     */
    public CreateResult create(CreateCommand cmd) {
        requireConsumer(cmd.consumerId());
        validateWebhookUrlForIngest(cmd);

        Map<String, Object> normalized = SubscriptionFilter.normalize(cmd.filter());
        String filterJson = SubscriptionFilter.toJson(normalized);
        String filterHash = SubscriptionFilter.sha256Hex(filterJson);

        WxSubscriptionEntity existing = selectActive(cmd.consumerId(), filterHash);
        if (existing != null) {
            return new CreateResult(existing, true);      // 重复订阅不报错（§2.2 API 层闸门）
        }

        WxSubscriptionEntity row = new WxSubscriptionEntity();
        row.setConsumerId(cmd.consumerId());
        row.setName(trimToNull(cmd.name()));
        row.setFilter(filterJson);
        row.setFilterHash(filterHash);
        row.setDeliveryMode(normalizeMode(cmd.deliveryMode(), DELIVERY_MODES, "POLL", "delivery_mode"));
        row.setWebhookUrl(requireWebhookUrl(cmd.webhookUrl()));
        row.setOrderMode(normalizeMode(cmd.orderMode(), ORDER_MODES, "PARALLEL", "order_mode"));
        row.setVisibleSeconds(requireRange(cmd.visibleSeconds(), DEFAULT_VISIBLE_SECONDS,
                1, VISIBLE_SECONDS_MAX, "visible_seconds"));
        row.setMaxAttempts(requireRange(cmd.maxAttempts(), DEFAULT_MAX_ATTEMPTS,
                1, MAX_ATTEMPTS_MAX, "max_attempts"));
        row.setBackoffBaseSeconds(requireRange(cmd.backoffBaseSeconds(), DEFAULT_BACKOFF_BASE_SECONDS,
                1, BACKOFF_BASE_MAX, "backoff_base_seconds"));
        row.setEnabled(1);
        row.setIsDeleted(0);
        try {
            subscriptionMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发同条件创建：撞 uk(consumer_id, filter_hash) → 复查既有行（幂等语义不变）
            WxSubscriptionEntity winner = selectActive(cmd.consumerId(), filterHash);
            if (winner == null) {
                throw e;
            }
            return new CreateResult(winner, true);
        }
        log.info("[Subscription] 创建订阅 id={} consumer={} mode={} hash={} filter={}",
                row.getId(), cmd.consumerId(), row.getDeliveryMode(), filterHash, filterJson);
        return new CreateResult(row, false);
    }

    // ==================== update / delete / get / list ====================

    /**
     * 部分更新（至少一键，否则 10101 照任务域口径）。<b>filter 不可变</b>：本命令类型上就不含
     * filter（类 javadoc）；enabled=0 与 delete 语义不同——前者只是停投，行仍可再启。
     */
    public WxSubscriptionEntity update(long subscriptionId, UpdateCommand cmd) {
        WxSubscriptionEntity row = requireSubscription(subscriptionId);
        if (cmd == null || (cmd.enabled() == null && trimToNull(cmd.webhookUrl()) == null
                && cmd.orderMode() == null && cmd.visibleSeconds() == null
                && cmd.maxAttempts() == null && cmd.backoffBaseSeconds() == null)) {
            throw new ApiException(ApiCode.PARAM_MISSING, "至少提供一个可更新字段");
        }
        LambdaUpdateWrapper<WxSubscriptionEntity> wrapper = new LambdaUpdateWrapper<WxSubscriptionEntity>()
                .eq(WxSubscriptionEntity::getId, subscriptionId)
                .set(WxSubscriptionEntity::getUpdatedAt, OffsetDateTime.now());
        if (cmd.enabled() != null) {
            if (cmd.enabled() != 0 && cmd.enabled() != 1) {
                throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "enabled 只允许 0/1: " + cmd.enabled());
            }
            wrapper.set(WxSubscriptionEntity::getEnabled, cmd.enabled());
        }
        if (trimToNull(cmd.webhookUrl()) != null) {
            com.wechat.sync.server.subscription.webhook.WebhookUrlValidator.validate(cmd.webhookUrl());
            wrapper.set(WxSubscriptionEntity::getWebhookUrl, requireWebhookUrl(cmd.webhookUrl()));
        }
        if (cmd.orderMode() != null) {
            wrapper.set(WxSubscriptionEntity::getOrderMode,
                    normalizeMode(cmd.orderMode(), ORDER_MODES, null, "order_mode"));
        }
        if (cmd.visibleSeconds() != null) {
            wrapper.set(WxSubscriptionEntity::getVisibleSeconds, requireRange(cmd.visibleSeconds(),
                    null, 1, VISIBLE_SECONDS_MAX, "visible_seconds"));
        }
        if (cmd.maxAttempts() != null) {
            wrapper.set(WxSubscriptionEntity::getMaxAttempts, requireRange(cmd.maxAttempts(),
                    null, 1, MAX_ATTEMPTS_MAX, "max_attempts"));
        }
        if (cmd.backoffBaseSeconds() != null) {
            wrapper.set(WxSubscriptionEntity::getBackoffBaseSeconds, requireRange(cmd.backoffBaseSeconds(),
                    null, 1, BACKOFF_BASE_MAX, "backoff_base_seconds"));
        }
        subscriptionMapper.update(null, wrapper);
        return requireSubscription(subscriptionId);
    }

    /** 软删（is_deleted=1 + enabled=0；投递行不动——队列只增不改，§2.3）。已删/不存在 → 10605。 */
    public void delete(long subscriptionId) {
        requireSubscription(subscriptionId);
        subscriptionMapper.update(null, new LambdaUpdateWrapper<WxSubscriptionEntity>()
                .eq(WxSubscriptionEntity::getId, subscriptionId)
                .set(WxSubscriptionEntity::getIsDeleted, 1)
                .set(WxSubscriptionEntity::getEnabled, 0)
                .set(WxSubscriptionEntity::getUpdatedAt, OffsetDateTime.now()));
        log.info("[Subscription] 软删订阅 id={}（投递行保留，新触发自本时刻停止）", subscriptionId);
    }

    /** 单查（未软删）；不存在/已删 → 10605。 */
    public WxSubscriptionEntity get(long subscriptionId) {
        return requireSubscription(subscriptionId);
    }

    /** 分页列表（软删过滤；consumerId 可选过滤）。 */
    public PageVO<WxSubscriptionEntity> list(Long consumerId, long page, long pageSize) {
        PageVO.PageParams pp = PageVO.clamp(page, pageSize);
        LambdaQueryWrapper<WxSubscriptionEntity> wrapper = new LambdaQueryWrapper<WxSubscriptionEntity>()
                .eq(WxSubscriptionEntity::getIsDeleted, 0)
                .orderByDesc(WxSubscriptionEntity::getId);
        if (consumerId != null) {
            wrapper.eq(WxSubscriptionEntity::getConsumerId, consumerId);
        }
        Page<WxSubscriptionEntity> result = subscriptionMapper.selectPage(new Page<>(pp.page(), pp.pageSize()), wrapper);
        return PageVO.of(result.getRecords(), result.getTotal(), pp.page(), pp.pageSize());
    }

    // ==================== 入参 record ====================

    /**
     * create 入参（T10-6 Controller 绑定后构造；filter 为任意 JSON 对象，校验在
     * {@link SubscriptionFilter#normalize}）。deliveryMode 缺省 POLL、orderMode 缺省 PARALLEL。
     */
    public record CreateCommand(long consumerId, String name, Map<String, Object> filter,
                                String deliveryMode, String webhookUrl, String orderMode,
                                Integer visibleSeconds, Integer maxAttempts, Integer backoffBaseSeconds) {
    }

    /**
     * update 入参：null = 该字段不改。<b>刻意不含 filter</b>——订阅条件不可变（改条件=删旧建新），
     * 类型层面杜绝；javadoc 见类头。
     */
    public record UpdateCommand(Integer enabled, String webhookUrl, String orderMode,
                                Integer visibleSeconds, Integer maxAttempts, Integer backoffBaseSeconds) {
    }

    /** create 产出：alreadyExists=true 时 subscription 为既有行（重复订阅幂等，不报错）。 */
    public record CreateResult(WxSubscriptionEntity subscription, boolean alreadyExists) {
    }

    // ==================== 内部 ====================

    /**
     * 录入时 SSRF 闸（§5.4「录入与派发前<b>双闸</b>」——派发前全量校验在 WebhookDispatcher，
     * 此处接线防配置进来就坏）：生效 URL = 订阅级 webhookUrl 覆写 consumer 级（§2.2），
     * 非空即 {@link WebhookUrlValidator#validate}（违规 10607）。resolution 不另行查消费者行
     * （订阅级为空且 mode=WEBHOOK 时才补查，POLL 订阅零额外开销）。
     */
    private void validateWebhookUrlForIngest(CreateCommand cmd) {
        String url = trimToNull(cmd.webhookUrl());
        if (url == null && "WEBHOOK".equalsIgnoreCase(trimToNull(cmd.deliveryMode()))) {
            WxConsumerEntity consumer = consumerMapper.selectById(cmd.consumerId());
            url = consumer == null ? null : trimToNull(consumer.getWebhookUrl());
        }
        if (url != null) {
            com.wechat.sync.server.subscription.webhook.WebhookUrlValidator.validate(url);
        }
    }

    private void requireConsumer(long consumerId) {
        Long count = consumerMapper.selectCount(new LambdaQueryWrapper<WxConsumerEntity>()
                .eq(WxConsumerEntity::getId, consumerId)
                .eq(WxConsumerEntity::getIsDeleted, 0));
        if (count == null || count == 0) {
            throw WxErrorCode.subscriptionNotFound("消费者", consumerId);
        }
    }

    private WxSubscriptionEntity requireSubscription(long subscriptionId) {
        WxSubscriptionEntity row = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<WxSubscriptionEntity>()
                        .eq(WxSubscriptionEntity::getId, subscriptionId)
                        .eq(WxSubscriptionEntity::getIsDeleted, 0)
                        .last("LIMIT 1"));
        if (row == null) {
            throw WxErrorCode.subscriptionNotFound("订阅", subscriptionId);
        }
        return row;
    }

    private WxSubscriptionEntity selectActive(long consumerId, String filterHash) {
        return subscriptionMapper.selectOne(new LambdaQueryWrapper<WxSubscriptionEntity>()
                .eq(WxSubscriptionEntity::getConsumerId, consumerId)
                .eq(WxSubscriptionEntity::getFilterHash, filterHash)
                .eq(WxSubscriptionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    /** 枚举归一：null/blank → 缺省（update 场景传 null 表示「已由调用方跳过」，不该走到这里）。 */
    private String normalizeMode(String value, Set<String> allowed, String fallback, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return fallback;
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR,
                    field + " 只允许 " + String.join("/", allowed) + ": " + value);
        }
        return upper;
    }

    /** 数值界校验：null → 缺省（缺省也为 null 时表示调用方已跳过）；越界 10102。 */
    private Integer requireRange(Integer value, Integer fallback, int min, int max, String field) {
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR,
                    field + " 必须 " + min + ".." + max + ": " + value);
        }
        return value;
    }

    /** webhook_url 形式校验（http/https + ≤512；深度 SSRF 校验属 T10-5 派发前，此处不重复造）。 */
    private String requireWebhookUrl(String webhookUrl) {
        String trimmed = trimToNull(webhookUrl);
        if (trimmed == null) {
            return null;                             // 空 = 回落 consumer.webhook_url（§2.2）
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "webhook_url 必须以 http(s):// 开头");
        }
        if (trimmed.length() > WEBHOOK_URL_MAX) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR,
                    "webhook_url 超过最大 " + WEBHOOK_URL_MAX + " 字符");
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
