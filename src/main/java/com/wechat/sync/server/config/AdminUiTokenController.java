package com.wechat.sync.server.config;

import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.service.TokenService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * admin SPA 独立令牌对签发（admin-ui 登录用）。
 *
 * <p>为何单独一炮：admin refresh 是一次性轮转 + 复用检测（同族并发轮换 = 整族撤销）。
 * admin-ui（浏览器）与后台自动化（auto_reply_demo.py 等脚本）若共享同一族，
 * SPA 登录轮换一次就把脚本手里的 refresh 作废——下次脚本轮换触发 10211 整族撤销，
 * 两条链路一起瘫。独立 uid = 独立 policy key（access-token policies.admin.key=[uid]）
 * = 互不干扰的会话族；本端点必须持有 admin 令牌才能调（凭据分发只增不减）。
 */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AdminUiTokenController {

    private final TokenService tokenService;

    /** POST /v1/auth/admin-ui-token：为 admin-ui 签发独立 admin 令牌对（uid=admin-ui）。 */
    @PostMapping("/admin-ui-token")
    @RequiresToken(value = "admin", roles = "admin")
    public ApiResponse<AccountTokenVO> issueAdminUiToken() {
        return ApiResponse.success(
                tokenService.issueAdminToken("admin-ui", "wxid_bootstrap_dev_0000"));
    }
}
