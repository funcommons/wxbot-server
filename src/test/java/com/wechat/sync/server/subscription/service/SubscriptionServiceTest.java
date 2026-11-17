package com.wechat.sync.server.subscription.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.sync.server.subscription.entity.WxConsumerEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.mapper.WxConsumerMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.web.ApiException;
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
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * SubscriptionService 单测（mock mapper，只测 filter 契约与幂等编排；SQL 语义归 T10-8 真库 IT）：
 * ① 10604 校验矩阵（SUBSCRIPTION_DESIGN §2.2：未知键/类型错/列表>100/keyword>64；JSON 边界在
 *    Controller 反序列化层——非对象/非法 JSON 进不了 normalize 的 Map）；
 * ② 规范化幂等（键排序、列表去重排序、去空值、数值↔字符串字面量归一 v1.1；
 *    <b>显式 include_revoked=true 与缺省等价收敛</b>——业务口径「语义缺省不落键」，'{}' 与
 *    '{"include_revoked":true}' 同 hash，javadoc 明示）；
 * ③ filter_hash = sha256(规范形 JSON)（防重复订阅键 §2.2）；
 * ④ create 幂等（预查 uk 命中 already_exists=true；并发 DuplicateKeyException 兜底查回；
 *    校验顺序=consumer 先行、非法 filter 零订阅表往返）；
 * ⑤ update：filter 不可变（<b>类型层面</b>不含 filter 组件——改条件=删旧建新）、
 *    空命令 10101、enabled 部分更新只动一键。
 *
 * <p>落盘契约（照业务实现断言）：SubscriptionService 构造器 (WxSubscriptionMapper, WxConsumerMapper)；
 * create(CreateCommand)→CreateResult(subscription, alreadyExists)；update(long, UpdateCommand)→实体；
 * filter 校验/规范化/hash 在静态工具 {@link SubscriptionFilter}
 * （normalize(Map)/toJson(Map)/sha256Hex(String)）。入参 record 嵌套在 Service 内
 * （CreateCommand 9 参 / UpdateCommand 6 参），无独立 dto 包。
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final long CONSUMER_ID = 9L;
    private static final long SUB_ID = 77L;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private WxSubscriptionMapper subscriptionMapper;
    @Mock private WxConsumerMapper consumerMapper;

    private SubscriptionService service;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxSubscriptionEntity.class, WxConsumerEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(subscriptionMapper, consumerMapper);
    }

    // ==================== ① 10604 校验矩阵 ====================

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidFilters")
    void filterInvalidMatrix(String label, String filterJson) {
        assertThatThrownBy(() -> SubscriptionFilter.normalize(mapOf(filterJson)))
                .as("契约 %s：filter=%s 必须被 10604 拒绝（SUBSCRIPTION_DESIGN §2.2 键集与类型）", label, filterJson)
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCode()).as("未知键/类型错/超限 → 10604").isEqualTo(10604));
    }

    static Stream<Arguments> invalidFilters() {
        String over100 = IntStream.rangeClosed(0, 100)
                .mapToObj(i -> "\"" + i + "\"").collect(Collectors.joining(","));
        return Stream.of(
                Arguments.of("未知键", "{\"foo\":[\"x\"]}"),
                Arguments.of("accounts 类型错（字符串而非列表）", "{\"accounts\":\"wxid_a\"}"),
                Arguments.of("chats 类型错", "{\"chats\":\"a@chatroom\"}"),
                Arguments.of("senders 类型错", "{\"senders\":\"wxid_a\"}"),
                Arguments.of("type_codes 类型错", "{\"type_codes\":\"1\"}"),
                Arguments.of("列表超 100", "{\"chats\":[" + over100 + "]}"),
                Arguments.of("keyword 超 64 字符", "{\"keyword\":\"" + "k".repeat(65) + "\"}"),
                Arguments.of("include_revoked 非布尔", "{\"include_revoked\":\"yes\"}"));
    }

    /**
     * JSON 边界（Controller 反序列化层，normalize 的 Map 入参进不来）：非对象 JSON / 非法 JSON
     * 在测试 helper 的 readValue(Map) 处即抛——「这两形到不了 normalize」本身就是契约
     * （T10-6 Controller 绑定 Map<String,Object> 时天然拒绝）。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("nonObjectFilters")
    void nonObjectJsonNeverReachesNormalize(String label, String filterJson) {
        assertThatThrownBy(() -> mapOf(filterJson))
                .as("契约 %s：非对象 JSON 在反序列化层即拒（进不了 normalize）", label)
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> nonObjectFilters() {
        return Stream.of(
                Arguments.of("非对象 JSON", "[\"chats\"]"),
                Arguments.of("非法 JSON", "{not-json"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("validFilters")
    void filterAcceptsKnownShapes(String label, String filterJson) {
        assertThatCode(() -> SubscriptionFilter.normalize(mapOf(filterJson)))
                .as("契约 %s：合法形态必须放行", label)
                .doesNotThrowAnyException();
    }

    static Stream<Arguments> validFilters() {
        return Stream.of(
                Arguments.of("空对象=全量订阅", "{}"),
                Arguments.of("全键合法", "{\"accounts\":[\"a\"],\"chats\":[\"c@chatroom\"],"
                        + "\"senders\":[\"s\"],\"type_codes\":[\"1\",\"49\"],\"keyword\":\"k\","
                        + "\"include_revoked\":false}"),
                Arguments.of("type_codes 数值字面量（归一为字符串，不拒收）", "{\"type_codes\":[25]}"),
                // 业务宽松口径：keyword 走 String.valueOf 归一（数值字面量不拒收；严格类型校验不在此层）
                Arguments.of("keyword 数值字面量归一不拒收", "{\"keyword\":123}"));
    }

    // ==================== ② 规范化幂等（filter_hash 防重复订阅的根基） ====================

    @Test
    void canonicalIsKeyOrderInsensitive() {
        // 键排序归一：两形 JSON 文本不同、语义相同 → 同一规范化文本（→ 同 hash，uk 才能拦住重复订阅）
        assertThat(canonical("{\"chats\":[\"a\",\"b\"],\"senders\":[\"s\"]}"))
                .isEqualTo(canonical("{\"senders\":[\"s\"],\"chats\":[\"a\",\"b\"]}"));
    }

    @Test
    void canonicalDedupsAndSortsListValues() {
        assertThat(canonical("{\"chats\":[\"b\",\"a\",\"b\"]}"))
                .as("列表去重+排序（§2.2「列表去重排序后的 JSON 文本」）")
                .isEqualTo(canonical("{\"chats\":[\"a\",\"b\"]}"));
    }

    @Test
    void canonicalDropsEmptyValues() {
        assertThat(canonical("{\"chats\":[],\"keyword\":\"\"}"))
                .as("空列表/空串按「去空值」剔除 → 与 '{}' 同形")
                .isEqualTo(canonical("{}"));
    }

    @Test
    void canonicalUnifiesNumericAndStringLiterals() {
        // v1.1 修订：数值与字符串按字符串字面量归一——杜绝同义两形条件漏判重复
        assertThat(canonical("{\"type_codes\":[25]}"))
                .isEqualTo(canonical("{\"type_codes\":[\"25\"]}"));
    }

    @Test
    void canonicalFoldsExplicitTrueDefaultIntoEquivalentForm() {
        // 业务口径（javadoc 明示）：include_revoked=true 是语义缺省 → 规范形<b>不落键</b>，
        // 显式 true 与缺省收敛为同一形（同 hash）——语义级去重的收益侧：'{}' 与
        // '{"include_revoked":true}' 是同一订阅条件，不会两行并存
        assertThat(canonical("{}"))
                .as("显式 true 与缺省等价收敛（include_revoked=true 不落键）")
                .isEqualTo(canonical("{\"include_revoked\":true}"));
        // false 是真实收紧条件 → 落键保留
        assertThat(canonical("{\"include_revoked\":false}"))
                .as("false 是真实收紧，必须落键与缺省区分")
                .isNotEqualTo(canonical("{}"));
    }

    // ==================== ③ filter_hash = sha256(规范形 JSON) ====================

    @Test
    void filterHashIsSha256HexOfCanonicalText() throws Exception {
        String raw = "{\"chats\":[\"b\",\"a\"],\"keyword\":\"订单\"}";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical(raw).getBytes(StandardCharsets.UTF_8));
        String expected = java.util.HexFormat.of().formatHex(digest);

        assertThat(SubscriptionFilter.sha256Hex(canonical(raw)))
                .as("hash=sha256(规范形 JSON) 的 64 位小写 hex")
                .isEqualTo(expected)
                .hasSize(64);
        // 键序不同的同义两形 → 同 hash（uk 拦重复订阅的根基）
        assertThat(hashOf("{\"chats\":[\"a\",\"b\"],\"keyword\":\"订单\"}"))
                .isEqualTo(hashOf(raw));
    }

    // ==================== ④ create 幂等（§2.2 already_exists 闸门） ====================

    @Test
    void createAppliesDefaultsAndSnapshotFields() {
        given(consumerMapper.selectCount(any())).willReturn(1L);
        given(subscriptionMapper.selectOne(any())).willReturn(null);

        SubscriptionService.CreateResult result = service.create(cmd(
                mapOf("{\"chats\":[\"c@chatroom\"]}"), null, null, null, null, null));

        assertThat(result.alreadyExists()).as("首次创建 → already_exists=false").isFalse();

        ArgumentCaptor<WxSubscriptionEntity> row = ArgumentCaptor.forClass(WxSubscriptionEntity.class);
        verify(subscriptionMapper).insert(row.capture());
        WxSubscriptionEntity saved = row.getValue();
        assertThat(saved.getConsumerId()).isEqualTo(CONSUMER_ID);
        assertThat(saved.getFilter()).isEqualTo(canonical("{\"chats\":[\"c@chatroom\"]}"));
        assertThat(saved.getFilterHash())
                .isEqualTo(SubscriptionFilter.sha256Hex(saved.getFilter()));
        assertThat(saved.getDeliveryMode()).as("缺省 POLL（§2.2）").isEqualTo("POLL");
        assertThat(saved.getOrderMode()).as("缺省 PARALLEL（§2.2）").isEqualTo("PARALLEL");
        assertThat(saved.getVisibleSeconds()).as("缺省 60").isEqualTo(60);
        assertThat(saved.getMaxAttempts()).as("缺省 8").isEqualTo(8);
        assertThat(saved.getBackoffBaseSeconds()).as("缺省 30").isEqualTo(30);
        assertThat(saved.getWebhookUrl()).as("空 = 回落 consumer.webhook_url（§2.2）").isNull();
        assertThat(saved.getEnabled()).isEqualTo(1);
        assertThat(saved.getIsDeleted()).isEqualTo(0);
    }

    @Test
    void createDuplicateFilterReturnsExistingWithoutInsert() {
        given(consumerMapper.selectCount(any())).willReturn(1L);
        given(subscriptionMapper.selectOne(any())).willReturn(existing());

        SubscriptionService.CreateResult result = service.create(cmd(
                mapOf("{}"), null, null, null, null, null));

        assertThat(result.alreadyExists()).as("同 consumer 同 hash → uk 语义前置返回（不报错，§2.2）").isTrue();
        assertThat(result.subscription().getId()).isEqualTo(SUB_ID);
        verify(subscriptionMapper, never()).insert(any(WxSubscriptionEntity.class));
    }

    @Test
    void createRaceOnUkFallsBackToExistingRow() {
        // 并发竞态：预查落空 → INSERT 撞 uk DuplicateKeyException → 兜底查回既有行（不报错）
        given(consumerMapper.selectCount(any())).willReturn(1L);
        given(subscriptionMapper.selectOne(any())).willReturn(null, existing());
        willThrow(new DuplicateKeyException("uk_wx_subscription_filter"))
                .given(subscriptionMapper).insert(any(WxSubscriptionEntity.class));

        SubscriptionService.CreateResult result = service.create(cmd(
                mapOf("{}"), null, null, null, null, null));

        assertThat(result.alreadyExists()).isTrue();
        assertThat(result.subscription().getId()).isEqualTo(SUB_ID);
    }

    @Test
    void createUnknownConsumerThrows10605() {
        given(consumerMapper.selectCount(any())).willReturn(0L);

        assertThatThrownBy(() -> service.create(cmd(mapOf("{}"), null, null, null, null, null)))
                .as("consumer 不存在 → 10605 SUBSCRIPTION_NOT_FOUND（§9 错误码分配）")
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCode()).isEqualTo(10605));
    }

    @Test
    void invalidFilterRejectedWithZeroSubscriptionRoundtrip() {
        // 业务校验顺序：consumer 先行（selectCount 发起）→ filter 校验（10604）；
        // 核心意图保住：非法 filter <b>零订阅表往返</b>（不查不写 wx_subscription）
        given(consumerMapper.selectCount(any())).willReturn(1L);

        assertThatThrownBy(() -> service.create(cmd(mapOf("{\"foo\":1}"), null, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10604));
        verify(consumerMapper).selectCount(any());
        verifyNoInteractions(subscriptionMapper);
    }

    // ==================== ⑤ update：filter 不可变 + 部分更新 ====================

    @Test
    void updateFilterIsImmutableAtTypeLevel() {
        // 改条件 = 另一个订阅（filter_hash 变更会破坏 uk 防重复语义）→ 业务在<b>类型层面</b>杜绝：
        // UpdateCommand record 没有 filter 组件（比运行时拒绝更强——误用直接编译不过）
        RecordComponent[] components = SubscriptionService.UpdateCommand.class.getRecordComponents();
        assertThat(Arrays.stream(components).map(RecordComponent::getName))
                .as("UpdateCommand 组件：" + Arrays.toString(components) + " —— filter 不可变契约")
                .doesNotContain("filter");
    }

    @Test
    void updateRejectsEmptyCommandWith10101() {
        given(subscriptionMapper.selectOne(any())).willReturn(existing());

        assertThatThrownBy(() -> service.update(SUB_ID,
                new SubscriptionService.UpdateCommand(null, null, null, null, null, null)))
                .as("至少提供一个可更新字段 → 10101（照任务域口径）")
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCode()).isEqualTo(10101));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateEnabledOnlyTouchesOneKey() {
        given(subscriptionMapper.selectOne(any())).willReturn(existing());   // 前置校验 + 回读共用

        service.update(SUB_ID, new SubscriptionService.UpdateCommand(0, null, null, null, null, null));

        ArgumentCaptor<LambdaUpdateWrapper<WxSubscriptionEntity>> wrapper =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(subscriptionMapper).update(isNull(), wrapper.capture());
        String sqlSet = wrapper.getValue().getSqlSet();
        assertThat(sqlSet).as("只动 enabled 一键（updated_at 是编排字段）")
                .contains("enabled").contains("updated_at")
                .doesNotContain("filter").doesNotContain("consumer_id").doesNotContain("name")
                .doesNotContain("webhook_url").doesNotContain("order_mode")
                .doesNotContain("visible_seconds").doesNotContain("max_attempts")
                .doesNotContain("backoff_base_seconds");
    }

    // ==================== 造数辅助 ====================

    /** JSON → Map（Controller 反序列化层替身）：非对象/非法 JSON 在此即抛。 */
    private static Map<String, Object> mapOf(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(json, Map.class);
            return map;
        } catch (Exception e) {
            throw new IllegalArgumentException("filter 不是 JSON 对象: " + json, e);
        }
    }

    /** 规范形文本：normalize → toJson（业务 hash 的输入）。 */
    private static String canonical(String filterJson) {
        return SubscriptionFilter.toJson(SubscriptionFilter.normalize(mapOf(filterJson)));
    }

    /** 业务同款 hash：sha256(规范形文本)。 */
    private static String hashOf(String filterJson) {
        return SubscriptionFilter.sha256Hex(canonical(filterJson));
    }

    private static WxConsumerEntity consumer() {
        WxConsumerEntity e = new WxConsumerEntity();
        e.setId(CONSUMER_ID);
        e.setName("bi-system");
        e.setIsDeleted(0);
        return e;
    }

    private WxSubscriptionEntity existing() {
        WxSubscriptionEntity e = new WxSubscriptionEntity();
        e.setId(SUB_ID);
        e.setConsumerId(CONSUMER_ID);
        e.setFilter("{}");
        e.setFilterHash(SubscriptionFilter.sha256Hex("{}"));
        e.setEnabled(1);
        return e;
    }

    /** create 形命令：filter 之外投递参数按序传（null=缺省）。 */
    private static SubscriptionService.CreateCommand cmd(Map<String, Object> filter,
                                                         String deliveryMode, String webhookUrl,
                                                         String orderMode, Integer visibleSeconds,
                                                         Integer backoffBaseSeconds) {
        return new SubscriptionService.CreateCommand(CONSUMER_ID, "sub-1", filter, deliveryMode,
                webhookUrl, orderMode, visibleSeconds, null, backoffBaseSeconds);
    }
}
