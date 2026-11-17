package com.wechat.sync.server.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_merge_log 行：fb 兜底键合并的审计（SYNCH_PLAN §6.4 fb→srv 补键合并）。
 * {@code merged_ids BIGINT[]} 本接口不写（被合并行 id 放 {@code detail} 文本），保持实体零 TypeHandler。
 */
@Data
@TableName("wx_merge_log")
public class WxMergeLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 触发合并的 dedup_key（fb: 原键或 srv: 新键） */
    private String dedupKey;

    /** 保留行 wx_message.id */
    private Long keptId;

    /** MERGED */
    private String action;

    /** 人读明细（被合并行 id、方向、字段变化） */
    private String detail;

    private OffsetDateTime createdAt;
}
