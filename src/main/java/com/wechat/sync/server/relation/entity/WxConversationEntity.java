package com.wechat.sync.server.relation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_conversation 行：会话（API_DESIGN §4.1 conversations）。
 *
 * <p>按 {@code (account, username)} 存（部分唯一索引 uk 仅约束 {@code is_deleted=0}）；
 * display_name 口径 remark or nick_name or username（读取层归一）。upsert 覆盖业务字段但保留
 * {@code id / created_at}。
 */
@Data
@TableName("wx_conversation")
public class WxConversationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String account;

    /** 私聊 wxid 或群 xxx@chatroom */
    private String username;

    private Boolean isGroup;

    private String displayName;

    private OffsetDateTime lastMessageAt;

    private Long messageCount;

    private Integer unread;

    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
