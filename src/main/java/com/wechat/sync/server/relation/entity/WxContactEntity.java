package com.wechat.sync.server.relation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wechat.sync.server.relation.support.IntArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_contact 行：关系层联系人（API_DESIGN §4.1 contacts / DB_DESIGN §5.3）。
 *
 * <p>按 {@code (account, username)} 存（部分唯一索引 uk 仅约束 {@code is_deleted=0}），不跨账号合并
 * （SYNCH_PLAN §6.5）。upsert 覆盖业务字段但保留 {@code id / created_at / tag_ids}——tag_ids 只由
 * 标签映射写，contacts 上报不清空它。主键雪花；{@code tag_ids INT[]} 走 {@link IntArrayTypeHandler}。
 */
@Data
@TableName(value = "wx_contact", autoResultMap = true)
public class WxContactEntity {

    /** 雪花 ID（API 契约以 String 下发） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String account;

    /** 全局身份：wxid_* / gh_* / xxx@chatroom */
    private String username;

    /** CONTACT | STRANGER（API 收小写，服务端归一化大写，DB_DESIGN §8） */
    private String source;

    /** 源侧本地 rowid，仅排障，不可做键 */
    private Long contactId;

    private String nickName;

    private String remark;

    /** 微信号，用户可改，不可做键 */
    private String alias;

    /** 0 系统 / 1 好友公众号 / 2 群 */
    private Integer localType;

    private Boolean isInChatRoom;

    private Integer deleteFlag;

    private String bigHeadUrl;

    private String description;

    /** wx_label_dict.id 集合（int4[] + GIN，筛选走 {@code tag_ids && ARRAY[?]}，DB_DESIGN §7） */
    @TableField(typeHandler = IntArrayTypeHandler.class)
    private int[] tagIds;

    /** 动态扩展属性（JSONB 文本透传，默认 {@code {}}；本接口不上报，仅读） */
    private String attributes;

    /** 软删：0 正常 / 1 已删除 */
    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
