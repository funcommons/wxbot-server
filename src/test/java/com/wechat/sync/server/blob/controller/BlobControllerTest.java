package com.wechat.sync.server.blob.controller;

import com.wechat.sync.server.blob.BlobStorage;
import com.wechat.sync.server.blob.dto.BlobUploadVO;
import com.wechat.sync.server.message.entity.WxBlobEntity;
import com.wechat.sync.server.message.mapper.WxBlobMapper;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.DelegatingServletInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * BlobController 单测：真实 BlobStorage（@TempDir 根，spy 验证交互）+ mock WxBlobMapper /
 * HttpServletRequest（body 用 DelegatingServletInputStream 包 ByteArrayInputStream）。
 * 覆盖 PUT 查重短路 / 落盘 + INSERT / 撞 uk 回 exists / 表有行盘无文件补落盘 / IO 10503 /
 * 非法 sha 先拦 / GET 原始字节无信封与 10400 两条路径。
 */
@ExtendWith(MockitoExtension.class)
class BlobControllerTest {

    private static final byte[] PAYLOAD = "controller-payload".getBytes(StandardCharsets.UTF_8);
    /** 路径参数与表行共用 PAYLOAD 的真 sha256：BlobStorage.store 写完会重算强校验，假哈希必抛 10602 */
    private static final String SHA = sha256Of(PAYLOAD);

    @TempDir
    Path root;

    @Mock
    private WxBlobMapper blobMapper;

    @Mock
    private HttpServletRequest request;

