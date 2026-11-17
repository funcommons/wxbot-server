package com.wechat.sync.server.relation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * POST /v1/reports/relations 请求体（API_DESIGN §4.1）：顶层 {@code account} + 六个数组，均可为空/缺省。
 * JSON 字段名 snake_case 由 framework4j-web 全局 PropertyNamingStrategy 转换（record 组件用驼峰）。
 *
 * <p>校验口径：元素级「必填非空」不放 bean validation（框架把 MethodArgumentNotValidException 翻成
 * 10100，而契约要求 10101/10102），由 {@code RelationsIngestService} 显式校验；这里只保留
 * {@code @Size}/{@code @Min} 作超长防御（命中走 10100）。
 */
public record RelationsReportRequest(
        @Size(max = 64, message = "account 长度不能超过 64")
        String account,

        @Size(max = 20000, message = "contacts 数量不能超过 20000")
        List<@Valid ContactItem> contacts,

        @Size(max = 20000, message = "groups 数量不能超过 20000")
        List<@Valid GroupItem> groups,

        @Size(max = 200000, message = "group_members 数量不能超过 200000")
        List<@Valid GroupMemberItem> groupMembers,

        @Size(max = 1000, message = "labels 数量不能超过 1000")
        List<@Valid LabelItem> labels,

        @Size(max = 100000, message = "label_members 数量不能超过 100000")
        List<@Valid LabelMemberItem> labelMembers,

        @Size(max = 20000, message = "conversations 数量不能超过 20000")
        List<@Valid ConversationItem> conversations) {

    /** 联系人元素（contact 表 + stranger 表同构，source 列区分，CONTACT 优先）。 */
    public record ContactItem(
            /** 全局身份：wxid_* / gh_* / xxx@chatroom，必填非空（服务端 10102） */
            @Size(max = 64, message = "username 长度不能超过 64")
            String username,

            /** contact | stranger，缺省 contact，越界 10102 */
            String source,

            /** 源侧 rowid，契约 String（JSON 数字/字符串均可），落库 BIGINT */
            @Size(max = 32, message = "contact_id 长度不能超过 32")
            String contactId,

            @Size(max = 128, message = "nick_name 长度不能超过 128")
            String nickName,

            @Size(max = 128, message = "remark 长度不能超过 128")
            String remark,

            @Size(max = 64, message = "alias 长度不能超过 64")
            String alias,

            /** 0 系统 / 1 好友公众号 / 2 群 */
            @Min(value = 0, message = "local_type 不能为负数")
            Integer localType,

            Boolean isInChatRoom,

            Integer deleteFlag,

            @Size(max = 1024, message = "big_head_url 长度不能超过 1024")
            String bigHeadUrl,

            @Size(max = 1024, message = "description 长度不能超过 1024")
            String description) {
    }

    /** 群元素。 */
    public record GroupItem(
            @Size(max = 64, message = "username 长度不能超过 64")
            String username,

            @Size(max = 32, message = "room_id 长度不能超过 32")
            String roomId,

            @Size(max = 64, message = "owner_username 长度不能超过 64")
            String ownerUsername,

            /** C9：群名常为 null */
            @Size(max = 128, message = "display_name 长度不能超过 128")
            String displayName,

            Integer memberCount) {
    }

    /** 群成员元素：member_username 解析不了时不上报该元素（C10，落库以 nick_name 占位行处理）。 */
    public record GroupMemberItem(
            @Size(max = 64, message = "group_username 长度不能超过 64")
            String groupUsername,

            @Size(max = 64, message = "member_username 长度不能超过 64")
            String memberUsername,

            @Size(max = 128, message = "nick_name 长度不能超过 128")
            String nickName,

            @Size(max = 128, message = "remark 长度不能超过 128")
            String remark,

            Boolean isOwner) {
    }

    /** 标签定义元素 → wx_label_dict。 */
    public record LabelItem(
            @Size(max = 64, message = "label_name 长度不能超过 64")
            String labelName,

            Integer sortOrder,

            /** db | ui，缺省 db，越界 10102 */
            String source,

            Integer memberCount) {
    }

    /** 标签成员元素：能唯一映射 → wx_contact.tag_ids；映射不了 → wx_label_member_hint。 */
    public record LabelMemberItem(
            @Size(max = 64, message = "label_name 长度不能超过 64")
            String labelName,

            /** UI 显示名（重名时无法唯一映射）；缺省回退用 member_username */
            @Size(max = 128, message = "member_hint 长度不能超过 128")
            String memberHint,

            @Size(max = 64, message = "member_username 长度不能超过 64")
            String memberUsername,

            /** 客户端侧重名判定；服务端在映射失败时也置位 */
            Boolean ambiguous) {
    }

    /** 会话元素。 */
    public record ConversationItem(
            @Size(max = 64, message = "username 长度不能超过 64")
            String username,

            Boolean isGroup,

            @Size(max = 128, message = "display_name 长度不能超过 128")
            String displayName,

            OffsetDateTime lastMessageAt,

            Long messageCount,

            Integer unread) {
    }
}
