package com.wechat.sync.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.ratelimit.config.RateLimitProperties;
import fun.commons.framework4j.ratelimit.service.RateLimitService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 订阅域 POLL 配额三件套的拦截器逻辑单测（SUBSCRIPTION_DESIGN §6 v1.1——v1 写法三重矛盾：
 * PER_MINUTE 精确 URI 匹配前缀桶永不命中 / 600>120 与 javadoc「仅收紧」矛盾 / 框架缺省 120 仍生效）。
 * v1.1 落点：QuotaInterceptor 加 <b>前缀分支</b>——URI 以 {@code /v1/subscriptions/} 开头 → 配额
 * <b>600</b>、桶用<b>固定前缀</b> {@code /v1/subscriptions/}（非按订阅 id 分桶，否则配额形同虚设）。
 *
 * <p>框架侧 {@code whitelist-paths: [/v1/subscriptions/**]} 的消费在框架 RateLimitInterceptor
 * （缺省 120 档放行订阅域）——不归本测试，归 T10-8 IT / 上下文加载验证。本测试只锚自建拦截器：
 * 前缀档命中与桶名、旧三档回归、白名单 IP 放行、429 信封形态。
 *
 * <p>限流维度 = 客户端 IP（本配置类 javadoc 既定偏差）；测试用非白名单 IP（203.0.113.9，TEST-NET-3）
 * 计数，豁免用例显式走白名单值。
 */
@ExtendWith(MockitoExtension.class)
class QuotaConfigTest {

    private static final String COUNTING_IP = "203.0.113.9";

    @Mock private RateLimitService rateLimitService;

    private RateLimitQuotaConfig.QuotaInterceptor interceptor;
    private RateLimitProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setKeyPrefix("wxsync");
        properties.setIncludeHeaders(true);
        properties.setWhitelistIps(List.of("127.0.0.1", "::1"));
        interceptor = new RateLimitQuotaConfig.QuotaInterceptor(
                rateLimitService, properties, objectMapper, "traceId");
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("traceId");
    }

    /** 核心锚（v1.1 三重矛盾之首）：订阅前缀档 600/min，桶 = 固定 {@code /v1/subscriptions/} 而非完整 URI。 */
    @Test
    void subscriptionPrefixRoutesGet600WithFixedBucketKey() throws Exception {
        given(rateLimitService.tryAcquire(anyString(), anyInt(), anyLong()))
                .willReturn(allowed(600));

        boolean pass = interceptor.preHandle(
                get("/v1/subscriptions/9/messages", "GET"), response(), handler());

        assertThat(pass).as("配额内放行").isTrue();
        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Integer> limit = org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.ArgumentCaptor<Long> window = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(rateLimitService).tryAcquire(key.capture(), limit.capture(), window.capture());
        assertThat(key.getValue()).as("桶名固定前缀（按 id 分桶 = 配额形同虚设，§6 v1.1）")
                .isEqualTo("wxsync:ip:" + COUNTING_IP + ":/v1/subscriptions/");
        assertThat(limit.getValue()).as("订阅域放宽档 600/min").isEqualTo(600);
        assertThat(window.getValue()).as("窗口 60s").isEqualTo(60_000L);
    }

    /** 不同订阅 id 的 messages/ack 命中同一只桶（前缀桶的语义本体）。 */
    @Test
    void differentSubscriptionIdsShareOneBucket() throws Exception {
        given(rateLimitService.tryAcquire(anyString(), anyInt(), anyLong())).willReturn(allowed(600));

        interceptor.preHandle(get("/v1/subscriptions/9/messages", "GET"), response(), handler());
        interceptor.preHandle(get("/v1/subscriptions/8/ack", "POST"), response(), handler());

        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimitService, org.mockito.Mockito.times(2)).tryAcquire(key.capture(), anyInt(), anyLong());
        assertThat(key.getAllValues().get(0)).isEqualTo(key.getAllValues().get(1));
    }

    /** admin 管理（/v1/consumers）与其它非订阅路径不进前缀桶——放行给框架缺省 120 档，零自建配额交互。 */
    @Test
    void adminAndOtherPathsStayOnDefaultTierWithoutSelfQuotaInteraction() throws Exception {
        assertThat(interceptor.preHandle(get("/v1/consumers", "POST"), response(), handler())).isTrue();
        assertThat(interceptor.preHandle(get("/v1/accounts", "GET"), response(), handler())).isTrue();
        assertThat(interceptor.preHandle(get("/v1/tasks", "GET"), response(), handler())).isTrue();
        verifyNoInteractions(rateLimitService);
    }

    /** 旧三档回归锚：reports/messages 30、reports/relations 6、blob PUT 60（桶固定 /v1/blobs/）。 */
    @Test
    void legacyTiersRemainUnchanged() throws Exception {
        given(rateLimitService.tryAcquire(anyString(), anyInt(), anyLong())).willReturn(allowed(30));
        interceptor.preHandle(get("/v1/reports/messages", "POST"), response(), handler());
        interceptor.preHandle(get("/v1/reports/relations", "POST"), response(), handler());
        interceptor.preHandle(get("/v1/blobs/abc123sha", "PUT"), response(), handler());

        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Integer> limit = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(rateLimitService, org.mockito.Mockito.times(3))
                .tryAcquire(key.capture(), limit.capture(), anyLong());

        assertThat(limit.getAllValues()).as("旧三档配额不变（§9）：30/6/60").containsExactly(30, 6, 60);
        assertThat(key.getAllValues().get(0))
                .isEqualTo("wxsync:ip:" + COUNTING_IP + ":/v1/reports/messages");
        assertThat(key.getAllValues().get(1))
                .isEqualTo("wxsync:ip:" + COUNTING_IP + ":/v1/reports/relations");
        assertThat(key.getAllValues().get(2)).as("blob PUT 桶固定（内容寻址按 URI 分桶等于不限）")
                .isEqualTo("wxsync:ip:" + COUNTING_IP + ":/v1/blobs/");
    }

    /** 白名单 IP（缺省含 127.0.0.1/::1，本机自测豁免）不消耗配额——直接放行。 */
    @Test
    void whitelistedIpBypassesSubscriptionQuota() throws Exception {
        assertThat(interceptor.preHandle(getFrom("/v1/subscriptions/9/messages", "GET", "127.0.0.1"),
                response(), handler())).isTrue();
        verifyNoInteractions(rateLimitService);
    }

    /** 超限 → 返回 false + HTTP 429 + 信封 code 10500 + Retry-After/X-RateLimit-Limit 头 + X-Trace-Id 回显。 */
    @Test
    void blockedSubscriptionRequestWrites429EnvelopeWithHeaders() throws Exception {
        long resetAt = System.currentTimeMillis() + 120_000L;
        given(rateLimitService.tryAcquire(anyString(), anyInt(), anyLong()))
                .willReturn(new RateLimitService.AcquireResult(false, 600, 600, resetAt));

        MockHttpServletRequest request = get("/v1/subscriptions/9/messages", "GET");
        MockHttpServletResponse response = response();
        MDC.put("traceId", "t-abc-123");

        boolean pass = interceptor.preHandle(request, response, handler());

        assertThat(pass).as("超限拦截").isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("\"code\":10500");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("600");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("X-Trace-Id")).as("trace_id 双通道：头回显").isEqualTo("t-abc-123");
        assertThat(Long.parseLong(response.getHeader("Retry-After")))
                .as("Retry-After 由 resetAtMs 推导（1..120s 合理区间）")
                .isBetween(1L, 120L);
    }

    /** handler 非 HandlerMethod（静态资源等）→ 直接放行，零配额交互。 */
    @Test
    void nonHandlerMethodPassesThrough() throws Exception {
        assertThat(interceptor.preHandle(get("/v1/subscriptions/9/messages", "GET"),
                response(), new Object())).isTrue();
        verifyNoInteractions(rateLimitService);
    }

    /** includeHeaders=false 时 429 仍拦但不写观测头（信封与状态码不变）。 */
    @Test
    void rateLimitHeadersAreOptionalBehindIncludeHeadersFlag() throws Exception {
        properties.setIncludeHeaders(false);
        given(rateLimitService.tryAcquire(anyString(), anyInt(), anyLong()))
                .willReturn(new RateLimitService.AcquireResult(false, 600, 600,
                        System.currentTimeMillis() + 30_000L));

        MockHttpServletResponse response = response();
        assertThat(interceptor.preHandle(get("/v1/subscriptions/9/messages", "GET"),
                response, handler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
        assertThat(response.getContentAsString()).contains("10500");
    }

    // ------------------------------------------------------------ 夹具

    private MockHttpServletRequest get(String uri, String method) {
        return getFrom(uri, method, COUNTING_IP);
    }

    private MockHttpServletRequest getFrom(String uri, String method, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    private HandlerMethod handler() {
        return mock(HandlerMethod.class);
    }

    private RateLimitService.AcquireResult allowed(int limit) {
        return new RateLimitService.AcquireResult(true, 1, limit,
                System.currentTimeMillis() + 60_000L);
    }
}
