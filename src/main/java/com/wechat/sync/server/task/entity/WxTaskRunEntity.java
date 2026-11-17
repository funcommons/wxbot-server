package com.wechat.sync.server.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wechat.sync.server.common.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_task_run 行：派发执行历史（append-only 日志）——一次派发一行，永不更新（TASK_DISPATCH §2.3）。
 *
 * <p>排障口径：「这条任务被派了几次、每次谁跑的、结果如何、租约丢没丢」。认领事务内插入；
 * complete/fail 时补 {@code finishedAt/outcome}；租约过期被回收则 {@code finishedAt} 保持 NULL、
 * 由巡检补 {@code outcome='LOST'}。日志表无 {@code is_deleted/updated_at}（mc-database-spec 日志表约定）。
 */
@Data
@TableName(value = "wx_task_run", autoResultMap = true)
public class WxTaskRunEntity {

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** wx_task.id（应用层保证，规范禁外键） */
    private Long taskId;

    /** 第几次派发（= wx_task.attempts 派发时点值） */
    private Integer attemptNo;

    /** 本次派发的租约凭据 */
    private String leaseId;

    /** 执行方账号 */
    private String account;

    /** 认领时点 */
    private OffsetDateTime startedAt;

    /** complete/fail 时点；NULL = 租约丢失被回收 */
    private OffsetDateTime finishedAt;

    /** SUCCEEDED | FAILED | LOST（租约过期未回报） */
    private String outcome;

    /** 本次执行摘要 JSONB 文本透传（jsonb 列经 JsonbTypeHandler 写入） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String result;

    /** 失败原因（fail 上报的 error） */
    private String error;

    private OffsetDateTime createdAt;
}
