package com.wechat.sync.server.relation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_label_dict 行：标签字典（API_DESIGN §4.1 labels / DB_DESIGN §7）。
 *
 * <p><strong>id 是 SERIAL(int4)</strong>——intarray 不支持 bigint[]，{@code wx_contact.tag_ids} 存的就是它，
 * 故主键用 {@code IdType.AUTO}（INSERT 不带 id，DB 生成后经 getGeneratedKeys 回填实体），与其它实体的
 * ASSIGN_ID 雪花刻意不同。uk(account, tag_value) 全唯一；重命名只改 tag_value 不扫 wx_contact。
 */
@Data
@TableName("wx_label_dict")
public class WxLabelDictEntity {

    /** SERIAL int4，DB 生成（存入 wx_contact.tag_ids） */
    @TableId(type = IdType.AUTO)
    private Integer id;

    private String account;

    /** 微信标签名（label_name） */
    private String tagValue;

    private Integer sortOrder;

    /** DB | UI（DB 只有定义，成员关系靠 UI，DB_DESIGN §7/§8） */
    private String source;

    private Integer memberCount;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
