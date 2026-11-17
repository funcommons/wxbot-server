package com.wechat.sync.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.ratelimit.config.RateLimitProperties;
import fun.commons.framework4j.ratelimit.service.RateLimitService;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Map;

/**
 * API_DESIGN §9 严格档限流（T7 接线）。
 *
 * <p><b>为什么不用 {@code @RateLimit} 注解</b>：本任务的边界是不改 T1~T6 的 Java 文件，而
 * §9 的四档配额（reports/messages 30、reports/relations 6、blob PUT 60、其余 120）必须分别
 * 落在不同任务的 Controller 上；注解只能加在那些文件里，故改在 {@code config/} 新增拦截器。
 *
 * <p><b>用的仍是框架真实机制</b>：{@link RateLimitService#tryAcquire}（framework4j-rate-limit 的
 * Lua 滑动窗口，Redis 故障时框架内建放行兜底）。框架的 {@code RateLimitInterceptor} 继续挂在
 * {@code /**} 上提供缺省档（yml {@code default-limit: 120} = §9 查询档），本拦截器只在配额偏离
 * 缺省的路径上叠加自己的档位——既有收紧（reports 30/6、register 6、blob PUT 60）也有放宽
 * （订阅 POLL 域前缀档 600，SUBSCRIPTION_DESIGN §6 v1.1）；被限流响应与框架同款：HTTP 429 +
 * 信封 code 10500 + {@code Retry-After} / {@code X-RateLimit-Limit|Remaining|Reset}。
 *
 * <p><b>维度偏差（已记 PROGRESS）</b>：框架 scope {@code user} 只认 {@code X-User-Id} 请求头
 * （RateLimitKeyResolver 源码；javadoc 说取 TokenContext uid，1.10.0 实现<b>没有</b>），
 * {@code @RateLimit.key} 的 SpEL 在 1.10.0 的拦截器里<b>未被消费</b>——「每账号 / 每令牌」维度
 * 框架不可用，取最近似维度<b>客户端 IP</b>（XFF 首段 → X-Real-IP → remoteAddr，与
 * RateLimitKeyResolver 同序）。blob PUT 的 key 用固定桶 {@code /v1/blobs/} 而非 requestURI：
 * 框架 key 含 URI，内容寻址下每个 sha256 各一桶，60/min 形同虚设。
 *
 * <p><b>豁免口径与框架一致</b>：复用 {@code framework4j.rate-limit.whitelist-ips}（缺省含
 * 127.0.0.1/::1，本机自测与集成测试不消耗配额；生产 profile 置空才会真正计数）。
 */
@Slf4j
@Configuration
public class RateLimitQuotaConfig implements WebMvcConfigurer {

    /**
     * 每 60 秒窗口的固定配额（API_DESIGN §9 + TASK_DISPATCH §2.1 + SUBSCRIPTION_DESIGN §6 v1.1）：
     * <b>列配额偏离缺省的路径，含放宽档</b>——精确匹配项既有收紧（reports 30/6、register 6）也有
     * 放宽（订阅 POLL 域走前缀档 600，见 {@link #SUBSCRIPTION_PER_MINUTE}）。
     */
    private static final Map<String, Integer> PER_MINUTE = Map.of(
            "/v1/reports/messages", 30,
            "/v1/reports/relations", 6,
            // register 无鉴权（一次性码即凭证）：6/min/IP 防在线爆破 code_hash（256 位熵 + 哈希比对，
            // 6/min 下穷举不可行；缺省 120/min 对无鉴权端点过宽）
            "/v1/auth/register", 6);

    private static final int BLOB_PUT_PER_MINUTE = 60;
    /**
     * 订阅 POLL 域配额（§6 v1.1 三件套之二）：600/min/<b>IP</b>——高于缺省 120（消费者按 SQS 式
     * 长轮询拉取，50/次 × 1 pull/s 即 50 msg/s，120/min 会把拉取掐死）；多消费者共享出口 IP 时
     * 配额共享，按 token_hash 维度分桶登记为已知限制（P2）。
     */
    private static final int SUBSCRIPTION_PER_MINUTE = 600;
    /** 订阅域固定桶前缀（同 blob PUT 口径——按 URI 分桶则每个订阅 id 各一桶，配额形同虚设）。 */
    private static final String SUBSCRIPTION_BUCKET = "/v1/subscriptions/";
    private static final long WINDOW_MS = 60_000L;

    private final ObjectProvider<RateLimitService> rateLimitService;
    private final ObjectProvider<RateLimitProperties> rateLimitProperties;
    private final ObjectMapper objectMapper;
    private final String mdcKey;

