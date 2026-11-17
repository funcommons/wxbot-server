package com.wechat.sync.server.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_message_attachment 行：附件元数据先行，字节独立上传（API_DESIGN §4.4 / SYNCH_PLAN §9）。
 * uk 四元组 {@code (message_id, kind, tier, sha256)}；{@code sha256} 缺省 {@code ''} 表示明文尚未取得，
 * {@code blob_sha256} 允许后回填（T5 blob 文件库）。
 */
@Data
@TableName("wx_message_attachment")
public class WxMessageAttachmentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** wx_message.id（应用层保证） */
    private Long messageId;

    /** IMAGE | VOICE | VIDEO | FILE | UNKNOWN */
    private String kind;

    /** ORIGINAL | FULL | MID | THUMB | UNKNOWN */
    private String tier;

    /** 原文件名（文件消息是明文原名） */
    private String filename;

    private String ext;

    private Long size;

    /** 微信侧指纹（对图片不是内容哈希，C14） */
    private String md5;

    /** 解密后明文哈希；'' = 尚未取得 */
    private String sha256;

    /** wx_blob.sha256（应用层保证） */
    private String blobSha256;

    /** 源侧路径（排障） */
    private String localSource;

    /** PENDING | OK | MID_ONLY | ONLY_THUMBNAIL | NO_LOCAL_COPY | FAILED */
    private String status;

    private OffsetDateTime syncedAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
