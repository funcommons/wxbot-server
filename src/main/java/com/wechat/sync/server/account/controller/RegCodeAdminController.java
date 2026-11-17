package com.wechat.sync.server.account.controller;

import com.wechat.sync.server.account.dto.RegCodeIssueVO;
import com.wechat.sync.server.account.dto.RegCodeVO;
import com.wechat.sync.server.account.service.RegCodeService;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册码管理接口（TASK_DISPATCH §6，角色 admin）：签发 / 作废 / 审计列表。
 *
 * <p>明文码只在 {@link #issue} 响应出现一次（{@link RegCodeIssueVO#code()}），列表端点
 * {@link RegCodeVO} 刻意不含明文——管理端拿到的是审计视图（label / 预绑定 / 消费痕迹）。
 * 异常一律上抛（不存在 → 10400，RegCodeService 内抛），由 GlobalExceptionHandler 翻译信封。
 */
@RestController
@RequiredArgsConstructor
@RequiresToken(value = TokenService.ADMIN_TYPE, roles = TokenService.ROLE_ADMIN)
public class RegCodeAdminController {

    private final RegCodeService regCodeService;

    /** 签发注册码：body 全可空（label 备注 / account 预绑定 / expires_hours 缺省 24h）；明文只此一次。 */
    @PostMapping("/v1/reg-codes")
    public ApiResponse<RegCodeIssueVO> issue(
            @Valid @RequestBody(required = false) RegCodeIssueRequest request) {
        RegCodeIssueRequest req = request == null
                ? new RegCodeIssueRequest(null, null, null) : request;
        return ApiResponse.success(regCodeService.issueDetailed(req.label(), req.account(), req.expiresHours()));
    }

    /** 作废（软删）：已消费的也可删——审计凭 used_by/used_at 留痕，软删只是断掉再消费的可能。 */
    @DeleteMapping("/v1/reg-codes/{id}")
    public ApiResponse<Void> revoke(@PathVariable("id") long id) {
        regCodeService.revoke(id);
        return ApiResponse.success();
    }

    /** 列表（Offset 分页）：不过滤软删（审计视图），不回明文。 */
    @GetMapping("/v1/reg-codes")
    public ApiResponse<PageVO<RegCodeVO>> list(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success(regCodeService.list(page, pageSize));
    }

    /** POST /v1/reg-codes 入参（全可空）。record 嵌在本控制器：仅此一处使用，不值得独立文件。 */
    public record RegCodeIssueRequest(String label, String account, Integer expiresHours) {
    }
}
