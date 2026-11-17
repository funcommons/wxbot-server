package com.wechat.sync.server.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 传输层契约回归（真机冒烟缺陷 #3）：缺 / 无效令牌访问 {@code POST /v1/reports/messages}
 * 必须返回 <b>HTTP 200 + 10200 族认证信封</b>（{@code data=null}、{@code trace_id} 非空），
 * 不得再是 HTTP 500 + 10001「系统繁忙」。
 *
 * <p><b>码的钉死依据</b>：API_DESIGN §2.3 鉴权段（10200/10201/10202）；framework4j-accesstoken
 * {@code TokenInterceptor} 对缺 {@code Authorization} 头固定抛 {@code AuthException(10200,
 * "未提供认证令牌")}（DEFAULT_CODE = 10200 UNAUTHORIZED），签名错 / 格式错为 10202 / 10207。
 * 故缺头场景<b>钉死 code == 10200</b>，无效令牌按「同族」断言 102xx 段。
 *
 * <p><b>机理与修复</b>：framework4j-web 的 {@code GlobalExceptionHandler} 无 {@code AuthException}
 * 条目 → 落 {@code Exception.class} 兜底 → HTTP 500 + 10001。修复是 {@code config/AuthExceptionMapping}
 * （{@code @Order} 抢先 + {@code @ExceptionHandler} + {@code @ResponseStatus(OK)}），已落盘。
 * 鉴权在 Controller 之前失败，故本类不注册账号、不写任何库行。
 */
@SpringBootTest(properties = "spring.test.context.failure.threshold=2")
@AutoConfigureMockMvc
class AuthMissingTokenEnvelopeTest {

    private static final String REPORTS_URI = "/v1/reports/messages";

    /** 缺 Authorization 头时框架确定抛出的码（TokenInterceptor「未提供认证令牌」）。 */
    private static final int MISSING_TOKEN_CODE = 10200;
    /** framework4j ApiCode.SYSTEM_BUSY：缺陷现场的错误形态，修复后必须绝迹。 */
    private static final int SYSTEM_BUSY_CODE = 10001;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void missingAuthorizationHeaderReturnsHttp200With10200Envelope() throws Exception {
        MvcResult result = mockMvc.perform(request(null)).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("认证失败必须 HTTP 200（API_DESIGN §1：业务/认证错误不走 4xx/5xx）").isEqualTo(200);
        JsonNode envelope = envelopeOf(result);
        assertThat(envelope.path("code").asInt(-1))
                .as("缺令牌钉死 10200 UNAUTHORIZED").isEqualTo(MISSING_TOKEN_CODE);
        assertEnvelopeShape(envelope);
    }

    @Test
    void malformedTokenStaysInHttp200And102xxFamily() throws Exception {
        MvcResult result = mockMvc.perform(request("Bearer not-a-jwt-token")).andReturn();

        assertThat(result.getResponse().getStatus()).as("无效令牌同样必须 HTTP 200").isEqualTo(200);
        int code = envelopeOf(result).path("code").asInt(-1);
        assertThat(code).as("无效令牌须落 102xx 认证段（签名失败 10202 / 格式错 10207）")
                .isBetween(10200, 10299);
        assertThat(code).as("不得再落 10001 系统繁忙").isNotEqualTo(SYSTEM_BUSY_CODE);
        assertEnvelopeShape(envelopeOf(result));
    }

    /** 六字段信封的失败形态：message 非空、data 为 null、trace_id 非空、timestamp 为毫秒数。 */
    private void assertEnvelopeShape(JsonNode envelope) {
        assertThat(envelope.path("message").asText()).as("失败信封 message 必须非空").isNotBlank();
        JsonNode data = envelope.path("data");
        assertThat(data.isMissingNode() || data.isNull())
                .as("认证失败信封 data 必须为 null（API_DESIGN §2.1）").isTrue();
        assertThat(envelope.path("trace_id").asText())
                .as("trace_id 必须非空（API_DESIGN §1：链路追踪）").isNotBlank();
        assertThat(envelope.path("timestamp").asLong(-1)).as("timestamp 必须为毫秒数").isPositive();
    }

    private JsonNode envelopeOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 无认证上下文的最小上报请求（拦在鉴权层，不触达业务校验/落库）。 */
    private static MockHttpServletRequestBuilder request(String authorization) {
        MockHttpServletRequestBuilder builder = post(REPORTS_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"wxid_t4auth_a\",\"chats\":[]}");
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return builder;
    }
}
