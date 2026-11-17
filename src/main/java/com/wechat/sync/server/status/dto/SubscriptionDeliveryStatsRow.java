package com.wechat.sync.server.status.dto;

import java.time.OffsetDateTime;

/**
 * 订阅投递观测行（{@code WxStatusMapper.subscriptionDeliveryStats} 的映射目标，T10-6）。
 *
 * <p>MyBatis snake_case→camelCase 自动映射（map-underscore-to-camel-case，项目全局口径）；
 * {@code max(delivered_at)} 空表为 NULL → {@code lastDeliveredAt} 为 null（观测语义「从未投递」）。
 */
public record SubscriptionDeliveryStatsRow(long pendingCount, long dispatchedCount, long deadCount,
                                           OffsetDateTime lastDeliveredAt) {
}
