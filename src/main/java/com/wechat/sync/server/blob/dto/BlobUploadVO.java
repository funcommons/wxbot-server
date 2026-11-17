package com.wechat.sync.server.blob.dto;

/**
 * PUT /v1/blobs/{sha256} 响应 data（API_DESIGN §4.4）。
 *
 * <p>{@code status} ∈ {@code exists | stored}：已有 → 幂等返回 exists（跨客户端去重发生在这里，
 * SYNCH_PLAN §9.4 第 2 步）；没有 → 临时名落盘 + 校验 + 原子转正后返回 stored。
 *
 * <p>注意 framework4j-web 全局 {@code Long → String}：{@code size} 线上是 JSON 字符串
 * （与 T4 {@code blobs_needed[].size} 同口径；API_DESIGN §2.2 把 size 归为 Number，属已知出入）。
 *
 * @param sha256 内容哈希（回显，小写归一化后）
 * @param status exists | stored
 * @param size   实际落盘/盘上字节数
 */
public record BlobUploadVO(String sha256, String status, Long size) {
}
