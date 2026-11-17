package com.wechat.sync.server.blob;

import com.wechat.sync.server.common.WxErrorCode;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * blob 内容寻址文件库（SYNCH_PLAN §9.4 / API_DESIGN §4.4）。
 *
 * <p><b>字节不进 PG</b>：附件是几 MB～几百 MB 的字节块，进 {@code bytea}/large object 会让
 * {@code pg_dump}/WAL/vacuum 跟着膨胀；文件系统 + 内容寻址（Git/MediaWiki 成熟做法），
 * {@code wx_blob.storage_path} 只存相对路径 {@code <aa>/<bb>/<sha256>}，根目录是部署配置
 * （换盘 / 换 S3 只改本类）。
 *
 * <p><b>写入协议</b>（防并发、防半截文件）：查重（表 + 盘）→ 临时名
 * {@code <sha256>.tmp-<pid>-<nano>} → 写完流式重算 sha256 → 不匹配删临时件抛 10602 →
 * 原子 rename 转正。半截字节只存在于 {@code .tmp-} 名下，永远不会以正式名露出坏字节；
 * 两个进程写同一 blob 内容相同，后 rename 者赢，无害。
 *
 * <p><b>生命周期</b>：append-only，本类不提供删除。GC（若未来要做）唯一安全判据是
 * 「没有任何 {@code wx_message_attachment.blob_sha256} 指向它」——引用计数不落列，靠查询；
 * 账号删了 blob 仍在（DB_DESIGN §9）。
 *
 * <p>显式构造器注入 {@code @Value}（不用 Lombok，避开 {@code @Value} 落不到 Lombok 构造参数的坑，
 * 与 {@code config/AdminBootstrap} 同款姿势）。
 */
@Slf4j
@Component
public class BlobStorage {

    /** PUT 响应 status：盘上已有（跨客户端去重命中，字节未重写） */
    public static final String STATUS_EXISTS = "exists";

    /** PUT 响应 status：本次落盘转正 */
    public static final String STATUS_STORED = "stored";

    /** 流式拷贝缓冲（大文件拷贝 8KB 太碎，64KB 足够） */
    private static final int COPY_BUFFER = 64 * 1024;

    /** sha256 是 64 位十六进制（小写归一化后校验，API_DESIGN §7 field=sha256 时 code=10102） */
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final Path root;

    private final long maxBytes;

