package com.wechat.sync.server.query.dto;

import com.wechat.sync.server.message.entity.WxIngestBatchLogEntity;
import com.wechat.sync.server.message.entity.WxMessageAttachmentEntity;
import com.wechat.sync.server.query.mapper.WxMessageQueryMapper;
import com.wechat.sync.server.relation.entity.WxContactEntity;
import com.wechat.sync.server.relation.entity.WxConversationEntity;
import com.wechat.sync.server.relation.entity.WxGroupMemberRelEntity;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 查询接口响应 VO 集合（API_DESIGN §5）。字段驼峰，线上由 framework4j-web 统一转 snake_case，
 * Long → String（JS 精度安全，§2.2——计数类 Long 也会落 String，与 §2.2「计数为 Number」的出入
 * 是框架全局行为，T4 的 size/blobs_needed 已登记同款出入）。
 */
public final class QueryVOs {

    private QueryVOs() {
    }

    /**
     * §5.2 会话（wx_conversation 行，display_name 口径 remark or nick_name or username 由 ingest 维护）。
     *
     * <p>末三参是会话页摘要——该会话最新一条消息的发送者 / 类型码 / 正文前 200 字（服务层对当页
     * 会话回填；该会话查无消息时为 null）。仅作列表第二行展示，全文走消息流与详情接口。
     */
    public record ChatVO(Long id, String account, String username, Boolean isGroup, String displayName,
                         OffsetDateTime lastMessageAt, Long messageCount, Integer unread,
                         OffsetDateTime updatedAt,
                         String lastSenderUsername, String lastTypeCode, String lastBody) {
        public static ChatVO from(WxConversationEntity e) {
            return new ChatVO(e.getId(), e.getAccount(), e.getUsername(), e.getIsGroup(), e.getDisplayName(),
                    e.getLastMessageAt(), e.getMessageCount(), e.getUnread(), e.getUpdatedAt(),
                    null, null, null);
        }

        /** 回填会话页摘要；{@code last} 为 null（查无消息）时原样返回。 */
        public ChatVO withLast(WxMessageQueryMapper.ChatLastRow last) {
            if (last == null) {
                return this;
            }
            return new ChatVO(id, account, username, isGroup, displayName, lastMessageAt, messageCount, unread,
                    updatedAt, last.getSenderUsername(), last.getTypeCode(), last.getBody());
        }
    }

