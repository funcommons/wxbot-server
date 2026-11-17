package com.wechat.sync.server.query.dto;

import jakarta.validation.constraints.Size;

/**
 * §5.5 消息检索请求体（POST + body，规范「复杂查询」模式；全部字段可选）。
 *
 * <p>时间字段收字符串（ISO-8601 / epoch 秒 / epoch 毫秒），服务层统一解析，
 * 非法 → 10102（field 指到具体字段）；与 §5.3 的 GET 查询参数同一套解析器。
 * {@code q} 走 {@code body ILIKE}，转义由服务层完成；{@code cursor / limit} 与
 * GET /v1/messages 同一套 keyset 位点与钳制。
 */
public record SearchRequest(
        @Size(max = 256) String q,
        @Size(max = 64) String account,
        @Size(max = 64) String chatUsername,
        @Size(max = 64) String senderUsername,
        @Size(max = 64) String typeName,
        @Size(max = 40) String createTimeAfter,
        @Size(max = 40) String createTimeBefore,
        @Size(max = 512) String cursor,
        Integer limit) {

    /** 空白视为未提供（q=" " 等于不检索，避免空串拼出 {@code %%} 全表通配）。 */
    public String normalize(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