    public BlobStorage(@Value("${wx-sync.blob-root:./data/blobs}") String root,
                       @Value("${wx-sync.blob-max-bytes:536870912}") long maxBytes) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    /** 启动期建根目录（fail-fast：盘不可写直接拒启，别等第一个 PUT 才炸）。 */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root);
            log.info("[Blob] 内容寻址文件库就绪 root={} maxBytes={}", root, maxBytes);
        } catch (IOException e) {
            throw new IllegalStateException("blob 根目录不可创建/不可写: " + root, e);
        }
    }

    /** 落库进 {@code wx_blob.storage_path} 的相对路径（统一正斜杠，跨平台稳定）。 */
    public String relativePath(String sha256) {
        return sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256;
    }

    /**
     * 校验并归一化 sha256（小写、64 hex）：缺失 → 10101，格式不符 → 10102。
     * public 供 Controller 在查表前先调（路径参数是不可信输入，直接拼路径会越出根目录）。
     */
    public String normalizeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            throw new ApiException(ApiCode.PARAM_MISSING, "sha256 必填");
        }
        String sha = sha256.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(sha).matches()) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "sha256 必须为 64 位十六进制: " + sha256);
        }
        return sha;
    }

    /** 两级 256×256 分桶布局（百万文件不塞一个目录），所有账号共享一个根（去重的前提）。 */
    public Path resolve(String sha256) {
        return root.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    /** 盘上是否已有正式名文件（SYNCH_PLAN §9.4 第 2 步：查表 / 盘上两者任一命中即短路）。 */
    public boolean exists(String sha256) {
        return Files.isRegularFile(resolve(sha256));
    }

    /**
     * 定位可下载的 blob 字节：文件缺失（库有行盘无文件，如盘被清/搬库）→ 10400。
     *
     * @return path + 实际字节数（盘上字节是 size 权威，比元数据表可信）
     */
    public BlobFile locate(String sha256) {
        Path path = resolve(normalizeSha256(sha256));
        if (!Files.isRegularFile(path)) {
            throw new ApiException(ApiCode.NOT_FOUND, "blob 字节缺失（元数据在而文件不在）: " + sha256);
        }
        try {
            return new BlobFile(path, Files.size(path));
        } catch (IOException e) {
            throw new ApiException(ApiCode.SYSTEM_BUSY, "blob 文件读取失败: " + path + " (" + e.getMessage() + ")");
        }
    }

    /** 单文件体积上限预检（Content-Length 已知时早失败，避免收满 512MB 才拒）→ 10505。 */
    public void checkLimit(String sha256, Long declaredSize) {
        if (declaredSize != null && declaredSize > maxBytes) {
            throw oversize(sha256, declaredSize);
        }
    }

    /**
     * 流式落盘 + 写后校验 + 原子转正（SYNCH_PLAN §9.4 第 3 步）。
     *
     * <p>入口已由调用方查重（wx_blob 表 + {@link #exists}），本方法只兜盘上并发；
     * 入参流<b>由调用方关闭</b>（{@code @RestController} 用 try-with-resources 持有 request body）。
     *
     * @param in           原始字节流（解密后明文，octet-stream，禁 JSON 反序列化）
     * @param sha256       路径参数声明的内容哈希（须与实际字节一致，否则 10602）
     * @param declaredSize 客户端声明的字节数（可空 = 未知，仅用于超限预检/差异告警，不作校验依据）
     * @return 落盘结果（exists 表示盘上已有、字节未重写）
     */
    public Stored store(InputStream in, String sha256, Long declaredSize) throws IOException {
        String sha = normalizeSha256(sha256);
        Path target = resolve(sha);
        if (Files.isRegularFile(target)) {
            return new Stored(sha, STATUS_EXISTS, Files.size(target));
        }
        checkLimit(sha, declaredSize);

        Files.createDirectories(target.getParent());
        // 临时名带 pid + nanoTime：同进程并发/多进程同写都不互踩（SYNCH_PLAN §9.4 的 <sha256>.tmp-<pid> 加固）
        Path temp = target.resolveSibling(sha + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean done = false;
        try {
            long size = 0;
            MessageDigest digest = newDigest();
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[COPY_BUFFER];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    size += read;
                    if (size > maxBytes) {
                        throw oversize(sha, size);
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            // 写完重算（防写坏）：不匹配 → 删临时件抛 10602，客户端重新解密后整文件重传
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(sha)) {
                throw WxErrorCode.blobChecksumMismatch(sha);
            }
            promote(temp, target);
            done = true;
            if (declaredSize != null && declaredSize != size) {
                log.warn("[Blob] 字节数与声明不一致 sha={} declared={} actual={}（以实际字节为准）", sha, declaredSize, size);
            }
            log.info("[Blob] stored sha={} size={} bucket={}", sha, size, relativePath(sha));
            return new Stored(sha, STATUS_STORED, size);
        } finally {
            if (!done) {
                deleteQuietly(temp);
            }
        }
    }

    /** 原子转正：临时件与正式名同目录（同卷）→ {@code ATOMIC_MOVE} 必可用。 */
    private void promote(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 跨卷降级（网络盘/容器挂载把根目录映射到别的文件系统）：非原子替换。
            // 写后已校验内容，且目标即便被并发先到者占住也是同内容字节，REPLACE_EXISTING 无损；
            // 代价是跨卷窗口内 GET 可能读到半截（同卷主路径无此问题）。
            log.warn("[Blob] ATOMIC_MOVE 不支持（跨卷），降级 REPLACE_EXISTING target={}", target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            // Windows 的 ATOMIC_MOVE 遇已存在目标会抛：并发同内容写入时后者让位，结果等价 exists
            log.info("[Blob] 并发写入让位（目标已存在）target={}", target);
        }
    }

    private ApiException oversize(String sha256, long size) {
        return new ApiException(ApiCode.FILE_SIZE_EXCEED,
                "blob 超过单文件上限 " + maxBytes + " 字节: " + sha256 + " (" + size + ")");
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[Blob] 临时文件清理失败 path={} ({})", path, e.getMessage());
        }
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** store 结果：status ∈ exists | stored（API_DESIGN §4.4 响应 data 的三个字段）。 */
    public record Stored(String sha256, String status, long size) {
    }

    /** locate 结果：盘上字节与其真实大小。 */
    public record BlobFile(Path path, long size) {
    }
}
