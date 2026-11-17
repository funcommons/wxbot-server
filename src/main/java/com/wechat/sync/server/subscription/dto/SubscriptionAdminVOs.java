package com.wechat.sync.server.subscription.dto;

import com.wechat.sync.server.subscription.entity.WxConsumerEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionDeliveryEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;

import java.time.OffsetDateTime;

/**
 * 订阅域管理端 VO 集（SUBSCRIPTION_DESIGN §2.1/§2.2 + T10-6）。
 *
 * <p>ID 出参一律 Long（框架 Jackson 全局 Long→String，mc-api-spec ID String 口径）。
 * <b>凭据哈希永不回显</b>（tokenHash/secretHash 不进任何 VO）——明文只在创建响应出现一次
 * （{@link ConsumerCreatedVO}，reg-code「只此一次」先例）；列表 VO 只带「是否已启用」布尔。
 */
public final class SubscriptionAdminVOs {

    private SubscriptionAdminVOs() {
    }

    /**
     * 建消费者响应：{@code pollToken}/{@code webhookSecret} 明文<b>只此一次</b>（按需生成——
     * 未启用的那把为 null）；服务端库存只有 sha256（WEBHOOK 签名密钥口径 = sha256hex(secret)，
     * §5.1 v1.1 补记——消费方拿到明文后自行 sha256 作 HMAC key）。
     */
    public record ConsumerCreatedVO(Long id, String name, String pollToken, String webhookSecret,
                                    String webhookUrl, String remark, OffsetDateTime createdAt) {
        public static ConsumerCreatedVO from(WxConsumerEntity e, String pollToken, String webhookSecret) {
            return new ConsumerCreatedVO(e.getId(), e.getName(), pollToken, webhookSecret,
                    e.getWebhookUrl(), e.getRemark(), e.getCreatedAt());
        }
    }

    /** 消费者列表/详情 VO：无任何凭据明文或哈希，仅「是否已配置」布尔（审计视图口径）。 */
    public record ConsumerVO(Long id, String name, boolean pollEnabled, boolean webhookEnabled,
                             String webhookUrl, String remark, OffsetDateTime createdAt) {
        public static ConsumerVO from(WxConsumerEntity e) {
            return new ConsumerVO(e.getId(), e.getName(),
                    e.getTokenHash() != null && !e.getTokenHash().isBlank(),
                    e.getSecretHash() != null && !e.getSecretHash().isBlank(),
                    e.getWebhookUrl(), e.getRemark(), e.getCreatedAt());
        }
    }

    /** 订阅管理 VO：{@code filter} 为规范化 JSONB 的<b>文本原样</b>（admin 审计视图，不做二次解析）。 */
    public record SubscriptionVO(Long id, Long consumerId, String name, String filter, String filterHash,
                                 String deliveryMode, String webhookUrl, String orderMode,
                                 Integer visibleSeconds, Integer maxAttempts, Integer backoffBaseSeconds,
                                 Integer enabled, OffsetDateTime createdAt) {
        public static SubscriptionVO from(WxSubscriptionEntity e) {
            return new SubscriptionVO(e.getId(), e.getConsumerId(), e.getName(), e.getFilter(),
                    e.getFilterHash(), e.getDeliveryMode(), e.getWebhookUrl(), e.getOrderMode(),
                    e.getVisibleSeconds(), e.getMaxAttempts(), e.getBackoffBaseSeconds(),
                    e.getEnabled(), e.getCreatedAt());
        }
    }

    /** 投递行管理 VO（§4 死信视图 / §7 可观测）：状态、凭据与退避调度键、失败落库信息。 */
    public record DeliveryRowVO(Long id, Long subscriptionId, Long consumerId, Long messageId,
                                String status, Integer attempts, Integer maxAttempts,
                                Integer leaseSeconds, String leaseId, OffsetDateTime nextAttemptAt,
                                Integer responseCode, String error, OffsetDateTime deliveredAt,
                                OffsetDateTime createdAt) {
        public static DeliveryRowVO from(WxSubscriptionDeliveryEntity e) {
            return new DeliveryRowVO(e.getId(), e.getSubscriptionId(), e.getConsumerId(), e.getMessageId(),
                    e.getStatus(), e.getAttempts(), e.getMaxAttempts(), e.getLeaseSeconds(), e.getLeaseId(),
                    e.getNextAttemptAt(), e.getResponseCode(), e.getError(), e.getDeliveredAt(),
                    e.getCreatedAt());
        }
    }
}
