package com.wechat.sync.server.query.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.query.dto.CursorPageVO;
import com.wechat.sync.server.query.dto.QueryVOs;
import com.wechat.sync.server.query.dto.SearchRequest;
import com.wechat.sync.server.query.service.QueryService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询接口（API_DESIGN §5，角色 admin）。
 *
 * <p>类级 {@code @RequiresToken(value="admin", roles="admin")}（§5 全部 admin；注解 value 是
 * token 型别名、必填）。默认跨账号全局视图，{@code ?account=} 收窄到单一来源（§5 开篇口径）。
 * 异常一律上抛由 GlobalExceptionHandler 翻成 HTTP 200 信封；限流 120 次/分钟每令牌（§9）
 * 归 T7 统一接线；查询接口只读、不参与 Idempotency。
 *
 * <p>§5.1/§5.2（GET /v1/accounts、GET /v1/accounts/{account}）由 T2 的
 * {@code AccountAdminController} 提供（同 URL 同角色，重复声明会撞 Spring 路由），
 * 本类不含；GET /v1/blobs/{sha256} 是 T5 的 BlobController（二进制无信封例外）。
 * 分页：messages / search 用 Cursor（§2.4 矩阵），其余 Offset（page&gt;1000 → 10102 禁深 offset）。
 */
@RestController
@RequiredArgsConstructor
@RequiresToken(value = TokenService.ADMIN_TYPE, roles = TokenService.ROLE_ADMIN)
public class QueryController {

    private final QueryService queryService;

    /** §5.2 会话列表（Offset）。 */
    @GetMapping("/v1/chats")
    public ApiResponse<PageVO<QueryVOs.ChatVO>> chats(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) Boolean isGroup,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success(queryService.chats(account, isGroup, page, pageSize));
    }

    /** §5.3 消息流（Cursor，(create_time, id) 倒序，list[] 含 sources 摘要）。 */
    @GetMapping("/v1/messages")
    public ApiResponse<CursorPageVO<QueryVOs.MessageVO>> messages(
            @RequestParam String chatUsername,
            @RequestParam(required = false) String createTimeAfter,
            @RequestParam(required = false) String createTimeBefore,
            @RequestParam(required = false) String senderUsername,
            @RequestParam(required = false) String typeName,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(queryService.messages(chatUsername, createTimeAfter, createTimeBefore,
                senderUsername, typeName, cursor, limit));
    }

    /** §5.5 复杂检索（POST + body；与 §5.3 同一套 keyset 位点）。 */
    @PostMapping("/v1/messages/search")
    public ApiResponse<CursorPageVO<QueryVOs.MessageVO>> search(
            @Valid @RequestBody(required = false) SearchRequest request) {
        return ApiResponse.success(queryService.search(request));
    }

    /** §5.4 单条详情（全文 + sources 全量 + attachments 元数据）。 */
    @GetMapping("/v1/messages/{message_id}")
    public ApiResponse<QueryVOs.MessageVO> messageDetail(
            @PathVariable("message_id") String messageId) {
        return ApiResponse.success(queryService.messageDetail(messageId));
    }

    /** §5.6 联系人（Offset，含 source）。 */
    @GetMapping("/v1/contacts")
    public ApiResponse<PageVO<QueryVOs.ContactVO>> contacts(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success(queryService.contacts(account, username, page, pageSize));
    }

    /** §5.7 群成员（union=true 走视图 v_wx_group_members_union，返回 seen_in_accounts）。 */
    @GetMapping("/v1/groups/{group_username}/members")
    public ApiResponse<PageVO<QueryVOs.GroupMemberVO>> groupMembers(
            @PathVariable("group_username") String groupUsername,
            @RequestParam(required = false) String account,
            @RequestParam(defaultValue = "false") boolean union,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success(queryService.groupMembers(groupUsername, account, union, page, pageSize));
    }

    /** §5.8 附件元数据（blob_sha256 可拼 /v1/blobs/{sha256} 下载，D3）。 */
    @GetMapping("/v1/attachments/{attachment_id}")
    public ApiResponse<QueryVOs.AttachmentVO> attachment(
            @PathVariable("attachment_id") String attachmentId) {
        return ApiResponse.success(queryService.attachment(attachmentId));
    }

    /** §5.10 去重统计（视图 v_wx_message_dedup）。 */
    @GetMapping("/v1/statistics/dedup")
    public ApiResponse<PageVO<QueryVOs.DedupVO>> dedupStatistics(
            @RequestParam(required = false) String chatUsername,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success(queryService.dedupStatistics(chatUsername, page, pageSize));
    }

    /** §5.11 最近上报批次（观测）。 */
    @GetMapping("/v1/ingest-batches")
    public ApiResponse<PageVO<QueryVOs.IngestBatchVO>> ingestBatches(
            @RequestParam(required = false) String account,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success(queryService.ingestBatches(account, page, pageSize));
    }
}
