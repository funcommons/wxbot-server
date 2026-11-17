package com.wechat.sync.server.config;

import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.account.service.AccountService;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.support.MpTestTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** admin 令牌 bootstrap（P1-③）：seed 空=禁用；seed 非空 + 库空=注册 dev 账号并签发。 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock private AccountService accountService;
    @Mock private TokenService tokenService;
    @Mock private WxAccountMapper accountMapper;

    @BeforeAll
    static void initTableInfo() { MpTestTables.init(WxAccountEntity.class); }

    private AdminBootstrap bootstrap(String seed) {
        return new AdminBootstrap(seed, accountService, tokenService, accountMapper);
    }

    /** 库空（含 dev 账号查重也为 0）的 stub */
    private void stubEmptyDb() {
        given(accountMapper.selectCount(any())).willReturn(0L);
        given(tokenService.issueAdminToken(any(), any()))
                .willReturn(new AccountTokenVO("a", "r", "t"));
    }

    @Test
    void runWithBlankSeedIsDisabled() {
        bootstrap("").run();
        bootstrap("   ").run();
        verifyNoInteractions(accountService, tokenService, accountMapper);
    }

    @Test
    void runSkipsWhenAccountTableNotEmpty() {
        given(accountMapper.selectCount(any())).willReturn(5L);

        bootstrap("seed").run();

        verifyNoInteractions(tokenService, accountService);
    }

    @Test
    void runRegistersDevAccountAndIssuesAdminTokenWhenDbEmpty() {
        stubEmptyDb();

        bootstrap("  seed-1  ").run();

        ArgumentCaptor<AccountRegisterRequest> req = ArgumentCaptor.forClass(AccountRegisterRequest.class);
        verify(accountService).register(req.capture());
        assertThat(req.getValue().account()).isEqualTo(AdminBootstrap.BOOTSTRAP_ACCOUNT);
        assertThat(req.getValue().wxid()).isNull();
        verify(tokenService).issueAdminToken("seed-1", AdminBootstrap.BOOTSTRAP_ACCOUNT);
    }

    @Test
    void runReusesExistingDevAccountWithoutRegistering() {
        given(accountMapper.selectCount(any())).willReturn(0L, 1L);
        given(tokenService.issueAdminToken(any(), any())).willReturn(new AccountTokenVO("a", "r", "t"));

        bootstrap("seed").run();

        verify(accountService, never()).register(any());
        verify(tokenService).issueAdminToken("seed", AdminBootstrap.BOOTSTRAP_ACCOUNT);
    }

    @Test
    void runDoesNotPropagateBootstrapFailure() {
        // register 抛异常后流程即中断，不会触达 issueAdminToken，故只 stub 库空检查
        given(accountMapper.selectCount(any())).willReturn(0L);
        doThrow(new IllegalStateException("boom")).when(accountService).register(any());

        assertThatCode(() -> bootstrap("seed").run()).doesNotThrowAnyException();

        verify(tokenService, never()).issueAdminToken(any(), any());
    }
}
