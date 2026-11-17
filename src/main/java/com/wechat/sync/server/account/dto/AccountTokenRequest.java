package com.wechat.sync.server.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * POST /v1/accounts/{account}/tokens 请求体（API_DESIGN §6）。
 *
 * @param ttlSeconds 令牌有效期（秒），可空，缺省 7776000 = 90d
 */
public record AccountTokenRequest(
        @Min(value = 60, message = "ttl_seconds 最小 60 秒")
        @Max(value = 31536000, message = "ttl_seconds 最大 1 年")
        Integer ttlSeconds) {

    /** 缺省 TTL：90 天（API_DESIGN §6 / application.yml policies.client.expire-time） */
    public static final int DEFAULT_TTL_SECONDS = 7776000;

    /** 归一化：null / 非正数回退缺省值。 */
    public int effectiveTtlSeconds() {
        return ttlSeconds == null || ttlSeconds <= 0 ? DEFAULT_TTL_SECONDS : ttlSeconds;
    }
}
