package com.wechat.sync.server.message.dto;

import java.util.List;

/**
 * POST /v1/reports/messages 响应 data（API_DESIGN §4.2）。
 *
 * <p>results 与请求 messages 顺序一一对应（只含成功/幂等命中的条目，失败条目走信封 error[]）。
 * 注意 framework4j-web 全局 {@code Long → String}：{@code batchId}/{@code messageId}/{@code localId}/
 * {@code size} 线上均为 JSON 字符串（ID 纪律 §2.2，客户端按字符串处理）。
 *
 * @param batchId     wx_ingest_batch_log.id（String）
 * @param results     逐条结果
 * @param blobsNeeded 服务端缺的字节；空数组 = 一次都不用传
 */
public record MessagesIngestVO(String batchId, List<MessageResult> results, List<BlobNeeded> blobsNeeded) {

    /**
     * @param shard     源库相对路径（回显）
     * @param localId   分片内 rowid（回显）
     * @param dedupKey  服务端计算的权威键（srv:/fb:）
     * @param messageId wx_message.id（String）
     * @param action    inserted | unchanged | merged（fb→srv 补键合并）
     */
    public record MessageResult(String shard, Long localId, String dedupKey, String messageId, String action) {
    }

    /**
     * @param sha256        需要上传的明文内容哈希
     * @param size          附件元数据自报大小（缺省 0 = 未知；PUT 校验以实际字节为准）
     * @param attachmentKey {@code <message_id>:<kind>:<tier>}，排障用
     */
    public record BlobNeeded(String sha256, Long size, String attachmentKey) {
    }
}
