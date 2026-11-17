package com.wechat.sync.server.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /v1/auth/refresh 请求体（TASK_DISPATCH §6）：refresh_token（family 凭据，一次性轮转）。
 *
 * @param refreshToken 当前 refresh token（重用会被框架检测并撤销整族 → 10211）
 */
public record RefreshRequest(
        @NotBlank(message = "refresh_token 不能为空")
        String refreshToken) {
}
