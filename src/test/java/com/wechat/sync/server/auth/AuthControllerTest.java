package com.wechat.sync.server.auth;

import com.wechat.sync.server.account.dto.AccountTokenRequest;
import com.wechat.sync.server.account.dto.AccountTokenVO;
import com.wechat.sync.server.account.dto.RegCodeIssueVO;
import com.wechat.sync.server.account.dto.RegCodeVO;
import com.wechat.sync.server.account.controller.RegCodeAdminController;
import com.wechat.sync.server.account.service.RegCodeService;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.auth.dto.RefreshRequest;
import com.wechat.sync.server.auth.dto.RegisterRequest;
import com.wechat.sync.server.auth.dto.RegisterVO;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** /v1/auth 端点：register 无鉴权契约 + refresh 型别注解 + 转发与信封（不起 Spring 上下文）。 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private static final String ACCOUNT = "wxid_t9test_a";

    @Mock private RegCodeService regCodeService;
    @Mock private TokenService tokenService;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(regCodeService, tokenService);
    }

    @Test
    void registerForwardsAndWrapsAccountPlusTokenTriple() {
        RegisterRequest request =
                new RegisterRequest("wxreg_t9", ACCOUNT, "wxid_t9", "host-t9", "4.2.0.11");
        given(regCodeService.registerWithCode(request)).willReturn(ACCOUNT);
        given(tokenService.issueForAccount(ACCOUNT, AccountTokenRequest.DEFAULT_TTL_SECONDS))
                .willReturn(new AccountTokenVO("acc-t9", "ref-t9", "2026-11-11T00:00:00Z"));

        ApiResponse<RegisterVO> response = controller.register(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCode()).isZero();
        assertThat(response.getData().account()).isEqualTo(ACCOUNT);
        assertThat(response.getData().accessToken()).isEqualTo("acc-t9");
        assertThat(response.getData().refreshToken()).isEqualTo("ref-t9");
        assertThat(response.getData().expiresAt()).isEqualTo("2026-11-11T00:00:00Z");
        verify(tokenService).issueForAccount(ACCOUNT, AccountTokenRequest.DEFAULT_TTL_SECONDS); // 缺省 90d
    }

    @Test
    void refreshForwardsTokenAndPassesVoThrough() {
        AccountTokenVO rotated = new AccountTokenVO("acc-2", "ref-2", "2026-11-12T00:00:00Z");
        given(tokenService.refreshForClient("ref-t9")).willReturn(rotated);

        ApiResponse<AccountTokenVO> response = controller.refresh(new RefreshRequest("ref-t9"));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(rotated).as("三字段整体透传");
        verify(tokenService).refreshForClient("ref-t9");
    }

    /** 鉴权契约：register **无** @RequiresToken（一次性码即凭证）；refresh 走 client 型别 refresh 令牌。 */
    @Test
    void authContractAnnotations() throws NoSuchMethodException {
        Method register = AuthController.class.getMethod("register", RegisterRequest.class);
        assertThat(register.getAnnotation(RequiresToken.class)).as("自助注册无鉴权").isNull();
        assertThat(register.getAnnotation(PostMapping.class).value()).containsExactly("/register");
        assertThat(register.getParameterAnnotations()[0]).as("@Valid 前置校验").anySatisfy(a ->
                assertThat(a).isInstanceOf(Valid.class));

        Method refresh = AuthController.class.getMethod("refresh", RefreshRequest.class);
        RequiresToken guard = refresh.getAnnotation(RequiresToken.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo(TokenService.CLIENT_TYPE);
        assertThat(guard.type()).isEqualTo("refresh");
        assertThat(refresh.getAnnotation(PostMapping.class).value()).containsExactly("/refresh");
    }

    /** /v1/reg-codes 三端点（T9-5b）：类级 admin 防线 + 路由形状。 */
    @Test
    void regCodeAdminDeclaresAdminGuardAndRoutes() throws NoSuchMethodException {
        RequiresToken classGuard = RegCodeAdminController.class.getAnnotation(RequiresToken.class);
        assertThat(classGuard).isNotNull();
        assertThat(classGuard.value()).isEqualTo(TokenService.ADMIN_TYPE);
        assertThat(classGuard.roles()).containsExactly(TokenService.ROLE_ADMIN);

        Method issue = RegCodeAdminController.class
                .getMethod("issue", RegCodeAdminController.RegCodeIssueRequest.class);
        assertThat(issue.getAnnotation(PostMapping.class).value()).containsExactly("/v1/reg-codes");
        assertThat(issue.getReturnType()).isEqualTo(ApiResponse.class).as("响应是含明文的 RegCodeIssueVO 信封");

        Method revoke = RegCodeAdminController.class.getMethod("revoke", long.class);
        assertThat(revoke.getAnnotation(DeleteMapping.class).value()).containsExactly("/v1/reg-codes/{id}");

        Method list = RegCodeAdminController.class.getMethod("list", long.class, long.class);
        assertThat(list.getAnnotation(GetMapping.class).value()).containsExactly("/v1/reg-codes");
        assertThat(list.getReturnType()).isEqualTo(ApiResponse.class);
        assertThat(RegCodeVO.class.getDeclaredFields()).noneMatch(f -> f.getName().equals("code"))
                .as("列表 VO 无明文字段");
    }
}
