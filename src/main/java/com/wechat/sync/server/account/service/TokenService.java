package com.wechat.sync.server.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import fun.commons.framework4j.accesstoken.config.AccessTokenProperties;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.accesstoken.core.RefreshTokenService;
import fun.commons.framework4j.accesstoken.core.TokenKeyBuilder;
import fun.commons.framework4j.accesstoken.interceptor.RoleAuthorizer;
import fun.commons.framework4j.accesstoken.util.TokenUtils;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 令牌签发 / 轮换 / 吊销（API_DESIGN §3、§6）。
 *
 * <p>签发走 framework4j-accesstoken：{@link RefreshTokenService#generateTokenPair}（内部复用
 * {@link AccessTokenGenerator#generateToken}），不自造 JWT；吊销用框架的撤销 Set + family 清理。
 * claims 约定：{@code account=账号}（会话互斥键，policy.client.key=[account]）、
 * {@code roles=[client]}（{@code @RequiresToken} 角色校验读它）、
 * {@code type=client}（{@link RefreshTokenService} 选 policy 用）。
 */
@Slf4j
@Service
public class TokenService {

    /** client 令牌型别（= application.yml policies.client） */
    public static final String CLIENT_TYPE = "client";

    /** admin 令牌型别（= application.yml policies.admin，管理/查询端） */
    public static final String ADMIN_TYPE = "admin";

    /** claims：账号（policy.client.key 互斥键，也是 AccountBinding 比对键） */
    public static final String CLAIM_ACCOUNT = "account";

    /** claims：型别名（RefreshTokenService#generateTokenPair 以它选 policy） */
    public static final String CLAIM_TYPE = "type";

    /** claims：角色列表（框架约定 key，与 RoleAuthorizer.CLAIM_KEY_ROLES 一致） */
    public static final String CLAIM_ROLES = RoleAuthorizer.CLAIM_KEY_ROLES;

    /** 上报端角色 */
    public static final String ROLE_CLIENT = "client";

    /** 管理端角色（@RequiresToken(roles="admin") 全匹配源） */
    public static final String ROLE_ADMIN = "admin";

    /** claims：admin 会话互斥键（policy.admin.key=[uid]，框架 revokeByUser/updateClaims 同约定） */
    public static final String CLAIM_UID = "uid";

    /** 撤销 Set TTL：access 自然过期上限 + 缓冲（框架同款口径 8d） */
    private static final Duration REVOKED_SET_TTL = Duration.ofDays(8);

    private static final TypeReference<Map<String, Object>> JSON_TYPE = new TypeReference<>() {};

    private final AccessTokenGenerator accessTokenGenerator;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenProperties properties;
    private final WxAccountMapper accountMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public TokenService(AccessTokenGenerator accessTokenGenerator,
                        RefreshTokenService refreshTokenService,
                        AccessTokenProperties properties,
                        WxAccountMapper accountMapper,
                        ObjectMapper objectMapper,
                        @Qualifier("accessTokenStringRedisTemplate") StringRedisTemplate redis) {
        this.accessTokenGenerator = accessTokenGenerator;
        this.refreshTokenService = refreshTokenService;
        this.properties = properties;
        this.accountMapper = accountMapper;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    /**
     * 签发（轮换）client 令牌：先吊销该账号既有令牌（踢下线），再签发新 access+refresh 对。
     *
     * @param account    已注册账号
     * @param ttlSeconds access token 有效期秒数（契约缺省 7776000）
     */
    public AccountTokenVO issueForAccount(String account, int ttlSeconds) {
        requireAccount(account);
        revokeByAccount(account);

        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_ACCOUNT, account);
        claims.put(CLAIM_TYPE, CLIENT_TYPE);
        claims.put(CLAIM_ROLES, List.of(ROLE_CLIENT));

        AccessTokenProperties.Policy policy = requirePolicy(CLIENT_TYPE);
        Long configuredExpire = policy.getExpireTime();
        // 框架 TTL 读自 policy 配置（无按次入参），按契约 ttl_seconds 临时覆写后还原；
        // 签发是 admin 低频操作，以 policy 对象为锁避免并发签发互相覆写
        synchronized (policy) {
            try {
                policy.setExpireTime((long) ttlSeconds);
                AccessTokenGenerator.TokenPair pair = refreshTokenService.generateTokenPair(claims);
                String expiresAt = OffsetDateTime.now().plusSeconds(pair.accessExpiresInSeconds()).toString();
                log.info("[Token] 已签发 client 令牌 account={} ttl={}s family={}",
                        account, ttlSeconds, pair.familyId());
                return new AccountTokenVO(pair.accessToken(), pair.refreshToken(), expiresAt);
            } finally {
                policy.setExpireTime(configuredExpire);
            }
        }
    }

    /**
     * refresh 型别令牌轮换（TASK_DISPATCH §6 {@code POST /v1/auth/refresh}）：
     * 旧 refresh 换新令牌对。框架语义：一次性轮转（旧 jti 标记 consumed，新 jti 写入 family）
     * + 重用检测（旧 refresh 被复用 → 撤销整族 + 毒丸）。
     *
     * <p>失败（10210 已过期 / 10211 无效或已使用 / 10212 轮转超限）由框架抛
     * {@code AuthException}，经 AuthExceptionMapping 原样透传成 102xx 信封，这里不兜底——
     * 客户端按 1021x 分叉：10211 意味着 family 已死，只能重新走 register 流程。
     *
     * @param refreshToken 客户端持有的 refresh token（family 凭据）
     */
    public AccountTokenVO refreshForClient(String refreshToken) {
        AccessTokenGenerator.TokenPair pair = refreshTokenService.refreshAccessToken(refreshToken);
        String expiresAt = OffsetDateTime.now().plusSeconds(pair.accessExpiresInSeconds()).toString();
        log.info("[Token] 已轮换 client 令牌 ttl={}s family={}", pair.accessExpiresInSeconds(), pair.familyId());
        return new AccountTokenVO(pair.accessToken(), pair.refreshToken(), expiresAt);
    }

    /**
     * 签发 admin 令牌对（P1-③ 首枚 admin 令牌入口：bootstrap / 运维签发，管理接口自身
     * {@code @RequiresToken(value="admin", roles="admin")}，必须先有一枚 admin 令牌才进得去）。
     *
     * <p>claims 约定（与 client 路径 {@link #issueForAccount} 不同点）：
     * {@code uid=<uid>} —— policy.admin.key=[uid] 的会话互斥键（框架
     * {@code AccessTokenGenerator#revokeByUser(type, uid)} / {@code #updateClaims(type, uid, claims)}
     * 内部也固定按 claims.uid 取值，故 admin 型别可直接用框架原生吊销/热更）；<br>
     * {@code roles=["admin"]} —— {@code RoleAuthorizer} 全匹配校验源；<br>
     * {@code type=admin} —— {@link RefreshTokenService#generateTokenPair} 选 policy 用；<br>
     * {@code account=<account>} —— 仅作溯源，不参与 admin 会话 key；ingest 端点按
     * {@code type=client} 匹配令牌，admin 令牌进不去，故不会绕过 {@code AccountBinding}。
     *
     * <p>TTL 不覆写，直接用 yml {@code policies.admin.expire-time}（7200s，2h）。
     *
     * @param uid     admin 会话互斥键（bootstrap 场景取 {@code wx-sync.bootstrap-token-seed}）
     * @param account 溯源关联账号（bootstrap 场景为已注册的 dev 账号；可空则不写该 claim）
     * @return access + refresh 令牌对
     */
    public AccountTokenVO issueAdminToken(String uid, String account) {
        if (uid == null || uid.isBlank()) {
            throw new ApiException(ApiCode.PARAM_ERROR, "admin 令牌缺少 uid（policy.admin.key 声明的会话互斥键）");
        }
        String adminUid = uid.trim();

        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_UID, adminUid);
        claims.put(CLAIM_TYPE, ADMIN_TYPE);
        claims.put(CLAIM_ROLES, List.of(ROLE_ADMIN));
        if (account != null && !account.isBlank()) {
            claims.put(CLAIM_ACCOUNT, account.trim());
        }

        AccessTokenGenerator.TokenPair pair = refreshTokenService.generateTokenPair(claims);
        String expiresAt = OffsetDateTime.now().plusSeconds(pair.accessExpiresInSeconds()).toString();
        log.info("[Token] 已签发 admin 令牌 uid={} account={} ttl={}s family={}",
                adminUid, claims.get(CLAIM_ACCOUNT), pair.accessExpiresInSeconds(), pair.familyId());
        return new AccountTokenVO(pair.accessToken(), pair.refreshToken(), expiresAt);
    }

    /**
     * 吊销该账号全部令牌（踢下线）：access 会话清扫 + refresh family 撤销，即时生效。
     *
     * @return 撤销的 access 会话数
     */
    public int revokeByAccount(String account) {
        int revoked = revokeAccessSessions(account);
        revokeRefreshFamilies(account);
        return revoked;
    }

    /**
     * access 会话清扫。框架的 {@code AccessTokenGenerator#revokeByUser(type, uid)} 仅支持
     * {@code policy.key=[uid]}（内部固定以 claims.uid 取互斥键值）；client policy 的键是
     * {@code [account]}（T1 配置，本任务只准追加配置），故按框架同一 Key 约定
     * （{@link TokenKeyBuilder} + {@link TokenUtils#calculateKeyHash}）做等价清扫。
     */
    private int revokeAccessSessions(String account) {
        String appName = accessTokenGenerator.getAppName();
        String keyHash = TokenUtils.calculateKeyHash(account, properties.getHashSalt());
        Set<String> keys = redis.keys(TokenKeyBuilder.accessMetadata(appName, CLIENT_TYPE, keyHash + "*"));
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        String revokedSet = TokenKeyBuilder.accessRevokedSet(appName);
        for (String key : keys) {
            Object jti = readJson(redis.opsForValue().get(key)).get("jti");
            if (jti != null) {
                redis.opsForSet().add(revokedSet, String.valueOf(jti));
            }
            redis.delete(key);
        }
        accessTokenGenerator.extendRevokedSetExpire(revokedSet, REVOKED_SET_TTL.toSeconds());
        log.info("[Token] 已吊销 client access 会话 account={} count={}", account, keys.size());
        return keys.size();
    }

    /** refresh family 撤销：family hash 的 claims.account 命中即整族撤销。 */
    private void revokeRefreshFamilies(String account) {
        Set<String> familyKeys = redis.keys(TokenKeyBuilder.refreshFamily(accessTokenGenerator.getAppName(), "*"));
        if (familyKeys == null) {
            return;
        }
        for (String familyKey : familyKeys) {
            String familyId = familyKey.substring(familyKey.lastIndexOf(':') + 1);
            for (Object raw : redis.opsForHash().entries(familyKey).values()) {
                Object claims = readJson(raw).get("claims");
                if (claims instanceof Map<?, ?> c && account.equals(c.get(CLAIM_ACCOUNT))) {
                    refreshTokenService.revokeFamily(familyId);
                    break;
                }
            }
        }
    }

    private void requireAccount(String account) {
        Long count = accountMapper.selectCount(new LambdaQueryWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, account)
                .eq(WxAccountEntity::getIsDeleted, 0));
        if (count == null || count == 0) {
            throw new ApiException(ApiCode.NOT_FOUND, "账号不存在: " + account);
        }
    }

    private AccessTokenProperties.Policy requirePolicy(String tokenType) {
        AccessTokenProperties.Policy policy = properties.getPolicies() == null
                ? null : properties.getPolicies().get(tokenType);
        if (policy == null) {
            throw new IllegalStateException("framework4j.access-token.policies 未配置型别: " + tokenType);
        }
        return policy;
    }

    private Map<String, Object> readJson(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(raw), JSON_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
