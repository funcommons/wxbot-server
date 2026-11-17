package com.wechat.sync.server.account.controller;

import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.dto.AccountTokenRequest;
import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.dto.AccountVO;
import com.wechat.sync.server.account.service.AccountService;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import fun.commons.framework4j.web.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** 5 个 admin 端点的转发与信封包装（不起 Spring 上下文，鉴权拦截器属框架行为不测）。 */
@ExtendWith(MockitoExtension.class)
class AccountAdminControllerTest {

    private static final String ACCOUNT = "wxid_c00pwixax7xv22_8316";

    @Mock private AccountService accountService;
    @Mock private TokenService tokenService;

    private AccountAdminController controller;

    @BeforeEach
    void setUp() { controller = new AccountAdminController(accountService, tokenService); }

    private AccountVO vo() {
        return new AccountVO(100L, ACCOUNT, "wxid_c00pwixax7xv22", "dev-1", "4.0.0",
                null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private AccountTokenVO token() { return new AccountTokenVO("acc", "ref", "expires"); }

    @Test
    void registerWrapsVoInSuccessEnvelope() {
        AccountRegisterRequest request = new AccountRegisterRequest(ACCOUNT, null);
        given(accountService.register(request)).willReturn(vo());

        ApiResponse<AccountVO> resp = controller.register(request);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().account()).isEqualTo(ACCOUNT);
    }

    /** 缺省 TTL：请求体缺省（null）→ 90d（AccountTokenRequest.DEFAULT_TTL_SECONDS）。 */
    @Test
    void issueTokenWithoutBodyUsesDefaultTtl() {
        given(tokenService.issueForAccount(ACCOUNT, AccountTokenRequest.DEFAULT_TTL_SECONDS))
                .willReturn(token());

        ApiResponse<AccountTokenVO> resp = controller.issueToken(ACCOUNT, null);

        assertThat(resp.getData().accessToken()).isEqualTo("acc");
        verify(tokenService).issueForAccount(ACCOUNT, AccountTokenRequest.DEFAULT_TTL_SECONDS);
    }

    /** 归一化：ttl_seconds=null 也回退缺省 90d（effectiveTtlSeconds）。 */
    @Test
    void issueTokenWithNullTtlFallsBackToDefaultTtl() {
        given(tokenService.issueForAccount(ACCOUNT, AccountTokenRequest.DEFAULT_TTL_SECONDS))
                .willReturn(token());

        ApiResponse<AccountTokenVO> resp = controller.issueToken(ACCOUNT, new AccountTokenRequest(null));

        assertThat(resp.getData().accessToken()).isEqualTo("acc");
        verify(tokenService).issueForAccount(ACCOUNT, AccountTokenRequest.DEFAULT_TTL_SECONDS);
    }

    /** 显式 ttl 原样透传（3600s），不走缺省。 */
    @Test
    void issueTokenForwardsExplicitTtl() {
        given(tokenService.issueForAccount(ACCOUNT, 3600)).willReturn(token());

        ApiResponse<AccountTokenVO> resp = controller.issueToken(ACCOUNT, new AccountTokenRequest(3600));

        assertThat(resp.getData().accessToken()).isEqualTo("acc");
        verify(tokenService).issueForAccount(ACCOUNT, 3600);
    }

    @Test
    void deleteForwardsAccountAndReturnsEmptyEnvelope() {
        ApiResponse<Void> resp = controller.delete(ACCOUNT);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData()).isNull();
        verify(accountService).delete(ACCOUNT);
    }

    @Test
    void listForwardsPagingAndReturnsPage() {
        PageVO<AccountVO> page = new PageVO<>(List.of(vo()), 1, 3, 50, false);
        given(accountService.list(3L, 50L)).willReturn(page);

        ApiResponse<PageVO<AccountVO>> resp = controller.list(3, 50);

        assertThat(resp.getData()).isSameAs(page);
        verify(accountService).list(3L, 50L);
    }

    @Test
    void detailForwardsAccount() {
        given(accountService.detail(ACCOUNT)).willReturn(vo());

        ApiResponse<AccountVO> resp = controller.detail(ACCOUNT);

        assertThat(resp.getData().id()).isEqualTo(100L);
        verify(accountService).detail(ACCOUNT);
    }
}
