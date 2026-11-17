package com.wechat.sync.server.task.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalTime;

/**
 * admin 任务/计划端点入参集合（TASK_DISPATCH §6）。字段驼峰，线上全局转 snake_case。
 */
public final class TaskAdminRequests {

    private TaskAdminRequests() {
    }

    /**
     * POST /v1/tasks：手工建任务；target 空为 '*'；payload 必须 JSON 对象（service 校验 10102）；
     * maxAttempts 可空（缺省 3），service 校验 1..10 越界 → 10102（T9-5b 扩参）。
     */
    public record TaskCreateRequest(@NotBlank String taskType, @NotBlank String account,
                                    String target, Integer priority, String payload,
                                    Integer maxAttempts) {
    }

    /**
     * POST /v1/task-schedules：interval_seconds 与 daily_at 二选一（service 校验 10102）；
     * account 空 = 对全部注册账号生效；enabled 缺省 true 且有周期时 next_materialize_at 强制 = now()。
     */
    public record ScheduleCreateRequest(String account, @NotBlank String taskType, String target,
                                        Integer intervalSeconds, LocalTime dailyAt, Integer priority,
                                        String payload, Integer activityWindowDays, Boolean enabled) {
    }

    /** PUT /v1/task-schedules/{id}：全字段可选，null = 不改；周期变更或恢复启用会重排 next_materialize_at。 */
    public record ScheduleUpdateRequest(Boolean enabled, Integer intervalSeconds, LocalTime dailyAt,
                                        Integer priority, String payload, Integer activityWindowDays) {
    }
}
