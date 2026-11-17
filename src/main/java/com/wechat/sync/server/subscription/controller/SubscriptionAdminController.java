package com.wechat.sync.server.subscription.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.common.WxErrorCode;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.ConsumerCreatedVO;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.ConsumerVO;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.DeliveryRowVO;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.SubscriptionVO;
import com.wechat.sync.server.subscription.entity.WxSubscriptionDeliveryEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import com.wechat.sync.server.subscription.service.ConsumerService;
import com.wechat.sync.server.subscription.service.DeliveryService;
import com.wechat.sync.server.subscription.service.SubscriptionService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 订阅域管理接口（SUBSCRIPTION_DESIGN §2/§4/§7，角色 admin）：消费者与订阅 CRUD、
 * 投递列表（死信视图）、DLQ 救济（requeue-dead / redeliver）。
 *
 * <p>明文凭据只在 {@code POST /v1/consumers} 响应出现一次（{@code ConsumerCreatedVO}），
 * 列表/详情 VO 无任何凭据明文或哈希。投递行只读列表直注 mapper（TaskAdminController 先例：
 * controller→mapper 只读查询可接受）；requeue-dead / redeliver 是写操作，走 DeliveryService
 * ——admin 场景把行自带的 consumer_id 回填给 service 做归属校验（admin 本身可信，校验保留
 * 以复用同一状态机路径）。返回 id 直接回 Long（框架全局 Long→String）。
 */
@RestController
@RequiredArgsConstructor
@RequiresToken(value = TokenService.ADMIN_TYPE, roles = TokenService.ROLE_ADMIN)
public class SubscriptionAdminController {

    /** 投递列表 status 过滤闭集（§4 状态机；管理员手输错误 → 10102）。 */
    private static final Set<String> STATUS_FILTERS = Set.of(
            "PENDING", "DISPATCHED", "DELIVERED", "DEAD");

    private final ConsumerService consumerService;
    private final SubscriptionService subscriptionService;
    private final DeliveryService deliveryService;
    private final WxSubscriptionMapper subscriptionMapper;
    private final WxSubscriptionDeliveryMapper deliveryMapper;

    // ------------------------------------------------------------ 消费者（§2.1）

    /** 建消费者：响应只此一次回明文 poll_token/webhook_secret（哈希入库）；重名 10401。 */
    @PostMapping("/v1/consumers")
    public ApiResponse<ConsumerCreatedVO> createConsumer(
            @Valid @RequestBody ConsumerCreateRequest request) {
        return ApiResponse.success(consumerService.create(request.name(), request.enablePoll(),
                request.enableWebhook(), request.webhookUrl(), request.remark()));
    }