    /** §5.6 联系人（wx_contact 行，含 source=CONTACT|STRANGER 大写；tag_ids 是标签 dict id 数组）。 */
    public record ContactVO(Long id, String account, String username, String source, Long contactId,
                            String nickName, String remark, String alias, Integer localType,
                            Boolean isInChatRoom, int[] tagIds, String bigHeadUrl,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static ContactVO from(WxContactEntity e) {
            return new ContactVO(e.getId(), e.getAccount(), e.getUsername(), e.getSource(), e.getContactId(),
                    e.getNickName(), e.getRemark(), e.getAlias(), e.getLocalType(), e.getIsInChatRoom(),
                    e.getTagIds(), e.getBigHeadUrl(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    /**
     * §5.7 群成员。union=true 时只填 groupUsername / memberUsername / seenInAccounts（视图行）；
     * 否则填 wx_group_member_rel 行字段，seenInAccounts 为 null。
     */
    public record GroupMemberVO(Long id, String account, String groupUsername, String memberUsername,
                                String nickName, String remark, Boolean isOwner, OffsetDateTime createdAt,
                                Long seenInAccounts) {
        public static GroupMemberVO from(WxGroupMemberRelEntity e) {
            return new GroupMemberVO(e.getId(), e.getAccount(), e.getGroupUsername(), e.getMemberUsername(),
                    e.getNickName(), e.getRemark(), e.getIsOwner(), e.getCreatedAt(), null);
        }

        public static GroupMemberVO fromUnion(WxMessageQueryMapper.UnionMemberRow r) {
            return new GroupMemberVO(null, null, r.getGroupUsername(), r.getMemberUsername(),
                    null, null, null, null, r.getSeenInAccounts());
        }
    }

    /**
     * §5.8 / §5.4 附件元数据。{@code sha256='' } 与 {@code blobSha256=null} 同义：明文尚未取得；
     * blobSha256 非空即可拼 {@code /v1/blobs/{sha256}} 下载（D3 唯一出口）。
     */
    public record AttachmentVO(String id, String messageId, String kind, String tier, String filename,
                               String ext, Long size, String md5, String sha256, String blobSha256,
                               String status, OffsetDateTime syncedAt, OffsetDateTime createdAt) {
        public static AttachmentVO from(WxMessageAttachmentEntity e) {
            return new AttachmentVO(String.valueOf(e.getId()), String.valueOf(e.getMessageId()), e.getKind(),
                    e.getTier(), e.getFilename(), e.getExt(), e.getSize(), e.getMd5(), e.getSha256(),
                    e.getBlobSha256(), e.getStatus(), e.getSyncedAt(), e.getCreatedAt());
        }
    }

    /** §5.10 去重统计（v_wx_message_dedup 行；accounts 由视图 to_json 还原成列表）。 */
    public record DedupVO(String messageId, String chatUsername, String serverId, String dedupKey,
                          String senderUsername, OffsetDateTime createTime, String typeName,
                          Long sourceAccounts, List<String> accounts) {
    }

    /** §5.11 上报批次观测。不含 error 文本大字段（排障直接查 wx_ingest_batch_log）。 */
    public record IngestBatchVO(String id, String account, String batchRef, OffsetDateTime startedAt,
                                Integer durationMs, Integer nMessages, Integer nSources, Integer nInserted,
                                Integer nMerged, String status, OffsetDateTime createdAt) {
        public static IngestBatchVO from(WxIngestBatchLogEntity e) {
            return new IngestBatchVO(String.valueOf(e.getId()), e.getAccount(), e.getBatchRef(), e.getStartedAt(),
                    e.getDurationMs(), e.getNMessages(), e.getNSources(), e.getNInserted(), e.getNMerged(),
                    e.getStatus(), e.getCreatedAt());
        }
    }

    /**
     * §5.3 / §5.4 / §5.5 消息 VO。列表/检索项 {@code contentRaw / dedupKey / attachments} 恒 null
     * （大字段按需取，DB_DESIGN §12.2）；仅 §5.4 详情单条填充全文与附件元数据。
     * {@code sources} 是来源摘要 {@code [{account, shard, local_id}]}（§5.3）。
     */
    public record MessageVO(String id, String chatUsername, Boolean isGroup, String serverId,
                            String serverIdRaw, String senderUsername, String senderSource, String typeCode,
                            String typeName, OffsetDateTime createTime, Long createTimeRaw, String body,
                            String contentSha256, Boolean isRevoked, OffsetDateTime revokedAt,
                            Integer seenAccounts, OffsetDateTime createdAt, OffsetDateTime lastSeenAt,
                            String dedupKey, String contentRaw, List<SourceVO> sources,
                            List<AttachmentVO> attachments) {

        /** 来源摘要（wx_message_source_rel 行的最小投影，§5.3）。 */
        public record SourceVO(String account, String shard, Long localId) {
        }

        /** 列表/检索项：不带全文、dedup_key 与附件列表。 */
        public static MessageVO fromRow(WxMessageQueryMapper.MessageRow row, List<SourceVO> sources) {
            return new MessageVO(String.valueOf(row.getId()), row.getChatUsername(), row.getIsGroup(),
                    row.getServerId() == null ? null : String.valueOf(row.getServerId()), row.getServerIdRaw(),
                    row.getSenderUsername(), row.getSenderSource(), row.getTypeCode(), row.getTypeName(),
                    row.getCreateTime(), row.getCreateTimeRaw(), row.getBody(), row.getContentSha256(),
                    row.getIsRevoked(), row.getRevokedAt(), row.getSeenAccounts(), row.getCreatedAt(),
                    row.getLastSeenAt(), null, null, sources, null);
        }

        /** 详情项：全文 + dedup_key + sources 全量 + attachments 全量。 */
        public static MessageVO fromDetail(WxMessageQueryMapper.MessageRow row, List<SourceVO> sources,
                                           List<AttachmentVO> attachments) {
            return new MessageVO(String.valueOf(row.getId()), row.getChatUsername(), row.getIsGroup(),
                    row.getServerId() == null ? null : String.valueOf(row.getServerId()), row.getServerIdRaw(),
                    row.getSenderUsername(), row.getSenderSource(), row.getTypeCode(), row.getTypeName(),
                    row.getCreateTime(), row.getCreateTimeRaw(), row.getBody(), row.getContentSha256(),
                    row.getIsRevoked(), row.getRevokedAt(), row.getSeenAccounts(), row.getCreatedAt(),
                    row.getLastSeenAt(), row.getDedupKey(), row.getContentRaw(), sources, attachments);
        }
    }
}
