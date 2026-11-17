package com.wechat.sync.server.subscription.service;

import com.wechat.sync.server.message.dto.MessagesReportRequest;
import com.wechat.sync.server.message.entity.WxIngestBatchLogEntity;
import com.wechat.sync.server.message.entity.WxMessageAttachmentEntity;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.entity.WxMessageSourceRelEntity;
import com.wechat.sync.server.message.entity.WxMergeLogEntity;
import com.wechat.sync.server.message.entity.WxSyncCursorEntity;
import com.wechat.sync.server.message.entity.WxBlobEntity;
import com.wechat.sync.server.message.mapper.WxIngestBatchLogMapper;
import com.wechat.sync.server.message.mapper.WxMessageAttachmentMapper;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import com.wechat.sync.server.message.mapper.WxMessageSourceRelMapper;
import com.wechat.sync.server.message.mapper.WxMergeLogMapper;
import com.wechat.sync.server.message.mapper.WxSyncCursorMapper;
import com.wechat.sync.server.message.mapper.WxBlobMapper;
import com.wechat.sync.server.message.service.MessagesIngestService;
import com.wechat.sync.server.subscription.entity.WxSubscriptionDeliveryEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.entity.WxSubDeliveryCursorEntity;
import com.wechat.sync.server.subscription.mapper.DeliveryEnqueueRow;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import com.wechat.sync.server.subscription.mapper.WxSubDeliveryCursorMapper;
import com.wechat.sync.server.support.MpTestTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 订阅触发链路单测（mock mapper / mock matcher，只测匹配与编排；SQL 语义归 T10-8 真库 IT）：
 * ① 六键匹配矩阵（SUBSCRIPTION_DESIGN §2.2：键间 AND、数组=IN、keyword 大小写敏感、
 * include_revoked 缺省 true、'{}'=全量）——谓词在 {@link SubscriptionFilter.Parsed}；
 * ② 快照刷新语义（§3.3：refresh 前旧快照——新建订阅最多晚一个快照周期生效；坏 filter 跳过不拖垮快照）；
 * ③ 钩子编排（§3.1：{@link SubscriptionTrigger} 批量 enqueueBatch 入队、异常吞掉返回 -1——v1.1「吞掉+兜底」；
 * 入队行带订阅快照 max_attempts/lease_seconds，§2.3 去规范化）；
 * ④ ingest 接线（§3.1：只对 inserted 新消息触发，merged/unchanged 不触发；trigger 缺位=T4 原状；
 * 真实 Trigger 的吞失败在 ingest 链路终点复验）；
 * ⑤ 补扫 runBackstop（§3.2：游标高水位推进、仅在水位可前进时推进、行数透传）。
 *
 * <p>落盘契约（照业务实现断言）：SubscriptionMatcher 构造器 (WxSubscriptionMapper)，
 * 方法 refresh()/snapshotSize()/match(account, List&lt;IncomingMessage&gt;)→List&lt;DeliveryEnqueueRow&gt;；
 * SubscriptionTrigger 构造器 (SubscriptionMatcher, WxSubscriptionDeliveryMapper)，
 * trigger(account, List&lt;IncomingMessage&gt;)→int（空表 0、异常吞掉 -1）；
 * WxSubscriptionDeliveryMapper.enqueueBatch(List&lt;DeliveryEnqueueRow&gt;)→int；
 * SubscriptionBackstopService 构造器 (cursorMapper, deliveryMapper, messageMapper)，
 * runBackstop()/runBackstop(int)；MessagesIngestService 注入字段 subscriptionTrigger
 * （@Autowired(required=false)，类型 SubscriptionTrigger，构造器零变化）。
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionMatcherTest {

    private static final String ACCOUNT = "wxid_t10_a";
    private static final String ACCOUNT_B = "wxid_t10_b";
    private static final String CHAT = "t10room@chatroom";
    private static final String SENDER = "wxid_sender_1";

    @Mock private WxSubscriptionMapper subscriptionMapper;
    @Mock private WxSubscriptionDeliveryMapper deliveryMapper;
    @Mock private WxSubDeliveryCursorMapper cursorMapper;
    @Mock private WxMessageMapper messageMapper;

    private SubscriptionMatcher matcher;
    private SubscriptionBackstopService backstop;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxSubscriptionEntity.class, WxSubscriptionDeliveryEntity.class,
                WxSubDeliveryCursorEntity.class,
                WxMessageEntity.class, WxMessageSourceRelEntity.class, WxMessageAttachmentEntity.class,
                WxSyncCursorEntity.class, WxIngestBatchLogEntity.class, WxMergeLogEntity.class,
                WxBlobEntity.class);
    }

    @BeforeEach
    void setUp() {
        matcher = new SubscriptionMatcher(subscriptionMapper);
        backstop = new SubscriptionBackstopService(cursorMapper, deliveryMapper, messageMapper);
    }

    // ==================== ① 六键匹配矩阵（Parsed 谓词，参数化） ====================

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("matrix")
    void sixKeyMatrix(String label, String filterJson, String account, WxMessageEntity message,
                      boolean expected) {
        assertThat(matches(filterJson, account, message))
                .as("契约 %s：filter=%s account=%s msg(chat=%s,sender=%s,type=%s,body=%s,revoked=%s) → %s",
                        label, filterJson, account, message.getChatUsername(), message.getSenderUsername(),
                        message.getTypeCode(), message.getBody(), message.getIsRevoked(), expected)
                .isEqualTo(expected);
    }

    static Stream<Arguments> matrix() {
        WxMessageEntity base = msgEnt(CHAT, SENDER, "1", "Hello World", false);
        return Stream.of(
                // accounts：对 ingest 批内现成 account 判定（§2.2——不在消息实体上）
                Arguments.of("accounts 命中", "{\"accounts\":[\"wxid_t10_a\"]}", ACCOUNT, base, true),
                Arguments.of("accounts 未命中", "{\"accounts\":[\"wxid_t10_a\"]}", ACCOUNT_B, base, false),
                // chats / senders / type_codes：String 精确 IN
                Arguments.of("chats 命中", "{\"chats\":[\"" + CHAT + "\"]}", ACCOUNT, base, true),
                Arguments.of("chats 未命中", "{\"chats\":[\"other@chatroom\"]}", ACCOUNT, base, false),
                Arguments.of("senders 命中", "{\"senders\":[\"" + SENDER + "\"]}", ACCOUNT, base, true),
                Arguments.of("senders 未命中", "{\"senders\":[\"wxid_other\"]}", ACCOUNT, base, false),
                Arguments.of("type_codes 命中", "{\"type_codes\":[\"49\",\"1\"]}", ACCOUNT, base, true),
                Arguments.of("type_codes 未命中", "{\"type_codes\":[\"10002\"]}", ACCOUNT, base, false),
                // keyword：body 子串、大小写敏感（§2.2 明示）
                Arguments.of("keyword 大小写一致命中", "{\"keyword\":\"World\"}", ACCOUNT, base, true),
                Arguments.of("keyword 大小写不符不命中", "{\"keyword\":\"world\"}", ACCOUNT, base, false),
                // include_revoked：缺省 true（撤回也投）；false 时滤掉撤回（§2.2）
                Arguments.of("include_revoked 缺省含撤回", "{}", ACCOUNT, msgEnt(CHAT, SENDER, "1", "x", true), true),
                Arguments.of("include_revoked=false 滤撤回",
                        "{\"include_revoked\":false}", ACCOUNT, msgEnt(CHAT, SENDER, "1", "x", true), false),
                Arguments.of("include_revoked=false 放行未撤回",
                        "{\"include_revoked\":false}", ACCOUNT, msgEnt(CHAT, SENDER, "1", "x", false), true),
                // 多键 AND：一键不命中全不命中（§2.2「键之间 AND」）
                Arguments.of("chat+type 双命中", "{\"chats\":[\"" + CHAT + "\"],\"type_codes\":[\"1\"]}",
                        ACCOUNT, base, true),
                Arguments.of("chat 命中 type 不命中",
                        "{\"chats\":[\"" + CHAT + "\"],\"type_codes\":[\"10002\"]}", ACCOUNT, base, false),
                // '{}' 空 filter = 全量订阅（任何消息命中，含撤回/任意账号）
                Arguments.of("空 filter 全量", "{}", ACCOUNT_B, msgEnt("any@chatroom", "wxid_x", "10002", "", true), true));
    }

    @Test
    void senderNullDoesNotMatchSenderFilter() {
        // sender_username 为空的消息（系统提示等）不该命中 senders 条件（条件存在而字段 null → 不命中）
        assertThat(matches("{\"senders\":[\"" + SENDER + "\"]}", ACCOUNT,
                msgEnt(CHAT, null, "1", "x", false))).isFalse();
    }

    @Test
    void typeCodeNullDoesNotMatchTypeFilter() {
        assertThat(matches("{\"type_codes\":[\"1\"]}", ACCOUNT,
                msgEnt(CHAT, SENDER, null, "x", false))).isFalse();
    }

    @Test
    void brokenFilterSubscriptionIsSkippedAtRefreshNotDroppingSnapshot() {
        // 原「非法 JSON filter 不命中且不抛」意图的现实形态：坏 filter 在 refresh 逐颗跳过
        //（不进快照、不拖垮别的订阅），等价于「该订阅永不命中」——兜底口径落在快照构建侧
        WxSubscriptionEntity broken = sub("{not-json");
        WxSubscriptionEntity good = sub("{\"chats\":[\"" + CHAT + "\"]}");
        good.setId(12L);        // sub() 缺省都给 11：同 id 会让「非 broken」断言恒假，先区分开
        given(subscriptionMapper.selectList(any())).willReturn(List.of(broken, good));

        assertThatCode(() -> matcher.refresh()).doesNotThrowAnyException();
        assertThat(matcher.snapshotSize()).as("坏 filter 跳过，好订阅照常进快照").isEqualTo(1);

        List<DeliveryEnqueueRow> rows =
                matcher.match(ACCOUNT, List.of(incoming(101L)));
        assertThat(rows).as("只有好订阅命中（坏订阅等价于不存在）").hasSize(1);
        assertThat(rows.get(0).getSubscriptionId()).as("命中行属于好订阅").isEqualTo(good.getId());
    }

    // ==================== ② 快照刷新语义（§3.3 30s 延迟窗口） ====================

    @Test
    void newSubscriptionInvisibleUntilRefresh() {
        // 新建订阅在 refresh 前不可见：旧快照继续用——「创建订阅后最早 30s 的消息才可能命中」锚。
        // matcher 只匹配不入队（入队归 Trigger）：deliveryMapper 全程零交互
        WxSubscriptionEntity created = sub("{\"chats\":[\"" + CHAT + "\"]}");
        assertThat(matcher.snapshotSize()).as("构造后快照为空（显式 refresh 口径，无懒加载）").isZero();
        assertThat(matcher.match(ACCOUNT, List.of(incoming(100L))))
                .as("未刷新前匹配零命中").isEmpty();

        given(subscriptionMapper.selectList(any())).willReturn(List.of(created));
        matcher.refresh();
        assertThat(matcher.snapshotSize()).isEqualTo(1);
        assertThat(matcher.match(ACCOUNT, List.of(incoming(101L)))).hasSize(1);
        verifyNoInteractions(deliveryMapper);
    }

    @Test
    void refreshReplacesSnapshotDisabledSubscriptionStopsMatching() {
        // refresh 前命中 → selectList 改回空表（订阅被禁用/软删后 DB 查询不再返回）→ refresh 后不再命中
        given(subscriptionMapper.selectList(any())).willReturn(List.of(sub("{}")));
        matcher.refresh();
        assertThat(matcher.match(ACCOUNT, List.of(incoming(101L)))).hasSize(1);

        given(subscriptionMapper.selectList(any())).willReturn(List.of());
        matcher.refresh();
        assertThat(matcher.snapshotSize()).as("refresh 后禁用订阅出快照").isZero();
        assertThat(matcher.match(ACCOUNT, List.of(incoming(102L)))).isEmpty();
    }

    @Test
    void matchCarriesSubscriptionSnapshotFields() {
        // §2.3 去规范化：入队行带订阅快照（max_attempts/lease_seconds←visible_seconds）
        WxSubscriptionEntity s = sub("{\"chats\":[\"" + CHAT + "\"]}");
        s.setMaxAttempts(5);
        s.setVisibleSeconds(90);
        given(subscriptionMapper.selectList(any())).willReturn(List.of(s));
        matcher.refresh();

        List<DeliveryEnqueueRow> rows = matcher.match(ACCOUNT, List.of(incoming(101L)));

        assertThat(rows).hasSize(1);
        DeliveryEnqueueRow row = rows.get(0);
        assertThat(row.getSubscriptionId()).isEqualTo(11L);
        assertThat(row.getConsumerId()).as("consumer_id 冗余快照（订阅删除后仍可归因，§2.3）").isEqualTo(22L);
        assertThat(row.getMessageId()).isEqualTo(101L);
        assertThat(row.getMaxAttempts()).as("快照自订阅，改订阅只影响新投递").isEqualTo(5);
        assertThat(row.getLeaseSeconds()).as("visible_seconds 双用之一（POLL 可见性/WEBHOOK 租约）").isEqualTo(90);
    }

    @Test
    void matchOnEmptyMessagesOrEmptySnapshotIsNoop() {
        assertThat(matcher.match(ACCOUNT, List.of())).as("空消息表 → 零候选").isEmpty();
        assertThat(matcher.match(ACCOUNT, null)).as("null 防御 → 零候选").isEmpty();

        given(subscriptionMapper.selectList(any())).willReturn(List.of(sub("{\"chats\":[\"other\"]}")));
        matcher.refresh();
        assertThat(matcher.match(ACCOUNT, List.of(incoming(101L))))
                .as("未命中订阅 → 零候选").isEmpty();
        verifyNoInteractions(deliveryMapper);
    }

    // ==================== ③ 钩子编排：Trigger 批量入队 + 异常吞掉（v1.1 核心锚） ====================

    @Test
    void triggerEnqueuesMatchedRowsAndReturnsInsertedCount() {
        SubscriptionMatcher matcherMock = mock(SubscriptionMatcher.class);
        given(matcherMock.match(eq(ACCOUNT), anyList())).willReturn(
                List.of(new DeliveryEnqueueRow(11L, 22L, 101L, 5, 90)));
        given(deliveryMapper.enqueueBatch(anyList())).willReturn(1);

        SubscriptionTrigger trigger = new SubscriptionTrigger(matcherMock, deliveryMapper);
        int inserted = trigger.trigger(ACCOUNT, List.of(incoming(101L)));

        assertThat(inserted).as("返回值=实际插入行数（观测面）").isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeliveryEnqueueRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(deliveryMapper).enqueueBatch(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        assertThat(rows.getValue().get(0).getSubscriptionId()).isEqualTo(11L);
        assertThat(rows.getValue().get(0).getMessageId()).isEqualTo(101L);
    }

    @Test
    void triggerSwallowsMatcherFailureAndInsertFailure() {
        // §3.1 v1.1 口径核心锚：匹配/入队任一环失败吞掉（log.warn）返回 -1，不回滚不抛——
        // 缺口由 60s 补扫兜底（消息落库是既成事实，投递失败不拖垮 ingest）
        SubscriptionMatcher brokenMatcher = mock(SubscriptionMatcher.class);
        given(brokenMatcher.match(eq(ACCOUNT), anyList()))
                .willThrow(new RuntimeException("snapshot deserialization"));
        SubscriptionTrigger t1 = new SubscriptionTrigger(brokenMatcher, deliveryMapper);
        assertThat(t1.trigger(ACCOUNT, List.of(incoming(101L))))
                .as("匹配器失败吞掉 → -1（观测口径，调用方不判值）").isEqualTo(-1);

        SubscriptionMatcher okMatcher = mock(SubscriptionMatcher.class);
        given(okMatcher.match(eq(ACCOUNT), anyList())).willReturn(
                List.of(new DeliveryEnqueueRow(11L, 22L, 101L, 8, 60)));
        doThrow(new RuntimeException("db down")).when(deliveryMapper).enqueueBatch(anyList());
        SubscriptionTrigger t2 = new SubscriptionTrigger(okMatcher, deliveryMapper);
        assertThatCode(() -> t2.trigger(ACCOUNT, List.of(incoming(101L))))
                .as("入队失败吞掉：绝不外泄").doesNotThrowAnyException();
        assertThat(t2.trigger(ACCOUNT, List.of(incoming(101L)))).isEqualTo(-1);
    }

    @Test
    void triggerNoopOnEmptyList() {
        SubscriptionTrigger trigger = new SubscriptionTrigger(mock(SubscriptionMatcher.class), deliveryMapper);
        assertThat(trigger.trigger(ACCOUNT, List.of())).as("空消息表 → 0 且零 mapper 交互").isZero();
        assertThat(trigger.trigger(ACCOUNT, null)).as("null 防御 → 0").isZero();
        verifyNoInteractions(deliveryMapper);
    }

    // ==================== ④ ingest 接线（§3.1：inserted 才触发；trigger 缺位=T4 原状） ====================

    @Test
    void ingestHookFiresOnlyForInsertedRows() {
        MessagesIngestService ingest = newIngest();
        SubscriptionTrigger trigger = mock(SubscriptionTrigger.class);
        ReflectionTestUtils.setField(ingest, "subscriptionTrigger", trigger);

        runTxInline(ingest);
        given(messageMapper.selectOne(any())).willReturn(null);
        doAnswer(inv -> {
            inv.getArgument(0, WxMessageEntity.class).setId(101L);
            return 1;
        }).when(messageMapper).insert(any(WxMessageEntity.class));
        given(sourceRelMapper.insert(any(WxMessageSourceRelEntity.class))).willReturn(1);

        ingest(requestOf(msg("db/MSG0.db", 10, "12345", 1760000000L, sha(1))), ingest);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubscriptionMatcher.IncomingMessage>> inserted =
                ArgumentCaptor.forClass(List.class);
        verify(trigger).trigger(eq(ACCOUNT), inserted.capture());
        assertThat(inserted.getValue()).as("只带本批真正 INSERT 的新消息（merged 补键不算新，§3.1）")
                .hasSize(1);
        SubscriptionMatcher.IncomingMessage incoming = inserted.getValue().get(0);
        assertThat(incoming.messageId()).isEqualTo(101L);
        assertThat(incoming.chatUsername()).isEqualTo(CHAT);
    }

    @Test
    void ingestHookSilentForUnchangedAndMergedRows() {
        // unchanged（srv 键直查命中，inserted=0）与 merged（fb 补键，§4.2 action=merged）都不触发
        MessagesIngestService ingest = newIngest();
        SubscriptionTrigger trigger = mock(SubscriptionTrigger.class);
        ReflectionTestUtils.setField(ingest, "subscriptionTrigger", trigger);

        runTxInline(ingest);
        given(messageMapper.selectOne(any())).willReturn(
                existingRow(101L, "srv:12345"),                                  // srv 命中 → unchanged
                null, existingRow(400L, "fb:" + CHAT + "||1760000000|" + sha(1)), null);  // → merged
        given(mergeLogMapper.insert(any(WxMergeLogEntity.class))).willReturn(1);

        ingest(requestOf(msg("db/MSG0.db", 10, "12345", 1760000000L, sha(1))), ingest);
        ingest(requestOf(msg("db/MSG0.db", 10, "7001", 1760000000L, sha(1))), ingest);

        verify(trigger, never()).trigger(any(), anyList());
    }

    @Test
    void ingestHookFailureDoesNotRollBackMessageTransaction() {
        // v1.1「吞掉+兜底」链路终点（现实形态：吞失败 owner 在真实 Trigger 内部）——
        // 注入真 Trigger + 其 enqueueBatch 依赖抛异常 → ingest 正常返回、消息仍是 inserted
        MessagesIngestService ingest = newIngest();
        SubscriptionMatcher matcherMock = mock(SubscriptionMatcher.class);
        given(matcherMock.match(eq(ACCOUNT), anyList())).willReturn(
                List.of(new DeliveryEnqueueRow(11L, 22L, 102L, 8, 60)));
        doThrow(new RuntimeException("delivery db down")).when(deliveryMapper).enqueueBatch(anyList());
        ReflectionTestUtils.setField(ingest, "subscriptionTrigger",
                new SubscriptionTrigger(matcherMock, deliveryMapper));

        runTxInline(ingest);
        given(messageMapper.selectOne(any())).willReturn(null);
        doAnswer(inv -> {
            inv.getArgument(0, WxMessageEntity.class).setId(102L);
            return 1;
        }).when(messageMapper).insert(any(WxMessageEntity.class));
        given(sourceRelMapper.insert(any(WxMessageSourceRelEntity.class))).willReturn(1);

        MessagesIngestService.IngestOutcome out =
                ingest(requestOf(msg("db/MSG0.db", 10, "12346", 1760000000L, sha(2))), ingest);

        assertThat(out).as("钩子异常不外泄").isNotNull();
        assertThat(out.data().results().get(0).action()).as("消息本身照常落库").isEqualTo("inserted");
    }

    @Test
    void ingestWithoutWiredTriggerBehavesAsT4() {
        // trigger 未装配（@Autowired(required=false) 缺位）→ T4 原状：零订阅交互、ingest 正常
        MessagesIngestService ingest = newIngest();
        runTxInline(ingest);
        given(messageMapper.selectOne(any())).willReturn(null);
        doAnswer(inv -> {
            inv.getArgument(0, WxMessageEntity.class).setId(103L);
            return 1;
        }).when(messageMapper).insert(any(WxMessageEntity.class));
        given(sourceRelMapper.insert(any(WxMessageSourceRelEntity.class))).willReturn(1);

        MessagesIngestService.IngestOutcome out =
                ingest(requestOf(msg("db/MSG0.db", 10, "12347", 1760000000L, sha(3))), ingest);

        assertThat(out.data().results().get(0).action()).isEqualTo("inserted");
        verifyNoInteractions(deliveryMapper);        // 钩子缺位 → 投递表零交互
    }

    // ==================== ⑤ 补扫 runBackstop（§3.2：游标高水位 + 行数透传） ====================

    @Test
    void backstopAdvancesCursorToMaxScannedId() {
        given(cursorMapper.selectOne(any())).willReturn(cursorRow(100L));
        given(messageMapper.selectOne(any())).willReturn(messageRow(250L));
        given(deliveryMapper.backstopEnqueue(eq(100L), eq(600))).willReturn(7);

        int inserted = backstop.runBackstop();

        assertThat(inserted).as("行数透传（观测面）").isEqualTo(7);
        verify(deliveryMapper).backstopEnqueue(eq(100L), eq(600));
        verify(cursorMapper).upsertGlobalCursor(eq("global"), eq(250L));
    }

    @Test
    void backstopFirstRunStartsFromZeroWatermark() {
        // 游标行不存在（§2.4：由补扫首轮自建）→ 高水位 0 起步；首轮有新消息 → 水位建行推进
        given(cursorMapper.selectOne(any())).willReturn(null);
        given(messageMapper.selectOne(any())).willReturn(messageRow(42L));
        given(deliveryMapper.backstopEnqueue(eq(0L), eq(600))).willReturn(3);

        assertThat(backstop.runBackstop()).isEqualTo(3);
        verify(deliveryMapper).backstopEnqueue(eq(0L), eq(600));
        verify(cursorMapper).upsertGlobalCursor(eq("global"), eq(42L));
    }

    @Test
    void backstopEmptyWindowKeepsWatermarkWithoutAdvancing() {
        // 窗内无新消息：业务口径「仅在水位可前进时推进」（GREATEST 防回退的服务层预判）——
        // 空窗连 upsert SQL 都不发，游标保持原值（原「upsert 幂等无害」意图的现实等价形态）
        given(cursorMapper.selectOne(any())).willReturn(cursorRow(100L));
        given(messageMapper.selectOne(any())).willReturn(null);
        given(deliveryMapper.backstopEnqueue(eq(100L), eq(600))).willReturn(0);

        assertThat(backstop.runBackstop()).isZero();
        verify(cursorMapper, never()).upsertGlobalCursor(anyString(), anyLong());
    }

    @Test
    void backstopFirstRunEmptyWindowDefersCursorRowCreation() {
        // 双空（无游标行 + 无新消息）：不 upsert——游标行延迟到首条真实消息时自建
        //（readMaxMessageId 空表返回 0，0 > 0 不推进；§2.4「补扫首轮自建」的现实口径）
        given(cursorMapper.selectOne(any())).willReturn(null);
        given(messageMapper.selectOne(any())).willReturn(null);
        given(deliveryMapper.backstopEnqueue(eq(0L), eq(600))).willReturn(0);

        assertThat(backstop.runBackstop()).isZero();
        verify(cursorMapper, never()).upsertGlobalCursor(anyString(), anyLong());
    }

    // ==================== 造数辅助 ====================

    /** Parsed 谓词直判（六键矩阵用）：filter JSON → parse → matches(实体字段展开)。 */
    private static boolean matches(String filterJson, String account, WxMessageEntity message) {
        return SubscriptionFilter.parse(filterJson).matches(account,
                message.getChatUsername(), message.getSenderUsername(),
                message.getTypeCode(), message.getBody(),
                Boolean.TRUE.equals(message.getIsRevoked()));
    }

    private static SubscriptionMatcher.IncomingMessage incoming(long messageId) {
        return new SubscriptionMatcher.IncomingMessage(messageId, CHAT, SENDER, "1", "x", false);
    }

    private WxSubscriptionEntity sub(String filterJson) {
        WxSubscriptionEntity e = new WxSubscriptionEntity();
        e.setId(11L);
        e.setConsumerId(22L);
        e.setFilter(filterJson);
        e.setEnabled(1);
        e.setIsDeleted(0);
        e.setMaxAttempts(8);
        e.setVisibleSeconds(60);
        return e;
    }

    private static WxMessageEntity msgEnt(String chat, String sender, String typeCode, String body,
                                          boolean revoked) {
        WxMessageEntity e = new WxMessageEntity();
        e.setChatUsername(chat);
        e.setSenderUsername(sender);
        e.setTypeCode(typeCode);
        e.setBody(body);
        e.setIsRevoked(revoked);
        return e;
    }

    private static WxSubDeliveryCursorEntity cursorRow(long watermark) {
        WxSubDeliveryCursorEntity e = new WxSubDeliveryCursorEntity();
        e.setScope("global");
        e.setLastMessageId(watermark);
        return e;
    }

    private static WxMessageEntity messageRow(long id) {
        WxMessageEntity e = new WxMessageEntity();
        e.setId(id);
        return e;
    }

    private static WxMessageEntity existingRow(long id, String dedupKey) {
        WxMessageEntity e = new WxMessageEntity();
        e.setId(id);
        e.setDedupKey(dedupKey);
        e.setChatUsername(CHAT);
        return e;
    }

    // ---- ingest 夹具（自含最小复制，MessagesIngestServiceTest 同款；不跨测试类 import） ----

    @Mock private WxMessageSourceRelMapper sourceRelMapper;
    @Mock private WxMessageAttachmentMapper attachmentMapper;
    @Mock private WxSyncCursorMapper ingestCursorMapper;
    @Mock private WxIngestBatchLogMapper batchLogMapper;
    @Mock private WxMergeLogMapper mergeLogMapper;
    @Mock private WxBlobMapper blobMapper;
    @Mock private TransactionTemplate transactionTemplate;

    private MessagesIngestService newIngest() {
        return new MessagesIngestService(messageMapper, sourceRelMapper, attachmentMapper,
                ingestCursorMapper, batchLogMapper, mergeLogMapper, blobMapper, transactionTemplate);
    }

    private void runTxInline(MessagesIngestService ingest) {
        given(transactionTemplate.execute(any())).willAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private MessagesIngestService.IngestOutcome ingest(MessagesReportRequest request,
                                                       MessagesIngestService service) {
        return service.ingest(request);
    }

    private static MessagesReportRequest requestOf(MessagesReportRequest.MessageItem... items) {
        return new MessagesReportRequest(ACCOUNT, "batch-t10", List.of(
                new MessagesReportRequest.ChatItem(CHAT, null, List.of(items))));
    }

    private static MessagesReportRequest.MessageItem msg(String shard, long localId, String serverId,
                                                         long createTimeRaw, String sha) {
        return new MessagesReportRequest.MessageItem(shard, localId, serverId, null,
                createTimeRaw * 1000, createTimeRaw, "10000", "文本", null, "SHARD_NAME2ID",
                "正文-" + localId, null, sha, Boolean.FALSE, null);
    }

    private static String sha(int seed) {
        return String.format("%064d", seed);
    }
}