    /** 消费者列表（Offset 分页；无凭据回显）。 */
    @GetMapping("/v1/consumers")
    public ApiResponse<PageVO<ConsumerVO>> listConsumers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(name = "page_size", defaultValue = "20") long pageSize) {
        return ApiResponse.success(consumerService.list(page, pageSize));
    }

    /** 软删消费者（其订阅/投递行不动——authenticateConsumer 只认未删行，软删即停止鉴权通过）。 */
    @DeleteMapping("/v1/consumers/{id}")
    public ApiResponse<Void> deleteConsumer(@PathVariable("id") String id) {
        consumerService.delete(SubPathIds.parse(id, "consumer_id"));
        return ApiResponse.success();
    }

    // ------------------------------------------------------------ 订阅（§2.2）

    /** 创建订阅：同 consumer 同条件重复 POST 不报错，返回既有行 + already_exists=true（§2.2 幂等闸门）。 */
    @PostMapping("/v1/subscriptions")
    public ApiResponse<SubscriptionCreateVO> createSubscription(
            @Valid @RequestBody SubscriptionCreateRequest request) {
        SubscriptionService.CreateResult result = subscriptionService.create(
                new SubscriptionService.CreateCommand(SubPathIds.parse(request.consumerId(), "consumer_id"),
                        request.name(), request.filter(), request.deliveryMode(), request.webhookUrl(),
                        request.orderMode(), request.visibleSeconds(), request.maxAttempts(),
                        request.backoffBaseSeconds()));
        return ApiResponse.success(new SubscriptionCreateVO(
                SubscriptionVO.from(result.subscription()), result.alreadyExists()));
    }

    /** 部分更新（至少一键 → 10101）；filter 不可变（UpdateCommand 类型上不含，传了被忽略）。 */
    @PutMapping("/v1/subscriptions/{id}")
    public ApiResponse<SubscriptionVO> updateSubscription(
            @PathVariable("id") String id, @RequestBody SubscriptionUpdateRequest request) {
        if (request == null) {
            request = new SubscriptionUpdateRequest(null, null, null, null, null, null);
        }
        return ApiResponse.success(SubscriptionVO.from(subscriptionService.update(
                SubPathIds.parse(id, "subscription_id"),
                new SubscriptionService.UpdateCommand(request.enabled(), request.webhookUrl(),
                        request.orderMode(), request.visibleSeconds(), request.maxAttempts(),
                        request.backoffBaseSeconds()))));
    }

    /** 软删订阅（is_deleted=1 + enabled=0；投递行保留）。 */
    @DeleteMapping("/v1/subscriptions/{id}")
    public ApiResponse<Void> deleteSubscription(@PathVariable("id") String id) {
        subscriptionService.delete(SubPathIds.parse(id, "subscription_id"));
        return ApiResponse.success();
    }

    /** 订阅详情；不存在/已软删 → 10605。 */
    @GetMapping("/v1/subscriptions/{id}")
    public ApiResponse<SubscriptionVO> getSubscription(@PathVariable("id") String id) {
        return ApiResponse.success(SubscriptionVO.from(
                subscriptionService.get(SubPathIds.parse(id, "subscription_id"))));
    }

    /** 订阅列表（Offset 分页；consumer_id / enabled 可选过滤）。 */
    @GetMapping("/v1/subscriptions")
    public ApiResponse<PageVO<SubscriptionVO>> listSubscriptions(
            @RequestParam(name = "consumer_id", required = false) String consumerId,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(name = "page_size", defaultValue = "20") long pageSize) {
        PageVO<WxSubscriptionEntity> result = subscriptionService.list(
                SubPathIds.parseOptional(consumerId, "consumer_id"), page, pageSize);
        return ApiResponse.success(PageVO.map(result.list(), result.total(), result.page(),
                result.pageSize(), SubscriptionVO::from));
    }

    // ------------------------------------------------------------ 投递（§4 视图与救济）

    /**
     * 投递列表（§4 死信管理视图 / §7 可观测）：status 过滤闭集 PENDING/DISPATCHED/DELIVERED/DEAD，
     * 其它值 10102；Offset 分页按 id 降序（新投递在前）。
     */
    @GetMapping("/v1/subscriptions/{id}/deliveries")
    public ApiResponse<PageVO<DeliveryRowVO>> listDeliveries(
            @PathVariable("id") String id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(name = "page_size", defaultValue = "20") long pageSize) {
        long subscriptionId = SubPathIds.parse(id, "subscription_id");
        String statusFilter = SubPathIds.requireStatus(status, STATUS_FILTERS);
        PageVO.PageParams pp = PageVO.clamp(page, pageSize);
        LambdaQueryWrapper<WxSubscriptionDeliveryEntity> wrapper =
                new LambdaQueryWrapper<WxSubscriptionDeliveryEntity>()
                        .eq(WxSubscriptionDeliveryEntity::getSubscriptionId, subscriptionId)
                        .orderByDesc(WxSubscriptionDeliveryEntity::getId);
        if (statusFilter != null) {
            wrapper.eq(WxSubscriptionDeliveryEntity::getStatus, statusFilter);
        }
        Page<WxSubscriptionDeliveryEntity> result =
                deliveryMapper.selectPage(new Page<>(pp.page(), pp.pageSize()), wrapper);
        return ApiResponse.success(PageVO.map(result.getRecords(), result.getTotal(),
                pp.page(), pp.pageSize(), DeliveryRowVO::from));
    }

    /** 死信重排（§4 DLQ 救济）：整订阅 DEAD → PENDING（attempts=0、清凭据、立即到期）；回重排行数。 */
    @PostMapping("/v1/subscriptions/{id}/requeue-dead")
    public ApiResponse<Integer> requeueDead(@PathVariable("id") String id) {
        long subscriptionId = SubPathIds.parse(id, "subscription_id");
        long consumerId = requireSubscriptionConsumerId(subscriptionId);
        return ApiResponse.success(deliveryService.requeueDead(subscriptionId, consumerId));
    }

    /** 误投重投（§4 v1.1 救济）：仅 DELIVERED 可 redeliver（其它 10402）；attempts 续算。 */
    @PostMapping("/v1/subscriptions/deliveries/{delivery_id}/redeliver")
    public ApiResponse<Long> redeliver(@PathVariable("delivery_id") String deliveryId) {
        long id = SubPathIds.parse(deliveryId, "delivery_id");
        long consumerId = deliveryService.getDelivery(id).getConsumerId();
        return ApiResponse.success(deliveryService.redeliver(id, consumerId));
    }

    // ------------------------------------------------------------ 内部

    /** 行自带 consumer_id 回填（requeue-dead 的归属校验用；订阅不存在 → 10605）。 */
    private long requireSubscriptionConsumerId(long subscriptionId) {
        WxSubscriptionEntity sub = subscriptionMapper.selectById(subscriptionId);
        if (sub == null) {
            throw WxErrorCode.subscriptionNotFound("订阅", subscriptionId);
        }
        return sub.getConsumerId();
    }

    /** POST /v1/consumers 入参：enablePoll/enableWebhook 声明要哪几把凭据（缺省各 true）。 */
    public record ConsumerCreateRequest(String name, Boolean enablePoll, Boolean enableWebhook,
                                        String webhookUrl, String remark) {
    }

    /** POST /v1/subscriptions 入参（filter 为任意 JSON 对象，校验在 SubscriptionFilter.normalize）。 */
    public record SubscriptionCreateRequest(String consumerId, String name,
                                            java.util.Map<String, Object> filter,
                                            String deliveryMode, String webhookUrl,
                                            String orderMode, Integer visibleSeconds,
                                            Integer maxAttempts, Integer backoffBaseSeconds) {
    }

    /** PUT /v1/subscriptions/{id} 入参：null=不改；刻意不含 filter（订阅条件不可变，§2.2）。 */
    public record SubscriptionUpdateRequest(Integer enabled, String webhookUrl, String orderMode,
                                            Integer visibleSeconds, Integer maxAttempts,
                                            Integer backoffBaseSeconds) {
    }

    /** create 响应：already_exists=true 时 subscription 为既有行（重复订阅幂等，不报错）。 */
    public record SubscriptionCreateVO(SubscriptionVO subscription, boolean alreadyExists) {
    }
}
