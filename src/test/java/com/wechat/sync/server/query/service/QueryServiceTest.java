package com.wechat.sync.server.query.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.wechat.sync.server.query.mapper.WxMessageQueryMapper.MessageRow;
import com.wechat.sync.server.relation.entity.WxContactEntity;
import com.wechat.sync.server.relation.entity.WxConversationEntity;
import com.wechat.sync.server.relation.entity.WxGroupMemberRelEntity;
import com.wechat.sync.server.relation.mapper.WxContactMapper;
import com.wechat.sync.server.relation.mapper.WxConversationMapper;
import com.wechat.sync.server.relation.mapper.WxGroupMemberRelMapper;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * QueryService 纯单测（不起 Spring/MyBatis 上下文）：keyset 首页/翻页/位点校验、has_more 两分支、
 * next_cursor 自洽、sources IN 分块、ILIKE 转义、Offset 钳制与禁深 offset、detail 全文、union 视图
 * 路由、statistics / ingest-batches 转发。CursorCodec 本体在 {@link CursorCodecTest}。
 * 断言 wrapper 绑定值前先 getSqlSegment() 渲染（MP 懒求值）；paramNameValuePairs 是 HashMap，断言用无序形态。
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    private static final String CHAT = "t6unit_room@chatroom";
    /** 1760001000 epoch 秒 / 1760001000000 epoch 毫秒 = 同一绝对时刻 */
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2025-10-09T09:10:00Z");
    private static final long T0_MILLIS = 1760001000000L;

    @Mock private WxMessageQueryMapper messageQueryMapper;
    @Mock private WxMessageSourceRelMapper sourceRelMapper;
    @Mock private WxMessageAttachmentMapper attachmentMapper;
    @Mock private WxConversationMapper conversationMapper;
    @Mock private WxContactMapper contactMapper;
    @Mock private WxGroupMemberRelMapper groupMemberRelMapper;
    @Mock private WxIngestBatchLogMapper batchLogMapper;

    private QueryService service;

    @BeforeEach
    void setUp() {
        MpTestTables.init(WxMessageSourceRelEntity.class, WxMessageAttachmentEntity.class,
                WxConversationEntity.class, WxContactEntity.class, WxGroupMemberRelEntity.class,
                WxIngestBatchLogEntity.class);
        service = new QueryService(messageQueryMapper, sourceRelMapper, attachmentMapper,
                conversationMapper, contactMapper, groupMemberRelMapper, batchLogMapper, new ObjectMapper());
    }

    // ---------- §5.3 / §5.5 keyset（messages 与 search 共用同一位点与分页逻辑） ----------

    /** 位点校验先于任何 DB 访问：非 Base64Url / 缺冒号 / 非数字 / 越界一律 10102 field=cursor。 */
    @Test
    void malformedCursorIsRejectedBeforeAnyDbCall() {
        List<String> bad = List.of("!!不是Base64!!", "aGVsbG8" /* 无冒号 */, "MTIz" /* 只有 millis */,
                cursorOf("abc:1") /* millis 非数字 */, cursorOf("1760001000000:0") /* id 越界 */,
                cursorOf("-1:9") /* 负毫秒 */);
        for (String cursor : bad) {
            bizError(() -> service.messages(CHAT, null, null, null, null, cursor, null), 10102, "cursor");
            bizError(() -> service.search(searchWithCursor(cursor)), 10102, "cursor");
        }
        verifyNoInteractions(messageQueryMapper);
    }

    /** 首页：无 cursor 不带位点；chat 存在性前置（缺 → 10400）；列表项不取 content_raw 等大字段。 */
    @Test
    void firstPageOmitsKeysetPredicateChecksChatAndSkipsLargeColumns() {
        given(messageQueryMapper.countByChat(CHAT)).willReturn(5L);
        pageReturns(List.of(row(11, T0, "hi")));

        CursorPageVO<QueryVOs.MessageVO> page = service.messages(CHAT, null, null, null, null, null, null);

        verify(messageQueryMapper).pageMessages(eq(CHAT), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(21));
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.list().get(0).id()).isEqualTo("11");
        assertThat(page.list().get(0).body()).isEqualTo("hi");
        assertThat(page.list().get(0).contentRaw()).as("列表不取全文").isNull();
        assertThat(page.list().get(0).attachments()).isNull();
        assertThat(page.list().get(0).sources()).isEmpty();

        given(messageQueryMapper.countByChat(CHAT)).willReturn(0L);
        bizError(() -> service.messages(CHAT, null, null, null, null, null, null), 10400, null);
        verify(messageQueryMapper, times(1)).pageMessages(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt());
    }

    /** 翻页：cursor 解码成 (cursor_time, cursor_id) 两个标量 @Param 透传、不再花 count、过滤槽位不串位。 */
    @Test
    void nextPageDecodesCursorIntoScalarsSkipsCountAndKeepsFilterSlots() {
        pageReturns(List.of());

        service.messages(CHAT, "1760001000", "1760002000", "wxid_s", "文本",
                CursorCodec.encode(T0_MILLIS, 42L), null);

        verify(messageQueryMapper, never()).countByChat(anyString());
        verify(messageQueryMapper).pageMessages(eq(CHAT), eq("wxid_s"), eq("文本"), isNull(), isNull(),
                eq(T0), eq(OffsetDateTime.parse("2025-10-09T09:26:40Z")), eq(T0), eq(42L), eq(21));

        service.search(new SearchRequest(null, null, null, null, null, null, null,
                CursorCodec.encode(T0_MILLIS, 7L), null));
        verify(messageQueryMapper).pageMessages(isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(T0), eq(7L), eq(21));
    }

    /** cursor 页大小钳制：null→20、0→1、999→200（DB 侧取 limit+1 防漏页）。 */
    @Test
    void cursorLimitIsClampedBeforeHittingTheMapper() {
        given(messageQueryMapper.countByChat(CHAT)).willReturn(1L);
        pageReturns(List.of());

        service.messages(CHAT, null, null, null, null, null, null);
        service.messages(CHAT, null, null, null, null, null, 0);
        service.messages(CHAT, null, null, null, null, null, 999);

        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(messageQueryMapper, times(3)).pageMessages(eq(CHAT), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), limits.capture());
        assertThat(limits.getAllValues()).as("DB 取 limit+1").containsExactly(21, 2, 201);
    }

    /** has_more 两分支：取满 limit+1 → 裁剪 + next_cursor=末行 (create_time,id)；恰好 limit → 无 cursor。 */
    @Test
    void hasMoreTrimsOverflowRowAndEncodesNextCursorFromLastKeptRow() {
        given(messageQueryMapper.countByChat(CHAT)).willReturn(9L);
        pageReturns(List.of(row(13, T0.plusSeconds(2), "c"), row(12, T0.plusSeconds(1), "b"), row(11, T0, "a")));

        CursorPageVO<QueryVOs.MessageVO> full = service.messages(CHAT, null, null, null, null, null, 2);

        assertThat(full.hasMore()).isTrue();
        assertThat(full.list()).extracting(QueryVOs.MessageVO::id).containsExactly("13", "12");
        CursorCodec.Key key = CursorCodec.decode(full.nextCursor());
        assertThat(key.id()).as("位点取裁剪后最后一条").isEqualTo(12L);
        assertThat(key.createTimeMillis()).isEqualTo(T0.plusSeconds(1).toInstant().toEpochMilli());

        pageReturns(List.of(row(13, T0.plusSeconds(2), "c")));
        CursorPageVO<QueryVOs.MessageVO> last = service.messages(CHAT, null, null, null, null, null, 2);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(last.list()).hasSize(1);
    }

    /** sources 摘要只为当前页 id 加载（IN 不含被裁剪的超载行），一行多来源聚合成摘要。 */
    @Test
    void sourcesAreLoadedForKeptRowsOnlyAndAggregatedPerMessage() {
        given(messageQueryMapper.countByChat(CHAT)).willReturn(9L);
        pageReturns(List.of(row(13, T0.plusSeconds(2), "c"), row(12, T0.plusSeconds(1), "b"), row(11, T0, "a")));
        given(sourceRelMapper.selectList(any())).willReturn(List.of(
                rel(13, "wxid_a", "db/MSG0.db", 1L), rel(13, "wxid_b", "db/MSG0.db", 3L)));

        CursorPageVO<QueryVOs.MessageVO> page = service.messages(CHAT, null, null, null, null, null, 2);

        ArgumentCaptor<LambdaQueryWrapper<WxMessageSourceRelEntity>> captor = wrapperCaptor();
        verify(sourceRelMapper).selectList(captor.capture());
        assertThat(boundValues(captor.getValue())).as("IN 只装当前页 id").containsExactlyInAnyOrder(13L, 12L);
        assertThat(page.list().get(0).sources()).containsExactly(
                new QueryVOs.MessageVO.SourceVO("wxid_a", "db/MSG0.db", 1L),
                new QueryVOs.MessageVO.SourceVO("wxid_b", "db/MSG0.db", 3L));
        assertThat(page.list().get(1).sources()).isEmpty();
    }

    /** IN 分块：1001 个 message_id 切成 500+500+1 三次 IN（块内 ≤500 不变量）、并集无重无漏（公开入口被 limit≤200 挡住，直测私有聚合）。 */
    @Test
    @SuppressWarnings("unchecked")
    void sourceLoadingChunksInPredicatesAtFiveHundredIds() {
        List<Long> ids = LongStream.rangeClosed(1, 1001).boxed().collect(Collectors.toList());
        given(sourceRelMapper.selectList(any())).willReturn(List.of());

        Map<Long, List<QueryVOs.MessageVO.SourceVO>> out = (Map<Long, List<QueryVOs.MessageVO.SourceVO>>)
                ReflectionTestUtils.invokeMethod(service, "loadSources", ids);

        assertThat(out).isEmpty();
        ArgumentCaptor<LambdaQueryWrapper<WxMessageSourceRelEntity>> captor = wrapperCaptor();
        verify(sourceRelMapper, times(3)).selectList(captor.capture());
        List<LambdaQueryWrapper<WxMessageSourceRelEntity>> chunks = captor.getAllValues();
        assertThat(boundValues(chunks.get(0))).hasSize(500);
        assertThat(boundValues(chunks.get(1))).hasSize(500);
        assertThat(boundValues(chunks.get(2))).as("尾块只装余量").hasSize(1);
        HashSet<Object> all = new HashSet<>();
        chunks.forEach(w -> all.addAll(boundValues(w)));
        assertThat(all).as("三块并集 = 全部 id").hasSize(1001);
    }

    // ---------- §5.5 search（q 转义 / 字段裁剪 / 时间解析） ----------

    /** ILIKE 转义：% _ \ 前加 \（字面匹配，%q% 是唯一豁免点）；q 空白 = 不检索；字段全裁剪。 */
    @Test
    void searchEscapesLikeWildcardsTrimsFieldsAndTreatsBlankQAsNoFilter() {
        pageReturns(List.of());

        service.search(new SearchRequest("a%b_c\\d", " wxid_a ", " chat ", " sender ", " type ",
                null, null, null, null));
        verify(messageQueryMapper).pageMessages(eq("chat"), eq("sender"), eq("type"), eq("wxid_a"),
                eq("%a\\%b\\_c\\\\d%"), isNull(), isNull(), isNull(), isNull(), eq(21));

        service.search(new SearchRequest("   ", null, null, null, null, null, null, null, null));
        service.search(null);
        verify(messageQueryMapper, times(2)).pageMessages(isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(21));
        verify(messageQueryMapper, never()).countByChat(anyString());
    }

    /** 时间入参：epoch 秒（≤10 位）/ epoch 毫秒（>10 位）/ ISO-8601 落同一时刻；非法 → 10102 指到字段。 */
    @Test
    void timeFiltersAcceptEpochSecondsMillisIsoAndRejectGarbage() {
        pageReturns(List.of());

        service.search(new SearchRequest(null, null, null, null, null, "1760001000", null, null, null));
        service.search(new SearchRequest(null, null, null, null, null, "2025-10-09T09:10:00Z", null, null, null));
        service.search(new SearchRequest(null, null, null, null, null, null, "1760001000000", null, null));

        verify(messageQueryMapper, times(2)).pageMessages(isNull(), isNull(), isNull(), isNull(),
                isNull(), eq(T0), isNull(), isNull(), isNull(), eq(21));
        verify(messageQueryMapper).pageMessages(isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), eq(T0), isNull(), isNull(), eq(21));
        bizError(() -> service.search(new SearchRequest(null, null, null, null, null, "not-a-time",
                null, null, null)), 10102, "create_time_after");
    }

    // ---------- §5.2 / §5.6 Offset 分页（page>1000 禁深 offset） ----------

    /** chats / contacts：PageVO.clamp 生效（page=0→1、size≤200、缺省 20），过滤条件与强制 is_deleted=0 下发。 */
    @Test
    @SuppressWarnings("unchecked")
    void offsetEndpointsClampPageAndSizeAndForwardFilters() {
        given(conversationMapper.selectPage(any(), any())).willReturn(new Page<>(1, 20));
        given(contactMapper.selectPage(any(), any())).willReturn(new Page<>(1, 20));

        PageVO<QueryVOs.ChatVO> chats = service.chats(null, null, 0, 0);
        assertThat(chats.page()).isEqualTo(1);
        assertThat(chats.pageSize()).isEqualTo(20);
        PageVO<QueryVOs.ChatVO> capped = service.chats("wxid_a", Boolean.TRUE, 3, 500);
        assertThat(capped.page()).isEqualTo(3);
        assertThat(capped.pageSize()).isEqualTo(200);

        ArgumentCaptor<Page<WxConversationEntity>> chatPages = pageCaptor();
        ArgumentCaptor<LambdaQueryWrapper<WxConversationEntity>> chatWrappers =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(conversationMapper, times(2)).selectPage(chatPages.capture(), chatWrappers.capture());
        assertThat(chatPages.getAllValues()).extracting(Page::getCurrent).containsExactly(1L, 3L);
        assertThat(chatPages.getAllValues()).extracting(Page::getSize).containsExactly(20L, 200L);
        chatWrappers.getAllValues().forEach(w -> {
            assertThat(w.getSqlSegment()).contains("is_deleted");
            assertThat(boundValues(w)).contains(0);
        });
        assertThat(boundValues(chatWrappers.getAllValues().get(1))).contains("wxid_a", Boolean.TRUE);

        PageVO<QueryVOs.ContactVO> contacts = service.contacts("wxid_a", "wxid_b", 0, 0);
        assertThat(contacts.page()).isEqualTo(1);
        assertThat(contacts.pageSize()).isEqualTo(20);
        ArgumentCaptor<Page<WxContactEntity>> contactPages = pageCaptor();
        ArgumentCaptor<LambdaQueryWrapper<WxContactEntity>> contactWrappers =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(contactMapper).selectPage(contactPages.capture(), contactWrappers.capture());
        assertThat(contactPages.getValue().getCurrent()).isEqualTo(1);
        assertThat(boundValues(contactWrappers.getValue())).contains("wxid_a", "wxid_b", 0);
    }

    /** 禁深 offset：page>1000 → 10102 field=page（PG 大 OFFSET 是逐行丢弃的顺序扫描，宁拒不给慢查询开口子）。 */
    @Test
    void offsetEndpointsRejectPageBeyondCeilingWithoutTouchingDb() {
        bizError(() -> service.chats(null, null, 1001, 20), 10102, "page");
        bizError(() -> service.contacts(null, null, 2000, 20), 10102, "page");
        verifyNoInteractions(conversationMapper, contactMapper);
    }

    // ---------- §5.4 / §5.8 detail 与 attachment ----------

    /** 详情是唯一取全文的入口：content_raw / dedup_key / sources 全量 / attachments 元数据（id 下发 String）。 */
    @Test
    @SuppressWarnings("unchecked")
    void detailReturnsFullTextWithAllSourcesAndAttachments() {
        MessageRow row = row(11, T0, "hello");
        row.setContentRaw("<xml>hello</xml>");
        row.setDedupKey("srv:9001");
        row.setServerId(9001L);
        given(messageQueryMapper.selectDetail(11L)).willReturn(row);
        given(sourceRelMapper.selectList(any())).willReturn(List.of(
                rel(11, "wxid_a", "db/MSG0.db", 1L), rel(11, "wxid_b", "db/MSG0.db", 2L)));
        given(attachmentMapper.selectList(any())).willReturn(List.of(attachment(3L, 11L, "image")));

        QueryVOs.MessageVO vo = service.messageDetail("11");

        assertThat(vo.id()).isEqualTo("11");
        assertThat(vo.contentRaw()).isEqualTo("<xml>hello</xml>");
        assertThat(vo.dedupKey()).isEqualTo("srv:9001");
        assertThat(vo.sources()).hasSize(2);
        assertThat(vo.attachments()).hasSize(1);
        assertThat(vo.attachments().get(0).id()).isEqualTo("3");
        assertThat(vo.attachments().get(0).blobSha256()).isEqualTo("sha256-3");

        ArgumentCaptor<LambdaQueryWrapper<WxMessageSourceRelEntity>> rel = wrapperCaptor();
        verify(sourceRelMapper).selectList(rel.capture());
        assertThat(boundValues(rel.getValue())).containsExactly(11L);
        ArgumentCaptor<LambdaQueryWrapper<WxMessageAttachmentEntity>> att = wrapperCaptor();
        verify(attachmentMapper).selectList(att.capture());
        assertThat(att.getValue().getSqlSegment()).contains("message_id", "kind", "tier");
        assertThat(boundValues(att.getValue())).containsExactly(11L);
    }

    /** 防脏输入：非数字 id → 10102（不打 bigint 绑定）；查无此行 → 10400 且不再查附件。 */
    @Test
    void detailRejectsNonNumericIdAndMissingRow() {
        bizError(() -> service.messageDetail("abc"), 10102, "message_id");
        given(messageQueryMapper.selectDetail(12L)).willReturn(null);
        bizError(() -> service.messageDetail("12"), 10400, null);
        verify(attachmentMapper, never()).selectList(any());
    }

    /** 附件元数据单查：id / messageId 下发 String；非数字 → 10102；查无 → 10400。 */
    @Test
    void attachmentMapsRowAndRejectsBadIdOrMissingRow() {
        given(attachmentMapper.selectById(3L)).willReturn(attachment(3L, 11L, "image"));
        assertThat(service.attachment("3").id()).isEqualTo("3");
        assertThat(service.attachment("3").messageId()).isEqualTo("11");
        assertThat(service.attachment("3").blobSha256()).isEqualTo("sha256-3");

        given(attachmentMapper.selectById(4L)).willReturn(null);
        bizError(() -> service.attachment("4"), 10400, null);
        bizError(() -> service.attachment("x1"), 10102, "attachment_id");
    }

    // ---------- §5.7 members / §5.10 statistics / §5.11 ingest-batches ----------

    /** union=true 走视图 v_wx_group_members_union（跨账号并集 + seen_in_accounts），不碰本账号成员表。 */
    @Test
    void unionMembersComeFromTheViewMapper() {
        WxMessageQueryMapper.UnionMemberRow r = new WxMessageQueryMapper.UnionMemberRow();
        r.setGroupUsername(CHAT);
        r.setMemberUsername("wxid_m1");
        r.setSeenInAccounts(2L);
        given(messageQueryMapper.pageUnionMembers(any(), eq(CHAT))).willAnswer(inv -> {
            Page<WxMessageQueryMapper.UnionMemberRow> p = inv.getArgument(0);
            p.setTotal(3);
            return List.of(r);
        });

        PageVO<QueryVOs.GroupMemberVO> page = service.groupMembers(CHAT, "wxid_a", true, 1, 2);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hasMore()).as("1*2 < 3").isTrue();
        assertThat(page.list().get(0).groupUsername()).isEqualTo(CHAT);
        assertThat(page.list().get(0).memberUsername()).isEqualTo("wxid_m1");
        assertThat(page.list().get(0).seenInAccounts()).isEqualTo(2L);
        assertThat(page.list().get(0).id()).as("视图行没有本账号行 id").isNull();
        verifyNoInteractions(groupMemberRelMapper);
    }

    /** union=false 查本账号成员行（seen_in_accounts 恒 null）；group_username 必填（10101）且先于 DB。 */
    @Test
    @SuppressWarnings("unchecked")
    void plainMembersComeFromRelMapperAndGroupUsernameIsRequired() {
        WxGroupMemberRelEntity entity = new WxGroupMemberRelEntity();
        entity.setId(5L);
        entity.setAccount("wxid_a");
        entity.setGroupUsername(CHAT);
        entity.setMemberUsername("wxid_m1");
        Page<WxGroupMemberRelEntity> hit = new Page<>(1, 20);
        hit.setTotal(1);
        hit.setRecords(List.of(entity));
        given(groupMemberRelMapper.selectPage(any(), any())).willReturn(hit);

        PageVO<QueryVOs.GroupMemberVO> page = service.groupMembers(CHAT, "wxid_a", false, 1, 20);

        assertThat(page.list().get(0).id()).isEqualTo(5L);
        assertThat(page.list().get(0).account()).isEqualTo("wxid_a");
        assertThat(page.list().get(0).seenInAccounts()).as("非 union 恒 null").isNull();

        ArgumentCaptor<Page<WxGroupMemberRelEntity>> pages = pageCaptor();
        ArgumentCaptor<LambdaQueryWrapper<WxGroupMemberRelEntity>> wrappers =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(groupMemberRelMapper).selectPage(pages.capture(), wrappers.capture());
        assertThat(boundValues(wrappers.getValue())).contains(CHAT, "wxid_a");

        bizError(() -> service.groupMembers("  ", null, false, 1, 20), 10101, "group_username");
        verifyNoMoreInteractions(groupMemberRelMapper);
    }

    /** 去重统计：视图行透传 + accounts JSON 还原成列表（坏 JSON 兜底空列表）+ chat 过滤裁剪。 */
    @Test
    void dedupStatisticsMapsAccountsJsonAndForwardsChatFilter() {
        WxMessageQueryMapper.DedupRow broken = dedup(12L, "{not-json");
        broken.setServerId(null);
        given(messageQueryMapper.pageDedup(any(), eq(CHAT))).willAnswer(inv -> {
            Page<WxMessageQueryMapper.DedupRow> p = inv.getArgument(0);
            p.setTotal(41);
            return List.of(dedup(11L, "[\"wxid_a\",\"wxid_b\"]"), broken);
        });

        PageVO<QueryVOs.DedupVO> page = service.dedupStatistics(" " + CHAT + " ", 2, 20);

        assertThat(page.total()).isEqualTo(41);
        assertThat(page.hasMore()).as("2*20 < 41").isTrue();
        assertThat(page.list().get(0).messageId()).isEqualTo("11");
        assertThat(page.list().get(0).serverId()).isEqualTo("9001");
        assertThat(page.list().get(0).accounts()).containsExactly("wxid_a", "wxid_b");
        assertThat(page.list().get(1).serverId()).isNull();
        assertThat(page.list().get(1).accounts()).as("坏 JSON 兜底空列表").isEmpty();
        verify(messageQueryMapper).pageDedup(any(), eq(CHAT));
    }

    /** ingest-batches：显式列清单排除 error 大字段（DB_DESIGN §12.2）+ 钳制转发 + 行映射。 */
    @Test
    @SuppressWarnings("unchecked")
    void ingestBatchesExcludeErrorColumnAndMapRowsWithClampedPage() {
        Page<WxIngestBatchLogEntity> hit = new Page<>(1, 20);
        hit.setTotal(1);
        hit.setRecords(List.of(batch(9L, "b1")));
        given(batchLogMapper.selectPage(any(), any())).willReturn(hit);

        PageVO<QueryVOs.IngestBatchVO> page = service.ingestBatches("wxid_a", 0, 0);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(page.list().get(0).id()).isEqualTo("9");
        assertThat(page.list().get(0).batchRef()).isEqualTo("b1");
        assertThat(page.list().get(0).nInserted()).isEqualTo(2);

        ArgumentCaptor<LambdaQueryWrapper<WxIngestBatchLogEntity>> wrappers =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(batchLogMapper).selectPage(any(), wrappers.capture());
        String select = wrappers.getValue().getSqlSelect();
        assertThat(select).contains("n_messages", "started_at", "status");
        assertThat(select).as("error 文本大字段不进 VO 查询").doesNotContain("error");
        assertThat(boundValues(wrappers.getValue())).contains("wxid_a");
    }

    // ---------- 造数与工具 ----------

    private static MessageRow row(long id, OffsetDateTime time, String body) {
        MessageRow row = new MessageRow();
        row.setId(id);
        row.setChatUsername(CHAT);
        row.setCreateTime(time);
        row.setBody(body);
        row.setTypeName("文本");
        return row;
    }

    private static WxMessageSourceRelEntity rel(long messageId, String account, String shard, long localId) {
        WxMessageSourceRelEntity e = new WxMessageSourceRelEntity();
        e.setMessageId(messageId);
        e.setAccount(account);
        e.setShard(shard);
        e.setLocalId(localId);
        return e;
    }

    private static WxMessageAttachmentEntity attachment(long id, long messageId, String kind) {
        WxMessageAttachmentEntity e = new WxMessageAttachmentEntity();
        e.setId(id);
        e.setMessageId(messageId);
        e.setKind(kind);
        e.setTier("HD");
        e.setBlobSha256("sha256-3");
        return e;
    }

    private static WxIngestBatchLogEntity batch(long id, String batchRef) {
        WxIngestBatchLogEntity e = new WxIngestBatchLogEntity();
        e.setId(id);
        e.setAccount("wxid_a");
        e.setBatchRef(batchRef);
        e.setStatus("OK");
        e.setNMessages(3);
        e.setNInserted(2);
        return e;
    }

    private static WxMessageQueryMapper.DedupRow dedup(long id, String accountsJson) {
        WxMessageQueryMapper.DedupRow r = new WxMessageQueryMapper.DedupRow();
        r.setId(id);
        r.setChatUsername(CHAT);
        r.setDedupKey("srv:9001");
        r.setServerId(9001L);
        r.setSourceAccounts(2L);
        r.setAccountsJson(accountsJson);
        return r;
    }

    private static SearchRequest searchWithCursor(String cursor) {
        return new SearchRequest(null, null, null, null, null, null, null, cursor, null);
    }

    /** Base64Url 手工构造非法位点（encode 的合法产物覆盖不到的形态）。 */
    private static String cursorOf(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** pageMessages 全参通配（末位 int 用 anyInt），只关心返回行的用例复用。 */
    private void pageReturns(List<MessageRow> rows) {
        given(messageQueryMapper.pageMessages(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyInt())).willReturn(rows);
    }

    /** 业务码 + error[0].field 断言（10101/10102/10400 统一出口；field=null 表示只看码）。 */
    private static void bizError(ThrowableAssert.ThrowingCallable call, int expectedCode, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(expectedCode);
            if (field != null) {
                assertThat(firstError(e).field()).isEqualTo(field);
            }
        });
    }

    /** wrapper 的 WHERE 绑定值：先渲染 sqlSegment（MP 懒求值），HashMap 无序 → 断言用无序形态。 */
    private static List<Object> boundValues(LambdaQueryWrapper<?> wrapper) {
        wrapper.getSqlSegment();
        return new ArrayList<>(wrapper.getParamNameValuePairs().values());
    }

    @SuppressWarnings("unchecked")
    private static <E> ArgumentCaptor<LambdaQueryWrapper<E>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<Page<T>> pageCaptor() {
        return ArgumentCaptor.forClass((Class) Page.class);
    }

    private static ApiError firstError(ApiException e) {
        return errors(e).get(0);
    }

    /** ApiException.errorDetail 实为 List&lt;ApiError&gt;（强类型化前的过渡签名）。 */
    @SuppressWarnings("unchecked")
    private static List<ApiError> errors(ApiException e) {
        return (List<ApiError>) e.getErrorDetail();
    }
}
