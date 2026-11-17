package com.wechat.sync.server.subscription.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_sub_delivery_cursor 行：漏匹配补扫的水位（SUBSCRIPTION_DESIGN §2.4，v1.1 补 DDL）。
 *
 * <p>单行行式调度状态（{@code scope} 现阶段固定 {@code 'global'}）：记录补扫已覆盖到的
 * {@code wx_message.id} 高水位。纯调度水位——无 GIN、无软删；行级 upsert
 * {@code ON CONFLICT (scope) DO UPDATE … GREATEST} 乐观防回退（见
 * {@code WxSubDeliveryCursorMapper#upsertGlobalCursor}）；无种子数据，行由补扫首轮自建。
 */
@Data
@TableName("wx_sub_delivery_cursor")
public class WxSubDeliveryCursorEntity {

    /** 雪花 ID（生产写入用雪花，DDL BIGSERIAL 仅兜底） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 游标域；现阶段固定单行 'global'（uk_wx_sub_delivery_cursor_scope 唯一） */
    private String scope;

    /** 补扫已覆盖到的 wx_message.id 高水位（GREATEST 只前进） */
    private Long lastMessageId;

    private OffsetDateTime updatedAt;
}
