package com.wechat.sync.server.subscription.webhook;

import com.wechat.sync.server.subscription.service.DeliveryService;
import com.wechat.sync.server.subscription.service.DeliveryVOs.WebhookDeliveryTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * WebhookDispatcher 单测（SUBSCRIPTION_DESIGN §5.1/§5.2/§5.4 + §12 裁决表）。零真网络：HTTP 走
 * 落盘缝隙 {@code HttpResponse<byte[]> send(HttpRequest)}（包级可覆写——本测试与被测类同包），
 * 投递经 worker 池异步执行，验证用 Mockito {@code timeout} + CountDownLatch 双保险。
 *
 * <p><b>落盘 API 对齐记录</b>：ctor = {@code (DeliveryService, workers, batchLimit, perSubInflight)}
 * 四参（<b>无 consumerMapper</b>——签名材料 {@code secretHash} 由 {@code WebhookDeliveryTask}
 * 第 7 组件自带，dispatchDue 组装点已传）；入口 {@code dispatchOnce()} 自认领；成功判定 = 严格 2xx。
 *
 * <p><b>body 断言取舍（javadoc 契约）</b>：{@link HttpRequest} 不暴露 body 字节——原始体逐字节
 * 透传在本层以两条间接锚收口：① 签名 = {@code HMAC(key=sha256hex(secret), ts + "." + BODY 原始字节)}
 * （dispatcher 若重序列化，签名必与体不一致——消费方校验立刻失效，因此签名匹配 ⇒ 签名材料即原始体）；
 * ② {@code bodyPublisher().contentLength()} 与 {@code BODY} 的 UTF-8 字节数相等（重序列化几乎必然
 * 变长）。完整字节级 fidelity 归 T10-8 IT（真 stub server 读真实 body）。
 *
 * <p>签名口径（§5.1 + §12 裁决）：{@code X-WxSync-Signature: sha256=<hex>}，
 * hex = {@code HMAC-SHA256(key=sha256hex(secret), msg=ts + "." + raw_body)}——测试用
 * MessageDigest/Mac <b>独立重算</b>期望值（防实现照抄测试），经头断言。
 */
