package com.wechat.sync.server.heartbeat.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.heartbeat.dto.HeartbeatRequest;
import com.wechat.sync.server.heartbeat.dto.HeartbeatVO;
import com.wechat.sync.server.heartbeat.service.HeartbeatService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 心跳接口（API_DESIGN §4.3，角色 client）：客户端活性信号，只更新
 * {@code wx_account.last_seen_at / hostname / client_version}。
 *
 * <p>client 令牌才可进（admin 令牌按 {@code type} 不匹配被框架拦截）；校验次序与 T3/T4 一致：
 * 先 {@link HeartbeatService#requireAccount}（缺 account 是参数缺失 → 10101），再
 * {@link AccountBinding#requireRegistered}（不一致 → 10300、未注册 → 10601）。异常一律上抛，
 * 由 GlobalExceptionHandler 翻成 HTTP 200 信封。
 *
 * <p>幂等由 framework4j-idempotency 对写方法自动处理（Idempotency-Key）；限流归 T7 统一接线。
 */
@RestController
@RequestMapping("/v1/reports")
@RequiredArgsConstructor
@RequiresToken(value = TokenService.CLIENT_TYPE, roles = TokenService.ROLE_CLIENT)
public class HeartbeatController {

    private final HeartbeatService heartbeatService;
    private final AccountBinding accountBinding;

    /** 心跳：last_seen_at = now + hostname / client_version 覆写，回 server_time。 */
    @PostMapping("/heartbeats")
    public ApiResponse<HeartbeatVO> heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        String account = heartbeatService.requireAccount(request.account());
        accountBinding.requireRegistered(account);
        return ApiResponse.success(heartbeatService.heartbeat(account, request));
    }
}
