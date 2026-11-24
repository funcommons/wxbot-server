package com.wechat.sync.server.config;

import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * admin-ui 静态令牌的后端兑换：静态串（yml {@code wx-sync.admin-ui-token}）本身不是
 * JWT，过不了框架 accesstoken 校验——本服务把它兑换成一枚真实 admin JWT（uid=
 * admin-ui-static，独立 policy key），缓存在内存里并对过期透明续签（TTL 2h，提前
 * 200s 换新）。SPA 全程只持静态串，无任何刷新动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUiStaticTokenService {

    private final TokenService tokenService;

    @Value("${wx-sync.admin-ui-token:}")
    private String staticToken;

    /** 内存缓存的兑换 JWT（静态串会话隔离：uid=admin-ui-static） */
    private final AtomicReference<String> cachedJwt = new AtomicReference<>("");

    /** 当前静态串的哈希——配置热改后旧缓存作废 */
    private final AtomicReference<String> cachedFor = new AtomicReference<>("");

    private volatile long mintedAt = 0;

    /** 静态串是否命中配置（登录校验口径） */
    public boolean matches(String candidate) {
        return staticToken != null && !staticToken.isBlank() && staticToken.equals(candidate);
    }

    /** 静态串 → 可过 @RequiresToken 的真实 admin JWT（带过期透明续签） */
    public synchronized String resolveJwt() {
        String want = String.valueOf(staticToken);
        long now = System.currentTimeMillis();
        String jwt = cachedJwt.get();
        boolean expired = now - mintedAt > 7000_000L; // admin TTL 7200s，提前 200s 续
        if (jwt.isEmpty() || !cachedFor.get().equals(want) || expired) {
            AccountTokenVO pair = tokenService.issueAdminToken("admin-ui-static", null);
            jwt = pair.accessToken();
            cachedJwt.set(jwt);
            cachedFor.set(want);
            mintedAt = now;
            log.info("[AdminUi] 静态令牌已兑换 admin JWT（uid=admin-ui-static，ttl={}s）",
                    pair.expiresAt());
        }
        return jwt;
    }

    /** JWT 剩余秒数（供日志/诊断；解析失败返 0） */
    public long jwtTtlSeconds(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            String expStr = payload.replaceAll(".*\"exp\":([0-9]+).*", "$1");
            return Math.max(0, Long.parseLong(expStr) - nowSeconds());
        } catch (Exception e) {
            return 0;
        }
    }

    private long nowSeconds() { return System.currentTimeMillis() / 1000; }
}