@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    private static final long DELIVERY_ID = 9007199254740994L;
    private static final long CONSUMER_ID = 9007199254740001L;
    private static final String LEASE = "lease-disp-001";
    private static final String SECRET = "whsec_t10_unit_secret";
    private static final String BODY = "{\"delivery_id\":112,\"msg\":\"中文正文\",\"n\":1}";
    private static final String HOOK_URL = "http://93.184.216.34/hook";     // 公网 IP 字面量（零 DNS）

    @Mock
    private DeliveryService deliveryService;

    /** 缝隙捕获物：目标 URI + 请求头 + body 长度（HttpRequest 不暴露 body 字节——见类 javadoc 取舍）。 */
    private record Captured(URI uri, HttpHeaders headers, long bodyLength) {
    }

    private final List<Captured> sent = new CopyOnWriteArrayList<>();
    private final List<URI> attempted = new CopyOnWriteArrayList<>();
    private CountDownLatch sendLatch = new CountDownLatch(0);
    private int nextStatus = 200;
    private IOException transportFailure;

    // ------------------------------------------------------------------ 【对齐缝】落盘缝隙：send(HttpRequest)

    private WebhookDispatcher dispatcher(int expectedSends) {
        sendLatch = new CountDownLatch(expectedSends);
        return new WebhookDispatcher(deliveryService, 2, 8, 4) {
            @Override
            HttpResponse<byte[]> send(HttpRequest request) throws IOException {
                attempted.add(request.uri());
                try {
                    if (transportFailure != null) {
                        throw transportFailure;
                    }
                    sent.add(new Captured(request.uri(), request.headers(),
                            request.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(-1L)));
                    return new FakeResponse(nextStatus);
                } finally {
                    sendLatch.countDown();                   // 写完列表才放行（latch/COW 读写建立 happens-before）
                }
            }
        };
    }

    /** 等异步投递把 N 个请求送达缝隙（CountDownLatch，2s 上限——超时算失败）。 */
    private void awaitSends() throws InterruptedException {
        assertThat(sendLatch.await(2, TimeUnit.SECONDS))
                .as("异步投递应在 2s 内到达缝隙（worker 池行为归 T10-8 IT）").isTrue();
    }

    private WebhookDeliveryTask task() {
        return new WebhookDeliveryTask(DELIVERY_ID, LEASE, 1, CONSUMER_ID, HOOK_URL, BODY,
                sha256Hex(SECRET));
    }

    private void claimReturns(WebhookDeliveryTask... tasks) {
        given(deliveryService.dispatchDue(anyInt(), anyInt())).willReturn(List.of(tasks));
    }

    // ------------------------------------------------------------------ C. 结果映射（2xx / 非 2xx / 传输失败）

    @Test
    void twoHundredCompletesWithActualResponseCode() throws Exception {
        claimReturns(task());
        nextStatus = 200;
        WebhookDispatcher dispatcher = dispatcher(1);

        dispatcher.dispatchOnce();
        awaitSends();

        verify(deliveryService, timeout(2000)).completeWebhook(DELIVERY_ID, LEASE, 200);
        verify(deliveryService, never()).failWebhook(anyLong(), anyString(), any(), anyString());
        assertThat(sent).hasSize(1);
    }

    @Test
    void otherTwoAndThreeXxAreFailuresNotCompletions() throws Exception {
        // 成功判定 = 严格 2xx（§5.2）：302 重定向不跟随也不算成功（followRedirects NEVER）
        claimReturns(task());
        nextStatus = 302;
        WebhookDispatcher dispatcher = dispatcher(1);

        dispatcher.dispatchOnce();
        awaitSends();

        verify(deliveryService, timeout(2000)).failWebhook(eq(DELIVERY_ID), eq(LEASE), eq(302), anyString());
        verify(deliveryService, never()).completeWebhook(anyLong(), anyString(), any());
    }

    @Test
    void fiveHundredFailsWithResponseCode() throws Exception {
        claimReturns(task());
        nextStatus = 500;
        WebhookDispatcher dispatcher = dispatcher(1);

        dispatcher.dispatchOnce();
        awaitSends();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(deliveryService, timeout(2000)).failWebhook(eq(DELIVERY_ID), eq(LEASE), eq(500), error.capture());
        assertThat(error.getValue()).as("失败落 error 摘要（§5.2）").isNotBlank();
    }

    @Test
    void transportFailureFailsWithNullCodeAndBoundedError() throws Exception {
        claimReturns(task());
        transportFailure = new IOException("connect timed out");
        WebhookDispatcher dispatcher = dispatcher(1);

        dispatcher.dispatchOnce();
        awaitSends();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(deliveryService, timeout(2000)).failWebhook(eq(DELIVERY_ID), eq(LEASE), isNull(), error.capture());
        assertThat(error.getValue()).as("传输失败 response_code=NULL + error 非空（§5.2）").isNotBlank();
        assertThat(error.getValue().length()).as("error ≤512 字符").isLessThanOrEqualTo(512);
        assertThat(sent).as("发送失败不入响应捕获（attempted 只记录达）").isEmpty();
        assertThat(attempted).as("缝隙确实被尝试过一次").hasSize(1);
    }

    @Test
    void ssrfRejectedUrlNeverSendsAndFailsWithoutCode() throws Exception {
        // 私网字面量：validator 静态直调（不 mock），dispatcher 必须先校验后发送；
        // P2#3 后校验在 worker 线程执行（不阻塞拨轮）——fail 断言用 timeout 等异步完成
        WebhookDeliveryTask evil = new WebhookDeliveryTask(DELIVERY_ID, LEASE, 1, CONSUMER_ID,
                "http://127.0.0.1/hook", BODY, sha256Hex(SECRET));
        claimReturns(evil);
        WebhookDispatcher dispatcher = dispatcher(0);

        dispatcher.dispatchOnce();

        verify(deliveryService, timeout(2000)).failWebhook(eq(DELIVERY_ID), eq(LEASE), isNull(),
                contains("ssrf"));                                  // 落盘文案前缀 "ssrf blocked: "
        verify(deliveryService, never()).completeWebhook(anyLong(), anyString(), any());
        assertThat(attempted).as("SSRF 拒 → 零 HTTP 发送（§5.4）").isEmpty();
    }

    @Test
    void nullSecretHashFailsWithBackoffNotDeadLetter() throws Exception {
        // 消费者认领后软删/缺凭据 → dispatchDue 组装 secretHash=null → dispatcher 按配置错退避重排；
        // P2#3 后 secret 检查同样在 worker 线程——timeout 等异步完成
        WebhookDeliveryTask noSecret = new WebhookDeliveryTask(DELIVERY_ID, LEASE, 1, CONSUMER_ID,
                HOOK_URL, BODY, null);
        claimReturns(noSecret);
        WebhookDispatcher dispatcher = dispatcher(0);

        dispatcher.dispatchOnce();

        verify(deliveryService, timeout(2000)).failWebhook(eq(DELIVERY_ID), eq(LEASE), isNull(),
                contains("secret"));                                // 落盘文案 "webhook secret 未配置…"
        verify(deliveryService, never()).completeWebhook(anyLong(), anyString(), any());
        assertThat(attempted).as("无凭据零发送").isEmpty();
    }

    // ------------------------------------------------------------------ C. 空认领与批量

    @Test
    void emptyClaimSendsAndReportsNothing() {
        given(deliveryService.dispatchDue(anyInt(), anyInt())).willReturn(List.of());
        WebhookDispatcher dispatcher = dispatcher(0);

        dispatcher.dispatchOnce();

        assertThat(attempted).isEmpty();
        verify(deliveryService, never()).completeWebhook(anyLong(), anyString(), any());
        verify(deliveryService, never()).failWebhook(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void claimArgsPassThroughConstructorConfig() {
        given(deliveryService.dispatchDue(anyInt(), anyInt())).willReturn(List.of());
        WebhookDispatcher dispatcher = dispatcher(0);

        dispatcher.dispatchOnce();

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> inflight = ArgumentCaptor.forClass(Integer.class);
        verify(deliveryService).dispatchDue(limit.capture(), inflight.capture());
        assertThat(limit.getValue()).as("dispatch 批量 = ctor 注入的 sub-dispatch-batch").isEqualTo(8);
        assertThat(inflight.getValue()).as("每订阅在飞上限 = ctor 注入的 sub-per-sub-inflight（§5.3/§8）").isEqualTo(4);
    }

    @Test
    void multipleTasksEachGetSignedSendAndTheirOwnReport() throws Exception {
        WebhookDeliveryTask other = new WebhookDeliveryTask(9007199254740995L, "lease-disp-002",
                2, CONSUMER_ID, HOOK_URL, "{\"delivery_id\":113}", sha256Hex(SECRET));
        claimReturns(task(), other);
        nextStatus = 201;
        WebhookDispatcher dispatcher = dispatcher(2);

        dispatcher.dispatchOnce();
        awaitSends();

        assertThat(attempted).as("两任务都经缝隙发送（worker 池行为归 T10-8 IT）").hasSize(2);
        verify(deliveryService, timeout(2000).times(1)).completeWebhook(DELIVERY_ID, LEASE, 201);
        verify(deliveryService, timeout(2000).times(1)).completeWebhook(9007199254740995L, "lease-disp-002", 201);
    }

    // ------------------------------------------------------------------ B. 头三件 + 签名 + 原始体（§5.1）

    @Test
    void carriesAllThreeHeadersWithContractSignature() throws Exception {
        claimReturns(task());
        WebhookDispatcher dispatcher = dispatcher(1);

        dispatcher.dispatchOnce();
        awaitSends();

        HttpHeaders headers = sent.get(0).headers();
        // ① X-WxSync-Delivery = 投递 ID（消费方幂等键；HttpHeaders 取值大小写不敏感）
        assertThat(header(headers, "X-WxSync-Delivery"))
                .as("投递头 = deliveryId（Long→String 出参口径）").isEqualTo(String.valueOf(DELIVERY_ID));
        // ② X-WxSync-Timestamp = Unix 秒（±5s 容差）
        long ts = Long.parseLong(header(headers, "X-WxSync-Timestamp"));
        assertThat(Math.abs(ts - Instant.now().getEpochSecond()))
                .as("时间戳为当前 Unix 秒").isLessThanOrEqualTo(5);
        // ③ X-WxSync-Signature = sha256=<HMAC(key=sha256hex(secret), ts+"."+raw_body)>，独立重算
        String expected = "sha256=" + hmacSha256Hex(sha256Hex(SECRET), ts + "." + BODY);
        assertThat(header(headers, "X-WxSync-Signature"))
                .as("§5.1 签名 + §12 裁决：key=库存 sha256hex(secret)，msg=ts+\".\"+raw_body")
                .isEqualTo(expected);
        assertThat(header(headers, "Content-Type")).as("JSON 体").contains("application/json");
    }

    @Test
    void bodyIsRawPayloadJsonLengthNotReserialized() throws Exception {
        // HttpRequest 不暴露 body 字节——逐字节透传以「签名匹配 + 长度相等」双锚收口（类 javadoc 取舍），
        // 字节级 fidelity 归 T10-8 IT 真 stub server
        claimReturns(task());
        WebhookDispatcher dispatcher = dispatcher(1);

        dispatcher.dispatchOnce();
        awaitSends();

        Captured captured = sent.get(0);
        assertThat(captured.uri().toString()).as("目标 URL 原样").isEqualTo(HOOK_URL);
        assertThat(captured.bodyLength())
                .as("bodyPublisher 长度 = payloadJson UTF-8 字节数（重序列化几乎必然变长）")
                .isEqualTo(BODY.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void signatureCoversExactlyTsDotRawBody() throws Exception {
        // 交叉验证：签名对「头里的 ts + 原始 BODY」成立，对任何重序列化变体不成立
        claimReturns(task());
        WebhookDispatcher dispatcher = dispatcher(1);

        dispatcher.dispatchOnce();
        awaitSends();

        String ts = header(sent.get(0).headers(), "X-WxSync-Timestamp");
        String wrongOrder = "{\"n\":1,\"msg\":\"中文正文\",\"delivery_id\":112}";
        assertThat(header(sent.get(0).headers(), "X-WxSync-Signature"))
                .isEqualTo("sha256=" + hmacSha256Hex(sha256Hex(SECRET), ts + "." + BODY))
                .isNotEqualTo("sha256=" + hmacSha256Hex(sha256Hex(SECRET), ts + "." + wrongOrder));
    }

    // ------------------------------------------------------------------ 独立密码学助手（双保险，不 import 业务实现）

    private static String header(HttpHeaders headers, String name) {
        Optional<String> value = headers.firstValue(name);
        assertThat(value).as("缺头：" + name + "（§5.1 头三件必齐；HttpHeaders 查找大小写不敏感）").isPresent();
        return value.get();
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 最小 HttpResponse 桩（接口无公开实现；dispatcher 只读 statusCode）。 */
    private static final class FakeResponse implements HttpResponse<byte[]> {
        private final int status;

        FakeResponse(int status) {
            this.status = status;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public byte[] body() {
            return new byte[0];
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return null;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
