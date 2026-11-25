package com.wechat.sync.server.query.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.message.entity.WxIngestBatchLogEntity;
import com.wechat.sync.server.message.entity.WxMessageAttachmentEntity;
import com.wechat.sync.server.message.entity.WxMessageSourceRelEntity;
import com.wechat.sync.server.message.mapper.WxIngestBatchLogMapper;
import com.wechat.sync.server.message.mapper.WxMessageAttachmentMapper;
import com.wechat.sync.server.message.mapper.WxMessageSourceRelMapper;
import com.wechat.sync.server.query.dto.CursorPageVO;
import com.wechat.sync.server.query.dto.QueryVOs;
import com.wechat.sync.server.query.dto.SearchRequest;
import com.wechat.sync.server.query.mapper.WxMessageQueryMapper;
import com.wechat.sync.server.relation.entity.WxContactEntity;
import com.wechat.sync.server.relation.entity.WxConversationEntity;
import com.wechat.sync.server.relation.entity.WxGroupMemberRelEntity;
import com.wechat.sync.server.relation.mapper.WxContactMapper;
import com.wechat.sync.server.relation.mapper.WxConversationMapper;
import com.wechat.sync.server.relation.mapper.WxGroupMemberRelMapper;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询服务（API_DESIGN §5，角色 admin）：chats / messages(keyset) / search / message detail /
 * contacts / group members / attachment / statistics dedup / ingest-batches。
 *
 * <p>账号清单与账号详情（§5.1/§5.2）由 T2 的 {@code AccountAdminController} 提供
 * （同 URL / 同 admin 角色 / 同 {@code AccountService.list/detail}），此处不重复声明路由。
 *
 * <p>分页双模式（§2.4 矩阵）：messages + search 走 keyset Cursor（{@link CursorCodec}）；
 * 其余管理类小表走 Offset（复用 {@link PageVO#clamp}），且 <b>禁深 offset</b>——
 * 请求 page &gt; 1000 直接 10102（PG OFFSET 1e5 行以上的翻页是逐行丢弃的顺序扫描，
 * 管理类小表没有翻那么深的正当场景，宁显式拒绝也不给慢查询开口子）。
 */
@Service
@RequiredArgsConstructor
public class QueryService {

    /** IN 列表分块上限（批量查 sources 按 message_id 切块，避免 SQL 过长） */
    private static final int CHUNK = 500;
    /** Offset 模式允许的最大页码（超过即 10102，禁深 offset） */
    private static final long MAX_OFFSET_PAGE = 1000;
    /** 时间入参按秒解析的位数上限（≤10 位按 epoch 秒，>10 位按 epoch 毫秒） */
    private static final int EPOCH_SECONDS_DIGITS = 11;

    private final WxMessageQueryMapper messageQueryMapper;
    private final WxMessageSourceRelMapper sourceRelMapper;
    private final WxMessageAttachmentMapper attachmentMapper;
    private final WxConversationMapper conversationMapper;
    private final WxContactMapper contactMapper;
    private final WxGroupMemberRelMapper groupMemberRelMapper;
    private final WxIngestBatchLogMapper batchLogMapper;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------- §5.2 chats

    public PageVO<QueryVOs.ChatVO> chats(String account, Boolean isGroup, long page, long pageSize) {
        PageVO.PageParams params = offsetParams(page, pageSize);
        Page<WxConversationEntity> result = conversationMapper.selectPage(
                Page.of(params.page(), params.pageSize()),
                new LambdaQueryWrapper<WxConversationEntity>()
                        .eq(account != null, WxConversationEntity::getAccount, account)
                        .eq(isGroup != null, WxConversationEntity::getIsGroup, isGroup)
                        .eq(WxConversationEntity::getIsDeleted, 0)
                        .orderByDesc(WxConversationEntity::getLastMessageAt)
                        .orderByDesc(WxConversationEntity::getId));
        List<QueryVOs.ChatVO> list;
        long total;
        // 会话层兜底（live 2026-11-24）：C++ 客户端暂不产 wx_conversation 行，表空时
        // 从 wx_message 聚合会话维度（chat_username 去重 + 条数 + 最近时间），
        // 消息浏览页才有会话列表可点
        if (result.getTotal() == 0) {
            var rows = messageQueryMapper.aggChatsByMessage(account, isGroup, params.pageSize(),
                    (params.page() - 1) * params.pageSize());
            total = messageQueryMapper.countAggChatsByMessage(account, isGroup);
            list = rows.stream()
                    .map(r -> new QueryVOs.ChatVO(null, account, r.getChatUsername(), r.getIsGroup(),
                            null, r.getLastMessageAt(), r.getMessageCount(), null, r.getLastMessageAt(),
                            null, null, null))
                    .toList();
        } else {
            total = result.getTotal();
            list = result.getRecords().stream().map(QueryVOs.ChatVO::from).toList();
        }
        return PageVO.of(attachLast(list, account), total, params.page(), params.pageSize());
    }

    /** 当页会话各回填最新一条消息（会话页摘要；两条取数路径共用，空页不发查询）。 */
    private List<QueryVOs.ChatVO> attachLast(List<QueryVOs.ChatVO> list, String account) {
        if (list.isEmpty()) {
            return list;
        }
        Map<String, WxMessageQueryMapper.ChatLastRow> last = new HashMap<>();
        for (WxMessageQueryMapper.ChatLastRow r : messageQueryMapper
                .lastMessagesByChats(list.stream().map(QueryVOs.ChatVO::username).toList(), account)) {
            last.putIfAbsent(r.getChatUsername(), r);
        }
        return list.stream().map(c -> c.withLast(last.get(c.username()))).toList();
    }

    // ------------------------------------------------------- §5.3 / §5.5 messages

    /** 消息流：keyset 倒序 (create_time, id)；chat_username 必填且必须存在（首屏判，§7 → 10400）。 */
    public CursorPageVO<QueryVOs.MessageVO> messages(String chatUsername, String createTimeAfter,
                                                     String createTimeBefore, String senderUsername,
                                                     String typeName, String cursor, Integer limit) {
        String chat = requireChatUsername(chatUsername);
        CursorCodec.Key key = decodeCursor(cursor);
        // 存在性只判首屏（带 cursor 时该 chat 已在上一页出现过，不必再花一次 count）
        if (key == null && messageQueryMapper.countByChat(chat) == 0) {
            throw notFound("chat_username 不存在: " + chat);
        }
        int size = cursorLimit(limit);
        List<WxMessageQueryMapper.MessageRow> rows = messageQueryMapper.pageMessages(chat,
                trimToNull(senderUsername), trimToNull(typeName), null, null,
                parseTime(createTimeAfter, "create_time_after"),
                parseTime(createTimeBefore, "create_time_before"),
                key == null ? null : key.createTime(), key == null ? null : key.id(),
                size + 1);
        return toCursorPage(rows, size);
    }

    /**
     * 检索：q → {@code body ILIKE}。<b>规范禁左模糊，检索接口经评审豁免：内容检索业务必需</b>
     * （用户记不起原文前缀；通配符由服务层转义，%q% 是唯一豁免点）。chat_username 在这里是
     * 过滤语义（可空），不触发 §7 的 10400 存在性校验；其余条件全可空，keyset 兜底防深翻页。
     */
    public CursorPageVO<QueryVOs.MessageVO> search(SearchRequest request) {
        SearchRequest req = request == null ? emptySearch() : request;
        String q = req.normalize(req.q());
        CursorCodec.Key key = decodeCursor(req.cursor());
        int size = cursorLimit(req.limit());
        List<WxMessageQueryMapper.MessageRow> rows = messageQueryMapper.pageMessages(
                req.normalize(req.chatUsername()), req.normalize(req.senderUsername()),
                req.normalize(req.typeName()), req.normalize(req.account()),
                q == null ? null : "%" + escapeLike(q) + "%",
                parseTime(req.createTimeAfter(), "create_time_after"),
                parseTime(req.createTimeBefore(), "create_time_before"),
                key == null ? null : key.createTime(), key == null ? null : key.id(),
                size + 1);
        return toCursorPage(rows, size);
    }

    // ------------------------------------------------------------- §5.4 detail

    /** 单条详情：全文（content_raw）+ sources 全量 + attachments 全量；不存在 → 10400。 */
    public QueryVOs.MessageVO messageDetail(String messageId) {
        long id = parseId(messageId, "message_id");
        WxMessageQueryMapper.MessageRow row = messageQueryMapper.selectDetail(id);
        if (row == null) {
            throw notFound("消息不存在: " + messageId);
        }
        List<Long> ids = List.of(id);
        Map<Long, List<QueryVOs.MessageVO.SourceVO>> sources = loadSources(ids); // 单 id，同一条分块路径
        List<QueryVOs.AttachmentVO> attachments = attachmentMapper.selectList(
                        new LambdaQueryWrapper<WxMessageAttachmentEntity>()
                                .eq(WxMessageAttachmentEntity::getMessageId, id)
                                .orderByAsc(WxMessageAttachmentEntity::getKind)
                                .orderByAsc(WxMessageAttachmentEntity::getTier))
                .stream().map(QueryVOs.AttachmentVO::from).toList();
        return QueryVOs.MessageVO.fromDetail(row, sources.getOrDefault(id, List.of()), attachments);
    }

    // ------------------------------------------------------------- §5.6 contacts

    public PageVO<QueryVOs.ContactVO> contacts(String account, String username, long page, long pageSize) {
        PageVO.PageParams params = offsetParams(page, pageSize);
        Page<WxContactEntity> result = contactMapper.selectPage(
                Page.of(params.page(), params.pageSize()),
                new LambdaQueryWrapper<WxContactEntity>()
                        .eq(account != null, WxContactEntity::getAccount, account)
                        .eq(username != null, WxContactEntity::getUsername, username)
                        .eq(WxContactEntity::getIsDeleted, 0)
                        .orderByDesc(WxContactEntity::getId));
        return PageVO.map(result.getRecords(), result.getTotal(), params.page(), params.pageSize(),
                QueryVOs.ContactVO::from);
    }

    // ------------------------------------------------------- §5.7 group members

    /** union=true 走视图 v_wx_group_members_union（跨账号并集 + seen_in_accounts）；否则查本账号成员行。 */
    public PageVO<QueryVOs.GroupMemberVO> groupMembers(String groupUsername, String account, boolean union,
                                                       long page, long pageSize) {
        String group = requireParam(groupUsername, "group_username");
        PageVO.PageParams params = offsetParams(page, pageSize);
        if (union) {
            Page<WxMessageQueryMapper.UnionMemberRow> p = Page.of(params.page(), params.pageSize());
            List<WxMessageQueryMapper.UnionMemberRow> rows =
                    messageQueryMapper.pageUnionMembers(p, group);
            return PageVO.map(rows, p.getTotal(), params.page(), params.pageSize(),
                    QueryVOs.GroupMemberVO::fromUnion);
        }
        Page<WxGroupMemberRelEntity> result = groupMemberRelMapper.selectPage(
                Page.of(params.page(), params.pageSize()),
                new LambdaQueryWrapper<WxGroupMemberRelEntity>()
                        .eq(WxGroupMemberRelEntity::getGroupUsername, group)
                        .eq(account != null, WxGroupMemberRelEntity::getAccount, account)
                        .orderByAsc(WxGroupMemberRelEntity::getMemberUsername));
        return PageVO.map(result.getRecords(), result.getTotal(), params.page(), params.pageSize(),
                QueryVOs.GroupMemberVO::from);
    }

    // ---------------------------------------------------------- §5.8 attachment

    public QueryVOs.AttachmentVO attachment(String attachmentId) {
        long id = parseId(attachmentId, "attachment_id");
        var entity = attachmentMapper.selectById(id);
        if (entity == null) {
            throw notFound("附件不存在: " + attachmentId);
        }
        return QueryVOs.AttachmentVO.from(entity);
    }

    // ------------------------------------------------------- §5.10 statistics

    /** 去重视图分页：source_accounts 由视图聚合，accounts 经 to_json 还原成列表。 */
    public PageVO<QueryVOs.DedupVO> dedupStatistics(String chatUsername, long page, long pageSize) {
        PageVO.PageParams params = offsetParams(page, pageSize);
        Page<WxMessageQueryMapper.DedupRow> p = Page.of(params.page(), params.pageSize());
        List<WxMessageQueryMapper.DedupRow> rows = messageQueryMapper.pageDedup(p, trimToNull(chatUsername));
        List<QueryVOs.DedupVO> list = rows.stream().map(r -> new QueryVOs.DedupVO(String.valueOf(r.getId()),
                r.getChatUsername(), r.getServerId() == null ? null : String.valueOf(r.getServerId()),
                r.getDedupKey(), r.getSenderUsername(), r.getCreateTime(), r.getTypeName(),
                r.getSourceAccounts(), parseAccounts(r.getAccountsJson()))).toList();
        return PageVO.of(list, p.getTotal(), params.page(), params.pageSize());
    }

    // ---------------------------------------------------- §5.11 ingest-batches

    /** 最近上报批次（倒序）。显式列清单并排除 error 文本大字段（DB_DESIGN §12.2）。 */
    public PageVO<QueryVOs.IngestBatchVO> ingestBatches(String account, long page, long pageSize) {
        PageVO.PageParams params = offsetParams(page, pageSize);
        Page<WxIngestBatchLogEntity> result = batchLogMapper.selectPage(
                Page.of(params.page(), params.pageSize()),
                new LambdaQueryWrapper<WxIngestBatchLogEntity>()
                        .eq(account != null, WxIngestBatchLogEntity::getAccount, account)
                        .select(WxIngestBatchLogEntity::getId, WxIngestBatchLogEntity::getAccount,
                                WxIngestBatchLogEntity::getBatchRef, WxIngestBatchLogEntity::getStartedAt,
                                WxIngestBatchLogEntity::getDurationMs, WxIngestBatchLogEntity::getNMessages,
                                WxIngestBatchLogEntity::getNSources, WxIngestBatchLogEntity::getNInserted,
                                WxIngestBatchLogEntity::getNMerged, WxIngestBatchLogEntity::getStatus,
                                WxIngestBatchLogEntity::getCreatedAt)
                        .orderByDesc(WxIngestBatchLogEntity::getStartedAt)
                        .orderByDesc(WxIngestBatchLogEntity::getId));
        return PageVO.map(result.getRecords(), result.getTotal(), params.page(), params.pageSize(),
                QueryVOs.IngestBatchVO::from);
    }

    // ============================================================== 私有工具

    /** 裁剪 + 组装 sources 摘要 + 推导 next_cursor。 */
    private CursorPageVO<QueryVOs.MessageVO> toCursorPage(List<WxMessageQueryMapper.MessageRow> fetched,
                                                          int limit) {
        boolean hasMore = fetched.size() > limit;
        List<WxMessageQueryMapper.MessageRow> page = hasMore ? fetched.subList(0, limit) : fetched;
        Map<Long, List<QueryVOs.MessageVO.SourceVO>> sources =
                loadSources(page.stream().map(WxMessageQueryMapper.MessageRow::getId).toList());
        List<QueryVOs.MessageVO> list = page.stream()
                .map(row -> QueryVOs.MessageVO.fromRow(row, sources.getOrDefault(row.getId(), List.of())))
                .toList();
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            WxMessageQueryMapper.MessageRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(epochMillis(last.getCreateTime()), last.getId());
        }
        return new CursorPageVO<>(list, nextCursor, hasMore);
    }

    /** 按 message_id 批量查来源行（IN 分块 ≤500）聚合成 sources 摘要。 */
    private Map<Long, List<QueryVOs.MessageVO.SourceVO>> loadSources(List<Long> messageIds) {
        Map<Long, List<QueryVOs.MessageVO.SourceVO>> out = new HashMap<>();
        for (int i = 0; i < messageIds.size(); i += CHUNK) {
            List<Long> chunk = messageIds.subList(i, Math.min(i + CHUNK, messageIds.size()));
            for (WxMessageSourceRelEntity e : sourceRelMapper.selectList(
                    new LambdaQueryWrapper<WxMessageSourceRelEntity>()
                            .in(WxMessageSourceRelEntity::getMessageId, chunk)
                            .orderByAsc(WxMessageSourceRelEntity::getAccount)
                            .orderByAsc(WxMessageSourceRelEntity::getShard)
                            .orderByAsc(WxMessageSourceRelEntity::getLocalId))) {
                out.computeIfAbsent(e.getMessageId(), k -> new ArrayList<>())
                        .add(new QueryVOs.MessageVO.SourceVO(e.getAccount(), e.getShard(), e.getLocalId()));
            }
        }
        return out;
    }

    /** Offset 模式统一入口：先禁深 offset（>1000 → 10102），再走既有钳制（page≥1 / size≤200）。 */
    private PageVO.PageParams offsetParams(long page, long pageSize) {
        if (page > MAX_OFFSET_PAGE) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR,
                    "page 超过上限 " + MAX_OFFSET_PAGE + "（禁深 offset，请缩小筛选范围）",
                    List.of(ApiError.of("page", "FORMAT_INVALID",
                            "page 必须在 1~" + MAX_OFFSET_PAGE, page)));
        }
        return PageVO.clamp(page, pageSize);
    }

    /** Cursor 模式页大小钳制：缺省 20，夹在 [1, 200]（与 PageVO.MAX_PAGE_SIZE 同口径）。 */
    private int cursorLimit(Integer limit) {
        long raw = limit == null ? PageVO.DEFAULT_PAGE_SIZE : limit;
        return (int) Math.min(Math.max(raw, 1), PageVO.MAX_PAGE_SIZE);
    }

    private CursorCodec.Key decodeCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? null : CursorCodec.decode(cursor);
    }

    private String requireChatUsername(String chatUsername) {
        return requireParam(chatUsername, "chat_username");
    }

    private String requireParam(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiException(ApiCode.PARAM_MISSING, field + " 必填",
                    List.of(ApiError.of(field, "PARAM_MISSING", field + " 必填", null)));
        }
        return trimmed;
    }

    /** 时间入参解析：ISO-8601 / Instant / epoch 秒 / epoch 毫秒；非法 → 10102（field 指到参数）。 */
    private OffsetDateTime parseTime(String raw, String field) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            if (value.chars().allMatch(Character::isDigit)) {
                Instant instant = value.length() < EPOCH_SECONDS_DIGITS
                        ? Instant.ofEpochSecond(Long.parseLong(value))
                        : Instant.ofEpochMilli(Long.parseLong(value));
                return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
            }
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, field + " 时间格式非法: " + value,
                    List.of(ApiError.of(field, "FORMAT_INVALID",
                            "支持 ISO-8601 / epoch 秒 / epoch 毫秒", value)));
        }
    }

    /** 路径变量 id 解析：非数字 → 10102（防把脏输入直接打到 bigint 绑定上）。 */
    private long parseId(String raw, String field) {
        String value = trimToNull(raw);
        if (value != null && value.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                // 超过 bigint 范围，走下面的 10102
            }
        }
        throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, field + " 必须是数字 id: " + raw,
                List.of(ApiError.of(field, "FORMAT_INVALID", "必须是十进制数字 id", raw)));
    }

    /** ILIKE 通配符转义（PG 缺省转义符是反斜杠），用户输入不当作模式解释。 */
    private String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** v_wx_message_dedup.accounts 的 JSON 形态 → 列表（to_json 输出恒为合法 JSON 数组）。 */
    private List<String> parseAccounts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return List.of();
        }
    }

    private SearchRequest emptySearch() {
        return new SearchRequest(null, null, null, null, null, null, null, null, null);
    }

    private long epochMillis(OffsetDateTime time) {
        return time == null ? 0L : time.toInstant().toEpochMilli();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ApiException notFound(String message) {
        return new ApiException(ApiCode.NOT_FOUND, message);
    }
}
