package com.wechat.sync.server.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /v1/accounts 请求体（API_DESIGN §6）。
 *
 * @param account 目录名，如 wxid_c00pwixax7xv22_8316，必填
 * @param wxid    剥掉末尾 4 位后缀的 wxid，可空（缺省由 account 推导）
 */
public record AccountRegisterRequest(
        @NotBlank(message = "account 不能为空")
        @Size(max = 64, message = "account 长度不能超过 64")
        String account,

        @Size(max = 64, message = "wxid 长度不能超过 64")
        String wxid) {
}
