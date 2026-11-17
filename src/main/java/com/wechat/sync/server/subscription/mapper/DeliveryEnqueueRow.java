package com.wechat.sync.server.subscription.mapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 触发钩子批量入队行（SUBSCRIPTION_DESIGN §3.1）：一条新消息 × 一个命中订阅 = 一行。
 *
 * <p>{@code maxAttempts}/{@code leaseSeconds} 为<b>入队时订阅快照</b>（§2.3 去规范化：
 * 改订阅只影响新投递，认领 SQL 无需回 join 订阅表）。
 *
 * <p>用 POJO 而非 record：MyBatis {@code <foreach>} 内的嵌套属性访问走 getter 反射，
 * POJO + Lombok 在所有 MyBatis 3.5.x 版本上行为确定（record 的 accessor 形态在老版本
 * Reflector 上有兼容性暗礁，不值得为省 4 行去赌）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryEnqueueRow {

    /** wx_subscription.id */
    private long subscriptionId;

    /** wx_consumer.id（冗余快照，订阅删除后排障仍可归因） */
    private long consumerId;

    /** wx_message.id */
    private long messageId;

    /** 入队时订阅快照：重试上限（订阅 max_attempts） */
    private int maxAttempts;

    /** 入队时订阅快照：可见性/在飞租约秒（订阅 visible_seconds） */
    private int leaseSeconds;
}
