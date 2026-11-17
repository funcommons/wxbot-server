package com.wechat.sync.server.relation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_group 行：关系层群实体（API_DESIGN §4.1 groups）。
 *
 * <p>按 {@code (account, username)} 存（部分唯一索引 uk 仅约束 {@code is_deleted=0}）；
 * upsert 覆盖业务字段但保留 {@code id / created_at}。
 */
@Data
@TableName("wx_group")
public class WxGroupEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String account;

    /** 完整 xxx@chatroom */
    private String username;

    /** 源侧本地 rowid，仅排障 */
    private Long roomId;

    private String ownerUsername;

    /** 常为 NULL（C9：群名不在 contact.db 里，可选来源按优先级补录，SYNCH_PLAN §6.5） */
    private String displayName;

    private Integer memberCount;

    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
