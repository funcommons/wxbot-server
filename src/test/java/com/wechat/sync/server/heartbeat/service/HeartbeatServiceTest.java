package com.wechat.sync.server.heartbeat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.heartbeat.dto.HeartbeatRequest;
import com.wechat.sync.server.heartbeat.dto.HeartbeatVO;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * HeartbeatService 单测：更新 last_seen_at / hostname / client_version 三列；缺省字段不覆写旧值
 * （缺字段 ≠ 清字段）；不碰 last_sync_at；updated != 1 → 10400；requireAccount 缺失 → 10101 并去空白。
 */
@ExtendWith(MockitoExtension.class)
class HeartbeatServiceTest {

    private static final String ACCOUNT = "wxid_t5unit_a";

    @Mock
    private WxAccountMapper accountMapper;

    @Captor
    private ArgumentCaptor<LambdaUpdateWrapper<WxAccountEntity>> update;

    private HeartbeatService service;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxAccountEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new HeartbeatService(accountMapper);
    }

    private static HeartbeatRequest request(String hostname, String clientVersion) {
        return new HeartbeatRequest(ACCOUNT, hostname, clientVersion, "4.0.6", 4096L, Boolean.TRUE);
    }

    @Test
    void heartbeatUpdatesLastSeenHostnameAndClientVersion() {
        given(accountMapper.update(isNull(), any())).willReturn(1);

        HeartbeatVO vo = service.heartbeat(ACCOUNT, request(" host-a ", " 3.9.10 "));

        verify(accountMapper).update(isNull(), update.capture());
        LambdaUpdateWrapper<WxAccountEntity> wrapper = update.getValue();
        assertThat(wrapper.getSqlSet())
                .contains("last_seen_at=", "hostname=", "client_version=")
                .doesNotContain("last_sync_at=", "api_key_hash=", "updated_at=");
        wrapper.getSqlSegment(); // WHERE 段懒求值：渲染后参数才进 paramNameValuePairs
        assertThat(wrapper.getParamNameValuePairs())
                .containsValue(ACCOUNT)
                .containsValue("host-a")
                .containsValue("3.9.10")
                .containsValue(0);
        assertThat(vo.serverTime()).isNotNull();
    }

    @Test
    void blankHostnameAndClientVersionAreNotOverwritten() {
        given(accountMapper.update(isNull(), any())).willReturn(1);

        service.heartbeat(ACCOUNT, request("   ", null));

        verify(accountMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet())
                .contains("last_seen_at=")
                .doesNotContain("hostname=", "client_version=");
    }

    @Test
    void updatedZeroThrows10400AsAccountGone() {
        given(accountMapper.update(isNull(), any())).willReturn(0);

        assertThatThrownBy(() -> service.heartbeat(ACCOUNT, request(null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }

    @Test
    void requireAccountRejectsBlankWith10101AndTrims() {
        assertThatThrownBy(() -> service.requireAccount(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
        assertThatThrownBy(() -> service.requireAccount("   "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
        assertThat(service.requireAccount(" " + ACCOUNT + " ")).isEqualTo(ACCOUNT);
    }
}
