package com.wechat.sync.server.common;

import fun.commons.framework4j.web.ApiException;

/**
 * 本服务登记的业务自定义错误码（106xx 段，mc-api-spec v1.6 §7.10 / API_DESIGN §2.3）。
 *
 * <p>framework4j 的 {@code ApiCode} 枚举按规范<strong>不预定义 106xx</strong>（业务线自行登记认领），
 * 且 {@code ApiCode.fromCode(10601)} 返回 null，故此处以「int 常量 + {@link ApiException} 工厂」扩展：
 * 5 位 ABCCC 中 A=1、B=06（业务自定义段）、CCC=001/002/003，经
 * {@link ApiException#ApiException(int, String)} 透传给 GlobalExceptionHandler 翻译成信封。
 */
public final class WxErrorCode {

    /** 10601 上报的账号未在服务端注册（客户端先于注册开跑）。 */
    public static final int ACCOUNT_NOT_REGISTERED = 10601;

    /** 10602 上传字节 sha256 校验失败（传输损坏 / 元数据过时）。 */
    public static final int BLOB_CHECKSUM_MISMATCH = 10602;

    /**
     * 10603 任务租约已失效（TASK_DISPATCH §4.2/§6）：租约被回收重派 / 易主 / 任务被管理员取消，
     * 迟到回报与异 lease 重放都落此码——客户端应丢弃本地现场并继续下一任务。
     * 同 lease 的终态重放<b>不</b>用此码（服务层幂等返回既有 result，§4.2 v1.1）。
     */
    public static final int TASK_LEASE_LOST = 10603;

    /**
     * 10604 订阅 filter 非法（SUBSCRIPTION_DESIGN §9）：未知键 / 值类型错 / 列表 &gt;100 /
     * keyword &gt;64 字符 / include_revoked 非布尔；以及「订阅条件不可变」的更新拒绝
     * （改条件 = 删旧建新，§2.2 uk(consumer_id, filter_hash) 语义）。
     */
    public static final int SUBSCRIPTION_FILTER_INVALID = 10604;

    /** 10605 消费者 / 订阅 / 投递行不存在（或已软删）（SUBSCRIPTION_DESIGN §9）。 */
    public static final int SUBSCRIPTION_NOT_FOUND = 10605;

    /**
     * 10606 投递租约已失效（SUBSCRIPTION_DESIGN §4/§9）：ack/nack/webhook 汇报时行已被回收重派 /
     * 易主 / 离开 DISPATCHED，且非同 lease 终态重放——消费方/dispatcher 应丢弃本行现场。
     * 终态（DELIVERED/DEAD）<b>同 lease</b> 重放不走此码（幂等返回既有终态，§4 v1.1 口径同 10603）。
     */
    public static final int DELIVERY_LEASE_LOST = 10606;

    /**
     * 10607 webhook_url 非法（SUBSCRIPTION_DESIGN §5.4/§9）：协议非 http(s) / 端口不在 80·443 /
     * IP 字面量落私网·环回·链路本地段 / 域名解析后任一 IP 落禁区（SSRF 拦截）。
     * 派发前校验命中即拒（dispatcher 侧按失败汇报走退避，不打 complete）。
     */
    public static final int WEBHOOK_URL_INVALID = 10607;

    private WxErrorCode() {
    }

    /** 账号未注册（典型 error[].field=account）。 */
    public static ApiException accountNotRegistered(String account) {
        return new ApiException(ACCOUNT_NOT_REGISTERED, "账号未在服务端注册: " + account);
    }

    /** 内容哈希校验失败（典型 error[].field=sha256）。 */
    public static ApiException blobChecksumMismatch(String sha256) {
        return new ApiException(BLOB_CHECKSUM_MISMATCH, "字节内容 sha256 校验失败: " + sha256);
    }

    /** 任务租约失效（典型 error[].field=task_id；客户端收到后弃本地现场继续下一任务）。 */
    public static ApiException taskLeaseLost(long taskId) {
        return new ApiException(TASK_LEASE_LOST, "任务租约已失效（被回收重派或易主）: " + taskId);
    }

    /** filter 非法（典型 error[].field=filter）。 */
    public static ApiException subscriptionFilterInvalid(String reason) {
        return new ApiException(SUBSCRIPTION_FILTER_INVALID, "订阅 filter 非法: " + reason);
    }

    /** 消费者 / 订阅不存在或已软删（典型 error[].field=consumer_id / subscription_id）。 */
    public static ApiException subscriptionNotFound(String what, Object id) {
        return new ApiException(SUBSCRIPTION_NOT_FOUND, what + "不存在或已软删: " + id);
    }

    /** 投递租约失效（典型 error[].field=delivery_id；汇报方丢弃本行现场，勿重试同凭据）。 */
    public static ApiException deliveryLeaseLost(long deliveryId) {
        return new ApiException(DELIVERY_LEASE_LOST, "投递租约已失效（被回收重派或已终态异凭据）: " + deliveryId);
    }

    /** webhook_url 非法 / SSRF 拦截（典型 error[].field=webhook_url；reason 带命中规则便于运营修配置）。 */
    public static ApiException webhookUrlInvalid(String url, String reason) {
        return new ApiException(WEBHOOK_URL_INVALID, "webhook_url 非法: " + url + "（" + reason + "）");
    }
}
