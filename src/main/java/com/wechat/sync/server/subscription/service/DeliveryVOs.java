package com.wechat.sync.server.subscription.service;

import java.util.List;

/**
 * 投递域 VO 集（SUBSCRIPTION_DESIGN §5.1/§6）：WEBHOOK 载荷与 POLL 拉取<b>共用</b>的 message
 * 摘要结构 + POLL 响应/汇报 VO + dispatcher 任务项。
 *
 * <p>ID 出参口径：{@code Long} 字段依赖框架 Jackson 全局 Long→String（mc-api-spec 防精度丢失），
 * 与 {@code QueryVOs} 的显式 {@code String.valueOf} 两条路并存、线上等价；lease_id 本就是 String 原样。
 * <b>正文是摘要不是全量</b>：{@code content_raw}/{@code content_sha256} 不投递（§5.1——大字段
 * 按需回查查询 API）。
 */
public final class DeliveryVOs {

    private DeliveryVOs() {
    }

    /** §5.1/§6 共用 message 摘要（attachments 仅元数据，无字节）。 */
    public record DeliveryMessageVO(Long id, String dedupKey, String chatUsername, Boolean isGroup,
                                    String senderUsername, String typeCode, String typeName,
                                    String body, Long createTimeRaw, Boolean isRevoked,
                                    List<DeliveryAttachmentVO> attachments) {
    }

    /** 附件元数据（§5.1 示例形态：kind/tier/md5/size；不含 sha256/字节——按需走 blob 下载）。 */
    public record DeliveryAttachmentVO(String kind, String tier, String md5, Long size) {
    }

    /** POLL 拉取单项：lease_id 是本行独立认领凭据（每行一个 UUID，ack/nack 逐行携带）。 */
    public record PullItemVO(Long deliveryId, String leaseId, int attempt, DeliveryMessageVO message) {
    }

    /** POLL 拉取响应（§6 data 结构）。 */
    public record PullResultVO(Long subscriptionId, int visibilitySeconds,
                               List<PullItemVO> messages, boolean hasMore) {
    }

    /** ack/nack 单条命令：每行认领时各得独立 lease_id，批量汇报必须逐行携带（§4 lease 条件更新）。 */
    public record DeliveryAckCommand(long deliveryId, String leaseId) {
    }

    /** ack/nack 逐条结果（批量 = 10700 部分成功口径，部分失败 data 非 null，mc-api-spec 例外）。 */
    public record AckItemResult(long deliveryId, boolean success, boolean replayed, String error) {
    }

    /**
     * WEBHOOK dispatcher 任务项（{@code dispatchDue} 产出、T10-5 WebhookDispatcher 消费）：
     * url 已解析（subscription.webhook_url 覆写 consumer.webhook_url）；payloadJson 是<b>最终
     * 序列化文本</b>——HMAC 签名按 {@code HMAC(key, ts + "." + raw_body)} 对原始字节算，
     * dispatcher 不得重新序列化（键序漂移会让签名与体不一致）。
     *
     * <p>{@code secretHash} 是消费者 WEBHOOK 密钥的 sha256hex（T10-4 补带——库存即签名密钥：
     * 签名密钥口径 = {@code sha256hex(secret)}，服务端直接用库存 hash 签，SUBSCRIPTION_DESIGN
     * §5.1 v1.1 补记 / §12 裁决表；明文永不出库）。NULL = 消费者未配置 WEBHOOK 凭据。
     */
    public record WebhookDeliveryTask(long deliveryId, String leaseId, int attempts, long consumerId,
                                      String url, String payloadJson, String secretHash) {
    }
}
