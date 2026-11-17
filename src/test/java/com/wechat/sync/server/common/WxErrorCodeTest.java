package com.wechat.sync.server.common;

import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 106xx 业务错误码登记（mc-api-spec §7.10：业务线自认领段，不与框架 10xxx 冲突）。 */
class WxErrorCodeTest {

    @Test
    void constantsOccupyBusinessCustomSegment() {
        assertThat(WxErrorCode.ACCOUNT_NOT_REGISTERED).isEqualTo(10601);
        assertThat(WxErrorCode.BLOB_CHECKSUM_MISMATCH).isEqualTo(10602);
    }

    @Test
    void factoriesCarryCodeAndOffendingValue() {
        ApiException a = WxErrorCode.accountNotRegistered("wxid_a_0001");
        assertThat(a.getCode()).isEqualTo(10601);
        assertThat(a.getMessage()).contains("wxid_a_0001");

        ApiException b = WxErrorCode.blobChecksumMismatch("deadbeef");
        assertThat(b.getCode()).isEqualTo(10602);
        assertThat(b.getMessage()).contains("deadbeef");
    }
}
