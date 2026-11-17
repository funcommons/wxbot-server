package com.wechat.sync.server.config;

import com.wechat.sync.server.common.GzipRequestDecompressFilter;
import fun.commons.framework4j.idempotency.IdempotencyBodyCacheFilter;
import fun.commons.framework4j.idempotency.IdempotencyProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code /v1/reports/*} 上、排在 framework4j {@link IdempotencyBodyCacheFilter} 之前的两个
 * 请求级过滤器接线（真机冒烟 P0-1 / P0-2 修复，证据见 client/smoke/README.md §9.3）。
 *
 * <p><b>为什么这两个过滤器必须挤在链首</b>：{@link IdempotencyBodyCacheFilter}（order
 * {@code HIGHEST_PRECEDENCE + 10}，注册见 {@link IdempotencyWiringConfig}）一进来就
 * {@code cacheBody()} 把请求体整读进堆，供幂等拦截器做 body hash、再给 Controller 解析。
 * 因此「把请求体改成下游该看到的样子」的工作必须发生在它<b>之前</b>：
 * <ol>
 *   <li>{@link GzipRequestDecompressFilter}（{@code + 5}）：{@code Content-Encoding: gzip}
 *       请求体在此解压，否则缓存进堆的是压缩字节 → Jackson 当裸 JSON → {@code 10103}；</li>
 *   <li>{@link IdempotencyKeyCanonicalFilter}（{@code + 8}）：{@code Idempotency-Key}
 *       hex 形态在此归一，否则框架拦截器按键原文校验并拼 Redis key → hex 被拒 {@code 10102}，
 *       且同一逻辑键两种形态落两个 Redis 键。</li>
 * </ol>
 *
 * <p><b>URL pattern 一律 {@code /v1/reports/*}</b>（Servlet 前缀映射）：与
 * {@code framework4j.idempotency.path-patterns} / {@link IdempotencyWiringConfig#URL_PATTERN}
 * 三者同向，缺一个就破坏「拦截器命中路径必须被 body 缓存 Filter 覆盖」的一致性约束
 * （见 {@link IdempotencyWiringConfig} 类注释）。{@code /v1/blobs/**} <b>刻意不进</b>：
 * PUT 是原始字节流，服务端按收到的字节算 sha256（10602），解压即损坏；也避免 512MB
 * octet-stream 被读进堆（OOM，见 {@link IdempotencyWiringConfig}）。
 *
 * <p><b>不用裸 {@code @Component} 注册</b>：Spring Boot 对裸 Filter Bean 的缺省 URL pattern 是
 * {@code /*}，会波及 blob 与查询端点——必须走 {@link FilterRegistrationBean} 显式限路。
 *
 * @since 0.1.0
 */
@Configuration
public class ReportTransportFiltersConfig {

    /** {@code Content-Encoding: gzip} 请求体解压，最先于 body 缓存。 */
    @Bean
    public FilterRegistrationBean<GzipRequestDecompressFilter> gzipRequestDecompressFilterRegistration() {
        FilterRegistrationBean<GzipRequestDecompressFilter> registration =
                new FilterRegistrationBean<>(new GzipRequestDecompressFilter());
        registration.addUrlPatterns(GzipRequestDecompressFilter.URL_PATTERN);
        registration.setOrder(GzipRequestDecompressFilter.FILTER_ORDER);
        registration.setName("gzipRequestDecompressFilter");
        return registration;
    }

    /** {@code Idempotency-Key} hex → 连字符归一，先于框架幂等拦截器（同请求在 MVC 链更早执行）。 */
    @Bean
    public FilterRegistrationBean<IdempotencyKeyCanonicalFilter> idempotencyKeyCanonicalFilterRegistration(
            IdempotencyProperties idempotencyProperties) {
        FilterRegistrationBean<IdempotencyKeyCanonicalFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyKeyCanonicalFilter(idempotencyProperties));
        registration.addUrlPatterns(IdempotencyKeyCanonicalFilter.URL_PATTERN);
        registration.setOrder(IdempotencyKeyCanonicalFilter.FILTER_ORDER);
        registration.setName("idempotencyKeyCanonicalFilter");
        return registration;
    }
}
