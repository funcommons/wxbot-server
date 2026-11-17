package com.wechat.sync.server.query.service;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CursorCodec 单测（API_DESIGN §2.4）：编码→解码 round-trip、Base64Url 无填充/无 +/ 安全字符集、
 * 全量非法形态（缺冒号 / 非数字 / 越界 / 非 Base64）一律 10102 且 field=cursor 带原值。
 */
class CursorCodecTest {

    /** round-trip：编码产物能无损还原位点（含 0 毫秒与 id=1 的下界、极大值上界）。 */
    @Test
    void encodeThenDecodeRoundTrips() {
        long[][] points = {{1760001000000L, 987654321L}, {0L, 1L}, {Long.MAX_VALUE / 2, Long.MAX_VALUE / 3}};
        for (long[] p : points) {
            CursorCodec.Key key = CursorCodec.decode(CursorCodec.encode(p[0], p[1]));
            assertThat(key.createTimeMillis()).as("millis round-trip %s", p[0]).isEqualTo(p[0]);
            assertThat(key.id()).as("id round-trip %s", p[1]).isEqualTo(p[1]);
        }
    }

    /** 编码产物是 URL 安全字符集（无 + / =），可直接放进 GET 查询参数不必再 encode。 */
    @Test
    void encodedCursorUsesUrlSafeAlphabetWithoutPadding() {
        String cursor = CursorCodec.encode(1760001000000L, 42L);
        assertThat(cursor).doesNotContain("+", "/", "=").isBase64();
        assertThat(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII))
                .isEqualTo("1760001000000:42");
    }

    /** Key.createTime() 以 UTC 固定偏移还原 timestamptz 绑定值（keyset 比较的是绝对时刻）。 */
    @Test
    void keyCreateTimeIsUtcInstant() {
        OffsetDateTime time = CursorCodec.decode(CursorCodec.encode(1760001000000L, 1L)).createTime();
        assertThat(time.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(time.toInstant().toEpochMilli()).isEqualTo(1760001000000L);
    }

    /** 缺冒号 / 冒号在两端 / 非 Base64Url 字符集，全部 10102 + field=cursor + 原值回显。 */
    @Test
    void malformedCursorsAreRejectedWithParamError() {
        List<String> bad = List.of(
                "aGVsbG8",                       // 合法 Base64Url 但没有冒号
                "MTIz",                          // "123" 无位点 id
                "Oi41",                          // ":5" 冒号在开头（millis 段空）
                "MTIzOg",                        // "123:" 冒号在结尾（id 段空）
                "!!不是Base64!!",                // 字符集外
                "a+b/c=="                        // 标准 Base64 字符（+ / =）不在 Base64Url 集合内
        );
        for (String cursor : bad) {
            assertThatThrownBy(() -> CursorCodec.decode(cursor))
                    .as("cursor=%s 应被拒", cursor)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(ApiCode.PARAM_FORMAT_ERROR.getCode());
                        assertThat(errors(e)).hasSize(1);
                        ApiError error = errors(e).get(0);
                        assertThat(error.field()).isEqualTo("cursor");
                        assertThat(error.code()).isEqualTo("FORMAT_INVALID");
                        assertThat(error.rejectedValue()).isEqualTo(cursor);
                    });
        }
    }

    /** ApiException.errorDetail 实为 List&lt;ApiError&gt;（强类型化前的过渡签名）。 */
    @SuppressWarnings("unchecked")
    private static List<ApiError> errors(ApiException e) {
        return (List<ApiError>) e.getErrorDetail();
    }

    /** 位点值非法：非数字段、负毫秒、id≤0 —— 杜绝不可信输入拼进 keyset 谓词。 */
    @Test
    void outOfRangePositionsAreRejected() {
        List<String> bad = List.of(
                b64("abc:1"),        // millis 非数字
                b64("1:x"),          // id 非数字
                b64("-1:5"),         // 负毫秒（越界下界）
                b64("1760001000000:0"), // id=0 越界
                b64("1760001000000:-3") // 负 id
        );
        for (String cursor : bad) {
            assertThatThrownBy(() -> CursorCodec.decode(cursor))
                    .as("cursor=%s 应越界拒绝", cursor)
                    .isInstanceOfSatisfying(ApiException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ApiCode.PARAM_FORMAT_ERROR.getCode()));
        }
    }

    /** null / 空白游标也算非法（10102），提示首页应省略 cursor 而不是传空串。 */
    @Test
    void nullOrBlankCursorIsRejected() {
        for (String cursor : new String[] {null, "  ", ""}) {
            assertThatThrownBy(() -> CursorCodec.decode(cursor))
                    .as("cursor='%s' 应拒绝", cursor)
                    .isInstanceOfSatisfying(ApiException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ApiCode.PARAM_FORMAT_ERROR.getCode()));
        }
    }

    /** 负数时间戳经编码链路进来同样被拒（编码侧只产合法位点，负值只能来自伪造）。 */
    @Test
    void negativeTimestampFromCraftedCursorIsRejected() {
        String crafted = b64("-1760001000000:9");
        assertThatThrownBy(() -> CursorCodec.decode(crafted))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ApiCode.PARAM_FORMAT_ERROR.getCode()));
    }

    /** 用 Base64Url 手工构造非标准输入（encode 的合法产物覆盖不到的形态）。 */
    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }
}
