package com.wechat.sync.server.subscription.webhook;

import com.wechat.sync.server.common.WxErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * webhook_url SSRF 校验器（SUBSCRIPTION_DESIGN §5.4 逐条，T10-5）。
 *
 * <p>检查矩阵（违规 → {@link WxErrorCode#WEBHOOK_URL_INVALID}）：
 * <ol>
 *   <li>协议仅 {@code http}/{@code https}；</li>
 *   <li>端口仅 80/443（未写端口按 scheme 缺省视为合规）；</li>
 *   <li><b>IP 字面量</b>落私网/环回/链路本地段拒绝（IPv4：10/8、172.16/12、192.168/16、127/8、
 *       169.254/16、0/8；IPv6：::1、fc00::/7、fe80::/10、未指定 ::，及 IPv4 映射段
 *       ::ffff:0:0/96——后者复用 IPv4 区段判定，堵「::ffff:127.0.0.1」绕行）；</li>
 *   <li>域名解析后<b>逐 IP 复检</b>（{@link InetAddress#getAllByName}，任一 IP 落禁区即拒）。</li>
 * </ol>
 *
 * <p><b>TOCTOU 残余风险登记</b>（§5.4 原文）：本检查的 DNS 解析与 {@code HttpClient} 实际连接
 * 非同刻——极端窗口内攻击者可让域名先解析到公网 IP 过检、再切换 A 记录指向内网
 * （DNS rebinding）。残余面按设计登记不消除（不跟随重定向 + 逐 IP 复检已收窄主通道；
 * 彻底消除需连接级 pin IP，10 QPS 内部系统成本不成比例）。
 *
 * <p>纯函数分层：{@link #checkSyntaxAndLiterals}（协议/端口/字面量，<b>不碰 DNS</b>，单测主入口）
 * 与 {@link #validate}（= 语法字面量 + 解析复检，派发前全量口径）。无状态、全静态。
 */
@Slf4j
public final class WebhookUrlValidator {

    private WebhookUrlValidator() {
    }

    /** 派发前全量校验：语法/字面量 + 域名解析逐 IP 复检。违规抛 10607（带命中规则 reason）。 */
    public static void validate(String url) {
        String host = checkSyntaxAndLiterals(url);
        if (host == null) {
            return;        // URL 是 IP 字面量——已在字面量检查覆盖，无需解析
        }
        checkResolvedHosts(host, url);
    }

    /**
     * 语法 + IP 字面量检查（纯函数，无 DNS）。
     *
     * @return null=URL 是 IP 字面量（字面量检查已定生死）；否则返回主机名（供解析复检）
     * @throws ApiException 10607 协议/端口/字面量违规
     */
    public static String checkSyntaxAndLiterals(String url) {
        URL parsed;
        try {
            parsed = new java.net.URI(url).toURL();          // URI 合规化 → toURL（JDK17 兼容）
        } catch (IllegalArgumentException | MalformedURLException | java.net.URISyntaxException e) {
            throw WxErrorCode.webhookUrlInvalid(url, "URL 无法解析");
        }
        String protocol = parsed.getProtocol() == null ? "" : parsed.getProtocol().toLowerCase();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw WxErrorCode.webhookUrlInvalid(url, "仅允许 http/https 协议，收到 " + protocol);
        }
        int port = parsed.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw WxErrorCode.webhookUrlInvalid(url, "端口仅允许 80/443，收到 " + port);
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw WxErrorCode.webhookUrlInvalid(url, "缺少主机名");
        }
        if (isIpLiteral(host)) {
            checkIpLiteral(host, url);
            return null;
        }
        return host;
    }

    /** 域名解析逐 IP 复检（§5.4 第④条：任一 IP 落禁区即拒；解析失败视同违规）。 */
    public static void checkResolvedHosts(String host, String url) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isForbiddenIp(address.getAddress())) {
                    throw WxErrorCode.webhookUrlInvalid(url,
                            "域名 " + host + " 解析到禁区 IP（SSRF 拦截）");
                }
            }
        } catch (UnknownHostException e) {
            throw WxErrorCode.webhookUrlInvalid(url, "域名无法解析: " + host);
        }
    }

    /** 判定主机名是否为 IP 字面量（IPv4 点分十进制或含冒号的 IPv6；不含 DNS 查询）。 */
    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** IP 字面量检查（IPv4 点分 / IPv6 交给 InetAddress 解析字节后统一走区段判定）。 */
    private static void checkIpLiteral(String host, String url) {
        try {
            InetAddress address = InetAddress.getByName(host);   // 字面量解析，无 DNS
            if (isForbiddenIp(address.getAddress())) {
                throw WxErrorCode.webhookUrlInvalid(url, "IP 字面量落禁区段（SSRF 拦截）: " + host);
            }
        } catch (UnknownHostException e) {
            throw WxErrorCode.webhookUrlInvalid(url, "IP 字面量无法解析: " + host);
        }
    }

    /**
     * 区段判定（字节级，IPv4/IPv6 统一入口）：
     * IPv4 四字节按 §5.4 表；IPv6 判 ::1 / ::（未指定）/ fc00::/7 / fe80::/10，
     * IPv4 映射段 ::ffff:0:0/96 取末四字节复用 IPv4 判定（堵混合写法绕行）。
     */
    private static boolean isForbiddenIp(byte[] addr) {
        if (addr.length == 4) {
            return isForbiddenIpv4(addr[0] & 0xFF, addr[1] & 0xFF);
        }
        if (addr.length != 16) {
            return true;                       // 未知地址族宁可错杀（SSRF 侧安全默认）
        }
        boolean v4Mapped = true;
        for (int i = 0; i < 10; i++) {
            if (addr[i] != 0) {
                v4Mapped = false;
                break;
            }
        }
        if (v4Mapped && (addr[10] & 0xFF) == 0xFF && (addr[11] & 0xFF) == 0xFF) {
            return isForbiddenIpv4(addr[12] & 0xFF, addr[13] & 0xFF);
        }
        if (addr[10] == 0 && addr[11] == 0 && addr[12] == 0 && addr[13] == 0
                && addr[14] == 0 && addr[15] == 0) {
            return true;                       // :: 未指定地址（或 ::1 由下一行覆盖）
        }
        if (addr[15] == 1 && isZeroPrefix(addr, 15)) {
            return true;                       // ::1 环回
        }
        int first = addr[0] & 0xFF;
        int second = addr[1] & 0xFF;
        return (first & 0xFE) == 0xFC          // fc00::/7 唯一本地
                || (first == 0xFE && (second & 0xC0) == 0x80);   // fe80::/10 链路本地
    }

    /** IPv4 两字节级区段判定（§5.4 第③条全表；第三字节供 172.16/12 精确分档）。 */
    private static boolean isForbiddenIpv4(int o1, int o2) {
        return o1 == 0 || o1 == 10 || o1 == 127
                || (o1 == 169 && o2 == 254)
                || (o1 == 172 && o2 >= 16 && o2 <= 31)
                || (o1 == 192 && o2 == 168);
    }

    /** 前 {@code len} 字节全零（供 ::1 判定）。 */
    private static boolean isZeroPrefix(byte[] addr, int len) {
        for (int i = 0; i < len; i++) {
            if (addr[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
