package com.wechat.sync.server.auth;

import com.wechat.sync.server.account.dto.AccountTokenRequest;
import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.service.RegCodeService;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.auth.dto.RefreshRequest;
import com.wechat.sync.server.auth.dto.RegisterRequest;
import com.wechat.sync.server.auth.dto.RegisterVO;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自助认证端点（TASK_DISPATCH §6）：注册（无鉴权）+ refresh 轮换（refresh 型别令牌）。
 *
 * <p>与 {@code AccountAdminController}（admin 三步手工流）并存：本控制器是 v1.1 无人值守首启的
 * 正门——一次性码即凭证，不再需要管理员替客户端建号发令牌。
 * 异常一律上抛，由 framework4j-web 的 GlobalExceptionHandler / {@link AuthExceptionMapping}
 * 翻译成 HTTP 200 信封。
 */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegCodeService regCodeService;
    private final TokenService tokenService;

    /**
     * POST /v1/auth/register：自助注册（无 @RequiresToken——一次性码即凭证）。
     *
     * <p>限流 6/min/IP（RateLimitQuotaConfig.PER_MINUTE，防在线爆破 code_hash）。
     * 事务边界：建号 + 注册码打点在 {@link RegCodeService#registerWithCode} 的一个事务里；
     * <b>签令牌放事务外</b>——令牌态在 Redis，无回滚语义，失败时账号已建不回滚
     * （客户端换一枚码重注册会撞 10401，走 admin 路径补发令牌即可，属可接受的人工兜底）。
     */
    @PostMapping("/register")
    public ApiResponse<RegisterVO> register(@Valid @RequestBody RegisterRequest request) {
        String account = regCodeService.registerWithCode(request);
        AccountTokenVO token = tokenService.issueForAccount(account, AccountTokenRequest.DEFAULT_TTL_SECONDS);
        return ApiResponse.success(
                new RegisterVO(account, token.accessToken(), token.refreshToken(), token.expiresAt()));
    }

    /**
     * POST /v1/auth/refresh：refresh 型别令牌轮换（type="refresh"）。
     *
     * <p>框架语义：一次性轮转（旧 jti 标记 consumed，新 jti 写入 family）+ 重用检测
     * （旧 refresh 被复用 → 撤销整族）。轮换失败（10210 已过期 / 10211 无效或已使用 /
     * 10212 轮转超限）由 {@link AuthExceptionMapping} 原样透传成 102xx 信封，无需在此兜底。
     */
    @PostMapping("/refresh")
    @RequiresToken(value = TokenService.CLIENT_TYPE, type = "refresh")
    public ApiResponse<AccountTokenVO> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.success(tokenService.refreshForClient(request.refreshToken()));
    }
}
