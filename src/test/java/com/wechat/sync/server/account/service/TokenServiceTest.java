package com.wechat.sync.server.account.service;

import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.accesstoken.config.AccessTokenProperties;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.accesstoken.core.RefreshTokenService;
import fun.commons.framework4j.accesstoken.core.TokenKeyBuilder;
import fun.commons.framework4j.accesstoken.util.TokenUtils;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 令牌签发 / 吊销：claims 组装 + 撤销清扫 + policy TTL 覆写还原。
 * 框架类（Generator / RefreshTokenService / RedisTemplate）全部 mock——
 * RefreshTokenService 构造即需真实 Redis 连接，claims 组装逻辑无需真连即可验证，无 @Disabled 用例。
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final String ACCOUNT = "wxid_c00pwixax7xv22_8316";
    private static final String APP = "wx-sync";

    @Mock private AccessTokenGenerator accessTokenGenerator;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private WxAccountMapper accountMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private HashOperations<String, Object, Object> hashOps;

    private AccessTokenProperties properties;
    private TokenService service;

    @BeforeAll
    static void initTableInfo() { MpTestTables.init(WxAccountEntity.class); }

    @BeforeEach
    void setUp() {
        properties = new AccessTokenProperties();
        properties.setHashSalt("salt");
        properties.setPolicies(new HashMap<>(Map.of(TokenService.CLIENT_TYPE, policy(100L))));
        service = new TokenService(accessTokenGenerator, refreshTokenService, properties,
                accountMapper, new com.fasterxml.jackson.databind.ObjectMapper(), redis);
    }

    private AccessTokenProperties.Policy policy(long expireTime) {
        AccessTokenProperties.Policy p = new AccessTokenProperties.Policy();
        p.setExpireTime(expireTime);
        return p;
    }

    /** stub 吊销前置路径（requireAccount + 空撤销 Set） */
    private void stubIssuePath() {
        given(accountMapper.selectCount(any())).willReturn(1L);
        given(accessTokenGenerator.getAppName()).willReturn(APP);
        given(redis.keys(anyString())).willReturn(Set.of());
    }

    /** 捕获传给框架 generateTokenPair 的 claims */
    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedClaims() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(refreshTokenService).generateTokenPair(captor.capture());
        return captor.getValue();
    }

    @Test
    void issueForAccountBuildsClientClaimsOverridesTtlThenRestores() {
        stubIssuePath();
        given(refreshTokenService.generateTokenPair(any())).willAnswer(inv -> {
            assertThat(properties.getPolicies().get(TokenService.CLIENT_TYPE).getExpireTime()).isEqualTo(3600L);
            return new AccessTokenGenerator.TokenPair("acc-1", "ref-1", "fam-1", 3600L, 7200L);
        });

        AccountTokenVO vo = service.issueForAccount(ACCOUNT, 3600);

        assertThat(capturedClaims())
                .containsEntry(TokenService.CLAIM_ACCOUNT, ACCOUNT)
                .containsEntry(TokenService.CLAIM_TYPE, TokenService.CLIENT_TYPE)
                .containsEntry(TokenService.CLAIM_ROLES, List.of(TokenService.ROLE_CLIENT))
                .hasSize(3);
        assertThat(vo.accessToken()).isEqualTo("acc-1");
        assertThat(vo.refreshToken()).isEqualTo("ref-1");
        assertThat(OffsetDateTime.parse(vo.expiresAt())).isAfter(OffsetDateTime.now().plusSeconds(3500));
        assertThat(properties.getPolicies().get(TokenService.CLIENT_TYPE).getExpireTime()).isEqualTo(100L);
    }

    @Test
    void issueForAccountMissingAccountThrowsNotFoundAndSkipsIssue() {
        given(accountMapper.selectCount(any())).willReturn(0L);

        assertThatThrownBy(() -> service.issueForAccount("ghost", 3600))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        verify(refreshTokenService, never()).generateTokenPair(any());
        verifyNoInteractions(accessTokenGenerator, redis);
    }

    @Test
    void issueAdminTokenBuildsAdminClaimsWithUidAndTrimsInputs() {
        given(refreshTokenService.generateTokenPair(any()))
                .willReturn(new AccessTokenGenerator.TokenPair("a", "r", "f", 7200L, 14400L));

        service.issueAdminToken("  seed-1  ", ACCOUNT);

        assertThat(capturedClaims())
                .containsEntry(TokenService.CLAIM_UID, "seed-1")
                .containsEntry(TokenService.CLAIM_TYPE, TokenService.ADMIN_TYPE)
                .containsEntry(TokenService.CLAIM_ROLES, List.of(TokenService.ROLE_ADMIN))
                .containsEntry(TokenService.CLAIM_ACCOUNT, ACCOUNT)
                .hasSize(4);
    }

    @Test
    void issueAdminTokenOmitsAccountClaimWhenBlank() {
        given(refreshTokenService.generateTokenPair(any()))
                .willReturn(new AccessTokenGenerator.TokenPair("a", "r", "f", 7200L, 14400L));

        service.issueAdminToken("seed", "   ");

        assertThat(capturedClaims()).doesNotContainKey(TokenService.CLAIM_ACCOUNT).hasSize(3);
    }

    @Test
    void issueAdminTokenBlankUidThrowsParamError() {
        assertThatThrownBy(() -> service.issueAdminToken("  ", ACCOUNT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
        verify(refreshTokenService, never()).generateTokenPair(any());
    }

    @Test
    void revokeByAccountClearsAccessSessionsAndRevokesMatchingFamily() {
        String accessKey = TokenKeyBuilder.accessMetadata(APP, TokenService.CLIENT_TYPE,
                TokenUtils.calculateKeyHash(ACCOUNT, "salt"));
        String familyKey = TokenKeyBuilder.refreshFamily(APP, "fam-9");
        given(accessTokenGenerator.getAppName()).willReturn(APP);
        doReturn(Set.of(accessKey), Set.of(familyKey)).when(redis).keys(anyString());
        doReturn(valueOps).when(redis).opsForValue();
        doReturn("{\"jti\":\"jti-1\"}").when(valueOps).get(accessKey);
        doReturn(setOps).when(redis).opsForSet();
        doReturn(hashOps).when(redis).opsForHash();
        doReturn(Map.of("claims", "{\"claims\":{\"account\":\"" + ACCOUNT + "\"}}")).when(hashOps).entries(familyKey);

        assertThat(service.revokeByAccount(ACCOUNT)).isEqualTo(1);

        verify(setOps).add(TokenKeyBuilder.accessRevokedSet(APP), "jti-1");
        verify(redis).delete(accessKey);
        verify(accessTokenGenerator).extendRevokedSetExpire(eq(TokenKeyBuilder.accessRevokedSet(APP)), anyLong());
        verify(refreshTokenService).revokeFamily("fam-9");
    }

    @Test
    void revokeByAccountWithNoSessionsReturnsZero() {
        given(accessTokenGenerator.getAppName()).willReturn(APP);
        given(redis.keys(anyString())).willReturn(Set.of());

        assertThat(service.revokeByAccount(ACCOUNT)).isZero();
        verify(setOps, never()).add(anyString(), anyString());
        verify(refreshTokenService, never()).revokeFamily(anyString());
    }
}
