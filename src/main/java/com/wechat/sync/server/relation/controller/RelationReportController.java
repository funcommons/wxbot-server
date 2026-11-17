package com.wechat.sync.server.relation.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.relation.dto.RelationsIngestVO;
import com.wechat.sync.server.relation.dto.RelationsReportRequest;
import com.wechat.sync.server.relation.service.RelationsIngestService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关系层上报接口（API_DESIGN §4.1，角色 client）。
 *
 * <p>client 令牌才可进（admin 令牌按 {@code type} 不匹配被框架拦截）；绑定硬规则见
 * {@link AccountBinding}：载荷 account 须与 claims.account 一致（10300）且已注册（10601）。
 * 异常一律上抛，由 framework4j-web 的 GlobalExceptionHandler 翻成 HTTP 200 信封；
 * 幂等由 framework4j-idempotency 对写方法自动处理（Idempotency-Key，48h 重放返回同一响应）；
 * 限流配额（6 次/分钟每账号）在 T7 统一接线。
 */
@RestController
@RequestMapping("/v1/reports")
@RequiredArgsConstructor
@RequiresToken(value = TokenService.CLIENT_TYPE, roles = TokenService.ROLE_CLIENT)
public class RelationReportController {

    private final RelationsIngestService ingestService;
    private final AccountBinding accountBinding;

    /** 关系层上报：contacts / groups / group_members / labels / label_members / conversations 六类单事务 upsert。 */
    @PostMapping("/relations")
    public ApiResponse<RelationsIngestVO> report(@Valid @RequestBody RelationsReportRequest request) {
        accountBinding.requireRegistered(ingestService.requireAccount(request.account()));
        return ApiResponse.success(ingestService.ingest(request));
    }
}
