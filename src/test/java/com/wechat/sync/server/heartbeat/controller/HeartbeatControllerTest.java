package com.wechat.sync.server.heartbeat.controller;

import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.heartbeat.dto.HeartbeatRequest;
import com.wechat.sync.server.heartbeat.dto.HeartbeatVO;
import com.wechat.sync.server.heartbeat.service.HeartbeatService;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

/** HeartbeatController 单测：信封取值 + 校验次序 requireAccount(10101) → requireRegistered(10300/10601) → heartbeat。 */
@ExtendWith(MockitoExtension.class)
class HeartbeatControllerTest {

    private static final String ACCOUNT = "wxid_t5unit_a";

    @Mock
    private HeartbeatService heartbeatService;

    @Mock
    private AccountBinding accountBinding;

    private HeartbeatController controller;

    @BeforeEach
    void setUp() {
        controller = new HeartbeatController(heartbeatService, accountBinding);
    }

    private static HeartbeatRequest request(String account) {
        return new HeartbeatRequest(account, "host-a", "3.9.10", "4.0.6", 4096L, Boolean.TRUE);
    }

    @Test
    void heartbeatReturnsSuccessEnvelopeAndChecksBindingBeforeWrite() {
        HeartbeatRequest req = request(ACCOUNT);
        OffsetDateTime now = OffsetDateTime.now();
        given(heartbeatService.requireAccount(ACCOUNT)).willReturn(ACCOUNT);
        given(heartbeatService.heartbeat(ACCOUNT, req)).willReturn(new HeartbeatVO(now));

        ApiResponse<HeartbeatVO> resp = controller.heartbeat(req);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getError()).isNull();
        assertThat(resp.getData().serverTime()).isEqualTo(now);

        InOrder order = inOrder(heartbeatService, accountBinding);
        order.verify(heartbeatService).requireAccount(ACCOUNT);
        order.verify(accountBinding).requireRegistered(ACCOUNT);
        order.verify(heartbeatService).heartbeat(ACCOUNT, req);
    }

    @Test
    void missingAccountThrows10101BeforeBindingCheck() {
        given(heartbeatService.requireAccount("  "))
                .willThrow(new ApiException(ApiCode.PARAM_MISSING, "account 必填"));

        assertThatThrownBy(() -> controller.heartbeat(request("  ")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));

        verifyNoInteractions(accountBinding);
    }
}
