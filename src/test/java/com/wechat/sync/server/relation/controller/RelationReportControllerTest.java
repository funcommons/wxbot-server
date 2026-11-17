package com.wechat.sync.server.relation.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.relation.dto.RelationsIngestVO;
import com.wechat.sync.server.relation.dto.RelationsReportRequest;
import com.wechat.sync.server.relation.service.RelationsIngestService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.BDDMockito.given;

/** 上报端点：@RequiresToken 接线 + requireAccount→requireRegistered 次序 + 成功信封。 */
@ExtendWith(MockitoExtension.class)
class RelationReportControllerTest {

    private static final String ACCOUNT = "wxid_a_0001";

    @Mock private RelationsIngestService ingestService;
    @Mock private AccountBinding accountBinding;

    private RelationReportController controller;

    @BeforeEach
    void setUp() { controller = new RelationReportController(ingestService, accountBinding); }

    private RelationsIngestVO vo() {
        return new RelationsIngestVO(ACCOUNT, new RelationsIngestVO.Upserted(1, 0, 2, 0, 0, 0));
    }

    /** 注解即契约：仅 client 令牌可进（admin 按 type 不匹配被框架拦截）、路由与 @RequestBody 接线。 */
    @Test
    void classDeclaresClientTokenGuardAndRoute() throws NoSuchMethodException {
        RequiresToken guard = RelationReportController.class.getAnnotation(RequiresToken.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo(TokenService.CLIENT_TYPE);
        assertThat(guard.roles()).containsExactly(TokenService.ROLE_CLIENT);
        assertThat(RelationReportController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/v1/reports");
        Method report = RelationReportController.class.getMethod("report", RelationsReportRequest.class);
        assertThat(report.getAnnotation(PostMapping.class).value()).containsExactly("/relations");
        assertThat(report.getParameterAnnotations()[0]).anySatisfy(a -> assertThat(a).isInstanceOf(RequestBody.class));
    }

    /** 转发次序：先 requireAccount（10101）再 requireRegistered（10300/10601），最后 ingest，同一载荷。 */
    @Test
    void reportValidatesBindingInOrderThenReturnsEnvelope() {
        RelationsReportRequest req = new RelationsReportRequest(ACCOUNT, null, null, null, null, null, null);
        RelationsIngestVO stubVo = vo(); // 同一实例：ApiResponse.success 只透传引用，不拷贝
        given(ingestService.requireAccount(ACCOUNT)).willReturn(ACCOUNT);
        given(ingestService.ingest(req)).willReturn(stubVo);

        ApiResponse<RelationsIngestVO> resp = controller.report(req);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData()).isSameAs(stubVo);
        assertThat(resp.getData().account()).isEqualTo(ACCOUNT);
        assertThat(resp.getData().upserted()).isEqualTo(new RelationsIngestVO.Upserted(1, 0, 2, 0, 0, 0));
        InOrder order = inOrder(ingestService, accountBinding);
        order.verify(ingestService).requireAccount(ACCOUNT);
        order.verify(accountBinding).requireRegistered(ACCOUNT);
        order.verify(ingestService).ingest(req);
    }

    /** account 缺失（10101）先于绑定校验：载荷缺 account 是参数缺失而非越权，次序不能反。 */
    @Test
    void reportSkipsBindingWhenPayloadAccountMissing() {
        given(ingestService.requireAccount(null))
                .willThrow(new ApiException(ApiCode.PARAM_MISSING, "account 必填"));

        assertThatThrownBy(() -> controller.report(
                new RelationsReportRequest(null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));

        verifyNoInteractions(accountBinding);
        verify(ingestService, never()).ingest(any());
    }
}
