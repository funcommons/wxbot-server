package com.wechat.sync.server.account.dto;

/**
 * 令牌签发/轮换响应（API_DESIGN §6）：三个字段契约均为 String。
 *
 * @param accessToken  access token（Bearer，framework4j-accesstoken HS256）
 * @param refreshToken refresh token（family，用于 {@code /v1/auth/refresh} 轮转）
 * @param expiresAt    access token 过期时间 ISO-8601（含时区）
 */
public record AccountTokenVO(String accessToken, String refreshToken, String expiresAt) {
}
