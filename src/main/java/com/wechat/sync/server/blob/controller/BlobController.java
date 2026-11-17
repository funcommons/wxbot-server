package com.wechat.sync.server.blob.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.blob.BlobStorage;
import com.wechat.sync.server.blob.dto.BlobUploadVO;
import com.wechat.sync.server.message.entity.WxBlobEntity;
import com.wechat.sync.server.message.mapper.WxBlobMapper;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;

/**
 * blob 上传 / 下载接口（API_DESIGN §4.4 / §5.9 / SYNCH_PLAN §9.4）。
 *
 * <p><b>PUT 是 client 角色字节入口</b>：body 是解密后明文的原始字节，经
 * {@code HttpServletRequest#getInputStream()} 流式读（{@code consumes=octet-stream} 把
 * JSON 请求体挡在路由层 → 415 原始 HTTP 无信封，这是 API_DESIGN §1「路由层拦截」的明示口径，
 * 同时保证 Spring 的 HttpMessageConverter / Jackson 完全不碰 body）。
 * 处理序：查 wx_blob 表 + 盘上文件 → 命中即幂等返回 exists（跨客户端去重发生在这里）；
 * 未命中 → 落盘 + 写后校验 + 原子转正 → INSERT（先查后插 + 撞 uk 兜底）。10602/10505
 * 经 {@link ApiException} 走 HTTP 200 信封。
 *
 * <p><b>不校验账号绑定</b>（与 /v1/reports/* 不同）：契约的绑定硬规则是「载荷 account 与
 * claims.account 一致」（API_DESIGN §3），blob 上传载荷没有 account 字段，无法比对；
 * 内容寻址 + append-only 决定了误传只产生一条无人引用的 wx_blob 行（GC 判据按引用计数），
 * 不会污染任何账号的数据。内网部署前提见 API_DESIGN §3（对外暴露则开 @RequiresSignature）。
 *
 * <p><b>GET 是附件下载唯一出口</b>（API_DESIGN §5.9 / D3，不提供 /attachments/{id}/content）：
 * admin 角色。返回 {@code ResponseEntity<FileSystemResource>} 原始字节流——<b>不带 ApiResponse
 * 信封</b>，这是信封契约的明示例外：路由层二进制响应（API_DESIGN §1 允许），带信封会破坏
 * octet-stream 语义；404 仍走 10400 信封（异常路径经 GlobalExceptionHandler）。
 */
@Slf4j
@RestController
@RequestMapping("/v1/blobs")
@RequiredArgsConstructor
public class BlobController {

    private final BlobStorage storage;
    private final WxBlobMapper blobMapper;

    /** 上传附件字节：查重 → 落盘校验 → 原子转正 → wx_blob 落行。 */
    @PutMapping(value = "/{sha256}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @RequiresToken(value = TokenService.CLIENT_TYPE, roles = TokenService.ROLE_CLIENT)
    public ApiResponse<BlobUploadVO> upload(@PathVariable("sha256") String sha256,
                                            HttpServletRequest request) {
        String sha = storage.normalizeSha256(sha256);
        long contentLength = request.getContentLengthLong();
        storage.checkLimit(sha, contentLength > 0 ? contentLength : null);

        // 查重（SYNCH_PLAN §9.4 第 2 步）：表行在且盘上文件在 → 字节早已存在，body 直接丢弃
        WxBlobEntity row = findBySha256(sha);
        if (row != null && storage.exists(sha)) {
            return ApiResponse.success(new BlobUploadVO(sha, BlobStorage.STATUS_EXISTS, row.getSize()));
        }

        BlobStorage.Stored stored;
        try (InputStream body = request.getInputStream()) {
            stored = storage.store(body, sha, contentLength > 0 ? contentLength : null);
        } catch (IOException e) {
            // 盘满 / 权限 / 中途断连：临时件已在 store 内清理，10503 上传失败
            throw new ApiException(ApiCode.UPLOAD_FAILED, "blob 落盘失败: " + sha + " (" + e.getMessage() + ")");
        }

        if (row == null) {
            return ApiResponse.success(new BlobUploadVO(sha, insertBlob(sha, stored), stored.size()));
        }
        // 表行在而文件缺（盘被清/搬库）：本次重新落盘补齐，size 以实际字节为准
        updateSizeIfChanged(row, stored.size());
        return ApiResponse.success(new BlobUploadVO(sha, stored.status(), stored.size()));
    }

    /** 下载附件字节（§5.9 唯一出口，admin）：按内容哈希取明文，同一文件永远同一 URL。 */
    @GetMapping("/{sha256}")
    @RequiresToken(value = TokenService.ADMIN_TYPE, roles = TokenService.ROLE_ADMIN)
    public ResponseEntity<FileSystemResource> download(@PathVariable("sha256") String sha256) {
        String sha = storage.normalizeSha256(sha256);
        if (findBySha256(sha) == null) {
            throw new ApiException(ApiCode.NOT_FOUND, "blob 不存在: " + sha);
        }
        BlobStorage.BlobFile file = storage.locate(sha);
        // 内容寻址 = 永不可变，长缓存安全（D3「缓存友好」）；文件流式读，写出阶段的 IO 错误
        // 发生在响应头已提交之后，无法再翻成信封（HTTP 语义限制，客户端凭 sha256 自校验）
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.size())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .body(new FileSystemResource(file.path()));
    }

    /** 先查后插（uk 是部分唯一索引 ON (sha256) WHERE is_deleted=0，查询带同谓词）。 */
    private WxBlobEntity findBySha256(String sha256) {
        return blobMapper.selectOne(new LambdaQueryWrapper<WxBlobEntity>()
                .eq(WxBlobEntity::getSha256, sha256)
                .eq(WxBlobEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    /**
     * INSERT wx_blob（ON 冲突跳过语义）：并发同内容 PUT 撞 uk_wx_blob_sha256 → 字节已由先到者
     * 持有记录，回 {@code exists}（本次落盘的字节与先到者相同，谁也不亏）。
     *
     * @return 本次生效的 status ∈ stored | exists
     */
    private String insertBlob(String sha256, BlobStorage.Stored stored) {
        WxBlobEntity row = new WxBlobEntity();
        row.setSha256(sha256);
        row.setSize(stored.size());
        row.setStoragePath(storage.relativePath(sha256));
        row.setIsDeleted(0);
        try {
            blobMapper.insert(row);
            return stored.status();
        } catch (DuplicateKeyException e) {
            log.info("[Blob] 并发同内容上传让位 sha={}（wx_blob 记录由先到请求持有）", sha256);
            return BlobStorage.STATUS_EXISTS;
        }
    }

    /** 元数据 size 与实际字节不一致时校正（DDL size NOT NULL，无需补空）。 */
    private void updateSizeIfChanged(WxBlobEntity row, long size) {
        if (row.getSize() != null && row.getSize() == size) {
            return;
        }
        blobMapper.update(null, new LambdaUpdateWrapper<WxBlobEntity>()
                .eq(WxBlobEntity::getId, row.getId())
                .set(WxBlobEntity::getSize, size)
                .set(WxBlobEntity::getUpdatedAt, OffsetDateTime.now()));
        log.info("[Blob] wx_blob.size 校正为实际字节 sha={} {} -> {}", row.getSha256(), row.getSize(), size);
    }
}
