package com.wechat.sync.server.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.account.service.AccountService;
import com.wechat.sync.server.account.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * admin 令牌 bootstrap（P1-③）：首枚 admin 令牌的签发入口。
 *
 * <p>死锁解法：管理接口（{@code /v1/accounts/**}）类级
 * {@code @RequiresToken(value="admin", roles="admin")}，而 admin 令牌此前无处可签——
 * 必须已持有 admin 令牌才能调签发接口。本 Runner 在启动期补上第一枚。
 *
 * <p>触发条件（默认禁用，显式 opt-in 才跑）：
 * <pre>
 * wx-sync:
 *   bootstrap-token-seed: ${WXUW_BOOTSTRAP_SEED:}   # 环境变量优先，缺省空 = 禁用
 * </pre>
 * seed 非空 且 {@code wx_account} 无任何行（含软删）时才动作：
 * ① 幂等注册 dev 账号 {@value #BOOTSTRAP_ACCOUNT}（已存在则跳过注册）；
 * ② 用 {@link TokenService#issueAdminToken} 为它签发 admin 令牌对（uid = seed，
 * {@code claims.roles=["admin"]}、{@code type=admin}）；
 * ③ {@code log.info} 打印 access_token（前 24 字符 + 完整值）与 refresh_token，方便 dev 复制。
 *
 * <p>边界：只管「首枚」——库里已有任何账号即不动（真实账号由运维用 admin API 建）；
 * 整体 best-effort，任何一步失败仅 {@code log.error}，不阻断应用启动。
 */
@Slf4j
@Component
public class AdminBootstrap implements CommandLineRunner {

    /** dev 账号名（幂等键；形如 wxid_xxx_8316 的口径，后缀表示安装序号） */
    public static final String BOOTSTRAP_ACCOUNT = "wxid_bootstrap_dev_0000";

    /** access_token 日志的前缀长度（完整值另起一行，方便肉眼比对是不是同一枚） */
    private static final int TOKEN_PREFIX_LEN = 24;

    private final String bootstrapTokenSeed;
    private final AccountService accountService;
    private final TokenService tokenService;
    private final WxAccountMapper accountMapper;

    public AdminBootstrap(
            @Value("${wx-sync.bootstrap-token-seed:}") String bootstrapTokenSeed,
            AccountService accountService,
            TokenService tokenService,
            WxAccountMapper accountMapper) {
        this.bootstrapTokenSeed = bootstrapTokenSeed;
        this.accountService = accountService;
        this.tokenService = tokenService;
        this.accountMapper = accountMapper;
    }

    @Override
    public void run(String... args) {
        String seed = bootstrapTokenSeed == null ? "" : bootstrapTokenSeed.trim();
        if (seed.isEmpty()) {
            log.debug("[Bootstrap] disabled：wx-sync.bootstrap-token-seed 为空（WXUW_BOOTSTRAP_SEED 未设置）");
            return;
        }

        try {
            Long count = accountMapper.selectCount(null);
            if (count != null && count > 0) {
                log.info("[Bootstrap] wx_account 已有 {} 行，跳过 bootstrap（管理接口请用既有 admin 令牌）", count);
                return;
            }

            String account = registerDevAccount();

            AccountTokenVO pair = tokenService.issueAdminToken(seed, account);
            String accessToken = pair.accessToken();
            log.info("[Bootstrap] admin access_token 前{}字符 = {}",
                    TOKEN_PREFIX_LEN, accessToken.substring(0, Math.min(TOKEN_PREFIX_LEN, accessToken.length())));
            log.info("[Bootstrap] admin access_token 完整值 = {}", accessToken);
            log.info("[Bootstrap] admin refresh_token 完整值 = {}（/v1/auth/refresh 轮转用）", pair.refreshToken());
            log.info("[Bootstrap] dev 账号 = {}，access 过期时间 = {}，bootstrap uid = {}",
                    account, pair.expiresAt(), seed);
            log.warn("[Bootstrap] admin 令牌已打印到日志，生产环境严禁开启该开关（WXUW_BOOTSTRAP_SEED 留空即禁用）");
        } catch (Exception e) {
            // best-effort：bootstrap 是 dev 便利入口，失败不阻断应用启动
            log.error("[Bootstrap] 执行失败（已跳过，不影响启动）：seed 是否已设置={}, 原因见下", !seed.isEmpty(), e);
        }
    }

    /** 幂等注册 dev 账号：已存在（is_deleted=0）则直接复用，并发/重复启动不抛 10401。 */
    private String registerDevAccount() {
        Long existing = accountMapper.selectCount(new LambdaQueryWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, BOOTSTRAP_ACCOUNT)
                .eq(WxAccountEntity::getIsDeleted, 0));
        if (existing != null && existing > 0) {
            log.info("[Bootstrap] dev 账号已存在，跳过注册 account={}", BOOTSTRAP_ACCOUNT);
            return BOOTSTRAP_ACCOUNT;
        }
        accountService.register(new AccountRegisterRequest(BOOTSTRAP_ACCOUNT, null));
        return BOOTSTRAP_ACCOUNT;
    }
}
