package com.wechat.sync.server.subscription.controller;

import com.wechat.sync.server.subscription.entity.WxConsumerEntity;
import com.wechat.sync.server.subscription.service.DeliveryService;
import com.wechat.sync.server.subscription.service.DeliveryVOs.AckItemResult;
import com.wechat.sync.server.subscription.service.DeliveryVOs.DeliveryAckCommand;
import com.wechat.sync.server.subscription.service.DeliveryVOs.PullResultVO;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.framework4j.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅 POLL 消费端（SUBSCRIPTION_DESIGN §6，SQS 式可见性语义）：拉取 / ack / nack。
 *
 * <p><b>鉴权不走 @RequiresToken</b>：consumer 是订阅域独立主体（POLL Bearer 令牌，服务端只存
 * sha256）——控制器内自行解析 {@code Authorization: Bearer} → {@link DeliveryService#authenticateConsumer}
 * （失败 10300，消息点名 {@code consumer_token}）。<b>10300 结构化 field 升级点登记</b>：设计 §6 的
 * {@code error[].field=consumer_token} 粒度受 ApiException 单 message 构造限制，先行落地在消息文本
 * （「（error[].field=consumer_token）」后缀）；如需结构化 error[]，由 GlobalExceptionHandler 侧
 * 加 ApiException→ApiError 映射或本控制器直接组装 fail(code, msg, List&lt;ApiError&gt;)，不阻塞本期。
 *
 * <p>ack/nack 批量是 <b>10700 部分成功</b>口径：逐条处理逐条回结果，全部成功 → code=0；
 * 任一失败 → {@link ApiResponse#partialSuccess}（data 非空，mc-api-spec 例外，MessageReportController 先例）。
 */
@RestController
@RequiredArgsConstructor
public class SubscriptionConsumerController {

    private static final String BEARER_PREFIX = "Bearer ";
    /** §6 示例 max=50 为缺省；硬顶由 DeliveryService.requirePullMax 承担（500）。 */
    private static final int DEFAULT_PULL_MAX = 50;

    private final DeliveryService deliveryService;

    /** 拉取（§6）：PENDING 且到期，按 id 升序认领，带可见性租约；不 ack 的行到期由巡检回队（at-least-once）。 */
    @GetMapping("/v1/subscriptions/{id}/messages")
    public ApiResponse<PullResultVO> pull(
            @PathVariable("id") String id,
            @RequestParam(value = "max", defaultValue = "50") int max,
            HttpServletRequest request) {
        WxConsumerEntity consumer = authenticate(request);
        return ApiResponse.success(
                deliveryService.pull(SubPathIds.parse(id, "subscription_id"), consumer.getId(), max));
    }

    /** ack（§4 终态）：逐行 (delivery_id, lease_id) 对——每行认领时各得独立 UUID 凭据（§12 裁决表）。 */
    @PostMapping("/v1/subscriptions/{id}/ack")
    public ApiResponse<List<AckItemResult>> ack(
            @PathVariable("id") String id,
            @RequestBody AckRequest body,
            HttpServletRequest request) {
        WxConsumerEntity consumer = authenticate(request);
        List<AckItemResult> results = deliveryService.ack(
                SubPathIds.parse(id, "subscription_id"), consumer.getId(), commands(body));
        return envelope(results);
    }

    /** nack（§4）：回队重试——首 nack 立即重试、非首按退避、额度用尽 → DEAD；逐条结果同 ack。 */
    @PostMapping("/v1/subscriptions/{id}/nack")
    public ApiResponse<List<AckItemResult>> nack(
            @PathVariable("id") String id,
            @RequestBody AckRequest body,
            HttpServletRequest request) {
        WxConsumerEntity consumer = authenticate(request);
        List<AckItemResult> results = deliveryService.nack(
                SubPathIds.parse(id, "subscription_id"), consumer.getId(), commands(body));
        return envelope(results);
    }

    // ------------------------------------------------------------ 内部

    /** Bearer 解析 + consumer 鉴权（缺头/格式错/验签不过 → 10300，消息点名 consumer_token）。 */
    private WxConsumerEntity authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw consumerTokenRejected();
        }
        return deliveryService.authenticateConsumer(header.substring(BEARER_PREFIX.length()));
    }

    private ApiException consumerTokenRejected() {
        return new ApiException(fun.commons.framework4j.api.ApiCode.FORBIDDEN,
                "consumer_token 无效或缺失（error[].field=consumer_token）");
    }

    /** 逐条结果 → 信封：全成功 0；任一失败 10700（error[] 列失败项，field=delivery_id）。 */
    private ApiResponse<List<AckItemResult>> envelope(List<AckItemResult> results) {
        List<ApiError> errors = new ArrayList<>();
        for (AckItemResult item : results) {
            if (!item.success()) {
                errors.add(ApiError.of("delivery_id", "DELIVERY_ACK_FAILED",
                        item.error(), String.valueOf(item.deliveryId())));
            }
        }
        return errors.isEmpty() ? ApiResponse.success(results)
                : ApiResponse.partialSuccess(results, errors);
    }

    private List<DeliveryAckCommand> commands(AckRequest body) {
        return body == null || body.items() == null ? List.of() : body.items();
    }

    /** ack/nack 入参：{@code items:[{delivery_id, lease_id}]}（逐行凭据对，§12 裁决表）。 */
    public record AckRequest(List<DeliveryAckCommand> items) {
    }
}
