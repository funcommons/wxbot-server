package com.wechat.sync.server.message.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /v1/reports/messages 请求体（API_DESIGN §4.2）：顶层 {@code account} + {@code batch_ref} + chats[]。
 * JSON 字段名 snake_case 由 framework4j-web 全局 PropertyNamingStrategy 转换（record 组件用驼峰）。
 *
 * <p>类型纪律（§2.2）：{@code serverId}/{@code serverIdRaw}/{@code typeCode} 为 String（实测值超 JS
 * 安全整数）；{@code localId}/{@code sortSeq}/{@code createTimeRaw}/{@code size} 为 Number。
 * 元素级「必填/格式」不放 bean validation（框架把 MethodArgumentNotValidException 翻成 10100，而契约要求
 * 10101/10102 且部分失败要逐条回 error[]），由 {@code MessagesIngestService} 显式校验；这里只保留
 * {@code @Size} 作超长防御（命中走 10100）。
 */
public record MessagesReportRequest(
        @Size(max = 64, message = "account 长度不能超过 64")
        String account,

        /** 客户端参考号，仅观测、可重放，记入 wx_ingest_batch_log */
        @Size(max = 64, message = "batch_ref 长度不能超过 64")
        String batchRef,

        @Size(max = 1000, message = "chats 数量不能超过 1000")
        List<@Valid ChatItem> chats) {

    /** 会话批：一个 (account, chat) 一个事务边界（SYNCH_PLAN §6.4：整 chat 原子、chat 间独立）。 */
    public record ChatItem(
            @Size(max = 64, message = "chat_username 长度不能超过 64")
            String chatUsername,

            /** 客户端本地游标镜像（权威在客户端，服务端只读观测）；缺省不镜像 */
            CursorItem cursor,

            @Size(max = 5000, message = "单会话 messages 数量不能超过 5000")
            List<@Valid MessageItem> messages) {
    }

    /** 游标镜像元素：shard 必带（local_id 跨分片重号，C1）。 */
    public record CursorItem(
            @Size(max = 128, message = "cursor.shard 长度不能超过 128")
            String shard,

            /** 补漏游标（分片内单调 rowid） */
            Long maxLocalId,

            /** 快路径游标（实测=毫秒时间戳） */
            Long maxSortSeq) {
    }

    /** 消息元素（源侧一行）。 */
    public record MessageItem(
            /** 源库相对路径，必填非空（服务端 10102） */
            @Size(max = 128, message = "shard 长度不能超过 128")
            String shard,

            /** 分片内 rowid，Number */
            Long localId,

            /** String；为 0 或缺省时不传（兜底键路径） */
            @Size(max = 64, message = "server_id 长度不能超过 64")
            String serverId,

            /** 仅 BIGINT 溢出时非 null */
            @Size(max = 64, message = "server_id_raw 长度不能超过 64")
            String serverIdRaw,

            /** 实测=毫秒时间戳 */
            Long sortSeq,

            /** Unix 秒，排序权威的来源 */
            Long createTimeRaw,

            /** 4.x 复合码，契约统一 String（防超精度） */
            @Size(max = 32, message = "type_code 长度不能超过 32")
            String typeCode,

            @Size(max = 64, message = "type_name 长度不能超过 64")
            String typeName,

            /** 已解析真实 wxid；解析不了可缺省（fb 键用空串占位） */
            @Size(max = 64, message = "sender_username 长度不能超过 64")
            String senderUsername,

            /** shard_name2id | body_prefix | card_xml，越界 10102 */
            String senderSource,

            /** 已剥群前缀正文 */
            String body,

            /** 可选（XML 大）；正确性只依赖 content_sha256 */
            String contentRaw,

            /** 必填，64 位十六进制，对剥前缀后的 body 算 */
            @Size(max = 64, message = "content_sha256 长度不能超过 64")
            String contentSha256,

            Boolean isRevoked,

            List<@Valid AttachmentItem> attachments) {
    }

    /** 附件元数据元素（字节走 PUT /v1/blobs/{sha256}，T5）。 */
    public record AttachmentItem(
            /** image | voice | video | file，未识别归 UNKNOWN */
            String kind,

            /** original | full | mid | thumb，空/缺省 → UNKNOWN */
            @Size(max = 16, message = "tier 长度不能超过 16")
            String tier,

            @Size(max = 255, message = "filename 长度不能超过 255")
            String filename,

            @Size(max = 16, message = "ext 长度不能超过 16")
            String ext,

            Long size,

            /** 微信侧指纹（图片非内容哈希，C14） */
            @Size(max = 32, message = "md5 长度不能超过 32")
            String md5,

            /** 解密后明文哈希；未取得时传空串 */
            @Size(max = 64, message = "sha256 长度不能超过 64")
            String sha256,

            @Size(max = 512, message = "local_source 长度不能超过 512")
            String localSource,

            /** pending | ok | mid_only | only_thumbnail | no_local_copy | failed，缺省 pending */
            String status) {
    }
}
