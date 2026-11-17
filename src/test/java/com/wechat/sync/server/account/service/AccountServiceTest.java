package com.wechat.sync.server.account.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.dto.AccountVO;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.BDDMockito.given;

/** 账号注册 / 查询 / 注销业务分支（软删标志 + 吊销语义）。 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String ACCOUNT = "wxid_c00pwixax7xv22_8316";

    @Mock private WxAccountMapper accountMapper;
    @Mock private TokenService tokenService;

    private AccountService service;

    @BeforeAll
    static void initTableInfo() { MpTestTables.init(WxAccountEntity.class); }

    @BeforeEach
    void setUp() { service = new AccountService(accountMapper, tokenService); }

    private WxAccountEntity existing() {
        WxAccountEntity e = new WxAccountEntity();
        e.setId(100L);
        e.setAccount(ACCOUNT);
        e.setWxid("wxid_c00pwixax7xv22");
        e.setHostname("dev-1");
        e.setIsDeleted(0);
        return e;
    }

    @Test
    void registerDuplicateThrowsUniqueConflictAndSkipsInsert() {
        given(accountMapper.selectOne(any())).willReturn(existing());

        assertThatThrownBy(() -> service.register(new AccountRegisterRequest(ACCOUNT, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10401));
        verify(accountMapper, never()).insert(any(WxAccountEntity.class));
    }

    @Test
    void registerTrimsAccountMapsFieldsAndDerivesWxid() {
        given(accountMapper.selectOne(any())).willReturn(null);
        doAnswer(inv -> { inv.getArgument(0, WxAccountEntity.class).setId(7L); return 1; })
                .when(accountMapper).insert(any(WxAccountEntity.class));

        AccountVO vo = service.register(new AccountRegisterRequest("  " + ACCOUNT + "  ", " wxid_plain "));
        service.register(new AccountRegisterRequest(ACCOUNT, "   "));
        service.register(new AccountRegisterRequest("wxid_nosuffix", null));

        assertThat(vo.id()).isEqualTo(7L);
        assertThat(vo.account()).isEqualTo(ACCOUNT);
        assertThat(vo.wxid()).isEqualTo("wxid_plain");
        ArgumentCaptor<WxAccountEntity> captor = ArgumentCaptor.forClass(WxAccountEntity.class);
        verify(accountMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(WxAccountEntity::getWxid)
                .containsExactly("wxid_plain", "wxid_c00pwixax7xv22", "wxid_nosuffix");
        assertThat(captor.getAllValues()).extracting(WxAccountEntity::getIsDeleted).containsOnly(0);
    }

    @Test
    void detailResolvesExistingAccountAndRejectsMissingOne() {
        given(accountMapper.selectOne(any())).willReturn(existing(), (WxAccountEntity) null);

        AccountVO vo = service.detail(ACCOUNT);
        assertThat(vo.id()).isEqualTo(100L);
        assertThat(vo.account()).isEqualTo(ACCOUNT);
        assertThat(vo.hostname()).isEqualTo("dev-1");

        assertThatThrownBy(() -> service.detail("ghost"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void listClampsPaginationAndQueriesUnDeletedOnly() {
        given(accountMapper.selectPage(any(), any())).willReturn(Page.of(1, 20));

        PageVO<AccountVO> vo = service.list(0, 500);

        assertThat(vo.page()).isEqualTo(1);
        assertThat(vo.pageSize()).isEqualTo(PageVO.MAX_PAGE_SIZE);
        assertThat(vo.hasMore()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<WxAccountEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(accountMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(200L);
    }

    @Test
    void listMapsRecordsAndComputesHasMore() {
        Page<WxAccountEntity> page = Page.of(1, 20);
        page.setRecords(List.of(existing()));
        page.setTotal(21);
        given(accountMapper.selectPage(any(), any())).willReturn(page);

        PageVO<AccountVO> vo = service.list(1, 20);

        assertThat(vo.list()).hasSize(1);
        assertThat(vo.list().get(0).account()).isEqualTo(ACCOUNT);
        assertThat(vo.total()).isEqualTo(21);
        assertThat(vo.hasMore()).isTrue();
    }

    @Test
    void deleteSoftDeletesThenRevokesTokens() {
        given(accountMapper.selectOne(any())).willReturn(existing());
        given(accountMapper.update(any(), any())).willReturn(1);
        given(tokenService.revokeByAccount(ACCOUNT)).willReturn(2);

        service.delete(ACCOUNT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<WxAccountEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(accountMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("is_deleted=", "updated_at=");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue(1);
        inOrder(accountMapper, tokenService).verify(accountMapper).update(any(), any());
        inOrder(accountMapper, tokenService).verify(tokenService).revokeByAccount(ACCOUNT);
    }

    @Test
    void deleteConflictingStateThrowsAndSkipsRevoke() {
        given(accountMapper.selectOne(any())).willReturn(existing());
        given(accountMapper.update(any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.delete(ACCOUNT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
        verify(tokenService, never()).revokeByAccount(any());
    }

    @Test
    void deleteMissingAccountThrowsNotFoundBeforeAnyUpdate() {
        given(accountMapper.selectOne(any())).willReturn(null);

        assertThatThrownBy(() -> service.delete("ghost"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        verify(accountMapper, never()).update(any(), any());
        verifyNoInteractions(tokenService);
    }
}
