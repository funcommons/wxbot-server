package com.wechat.sync.server.subscription.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.common.WxErrorCode;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.ConsumerCreatedVO;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.ConsumerVO;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.DeliveryRowVO;
import com.wechat.sync.server.subscription.entity.WxSubscriptionDeliveryEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import com.wechat.sync.server.subscription.service.ConsumerService;
import com.wechat.sync.server.subscription.service.DeliveryService;
import com.wechat.sync.server.subscription.service.DeliveryVOs.AckItemResult;
import com.wechat.sync.server.subscription.service.DeliveryVOs.DeliveryAckCommand;
import com.wechat.sync.server.subscription.service.DeliveryVOs.PullResultVO;
import com.wechat.sync.server.subscription.service.SubscriptionService;
import com.wechat.sync.server.subscription.service.SubscriptionService.CreateResult;
import com.wechat.sync.server.subscription.service.SubscriptionService.UpdateCommand;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 订阅域 HTTP 层单测（TaskControllersTest 先例：不起 Spring 上下文、直接调用控制器 + 注解反射，
 * 鉴权拦截器属框架行为不测）。覆盖：admin 类级 @RequiresToken 防线与代表路由、consumer 控制器
 * **不走框架令牌**（设计 §6 独立主体，控制器内自行 Bearer 校验——MockHttpServletRequest 传头，
 * AuthMissingTokenEnvelopeTest 同款缝隙）、consumer 侧 10300/10102 入口拦截、ack/nack 10700 部分
 * 成功（全部成功 code=0 / 任一失败 10700 且 data 非空逐条结果）、admin 明文凭据只此一次（响应有 /
 * 列表无）、deliveries 分页六件套、requeue-dead/redeliver 的 consumerId 解析转发、SSRF 10607 透传、
 * ID Long 出参锚。
 *
 * <p><b>落盘口径（对齐 T10-6 业务现实，2026-11-10）</b>：控制器鉴权先于路径解析（pull 先
 * authenticate 再 SubPathIds.parse，垃圾 id 用例据此断言顺序）；request record 嵌在控制器内
 * （{@code ConsumerCreateRequest}/{@code SubscriptionCreateRequest}/{@code SubscriptionUpdateRequest}/
 * {@code AckRequest}）；VO 在 {@code dto.SubscriptionAdminVOs}；ack/nack 信封由控制器按逐条结果
 * 推导（全成功 0，任一失败 10700）；redeliver 的行解析走 {@code deliveryService.getDelivery}
 * （不存在 → 10605 由 service 抛）。
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionControllersTest {

    private static final long CONSUMER_ID = 7L;
    private static final long SUB_ID = 5L;
    private static final String BEARER = "Bearer tok-consumer-1";
    private static final String RAW_TOKEN = "tok-consumer-1";

    @Mock private ConsumerService consumerService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private DeliveryService deliveryService;
    @Mock private WxSubscriptionMapper subscriptionMapper;
    @Mock private WxSubscriptionDeliveryMapper deliveryMapper;

    private SubscriptionAdminController admin;
    private SubscriptionConsumerController consumer;

    @BeforeAll
    static void initTables() {
        MpTestTables.init(WxSubscriptionEntity.class, WxSubscriptionDeliveryEntity.class);
    }

    @BeforeEach
    void setUp() {
        admin = new SubscriptionAdminController(consumerService, subscriptionService,
                deliveryService, subscriptionMapper, deliveryMapper);
        consumer = new SubscriptionConsumerController(deliveryService);
    }

    // ------------------------------------------------------------ 类级防线与代表路由

    /** admin 类级 admin/admin 防线；consumer 控制器**无** @RequiresToken（§6：POLL 独立主体自校验）。 */
    @Test
    void adminClassGuardedAndConsumerControllerStandsOutsideFrameworkGuard() {
        RequiresToken adminGuard = SubscriptionAdminController.class.getAnnotation(RequiresToken.class);
        assertThat(adminGuard).as("admin 类级防线").isNotNull();
        assertThat(adminGuard.value()).isEqualTo(TokenService.ADMIN_TYPE);
        assertThat(adminGuard.roles()).containsExactly(TokenService.ROLE_ADMIN);

        assertThat(SubscriptionConsumerController.class.getAnnotation(RequiresToken.class))
                .as("consumer 控制器不走框架令牌——Bearer consumer token 自行校验（§6）").isNull();
    }

    @Test
    void representativeRoutesMatchDesignV11() throws NoSuchMethodException {
        assertThat(SubscriptionAdminController.class
                .getMethod("createConsumer", SubscriptionAdminController.ConsumerCreateRequest.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                .value()).containsExactly("/v1/consumers");
        assertThat(SubscriptionAdminController.class
                .getMethod("listDeliveries", String.class, String.class, long.class, long.class)
                .getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)
                .value()).containsExactly("/v1/subscriptions/{id}/deliveries");
        assertThat(SubscriptionAdminController.class
                .getMethod("requeueDead", String.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                .value()).containsExactly("/v1/subscriptions/{id}/requeue-dead");
        assertThat(SubscriptionAdminController.class
                .getMethod("redeliver", String.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                .value()).containsExactly("/v1/subscriptions/deliveries/{delivery_id}/redeliver");
        assertThat(SubscriptionAdminController.class
                .getMethod("updateSubscription", String.class,
                        SubscriptionAdminController.SubscriptionUpdateRequest.class)
                .getAnnotation(org.springframework.web.bind.annotation.PutMapping.class)
                .value()).containsExactly("/v1/subscriptions/{id}");

        assertThat(SubscriptionConsumerController.class
                .getMethod("pull", String.class, int.class, HttpServletRequest.class)
                .getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)
                .value()).containsExactly("/v1/subscriptions/{id}/messages");
        assertThat(SubscriptionConsumerController.class
                .getMethod("ack", String.class,
                        SubscriptionConsumerController.AckRequest.class, HttpServletRequest.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                .value()).containsExactly("/v1/subscriptions/{id}/ack");
        assertThat(SubscriptionConsumerController.class
                .getMethod("nack", String.class,
                        SubscriptionConsumerController.AckRequest.class, HttpServletRequest.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                .value()).containsExactly("/v1/subscriptions/{id}/nack");
        // 拉取只许 GET（§6 拉取不产生投递副作用，误配 POST 会让爬虫/预取消耗可见性租约）——
        // Spring 6.1 @GetMapping 已无 method() 属性，GET 语义锚在组合元注解 @RequestMapping 上
        assertThat(org.springframework.core.annotation.AnnotatedElementUtils
                .findMergedAnnotation(SubscriptionConsumerController.class
                        .getMethod("pull", String.class, int.class, HttpServletRequest.class),
                        org.springframework.web.bind.annotation.RequestMapping.class)
                .method()).containsExactly(RequestMethod.GET);
    }

    // ------------------------------------------------------------ consumer 侧（POLL 三件）

    /** Bearer 剥前缀 → authenticateConsumer → 以 consumer.id 归属拉取；max 缺省 50（§6 示例口径）。 */
    @Test
    void pullStripsBearerAuthenticatesThenForwardsOwnedPullWithDefaultMax() {
        given(deliveryService.authenticateConsumer(RAW_TOKEN))
                .willReturn(consumerEntity(CONSUMER_ID));
        given(deliveryService.pull(SUB_ID, CONSUMER_ID, 50)).willReturn(
                new PullResultVO(SUB_ID, 60, List.of(), false));

        ApiResponse<PullResultVO> resp = consumer.pull("5", 50, request(BEARER));

        assertThat(resp.getCode()).as("拉取成功信封").isZero();
        assertThat(resp.getData().subscriptionId()).isEqualTo(SUB_ID);
        assertThat(resp.getData().visibilitySeconds()).isEqualTo(60);
        assertThat(resp.getData().hasMore()).isFalse();
        verify(deliveryService).authenticateConsumer(RAW_TOKEN);
        verify(deliveryService).pull(SUB_ID, CONSUMER_ID, 50);
    }

    /** max 显式值原样透传（1..500 越界钳制与 10102 是 service.requirePullMax 职责，控制器只转发）。 */
    @Test
    void pullForwardsExplicitMaxVerbatim() {
        given(deliveryService.authenticateConsumer(RAW_TOKEN)).willReturn(consumerEntity(CONSUMER_ID));
        given(deliveryService.pull(SUB_ID, CONSUMER_ID, 500)).willReturn(
                new PullResultVO(SUB_ID, 60, List.of(), false));

        consumer.pull("5", 500, request(BEARER));

        verify(deliveryService).pull(SUB_ID, CONSUMER_ID, 500);
    }

    /** 缺头/非 Bearer 形态 → 10300 且**不触服务**（控制器自守）；空白 token 落 service 鉴权（10300 同码）。 */
    @Test
    void pullRejectsMissingOrMalformedBearerWithoutServiceCall() {
        assertThatThrownBy(() -> consumer.pull("5", 50, request(null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10300));
        assertThatThrownBy(() -> consumer.pull("5", 50, request("Basic dXNlcjpwYXNz")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10300));
        verify(deliveryService, never()).authenticateConsumer(any());

        // "Bearer " 前缀合法但 token 空白：控制器放行给 service 鉴权（authenticateConsumer 对空白
        // token 直接 10300，零 DB——DeliveryServiceTest G 组已锚），此处断信封与文本透传
        given(deliveryService.authenticateConsumer("   "))
                .willThrow(new ApiException(10300, "consumer_token 无效"));
        assertThatThrownBy(() -> consumer.pull("5", 50, request("Bearer    ")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10300));
        verify(deliveryService, never()).pull(anyLong(), anyLong(), anyInt());
    }

    /** service 鉴权失败 10300 → 原样上抛，error 文本点名 consumer_token（§6 口径）；不再走 pull。 */
    @Test
    void pullPropagates10300NamingConsumerTokenField() {
        given(deliveryService.authenticateConsumer(RAW_TOKEN))
                .willThrow(new ApiException(10300, "consumer_token 无效：sha256 不匹配"));

        assertThatThrownBy(() -> consumer.pull("5", 50, request(BEARER)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10300);
                    assertThat(e.getMessage()).as("error[].field=consumer_token 的文本锚").contains("consumer_token");
                });
        verify(deliveryService, never()).pull(anyLong(), anyLong(), anyInt());
    }

    /**
     * 非数字 subscription_id → 10102 入口拦截，pull 零交互。
     * 落盘顺序：控制器**先鉴权后解析**（authenticate 在 SubPathIds.parse 之前）——垃圾 id 用例
     * 据此断言 authenticateConsumer 已调、pull 未调（解析在鉴权管道之后）。
     */
    @Test
    void pullRejectsGarbageSubscriptionIdAs10102() {
        given(deliveryService.authenticateConsumer(RAW_TOKEN)).willReturn(consumerEntity(CONSUMER_ID));

        assertThatThrownBy(() -> consumer.pull("abc", 50, request(BEARER)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(deliveryService).authenticateConsumer(RAW_TOKEN);
        verify(deliveryService, never()).pull(anyLong(), anyLong(), anyInt());
    }

    /**
     * ack 批量逐行命令对（v1.1 裁决：每行独立 lease）→ 控制器按逐条结果推导信封：
     * 任一失败 → <b>10700</b> 部分成功（data 非空，逐条 success/replayed/error 可读）。
     */
    @Test
    void ackForwardsPerRowCommandPairsAndMarksPartialSuccess10700() {
        given(deliveryService.authenticateConsumer(RAW_TOKEN)).willReturn(consumerEntity(CONSUMER_ID));
        List<DeliveryAckCommand> commands = List.of(
                new DeliveryAckCommand(101L, "lease-a"), new DeliveryAckCommand(102L, "lease-b"));
        given(deliveryService.ack(SUB_ID, CONSUMER_ID, commands)).willReturn(List.of(
                new AckItemResult(101L, true, false, null),
                new AckItemResult(102L, false, false, "10606")));

        ApiResponse<List<AckItemResult>> resp =
                consumer.ack("5", new SubscriptionConsumerController.AckRequest(commands), request(BEARER));

        assertThat(resp.getCode()).as("任一失败 → 10700 部分成功（mc-api-spec 例外）").isEqualTo(10700);
        assertThat(resp.getData()).as("部分成功 data 非 null").hasSize(2);
        assertThat(resp.getData().get(0).success()).isTrue();
        assertThat(resp.getData().get(1).success()).isFalse();
        assertThat(resp.getData().get(1).error()).as("异 lease 单条失败不整批炸").contains("10606");
        verify(deliveryService).ack(SUB_ID, CONSUMER_ID, commands);
    }

    /** ack 全部成功 → 信封 code=0（部分成功的对照面）。 */
    @Test
    void ackAllSuccessGetsZeroEnvelope() {
        given(deliveryService.authenticateConsumer(RAW_TOKEN)).willReturn(consumerEntity(CONSUMER_ID));
        List<DeliveryAckCommand> commands = List.of(new DeliveryAckCommand(101L, "lease-a"));
        given(deliveryService.ack(SUB_ID, CONSUMER_ID, commands)).willReturn(List.of(
                new AckItemResult(101L, true, false, null)));

        ApiResponse<List<AckItemResult>> resp =
                consumer.ack("5", new SubscriptionConsumerController.AckRequest(commands), request(BEARER));

        assertThat(resp.getCode()).as("全成功 → 普通成功信封").isZero();
        assertThat(resp.getData()).hasSize(1);
    }

    /** nack 部分失败 data 仍非 null 且逐条 success/replayed/error 可读（10700 部分成功口径）。 */
    @Test
    void nackPartialFailureKeepsDataNonNullWithPerItemFields() {
        given(deliveryService.authenticateConsumer(RAW_TOKEN)).willReturn(consumerEntity(CONSUMER_ID));
        List<DeliveryAckCommand> commands = List.of(
                new DeliveryAckCommand(201L, "lease-c"), new DeliveryAckCommand(202L, "lease-d"));
        given(deliveryService.nack(SUB_ID, CONSUMER_ID, commands)).willReturn(List.of(
                new AckItemResult(201L, true, true, null),
                new AckItemResult(202L, false, false, "10606 租约已失效")));

        ApiResponse<List<AckItemResult>> resp =
                consumer.nack("5", new SubscriptionConsumerController.AckRequest(commands), request(BEARER));

        assertThat(resp.getCode()).as("部分失败 → 10700").isEqualTo(10700);
        assertThat(resp.getData()).as("部分成功 data 非 null（10700 语义）").isNotNull();
        assertThat(resp.getData().get(0).replayed()).as("重放幂等逐条可见").isTrue();
        assertThat(resp.getData().get(1).success()).isFalse();
    }

    /** ack/nack 复用同一鉴权前置：令牌失败时不触批量汇报。 */
    @Test
    void ackRejectsBadBearerBeforeBatchReport() {
        assertThatThrownBy(() -> consumer.ack("5",
                        new SubscriptionConsumerController.AckRequest(List.of()), request(null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10300));
        verify(deliveryService, never()).ack(anyLong(), anyLong(), anyList());
    }

    // ------------------------------------------------------------ admin 侧

    /** POST /v1/consumers 响应携带明文凭据（只此一次，reg-code 先例）；列表 VO 无任何明文/哈希键。 */
    @Test
    void createConsumerReturnsPlaintextOnceAndListVoNeverCarriesCredentialFields() {
        given(consumerService.create("bi-系统", true, true, null, null)).willReturn(
                new ConsumerCreatedVO(11L, "bi-系统", "poll-tok-plain", "whsec-plain", null, null, null));

        ApiResponse<ConsumerCreatedVO> resp = admin.createConsumer(
                new SubscriptionAdminController.ConsumerCreateRequest("bi-系统", true, true, null, null));

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().pollToken()).as("POLL token 明文只此一次").isEqualTo("poll-tok-plain");
        assertThat(resp.getData().webhookSecret()).as("WEBHOOK secret 明文只此一次").isEqualTo("whsec-plain");

        // 结构性锚：列表 VO 的组件集里不允许出现任何凭据形态字段（明文或哈希都不可出参）
        List<String> components = java.util.Arrays.stream(ConsumerVO.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(components).contains("id", "name");
        assertThat(components).noneMatch(n -> n.toLowerCase().contains("token")
                || n.toLowerCase().contains("secret") || n.toLowerCase().contains("hash"));

        given(consumerService.list(1, 20)).willReturn(
                new PageVO<>(List.of(new ConsumerVO(11L, "bi-系统", true, false, null, null, null)),
                        1, 1, 20, false));
        ApiResponse<PageVO<ConsumerVO>> page = admin.listConsumers(1, 20);
        assertThat(page.getData().list()).hasSize(1);
    }

    /** create 转发 CreateCommand 九件（filter Map 原样；controllerId String→parse long）；alreadyExists 原样出参。 */
    @Test
    void createSubscriptionForwardsCommandAndWrapsAlreadyExists() {
        Map<String, Object> filter = Map.of("chats", List.of("123@chatroom"), "type_codes", List.of("1"));
        WxSubscriptionEntity entity = subscriptionEntity();
        given(subscriptionService.create(any())).willReturn(new CreateResult(entity, true));

        ApiResponse<?> resp = admin.createSubscription(new SubscriptionAdminController.SubscriptionCreateRequest(
                "7", "群消息", filter, "POLL", null, null, null, null, null));

        assertThat(resp.getCode()).as("already_exists=true 也是成功信封").isZero();
        org.mockito.ArgumentCaptor<SubscriptionService.CreateCommand> captor =
                org.mockito.ArgumentCaptor.forClass(SubscriptionService.CreateCommand.class);
        verify(subscriptionService).create(captor.capture());
        assertThat(captor.getValue().consumerId()).isEqualTo(CONSUMER_ID);
        assertThat(captor.getValue().filter()).isEqualTo(filter);
        assertThat(captor.getValue().deliveryMode()).isEqualTo("POLL");
        assertThat(captor.getValue().webhookUrl()).as("POLL 订阅不强制 webhook_url").isNull();
    }

    /** SSRF 接线在 service.create（录入时校验，§5.4）——控制器只透传 10607；validator 矩阵已有专测。 */
    @Test
    void createSubscriptionPropagatesSsrfRejection10607() {
        given(subscriptionService.create(any()))
                .willThrow(new ApiException(10607, "webhook_url 非法: ssrf blocked http://127.0.0.1/x"));

        assertThatThrownBy(() -> admin.createSubscription(
                new SubscriptionAdminController.SubscriptionCreateRequest(
                        "7", "w", null, "WEBHOOK", "http://127.0.0.1/x", null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10607));
    }

    /**
     * PUT 入口语义：非数字 id → 10102（SubPathIds 先于 service，零交互）；空体（全 null 请求）→
     * 控制器兜底默认 record 后转发，10101「至少一键」语义在 service（mock 抛）——控制器职责边界
     * 与任务域 PUT 先例（10102 入口拦截）一致。
     */
    @Test
    void updateSubscriptionRejectsGarbageIdWithoutServiceCallAndEmptyBodyFallsTo10101() {
        assertThatThrownBy(() -> admin.updateSubscription("abc", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(subscriptionService, never()).update(anyLong(), any());

        given(subscriptionService.update(anyLong(), any()))
                .willThrow(new ApiException(10101, "至少提供一个可更新字段"));
        assertThatThrownBy(() -> admin.updateSubscription("5", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
    }

    @Test
    void updateSubscriptionForwardsParsedIdAndCommandFields() {
        given(subscriptionService.update(SUB_ID, new UpdateCommand(0, null, null, 30, null, null)))
                .willReturn(subscriptionEntity());

        admin.updateSubscription("5",
                new SubscriptionAdminController.SubscriptionUpdateRequest(0, null, null, 30, null, null));

        verify(subscriptionService).update(SUB_ID,
                new UpdateCommand(0, null, null, 30, null, null));
    }

    @Test
    void deleteAndGetForwardParsedId() {
        given(subscriptionService.get(SUB_ID)).willReturn(subscriptionEntity());
        assertThat(admin.getSubscription("5").getCode()).isZero();
        verify(subscriptionService).get(SUB_ID);

        admin.deleteSubscription("5");
        verify(subscriptionService).delete(SUB_ID);
    }

    /** deliveries 视图六件套分页 + 状态过滤转发（§4 死信管理视图，Offset 分页）。 */
    @Test
    void deliveriesListReturnsSixPaginationFieldsAndForwardsStatusFilter() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WxSubscriptionDeliveryEntity> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(deliveryRow()));
        page.setTotal(21);
        given(deliveryMapper.selectPage(any(), any())).willReturn(page);

        ApiResponse<PageVO<DeliveryRowVO>> resp = admin.listDeliveries("5", "DEAD", 1, 20);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().list()).hasSize(1);
        assertThat(resp.getData().total()).isEqualTo(21);
        assertThat(resp.getData().page()).isEqualTo(1);
        assertThat(resp.getData().pageSize()).isEqualTo(20);
        assertThat(resp.getData().hasMore()).as("total=21 > 1×20 有下一页").isTrue();
        assertThat(resp.getData().list().get(0).status()).isEqualTo("DEAD");
        assertThat(resp.getData().list().get(0).attempts()).isEqualTo(8);
        assertThat(resp.getData().list().get(0).responseCode()).isEqualTo(502);
        assertThat(resp.getData().list().get(0).error()).contains("connect");
        // 状态过滤以 wrapper 透传：selectPage 的 wrapper 是 Lambda 形态，此处只锚「查过一次」
        verify(deliveryMapper).selectPage(any(), any());
    }

    /** 非法状态字面量 → 10102 入口拦截（status 闭集，requireStatus 先于 selectPage）。 */
    @Test
    void deliveriesListRejectsUnknownStatusBeforeQuerying() {
        assertThatThrownBy(() -> admin.listDeliveries("5", "WHAT", 1, 20))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(deliveryMapper, never()).selectPage(any(), any());
    }

    /** requeue-dead：控制器解析订阅行取 consumerId 再调 service 双参方法（admin 可信仍过同一状态机）。 */
    @Test
    void requeueDeadResolvesConsumerIdFromSubscriptionThenForwards() {
        given(subscriptionMapper.selectById(SUB_ID)).willReturn(subscriptionEntity());

        ApiResponse<Integer> resp = admin.requeueDead("5");

        assertThat(resp.getCode()).as("死信重排成功信封 code=0").isZero();
        verify(deliveryService).requeueDead(SUB_ID, CONSUMER_ID);
    }

    @Test
    void requeueDeadRejectsMissingSubscriptionAs10605WithoutServiceCall() {
        given(subscriptionMapper.selectById(SUB_ID)).willReturn(null);

        assertThatThrownBy(() -> admin.requeueDead("5"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10605));
        verify(deliveryService, never()).requeueDead(anyLong(), anyLong());
    }

    /**
     * redeliver：控制器经 {@code deliveryService.getDelivery} 取行自带 consumer_id 回填
     * （行不存在 → getDelivery 内 requireDelivery 直接 10605——mock 抛同码断透传）。
     */
    @Test
    void redeliverResolvesConsumerIdFromDeliveryRowThenForwards() {
        given(deliveryService.getDelivery(33L)).willReturn(deliveryRow());

        ApiResponse<Long> resp = admin.redeliver("33");

        assertThat(resp.getCode()).as("误投救济成功信封 code=0").isZero();
        verify(deliveryService).redeliver(33L, CONSUMER_ID);
    }

    @Test
    void redeliverRejectsMissingDeliveryAs10605WithoutServiceCall() {
        given(deliveryService.getDelivery(33L))
                .willThrow(WxErrorCode.subscriptionNotFound("投递", 33L));

        assertThatThrownBy(() -> admin.redeliver("33"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10605));
        verify(deliveryService, never()).redeliver(anyLong(), anyLong());
    }

    /** ID 出参 String 由框架 Jackson 全局 Long→String 提供——本锚钉住 VO 字段保持 Long（防改 int 丢精度/改 String 绕过全局策略）。 */
    @Test
    void idComponentsStayLongDelegatingStringificationToGlobalJacksonPolicy() {
        for (Class<?> vo : List.of(
                com.wechat.sync.server.subscription.service.DeliveryVOs.PullItemVO.class,
                com.wechat.sync.server.subscription.service.DeliveryVOs.PullResultVO.class,
                com.wechat.sync.server.subscription.service.DeliveryVOs.DeliveryMessageVO.class)) {
            java.util.Arrays.stream(vo.getRecordComponents())
                    .filter(c -> c.getName().endsWith("Id") && !c.getName().equals("leaseId"))
                    .forEach(c -> assertThat(c.getType())
                            .as(vo.getSimpleName() + "." + c.getName() + " 保持 Long（全局 Long→String 出参）")
                            .isEqualTo(Long.class));
        }
    }

    // ------------------------------------------------------------ 夹具

    /** POLL 控制器鉴权缝隙：MockHttpServletRequest 传 Authorization 头（AuthMissingTokenEnvelopeTest 同款）。 */
    private MockHttpServletRequest request(String authorizationHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            req.addHeader("Authorization", authorizationHeader);
        }
        return req;
    }

    private com.wechat.sync.server.subscription.entity.WxConsumerEntity consumerEntity(long id) {
        com.wechat.sync.server.subscription.entity.WxConsumerEntity c =
                new com.wechat.sync.server.subscription.entity.WxConsumerEntity();
        c.setId(id);
        c.setName("bi-系统");
        return c;
    }

    private WxSubscriptionEntity subscriptionEntity() {
        WxSubscriptionEntity s = new WxSubscriptionEntity();
        s.setId(SUB_ID);
        s.setConsumerId(CONSUMER_ID);
        s.setName("群消息");
        s.setDeliveryMode("POLL");
        s.setOrderMode("PARALLEL");
        s.setVisibleSeconds(60);
        s.setMaxAttempts(8);
        s.setBackoffBaseSeconds(30);
        s.setEnabled(1);
        return s;
    }

    private WxSubscriptionDeliveryEntity deliveryRow() {
        WxSubscriptionDeliveryEntity d = new WxSubscriptionDeliveryEntity();
        d.setId(33L);
        d.setSubscriptionId(SUB_ID);
        d.setConsumerId(CONSUMER_ID);
        d.setMessageId(999L);
        d.setStatus("DEAD");
        d.setAttempts(8);
        d.setMaxAttempts(8);
        d.setLeaseSeconds(60);
        d.setResponseCode(502);
        d.setError("connect refused");
        return d;
    }
}
