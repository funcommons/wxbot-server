package com.wechat.sync.server.relation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_group_member_rel 行：群成员关系（API_DESIGN §4.1 group_members）。
 *
 * <p>uk(account, group_username, member_username) 三元组唯一，两端都是 username，绝不用本地 rowid；
 * 同一 username 在不同账号下 nick_name 允许不同（ABC 实跑三行并存）。无 updated_at，
 * upsert 保留 id / created_at。
 */
@Data
@TableName("wx_group_member_rel")
public class WxGroupMemberRelEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String account;

    private String groupUsername;

    private String memberUsername;

    private String nickName;

    private String remark;

    private Boolean isOwner;

    private OffsetDateTime createdAt;
}
