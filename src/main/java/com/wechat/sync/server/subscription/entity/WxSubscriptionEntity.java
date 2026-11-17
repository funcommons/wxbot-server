package com.wechat.sync.server.subscription.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wechat.sync.server.common.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_subscription 行：条件订阅（SUBSCRIPTION_DESIGN §2.2）——filter（JSONB）+ 投递形式 + 顺序/重试参数。
 *
 * <p>{@code filter} 是全包**唯一 jsonb 列**：实体侧 String 文本透传 + {@link JsonbTypeHandler}
 * 写入（口径同 {@code WxTaskEntity.payload}）；{@code filter_hash} = 规范化条件的 sha256，
 * 与 consumer_id 组成部分唯一索引 {@code uk_wx_subscription_filter}（防重复订阅，API 层闸门——
 * 重复 POST 返回既有行 + already_exists，不报错）。{@code visible_seconds} 双用：
 * POLL 可见性超时 / WEBHOOK 在飞租约。
 */
@Data
@TableName(value = "wx_subscription", autoResultMap = true)
public class WxSubscriptionEntity {

    /** 雪花 ID（API 契约上以 String 下发） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** wx_consumer.id（应用层保证，规范禁外键） */
    private Long consumerId;

    /** 订阅名称（展示/排障用，可空） */
    private String name;

    /** 匹配条件 JSONB 文本透传（'{}' = 全量订阅；键之间 AND，语义见 SUBSCRIPTION_DESIGN §2.2） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String filter;

    /** 规范化条件的 sha256（防重复订阅键；数值/字符串字面量归一，v1.1） */
    private String filterHash;

    /** POLL | WEBHOOK */
    private String deliveryMode;

    /** mode=WEBHOOK 的回调地址；空则回落 consumer.webhook_url */
    private String webhookUrl;

    /** PARALLEL | STRICT_FIFO（单飞窗口 = 同时至多 1 条 DISPATCHED） */
    private String orderMode;

    /** POLL 可见性超时 / WEBHOOK 在飞租约秒 */
    private Integer visibleSeconds;

    /** 重试上限（入队时快照到投递行，改订阅只影响新投递） */
    private Integer maxAttempts;

    /** 指数退避基数秒（§5：base×2^(n-1) 封顶 3600） */
    private Integer backoffBaseSeconds;

    /** 启停：1 启用 / 0 停用（停用后认领 SQL 与补扫都跳过） */
    private Integer enabled;

    /** 软删：0 正常 / 1 已删除 */
    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
