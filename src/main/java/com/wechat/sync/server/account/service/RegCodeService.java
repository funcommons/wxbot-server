package com.wechat.sync.server.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.dto.RegCodeIssueVO;
import com.wechat.sync.server.account.dto.RegCodeVO;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.entity.WxRegCodeEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.account.mapper.WxRegCodeMapper;
import com.wechat.sync.server.auth.dto.RegisterRequest;
import com.wechat.sync.server.common.PageVO;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 一次性注册码（TASK_DISPATCH §2.1）：签发 / 列表 / 作废 / 自助注册消费。
 *
 * <p>明文码只在 {@link #issue} 响应出现一次，库只存 {@code sha256(code_hash)}；
 * 部分唯一索引 {@code uk_wx_reg_code_hash}（is_deleted=0）保证软删后同哈希可再签。
 * 自助注册流程：consume 校验 → 建号（复用 {@link AccountService#register} 的 10401 逻辑）→
 * 一次��消费打点，三步同事务（{@link #registerWithCode}）；签令牌放事务外
 * （Redis 会话操作无回滚语义，见 AuthController）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegCodeService {

    /** 明文码前缀（排障时一眼区分令牌/注册码） */
    private static final String CODE_PREFIX = "wxreg_";

    /** 随机字节数：32 字节 → 43 位 base64url，256 位熵，在线爆破（6/min 配额下）不可行 */
    private static final int RANDOM_BYTES = 32;

    /** expires_hours 缺省：24h（TASK_DISPATCH §2.1） */
    private static final int DEFAULT_EXPIRES_HOURS = 24;

    /** 哈希撞 uk 的重试上限（256 位随机撞哈希概率可忽略，防御性上限） */
    private static final int HASH_RETRY_MAX = 5;

    /** account 是本地数据目录名（WeChatDB 定位键），服务端只做格式闸门（TASK_DISPATCH §2.1） */
    private static final Pattern ACCOUNT_FORMAT = Pattern.compile("^[A-Za-z0-9_@.\\-]{1,64}$");

    private static final int DELETED = 1;
    private static final int NORMAL = 0;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WxRegCodeMapper regCodeMapper;
    private final WxAccountMapper accountMapper;
    private final AccountService accountService;

    /**
     * 签发注册码（旧口径）：只回明文。行为契约同 {@link #issueDetailed}。
     *
     * @return 明文码（{@code wxreg_} + 43 位 base64url）
     */
    public String issue(String label, String preBoundAccount, Integer expiresHours) {
        return issueDetailed(label, preBoundAccount, expiresHours).code();
    }

    /**
     * 签发注册码并带回元数据（POST /v1/reg-codes 响应口径）：明文只此一次返回（随
     * {@link RegCodeIssueVO#code()}），库存哈希。expires_hours 空/非正 → 缺省 24h。
     * 哈希撞部分唯一索引（同哈希活动行已存在）时重新生成——256 位随机撞概率可忽略，
     * 仅防极端重试下的重复。
     */
    public RegCodeIssueVO issueDetailed(String label, String preBoundAccount, Integer expiresHours) {
        int hours = expiresHours == null || expiresHours <= 0 ? DEFAULT_EXPIRES_HOURS : expiresHours;
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(hours);
        for (int attempt = 1; attempt <= HASH_RETRY_MAX; attempt++) {
            String code = generateCode();
            WxRegCodeEntity entity = new WxRegCodeEntity();
            entity.setCodeHash(sha256Hex(code));
            entity.setAccount(blankToNull(preBoundAccount));
            entity.setLabel(blankToNull(label));
            entity.setExpiresAt(expiresAt);
            entity.setIsDeleted(NORMAL);
            try {
                regCodeMapper.insert(entity);
                log.info("[RegCode] 签发注册码 id={} label={} 预绑定={} 有效期至={} attempt={}",
                        entity.getId(), label, preBoundAccount, expiresAt, attempt);
                return new RegCodeIssueVO(code, entity.getId(), entity.getExpiresAt());
            } catch (DuplicateKeyException e) {
                // uk_wx_reg_code_hash 撞哈希：换一枚重生成（软删行不在索引内，不冲突）
            }
        }
        throw new IllegalStateException("注册码哈希连续冲突，重试 " + HASH_RETRY_MAX + " 次仍失败");
    }

    /**
     * 列表（Offset 分页，不回明文）。刻意**不过滤 is_deleted**：管理端要的是审计视图
     * （哪些被消费/哪些被作废），消费与作废状态都在 VO 里。
     */
    public PageVO<RegCodeVO> list(long page, long pageSize) {
        PageVO.PageParams params = PageVO.clamp(page, pageSize);
        Page<WxRegCodeEntity> result = regCodeMapper.selectPage(
                Page.of(params.page(), params.pageSize()),
                new LambdaQueryWrapper<WxRegCodeEntity>()
                        .orderByDesc(WxRegCodeEntity::getId));
        return PageVO.map(result.getRecords(), result.getTotal(), params.page(), params.pageSize(),
                RegCodeVO::from);
    }

    /**
     * 作废（软删）。已消费的也可删——审计凭 {@code used_by/used_at} 留痕，软删只是让它不可再消费
     * （消费查询本就只扫 is_deleted=0）。重复作废幂等（已是软删态直接返回）。
     */
    public void revoke(long id) {
        WxRegCodeEntity row = regCodeMapper.selectById(id);
        if (row == null) {
            throw new ApiException(ApiCode.NOT_FOUND, "注册码不存在: " + id);
        }
        if (row.getIsDeleted() != null && row.getIsDeleted() == DELETED) {
            return;
        }
        regCodeMapper.update(null, new LambdaUpdateWrapper<WxRegCodeEntity>()
                .eq(WxRegCodeEntity::getId, id)
                .set(WxRegCodeEntity::getIsDeleted, DELETED)
                .set(WxRegCodeEntity::getUpdatedAt, OffsetDateTime.now()));
        log.info("[RegCode] 作废注册码 id={}", id);
    }

    /**
     * 自助注册（TASK_DISPATCH §2.1 v1.1 流程，事务整体）：
     * account 格式闸门 → 注册码三重校验（存在/未消费/未过期/预绑定匹配）→
     * 建号（复用 {@link AccountService#register}，撞已注册 → 10401）→ 补 hostname/client_version →
     * 条件消费打点。
     *
     * <p><b>并发双消费防线</b>：两个请求同时通过「used_at IS NULL」预检时，
     * 最终只有条件 UPDATE（{@code WHERE id=? AND used_at IS NULL}）的一个能置位成功，
     * 失败方抛异常整体回滚（含已 insert 的账号行）。
     *
     * @return 注册成功的账号名（签令牌在事务外进行，见 AuthController）
     * @throws ApiException 10400 注册码不存在/已作废；10402 已使用/已过期/预绑定不匹配/并发双消费；
     *                      10102 account 格式非法；10401 account 已注册（透传自 AccountService）
     */
    @Transactional
    public String registerWithCode(RegisterRequest request) {
        String account = request.account().trim();
        if (!ACCOUNT_FORMAT.matcher(account).matches()) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR,
                    "account 格式非法（≤64 位 [A-Za-z0-9_@.-]）: " + account);
        }

        WxRegCodeEntity code = regCodeMapper.selectOne(new LambdaQueryWrapper<WxRegCodeEntity>()
                .eq(WxRegCodeEntity::getCodeHash, sha256Hex(request.regCode().trim()))
                .eq(WxRegCodeEntity::getIsDeleted, NORMAL)
                .last("LIMIT 1"));
        if (code == null) {
            throw new ApiException(ApiCode.NOT_FOUND, "注册码不存在或已作废");
        }
        if (code.getUsedAt() != null) {
            throw new ApiException(ApiCode.STATE_CONFLICT, "注册码已被使用: " + code.getId());
        }
        if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ApiException(ApiCode.STATE_CONFLICT, "注册码已过期: " + code.getId());
        }
        if (code.getAccount() != null && !code.getAccount().equals(account)) {
            throw new ApiException(ApiCode.STATE_CONFLICT,
                    "注册码预绑定账号不匹配: 绑定=" + code.getAccount() + " 请求=" + account);
        }

        String hostname = request.hostname().trim();
        // 建号复用 AccountService：已注册 → 10401、wxid 缺省推导（剥末尾 4 位后缀）都由它统一口径
        accountService.register(new AccountRegisterRequest(account, request.wxid().trim()));
        int accountUpdated = accountMapper.update(null, new LambdaUpdateWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, account)
                .eq(WxAccountEntity::getIsDeleted, NORMAL)
                .set(WxAccountEntity::getHostname, hostname)
                .set(WxAccountEntity::getClientVersion, blankToNull(request.clientVersion()))
                .set(WxAccountEntity::getUpdatedAt, OffsetDateTime.now()));
        if (accountUpdated != 1) {
            throw new ApiException(ApiCode.STATE_CONFLICT, "账号状态已变更: " + account);
        }

        int consumed = regCodeMapper.update(null, new LambdaUpdateWrapper<WxRegCodeEntity>()
                .eq(WxRegCodeEntity::getId, code.getId())
                .isNull(WxRegCodeEntity::getUsedAt)
                .set(WxRegCodeEntity::getUsedAt, OffsetDateTime.now())
                .set(WxRegCodeEntity::getUsedBy, hostname)
                .set(WxRegCodeEntity::getUpdatedAt, OffsetDateTime.now()));
        if (consumed != 1) {
            throw new ApiException(ApiCode.STATE_CONFLICT, "注册码已被并发使用: " + code.getId());
        }
        log.info("[RegCode] 自助注册成功 account={} regCodeId={} hostname={}", account, code.getId(), hostname);
        return account;
    }

    /** {@code wxreg_} + 32 字节 SecureRandom 的 base64url（无填充，43 位）。 */
    private static String generateCode() {
        byte[] buf = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(buf);
        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
