package com.wechat.sync.server.relation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_label_member_hint 行：无法唯一映射到联系人的标签成员线索（API_DESIGN §4.1 label_members）。
 *
 * <p>仅留痕（DB_DESIGN §7）：{@code member_username} 能唯一命中 wx_contact 的成员关系直接落
 * {@code wx_contact.tag_ids}，<strong>不进本表</strong>；映射不了才 upsert，{@code is_ambiguous} 置位。
 * 无 updated_at，upsert 保留 id / created_at。
 */
@Data
@TableName("wx_label_member_hint")
public class WxLabelMemberHintEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String account;

    /** wx_label_dict.id（int4，应用层引用，无外键） */
    private Integer dictId;

    /** UI 显示名（重名时无法唯一映射） */
    private String memberHint;

    /** 能反查出来时填，否则 NULL */
    private String memberUsername;

    private Boolean isAmbiguous;

    private OffsetDateTime createdAt;
}
