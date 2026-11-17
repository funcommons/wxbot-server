package com.wechat.sync.server.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_account 行：同步客户端注册表 + 账号身份（1 客户端 = 1 微信号）。
 *
 * <p>字段对齐 {@code schema.sql} DDL（无外键；软删 {@code is_deleted}；部分唯一索引
 * {@code uk_wx_account_account} 仅约束 {@code is_deleted=0}）。
 * <p>主键为雪花 ID：{@code IdType.ASSIGN_ID} 委托 framework4j-id 注册的
 * {@code MpIdGenerator}（MyBatis Plus {@code IdentifierGenerator}，worker id 走 Redis 租约）。
 * DDL 的 {@code BIGSERIAL} 默认值仅在应用不赋值时兜底，正常路径不会触发。
 */
@Data
@TableName("wx_account")
public class WxAccountEntity {

    /** 雪花 ID（API 契约上以 String 下发，framework4j-web 全局 Long→String） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 目录名，如 wxid_c00pwixax7xv22_8316（is_deleted=0 内唯一） */
    private String account;

    /** 剥掉末尾 4 位后缀的 wxid */
    private String wxid;

    /** 客户端所在机器（心跳上报） */
    private String hostname;

    /** 客户端版本 */
    private String clientVersion;

    /** 静态令牌模式可空；framework4j-accesstoken 模式下令牌态在 Redis */
    private String apiKeyHash;

    /** 最近一次成功同步（游标推进） */
    private OffsetDateTime lastSyncAt;

    /** 最近一次心跳/上报 */
    private OffsetDateTime lastSeenAt;

    /** 软删标记：0 正常 / 1 已删除 */
    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
