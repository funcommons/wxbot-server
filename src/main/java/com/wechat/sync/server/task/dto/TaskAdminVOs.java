package com.wechat.sync.server.task.dto;

import com.wechat.sync.server.task.entity.WxTaskEntity;
import com.wechat.sync.server.task.entity.WxTaskRunEntity;
import com.wechat.sync.server.task.entity.WxTaskScheduleEntity;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * admin 任务/历史/计划列表 VO 集合（TASK_DISPATCH §6）。实体直出、诊断字段全带
 * （lease_id / last_error 是 §2.3 排障口径的一部分）；字段驼峰，线上全局转 snake_case，
 * Long → String 由框架统一处理（TaskClaimVO 同口径）。
 */
public final class TaskAdminVOs {

    private TaskAdminVOs() {
    }

    /** GET /v1/tasks 行。 */
    public record TaskVO(Long id, String taskType, String account, String target, Integer priority,
                         String status, Integer attempts, Integer maxAttempts, String leaseId,
                         OffsetDateTime leaseUntil, String payload, String result, String lastError,
                         OffsetDateTime dispatchedAt, OffsetDateTime finishedAt, Long scheduleId,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static TaskVO from(WxTaskEntity t) {
            return new TaskVO(t.getId(), t.getTaskType(), t.getAccount(), t.getTarget(),
                    t.getPriority(), t.getStatus(), t.getAttempts(), t.getMaxAttempts(),
                    t.getLeaseId(), t.getLeaseUntil(), t.getPayload(), t.getResult(),
                    t.getLastError(), t.getDispatchedAt(), t.getFinishedAt(), t.getScheduleId(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    /** GET /v1/task-runs 行（派发执行历史，append-only）。 */
    public record TaskRunVO(Long id, Long taskId, Integer attemptNo, String leaseId, String account,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt, String outcome,
                            String result, String error, OffsetDateTime createdAt) {

        public static TaskRunVO from(WxTaskRunEntity r) {
            return new TaskRunVO(r.getId(), r.getTaskId(), r.getAttemptNo(), r.getLeaseId(),
                    r.getAccount(), r.getStartedAt(), r.getFinishedAt(), r.getOutcome(),
                    r.getResult(), r.getError(), r.getCreatedAt());
        }
    }

    /** GET /v1/task-schedules 行（周期计划，配置表量级小全量返回）。 */
    public record ScheduleVO(Long id, String account, String taskType, String target,
                             Integer intervalSeconds, LocalTime dailyAt, Integer priority,
                             String payload, Integer activityWindowDays, Integer enabled,
                             OffsetDateTime lastMaterializedAt, OffsetDateTime nextMaterializeAt,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static ScheduleVO from(WxTaskScheduleEntity s) {
            return new ScheduleVO(s.getId(), s.getAccount(), s.getTaskType(), s.getTarget(),
                    s.getIntervalSeconds(), s.getDailyAt(), s.getPriority(), s.getPayload(),
                    s.getActivityWindowDays(), s.getEnabled(), s.getLastMaterializedAt(),
                    s.getNextMaterializeAt(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }
}
