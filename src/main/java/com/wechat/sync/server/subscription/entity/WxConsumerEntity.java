package com.wechat.sync.server.subscription.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_consumer 行：外部消费系统的身份 + 凭据（SUBSCRIPTION_DESIGN §2.1）。
 *
 * <p>POLL Bearer 令牌与 WEBHOOK HMAC 密钥**均 sha256 哈希入库**（明文只在创建响应中出现一次，
 * {@code wx_account.api_key_hash} / {@code wx_reg_code.code_hash} 先例）；二者独立——
 * 只拉不推的消费者可以没有 secret。字段对齐 {@code schema.sql} v6 增量块。
 */
@Data
@TableName("wx_consumer")
public class WxConsumerEntity {

    /** 雪花 ID（API 契约上以 String 下发，framework4j-web 全局 Long→String） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 消费者名称（uk_wx_consumer_name 部分唯一：仅约束未删行，软删后同名可重建） */
    private String name;

    /** POLL Bearer 令牌 sha256；NULL = 未启用 POLL */
    private String tokenHash;

    /** WEBHOOK HMAC-SHA256 密钥 sha256；NULL = 未启用 WEBHOOK */
    private String secretHash;

    /** 缺省回调地址；订阅可覆写（§2.2） */
    private String webhookUrl;

    /** 备注（排障用） */
    private String remark;

    /** 软删：0 正常 / 1 已删除 */
    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
