package com.wechat.sync.server.common;

import com.wechat.sync.server.config.IdempotencyWiringConfig;
import com.wechat.sync.server.config.ReportTransportFiltersConfig;
import fun.commons.framework4j.idempotency.IdempotencyBodyCacheFilter;
import fun.commons.framework4j.idempotency.IdempotencyProperties;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.AntPathMatcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 传输层契约回归（真机冒烟缺陷 #2）——<b>纯单元</b>，不起 Spring 上下文：
 * {@code Content-Encoding: gzip} 的请求体必须在过滤器层解压，下游（Controller 的
 * {@code @RequestBody}、幂等拦截器的 body hash）读到的是<b>解压后</b>的原文；
 * 无该头 / 非该值的请求原样透传；且 {@code /v1/blobs/*} 必须在作用域外。
 * 修前全工程无请求体解压处理，gzip 字节被 Jackson 当裸 JSON → 10103。
 *
 * <p>断言分两层：流行为直接驱动 {@link GzipRequestDecompressFilter}；作用域与执行次序落在注册
 * 配置上——该 Filter 无路径感知，{@code /v1/blobs/*} 的豁免由 {@code FilterRegistrationBean} 的
 * urlPatterns 决定，三个配置类的 {@code @Bean} 方法均可无 Spring 上下文直接 new 调用。gzip 也必须
 * 排在 {@code IdempotencyBodyCacheFilter} 之前：后者一进来就 {@code cacheBody()} 整读进堆。
 */
class GzipRequestDecompressFilterTest {

    private static final String REPORTS_URI = "/v1/reports/messages";
    private static final String BLOB_SHA =
            "4b0000000000000000000000000000000000000000000000000000000000004b";
    private static final String BLOB_URI = "/v1/blobs/" + BLOB_SHA;

    private static final byte[] PLAIN =
            "{\"account\":\"wxid_t4gzip_a\",\"batch_ref\":\"t4gzip\",\"chats\":[]}"
                    .getBytes(StandardCharsets.UTF_8);

    private final GzipRequestDecompressFilter filter = new GzipRequestDecompressFilter();

    @Test
    void gzipBodyIsDecompressedForTheDownstreamChain() throws Exception {
        MockHttpServletRequest request = request(REPORTS_URI, "gzip", gzip(PLAIN));

        byte[] downstream = throughFilter(request);

        assertThat(downstream)
                .as("Content-Encoding: gzip 的请求体须在过滤器层解压，下游读到的流等于原文")
                .isEqualTo(PLAIN);
        HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();
        assertThat(wrapped.getContentLengthLong())
                .as("解压后长度不可知：Content-Length 必须置 -1（原值是压缩字节数，按它截断读体会提前 EOF）")
                .isEqualTo(-1L);
    }

    @Test
    void requestWithoutOrWithNonGzipContentEncodingPassesThroughUnwrapped() throws Exception {
        MockHttpServletRequest noHeader = request(REPORTS_URI, null, PLAIN);
        assertThat(throughFilter(noHeader)).as("无 Content-Encoding 须原样透传").isEqualTo(PLAIN);
        assertThat(chain.getRequest()).as("不得包装请求").isSameAs(noHeader);

        MockHttpServletRequest otherEncoding = request(REPORTS_URI, "identity", PLAIN);
        assertThat(throughFilter(otherEncoding))
                .as("Content-Encoding 非 gzip（identity）不得解压——防 header 存在即解压的误判")
                .isEqualTo(PLAIN);
        assertThat(chain.getRequest()).as("非 gzip 编码不得包装请求").isSameAs(otherEncoding);
    }

    @Test
    void gzipDeclaredButBodyNotGzipFallsBackToRawInsteadOfThrowing500() throws Exception {
        MockHttpServletRequest request = request(REPORTS_URI, "gzip", PLAIN);

        assertThat(throughFilter(request))
                .as("声明 gzip 但体不是 gzip：须按原始体放行（下游走既有 10103 信封），"
                        + "不得把 ZipException 从 Filter 抛成无信封 500")
                .isEqualTo(PLAIN);
        HttpServletRequest buffered = (HttpServletRequest) chain.getRequest();
        assertThat(buffered)
                .as("回退分支要判 gzip 必先读流，读过的流无法 isSameAs 原样透传——须包一层 wrapper 回放原始体")
                .isInstanceOf(HttpServletRequestWrapper.class);
        assertThat(buffered.getInputStream().readAllBytes())
                .as("回放字节必须等于原始体（绝不能是被 GZIPInputStream 构造期啃掉头部的残体）")
                .isEqualTo(PLAIN);
        assertThat(buffered.getContentLengthLong())
                .as("回退分支 Content-Length 即原始体真实字节数（与原请求语义一致，不解压分支才置 -1）")
                .isEqualTo(PLAIN.length);
    }

    @Test
    void contentEncodingMatchesCaseInsensitivelyAcrossMultiValueTokens() {
        assertThat(GzipRequestDecompressFilter.isGzipEncoded(request(REPORTS_URI, "GZIP", PLAIN)))
                .as("编码值大小写不敏感").isTrue();
        assertThat(GzipRequestDecompressFilter.isGzipEncoded(request(REPORTS_URI, "x-gzip", PLAIN)))
                .as("兼容 x-gzip 别名").isTrue();
        assertThat(GzipRequestDecompressFilter.isGzipEncoded(request(REPORTS_URI, "gzip, identity", PLAIN)))
                .as("多段编码头按逗号拆分逐段比对").isTrue();
        assertThat(GzipRequestDecompressFilter.isGzipEncoded(request(REPORTS_URI, null, PLAIN))).isFalse();
    }

    @Test
    void registrationPutsGzipAheadOfBodyCacheAndOffTheBlobPath() {
        var gzip = new ReportTransportFiltersConfig().gzipRequestDecompressFilterRegistration();
        var canonical = new ReportTransportFiltersConfig()
                .idempotencyKeyCanonicalFilterRegistration(new IdempotencyProperties());
        var bodyCache = new IdempotencyWiringConfig()
                .idempotencyBodyCacheFilterRegistration(new IdempotencyBodyCacheFilter());

        assertThat(gzip.getFilter()).isInstanceOf(GzipRequestDecompressFilter.class);
        assertThat(gzip.getUrlPatterns()).as("gzip 过滤器必须显式限路 /v1/reports/*")
                .containsExactly("/v1/reports/*");
        assertThat(gzip.getUrlPatterns()).as("禁止裸 @Component 的 /* 缺省（会波及 blob 与查询端点）")
                .doesNotContain("/*", "/v1/blobs/*");

        assertThat(canonical.getUrlPatterns()).as("幂等键归一过滤器与 gzip 同向")
                .containsExactly("/v1/reports/*");
        assertThat(bodyCache.getUrlPatterns()).as("body 缓存过滤器与拦截器路径同向")
                .containsExactly("/v1/reports/*");

        // servlet 前缀映射语义：/v1/reports/* 命中 reports 族、不命中 blob 族
        AntPathMatcher matcher = new AntPathMatcher();
        assertThat(matcher.match("/v1/reports/*", REPORTS_URI))
                .as("pattern 覆盖 reports 族").isTrue();
        assertThat(matcher.match("/v1/reports/*", BLOB_URI))
                .as("/v1/blobs/* 必须在作用域外——blob PUT 是原始字节流，服务端按收到的字节算"
                        + "sha256（10602），解压即损坏；且内容寻址的地址是压缩前的 sha256")
                .isFalse();

        // 次序：解压 → 键归一 → body 缓存（order 数值越小越先执行）
        assertThat(gzip.getOrder()).as("gzip 须先于 body 缓存，缓存进堆的才是解压后字节")
                .isLessThan(bodyCache.getOrder());
        assertThat(canonical.getOrder()).as("键归一须先于 body 缓存（框架按键原文拼 Redis key）")
                .isLessThan(bodyCache.getOrder());
        assertThat(gzip.getOrder()).isLessThan(canonical.getOrder())
                .as("order 与实现常量一致")
                .isEqualTo(GzipRequestDecompressFilter.FILTER_ORDER)
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5);
    }

    private MockFilterChain chain;

    /** 走完过滤器链并返回下游读到的字节；{@link #chain} 保留供包装/透传断言。 */
    private byte[] throughFilter(MockHttpServletRequest request) throws Exception {
        chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("过滤器必须放行请求到下游链").isNotNull();
        byte[] seen = ((HttpServletRequest) chain.getRequest()).getInputStream().readAllBytes();
        assertThat(seen).as("下游流内容（uri=%s, Content-Encoding=%s）",
                request.getRequestURI(), request.getHeader("Content-Encoding")).isNotEmpty();
        return seen;
    }

    /**
     * {@link MockHttpServletRequest} 会缓存 {@code getInputStream()}，而真实容器对缓冲后的 body
     * 每次给一条新流；若共用缓存流，透传/回退分支在 {@code GZIPInputStream} 构造期就把流读尽，
     * 下游只能读到空——那是测试造具的假象而非被测行为。故每次调用重开一条指向同一字节的流。
     */
    private static MockHttpServletRequest request(String uri, String contentEncoding, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public ServletInputStream getInputStream() {
                return new DelegatingServletInputStream(new ByteArrayInputStream(body));
            }
        };
        request.setRequestURI(uri);
        request.setContentType("application/json");
        request.setContent(body);
        if (contentEncoding != null) {
            request.addHeader("Content-Encoding", contentEncoding);
        }
        return request;
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(raw);
        }
        return buffer.toByteArray();
    }
}
