package com.wechat.sync.server.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_blob 行：内容寻址对象（跨账号共享，schema.sql / SYNCH_PLAN §9.4）。T4 只做读侧存在性判定
 * （blobs_needed = 批内附件 sha256 减去 wx_blob 已有）；写入归 T5 的 PUT /v1/blobs/{sha256}。
 * uk 是部分唯一索引 {@code ON (sha256) WHERE is_deleted=0}，查询须带同谓词。
 */
@Data
@TableName("wx_blob")
public class WxBlobEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 解密/解包后明文的内容哈希 */
    private String sha256;

    private Long size;

    /** 探测值（jpg/png/silk/mp4…），不轻信源侧 */
    private String ext;

    /** 相对路径 &lt;aa&gt;/&lt;bb&gt;/&lt;sha256&gt;，根目录在部署配置 */
    private String storagePath;

    /** 软删：0 正常（GC 判据见 DB_DESIGN §9 引用计数） */
    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
