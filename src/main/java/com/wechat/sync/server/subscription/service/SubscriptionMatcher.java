package com.wechat.sync.server.subscription.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.mapper.DeliveryEnqueueRow;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅内存匹配器（SUBSCRIPTION_DESIGN §3.3）：订阅量级 &lt;100 → 全量载入内存快照，
 * 匹配在 Java 内做 O(订阅数) 字段比较——触发在 ingest 事务内<b>零额外 DB 读</b>；
 * SQL 匹配（GIN）只在兜底补扫里用（{@code backstopEnqueue}）。
 *
 * <p><b>快照口径</b>：{@code volatile} 不可变列表<b>整体替换</b>（读无锁、刷新线程安全）；
 * 刷新节奏 30s 由 T10-5 的调度器驱动（本类不自带 @Scheduled），语义代价=「新订阅最多晚一个
 * 快照周期开始收消息；恰在批处理中创建的订阅对当前批不生效」（§9 边界，文档明示）。
 * 单颗订阅 filter 解析失败只跳过该颗（log.warn），不拖垮整张快照。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionMatcher {

    private final WxSubscriptionMapper subscriptionMapper;

    /** 当前快照（volatile 整体替换；初始空 = 未刷新前不匹配任何消息，等价「订阅未生效」） */
    private volatile List<SnapshotSubscription> snapshot = List.of();

    /**
     * 全量刷新快照（enabled=1 且未软删；调度器 30s 一调，IT 可直调）。
     * DB 读失败直接上抛（调度器 catch-all 记日志；快照保持上一份不动——刷新失败不空窗）。
     */
    public void refresh() {
        List<WxSubscriptionEntity> rows = subscriptionMapper.selectList(
                new LambdaQueryWrapper<WxSubscriptionEntity>()
                        .eq(WxSubscriptionEntity::getEnabled, 1)
                        .eq(WxSubscriptionEntity::getIsDeleted, 0));
        List<SnapshotSubscription> next = new ArrayList<>(rows.size());
        int skipped = 0;
        for (WxSubscriptionEntity row : rows) {
            try {
                next.add(new SnapshotSubscription(row.getId(), row.getConsumerId(),
                        SubscriptionFilter.parse(row.getFilter()),
                        row.getMaxAttempts() == null ? 8 : row.getMaxAttempts(),
                        row.getVisibleSeconds() == null ? 60 : row.getVisibleSeconds()));
            } catch (IllegalArgumentException e) {
                skipped++;                       // 坏 filter 不拖垮整张快照
                log.warn("[Subscription] 订阅 filter 解析失败，本快照周期跳过 subscription_id={}: {}",
                        row.getId(), e.getMessage());
            }
        }
        snapshot = List.copyOf(next);
        log.info("[Subscription] 快照刷新完成：订阅 {} 颗（跳过坏 filter {} 颗）", next.size(), skipped);
    }

    /** 当前快照大小（观测/IT 断言用）。 */
    public int snapshotSize() {
        return snapshot.size();
    }

    /**
     * 六键内存匹配（§2.2/§3.3）：一条新消息 × 每颗快照订阅 = 至多一行投递候选。
     * {@code account} 用 ingest 自带参数比对，<b>不查来源表</b>（账号条件在 SQL 补扫侧才
     * join wx_message_source_rel）。返回按订阅快照携带 maxAttempts/leaseSeconds 的入队行。
     */
    public List<DeliveryEnqueueRow> match(String account, List<IncomingMessage> messages) {
        List<SnapshotSubscription> current = snapshot;
        if (current.isEmpty() || messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<DeliveryEnqueueRow> rows = new ArrayList<>();
        for (IncomingMessage message : messages) {
            for (SnapshotSubscription sub : current) {
                if (sub.filter.matches(account, message.chatUsername(), message.senderUsername(),
                        message.typeCode(), message.body(), message.isRevoked())) {
                    rows.add(new DeliveryEnqueueRow(sub.subscriptionId(), sub.consumerId(),
                            message.messageId(), sub.maxAttempts(), sub.leaseSeconds()));
                }
            }
        }
        return rows;
    }

    /** 匹配器输入：仅携带六键判定所需字段（webhook 载荷的完整消息体由 T10-4 派发时按 id 回读）。 */
    public record IncomingMessage(long messageId, String chatUsername, String senderUsername,
                                  String typeCode, String body, boolean isRevoked) {
    }

    /** 快照元素（不可变）：订阅行 + 已解析谓词 + 入队快照参数。 */
    record SnapshotSubscription(long subscriptionId, long consumerId,
                               SubscriptionFilter.Parsed filter, int maxAttempts, int leaseSeconds) {
    }
}
