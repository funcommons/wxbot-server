package com.wechat.sync.server.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /v1/auth/register 请求体（TASK_DISPATCH §2.1 v1.1）。
 *
 * <p><b>account 必须由客户端携带</b>：它是本地微信数据目录名（WeChatDB 的定位键），
 * 服务端造名则客户端永远打不开数据（评审 P0 结论）。服务端只做格式闸门
 * （{@code ^[A-Za-z0-9_@.\-]{1,64}$}，Illegal → 10102），合法性（未注册/预绑定一致）在事务内判。
 *
 * @param regCode       一次性注册码明文
 * @param account       本地数据目录名，如 wxid_c00pwixax7xv22_8316
 * @param wxid          剥掉末尾 4 位后缀的 wxid（客户端本地探测）
 * @param hostname      客户端所在机器（落 wx_account.hostname 审计）
 * @param clientVersion 客户端版本，可空
 */
public record RegisterRequest(
        @NotBlank(message = "reg_code 不能为空")
        String regCode,

        @NotBlank(message = "account 不能为空")
        @Size(max = 64, message = "account 长度不能超过 64")
        @Pattern(regexp = "^[A-Za-z0-9_@.\\-]{1,64}$", message = "account 含非法字符")
        String account,

        @NotBlank(message = "wxid 不能为空")
        @Size(max = 64, message = "wxid 长度不能超过 64")
        String wxid,

        @NotBlank(message = "hostname 不能为空")
        @Size(max = 128, message = "hostname 长度不能超过 128")
        String hostname,

        @Size(max = 32, message = "client_version 长度不能超过 32")
        String clientVersion) {
}
