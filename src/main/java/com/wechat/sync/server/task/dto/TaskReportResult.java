package com.wechat.sync.server.task.dto;

/**
 * complete/fail/renew 汇报结果（TASK_DISPATCH §4.2/§6）。
 *
 * @param replayed   true = 同 lease 的终态重放（返回既有 result/error，幂等不报错）；
 *                   false = 本次生效，taskStatus 为写入后的新状态
 * @param taskStatus SUCCEEDED / PENDING（可重试失败回队）/ DEAD / RUNNING（renew 后观测态）
 * @param result     complete 写入或重放读出的执行摘要；renew/fail 为 null
 * @param error      fail 写入或重放读出的失败原因；其余为 null
 */
public record TaskReportResult(boolean replayed, String taskStatus, String result, String error) {
}
