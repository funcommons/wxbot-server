package com.wechat.sync.server.account.dto;

import com.wechat.sync.server.account.entity.WxAccountEntity;

import java.time.OffsetDateTime;

/**
 * 账号视图（API_DESIGN §5.1 / §6）。字段驼峰命名，线上由 framework4j-web 转 snake_case，
 * {@code id}（Long）自动转 String。
 */
public record AccountVO(
        Long id,
        String account,
        String wxid,
        String hostname,
        String clientVersion,
        OffsetDateTime lastSyncAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static AccountVO from(WxAccountEntity e) {
        return new AccountVO(e.getId(), e.getAccount(), e.getWxid(), e.getHostname(), e.getClientVersion(),
                e.getLastSyncAt(), e.getLastSeenAt(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
