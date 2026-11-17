package com.wechat.sync.server.auth.dto;

/**
 * POST /v1/auth/register 响应体（TASK_DISPATCH §6）：账号名 + 令牌对三字段（展开自 AccountTokenVO）。
 * snake_case 由 framework4j-web 全局策略自动转换（access_token / refresh_token / expires_at）。
 *
 * @param account      注册成功的账号名（客户端写回 config）
 * @param accessToken  access token（Bearer，HS256）
 * @param refreshToken refresh token（family，供 /v1/auth/refresh 一次性轮转）
 * @param expiresAt    access token 过期时间 ISO-8601（含时区）
 */
public record RegisterVO(String account, String accessToken, String refreshToken, String expiresAt) {
}
