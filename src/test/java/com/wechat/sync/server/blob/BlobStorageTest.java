package com.wechat.sync.server.blob;

import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BlobStorage 纯单测（@TempDir 真文件，不起 Spring）：分桶落盘布局、.tmp 不残留、
 * 同内容去重短路不重写、写后 sha256 校验失败清临时件（10602）、哈希格式非法（10101/10102）、
 * 体积上限（10505）、exists / locate（10400）。
 */
class BlobStorageTest {

    /** 512KB 上限：容得下跨 64KB 拷贝缓冲的大内容，又可用 600KB 触发超限 */
    private static final long MAX_BYTES = 512 * 1024L;

    private static final byte[] PAYLOAD = "blob-unit-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String SHA = sha256Of(PAYLOAD);

    @TempDir
    Path root;

    private BlobStorage storage;

    @BeforeEach
    void setUp() {
        storage = new BlobStorage(root.toString(), MAX_BYTES);
    }

    private static String sha256Of(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(int size, byte seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (seed + i);
        }
        return data;
    }

    /** 正式名路径按 <aa>/<bb>/<sha256> 布局独立推算（不借被测代码自身的 resolve）。 */
    private Path expectedPath(String sha) {
        return root.resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4)).resolve(sha);
    }

    private void assertNoTempLeft() throws IOException {
        try (Stream<Path> all = Files.walk(root)) {
            assertThat(all.filter(p -> p.getFileName().toString().contains(".tmp-"))).isEmpty();
        }
    }

    @Test
    void storeWritesShardedFileAndReturnsStored() throws IOException {
        BlobStorage.Stored stored = storage.store(new ByteArrayInputStream(PAYLOAD), SHA, null);

        assertThat(stored.sha256()).isEqualTo(SHA);
        assertThat(stored.status()).isEqualTo(BlobStorage.STATUS_STORED);
        assertThat(stored.size()).isEqualTo(PAYLOAD.length);
        assertThat(expectedPath(SHA)).exists();
        assertThat(Files.readAllBytes(expectedPath(SHA))).isEqualTo(PAYLOAD);
        assertNoTempLeft();
    }

    @Test
    void storeSameContentTwiceShortCircuitsWithoutRewrite() throws IOException {
        storage.store(new ByteArrayInputStream(PAYLOAD), SHA, null);
        FileTime frozen = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(expectedPath(SHA), frozen);

        BlobStorage.Stored second = storage.store(new ByteArrayInputStream(PAYLOAD), SHA, (long) PAYLOAD.length);

        assertThat(second.status()).isEqualTo(BlobStorage.STATUS_EXISTS);
        assertThat(second.size()).isEqualTo(PAYLOAD.length);
        assertThat(Files.getLastModifiedTime(expectedPath(SHA)).toMillis()).isEqualTo(frozen.toMillis());
        assertThat(Files.readAllBytes(expectedPath(SHA))).isEqualTo(PAYLOAD);
        assertNoTempLeft();
    }

    @Test
    void storeChecksumMismatchThrows10602AndCleansTemp() throws IOException {
        String claimed = sha256Of("another-payload".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> storage.store(new ByteArrayInputStream(PAYLOAD), claimed, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10602));

        assertThat(expectedPath(claimed)).doesNotExist();
        assertNoTempLeft();
    }

    @Test
    void normalizeSha256RejectsMissingAndLowercases() {
        assertThatThrownBy(() -> storage.normalizeSha256(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
        assertThatThrownBy(() -> storage.normalizeSha256("   "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));

        String upper = SHA.toUpperCase(java.util.Locale.ROOT);
        assertThat(storage.normalizeSha256("  " + upper + "\n")).isEqualTo(SHA);
    }

    @Test
    void normalizeSha256RejectsMalformedAndTraversalWith10102() throws IOException {
        for (String bad : new String[]{"short", "not-a-hash-at-all",
                "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                "ZZ" + SHA.substring(2), "../../../etc/passwd", SHA + "/../evil"}) {
            assertThatThrownBy(() -> storage.normalizeSha256(bad))
                    .as("非法 sha256: %s", bad)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        }
        // 路径穿越在格式校验即被拦下，根目录不得被建出任何子目录/文件
        try (Stream<Path> all = Files.walk(root)) {
            assertThat(all.filter(p -> !p.equals(root))).isEmpty();
        }
    }

    @Test
    void storeOverDeclaredSizeLimitThrows10505BeforeWriting() {
        String sha = sha256Of(bytes(16, (byte) 1));

        assertThatThrownBy(() -> storage.store(new ByteArrayInputStream(bytes(16, (byte) 1)), sha, MAX_BYTES + 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10505));

        assertThat(expectedPath(sha)).doesNotExist();
    }

    @Test
    void storeOverStreamLimitThrows10505MidReadAndCleansTemp() throws IOException {
        String sha = sha256Of(bytes(16, (byte) 2));

        assertThatThrownBy(() -> storage.store(new ByteArrayInputStream(bytes((int) MAX_BYTES + 1, (byte) 2)), sha,
                (long) MAX_BYTES + 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10505));

        assertThat(expectedPath(sha)).doesNotExist();
        assertNoTempLeft();
    }

    @Test
    void checkLimitPassesNullAndWithinLimit() {
        storage.checkLimit(SHA, null);
        storage.checkLimit(SHA, MAX_BYTES);
        assertThatThrownBy(() -> storage.checkLimit(SHA, MAX_BYTES + 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10505));
    }

    @Test
    void storeSpansMultipleCopyBuffers() throws IOException {
        byte[] big = bytes(100_000, (byte) 7);
        String sha = sha256Of(big);

        BlobStorage.Stored stored = storage.store(new ByteArrayInputStream(big), sha, (long) big.length);

        assertThat(stored.status()).isEqualTo(BlobStorage.STATUS_STORED);
        assertThat(stored.size()).isEqualTo(big.length);
        assertThat(Files.readAllBytes(expectedPath(sha))).isEqualTo(big);
    }

    @Test
    void existsLocateAndRelativePathReflectDiskState() throws IOException {
        assertThat(storage.exists(SHA)).isFalse();
        assertThatThrownBy(() -> storage.locate(SHA))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));

        Files.createDirectories(expectedPath(SHA).getParent());
        Files.write(expectedPath(SHA), PAYLOAD);

        assertThat(storage.exists(SHA)).isTrue();
        assertThat(storage.relativePath(SHA))
                .isEqualTo(SHA.substring(0, 2) + "/" + SHA.substring(2, 4) + "/" + SHA);
        BlobStorage.BlobFile file = storage.locate(SHA);
        assertThat(file.path()).isEqualTo(expectedPath(SHA));
        assertThat(file.size()).isEqualTo(PAYLOAD.length);
    }

    @Test
    void initCreatesRootDirectoryFailFast() throws IOException {
        Path nested = root.resolve("a/b/c");
        new BlobStorage(nested.toString(), MAX_BYTES).init();
        assertThat(nested).exists();

        Path occupied = root.resolve("occupied");
        Files.writeString(occupied, "x");
        assertThatThrownBy(() -> new BlobStorage(occupied.toString(), MAX_BYTES).init())
                .isInstanceOf(IllegalStateException.class);
    }
}
