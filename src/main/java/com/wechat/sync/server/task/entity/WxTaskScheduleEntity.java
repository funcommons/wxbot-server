package com.wechat.sync.server.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wechat.sync.server.common.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * wx_task_schedule 行：周期计划（物化源）——调度器扫 nextMaterializeAt 生成 wx_task（TASK_DISPATCH §2.4）。
 *
 * <p>account NULL = 对全部注册账号生效（物化时展开）；target '*' = 账号内按策略展开。
 * interval_seconds 与 daily_at 二选一，两者都空 = 仅手工；服务层创建/启用时强制
 * {@code nextMaterializeAt = now()}（NULL 表示显式「仅手工」，调度器 {@code <= now()} 永不命中）。
 * 种子计划 id 固定 1/2/3（schema.sql v5 块 ON CONFLICT (id) DO NOTHING）。
 */
@Data
@TableName(value = "wx_task_schedule", autoResultMap = true)
public class WxTaskScheduleEntity {

    /** 雪花 ID（种子计划固定 1/2/3） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 生效账号；NULL = 全部注册账号（物化时展开） */
    private String account;

    /** SYNC_MESSAGES | SYNC_RELATIONS | FULL_SCAN | BLOB_UPLOAD | PROBE */
    private String taskType;

    /** chat_username；'*' = 账号内按策略展开（活动窗口 / 全扫轮转） */
    private String target;

    /** 周期秒数；与 dailyAt 二选一 */
    private Integer intervalSeconds;

    /** 每日定点（HH:MM[:SS]）；与 intervalSeconds 二选一 */
    private LocalTime dailyAt;

    /** 物化出的 wx_task.priority */
    private Integer priority;

    /** 计划参数 JSONB 文本透传（物化时并入 wx_task.payload；jsonb 列经 JsonbTypeHandler 写入） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String payload;

    /** target='*' 且 SYNC_MESSAGES 物化窗口（天）：只为 last_message_at 在窗口内的会话物化 */
    private Integer activityWindowDays;

    /** 启用：1 启用 / 0 暂停（调度器只扫 enabled=1） */
    private Integer enabled;

    /** 软删：0 正常 / 1 已删除 */
    private Integer isDeleted;

    /** 上次物化时点（观测） */
    private OffsetDateTime lastMaterializedAt;

    /** 下次物化时点（调度器扫此列；创建/启用时强制 = now()） */
    private OffsetDateTime nextMaterializeAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
