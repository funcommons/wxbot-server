package com.wechat.sync.server.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * 请求体 gzip 解压过滤器（冒烟 P0-2 修复，见 client/smoke/README.md §9.3/§9.6-2）。
 *
 * <p><b>缺陷</b>：客户端按契约对 {@code /v1/reports/**} 发 {@code Content-Encoding: gzip} 的请求体
 * （API_DESIGN §2.5 Header 表），服务端全工程（src + pom）没有任何请求体解压处理 → gzip 字节
 * 被 Jackson 当裸 JSON 解析 → {@code HttpMessageNotReadableException} → 信封 {@code 10103}
 * 「请求体格式错误或缺失」。真机冒烟 A/B 探针：同一 payload，gzip → 10103，plain JSON → 10100。
 *
 * <p><b>机制</b>：仅当 {@code Content-Encoding} 含 {@code gzip}（兼容 {@code x-gzip}）时，先把
 * 原始体整读进内存，用 {@link GZIPInputStream}（JDK 标准库，零新依赖）解压出字节，再以
 * {@link HttpServletRequestWrapper} 把解压结果回放给下游（幂等 body 缓存、Jackson
 * {@code @RequestBody}）；{@code Content-Encoding} 缺省或为其它值（identity / deflate / br …）时
 * 请求<b>原样透传</b>，行为与修复前完全一致。声明了 gzip 但体不是合法 gzip（含空体）时，把
 * <b>原始字节无损回放</b>给下游，由下游按既有裸 JSON 路径自行裁决——绝不把 {@code ZipException}
 * 从 Filter 抛成无信封 500，也绝不回传被啃掉头部的残体（见 doFilter 内注释）。
 *
 * <p><b>为什么先整读再解压</b>：{@code GZIPInputStream} 构造期就向前读（gzip 头 10 字节起 +
 * Inflater 内部缓冲），直接架在原始流上会让「回退透传」分支丢失头部字节，无声损坏请求体。
 * 整读不新增内存暴露面：本路径紧随其后的 framework4j {@code IdempotencyBodyCacheFilter} 本来
 * 就把整个 body 读进堆做幂等 body hash。
 *
 * <p><b>解压分支 Content-Length 置 -1</b>：请求头里的 Content-Length 是<b>压缩后</b>字节数，
 * 不能拿来描述解压流；不置 -1 的话，任何按该值截断读体的下游都会提前 EOF。
 *
 * <p><b>注册口径（见 ReportTransportFiltersConfig）</b>：只注册到 {@code /v1/reports/*}，且 order
 * 排在 framework4j {@code IdempotencyBodyCacheFilter}（{@code HIGHEST_PRECEDENCE + 10}）之前——
 * 该过滤器会 {@code cacheBody()} 把整读进堆，若不先解压，缓存的就是压缩字节，幂等 body hash 与
 * Controller 解析全部错位。{@code /v1/blobs/*} <b>绝不进</b>本过滤器：blob PUT 是原始字节流，
 * 服务端按收到的字节算 sha256 校验（API_DESIGN §2.3 10602），解压即损坏，且内容寻址的地址就是
 * 压缩前的 sha256。
 *
 * <p><b>响应侧不处理</b>：契约未约定响应压缩，本服务响应不压缩，无需对应 {@code Accept-Encoding} 逻辑。
 *
 * @since 0.1.0
 */
@Slf4j
public class GzipRequestDecompressFilter implements Filter {

    /** 本过滤器包装标记（request attribute），防止同请求被二次包装（多注册 / 重入）。 */
    private static final String WRAPPED_ATTR = GzipRequestDecompressFilter.class.getName() + ".WRAPPED";

    /** 注册到 servlet 的 URL pattern（必须与 IdempotencyWiringConfig.URL_PATTERN 同向，见类注释）。 */
    public static final String URL_PATTERN = "/v1/reports/*";

    /** 排在 IdempotencyBodyCacheFilter（HIGHEST_PRECEDENCE + 10）之前：解压必须先于 body 缓存。 */
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || Boolean.TRUE.equals(request.getAttribute(WRAPPED_ATTR))
                || !isGzipEncoded(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }
        // 先把原始体读进内存再尝试解压，绝不能让 GZIPInputStream 直接架在原始流上：
        // 它构造期就向前读（gzip 头 10 字节起，Inflater 内部缓冲最多 512 字节），一旦声明 gzip
        // 而体并不是 gzip，回退分支若仍透传原请求，下游拿到的是被啃掉头部的残体 → 无声的数据丢失。
        // 这里的整读不新增内存暴露面：本路径紧随其后的 framework4j IdempotencyBodyCacheFilter
        // （HIGHEST_PRECEDENCE + 10）本来就把整个 body 读进堆做幂等 body hash，量级相同。
        final byte[] raw = httpRequest.getInputStream().readAllBytes();
        byte[] decompressed;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            decompressed = gzip.readAllBytes();
        } catch (IOException e) {
            // 声明 gzip 但体不是合法 gzip（含空体）：原始字节无损放行，下游按既有裸 JSON 路径自行裁决
            // （合法 JSON 照常处理，非法则既有 10103 信封）——绝不在 Filter 层抛 ZipException 造无信封 500
            log.warn("[gzip] Content-Encoding: gzip 但请求体不是合法 gzip 流（{} 字节），按原始体透传: {}",
                    raw.length, e.getMessage());
            chain.doFilter(new BufferedBodyRequest(httpRequest, raw), response);
            return;
        }
        request.setAttribute(WRAPPED_ATTR, Boolean.TRUE);
        chain.doFilter(new DecompressedBodyRequest(httpRequest, decompressed), response);
    }

    /** 多值 / 多段编码头（如 "gzip, identity"）按逗号拆分逐段比对，大小写不敏感。 */
    static boolean isGzipEncoded(HttpServletRequest request) {
        String encoding = request.getHeader("Content-Encoding");
        if (encoding == null || encoding.isBlank()) {
            return false;
        }
        for (String token : encoding.split(",")) {
            String value = token.trim();
            if ("gzip".equalsIgnoreCase(value) || "x-gzip".equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** 解压分支：Content-Length 头是压缩字节数，对下游必须表现为未知（-1），按读尽为准。 */
    private static final class DecompressedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        DecompressedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new BytesServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }

    /** 回退分支：原始字节原样回放（长度即真实字节数，与原请求 Content-Length 语义一致）。 */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new BytesServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    /** byte[] → ServletInputStream（阻塞读，读尽置 finished）。 */
    private static final class BytesServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream in;

        BytesServletInputStream(byte[] body) {
            this.in = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("阻塞式解压读不支持 ReadListener");
        }
    }
}
