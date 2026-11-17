package com.wechat.sync.server.heartbeat.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /v1/reports/heartbeats 请求体（API_DESIGN §4.3）。
 *
 * <p>字段命名驼峰，线上 JSON 为 snake_case（framework4j-web 全局命名策略，与 T3/T4 DTO 同）。
 * 校验只放 {@code @Size} 超长防御（命中走框架 10100，防 DB varchar 溢出）；account 缺失由
 * service 的 {@code requireAccount} 抛 10101（参数缺失不走 bean validation，T3/T4 同口径）。
 *
 * <p>{@code wechatVersion} / {@code dbSizeBytes} / {@code loggedIn}：wx_account 无对应列
 * （schema.sql DDL 只有 hostname / client_version / last_seen_at），<b>不落库只记日志</b>——
 * 没有列就不存，避免为心跳私加表结构。
 *
 * @param account       已注册同步账号（wxid_xxx_8316）
 * @param hostname      客户端所在机器（写入 wx_account.hostname）
 * @param clientVersion 客户端版本（写入 wx_account.client_version）
 * @param wechatVersion 微信版本（观测，仅日志）
 * @param dbSizeBytes   微信库体积（观测，仅日志）
 * @param loggedIn      微信登录态（观测，仅日志）
 */
public record HeartbeatRequest(
        @Size(max = 64) String account,
        @Size(max = 128) String hostname,
        @Size(max = 32) String clientVersion,
        @Size(max = 32) String wechatVersion,
        Long dbSizeBytes,
        Boolean loggedIn) {
}
