package com.wechat.sync.server.config;

import fun.commons.framework4j.idempotency.IdempotencyProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code Idempotency-Key} 形态归一过滤器（冒烟 P0-1 修复，见 client/smoke/README.md §9.3/§9.6-1）。
 *
 * <p><b>缺陷</b>：framework4j-idempotency 的 {@code IdempotencyInterceptor} 用硬编码正则
 * {@code ^[0-9a-f]{8}-[0-9a-f]{4}-4…-[89ab]…-[0-9a-f]{12}$} 校验键（其 L47-49 / L117），只认
 * <b>带连字符</b>的 UUID v4；客户端发 {@code uuid4().hex}（32 位无连字符）被拒 → HTTP 400 +
 * {@code 10102}。而 {@code PUT /v1/blobs/{sha256}} 不在
 * {@code framework4j.idempotency.path-patterns} 内，hex 键照收——同一个键的两种合法写法在
 * {@code /v1/reports/**} 与 blob 上分道扬镳，纯属路径配置不一致。真机冒烟 A/B 探针：
 * 同 payload，{@code uuid4().hex} → 400/10102，{@code uuid4()} 连字符 → 200。
 *
 * <p><b>为什么选「服务端归一化」而不是改拦截器</b>：①{@code IdempotencyProperties} 没有任何
 * 键格式 / 校验配置键（grep 全模块，仅 enabled/headerName/keyPrefix/ttlSeconds/bodyHashRequired/
 * redisName/pathPatterns/excludePathPatterns 八个键），校验正则是 {@code private static final}；
 * ②framework4j 是本地 install 的 jar，按边界禁止改源码。于是把键<b>归一成框架已接受的标准形态</b>
 * 再交给下游，框架的校验语义（v4 + variant）原样保留，一行框架代码不动。
 *
 * <p><b>归一口径</b>：32 位纯 hex（{@code uuid4().hex}，大小写均可）→ 还原成小写连字符标准形态。
 * hex 本就是 UUID 的合法紧凑写法（RFC 4122 的 32 个十六进制位 = 8-4-4-4-12 去掉连字符），
 * mc-api-spec / API_DESIGN §2.5 的约定是「Idempotency-Key = 客户端 UUID」，未规定排版。
 * 其余取值<b>逐字节原样透传</b>——非法值（乱码、非 v4 的 hex / 连字符 UUID）仍由框架按原规则
 * 拒 {@code 10102}，本过滤器不做任何裁决。
 *
 * <p><b>归一必须在进拦截器之前</b>：框架用键<b>原文</b>拼 Redis key
 * （{@code keyPrefix:requestURI:key}，其 L125），不归一则 hex 与连字符同一逻辑 UUID 两个键，
 * 48h 重放判定失效；归一后两种形态落同一键，重放返回同一响应（48h TTL 见 yml
 * {@code framework4j.idempotency.ttl-seconds=172800}）。同理 body hash 与归一无耦合，键换形态
 * 不影响「同 key 不同 body → 409」的防误用校验。
 *
 * <p>对连字符形态刻意<b>不做</b>大小写归一：已有客户端在用小写连字符，改它的键空间会让部署切换
 * 前后 48h 内的同键重放 miss（本次只放宽 hex，不动已接受形态）。
 *
 * @since 0.1.0
 */
@Slf4j
public class IdempotencyKeyCanonicalFilter implements Filter {

    /** 注册到 servlet 的 URL pattern，与 IdempotencyWiringConfig.URL_PATTERN 同向。 */
    public static final String URL_PATTERN = IdempotencyWiringConfig.URL_PATTERN;

    /** 排在 IdempotencyBodyCacheFilter（HIGHEST_PRECEDENCE + 10）之前，紧跟 gzip 解压之后。 */
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 8;

    /** 32 位纯 hex（无连字符），即 {@code UUID.hex} 紧凑形态。 */
    private static final Pattern HEX_32 = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final String headerName;

    /** header 名取自框架配置（缺省 {@code Idempotency-Key}），不复制常量避免两处漂移。 */
    public IdempotencyKeyCanonicalFilter(IdempotencyProperties properties) {
        this.headerName = properties.getHeaderName();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        String raw = httpRequest.getHeader(headerName);
        String canonical = canonical(raw);
        if (canonical == null || canonical.equals(raw)) {
            // 键缺失 / 无需改写：原请求直通（Filter 层不做任何裁决，链路零开销）
            chain.doFilter(request, response);
            return;
        }
        log.debug("[Idempotency] 键形态归一: {} -> {}", raw, canonical);
        chain.doFilter(new CanonicalKeyRequest(httpRequest, headerName, canonical), response);
    }

    /** 32 位 hex → 小写连字符标准形态；其它值原样返回（校验仍归框架）。 */
    static String canonical(String raw) {
        if (raw == null || !HEX_32.matcher(raw).matches()) {
            return raw;
        }
        UUID uuid = UUID.fromString(raw.substring(0, 8) + '-' + raw.substring(8, 12) + '-'
                + raw.substring(12, 16) + '-' + raw.substring(16, 20) + '-' + raw.substring(20));
        return uuid.version() == 4 ? uuid.toString() : raw;
    }

    /** 只改写幂等键这一个 header，其余请求语义原样。 */
    private static final class CanonicalKeyRequest extends HttpServletRequestWrapper {

        private final String headerName;
        private final String canonicalKey;

        CanonicalKeyRequest(HttpServletRequest request, String headerName, String canonicalKey) {
            super(request);
            this.headerName = headerName;
            this.canonicalKey = canonicalKey;
        }

        @Override
        public String getHeader(String name) {
            return headerName.equalsIgnoreCase(name) ? canonicalKey : super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            if (!headerName.equalsIgnoreCase(name)) {
                return super.getHeaders(name);
            }
            return java.util.Collections.enumeration(java.util.List.of(canonicalKey));
        }
    }
}
