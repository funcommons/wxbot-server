package com.wechat.sync.server;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.framework4j.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 项目骨架冒烟测试（集成联通用例，直连真实 PG 5433 / Redis 6379，不 mock）。
 *
 * <p>覆盖：Spring 上下文加载（framework4j 16 starter 自动装配无冲突）、DataSource、Redis、
 * 统一响应信封、MyBatis Plus 分页拦截器。
 */
@SpringBootTest(properties = "spring.test.context.failure.threshold=2")
class WxSyncServerContextTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** ObjectProvider 而非直接注入：framework4j 多数据源开关打开时会竞争注册同名类型 Bean。 */
    @Autowired
    private ObjectProvider<MybatisPlusInterceptor> mybatisPlusInterceptor;

    @Autowired
    private ObjectProvider<GlobalExceptionHandler> globalExceptionHandler;

    @Test
    void contextLoads() {
        // 上下文成功起来本身即断言：framework4j 全部 AutoConfiguration 装配不冲突
    }

    @Test
    void dataSourceIsReachable() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void redisIsReachable() {
        String pong = stringRedisTemplate.execute((RedisConnection conn) -> conn.ping());
        assertThat(pong).isEqualTo("PONG");
    }

    @Test
    void apiResponseEnvelopeAndHandlerAreInPlace() {
        // 信封六字段（mc-api-spec §4：code/message/data/error/trace_id/timestamp 必返）
        ApiResponse<String> envelope = ApiResponse.success("ok");
        assertThat(envelope.getCode()).isZero();
        assertThat(envelope.getMessage()).isNotBlank();
        assertThat(envelope.getData()).isEqualTo("ok");
        assertThat(envelope.getError()).isNull();
        assertThat(envelope.getTraceId()).isNotBlank();
        assertThat(envelope.getTimestamp()).isPositive();
        assertThat(envelope.isSuccess()).isTrue();

        // GlobalExceptionHandler 由 framework4j WebAutoConfiguration 注册为 Bean（HTTP 200 全异常兜底）
        assertThat(globalExceptionHandler.getIfAvailable())
                .as("framework4j GlobalExceptionHandler Bean").isNotNull();
    }

    @Test
    void mybatisPlusPaginationInterceptorIsRegistered() {
        MybatisPlusInterceptor interceptor = mybatisPlusInterceptor.getIfAvailable();
        assertThat(interceptor).as("MybatisPlusInterceptor Bean").isNotNull();
        assertThat(interceptor.getInterceptors()).isNotEmpty();
    }
}
