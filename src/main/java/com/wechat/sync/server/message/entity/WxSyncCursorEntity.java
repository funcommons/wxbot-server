package com.wechat.sync.server.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_sync_cursor 行：客户端本地游标的服务端镜像（权威在客户端，服务端只读供 /v1/status 观测，
 * SYNCH_PLAN §7.2）。uk 三元组 {@code (account, chat_username, shard)}——shard 必带（local_id 跨分片重号）；
 * 镜像只前进不后退（乱序/并发上报取较大值）。
 */
@Data
@TableName("wx_sync_cursor")
public class WxSyncCursorEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String account;

    private String chatUsername;

    /** 源库相对路径，缺省空串 */
    private String shard;

    /** 补漏游标（分片内单调 rowid） */
    private Long maxLocalId;

    /** 快路径游标（实测=毫秒时间戳） */
    private Long maxSortSeq;

    private OffsetDateTime lastSyncAt;

    private OffsetDateTime lastFullScan;

    private OffsetDateTime createdAt;
}