    private BlobStorage storage;
    private BlobController controller;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxBlobEntity.class);
    }

    @BeforeEach
    void setUp() {
        storage = spy(new BlobStorage(root.toString(), 512 * 1024L));
        controller = new BlobController(storage, blobMapper);
    }

    private void givenBody(byte[] bytes) throws IOException {
        given(request.getInputStream())
                .willReturn(new DelegatingServletInputStream(new ByteArrayInputStream(bytes)));
    }

    private static String sha256Of(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private WxBlobEntity row(long size) {
        WxBlobEntity row = new WxBlobEntity();
        row.setId(900L);
        row.setSha256(SHA);
        row.setSize(size);
        row.setIsDeleted(0);
        return row;
    }

    private void seedDisk(byte[] bytes) throws IOException {
        Files.createDirectories(storage.resolve(SHA).getParent());
        Files.write(storage.resolve(SHA), bytes);
    }

    @Test
    void putReturnsExistsWithoutStoreOrInsertWhenTableAndDiskHit() throws IOException {
        given(blobMapper.selectOne(any())).willReturn(row(PAYLOAD.length));
        seedDisk(PAYLOAD);

        ApiResponse<BlobUploadVO> resp = controller.upload(SHA, request);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().sha256()).isEqualTo(SHA);
        assertThat(resp.getData().status()).isEqualTo(BlobStorage.STATUS_EXISTS);
        assertThat(resp.getData().size()).isEqualTo(PAYLOAD.length);
        assertThat(Files.readAllBytes(storage.resolve(SHA))).isEqualTo(PAYLOAD);
        verify(storage, never()).store(any(), anyString(), any());
        verify(blobMapper, never()).insert(any(WxBlobEntity.class));
    }

    @Test
    void putStreamsBodyToStoreAndInsertsRowOnMiss() throws IOException {
        givenBody(PAYLOAD);
        given(blobMapper.selectOne(any())).willReturn(null);

        ApiResponse<BlobUploadVO> resp = controller.upload(SHA, request);

        // storage 是真实实例（spy）：流已被 store 消费，事后读必空——改为断言落盘字节（等价且更强）
        verify(storage).store(any(InputStream.class), eq(SHA), any());
        assertThat(Files.readAllBytes(storage.resolve(SHA))).isEqualTo(PAYLOAD);

        ArgumentCaptor<WxBlobEntity> insert = ArgumentCaptor.forClass(WxBlobEntity.class);
        verify(blobMapper).insert(insert.capture());
        assertThat(insert.getValue()).extracting(WxBlobEntity::getSha256, WxBlobEntity::getSize,
                        WxBlobEntity::getStoragePath, WxBlobEntity::getIsDeleted)
                .containsExactly(SHA, (long) PAYLOAD.length, storage.relativePath(SHA), 0);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().status()).isEqualTo(BlobStorage.STATUS_STORED);
        assertThat(resp.getData().size()).isEqualTo((long) PAYLOAD.length);
    }

    @Test
    void putReturnsExistsWhenInsertHitsDuplicateKey() throws IOException {
        givenBody(PAYLOAD);
        given(blobMapper.selectOne(any())).willReturn(null);
        doThrow(new DuplicateKeyException("uk_wx_blob_sha256")).when(blobMapper).insert(any(WxBlobEntity.class));

        ApiResponse<BlobUploadVO> resp = controller.upload(SHA, request);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().status()).isEqualTo(BlobStorage.STATUS_EXISTS);
        assertThat(storage.exists(SHA)).isTrue();
    }

    @Test
    void putRestoresFileAndCorrectsSizeWhenRowExistsButFileMissing() throws IOException {
        givenBody(PAYLOAD);
        given(blobMapper.selectOne(any())).willReturn(row(3L));

        ApiResponse<BlobUploadVO> resp = controller.upload(SHA, request);

        assertThat(resp.getData().status()).isEqualTo(BlobStorage.STATUS_STORED);
        assertThat(resp.getData().size()).isEqualTo((long) PAYLOAD.length);
        assertThat(Files.readAllBytes(storage.resolve(SHA))).isEqualTo(PAYLOAD);
        verify(blobMapper).update(isNull(), any());
        verify(blobMapper, never()).insert(any(WxBlobEntity.class));
    }

    @Test
    void putSkipsSizeUpdateWhenRowSizeAlreadyMatchesActualBytes() throws IOException {
        givenBody(PAYLOAD);
        given(blobMapper.selectOne(any())).willReturn(row(PAYLOAD.length));

        assertThat(controller.upload(SHA, request).getData().status()).isEqualTo(BlobStorage.STATUS_STORED);
        verify(blobMapper, never()).update(any(), any());
    }

    @Test
    void putMapsIoFailureTo10503() throws IOException {
        givenBody(PAYLOAD);
        given(blobMapper.selectOne(any())).willReturn(null);
        doThrow(new IOException("disk full")).when(storage).store(any(), eq(SHA), any());

        assertThatThrownBy(() -> controller.upload(SHA, request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10503));
        assertThat(storage.exists(SHA)).isFalse();
    }

    @Test
    void putRejectsMalformedShaBeforeReadingBodyOrQuerying() throws IOException {
        assertThatThrownBy(() -> controller.upload("short", request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));

        verifyNoInteractions(blobMapper);
        verify(storage, never()).store(any(), anyString(), any());
        verify(request, never()).getInputStream();
    }

    @Test
    void getReturnsRawBytesWithoutEnvelopeWhenHit() throws IOException {
        given(blobMapper.selectOne(any())).willReturn(row(PAYLOAD.length));
        seedDisk(PAYLOAD);

        ResponseEntity<org.springframework.core.io.FileSystemResource> resp = controller.download(SHA);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(resp.getHeaders().getContentLength()).isEqualTo(PAYLOAD.length);
        assertThat(resp.getHeaders().get(HttpHeaders.CACHE_CONTROL).get(0)).contains("immutable");
        assertThat(resp.getBody()).isNotInstanceOf(ApiResponse.class);
        assertThat(resp.getBody().getFile()).isEqualTo(storage.resolve(SHA).toFile());
        assertThat(resp.getBody().getInputStream().readAllBytes()).isEqualTo(PAYLOAD);
    }

    @Test
    void getThrows10400WhenTableHasNoRow() {
        given(blobMapper.selectOne(any())).willReturn(null);

        assertThatThrownBy(() -> controller.download(SHA))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        verify(storage, never()).locate(any());
    }

    @Test
    void getThrows10400WhenRowExistsButFileMissing() {
        given(blobMapper.selectOne(any())).willReturn(row(5L));

        assertThatThrownBy(() -> controller.download(SHA))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
    }
}
