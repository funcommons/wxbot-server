package com.wechat.sync.server.account.dto;

import com.wechat.sync.server.account.entity.WxRegCodeEntity;

import java.time.OffsetDateTime;

/**
 * 注册码管理视图（GET /v1/reg-codes，TASK_DISPATCH §6）：<b>不含明文</b>——明文只在签发响应出现一次。
 * 含已消费/已作废行的审计字段（used_at/used_by/is_deleted）。
 *
 * @param id              雪花 ID（契约 String，全局 Long→String）
 * @param label           备注（发给谁）
 * @param preBoundAccount 预绑定账号；null = 任意未注册账号可用
 * @param expiresAt       过期即废
 * @param usedAt          非 null = 已消费
 * @param usedBy          消费方 hostname（审计）
 * @param createdAt       签发时间
 * @param isDeleted       0 正常 / 1 已作废
 */
public record RegCodeVO(
        Long id,
        String label,
        String preBoundAccount,
        OffsetDateTime expiresAt,
        OffsetDateTime usedAt,
        String usedBy,
        OffsetDateTime createdAt,
        Integer isDeleted) {

    public static RegCodeVO from(WxRegCodeEntity entity) {
        return new RegCodeVO(entity.getId(), entity.getLabel(), entity.getAccount(),
                entity.getExpiresAt(), entity.getUsedAt(), entity.getUsedBy(),
                entity.getCreatedAt(), entity.getIsDeleted());
    }
}