    public RateLimitQuotaConfig(ObjectProvider<RateLimitService> rateLimitService,
                                ObjectProvider<RateLimitProperties> rateLimitProperties,
                                ObjectMapper objectMapper,
                                @Value("${framework4j.api.trace.mdc-key:traceId}") String mdcKey) {
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
        this.objectMapper = objectMapper;
        this.mdcKey = mdcKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimitService service = rateLimitService.getIfAvailable();
        RateLimitProperties properties = rateLimitProperties.getIfAvailable();
        if (service == null || properties == null) {
            log.warn("[RateLimitQuota] framework4j-rate-limit 未启用（无 RateLimitService/Properties），§9 配额不生效");
            return;
        }
        registry.addInterceptor(new QuotaInterceptor(service, properties, objectMapper, mdcKey))
                // /v1/blobs/* 同时覆盖 GET 下载（拦截器内按 method 放行给缺省档）；
                // /v1/subscriptions/** 是订阅 POLL 域（§6 v1.1：whitelist-paths 放行框架缺省档后
                // 由本拦截器按前缀档 600 计数——三件套之二在此，之一在 yml whitelist-paths）
                .addPathPatterns("/v1/reports/messages", "/v1/reports/relations", "/v1/blobs/*",
                        "/v1/subscriptions/**");
    }

    /** §9 严格档拦截器：只做配额裁决，不碰业务。 */
    static class QuotaInterceptor implements HandlerInterceptor {

        private final RateLimitService rateLimitService;
        private final RateLimitProperties properties;
        private final ObjectMapper objectMapper;
        private final String mdcKey;

        QuotaInterceptor(RateLimitService rateLimitService, RateLimitProperties properties,
                         ObjectMapper objectMapper, String mdcKey) {
            this.rateLimitService = rateLimitService;
            this.properties = properties;
            this.objectMapper = objectMapper;
            this.mdcKey = mdcKey;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            if (!(handler instanceof HandlerMethod)) {
                return true;
            }
            Integer limit = quotaOf(request);
            if (limit == null) {
                return true;
            }
            String ip = clientIp(request);
            if (properties.getWhitelistIps() != null && properties.getWhitelistIps().contains(ip)) {
                return true;
            }
            String key = properties.getKeyPrefix() + ":ip:" + ip + ":" + bucketOf(request);
            RateLimitService.AcquireResult result = rateLimitService.tryAcquire(key, limit, WINDOW_MS);
            return result.allowed() || writeTooManyRequests(response, result);
        }

        /** 配额路径 → 次数上限；其余（blob GET / admin 管理端等）→ null，放行给框架缺省档 120/min。 */
        private Integer quotaOf(HttpServletRequest request) {
            if ("PUT".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().startsWith("/v1/blobs/")) {
                return BLOB_PUT_PER_MINUTE;
            }
            if (request.getRequestURI().startsWith(SUBSCRIPTION_BUCKET)) {
                return SUBSCRIPTION_PER_MINUTE;      // 订阅 POLL 域前缀档（§6 v1.1，三件套之二）
            }
            return PER_MINUTE.get(request.getRequestURI());
        }

        /**
         * blob PUT 所有 sha256 共用一个桶（内容寻址下按 URI 分桶等于不限）、reports 按 URI；
         * 订阅域共用固定前缀桶 {@code /v1/subscriptions/}（§6 v1.1——PULL/ack/nack 的 id 各异，
         * 按 URI 分桶则每个订阅各一桶，600/min 形同虚设）。
         */
        private String bucketOf(HttpServletRequest request) {
            String uri = request.getRequestURI();
            if (uri.startsWith("/v1/blobs/")) {
                return "/v1/blobs/";
            }
            return uri.startsWith(SUBSCRIPTION_BUCKET) ? SUBSCRIPTION_BUCKET : uri;
        }

        /** XFF 首段 → X-Real-IP → remoteAddr（与框架 RateLimitKeyResolver.resolveIp 同序）。 */
        private String clientIp(HttpServletRequest request) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                String ip = comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp;
            }
            return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
        }

        /** 429 + 信封 10500 + 四个限流响应头（与框架 RateLimitInterceptor.writeTooManyRequests 同款）。 */
        private boolean writeTooManyRequests(HttpServletResponse response,
                                             RateLimitService.AcquireResult result) throws IOException {
            if (properties.isIncludeHeaders()) {
                response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
                response.setHeader("X-RateLimit-Remaining", "0");
                response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetAtMs() / 1000));
                response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            }
            String traceId = MDC.get(mdcKey);
            if (traceId != null && !traceId.isBlank()) {
                response.setHeader("X-Trace-Id", traceId);
            }
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(ApiCode.TOO_MANY_REQUESTS,
                    "请求过于频繁，请 " + result.retryAfterSeconds() + " 秒后重试")));
            return false;
        }
    }
}
