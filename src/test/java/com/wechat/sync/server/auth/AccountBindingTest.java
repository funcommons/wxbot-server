package com.wechat.sync.server.auth;

import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 账号绑定校验：claims.account ↔ 载荷 account（10300）+ 注册校验（10601）。 */
@ExtendWith(MockitoExtension.class)
class AccountBindingTest {

    private static final String ACCOUNT = "wxid_a_0001";

    @Mock private WxAccountMapper accountMapper;

    private AccountBinding binding;

    @BeforeAll
    static void initTableInfo() { MpTestTables.init(WxAccountEntity.class); }

    @BeforeEach
    void setUp() { binding = new AccountBinding(accountMapper); }

    @AfterEach
    void clearContext() { TokenContext.clear(); }

    /** 以 client 令牌身份塞入 ThreadLocal claims */
    private void loginAs(String account) {
        TokenContext.set(TokenService.CLIENT_TYPE, Map.of(TokenService.CLAIM_ACCOUNT, account));
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10300));
    }

    @Test
    void checkBoundPassesWhenTokenAccountMatches() {
        loginAs(ACCOUNT);
        assertThatCode(() -> binding.checkBound(ACCOUNT)).doesNotThrowAnyException();
        verify(accountMapper, never()).selectCount(any());
    }

    @Test
    void checkBoundRejectsMismatchedPayloadAccount() {
        loginAs(ACCOUNT);
        assertForbidden(() -> binding.checkBound("wxid_other_0002"));
        verify(accountMapper, never()).selectCount(any());
    }

    @Test
    void checkBoundRejectsMissingTokenContext() {
        assertForbidden(() -> binding.checkBound(ACCOUNT));
    }

    @Test
    void requireRegisteredReturnsValidatedAccount() {
        loginAs(ACCOUNT);
        given(accountMapper.selectCount(any())).willReturn(1L);
        assertThat(binding.requireRegistered(ACCOUNT)).isEqualTo(ACCOUNT);
    }

    @Test
    void requireRegisteredRejectsUnknownAccountWith10601() {
        loginAs(ACCOUNT);
        given(accountMapper.selectCount(any())).willReturn(0L);
        assertThatThrownBy(() -> binding.requireRegistered(ACCOUNT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10601));
    }
}
