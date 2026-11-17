package com.wechat.sync.server.relation.dto;

/**
 * POST /v1/reports/relations 响应 data（API_DESIGN §4.1）。
 *
 * <p>字段驼峰命名，线上由 framework4j-web 转 snake_case（account / upserted.contacts / …）。
 * upserted 计数 = 实际写入（insert 或 update 命中）的行数，缺省数组计 0。
 *
 * @param account  经绑定校验的账号
 * @param upserted 六类 upsert 计数
 */
public record RelationsIngestVO(String account, Upserted upserted) {

    /**
     * @param contacts      wx_contact upsert 数
     * @param groups        wx_group upsert 数
     * @param groupMembers  wx_group_member_rel upsert 数
     * @param labels        wx_label_dict upsert 数
     * @param labelMembers  label_members 元素处理数（落 tag_ids 或 hint 表，含两者）
     * @param conversations wx_conversation upsert 数
     */
    public record Upserted(int contacts,
                           int groups,
                           int groupMembers,
                           int labels,
                           int labelMembers,
                           int conversations) {
    }
}
