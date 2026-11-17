package com.wechat.sync.server.account.controller;

import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.dto.AccountTokenRequest;
import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.dto.AccountVO;
import com.wechat.sync.server.account.service.AccountService;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号与令牌管理接口（API_DESIGN §6，角色 admin）。
 *
 * <p>异常一律上抛，由 framework4j-web 的 GlobalExceptionHandler 翻译成 HTTP 200 信封；
 * 幂等由 framework4j-idempotency 拦截器对写方法自动处理（Idempotency-Key）。
 */
@RestController
@RequestMapping("/v1/accounts")
@RequiredArgsConstructor
@RequiresToken(value = TokenService.ADMIN_TYPE, roles = "admin")
public class AccountAdminController {

    private final AccountService accountService;
    private final TokenService tokenService;

    /** 注册同步账号；重复注册 → 10401。 */
    @PostMapping
    public ApiResponse<AccountVO> register(@Valid @RequestBody AccountRegisterRequest request) {
        return ApiResponse.success(accountService.register(request));
    }

    /** 签发/轮换 client 令牌（旧令牌即时吊销）。 */
    @PostMapping("/{account}/tokens")
    public ApiResponse<AccountTokenVO> issueToken(
            @PathVariable String account,
            @Valid @RequestBody(required = false) AccountTokenRequest request) {
        int ttl = request == null ? AccountTokenRequest.DEFAULT_TTL_SECONDS : request.effectiveTtlSeconds();
        return ApiResponse.success(tokenService.issueForAccount(account, ttl));
    }

    /** 注销账号（软删 + 吊销令牌）。 */
    @DeleteMapping("/{account}")
    public ApiResponse<Void> delete(@PathVariable String account) {
        accountService.delete(account);
        return ApiResponse.success();
    }

    /** 账号列表（Offset 分页）。 */
    @GetMapping
    public ApiResponse<PageVO<AccountVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success(accountService.list(page, pageSize));
    }

    /** 账号详情。 */
    @GetMapping("/{account}")
    public ApiResponse<AccountVO> detail(@PathVariable String account) {
        return ApiResponse.success(accountService.detail(account));
    }
}
