package com.wechat.sync.server.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_ingest_batch_log 行：上报批次观测（幂等性不依赖它，供 /v1/status 与排障）。
 * 每次消息批上报落一行；{@code batch_ref} 是客户端参考号，可重放、不参与正确性。
 */
@Data
@TableName("wx_ingest_batch_log")
public class WxIngestBatchLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String account;

    /** 客户端参考号（可重放，仅观测） */
    private String batchRef;

    private OffsetDateTime startedAt;

    private Integer durationMs;

    /** 进入落库流程的合法消息数（不含校验失败条目） */
    private Integer nMessages;

    /** 新增 wx_message_source_rel 行数 */
    private Integer nSources;

    /** 新建 wx_message 行数 */
    private Integer nInserted;

    /** fb→srv 补键合并次数 */
    private Integer nMerged;

    /** OK | PARTIAL | FAILED */
    private String status;

    /** 校验/落库失败摘要（超长截断） */
    private String error;

    private OffsetDateTime createdAt;
}
