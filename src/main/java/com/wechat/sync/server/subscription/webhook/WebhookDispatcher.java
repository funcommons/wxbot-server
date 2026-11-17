package com.wechat.sync.server.subscription.webhook;

import com.wechat.sync.server.subscription.service.DeliveryService;
import fun.commons.framework4j.web.ApiException;
import com.wechat.sync.server.subscription.service.DeliveryVOs;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * WEBHOOK 推送派发器（SUBSCRIPTION_DESIGN §5，T10-5）：worker 池 + HMAC 签名 + SSRF 前置校验。
 *
 * <p>一轮（{@link #dispatchOnce}）：{@code dispatchDue(batch, perSubInflight)} 认领（§5.3 SQL——
 * delivery_mode='WEBHOOK' 互斥 + 同订阅在飞上限）→ 调度线程<b>只入池</b>（P2#3：校验/组包/汇报
 * 全在 worker 线程——SSRF 的 DNS 解析不许阻塞 200ms 拨轮）；worker 内先过 {@link WebhookUrlValidator}
 * （违规 → 失败汇报走退避，不打 complete）→ 构造 §5.1 请求（头三件 + body=<b>最终 payloadJson
 * 原文</b>，禁止重序列化）→ 发送。成功判定 = HTTP 2xx（响应体不解析，§5.2）。
 *
 * <p><b>签名密钥口径</b>（§5.1 v1.1 裁决）：{@code HMAC-SHA256(key=sha256hex(secret),
 * data="<ts>.<payloadJson 原始字节>")}——服务端只有库存 hash、明文永不落库，故直接以
 * {@code sha256hex(secret)} 作 HMAC key（secret 本为服务端生成高熵串，安全性不降）。
 * 头 {@code X-WxSync-Signature: sha256=<hex>}，{@code X-WxSync-Timestamp}=Unix 秒，
 * {@code X-WxSync-Delivery}=deliveryId（消费方幂等键）。
 *
 * <p>失败落库：非 2xx → {@code responseCode=HTTP 状态}；连接失败/超时 → {@code responseCode=null}
 * + error 摘要 ≤512（§5.2）。池满/关停拒绝 → 任务<b>原样留在租约期</b>不汇报（本方不动任务，
 * 租约到期 sweep 回收——与任务域「不 local fail」同一哲学）。TOCTOU 残余风险见校验器 javadoc。
 *
 * <p>测试缝隙：{@link #send} 包级可覆写（单测不碰真网络；T10-8 IT 才起真 stub server）。
 */
@Slf4j
@Component
public class WebhookDispatcher {

    /** 请求级硬超时（§5.2：connect 3s / read 10s；在飞租约 60s 覆盖最慢请求 + 提交窗口）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 失败 error 摘要上限（§5.2：超时 null + error ≤512）。 */
    private static final int ERROR_MAX_LEN = 512;

    private final DeliveryService deliveryService;
    private final int batchLimit;
    private final int perSubInflight;
    private final ExecutorService workers;
    private final HttpClient http;

    public WebhookDispatcher(DeliveryService deliveryService,
                             @Value("${wx-sync.sub-webhook-workers:8}") int workers,
                             @Value("${wx-sync.sub-dispatch-batch:8}") int batchLimit,
                             @Value("${wx-sync.sub-per-sub-inflight:4}") int perSubInflight) {
        this.deliveryService = deliveryService;
        this.batchLimit = batchLimit;
        this.perSubInflight = perSubInflight;
        this.workers = Executors.newFixedThreadPool(Math.max(1, workers));
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)      // §5.4 第⑤条
                .build();
    }

    /** 派发一轮：认领 + 组装 + SSRF 校验 + 异步投递；异常捕获不外泄（调度轮 catch-all 兜底）。 */
    public int dispatchOnce() {
        List<DeliveryVOs.WebhookDeliveryTask> tasks;
        try {
            tasks = deliveryService.dispatchDue(batchLimit, perSubInflight);
        } catch (Exception e) {
            log.warn("[WebhookDispatch] 认领失败（下一轮重试）: {}", e.getMessage(), e);
            return 0;
        }
        int submitted = 0;
        for (DeliveryVOs.WebhookDeliveryTask task : tasks) {
            try {
                submit(task);
                submitted++;
            } catch (RejectedExecutionException e) {
                // 池满/关停：任务原样留租约期，本方不动（租约到期 sweep 回收重派）
                log.warn("[WebhookDispatch] worker 池拒绝投递 {}，留待租约回收重派", task.deliveryId());
            } catch (Exception e) {
                log.warn("[WebhookDispatch] 投递 {} 提交失败: {}", task.deliveryId(), e.getMessage());
            }
        }
        return submitted;
    }

    /** 提交（P2#3：调度线程只认领 + 入池——SSRF 校验含 DNS 解析、组包、汇报全在 worker 线程，见 deliver）。 */
    private void submit(DeliveryVOs.WebhookDeliveryTask task) {
        CompletableFuture.runAsync(() -> deliver(task), workers);
    }

    /**
     * 单投递（worker 池内执行）：SSRF 校验 → secret 检查 → 组包 → 发送 → 汇报。
     *
     * <p>P2#3：校验放在 worker 而非 dispatchOnce 循环——{@link WebhookUrlValidator} 的
     * {@code checkResolvedHosts} 走 {@code getAllByName}，慢/无解析器下单域名可达秒级；
     * 200ms 拨轮线程若同步跑校验，批 8 可停摆数十秒。SSRF 拒 → failWebhook(null,
     * "ssrf blocked: …") 语义不变，只是执行线程变了。
     *
     * <p>2xx → complete；非 2xx → fail(HTTP 状态)；超时/传输失败 → fail(null, error)。
     * P2#2：汇报撞 {@link ApiException}（10606 租约易主等业务码）＝汇报权已易主——log 后
     * <b>放弃汇报</b>，不再二次 failWebhook（旧实现把业务码误归 "transport:" 再汇报一次）。
     */
    void deliver(DeliveryVOs.WebhookDeliveryTask task) {
        try {
            try {
                WebhookUrlValidator.validate(task.url());
            } catch (RuntimeException e) {
                String reason = String.valueOf(e.getMessage());
                log.warn("[WebhookDispatch] 投递 {} SSRF 拦截（{}）", task.deliveryId(), reason);
                deliveryService.failWebhook(task.deliveryId(), task.leaseId(), null,
                        clip("ssrf blocked: " + reason));
                return;
            }
            if (task.secretHash() == null || task.secretHash().isBlank()) {
                log.warn("[WebhookDispatch] 投递 {} 消费者 {} 未配置 WEBHOOK 密钥，退避重排",
                        task.deliveryId(), task.consumerId());
                deliveryService.failWebhook(task.deliveryId(), task.leaseId(), null,
                        clip("webhook secret 未配置（消费者缺 HMAC 凭据）"));
                return;
            }
            long timestamp = System.currentTimeMillis() / 1000L;
            byte[] body = task.payloadJson().getBytes(StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(task.url()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-WxSync-Timestamp", Long.toString(timestamp))
                    .header("X-WxSync-Delivery", Long.toString(task.deliveryId()))
                    .header("X-WxSync-Signature", sign(timestamp, body, task.secretHash()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = send(request);
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                deliveryService.completeWebhook(task.deliveryId(), task.leaseId(), code);
                return;
            }
            deliveryService.failWebhook(task.deliveryId(), task.leaseId(), code,
                    clip("non-2xx: HTTP " + code));
        } catch (java.net.http.HttpTimeoutException e) {
            reportFailure(task, null, clip("timeout: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reportFailure(task, null, clip("interrupted"));
        } catch (java.io.IOException e) {
            reportFailure(task, null, clip("transport: " + e.getMessage()));
        } catch (ApiException e) {
            log.warn("[WebhookDispatch] 投递 {} 汇报被业务码拒绝（bizCode={}），视为租约易主放弃: {}",
                    task.deliveryId(), e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            // 组包/缝隙内非受检异常（URI 变体等）：按传输失败退避；汇报自身再抛由 reportFailure 内吞
            reportFailure(task, null, clip("runtime: " + e.getMessage()));
        }
    }

    /** 失败汇报（deliver 的 catch 出口统一走此；汇报自身异常只 log 不再上抛——下一轮租约回收兜底）。 */
    private void reportFailure(DeliveryVOs.WebhookDeliveryTask task, Integer code, String error) {
        try {
            deliveryService.failWebhook(task.deliveryId(), task.leaseId(), code, error);
        } catch (RuntimeException e) {
            log.warn("[WebhookDispatch] 投递 {} 失败汇报异常（租约回收兜底）: {}",
                    task.deliveryId(), e.getMessage());
        }
    }

    /**
     * HTTP 发送缝隙：包级可覆写——单测以同包子类覆写本方法注伪响应（不碰真网络），
     * T10-8 IT 起真 stub server 走默认实现。
     */
    HttpResponse<byte[]> send(HttpRequest request) throws java.io.IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * HMAC-SHA256 纯函数（§5.1 v1.1 裁决口径）：{@code HMAC(key=sha256hex(secret) 的 ASCII 字节,
     * data=<ts>.<body>)}，返回 {@code "sha256=<hex>"} 完整头值。已知向量可测。
     */
    public static String sign(long timestamp, byte[] body, String secretHashHex) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretHashHex.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, data, 0, prefix.length);
            System.arraycopy(body, 0, data, prefix.length, body.length);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用（JVM 缺算法）", e);
        }
    }

    /** error 摘要截断 ≤512（§5.2 落库口径）。 */
    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_MAX_LEN ? text : text.substring(0, ERROR_MAX_LEN);
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
