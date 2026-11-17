package com.wechat.sync.server.task.controller;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;

/**
 * 任务路径参数统一解析：{@code task_id} 非数字 → 10102（PARAM_FORMAT_ERROR），不让 Spring 的
 * 类型失配落成 10001 未知错误（TASK_DISPATCH §6 口径：10102 参数非法归业务信封）。
 * 两个 task 控制器共用（同包 package-private）。
 */
final class TaskIdParser {

    private TaskIdParser() {
    }

    /** 解析路径参数；负数不拦（selectById 未命中自然走 10400）。field 用于 10102 错误消息。 */
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
}
