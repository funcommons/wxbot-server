package com.wechat.sync.server.status.dto;

import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.message.entity.WxIngestBatchLogEntity;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /v1/status 响应 VO（T7，运维观测）。字段驼峰，线上由 framework4j-web 统一转 snake_case；
 * Long / long 一律落 String（WebConfig 全局 Long→String，T4/T6 同口径）。
 */
public final class StatusVOs {

    private StatusVOs() {
    }

    /** 账号活性摘要（wx_account 行投影，不含令牌哈希）。 */
    public record AccountStatusVO(String account, String hostname, String clientVersion,
                                  OffsetDateTime lastSyncAt, OffsetDateTime lastSeenAt) {
        public static AccountStatusVO from(WxAccountEntity e) {
            return new AccountStatusVO(e.getAccount(), e.getHostname(), e.getClientVersion(),
                    e.getLastSyncAt(), e.getLastSeenAt());
        }
    }

    /** 最近批次摘要（wx_ingest_batch_log 行投影，不含 error 文本大字段）。 */
    public record BatchSummaryVO(String id, String account, String batchRef, OffsetDateTime startedAt,
                                 Integer durationMs, Integer nMessages, Integer nInserted,
                                 Integer nMerged, String status) {
        public static BatchSummaryVO from(WxIngestBatchLogEntity e) {
            return new BatchSummaryVO(String.valueOf(e.getId()), e.getAccount(), e.getBatchRef(),
                    e.getStartedAt(), e.getDurationMs(), e.getNMessages(), e.getNInserted(),
                    e.getNMerged(), e.getStatus());
        }
    }

    /** 订阅投递观测（§7 可观测行，T10-6）：在途（PENDING/DISPATCHED）+ 死信 + 最近成功投递时间。 */
    public record SubscriptionDeliveryStatsVO(long pendingCount, long dispatchedCount, long deadCount,
                                              OffsetDateTime lastDeliveredAt) {
        public static SubscriptionDeliveryStatsVO from(SubscriptionDeliveryStatsRow row) {
            return row == null ? new SubscriptionDeliveryStatsVO(0, 0, 0, null)
                    : new SubscriptionDeliveryStatsVO(row.pendingCount(), row.dispatchedCount(),
                            row.deadCount(), row.lastDeliveredAt());
        }
    }

    /** /v1/status data：accounts + 两个去重健康计数 + 最近 10 批 + 订阅投递观测（T10-6 增量）。 */
    public record StatusVO(List<AccountStatusVO> accounts, long fbPendingCount,
                           long suspectDuplicateCount, List<BatchSummaryVO> recentBatches,
                           SubscriptionDeliveryStatsVO subscriptionDeliveries) {
    }
}
