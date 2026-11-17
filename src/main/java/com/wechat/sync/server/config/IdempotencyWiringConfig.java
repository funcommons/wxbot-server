package com.wechat.sync.server.config;

import fun.commons.framework4j.idempotency.IdempotencyBodyCacheFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * framework4j-idempotency 的 body 缓存过滤器接线（T7，修 T5 记录的 P1 OOM 风险）。
 *
 * <p><b>问题</b>：框架把 {@link IdempotencyBodyCacheFilter} 以普通 {@code @Bean} 注册，
 * Spring Boot 对裸 Filter Bean 的缺省 URL pattern 是 {@code /*}——与本服务的拦截器路径
 * {@code framework4j.idempotency.path-patterns=/v1/reports/**} 并不对齐。该过滤器
 * {@code cacheBody()} 会把非 multipart 请求体<b>整读进堆</b>（CachedBodyRequestWrapper.readAllBytes），
 * 而 PUT /v1/blobs/{sha256} 上限 512MB 且是 application/octet-stream（非 multipart，不触发
 * {@code shouldNotFilter}），等于每个并发上传都有一次 ≥512MB 的堆峰值，OOM 风险真实存在；
 * 且该路径不进幂等拦截器，缓存的 body 没有任何消费方。
 *
 * <p><b>做法</b>：用 {@link FilterRegistrationBean} 把<b>框架创建的那个 Filter Bean</b> 重新注册到
 * {@code /v1/reports/*}（必须注入既有 Bean 而不是 new 一个——框架 Bean 是
 * {@code @ConditionalOnMissingBean}，自建实例会让框架再创建一份，裸 Bean 仍按 {@code /*} 自动注册）。
 * Spring Boot 的 {@code ServletContextInitializerBeans} 会先收集注册 Bean 并把 Filter 实例记入
 * seen，随后对裸 Filter Bean 的适配注册按 seen 去重，因此<b>不会双重注册</b>。
 * order 沿用框架类上的 {@code @Order(HIGHEST_PRECEDENCE + 10)}，维持既有过滤器相对次序。
 *
 * <p><b>与拦截器的一致性约束</b>：拦截器对命中路径做 body hash（bodyHashRequired=true），
 * 若过滤器未覆盖该路径会走 fail-secure 分支抛 IOException → 500(10001)，所以<b>这两个 pattern
 * 必须同向修改</b>：改 {@code framework4j.idempotency.path-patterns} 时同步改这里的
 * {@link #URL_PATTERNS}。
 *
 * <p><b>blob PUT 不需要 Idempotency-Key</b>：内容寻址（sha256 即地址）+ append-only，重复 PUT
 * 在 wx_blob 查重处幂等返回 {@code exists}，天然幂等；故 {@code /v1/blobs/**} 不进 pattern →
 * 不进 body 堆缓存，512MB 字节全程流式读盘。
 *
 * <p><b>上游过滤器</b>（{@link ReportTransportFiltersConfig}，{@code +5} gzip 解压、{@code +8}
 * Idempotency-Key hex 归一）排在本过滤器（{@code +10}）之前：请求体必须先解压、幂等键必须先归一，
 * 本过滤器缓存的才是下游该看到的字节。
 */
@Configuration
public class IdempotencyWiringConfig {

    /** 与 yml {@code framework4j.idempotency.path-patterns}（/v1/reports/**）保持同向的 Servlet 前缀映射。 */
    public static final String URL_PATTERN = "/v1/reports/*";

    /** 框架类上的 @Order 值，重注册时显式回填以保留相对次序。 */
    private static final int FRAMEWORK_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    public FilterRegistrationBean<IdempotencyBodyCacheFilter> idempotencyBodyCacheFilterRegistration(
            IdempotencyBodyCacheFilter idempotencyBodyCacheFilter) {
        FilterRegistrationBean<IdempotencyBodyCacheFilter> registration =
                new FilterRegistrationBean<>(idempotencyBodyCacheFilter);
        registration.addUrlPatterns(URL_PATTERN);
        registration.setOrder(FRAMEWORK_FILTER_ORDER);
        registration.setName("idempotencyBodyCacheFilter");
        return registration;
    }
}
