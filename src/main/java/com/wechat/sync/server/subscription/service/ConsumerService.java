package com.wechat.sync.server.subscription.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.ConsumerCreatedVO;
import com.wechat.sync.server.subscription.dto.SubscriptionAdminVOs.ConsumerVO;
import com.wechat.sync.server.subscription.entity.WxConsumerEntity;
import com.wechat.sync.server.subscription.mapper.WxConsumerMapper;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 消费者 CRUD（SUBSCRIPTION_DESIGN §2.1）：凭据哈希入库、明文只此一次。
 *
 * <p>凭据生成：{@link SecureRandom} 32 字节 → base64url（43 字符，同 reg-code 熵口径 256 位），
 * POLL 令牌前缀 {@code wxc_} / WEBHOOK 密钥前缀 {@code wcs_}；sha256 入库（{@code token_hash}/
 * {@code secret_hash}），明文只在 {@link #create} 响应出现一次。两把凭据按需生成——只拉不推的
 * 消费者可以没有 secret（§2.1）。
 *
 * <p>防重名：{@code uk_wx_consumer_name}（部分唯一，软删行除外）命中 → 10401；
 * 并发竞态由 INSERT 撞 {@link DuplicateKeyException} 翻译（AccountService 10401 先例）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumerService {

    /** 32 字节随机 → base64url 43 字符（256 位熵，穷举不可行） */
    private static final int CREDENTIAL_BYTES = 32;
    private static final String POLL_TOKEN_PREFIX = "wxc_";
    private static final String WEBHOOK_SECRET_PREFIX = "wcs_";
    private static final int NAME_MAX = 64;

    private final WxConsumerMapper consumerMapper;
    /** 字段初始化而非构造器注入：JDK 类无容器 bean，@RequiredArgsConstructor 会把它当依赖导致上下文拒载。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 建消费者：{@code enablePoll}/{@code enableWebhook} 声明要哪几把凭据（缺省各 true）。
     * 响应明文只此一次；重名 10401（uk_wx_consumer_name 命中或并发竞态同码）。
     */
    public ConsumerCreatedVO create(String name, Boolean enablePoll, Boolean enableWebhook,
                                    String webhookUrl, String remark) {
        String trimmedName = requireName(name);
        String pollToken = enablePoll == null || enablePoll ? POLL_TOKEN_PREFIX + randomCredential() : null;
        String webhookSecret = enableWebhook == null || enableWebhook
                ? WEBHOOK_SECRET_PREFIX + randomCredential() : null;
        WxConsumerEntity row = new WxConsumerEntity();
        row.setName(trimmedName);
        row.setTokenHash(pollToken == null ? null : SubscriptionFilter.sha256Hex(pollToken));
        row.setSecretHash(webhookSecret == null ? null : SubscriptionFilter.sha256Hex(webhookSecret));
        row.setWebhookUrl(trimToNull(webhookUrl));
        row.setRemark(trimToNull(remark));
        row.setIsDeleted(0);
        try {
            consumerMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ApiCode.UNIQUE_CONFLICT, "消费者名称已存在: " + trimmedName);
        }
        log.info("[Consumer] 创建消费者 id={} name={} poll={} webhook={}",
                row.getId(), trimmedName, pollToken != null, webhookSecret != null);
        return ConsumerCreatedVO.from(row, pollToken, webhookSecret);
    }

    /** 列表（Offset 分页，软删过滤；无任何凭据明文/哈希回显）。 */
    public PageVO<ConsumerVO> list(long page, long pageSize) {
        PageVO.PageParams pp = PageVO.clamp(page, pageSize);
        Page<WxConsumerEntity> result = consumerMapper.selectPage(new Page<>(pp.page(), pp.pageSize()),
                new LambdaQueryWrapper<WxConsumerEntity>()
                        .eq(WxConsumerEntity::getIsDeleted, 0)
                        .orderByDesc(WxConsumerEntity::getId));
        return PageVO.of(result.getRecords().stream().map(ConsumerVO::from).toList(),
                result.getTotal(), pp.page(), pp.pageSize());
    }

    /** 软删（is_deleted=1；哈希列原样保留——审计口径，软删即失效：authenticateConsumer 只认未删行）。 */
    public void delete(long consumerId) {
        WxConsumerEntity row = consumerMapper.selectOne(new LambdaQueryWrapper<WxConsumerEntity>()
                .eq(WxConsumerEntity::getId, consumerId)
                .eq(WxConsumerEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (row == null) {
            throw new ApiException(ApiCode.NOT_FOUND, "消费者不存在或已软删: " + consumerId);
        }
        consumerMapper.update(null, new LambdaUpdateWrapper<WxConsumerEntity>()
                .eq(WxConsumerEntity::getId, consumerId)
                .set(WxConsumerEntity::getIsDeleted, 1)
                .set(WxConsumerEntity::getUpdatedAt, OffsetDateTime.now()));
        log.info("[Consumer] 软删消费者 id={} name={}", consumerId, row.getName());
    }

    // ==================== 内部 ====================

    private String requireName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            throw new ApiException(ApiCode.PARAM_MISSING, "name 必填");
        }
        if (trimmed.length() > NAME_MAX) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "name 超过最大 " + NAME_MAX + " 字符");
        }
        return trimmed;
    }

    /** SecureRandom 32 字节 → base64url 无填充（43 字符）；用于 poll token 与 webhook secret。 */
    private String randomCredential() {
        byte[] bytes = new byte[CREDENTIAL_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
