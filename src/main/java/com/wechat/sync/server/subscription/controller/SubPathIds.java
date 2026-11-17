package com.wechat.sync.server.subscription.controller;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;

import java.util.Set;

/**
 * 订阅域路径参数统一解析（TaskIdParser 同款口径）：非数字 → 10102（不让 Spring 类型失配落成
 * 10001）；负数不拦（selectById 未命中自然走 10605/10400）。两控制器共用（包内可见）。
 */
final class SubPathIds {

    private SubPathIds() {
    }

    /** 解析路径参数；field 用于 10102 错误消息。 */
    static long parse(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, field + " 必须是数字: " + raw);
        }
    }

    /** 可选查询参数版：空/空白返回 null（不过滤），否则按 {@link #parse} 严格解析。 */
    static Long parseOptional(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parse(raw, field);
    }

    /** status 过滤值闭集校验：null/空白 = 不过滤；闭集外 → 10102。 */
    static String requireStatus(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR,
                    "status 只允许 " + String.join("/", allowed) + ": " + raw);
        }
        return upper;
    }
}
