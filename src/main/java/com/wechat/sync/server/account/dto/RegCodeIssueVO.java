package com.wechat.sync.server.account.dto;

import java.time.OffsetDateTime;

/**
 * POST /v1/reg-codes 响应 VO（TASK_DISPATCH §6）：明文码 + 元数据。
 *
 * <p>{@code code} 是**全生命周期唯一一次**明文返回——库存 sha256 哈希（RegCodeService），
 * 列表端点 {@code GET /v1/reg-codes} 的 {@code RegCodeVO} 刻意不含该字段；管理员须当场
 * 把码交付给客户端（聊天工具/密码管理器），关掉响应即不可再取。字段驼峰，线上全局转
 * snake_case，Long → String 由框架统一处理。
 *
 * @param code      明文注册码（{@code wxreg_} + 43 位 base64url，256 位熵）
 * @param id        wx_reg_code 行 id（作废 DELETE /v1/reg-codes/{id} 用）
 * @param expiresAt 过期时刻（缺省签发后 24h）
 */
public record RegCodeIssueVO(String code, Long id, OffsetDateTime expiresAt) {
}
