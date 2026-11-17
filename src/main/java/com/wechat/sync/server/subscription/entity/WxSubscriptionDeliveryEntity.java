package com.wechat.sync.server.subscription.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_subscription_delivery 行：投递队列（SUBSCRIPTION_DESIGN §2.3，核心表）——
 * 一条消息 × 一个订阅 = 一行；{@code uk_wx_subscription_delivery_2}（非部分索引）是
 * fan-out 去重 + 投递幂等双闸门（触发钩子与兜底补扫都走 ON CONFLICT DO NOTHING 入队）。
 *
 * <p>去规范化决策：{@code maxAttempts}/{@code leaseSeconds} **入队时从订阅快照**——改订阅
 * 只影响新投递，认领 SQL 无需回 join 订阅表取租约时长。终态行**保留 leaseId**
 * （重放判定凭据，v1.1 教训，口径同 wx_task）；本表无 is_deleted，行保留不删靠清理任务。
 */
@Data
@TableName("wx_subscription_delivery")
public class WxSubscriptionDeliveryEntity {

    /** 雪花 ID（API 契约上以 String 下发；消费方幂等键 delivery_id） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** wx_subscription.id（应用层保证） */
    private Long subscriptionId;

    /** wx_consumer.id 冗余快照（订阅删除后排障仍可归因） */
    private Long consumerId;

    /** wx_message.id（应用层保证） */
    private Long messageId;

    /** PENDING | DISPATCHED | DELIVERED | DEAD（SUBSCRIPTION_DESIGN §4 状态机） */
    private String status;

    /** 认领时 +1（口径同 wx_task）；≥ maxAttempts → DEAD */
    private Integer attempts;

    /** 重试上限（入队时从订阅快照） */
    private Integer maxAttempts;

    /** 在飞租约秒（入队时从订阅快照：POLL 可见性 / WEBHOOK 在飞租约） */
    private Integer leaseSeconds;

    /** 认领凭据（UUID）；终态保留，回 PENDING 时清空 */
    private String leaseId;

    /** 租约到期；过期由回收巡检打回 PENDING / 置 DEAD */
    private OffsetDateTime leaseUntil;

    /** 退避调度键（认领按它到期序 + id 排序；nack 非首次回队按退避排） */
    private OffsetDateTime nextAttemptAt;

    /** 消费方 HTTP 状态（排障；超时/连接失败为 NULL） */
    private Integer responseCode;

    /** 失败摘要（≤512 字符约定）；回收巡检置 'lease expired' */
    private String error;

    /** 进入 DELIVERED 终态时点 */
    private OffsetDateTime deliveredAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
