package com.wechat.sync.server.subscription.webhook;

import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * WebhookUrlValidator 单测（SUBSCRIPTION_DESIGN §5.4 SSRF 五条中的可离线三条：协议 / 端口 / IP 字面量）。
 *
 * <p>落盘 API（对齐后）：{@code validate(String)} 静态 void、违规抛 {@link ApiException}
 * （业务码 10607 WEBHOOK_URL_INVALID，数值断言）；分层入口 {@code checkSyntaxAndLiterals(String)}
 * 纯语法/字面量（不碰 DNS），{@code validate} = 该层 + {@code checkResolvedHosts} 解析复检。
 * 本文件断言一律走 {@code validate}（全量口径），分层本身由派发链路（dispatcher 每次投递前全量调）
 * 与 T10-8 IT 覆盖。
 *
 * <p><b>单测不碰真 DNS</b>：公网 hostname 的「解析后逐 IP 复检 + 放行」用例不做（hostname 一旦
 * 在 validate 内被解析，example.com 会触发真 DNS）——公网过例归 T10-8 IT（内置 stub webhook 端到端）。
 * 这里过例一律用<b>公网 IP 字面量</b>（93.184.216.34 = example.com 历史 A 记录，字面量分支
 * 零解析直接放行）；hostname 侧只做 {@code localhost} → 127.0.0.1 的<b>拒</b>例（真解析但无外网）。
 */
class WebhookUrlValidatorTest {

    // ------------------------------------------------------------------ 助手

    /** 违规必抛 ApiException。 */
    private static void assertRejected(String url, String because) {
        assertThatThrownBy(() -> WebhookUrlValidator.validate(url))
                .as(because)
                .isInstanceOf(ApiException.class);
    }

    /** 合规必不抛。 */
    private static void assertAccepted(String url, String because) {
        assertThatCode(() -> WebhookUrlValidator.validate(url))
                .as(because)
                .doesNotThrowAnyException();
    }

    /** 业务码数值断言（DeliveryServiceTest.codeOf 同款收敛助手）。 */
    private static int codeOf(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e.getCode();
        }
        return fail("未抛 ApiException");
    }

    // ------------------------------------------------------------------ A. 拒绝矩阵（§5.4 ①②③⑤）

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "http://127.0.0.1/x",           // 环回段
            "http://10.0.0.1/x",            // RFC1918 /8
            "http://172.16.0.1/x",          // RFC1918 /12
            "http://192.168.1.1/x",         // RFC1918 /16
            "http://169.254.1.1/x",         // 链路本地
            "http://0.0.0.0/x",             // 未指定地址
            "http://[::1]/x",               // IPv6 环回
            "http://[fd00::1]/x",           // IPv6 ULA
            "http://[fe80::1]/x",           // IPv6 链路本地
            "ftp://example.com/x",          // 协议白名单外（仅 http/https）
            "http://example.com:8443/x",    // 端口白名单外（仅 80/443）
            "http://93.184.216.34:8080/x",  // 公网 IP 也拦非白名单端口（端口检查先于解析）
    })
    void rejectsPrivateLoopbackLinkLocalAndNonHttpOrBadPort(String url) {
        assertRejected(url, "SSRF 五条：私网/环回/链路本地 IP、非 http(s) 协议、非 80/443 端口一律 10607");
    }

    @ParameterizedTest(name = "[{index}] 空/畸形 URL：{0}")
    @ValueSource(strings = {"", "   ", "not-a-url", "http://", "/relative/path"})
    void rejectsBlankOrMalformedUrl(String url) {
        assertRejected(url, "空 / 畸形 / 相对路径不是合法 webhook_url");
    }

    @Test
    void rejectionCarriesBusinessCode10607() {
        int code = codeOf(() -> WebhookUrlValidator.validate("http://127.0.0.1/x"));
        assertThat(code).as("违规 → 10607 WEBHOOK_URL_INVALID（数值断言）").isEqualTo(10607);
    }

    // ------------------------------------------------------------------ A. 环回解析拒（真解析、零外网）

    @Test
    void rejectsHostnameResolvingToLoopback() {
        assertRejected("http://localhost/x", "localhost 解析为 127.0.0.1 → 逐 IP 复检必须拒（DNS rebinding 防线的可见面）");
    }

    // ------------------------------------------------------------------ A. 放行（公网 IP 字面量，零 DNS）

    @Test
    void acceptsPublicIpLiteralOnDefaultAndHttpsPorts() {
        assertAccepted("http://93.184.216.34/x", "公网 IP 字面量 + 缺省 80 端口 → 放行（字面量分支零 DNS）");
        assertAccepted("https://93.184.216.34/x", "公网 IP 字面量 + https 缺省 443 → 放行");
    }
}
