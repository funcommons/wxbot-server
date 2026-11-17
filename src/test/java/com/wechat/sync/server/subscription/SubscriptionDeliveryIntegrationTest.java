package com.wechat.sync.server.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import com.wechat.sync.server.message.service.MessagesIngestService;
import com.wechat.sync.server.message.dto.MessagesReportRequest;
import com.wechat.sync.server.subscription.entity.WxConsumerEntity;
import com.wechat.sync.server.subscription.entity.WxSubDeliveryCursorEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionDeliveryEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.mapper.WxConsumerMapper;
import com.wechat.sync.server.subscription.mapper.WxSubDeliveryCursorMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import com.wechat.sync.server.subscription.service.DeliveryService;
import com.wechat.sync.server.subscription.service.DeliveryVOs;
import com.wechat.sync.server.subscription.service.SubscriptionBackstopService;
import com.wechat.sync.server.subscription.service.SubscriptionFilter;
import com.wechat.sync.server.subscription.service.SubscriptionMatcher;
import com.wechat.sync.server.subscription.service.SubscriptionTrigger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fun.commons.framework4j.ratelimit.config.RateLimitProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订阅域真库集成测试（直连真实 PG 5433 / Redis 6379，走真实 Mapper + Service，不起 HTTP 容器）。
 *
 * <p>覆盖审查轮 IT 盲区清单（T10-8）：ingest 同事务钩子 fan-out（一条新消息 × N 订阅 = N 行，
 * 与消息原子提交）、重复上报/补扫重放的 ON CONFLICT 零新投递（dedup 双闸门的真库证据）、
 * 并发 pull 的 FOR UPDATE SKIP LOCKED 不重派（并集=全部、交集=空）、webhook 线上协议字节级
 * 保真（真 stub server：三头/HMAC(key=sha256hex(secret))/防重序列化）、失败退避的
 * next_attempt_at 真实区间算术 + DEAD 终态保留 lease_id + 终态重放、sweep 两分支、
 * 补扫窗口缺口锚（P1#1 修后语义：水位只推进到<b>窗口内</b> max(id)）、@Update 承载 INSERT
 * 的 backstopEnqueue 实证、配额 whitelist-paths 的 yml 绑定生效。
 *
 * <p><b>D 用例口径（stub webhook）</b>：dispatcher 的 {@code submit} 管道对 URL 做 SSRF 静态
 * 校验——环回地址必被拦（这是特性不是缺陷），故「经 dispatchOnce 推到真 stub」在单测 JVM 里
 * 走不通；本类采用降级口径：<b>真 stub server + 按 dispatcher 逐字节复刻的请求构造</b>验证
 * 线上协议（头三件/签名/原始体保真/重序列化拒绝——消费方视角的完整验收），DB 状态机由
 * {@link DeliveryService} 直接驱动（认领/汇报语义已被 WebhookDispatcherTest 单测锚定）。
 *
 * <p><b>存活调度器竞态容忍</b>：本上下文（及并行的 dev 服务进程）里 SubscriptionSchedulerConfig
 * 的五轮 @Scheduled 是活的——dispatch 200ms / sweep 30s / 补扫 60s / 快照 30s。策略：
 * ① POLL 行 dispatcher 不认领（delivery_mode 互斥）→ A/B/C/G 用 POLL 订阅零竞态；
 * ② E/D 的 WEBHOOK 行直插为 DISPATCHED（带未来 lease_until，sweep 不碰）由本测试驱动，
 *    重臂认领用 {@link #claimMine}（自认领优先，被存活 tick 抢先则收编其新租约，语义不变）；
 * ③ 全部断言以「最终库态」为准，时间敏感断言用区间不用精确值。
 *
 * <p><b>数据卫生（最高优先级，先例 TaskDispatchIntegrationTest 口径）</b>：
 * 消费者名 {@code t10it_} 前缀、会话 {@code t10it_*} 前缀、账号 {@code wxid_t10it_*}；
 * wx_sub_delivery_cursor 的 {@code scope='global'} 是<b>共享生产水位</b>——不删行，测试内
 * 直写（绕过 GREATEST 允许回拨——回拨只造成一次性重扫，ON CONFLICT 保护零副作用），
 * {@code @AfterAll} 恢复到 ≥ 全局 max(id)；种子 wx_task_schedule / 真实 wx_account 永不触碰。
 */
@SpringBootTest(properties = "spring.test.context.failure.threshold=2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionDeliveryIntegrationTest {

    private static final String ACC = "wxid_t10it_main";
    private static final String CONSUMER_PREFIX = "t10it_";
    private static final String CHAT_PREFIX = "t10it_";
    private static final String CHAT_FANOUT = CHAT_PREFIX + "fanout@chatroom";
    private static final String CHAT_DUP = CHAT_PREFIX + "dup";
    private static final String CHAT_GAP = CHAT_PREFIX + "gap@chatroom";
    private static final String CHAT_PULL = CHAT_PREFIX + "pull@chatroom";
    private static final String CHAT_KW = CHAT_PREFIX + "kw@chatroom";
    private static final String CHAT_KW2 = CHAT_PREFIX + "kw2@chatroom";

    /**
     * stub webhook 收到的请求（D 用例）。
     *
     * <p>headers 用 {@link com.sun.net.httpserver.Headers}（{@code getRequestHeaders()} 的原样返回类型）：
     * 它实现 {@code Map<String,List<String>>} 且额外提供 {@code getFirst(name)} 取首个值。
     * 若声明成裸 {@code Map} 则 {@code getFirst} 不可用——那是 {@code List} 的 SequencedCollection 方法，
     * {@code Map} 没有。
     */
    private record CapturedRequest(com.sun.net.httpserver.Headers headers, byte[] body) {
    }

    @Autowired private DeliveryService deliveryService;
    @Autowired private SubscriptionTrigger trigger;
    @Autowired private SubscriptionMatcher matcher;
    @Autowired private SubscriptionBackstopService backstopService;
    @Autowired private MessagesIngestService ingestService;
    @Autowired private WxConsumerMapper consumerMapper;
    @Autowired private WxSubscriptionMapper subscriptionMapper;
    @Autowired private WxSubscriptionDeliveryMapper deliveryMapper;
    @Autowired private WxSubDeliveryCursorMapper cursorMapper;
    @Autowired private WxMessageMapper messageMapper;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ObjectProvider<RateLimitProperties> rateLimitProperties;
    @Autowired private DataSource dataSource;

    private final AtomicLong serverIdSeq = new AtomicLong(7712000);

    @BeforeAll
    void setUpClass() throws SQLException {
        purge();
    }

    @AfterAll
    void tearDownClass() throws SQLException {
        restoreGlobalCursor();
        purge();
    }

    @BeforeEach
    void fresh() throws SQLException {
        purge();
    }

    // ================================================================ A. ingest 同事务钩子 fan-out

    @Test
    void ingestHookFansOutOneMessageToTwoSubscriptionsAtomically() throws SQLException {
        long consumerId = consumer("t10it_fan", null, null);
        long subNarrow = subscription(consumerId, "t10it_sub_narrow",
                "{\"chats\":[\"" + CHAT_FANOUT + "\"],\"type_codes\":[\"10000\"]}", "POLL",
                null, 33, 5, 30, 1);
        long subBroad = subscription(consumerId, "t10it_sub_broad",
                "{\"chats\":[\"" + CHAT_FANOUT + "\"]}", "POLL",
                null, 60, 8, 30, 1);
        matcher.refresh();   // 钩子按内存快照匹配——先让两颗订阅进快照

        long messageId = ingestMessage(CHAT_FANOUT, "7712001", "hello fanout");

        List<WxSubscriptionDeliveryEntity> rows = deliveriesOfMessage(messageId);
        assertThat(rows).as("一条新消息 × 两颗命中订阅 = 两行投递（§3.1 fan-out，与消息同事务提交）")
                .hasSize(2);
        assertThat(rows).extracting(WxSubscriptionDeliveryEntity::getSubscriptionId)
                .containsExactlyInAnyOrder(subNarrow, subBroad);
        for (WxSubscriptionDeliveryEntity row : rows) {
            assertThat(row.getStatus()).as("钩子入队即 PENDING").isEqualTo("PENDING");
            assertThat(row.getConsumerId()).as("consumer_id 冗余快照随行").isEqualTo(consumerId);
        }
        WxSubscriptionDeliveryEntity narrow = rows.stream()
                .filter(r -> r.getSubscriptionId() == subNarrow).findFirst().orElseThrow();
        assertThat(narrow.getMaxAttempts()).as("入队时订阅快照 max_attempts（§2.3 去规范化）").isEqualTo(5);
        assertThat(narrow.getLeaseSeconds()).as("入队时订阅快照 lease_seconds").isEqualTo(33);

        // ON CONFLICT 幂等：同一消息按同一语义重触发（钩子重放/并发触发/人工补数）零新行
        int replay = trigger.trigger(ACC, List.of(new SubscriptionMatcher.IncomingMessage(
                messageId, CHAT_FANOUT, "wxid_t10it_sender", "10000", "hello fanout", false)));
        assertThat(replay).as("同 (subscription_id, message_id) 重触发被 uk 吸收，零新行").isZero();
        assertThat(deliveriesOfMessage(messageId)).as("重触发后仍是 2 行").hasSize(2);
    }

    // ================================================================ B. 重复上报零新投递（dedup 双闸门）

    @Test
    void duplicateIngestUnchangedAndBackstopReplayProduceZeroNewDeliveries() throws SQLException {
        long consumerId = consumer("t10it_dup", null, null);
        subscription(consumerId, "t10it_sub_dup", "{\"chats\":[\"" + CHAT_DUP + "\"]}", "POLL",
                null, 60, 8, 30, 1);
        matcher.refresh();

        long messageId = ingestMessage(CHAT_DUP, "7712002", "dup body");
        assertThat(deliveriesOfMessage(messageId)).as("首次 ingest：钩子入队 1 行").hasSize(1);

        // 同 dedup_key 二次上报 → unchanged（非 inserted）→ 钩子不触发（第一闸门：inserted 计数）
        // 口径依据 MessagesIngestService.upsertMessage：主查找按入参 dedup_key 命中 → 覆盖观测字段
        // → action=unchanged；action=merged 只出现在 fb 键行被 srv 键补齐时（契约三值
        // inserted|unchanged|merged），merged 路径由 MessageIngestIntegrationTest / MessagesIngestServiceTest 锚定。
        MessagesIngestService.IngestOutcome second = ingestOutcome(CHAT_DUP, "7712002", "dup body");
        assertThat(second.data().results()).hasSize(1);
        assertThat(second.data().results().get(0).action())
                .as("重复上报按 unchanged 落账（不产生新消息行）").isEqualTo("unchanged");
        assertThat(deliveriesOfMessage(messageId)).as("unchanged 不触发：仍 1 行").hasSize(1);

        // 第二闸门：补扫重放同消息 → ON CONFLICT 吸收零新行（uk_wx_subscription_delivery_2）
        int enqueued = deliveryMapper.backstopEnqueue(0L, SubscriptionBackstopService.DEFAULT_WINDOW_SECONDS);
        assertThat(enqueued).as("补扫重放已被投递过的消息 → 零新行").isZero();
        assertThat(deliveriesOfMessage(messageId)).hasSize(1);
    }

    // ================================================================ C. 并发 pull 不重派 + ack 逐行凭据

    @Test
    void concurrentPullClaimsEachRowExactlyOnceAndAckConsumesByOwnLease() throws Exception {
        long consumerId = consumer("t10it_puller", "t10it-poll-token", null);
        long subId = subscription(consumerId, "t10it_sub_pull", "{\"chats\":[\"" + CHAT_PULL + "\"]}",
                "POLL", null, 60, 8, 30, 1);

        List<Long> deliveryIds = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            deliveryIds.add(insertDelivery(subId, consumerId, 990000000L + i,
                    "PENDING", 0, 8, 60, null, null).getId());
        }

        List<Object> outcomes = race(
                () -> deliveryService.pull(subId, consumerId, 50),
                () -> deliveryService.pull(subId, consumerId, 50));

        List<Long> union = new ArrayList<>();
        for (Object o : outcomes) {
            assertThat(o).as("并发 pull 都应正常返回（SKIP LOCKED 空手而归也是正常返回）")
                    .isInstanceOf(DeliveryVOs.PullResultVO.class);
            for (DeliveryVOs.PullItemVO item : ((DeliveryVOs.PullResultVO) o).messages()) {
                union.add(item.deliveryId());
            }
        }
        assertThat(union).as("并集 = 6 行全被拉走").containsExactlyInAnyOrderElementsOf(deliveryIds);
        assertThat(union.stream().distinct().count())
                .as("交集 = 0：同一行绝不会被两个消费者同时拿到").isEqualTo(6);

        for (WxSubscriptionDeliveryEntity row : allDeliveriesOfSub(subId)) {
            assertThat(row.getStatus()).isEqualTo("DISPATCHED");
            assertThat(row.getAttempts()).as("认领即 attempts+1").isEqualTo(1);
            assertThat(row.getLeaseId()).as("每行独立 UUID 凭据").isNotBlank();
        }

        // ack 逐行凭据（§4 lease 条件更新真库命中）：全部 → DELIVERED
        List<DeliveryVOs.DeliveryAckCommand> commands = allDeliveriesOfSub(subId).stream()
                .map(r -> new DeliveryVOs.DeliveryAckCommand(r.getId(), r.getLeaseId()))
                .toList();
        List<DeliveryVOs.AckItemResult> acked = deliveryService.ack(subId, consumerId, commands);
        assertThat(acked).as("6 行全部 ack 成功").allSatisfy(r -> {
            assertThat(r.success()).isTrue();
            assertThat(r.replayed()).isFalse();
        });
        assertThat(allDeliveriesOfSub(subId)).allSatisfy(r -> {
            assertThat(r.getStatus()).as("ack → DELIVERED 终态").isEqualTo("DELIVERED");
            assertThat(r.getDeliveredAt()).as("终态时点落库").isNotNull();
            assertThat(r.getLeaseId()).as("终态行保留 lease_id（重放判定凭据）").isNotNull();
        });
    }

    // ================================================================ D. stub webhook 线上协议（口径②，见类注释）

    @Test
    void webhookWireProtocolReachesRealStubServerWithFaithfulBytes() throws Exception {
        String secret = "t10it-webhook-secret-0123456789abcdef";
        String secretHash = SubscriptionFilter.sha256Hex(secret);
        long consumerId = consumer("t10it_hooked", null, secretHash);
        long subId = subscription(consumerId, "t10it_sub_hook",
                "{\"chats\":[\"" + CHAT_FANOUT + "\"]}", "WEBHOOK",
                null, 60, 8, 30, 1);
        long messageId = ingestMessage(CHAT_FANOUT, "7712003", "wire body");

        // 环回 stub：SSRF 必拦环回（特性），故不经 dispatcher.submit——请求按其构造逐字节复刻
        ConcurrentLinkedQueue<CapturedRequest> captured = new ConcurrentLinkedQueue<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            captured.add(new CapturedRequest(exchange.getRequestHeaders(), body));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        stub.start();
        try {
            // 直插 DISPATCHED 行并手工组装 dispatcher 同款任务项（绕开 SSRF 拦截与存活 tick 竞态）
            String leaseId = "t10it-wire-lease-1";
            WxSubscriptionDeliveryEntity row = insertDelivery(subId, consumerId, messageId,
                    "DISPATCHED", 1, 8, 60, leaseId, OffsetDateTime.now().plusMinutes(10));
            String payloadJson = manualPayloadJson(row, messageId);
            String url = "http://127.0.0.1:" + stub.getAddress().getPort() + "/hook";

            long timestamp = System.currentTimeMillis() / 1000L;
            byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-WxSync-Timestamp", Long.toString(timestamp))
                    .header("X-WxSync-Delivery", Long.toString(row.getId()))
                    .header("X-WxSync-Signature", hmacSha256Hex(timestamp, body, secretHash))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).as("stub 返回 2xx").isEqualTo(200);

            CapturedRequest seen = awaitCapture(captured);
            assertThat(seen).as("stub 真实收到了请求（2s 轮询窗）").isNotNull();
            assertThat(seen.headers().getFirst("X-WxSync-Delivery"))
                    .as("投递 ID 头（消费方幂等键）").isEqualTo(String.valueOf(row.getId()));
            long wireTimestamp = Long.parseLong(seen.headers().getFirst("X-WxSync-Timestamp"));
            assertThat(Math.abs(wireTimestamp - System.currentTimeMillis() / 1000L))
                    .as("签名时间戳是当前 Unix 秒（±300s 重放窗内）").isLessThan(300L);
            assertThat(seen.body()).as("payloadJson 原始字节保真（禁止重序列化，§5.1）")
                    .isEqualTo(payloadJson.getBytes(StandardCharsets.UTF_8));
            assertThat(seen.headers().getFirst("X-WxSync-Signature"))
                    .as("签名 = HMAC-SHA256(key=sha256hex(secret), ts + \".\" + 原始体)，测试本地独立重算")
                    .isEqualTo(hmacSha256Hex(wireTimestamp, seen.body(), secretHash));
            // 交叉验证：对重序列化变体签名必不匹配 → 证明签名绑定在逐字节原文上
            String mutated = payloadJson + " ";
            assertThat(hmacSha256Hex(wireTimestamp, mutated.getBytes(StandardCharsets.UTF_8), secretHash))
                    .as("对任何字节漂移签名立即失配（消费方防重序列化依据）")
                    .isNotEqualTo(seen.headers().getFirst("X-WxSync-Signature"));

            // DB 状态机：2xx → completeWebhook → DELIVERED（真库条件 UPDATE 命中本人租约）
            DeliveryService.WebhookReportResult done =
                    deliveryService.completeWebhook(row.getId(), leaseId, 200);
            assertThat(done.replayed()).as("首次 complete 是真实写入").isFalse();
            assertThat(done.status()).isEqualTo("DELIVERED");
            assertThat(deliveryMapper.selectById(row.getId()).getLeaseId())
                    .as("终态行保留 lease_id").isEqualTo(leaseId);
        } finally {
            stub.stop(0);
        }
    }

    // ================================================================ E. 失败退避 / 死信 / 救济（真库时间戳算术）

    @Test
    void failWebhookBackoffThenDeadThenRequeueDeadRedeliverLifecycle() throws Exception {
        long consumerId = consumer("t10it_dler", null, SubscriptionFilter.sha256Hex("t10it-dl-secret"));
        long subId = subscription(consumerId, "t10it_sub_dl", "{\"chats\":[\"" + CHAT_DUP + "\"]}",
                "WEBHOOK", null, 60, 3, 3600, 1);   // max=3、base=3600：重排后存活 tick 无法再认领

        // 直插 DISPATCHED（未来 lease_until，sweep 不碰；存活 dispatcher 不认领 DISPATCHED 行）
        WxSubscriptionDeliveryEntity row = insertDelivery(subId, consumerId, 990001001L,
                "DISPATCHED", 1, 3, 60, "t10it-dl-l1", OffsetDateTime.now().plusMinutes(10));

        // 轮 1：attempts=1 < 3 → 退避重排，next_attempt_at ≈ now + min(3600×2^0, 3600) = 3600s
        DeliveryService.WebhookReportResult r1 =
                deliveryService.failWebhook(row.getId(), "t10it-dl-l1", 500, "boom-1");
        assertThat(r1.replayed()).isFalse();
        assertThat(r1.status()).isEqualTo("PENDING");
        WxSubscriptionDeliveryEntity after1 = deliveryMapper.selectById(row.getId());
        assertThat(after1.getStatus()).isEqualTo("PENDING");
        assertThat(after1.getLeaseId()).as("重排清租约凭据").isNull();
        assertThat(after1.getResponseCode()).isEqualTo(500);
        assertThat(after1.getError()).isEqualTo("boom-1");
        assertThat(after1.getNextAttemptAt()).as("退避区间算术（base=3600 档，±5s）")
                .isBetween(OffsetDateTime.now().plusSeconds(3595), OffsetDateTime.now().plusSeconds(3605));

        // 轮 2：重臂 → 认领（attempts=2）→ 再退避
        WxSubscriptionDeliveryEntity claimed2 = claimMine(row.getId());
        assertThat(claimed2.getAttempts()).as("认领即 attempts+1").isEqualTo(2);
        DeliveryService.WebhookReportResult r2 = deliveryService.failWebhook(
                row.getId(), claimed2.getLeaseId(), 503, "boom-2");
        assertThat(r2.status()).isEqualTo("PENDING");
        assertThat(deliveryMapper.selectById(row.getId()).getNextAttemptAt())
                .as("轮 2 退避区间（3600 封顶档）")
                .isBetween(OffsetDateTime.now().plusSeconds(3595), OffsetDateTime.now().plusSeconds(3605));

        // 轮 3：attempts=3 >= max=3 → DEAD（DLQ），终态保留 lease_id
        WxSubscriptionDeliveryEntity claimed3 = claimMine(row.getId());
        assertThat(claimed3.getAttempts()).isEqualTo(3);
        DeliveryService.WebhookReportResult r3 = deliveryService.failWebhook(
                row.getId(), claimed3.getLeaseId(), null, "boom-3");
        assertThat(r3.status()).as("额度用尽 → DEAD").isEqualTo("DEAD");
        WxSubscriptionDeliveryEntity dead = deliveryMapper.selectById(row.getId());
        assertThat(dead.getStatus()).isEqualTo("DEAD");
        assertThat(dead.getLeaseId()).as("终态行保留 lease_id（重放判定凭据，v1.1）").isEqualTo(claimed3.getLeaseId());
        assertThat(dead.getResponseCode()).isNull();
        assertThat(dead.getError()).isEqualTo("boom-3");

        // 终态重放：同 lease 的 failWebhook 重放幂等成功（真库判定，口径同 TaskService）
        DeliveryService.WebhookReportResult replay = deliveryService.failWebhook(
                row.getId(), claimed3.getLeaseId(), null, "boom-replay");
        assertThat(replay.replayed()).as("同 lease 终态重放幂等").isTrue();
        assertThat(replay.status()).isEqualTo("DEAD");

        // DLQ 救济：requeue-dead → PENDING attempts=0 清凭据立即到期
        int requeued = deliveryService.requeueDead(subId, consumerId);
        assertThat(requeued).as("死信重排行数").isEqualTo(1);
        WxSubscriptionDeliveryEntity requeuedRow = deliveryMapper.selectById(row.getId());
        assertThat(requeuedRow.getStatus()).isEqualTo("PENDING");
        assertThat(requeuedRow.getAttempts()).as("重排重置额度").isZero();
        assertThat(requeuedRow.getLeaseId()).isNull();
        assertThat(requeuedRow.getNextAttemptAt())
                .as("立即到期（±5s 区间）")
                .isBetween(OffsetDateTime.now().minusSeconds(5), OffsetDateTime.now().plusSeconds(5));

        // 再认领 → 2xx → DELIVERED → redeliver（attempts 续算不归零、delivered_at 清空）
        WxSubscriptionDeliveryEntity claimed4 = claimMine(row.getId());
        assertThat(claimed4.getAttempts()).as("重排后重新从 1 计").isEqualTo(1);
        deliveryService.completeWebhook(row.getId(), claimed4.getLeaseId(), 200);
        assertThat(deliveryMapper.selectById(row.getId()).getDeliveredAt()).isNotNull();

        assertThat(deliveryService.redeliver(row.getId(), consumerId)).isEqualTo(row.getId());
        WxSubscriptionDeliveryEntity redelivered = deliveryMapper.selectById(row.getId());
        assertThat(redelivered.getStatus()).as("误投重投 → PENDING").isEqualTo("PENDING");
        assertThat(redelivered.getAttempts()).as("attempts 续算（不清零）").isEqualTo(1);
        assertThat(redelivered.getDeliveredAt()).as("delivered_at 清空待重投").isNull();
    }

    @Test
    void failWebhookUsesSubscriptionBackoffBaseAndCapOnRealRows() throws SQLException {
        long consumerId = consumer("t10it_base", null, SubscriptionFilter.sha256Hex("t10it-base-secret"));
        long subFast = subscription(consumerId, "t10it_sub_fast", "{\"chats\":[\"" + CHAT_DUP + "\"]}",
                "WEBHOOK", null, 60, 16, 30, 1);   // base=30、max=16

        // base 生效：attempts=1 → min(30×2^0, 3600) = 30s
        WxSubscriptionDeliveryEntity fast = insertDelivery(subFast, consumerId, 990001201L,
                "DISPATCHED", 1, 16, 60, "t10it-fast-l1", OffsetDateTime.now().plusMinutes(10));
        deliveryService.failWebhook(fast.getId(), "t10it-fast-l1", 500, "fast-fail");
        assertThat(deliveryMapper.selectById(fast.getId()).getNextAttemptAt())
                .as("退避基数回订阅表（base=30 档，±5s）")
                .isBetween(OffsetDateTime.now().plusSeconds(25), OffsetDateTime.now().plusSeconds(35));

        // 封顶生效：attempts=8 → min(30×2^7, 3600) = 3600s
        WxSubscriptionDeliveryEntity capped = insertDelivery(subFast, consumerId, 990001202L,
                "DISPATCHED", 8, 16, 60, "t10it-fast-l8", OffsetDateTime.now().plusMinutes(10));
        deliveryService.failWebhook(capped.getId(), "t10it-fast-l8", null, "capped-fail");
        assertThat(deliveryMapper.selectById(capped.getId()).getNextAttemptAt())
                .as("指数退避封顶 3600s（§5.2，±5s）")
                .isBetween(OffsetDateTime.now().plusSeconds(3595), OffsetDateTime.now().plusSeconds(3605));
    }

    // ================================================================ F. sweep 回收两分支

    @Test
    void sweepExpiredSplitsPendingAndDeadOnRealRows() throws SQLException {
        long consumerId = consumer("t10it_sweep", null, SubscriptionFilter.sha256Hex("t10it-sweep-secret"));
        long subId = subscription(consumerId, "t10it_sub_sweep", "{\"chats\":[\"" + CHAT_DUP + "\"]}",
                "WEBHOOK", null, 60, 8, 3600, 1);
        OffsetDateTime expired = OffsetDateTime.now().minusSeconds(60);
        WxSubscriptionDeliveryEntity pending = insertDelivery(subId, consumerId, 990001301L,
                "DISPATCHED", 1, 8, 60, "t10it-sweep-p", expired);
        WxSubscriptionDeliveryEntity dead = insertDelivery(subId, consumerId, 990001302L,
                "DISPATCHED", 8, 8, 60, "t10it-sweep-d", expired);

        deliveryService.sweepExpired();   // 断最终库态：存活调度器的 30s sweep 与本调用产出一致

        WxSubscriptionDeliveryEntity afterP = deliveryMapper.selectById(pending.getId());
        assertThat(afterP.getStatus()).as("额度未尽 → 回 PENDING 重投").isEqualTo("PENDING");
        assertThat(afterP.getLeaseId()).as("回队清租约").isNull();
        assertThat(afterP.getLeaseUntil()).as("回队清到期").isNull();
        WxSubscriptionDeliveryEntity afterD = deliveryMapper.selectById(dead.getId());
        assertThat(afterD.getStatus()).as("额度用尽 → DEAD").isEqualTo("DEAD");
        assertThat(afterD.getLeaseId()).as("DEAD 保留 lease_id").isEqualTo("t10it-sweep-d");
        assertThat(afterD.getError()).as("回收标记").isEqualTo("lease expired");
    }

    // ================================================================ G+H. 补扫窗口缺口锚（P1#1 修后语义）+ @Update-INSERT 实证

    /**
     * P1#1 回归锚：水位推进目标 = <b>窗口内</b> max(id)（修复后语义），不是全局 max(id)。
     * 场景 = §3.2 的三类残余之首（订阅恰在批处理中创建）：订阅先禁用（快照不含 → 钩子不触发），
     * 造「窗内消息 + 窗外更高 id 消息」→ 补扫 → 水位只到窗内 max；下一轮放大窗口缺口补上。
     * 修复前（全局 max）本用例水位断言必红——这正是锚点目的。
     */
    @Test
    void backstopAdvancesCursorToWindowMaxNotGlobalMaxAndClosesGapNextRound() throws SQLException {
        long consumerId = consumer("t10it_gapper", null, null);
        long subId = subscription(consumerId, "t10it_sub_gap",
                "{\"chats\":[\"" + CHAT_GAP + "\"],\"accounts\":[\"" + ACC + "\"]}", "POLL",
                null, 60, 8, 30, 0);   // enabled=0：快照不含 → 钩子不触发（典型补扫残余场景）

        long inWindowId = ingestMessage(CHAT_GAP, "7712004", "gap in window");
        long outWindowId = ingestMessage(CHAT_GAP, "7712005", "gap out of window");
        exec("UPDATE wx_message SET created_at = now() - interval '1 hour' WHERE id = " + outWindowId);
        assertThat(outWindowId).as("窗外消息 id 更高（雪花单调）").isGreaterThan(inWindowId);

        // 补扫只扫 enabled=1 订阅：此刻启用（「订阅恰在批处理中创建」的正式化——钩子快照没赶上它，
        // 内存匹配永远不触发，缺口只能靠补扫补；hook 也不会再被调，消息已落库）
        exec("UPDATE wx_subscription SET enabled = 1 WHERE id = " + subId);

        // 直写水位（绕过 GREATEST 允许回拨——回拨只重扫已投递窗口，ON CONFLICT 零副作用）
        setGlobalCursor(inWindowId - 1);

        int enqueued = backstopService.runBackstop(600);   // H：@Update 承载 INSERT 真库执行实证
        assertThat(enqueued).as("本轮补队行数（存活调度器 60s tick 可能抢先入队同一行 → 允许 0/1）")
                .isIn(0, 1);
        assertThat(deliveriesOfMessage(inWindowId)).as("窗内消息入队（状态断言为准）").hasSize(1);
        assertThat(deliveriesOfMessage(outWindowId))
                .as("窗外消息本轮不入队").isEmpty();
        assertThat(globalCursor()).as("P1#1 锚：水位只推进到窗口内 max(id)，不被窗外全局 max 拖走")
                .isEqualTo(inWindowId);

        int closed = backstopService.runBackstop(7200);   // 放大窗口：缺口下轮补上
        assertThat(closed).as("放大窗口后窗外缺口补队（同上允许存活 tick 抢先）").isIn(0, 1);
        assertThat(deliveriesOfMessage(outWindowId)).hasSize(1);
        assertThat(globalCursor()).as("缺口补齐后水位推进到新窗口内 max").isEqualTo(outWindowId);

        int replay = backstopService.runBackstop(7200);
        assertThat(replay).as("补扫重放 ON CONFLICT 吸收零新行").isZero();
    }

    // ================================================================ J. 补扫 keyword 子串语义（PG 运算符优先级崩溃回归锚）

    /**
     * 回归锚：补扫 SQL 的 keyword 匹配必须是<b>纯子串</b>（{@code %} / {@code _} 不当通配符），
     * 且补扫整条链不得因「库里有任何 enabled 订阅」而崩溃。
     *
     * <p>修复前：{@code m.body LIKE '%' || s.filter->>'keyword' || '%'} 在 PG 里 {@code ->>} 与
     * {@code ||} 同级左结合 → 解析成 {@code (('%' || s.filter) ->> 'keyword') || '%'}，
     * {@code '%'} 被当 JSON 解析 → {@code invalid input syntax for type json} → 只要库里存在
     * enabled 订阅，补扫每轮必炸（兜底保险带整条链死）。修复后
     * {@code position((s.filter->>'keyword') in m.body) > 0} 与内存匹配器
     * {@code SubscriptionFilter.Parsed.matches} 的 {@code body.contains(keyword)} 逐字节等价
     * （大小写敏感纯子串），且不再把 keyword 里的 {@code %} / {@code _} 当通配符——这是与 LIKE
     * 的第二个语义差异（第一个是上面的运算符优先级崩溃）。
     *
     * <p>口径（照 H 用例手法）：订阅建时 enabled=0（内存快照不含 → ingest 钩子不抢先入队，
     * 补扫是唯一入队路径）；<b>消息全部造完之后</b>再启用 → 「哪些消息有投递行」的断言无歧义；
     * 行数断言放宽（存活调度器 60s tick 可能抢先入队同一行），但命中集合断言精确。
     */
    @Test
    void backstopKeywordFilterMatchesSubstringOnlyAndNeverWildcards() throws SQLException {
        long consumerId = consumer("t10it_kw", null, null);

        // A：chats + keyword 两键（覆盖「键之间 AND」）；B：keyword 含 % （通配符守卫）
        long subA = subscription(consumerId, "t10it_sub_kw",
                "{\"chats\":[\"" + CHAT_KW + "\",\"" + CHAT_KW2 + "\"],\"keyword\":\"needle\"}",
                "POLL", null, 60, 8, 30, 0);   // enabled=0：钩子不触发 → 补扫是唯一入队路径
        long subB = subscription(consumerId, "t10it_sub_kw2",
                "{\"chats\":[\"" + CHAT_KW2 + "\"],\"keyword\":\"50%\"}",
                "POLL", null, 60, 8, 30, 0);

        // 五条消息（全部在 enabled=1 之前落库 → 钩子无机会抢先入队）
        long m1 = ingestMessage(CHAT_KW, "7713001", "a needle here");            // chat ✓ + needle ✓ → 命中 A
        long m2 = ingestMessage(CHAT_KW, "7713002", "nothing relevant");        // chat ✓ + needle ✗ → 不命中
        long m3 = ingestMessage(CHAT_KW2, "7713003", "needle 50% off today");   // chat ✓ + needle ✓ + 50% ✓ → 命中 A、B
        long m4 = ingestMessage(CHAT_KW2, "7713004", "500 items");              // 无 needle 且无字面量 50% → 不命中
        long m5 = ingestMessage(CHAT_KW, "7713005", "50% off today");           // 含 50% 但 chat 不在 B 的 chats → 不命中

        // 直写水位到首条消息之前（绕过 GREATEST 允许回拨；回拨只重扫窗口，ON CONFLICT 零副作用）
        setGlobalCursor(m1 - 1);

        // 此刻启用：钩子快照赶不上（消息已落库）→ 缺口只能靠补扫补（§3.2 残余场景正式化）
        exec("UPDATE wx_subscription SET enabled = 1 WHERE id = " + subA);
        exec("UPDATE wx_subscription SET enabled = 1 WHERE id = " + subB);

        int enqueued = backstopService.runBackstop(600);
        assertThat(enqueued).as("补扫一轮跑完未抛异常——修复前本 SQL 必抛 invalid input syntax for type json"
                + "（'%' || s.filter->>'keyword' 被 PG 当 JSON 解析）；行数允许 0"
                + "（存活调度器 60s tick 可能抢先入队同一行）").isGreaterThanOrEqualTo(0);

        // 命中集合必须精确：补扫命中集 ≡ 钩子命中集（§2.2 keyword 纯子串口径）
        assertThat(deliveriesOfMessage(m1)).as("m1（chat 命中 + body 含 needle 纯子串）→ 命中 A 一行")
                .hasSize(1);
        assertThat(deliveriesOfMessage(m1)).extracting(WxSubscriptionDeliveryEntity::getSubscriptionId)
                .as("m1 的投递行归属 A").containsExactly(subA);

        assertThat(deliveriesOfMessage(m2)).as("m2（chat 命中但 body 无 needle）→ 键之间 AND，不命中")
                .isEmpty();

        assertThat(deliveriesOfMessage(m3)).as("m3（chat 命中 + 同时含 needle 与字面量 50%）→ 命中 A、B 各一行")
                .hasSize(2);
        assertThat(deliveriesOfMessage(m3)).extracting(WxSubscriptionDeliveryEntity::getSubscriptionId)
                .as("m3 同时落在 A、B 两颗订阅下").containsExactlyInAnyOrder(subA, subB);

        assertThat(deliveriesOfMessage(m4)).as("m4（body '500 items' 不含字面量 '50%'——"
                + "若 % 被当通配符，LIKE '%50%%' 会误命中 → 通配符守卫点）→ 不命中").isEmpty();

        assertThat(deliveriesOfMessage(m5)).as("m5（body 含 '50%' 但 chat 不在 B 的 chats 里）→ 键之间 AND，不命中")
                .isEmpty();

        assertThat(deliveriesOfMessage(m1).size() + deliveriesOfMessage(m3).size())
                .as("五消息 × 两订阅的最终投递行总数 = 3（m1 一行 + m3 两行；m2/m4/m5 各零行）")
                .isEqualTo(3);
    }

    // ================================================================ I. 配额 whitelist-paths yml 绑定生效

    @Test
    void quotaWhitelistPathsBindingCoversSubscriptionDomain() {
        RateLimitProperties properties = rateLimitProperties.getIfAvailable();
        assertThat(properties).as("framework4j-rate-limit 装配且 enabled=true（application.yml）").isNotNull();
        assertThat(properties.getWhitelistPaths())
                .as("§6 v1.1 三件套之一：/v1/subscriptions/** 放行出框架缺省 120 档"
                        + "（全链路 600/min 限流压测归 T10-9）")
                .contains("/v1/subscriptions/**");
    }

    // ================================================================ 造数与清理

    private long consumer(String name, String pollToken, String secretHash) {
        WxConsumerEntity row = new WxConsumerEntity();
        row.setName(name);
        row.setTokenHash(pollToken == null ? null : SubscriptionFilter.sha256Hex(pollToken));
        row.setSecretHash(secretHash);
        row.setIsDeleted(0);
        consumerMapper.insert(row);
        return row.getId();
    }

    @SuppressWarnings("SameParameterValue")
    private long subscription(long consumerId, String name, String filterJson, String deliveryMode,
                              String webhookUrl, int visibleSeconds, int maxAttempts,
                              int backoffBaseSeconds, int enabled) {
        WxSubscriptionEntity row = new WxSubscriptionEntity();
        row.setConsumerId(consumerId);
        row.setName(name);
        row.setFilter(filterJson);
        row.setFilterHash(SubscriptionFilter.sha256Hex(filterJson));
        row.setDeliveryMode(deliveryMode);
        row.setWebhookUrl(webhookUrl);
        row.setOrderMode("PARALLEL");
        row.setVisibleSeconds(visibleSeconds);
        row.setMaxAttempts(maxAttempts);
        row.setBackoffBaseSeconds(backoffBaseSeconds);
        row.setEnabled(enabled);
        row.setIsDeleted(0);
        subscriptionMapper.insert(row);
        return row.getId();
    }

    private WxSubscriptionDeliveryEntity insertDelivery(long subId, long consumerId, long messageId,
                                                        String status, int attempts, int maxAttempts,
                                                        int leaseSeconds, String leaseId,
                                                        OffsetDateTime leaseUntil) {
        WxSubscriptionDeliveryEntity row = new WxSubscriptionDeliveryEntity();
        row.setSubscriptionId(subId);
        row.setConsumerId(consumerId);
        row.setMessageId(messageId);
        row.setStatus(status);
        row.setAttempts(attempts);
        row.setMaxAttempts(maxAttempts);
        row.setLeaseSeconds(leaseSeconds);
        row.setLeaseId(leaseId);
        row.setLeaseUntil(leaseUntil);
        deliveryMapper.insert(row);
        return row;
    }

    /** 真实 ingest 路径造一条消息（钩子随事务触发）；返回 wx_message.id。 */
    private long ingestMessage(String chat, String serverId, String body) throws SQLException {
        MessagesIngestService.IngestOutcome outcome = ingestOutcome(chat, serverId, body);
        assertThat(outcome.errors()).as("ingest 零错误").isEmpty();
        WxMessageEntity row = messageMapper.selectOne(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getDedupKey, "srv:" + serverId));
        assertThat(row).as("消息落库").isNotNull();
        return row.getId();
    }

    private MessagesIngestService.IngestOutcome ingestOutcome(String chat, String serverId, String body) {
        MessagesReportRequest request = new MessagesReportRequest(ACC, null, List.of(chatItem(chat, serverId, body)));
        return ingestService.ingest(request);
    }

    private MessagesReportRequest.ChatItem chatItem(String chat, String serverId, String body) {
        long localId = serverIdSeq.incrementAndGet();
        return new MessagesReportRequest.ChatItem(chat, null, List.of(new MessagesReportRequest.MessageItem(
                "db/MSG_T10IT.db", localId, serverId, null, 1760001000L * 1000 + localId,
                1760001000L, "10000", "文本", null, "SHARD_NAME2ID", body, null,
                String.format("%064d", localId), Boolean.FALSE, null)));
    }

    /** dispatcher 同款载荷组装（§5.1 顶层 + message 摘要），供 D 用例构造最终 payloadJson。 */
    private String manualPayloadJson(WxSubscriptionDeliveryEntity row, long messageId) {
        try {
            WxMessageEntity message = messageMapper.selectById(messageId);
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("delivery_id", row.getId());
            payload.put("subscription_id", row.getSubscriptionId());
            payload.put("consumer_id", row.getConsumerId());
            payload.put("attempt", row.getAttempts());
            payload.put("timestamp", System.currentTimeMillis() / 1000L);
            Map<String, Object> messageMap = new java.util.LinkedHashMap<>();
            messageMap.put("id", message.getId());
            messageMap.put("dedup_key", message.getDedupKey());
            messageMap.put("chat_username", message.getChatUsername());
            messageMap.put("is_group", message.getIsGroup());
            messageMap.put("sender_username", message.getSenderUsername());
            messageMap.put("type_code", message.getTypeCode());
            messageMap.put("type_name", message.getTypeName());
            messageMap.put("body", message.getBody());
            messageMap.put("create_time_raw", message.getCreateTimeRaw());
            messageMap.put("is_revoked", message.getIsRevoked());
            messageMap.put("attachments", List.of());
            payload.put("message", messageMap);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("载荷组装失败", e);
        }
    }

    /** stub 捕获轮询：ConcurrentLinkedQueue 无定时 poll，2s 窗内 50ms 步进取件。 */
    private CapturedRequest awaitCapture(ConcurrentLinkedQueue<CapturedRequest> queue)
            throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            CapturedRequest item = queue.poll();
            if (item != null) {
                return item;
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** 测试本地独立 HMAC 实现（不调 WebhookDispatcher.sign——独立实现才有交叉验证价值）。 */
    private static String hmacSha256Hex(long timestamp, byte[] body, String secretHashHex) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretHashHex.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, data, 0, prefix.length);
            System.arraycopy(body, 0, data, prefix.length, body.length);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 不可用", e);
        }
    }

    /**
     * 重臂认领：自认领优先；被存活调度器的 200ms tick 抢先则收编其新租约（行已 DISPATCHED，
     * 汇报语义不变）；若 tick 已把行 SSRF 失败打回 PENDING（+3600s）则重臂重试。
     */
    private WxSubscriptionDeliveryEntity claimMine(long deliveryId) throws Exception {
        for (int i = 0; i < 5; i++) {
            exec("UPDATE wx_subscription_delivery SET next_attempt_at = now() WHERE id = " + deliveryId);
            List<WxSubscriptionDeliveryEntity> claimed = deliveryMapper.claimDueWebhook(10, 4);
            WxSubscriptionDeliveryEntity mine = claimed.stream()
                    .filter(r -> r.getId() == deliveryId).findFirst().orElse(null);
            if (mine != null) {
                return mine;
            }
            WxSubscriptionDeliveryEntity current = deliveryMapper.selectById(deliveryId);
            if (current != null && "DISPATCHED".equals(current.getStatus())) {
                return current;   // 存活 tick 抢先认领：收编其租约（attempts 同样 +1，语义一致）
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("认领失败：行始终无法进入 DISPATCHED（deliveryId=" + deliveryId + "）");
    }

    private List<WxSubscriptionDeliveryEntity> deliveriesOfMessage(long messageId) {
        return deliveryMapper.selectList(new LambdaQueryWrapper<WxSubscriptionDeliveryEntity>()
                .eq(WxSubscriptionDeliveryEntity::getMessageId, messageId));
    }

    private List<WxSubscriptionDeliveryEntity> allDeliveriesOfSub(long subId) {
        return deliveryMapper.selectList(new LambdaQueryWrapper<WxSubscriptionDeliveryEntity>()
                .eq(WxSubscriptionDeliveryEntity::getSubscriptionId, subId)
                .orderByAsc(WxSubscriptionDeliveryEntity::getId));
    }

    private long globalCursor() throws SQLException {
        WxSubDeliveryCursorEntity row = cursorMapper.selectOne(new LambdaQueryWrapper<WxSubDeliveryCursorEntity>()
                .eq(WxSubDeliveryCursorEntity::getScope, SubscriptionBackstopService.SCOPE_GLOBAL));
        return row == null || row.getLastMessageId() == null ? 0L : row.getLastMessageId();
    }

    /** 直写水位（绕过 GREATEST 允许回拨）：EXCLUDED 直赋而非 GREATEST。 */
    private void setGlobalCursor(long waterMark) throws SQLException {
        exec("INSERT INTO wx_sub_delivery_cursor (scope, last_message_id) VALUES ('global', " + waterMark + ") "
                + "ON CONFLICT (scope) DO UPDATE SET last_message_id = EXCLUDED.last_message_id, updated_at = now()");
    }

    /** 恢复共享水位到 ≥ 全局 max(id)（礼貌归还：生产补扫从真实前沿继续）。 */
    private void restoreGlobalCursor() throws SQLException {
        exec("INSERT INTO wx_sub_delivery_cursor (scope, last_message_id) "
                + "VALUES ('global', (SELECT COALESCE(max(id), 0) FROM wx_message)) "
                + "ON CONFLICT (scope) DO UPDATE SET last_message_id = "
                + "GREATEST(wx_sub_delivery_cursor.last_message_id, EXCLUDED.last_message_id), updated_at = now()");
    }

    /**
     * 两线程同起跑线的并发执行器（先例 TaskDispatchIntegrationTest.race 同款）：
     * submit 两 worker → ready 门等就位 → go 门同时放行；异常按结果带回由主线程断言。
     */
    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> f1 = pool.submit(wrap(first, ready, go));
            Future<Object> f2 = pool.submit(wrap(second, ready, go));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发就绪门超时");
            }
            go.countDown();
            List<Object> out = new ArrayList<>();
            out.add(f1.get(10, TimeUnit.SECONDS));
            out.add(f2.get(10, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Object> wrap(Callable<?> body, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发起步门超时");
            }
            try {
                return body.call();
            } catch (Exception e) {
                return e;
            }
        };
    }

    /** 只清测试前缀（t10it_）：种子与真实数据永不触碰。 */
    private void purge() throws SQLException {
        // 投递行 → 订阅 → 消费者（依赖序；无外键也按依赖删）
        exec("DELETE FROM wx_subscription_delivery WHERE subscription_id IN "
                + "(SELECT id FROM wx_subscription WHERE consumer_id IN "
                + "(SELECT id FROM wx_consumer WHERE name LIKE '" + CONSUMER_PREFIX + "%'))");
        exec("DELETE FROM wx_subscription WHERE consumer_id IN "
                + "(SELECT id FROM wx_consumer WHERE name LIKE '" + CONSUMER_PREFIX + "%')");
        exec("DELETE FROM wx_consumer WHERE name LIKE '" + CONSUMER_PREFIX + "%'");
        // 消息域按 chat 前缀（先例 MessageIngestIntegrationTest 口径）
        exec("DELETE FROM wx_message_attachment WHERE message_id IN "
                + "(SELECT id FROM wx_message WHERE chat_username LIKE '" + CHAT_PREFIX + "%')");
        exec("DELETE FROM wx_merge_log WHERE kept_id IN "
                + "(SELECT id FROM wx_message WHERE chat_username LIKE '" + CHAT_PREFIX + "%')");
        exec("DELETE FROM wx_message_source_rel WHERE account LIKE '" + ACC + "%'");
        exec("DELETE FROM wx_message WHERE chat_username LIKE '" + CHAT_PREFIX + "%'");
        exec("DELETE FROM wx_sync_cursor WHERE account LIKE '" + ACC + "%'");
        exec("DELETE FROM wx_ingest_batch_log WHERE account LIKE '" + ACC + "%'");
    }

    private void exec(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
