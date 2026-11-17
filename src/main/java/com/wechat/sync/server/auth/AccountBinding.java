package com.wechat.sync.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.WxErrorCode;
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 账号绑定校验（API_DESIGN §3 硬规则）：client 令牌 claims 的 {@code account}
 * 必须与上报载荷的 {@code account} 一致，且账号已注册。
 *
 * <p>防止一个客户端冒写别的账号的来源行（SYNCH_PLAN §6.1）。供 ingest 端点
 * （POST /v1/reports/*、PUT /v1/blobs/*）在落库前调用。
 */
@Component
@RequiredArgsConstructor
public class AccountBinding {

    private final WxAccountMapper accountMapper;

    /** 令牌 claims.account 与载荷 account 一致性校验；不一致 → 10300。 */
    public void checkBound(String payloadAccount) {
        String tokenAccount = TokenContext.getClaim(TokenService.CLAIM_ACCOUNT);
        if (tokenAccount == null || !tokenAccount.equals(payloadAccount)) {
            throw new ApiException(ApiCode.FORBIDDEN,
                    "上报账号与令牌账号不一致: token=" + tokenAccount + " payload=" + payloadAccount);
        }
    }

    /** 绑定校验 + 注册校验；未注册 → 10601。返回经校验的账号。 */
    public String requireRegistered(String payloadAccount) {
        checkBound(payloadAccount);
        Long count = accountMapper.selectCount(new LambdaQueryWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, payloadAccount)
                .eq(WxAccountEntity::getIsDeleted, 0));
        if (count == null || count == 0) {
            throw WxErrorCode.accountNotRegistered(payloadAccount);
        }
        return payloadAccount;
    }
}
