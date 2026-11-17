package com.wechat.sync.server.heartbeat.dto;

import java.time.OffsetDateTime;

/**
 * POST /v1/reports/heartbeats 响应 data（API_DESIGN §4.3）：
 * {@code { "server_time": "2026-11-09T17:20:00+08:00" }}。
 *
 * <p>{@code OffsetDateTime} 由 framework4j-datetime 全局序列化为 ISO-8601 含时区；
 * 组件名 {@code serverTime} 线上自动转 {@code server_time}。
 */
public record HeartbeatVO(OffsetDateTime serverTime) {
}
