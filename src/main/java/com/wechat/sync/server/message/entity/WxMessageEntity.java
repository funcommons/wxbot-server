package com.wechat.sync.server.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_message 行：跨账号去重后的全局消息事实（schema.sql / SYNCH_PLAN §4）。
 *
 * <p>{@code dedup_key} 是唯一权威键（uk_wx_message_dedup_key）：{@code srv:<server_id>} 首选、
 * {@code fb:<chat>|<sender>|<ts>|<sha>} 兜底；不变量 {@code ck_wx_message_srv_dedup} 要求 server_id
 * 有效时键必须同源——fb→srv 合并必须「键与 server_id 同一条 UPDATE 写」。{@code seen_accounts} 由
 * wx_message_source_rel 反算（永不 +1）；{@code created_at} 首次落库时间，upsert 不碰。
 */
@Data
@TableName("wx_message")
public class WxMessageEntity {

    /** 雪花 ID（API 契约以 String 下发） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 'srv:&lt;id|raw&gt;' 或 'fb:&lt;chat&gt;|&lt;sender&gt;|&lt;ts&gt;|&lt;sha256&gt;'，UNIQUE */
    private String dedupKey;

    private String chatUsername;

    /** chat_username 以 {@code @chatroom} 结尾 */
    private Boolean isGroup;

    /** 服务端消息 ID；0 / 缺失 → NULL，BIGINT 溢出 → NULL + server_id_raw */
    private Long serverId;

    /** BIGINT 溢出兜底的原始串（≤64 字符） */
    private String serverIdRaw;

    /** 已解析真实 wxid（不用裸 real_sender_id） */
    private String senderUsername;

    /** SHARD_NAME2ID | BODY_PREFIX | CARD_XML */
    private String senderSource;

    /** local_type 原值（4.x 复合码，String 防超精度） */
    private String typeCode;

    private String typeName;

    /** 实测=create_time*1000（毫秒），仅快路径游标 */
    private Long sortSeq;

    /** 微信消息时间（排序权威，数据事实） */
    private OffsetDateTime createTime;

    /** Unix 秒原值 */
    private Long createTimeRaw;

    /** 已剥群前缀正文 */
    private String body;

    /** 原始正文（XML 原文等，可大） */
    private String contentRaw;

    /** 归一化正文 sha256（剥前缀后，fb 键组成部分） */
    private String contentSha256;

    private Boolean isRevoked;

    private OffsetDateTime revokedAt;

    /** 兜底键疑似误合并：标记不删（ingest 不置位，归并任务的事） */
    private Boolean suspectDuplicate;

    /** 由 wx_message_source_rel 反算（count(DISTINCT account)） */
    private Integer seenAccounts;

    private OffsetDateTime createdAt;

    private OffsetDateTime lastSeenAt;

    private OffsetDateTime updatedAt;
}
