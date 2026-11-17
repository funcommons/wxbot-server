package com.wechat.sync.server.task.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.task.dto.TaskClaimVO;
import com.wechat.sync.server.task.dto.TaskClientRequests.ClaimRequest;
import com.wechat.sync.server.task.dto.TaskClientRequests.CompleteRequest;
import com.wechat.sync.server.task.dto.TaskClientRequests.FailRequest;
import com.wechat.sync.server.task.dto.TaskClientRequests.RenewRequest;
import com.wechat.sync.server.task.dto.TaskReportResult;
import com.wechat.sync.server.task.service.TaskService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务认领/汇报接口（TASK_DISPATCH §6，角色 client）：拉模式队列的客户端入口——
 * 认领 → 本地执行 → complete/fail/renew 按 lease_id 汇报。
 *
 * <p>鉴权与账号来源：{@code @RequiresToken(value=CLIENT_TYPE)} 按令牌 type 拦住 admin 令牌；
 * 任务归属账号直接取 {@code claims.account}（policy.client.key 互斥键，签发必有）传给 service，
 * service 内 {@code AccountBinding.checkBound}（token↔入参一致）+ {@code requireOwnedTask}
 * （任务行归属比对）双层校验，越权 10300。claims.account 异常缺失视同越权（10300）。
 *
 * <p>重放语义（§4.2 v1.1）：complete/fail/renew 对「同 lease 的终态重放」返回 code=0
 * （TaskReportResult.replayed=true，result/error 为既有值）——客户端网络重试不报错；
 * 租约失效一律 10603 由 service 抛出透传。异常一律上抛，GlobalExceptionHandler 翻成 HTTP 200 信封。
 *
 * <p>限流与幂等：claim 走框架缺省档 120/min/IP（application.yml default-limit，§10 口径——
 * 单客户端轮询 60s+抖动，余量充足，不收紧）；complete/fail/renew 的幂等凭据是 body.lease_id
 * （同 lease 重放返回同一终态），不经 Idempotency-Key 拦截器（只挂 /v1/reports/**，§6 claim 豁免同理）。
 */
@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
@RequiresToken(value = TokenService.CLIENT_TYPE, roles = TokenService.ROLE_CLIENT)
public class TaskClientController {

    private final TaskService taskService;

    /** 认领：原子（SKIP LOCKED），认领到 → data.tasks[]（含 lease_id/lease_until），空手 → 空数组。 */
    @PostMapping("/claim")
    public ApiResponse<List<TaskClaimVO>> claim(@RequestBody(required = false) ClaimRequest request) {
        ClaimRequest req = request == null ? new ClaimRequest(null, null) : request;
        return ApiResponse.success(taskService.claim(requireTokenAccount(), req.maxTasks(), req.taskTypes()));
    }

    /** 汇报成功：COMPLETED/REPLAYED 都 code=0（replayed 区分），task_status=SUCCEEDED。 */
    @PostMapping("/{task_id}/complete")
    public ApiResponse<TaskReportResult> complete(@PathVariable("task_id") String taskId,
                                                  @Valid @RequestBody CompleteRequest request) {
        return ApiResponse.success(taskService.complete(requireTokenAccount(),
                TaskIdParser.parse(taskId, "task_id"), request.leaseId(), request.result()));
    }

    /**
     * 汇报失败：task_status=SUCCEEDED 之外的落点由 service 判定——可重试且额度未尽 → PENDING（回队），
     * 否则 → DEAD（终态，保留 lease_id 供重放）。
     */
    @PostMapping("/{task_id}/fail")
    public ApiResponse<TaskReportResult> fail(@PathVariable("task_id") String taskId,
                                              @Valid @RequestBody FailRequest request) {
        boolean retryable = Boolean.TRUE.equals(request.retryable());
        return ApiResponse.success(taskService.fail(requireTokenAccount(),
                TaskIdParser.parse(taskId, "task_id"), request.leaseId(), request.error(), retryable));
    }

    /** 续租：置 RUNNING（观测态，§3），lease_until 顺延——客户端按 lease/3 节拍调用（§7）。 */
    @PostMapping("/{task_id}/renew")
    public ApiResponse<TaskReportResult> renew(@PathVariable("task_id") String taskId,
                                               @Valid @RequestBody RenewRequest request) {
        return ApiResponse.success(taskService.renew(requireTokenAccount(),
                TaskIdParser.parse(taskId, "task_id"), request.leaseId()));
    }

    /** client 令牌的任务归属账号（policy.client.key=[account]，正常签发必有；缺失视同越权）。 */
    private String requireTokenAccount() {
        String account = TokenContext.getClaim(TokenService.CLAIM_ACCOUNT);
        if (account == null || account.isBlank()) {
            throw new ApiException(ApiCode.FORBIDDEN, "令牌缺少 account claim，无法定位任务归属账号");
        }
        return account;
    }
}
