package com.wechat.sync.server.task.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * client 任务端点入参集合（TASK_DISPATCH §6）。字段驼峰，线上由 framework4j-web 全局转
 * snake_case。四个薄 record 集中一个文件（QueryVOs 先例：单文件徒增噪音）。
 */
public final class TaskClientRequests {

    private TaskClientRequests() {
    }

    /**
     * POST /v1/tasks/claim：body 可空——maxTasks 缺省/钳制（1..10）在 service（§6 钳制口径单一化）；
     * taskTypes 空表/null = 不过滤。
     */
    public record ClaimRequest(Integer maxTasks, List<String> taskTypes) {
    }

    /**
     * POST /v1/tasks/{task_id}/complete：lease_id 是认领时下发的凭据（必填，@NotBlank → 10101）；
     * result 为执行摘要 JSON 对象文本，空 → 服务端落 '{}'（§6 body {result}）。
     */
    public record CompleteRequest(@NotBlank String leaseId, String result) {
    }

    /**
     * POST /v1/tasks/{task_id}/fail：retryable 缺省 false——契约错误（101xx/106xx）不进重试循环
     * 是保守缺省（§7：客户端语义上两条路径都会显式传）。
     */
    public record FailRequest(@NotBlank String leaseId, String error, Boolean retryable) {
    }

    /** POST /v1/tasks/{task_id}/renew：续租凭据（§4.2 续租 SQL 以 lease_id 为条件，body 必带）。 */
    public record RenewRequest(@NotBlank String leaseId) {
    }
}
