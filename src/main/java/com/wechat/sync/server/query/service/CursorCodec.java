package com.wechat.sync.server.query.service;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 消息流 keyset 游标编解码（API_DESIGN §2.4 / DB_DESIGN §12.3）。
 *
 * <p>游标是<strong>服务端不透明串</strong>：{@code Base64Url(createTimeMillis + ":" + id)}，
 * 对应 {@code (create_time, id)} 倒序 keyset 的「上一页最后一条」。客户端只可原样回传，
 * 不承诺内部格式（换编码不算破坏性变更）；解码做全量结构校验——非 Base64Url 字符集、
 * 缺冒号分隔、非数字、越界（millis&lt;0 / id≤0）一律 {@code 10102}（field=cursor），
 * 杜绝把不可信输入拼进 keyset 谓词。
 *
 * <p>时间取 {@code create_time} 的 UTC epoch 毫秒（排序权威是微信侧数据事实，API_DESIGN D5）；
 * 同毫秒多消息由 id 兜底保证全序。{@code create_time} 为 NULL 的行在 PG
 * {@code ORDER BY DESC} 下排最前、只可能出现在第一屏，且永远进不了游标（编码侧不会遇到）。
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    /** 解码后的 keyset 位点：{@code createTimeMillis} UTC 毫秒 + 行 id。 */
    public record Key(long createTimeMillis, long id) {

        /** 还原成 timestamptz 绑定值（UTC 固定偏移，比较的是绝对时刻）。 */
        public OffsetDateTime createTime() {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(createTimeMillis), ZoneOffset.UTC);
        }
    }

    /** 编码一个位点（分页响应的 next_cursor）。 */
    public static String encode(long createTimeMillis, long id) {
        String raw = createTimeMillis + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** 解码客户端回传的游标；任何不合法形态 → 10102（field=cursor）。 */
    public static Key decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw paramError(cursor, "cursor 必填（keyset 分页起点，首页传 cursor 省略）");
        }
        final String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw paramError(cursor, "cursor 不是合法的 Base64Url 串");
        }
        int sep = decoded.indexOf(':');
        if (sep <= 0 || sep == decoded.length() - 1) {
            throw paramError(cursor, "cursor 内部格式必须是 <createTimeMillis>:<id>");
        }
        try {
            long millis = Long.parseLong(decoded.substring(0, sep));
            long id = Long.parseLong(decoded.substring(sep + 1));
            if (millis < 0 || id <= 0) {
                throw paramError(cursor, "cursor 位点越界（createTimeMillis>=0 且 id>0）");
            }
            return new Key(millis, id);
        } catch (NumberFormatException e) {
            throw paramError(cursor, "cursor 位点必须是十进制整数");
        }
    }

    /** 10102 统一出口：带 error[].field=cursor 与被拒绝的原值。 */
    private static ApiException paramError(String cursor, String message) {
        return new ApiException(ApiCode.PARAM_FORMAT_ERROR, "cursor 非法: " + message,
                java.util.List.of(ApiError.of("cursor", "FORMAT_INVALID", message, cursor)));
    }
}
