package com.wechat.sync.server.status.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.status.dto.StatusVOs.StatusVO;
import com.wechat.sync.server.status.service.StatusService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维观测入口（T7，角色 admin）：客户端活性、去重健康度、最近批次。
 *
 * <p>admin 令牌才可进（{@code @RequiresToken} value 是 token 型别名，必填）——观测数据含
 * hostname / client_version 与账号清单，等同管理面。只读、无 Idempotency-Key（幂等只覆盖
 * 写方法，且 path-patterns 已收窄到 /v1/reports/**）；限流走框架缺省 120 次/分钟每 IP
 * （§9 查询档）。异常一律上抛由 GlobalExceptionHandler 翻成 HTTP 200 信封。
 */
@RestController
@RequiredArgsConstructor
@RequiresToken(value = TokenService.ADMIN_TYPE, roles = TokenService.ROLE_ADMIN)
public class StatusController {

    private final StatusService statusService;

    /** GET /v1/status：accounts 活性 + fb 未合并/疑似重复计数 + 最近 10 批摘要。 */
    @GetMapping("/v1/status")
    public ApiResponse<StatusVO> status() {
        return ApiResponse.success(statusService.status());
    }
}
