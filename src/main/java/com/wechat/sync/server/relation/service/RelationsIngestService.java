package com.wechat.sync.server.relation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wechat.sync.server.relation.dto.RelationsIngestVO;
import com.wechat.sync.server.relation.dto.RelationsReportRequest;
import com.wechat.sync.server.relation.entity.WxContactEntity;
import com.wechat.sync.server.relation.entity.WxConversationEntity;
import com.wechat.sync.server.relation.entity.WxGroupEntity;
import com.wechat.sync.server.relation.entity.WxGroupMemberRelEntity;
import com.wechat.sync.server.relation.entity.WxLabelDictEntity;
import com.wechat.sync.server.relation.entity.WxLabelMemberHintEntity;
import com.wechat.sync.server.relation.mapper.WxContactMapper;
import com.wechat.sync.server.relation.mapper.WxConversationMapper;
import com.wechat.sync.server.relation.mapper.WxGroupMapper;
import com.wechat.sync.server.relation.mapper.WxGroupMemberRelMapper;
import com.wechat.sync.server.relation.mapper.WxLabelDictMapper;
import com.wechat.sync.server.relation.mapper.WxLabelMemberHintMapper;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 关系层 ingest：POST /v1/reports/relations 落库（API_DESIGN §4.1 / SYNCH_PLAN §6.4-§6.5）。
 *
 * <p>单事务六步，全按「先查后插/改」upsert（部分唯一索引 {@code uk(...) WHERE is_deleted=0} 无法直接
 * {@code ON CONFLICT (account, username)}，需带同谓词，逐行量级可接受）。upsert 幂等 = 重复上报覆盖业务
 * 字段、保留 {@code id / created_at}；contacts 上报<strong>不碰 {@code tag_ids}</strong>（只由标签映射写，
 * 避免抹掉标签结果）。校验口径：account 缺失 → 10101，元素 username/label_name 缺失或枚举越界 → 10102
 * （bean validation 会被框架翻成 10100，故这里显式抛）；10101/10102 均带 {@code error[]}（field + value，
 * 见 {@link #paramError}）。
 *
 * <p><strong>部分更新语义（2026-11-17 修复）</strong>：update 路径的业务字段一律「缺席/为 null 不写 SET」——
 * C++ 客户端（wxbot-helper sync 模块）载荷只带 wxdb 有的字段子集（无 {@code is_in_chat_room /
 * contact_id / big_head_url} 等），Python 客户端则全量；无条件覆盖会把 NOT NULL 列写成 null（10106 炸约束）
 * 或抹掉既有行数据。语义 = 字段缺席即不动库值；需要清空时由数据源真值自然带出，v1 不支持显式置 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationsIngestService {

    /** wx_contact.source 可收值（API 小写 → 库大写，DB_DESIGN §8） */
    private static final Set<String> CONTACT_SOURCES = Set.of("CONTACT", "STRANGER");

    /** wx_label_dict.source 可收值 */
    private static final Set<String> LABEL_SOURCES = Set.of("DB", "UI");

    private static final String DEFAULT_CONTACT_SOURCE = "CONTACT";
    private static final String DEFAULT_LABEL_SOURCE = "DB";
    private static final int NORMAL = 0;

    private final WxContactMapper contactMapper;
    private final WxGroupMapper groupMapper;
    private final WxGroupMemberRelMapper groupMemberMapper;
    private final WxLabelDictMapper labelDictMapper;
    private final WxLabelMemberHintMapper labelHintMapper;
    private final WxConversationMapper conversationMapper;

    /** 单事务入口：绑定校验在 Controller 已做完（10300/10601），这里只做参数校验 + 落库。 */
    @Transactional
    public RelationsIngestVO ingest(RelationsReportRequest request) {
        String account = requireAccount(request.account());
        int contacts = upsertContacts(account, request.contacts());
        int groups = upsertGroups(account, request.groups());
        int groupMembers = upsertGroupMembers(account, request.groupMembers());
        int labels = upsertLabels(account, request.labels());
        int labelMembers = applyLabelMembers(account, request.labelMembers());
        int conversations = upsertConversations(account, request.conversations());
        log.info("[Relations] ingest account={} contacts={} groups={} groupMembers={} labels={} "
                        + "labelMembers={} conversations={}",
                account, contacts, groups, groupMembers, labels, labelMembers, conversations);
        return new RelationsIngestVO(account, new RelationsIngestVO.Upserted(
                contacts, groups, groupMembers, labels, labelMembers, conversations));
    }

    /**
     * 参数校验类抛错的统一出口：把 field / rejectedValue 装进 {@code errorDetail}（{@code List<ApiError>}），
     * GlobalExceptionHandler 翻成 {@code error[]}（API_DESIGN §2.1：field/code/message/value）。
     * 框架 1.10.0 只有三参构造器可同时带诊断 message 与 errorDetail（标了 @Deprecated，但无替代签名）。
     */
    private static ApiException paramError(ApiCode apiCode, String errorCode, String field,
            String message, Object rejectedValue) {
        return new ApiException(apiCode, message, List.of(ApiError.of(field, errorCode, message, rejectedValue)));
    }

    private int upsertContacts(String account, List<RelationsReportRequest.ContactItem> items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            RelationsReportRequest.ContactItem item = items.get(i);
            String username = requireUsername(item.username(), "contacts[" + i + "].username");
            String source = normalizeSource(item.source(), CONTACT_SOURCES,
                    DEFAULT_CONTACT_SOURCE, "contacts[" + i + "].source");
            Long contactId = parseRowId(item.contactId(), "contacts[" + i + "].contact_id");
            WxContactEntity existing = findContact(account, username);
            if (existing == null) {
                WxContactEntity entity = new WxContactEntity();
                entity.setAccount(account);
                entity.setUsername(username);
                entity.setSource(source);
                entity.setContactId(contactId);
                entity.setNickName(item.nickName());
                entity.setRemark(item.remark());
                entity.setAlias(item.alias());
                entity.setLocalType(item.localType());
                // is_in_chat_room 是 NOT NULL 列且 C++ 客户端（wxbot-helper）载荷不带该键——
                // 缺席时按 username 形态兜底推导，保证新建行不炸约束
                entity.setIsInChatRoom(item.isInChatRoom() != null
                        ? item.isInChatRoom() : username.endsWith("@chatroom"));
                entity.setDeleteFlag(item.deleteFlag());
                entity.setBigHeadUrl(item.bigHeadUrl());
                entity.setDescription(item.description());
                entity.setIsDeleted(NORMAL);
                contactMapper.insert(entity);
                count++;
            } else if (contactMapper.update(null, new LambdaUpdateWrapper<WxContactEntity>()
                    .eq(WxContactEntity::getId, existing.getId())
                    .set(WxContactEntity::getSource, source)
                    .set(contactId != null, WxContactEntity::getContactId, contactId)
                    .set(item.nickName() != null, WxContactEntity::getNickName, item.nickName())
                    .set(item.remark() != null, WxContactEntity::getRemark, item.remark())
                    .set(item.alias() != null, WxContactEntity::getAlias, item.alias())
                    .set(item.localType() != null, WxContactEntity::getLocalType, item.localType())
                    .set(item.isInChatRoom() != null, WxContactEntity::getIsInChatRoom, item.isInChatRoom())
                    .set(item.deleteFlag() != null, WxContactEntity::getDeleteFlag, item.deleteFlag())
                    .set(item.bigHeadUrl() != null, WxContactEntity::getBigHeadUrl, item.bigHeadUrl())
                    .set(item.description() != null, WxContactEntity::getDescription, item.description())
                    .set(WxContactEntity::getUpdatedAt, OffsetDateTime.now())) == 1) {
                count++;
            }   // 0 行命中 = 与管理端并发软删：不计 upserted，下轮上报自愈
        }
        return count;
    }

    private int upsertGroups(String account, List<RelationsReportRequest.GroupItem> items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            RelationsReportRequest.GroupItem item = items.get(i);
            String username = requireUsername(item.username(), "groups[" + i + "].username");
            Long roomId = parseRowId(item.roomId(), "groups[" + i + "].room_id");
            WxGroupEntity existing = groupMapper.selectOne(new LambdaQueryWrapper<WxGroupEntity>()
                    .eq(WxGroupEntity::getAccount, account)
                    .eq(WxGroupEntity::getUsername, username)
                    .eq(WxGroupEntity::getIsDeleted, NORMAL)
                    .last("LIMIT 1"));
            if (existing == null) {
                WxGroupEntity entity = new WxGroupEntity();
                entity.setAccount(account);
                entity.setUsername(username);
                entity.setRoomId(roomId);
                entity.setOwnerUsername(item.ownerUsername());
                entity.setDisplayName(item.displayName());
                entity.setMemberCount(item.memberCount());
                entity.setIsDeleted(NORMAL);
                groupMapper.insert(entity);
                count++;
            } else if (groupMapper.update(null, new LambdaUpdateWrapper<WxGroupEntity>()
                    .eq(WxGroupEntity::getId, existing.getId())
                    .set(roomId != null, WxGroupEntity::getRoomId, roomId)
                    .set(item.ownerUsername() != null, WxGroupEntity::getOwnerUsername, item.ownerUsername())
                    .set(item.displayName() != null, WxGroupEntity::getDisplayName, item.displayName())
                    .set(item.memberCount() != null, WxGroupEntity::getMemberCount, item.memberCount())
                    .set(WxGroupEntity::getUpdatedAt, OffsetDateTime.now())) == 1) {
                count++;
            }   // 0 行命中 = 并发软删，下轮上报自愈
        }
        return count;
    }

    private int upsertConversations(String account, List<RelationsReportRequest.ConversationItem> items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            RelationsReportRequest.ConversationItem item = items.get(i);
            String username = requireUsername(item.username(), "conversations[" + i + "].username");
            WxConversationEntity existing = conversationMapper.selectOne(
                    new LambdaQueryWrapper<WxConversationEntity>()
                            .eq(WxConversationEntity::getAccount, account)
                            .eq(WxConversationEntity::getUsername, username)
                            .eq(WxConversationEntity::getIsDeleted, NORMAL)
                            .last("LIMIT 1"));
            if (existing == null) {
                WxConversationEntity entity = new WxConversationEntity();
                entity.setAccount(account);
                entity.setUsername(username);
                entity.setIsGroup(Boolean.TRUE.equals(item.isGroup()));
                entity.setDisplayName(item.displayName());
                entity.setLastMessageAt(item.lastMessageAt());
                entity.setMessageCount(item.messageCount());
                entity.setUnread(item.unread());
                entity.setIsDeleted(NORMAL);
                conversationMapper.insert(entity);
                count++;
            } else if (conversationMapper.update(null, new LambdaUpdateWrapper<WxConversationEntity>()
                    .eq(WxConversationEntity::getId, existing.getId())
                    .set(WxConversationEntity::getIsGroup, Boolean.TRUE.equals(item.isGroup()))
                    .set(item.displayName() != null, WxConversationEntity::getDisplayName, item.displayName())
                    .set(item.lastMessageAt() != null, WxConversationEntity::getLastMessageAt, item.lastMessageAt())
                    .set(item.messageCount() != null, WxConversationEntity::getMessageCount, item.messageCount())
                    .set(item.unread() != null, WxConversationEntity::getUnread, item.unread())
                    .set(WxConversationEntity::getUpdatedAt, OffsetDateTime.now())) == 1) {
                count++;
            }   // 0 行命中 = 并发软删，下轮上报自愈
        }
        return count;
    }

    /** 群成员 upsert：uk 三元组唯一（无软删列），覆盖 nick_name/remark/is_owner。 */
    private int upsertGroupMembers(String account, List<RelationsReportRequest.GroupMemberItem> items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            RelationsReportRequest.GroupMemberItem item = items.get(i);
            String groupUsername = requireUsername(item.groupUsername(),
                    "group_members[" + i + "].group_username");
            String memberUsername = trimToNull(item.memberUsername());
            if (memberUsername == null) {
                // C10：未解析成员（member_username 解析不了）客户端本不该上报该元素；容忍为跳过而非报错
                log.debug("[Relations] group_members[{}] 缺 member_username，跳过（C10）", i);
                continue;
            }
            WxGroupMemberRelEntity existing = groupMemberMapper.selectOne(
                    new LambdaQueryWrapper<WxGroupMemberRelEntity>()
                            .eq(WxGroupMemberRelEntity::getAccount, account)
                            .eq(WxGroupMemberRelEntity::getGroupUsername, groupUsername)
                            .eq(WxGroupMemberRelEntity::getMemberUsername, memberUsername)
                            .last("LIMIT 1"));
            if (existing == null) {
                WxGroupMemberRelEntity entity = new WxGroupMemberRelEntity();
                entity.setAccount(account);
                entity.setGroupUsername(groupUsername);
                entity.setMemberUsername(memberUsername);
                entity.setNickName(item.nickName());
                entity.setRemark(item.remark());
                entity.setIsOwner(Boolean.TRUE.equals(item.isOwner()));
                groupMemberMapper.insert(entity);
            } else {
                groupMemberMapper.update(null, new LambdaUpdateWrapper<WxGroupMemberRelEntity>()
                        .eq(WxGroupMemberRelEntity::getId, existing.getId())
                        .set(item.nickName() != null, WxGroupMemberRelEntity::getNickName, item.nickName())
                        .set(item.remark() != null, WxGroupMemberRelEntity::getRemark, item.remark())
                        .set(WxGroupMemberRelEntity::getIsOwner, Boolean.TRUE.equals(item.isOwner())));
            }
            count++;
        }
        return count;
    }

    /** 标签字典 upsert：uk(account, tag_value)，SERIAL id 只由 DB 生成。 */
    private int upsertLabels(String account, List<RelationsReportRequest.LabelItem> items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            RelationsReportRequest.LabelItem item = items.get(i);
            String tagValue = requireUsername(item.labelName(), "labels[" + i + "].label_name");
            String source = normalizeSource(item.source(), LABEL_SOURCES,
                    DEFAULT_LABEL_SOURCE, "labels[" + i + "].source");
            WxLabelDictEntity existing = findLabel(account, tagValue);
            if (existing == null) {
                WxLabelDictEntity entity = new WxLabelDictEntity();
                entity.setAccount(account);
                entity.setTagValue(tagValue);
                entity.setSortOrder(item.sortOrder());
                entity.setSource(source);
                entity.setMemberCount(item.memberCount());
                // SERIAL 主键：IdType.AUTO → INSERT 不带 id，DB 生成后 getGeneratedKeys 回填实体
                labelDictMapper.insert(entity);
                if (entity.getId() == null) {
                    entity = findLabel(account, tagValue);  // 兜底回读（keygen 未生效时的保险）
                }
                log.debug("[Relations] label dict 新建 account={} tag={} id={}",
                        account, tagValue, entity == null ? null : entity.getId());
            } else {
                labelDictMapper.update(null, new LambdaUpdateWrapper<WxLabelDictEntity>()
                        .eq(WxLabelDictEntity::getId, existing.getId())
                        .set(item.sortOrder() != null, WxLabelDictEntity::getSortOrder, item.sortOrder())
                        .set(WxLabelDictEntity::getSource, source)
                        .set(item.memberCount() != null, WxLabelDictEntity::getMemberCount, item.memberCount())
                        .set(WxLabelDictEntity::getUpdatedAt, OffsetDateTime.now()));
            }
            count++;
        }
        return count;
    }

    /**
     * 标签成员映射：能唯一命中联系人的写 {@code wx_contact.tag_ids}（不进 hint 表，DB_DESIGN §7）；
     * 映射不了（查无此人 / 未给 member_username / 并发软删）→ upsert hint 行，{@code is_ambiguous} =
     * 客户端判定 || 本端未解析（置位纪律，SYNCH_PLAN §6.5）。
     */
    private int applyLabelMembers(String account, List<RelationsReportRequest.LabelMemberItem> items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            RelationsReportRequest.LabelMemberItem item = items.get(i);
            String tagValue = requireUsername(item.labelName(), "label_members[" + i + "].label_name");
            WxLabelDictEntity dict = findLabel(account, tagValue);
            if (dict == null) {
                throw paramError(ApiCode.PARAM_FORMAT_ERROR, "FORMAT_INVALID",
                        "label_members[" + i + "].label_name",
                        "label_members[" + i + "].label_name 未在 labels 上报且服务端无字典: " + tagValue,
                        tagValue);
            }
            String username = trimToNull(item.memberUsername());
            String hint = trimToNull(item.memberHint());
            boolean mapped = username != null
                    && contactMapper.appendTagId(account, username, dict.getId()) == 1;
            if (mapped) {
                count++;
                continue;
            }
            if (hint == null) {
                throw paramError(ApiCode.PARAM_FORMAT_ERROR, "FORMAT_INVALID",
                        "label_members[" + i + "].member_hint",
                        "label_members[" + i + "].member_hint 为空且 member_username 未命中联系人", null);
            }
            upsertHint(account, dict.getId(), hint, username,
                    Boolean.TRUE.equals(item.ambiguous()) || !mapped);
            count++;
        }
        return count;
    }

    private void upsertHint(String account, Integer dictId, String hint, String username, boolean ambiguous) {
        WxLabelMemberHintEntity existing = labelHintMapper.selectOne(
                new LambdaQueryWrapper<WxLabelMemberHintEntity>()
                        .eq(WxLabelMemberHintEntity::getAccount, account)
                        .eq(WxLabelMemberHintEntity::getDictId, dictId)
                        .eq(WxLabelMemberHintEntity::getMemberHint, hint)
                        .last("LIMIT 1"));
        if (existing == null) {
            WxLabelMemberHintEntity entity = new WxLabelMemberHintEntity();
            entity.setAccount(account);
            entity.setDictId(dictId);
            entity.setMemberHint(hint);
            entity.setMemberUsername(username);
            entity.setIsAmbiguous(ambiguous);
            labelHintMapper.insert(entity);
            return;
        }
        labelHintMapper.update(null, new LambdaUpdateWrapper<WxLabelMemberHintEntity>()
                .eq(WxLabelMemberHintEntity::getId, existing.getId())
                .set(username != null, WxLabelMemberHintEntity::getMemberUsername, username)
                .set(WxLabelMemberHintEntity::getIsAmbiguous, ambiguous));
    }

    private WxContactEntity findContact(String account, String username) {
        return contactMapper.selectOne(new LambdaQueryWrapper<WxContactEntity>()
                .eq(WxContactEntity::getAccount, account)
                .eq(WxContactEntity::getUsername, username)
                .eq(WxContactEntity::getIsDeleted, NORMAL)
                .last("LIMIT 1"));
    }

    private WxLabelDictEntity findLabel(String account, String tagValue) {
        return labelDictMapper.selectOne(new LambdaQueryWrapper<WxLabelDictEntity>()
                .eq(WxLabelDictEntity::getAccount, account)
                .eq(WxLabelDictEntity::getTagValue, tagValue)
                .last("LIMIT 1"));
    }

    /**
     * 10101：顶层 account 缺失（元素级缺失走 10102）。public：Controller 须在绑定校验（10300）前先调
     * 它——载荷缺 account 是参数缺失而非越权，次序不能反；ingest 内部幂等复检。
     */
    public String requireAccount(String account) {
        String trimmed = trimToNull(account);
        if (trimmed == null) {
            throw paramError(ApiCode.PARAM_MISSING, "PARAM_MISSING", "account", "account 必填", null);
        }
        return trimmed;
    }

    /** 10102：元素级 username / label_name 必填非空。 */
    private String requireUsername(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw paramError(ApiCode.PARAM_FORMAT_ERROR, "PARAM_MISSING", field,
                    field + " 必填且非空串", value);
        }
        return trimmed;
    }

    /** 枚举收小写、归一化大写落库；null/缺省取默认值，越界 10102。 */
    private String normalizeSource(String source, Set<String> allowed, String fallback, String field) {
        String trimmed = trimToNull(source);
        if (trimmed == null) {
            return fallback;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw paramError(ApiCode.PARAM_FORMAT_ERROR, "FORMAT_INVALID", field,
                    field + " 只允许 " + allowed.stream().sorted() + "（收小写）: " + source, source);
        }
        return upper;
    }

    /** 源侧 rowid（契约 String）→ BIGINT；非数字 10102。 */
    private Long parseRowId(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw paramError(ApiCode.PARAM_FORMAT_ERROR, "FORMAT_INVALID", field,
                    field + " 必须为整数: " + value, value);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
