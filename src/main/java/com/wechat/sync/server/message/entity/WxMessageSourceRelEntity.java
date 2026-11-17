package com.wechat.sync.server.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_message_source_rel 行：一条全局消息 × N 个来源客户端（ABC 三人群只留一条消息的关键，
 * SYNCH_PLAN §4.2）。uk 四元组 {@code (account, chat_username, shard, local_id)} 保证同一来源行
 * 重复同步不产生新行；fb→srv 合并时 {@code message_id} 原地改指保留行。
 */
@Data
@TableName("wx_message_source_rel")
public class WxMessageSourceRelEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 上报客户端的账号 */
    private String account;

    private String chatUsername;

    /** 源库相对路径（local_id 只在分片内唯一，C1） */
    private String shard;

    /** 分片内 rowid */
    private Long localId;

    /** wx_message.id（应用层保证存在，规范禁外键） */
    private Long messageId;

    private OffsetDateTime seenAt;

    private OffsetDateTime createdAt;
}
