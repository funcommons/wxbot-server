package com.wechat.sync.server.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wechat.sync.server.common.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_task 行：任务队列——服务端把「该干什么」物化进本表，客户端拉模式认领（TASK_DISPATCH §2.2）。
 *
 * <p>字段对齐 {@code schema.sql} v5 增量块。防堆积靠部分唯一索引
 * {@code uk_wx_task_active_target}（仅约束活动状态）；终态行保留 {@code leaseId}（重放判定凭据），
 * 回 PENDING（回收/重派）时才清空；认领/回收/汇报全部走带状态条件的 UPDATE，无分布式锁。
 * 主键为雪花 ID：{@code IdType.ASSIGN_ID} 委托 framework4j-id（DDL 的 BIGSERIAL 仅兜底）。
 */
@Data
@TableName(value = "wx_task", autoResultMap = true)
public class WxTaskEntity {

    /** 雪花 ID（API 契约上以 String 下发，framework4j-web 全局 Long→String） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** SYNC_MESSAGES | SYNC_RELATIONS | FULL_SCAN | BLOB_UPLOAD | PROBE（TASK_DISPATCH §5） */
    private String taskType;

    /** 任务归属账号（令牌↔账号绑定，越权认领/汇报 10300） */
    private String account;

    /** chat_username；'*' = 账号全域 */
    private String target;

    /** 大者先派 */
    private Integer priority;

    /** PENDING | DISPATCHED | RUNNING | SUCCEEDED | FAILED | DEAD | CANCELLED（TASK_DISPATCH §3） */
    private String status;

    /** 认领时 +1；≥ maxAttempts → DEAD */
    private Integer attempts;

    /** 重试上限（DDL 缺省 3） */
    private Integer maxAttempts;

    /** 认领令牌（UUID）；complete/fail/renew 的凭据；终态行保留，回 PENDING 时清空 */
    private String leaseId;

    /** 租约到期；过期由回收巡检打回 PENDING */
    private OffsetDateTime leaseUntil;

    /** 任务参数 JSONB 文本透传（time range / probe 项），缺省 {}（jsonb 列经 JsonbTypeHandler 写入） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String payload;

    /** 执行摘要 JSONB 文本透传（成功条数/游标推进等），缺省 {}（jsonb 列经 JsonbTypeHandler 写入） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String result;

    /** 最近一次失败原因（回收巡检写 'lease expired'） */
    private String lastError;

    /** 最近一次认领时点 */
    private OffsetDateTime dispatchedAt;

    /** 进入终态时点 */
    private OffsetDateTime finishedAt;

    /** 源计划 wx_task_schedule.id（应用层保证，规范禁外键；手工建任务为 NULL） */
    private Long scheduleId;

    /** 软删/归档隐藏：0 正常 / 1 已删除 */
    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
