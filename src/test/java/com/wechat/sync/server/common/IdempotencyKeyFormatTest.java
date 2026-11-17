package com.wechat.sync.server.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.service.AccountService;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 传输层契约回归（真机冒烟缺陷 #1）：{@code POST /v1/reports/messages} 的 {@code Idempotency-Key}
 * 必须同时接受 <b>32 位小写十六进制</b> 与 <b>标准连字符 UUID</b> 两种形态，且两种形态落在
 * <b>同一幂等键空间</b>（48h 内重放吃到同一响应、库不重复落）；非法格式仍按参数错误拒收。
 * 修前 framework4j-idempotency {@code IdempotencyInterceptor} 的 {@code UUID_V4} 正则只认连字符
 * UUID v4，{@code uuid4().hex} 在 {@code preHandle} 被 {@code writeJson(400, 10102)} 拒收；修复
 * {@code config/IdempotencyKeyCanonicalFilter} 做「服务端归一化」——32 位 hex → 小写连字符，其余透传。
 * 因框架按键<b>原文</b>拼 {@code keyPrefix:uri:key}，归一与否直接决定同一 UUID 两种写法是否共享
 * 48h 重放空间，故 {@code hexAndHyphenatedFormOfSameUuidShareOneIdempotencyKey} 单钉这一点。
 *
 * <p><b>为什么走 MockMvc</b>：键形态裁决在 MVC 拦截器及其前置 Filter，直调
 * {@code MessagesIngestService} 完全绕过该层；Spring Boot 3.5 的
 * {@code SpringBootMockMvcBuilderCustomizer#addFilters} 会把 {@code FilterRegistrationBean} 的
 * {@code getUrlPatterns()} 一并传给 MockMvc（源码已核），故 {@code /v1/reports/*} 注册同样生效。
 * 直连真实 PG 5433 / Redis 6379（与 {@code MessageIngestIntegrationTest} 同口径），
 * 数据用 {@code wxid_t4idemfmt_} / {@code t4idemfmt_} 前缀、幂等 Redis 键只清本类用到的几把，前后各清一次。
 */
@SpringBootTest(properties = "spring.test.context.failure.threshold=2")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyKeyFormatTest {

    private static final String ACCOUNT = "wxid_t4idemfmt_a";
    private static final String CHAT = "t4idemfmt_room@chatroom";
    private static final String SHARD = "db/MSG0.db";
    private static final long LOCAL_ID = 410L;
    /** 64 位小写十六进制（业务 SHA256_PATTERN 闭集） */
    private static final String BODY_SHA = "4100000000000000000000000000000000000000000000000000000000000041";

    /** 32 位裸 hex（uuid4().hex 形态）：第 13 位是 4（版本位）、第 17 位是 9（variant 位） */
    private static final String HEX_KEY = "a1b2c3d4e5f64a7d9e2f1a5b6c7d8e9f";
    /** 重放用例专属 hex 键（同样第 13 位 4 / 第 17 位 8）：幂等键不含 body，与 {@link #HEX_KEY} 共用必吃到对方的 48h 重放响应 */
    private static final String HEX_KEY_REPLAY = "f0e1d2c3b4a54c6d8e7f0a1b2c3d4e5f";
    /** 另一个逻辑 UUID 的标准连字符形态（与 HEX_KEY 不同 UUID，避免两用例共键互相 409） */
    private static final String UUID_KEY = "3f9d1c2e-8b4a-4c7d-9e2f-1a5b6c7d8e9f";
    /** 第三把键：hex 形态，专测「同一 UUID 的两种写法落同一幂等键」 */
    private static final String HEX_KEY_2 = "b7e3f5a91c8d4e2fa6b90d4c8e2f6a1b";
    /** 8 字符短串：两种合法形态都不是，必须仍被拒 */
    private static final String SHORT_KEY = "abc12345";

    /** 框架幂等键的 Redis 前缀（IdempotencyProperties.keyPrefix 缺省 idem）+ 拦截器拼的 URI 段 */
    private static final String IDEM_REDIS_PREFIX = "idem:/v1/reports/messages:";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountService accountService;
    @Autowired private TokenService tokenService;
    @Autowired private WxMessageMapper messageMapper;
    @Autowired private StringRedisTemplate redis;
    @Autowired private DataSource dataSource;

    private String bearer;

    @BeforeAll
    void seedAccountAndToken() throws SQLException {
        purge();
        accountService.register(new AccountRegisterRequest(ACCOUNT, null));
        bearer = "Bearer " + tokenService.issueForAccount(ACCOUNT, 7776000).accessToken();
    }

    @AfterAll
    void tearDown() throws SQLException {
        purge();
    }

    @Test
    void hexKeyAndHyphenUuidKeyAreBothAccepted() throws Exception {
        assertThat(codeOf(postReport(HEX_KEY, "41001")))
                .as("32 位裸 hex 键必须被接受（修前 10102 拒收）").isZero();
        assertThat(codeOf(postReport(UUID_KEY, "41002")))
                .as("标准连字符 UUID 键必须仍被接受（不得修复时误伤既有形态）").isZero();
    }

    @Test
    void sameHexKeyReplaysSameResponseWithoutSecondRow() throws Exception {
        String first = postReport(HEX_KEY_REPLAY, "41003").getResponse().getContentAsString();
        String second = postReport(HEX_KEY_REPLAY, "41003").getResponse().getContentAsString();

        JsonNode firstNode = objectMapper.readTree(first);
        JsonNode secondNode = objectMapper.readTree(second);

        assertThat(firstNode.path("code").asInt(-1)).as("首次上报须成功").isZero();
        assertThat(secondNode.path("code").asInt(-1))
                .as("同 key 重放须吃到同一响应（幂等命中，而非再次落库）").isZero();
        // batch_id 是雪花新值：重放一旦穿透到 Controller 必然产生不同 batch_id
        assertThat(secondNode.path("data").path("batch_id").asText())
                .as("重放响应 data 与首次一致（batch_id 同值）")
                .isEqualTo(firstNode.path("data").path("batch_id").asText());
        assertThat(secondNode.path("data").path("results").toString())
                .isEqualTo(firstNode.path("data").path("results").toString());

        assertThat(countRows("srv:41003")).as("同 key 重放不得产生第二行 wx_message").isEqualTo(1L);
    }

    @Test
    void hexAndHyphenatedFormOfSameUuidShareOneIdempotencyKey() throws Exception {
        String hyphenated = hyphenated(HEX_KEY_2);

        JsonNode hexNode = objectMapper.readTree(
                postReport(HEX_KEY_2, "41004").getResponse().getContentAsString());
        JsonNode hyphenNode = objectMapper.readTree(
                postReport(hyphenated, "41004").getResponse().getContentAsString());

        assertThat(hexNode.path("code").asInt(-1)).as("hex 形态首次上报须成功").isZero();
        assertThat(hyphenNode.path("code").asInt(-1))
                .as("同 UUID 的连字符形态必须命中同一幂等键（200 重放，而非 409 重复提交）").isZero();
        assertThat(hyphenNode.path("data").path("batch_id").asText())
                .as("两形态共享 48h 重放空间：batch_id 必须同值")
                .isEqualTo(hexNode.path("data").path("batch_id").asText());
        assertThat(countRows("srv:41004")).as("两形态只允许落一行").isEqualTo(1L);
        assertThat(redis.keys(IDEM_REDIS_PREFIX + "*" + hyphenated))
                .as("Redis 里只应有归一后的那一个键").hasSize(1);
    }

    @Test
    void invalidKeyFormatIsStillRejectedAsParamError() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/reports/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", SHORT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("41005")))
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        int code = node.path("code").asInt(-1);

        assertThat(code).as("非法格式键必须拒收（归一过滤器对非 32 位 hex 逐字节透传，裁决归框架）")
                .isNotZero();
        JsonNode data = node.path("data");
        assertThat(data.isMissingNode() || data.isNull()).as("拒收响应 data 必须为 null").isTrue();
        assertThat(code).as("须落在 101xx 参数段（不是 0 成功，也不落 10001 系统错）")
                .isBetween(10100, 10199);
    }

    /**
     * 带 Bearer + Idempotency-Key 的合法上报；serverId 每用例独立且必须是<b>纯数值</b>串（非数值走
     * {@code resolveServerId} 的 BIGINT 兜底落成 {@code srv:<原文>}，按 {@code srv:<id>} 断言恒空）。
     */
    private MvcResult postReport(String idempotencyKey, String serverId) throws Exception {
        return mockMvc.perform(post("/v1/reports/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(serverId)))
                .andReturn();
    }

    private int codeOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt(-1);
    }

    private Long countRows(String dedupKey) {
        return messageMapper.selectCount(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getChatUsername, CHAT)
                .eq(WxMessageEntity::getDedupKey, dedupKey));
    }

    /** 32 位 hex → 连字符形态（与 IdempotencyKeyCanonicalFilter 的归一写法一致）。 */
    private static String hyphenated(String hex32) {
        return hex32.substring(0, 8) + '-' + hex32.substring(8, 12) + '-' + hex32.substring(12, 16)
                + '-' + hex32.substring(16, 20) + '-' + hex32.substring(20);
    }

    private static String payload(String serverId) {
        // local_id 也要每用例独立：兜底二按 (account, chat, shard, local_id) 定位「同一源侧键」，
        // 共用 LOCAL_ID 会让后到用例命中前一用例的行（action=unchanged），永远不落新行。
        long localId = LOCAL_ID * 1000 + Long.parseLong(serverId);
        return """
                {"account":"%s","batch_ref":"t4idemfmt-batch","chats":[{\
                "chat_username":"%s","messages":[{"shard":"%s","local_id":%d,\
                "server_id":"%s","sort_seq":1760000000000,"create_time_raw":1760000000,\
                "type_code":"10000","type_name":"文本","sender_source":"SHARD_NAME2ID",\
                "body":"hello","content_sha256":"%s","is_revoked":false}]}]}\
                """.formatted(ACCOUNT, CHAT, SHARD, localId, serverId, BODY_SHA);
    }

    /** 只清本前缀（账号 wxid_t4idemfmt_ / 会话 t4idemfmt_）与本次用到的幂等 Redis 键，不碰他人数据。 */
    private void purge() throws SQLException {
        exec("DELETE FROM wx_message_attachment WHERE message_id IN "
                + "(SELECT id FROM wx_message WHERE chat_username LIKE 't4idemfmt%')");
        exec("DELETE FROM wx_merge_log WHERE kept_id IN "
                + "(SELECT id FROM wx_message WHERE chat_username LIKE 't4idemfmt%')");
        exec("DELETE FROM wx_message_source_rel WHERE chat_username LIKE 't4idemfmt%'");
        exec("DELETE FROM wx_message WHERE chat_username LIKE 't4idemfmt%'");
        exec("DELETE FROM wx_sync_cursor WHERE account LIKE 'wxid_t4idemfmt_%'");
        exec("DELETE FROM wx_ingest_batch_log WHERE account LIKE 'wxid_t4idemfmt_%'");
        exec("DELETE FROM wx_account WHERE account LIKE 'wxid_t4idemfmt_%'");
        deleteOwnIdempotencyKeys();
    }

    /**
     * 幂等 Redis 键只删<b>本类用到的</b>（框架键形如 {@code idem:<uri>:<键>}，hex 键存的是归一后的连字符形态，
     * 故两种写法都清）：清整个 {@code idem:/v1/reports/messages:*} 会误删他人 48h TTL 内的键。
     */
    private void deleteOwnIdempotencyKeys() {
        for (String key : List.of(HEX_KEY, HEX_KEY_REPLAY, HEX_KEY_2, UUID_KEY, SHORT_KEY,
                hyphenated(HEX_KEY), hyphenated(HEX_KEY_REPLAY), hyphenated(HEX_KEY_2))) {
            Set<String> keys = redis.keys(IDEM_REDIS_PREFIX + "*" + key);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
