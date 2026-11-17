package com.wechat.sync.server.message.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.message.dto.MessagesIngestVO;
import com.wechat.sync.server.message.dto.MessagesReportRequest;
import com.wechat.sync.server.message.service.MessagesIngestService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息批上报接口（API_DESIGN §4.2，角色 client）——去重核心入口。
 *
 * <p>client 令牌才可进（admin 令牌按 {@code type} 不匹配被框架拦截）；绑定硬规则见
 * {@link AccountBinding}：载荷 account 须与 claims.account 一致（10300）且已注册（10601），
 * {@code requireRegistered} 内含 {@code checkBound}（缺 account 的 10101 由 service 的
 * {@code requireAccount} 先行抛出，次序不能反）。异常一律上抛由 GlobalExceptionHandler 翻成 HTTP 200 信封；
 * 幂等由 framework4j-idempotency 对写方法自动处理（Idempotency-Key，48h 重放返回同一响应）；
 * 限流（30 次/分钟每账号，§9）在 T7 统一接线。
 *
 * <p>信封取值（API_DESIGN §4.2 结果码语义）：results 全量且无失败 → 0；部分失败 →
 * {@link ApiResponse#partialSuccess}（code=10700，data.results 只含成功条目 + error[] 列失败字段，
 * 框架原生支持 data 与 error 并存）；整批无一条合法 → 10100（data=null）。
 */
@RestController
@RequestMapping("/v1/reports")
@RequiredArgsConstructor
@RequiresToken(value = TokenService.CLIENT_TYPE, roles = TokenService.ROLE_CLIENT)
public class MessageReportController {

    private final MessagesIngestService ingestService;
    private final AccountBinding accountBinding;

    /** 消息批上报：逐 chat 事务 upsert + fb→srv 合并 + seen_accounts 反算 + 游标镜像 + 批次日志。 */
    @PostMapping("/messages")
    public ApiResponse<MessagesIngestVO> report(@Valid @RequestBody MessagesReportRequest request) {
        accountBinding.requireRegistered(ingestService.requireAccount(request.account()));
        MessagesIngestService.IngestOutcome outcome = ingestService.ingest(request);
        if (outcome.data().results().isEmpty()) {
            // 10100 也带 error[]：逐条失败 field 已在校验/落库阶段收集（API_DESIGN §2.1）
            throw new ApiException(ApiCode.PARAM_ERROR, "整批无一条合法消息（全部条目校验/落库失败）",
                    outcome.errors());
        }
        return outcome.errors().isEmpty()
                ? ApiResponse.success(outcome.data())
                : ApiResponse.partialSuccess(outcome.data(), outcome.errors());
    }
}
