package com.wechat.sync.server.status.mapper;

import com.wechat.sync.server.status.dto.SubscriptionDeliveryStatsRow;
import org.apache.ibatis.annotations.Select;

/**
 * GET /v1/status 计数 Mapper（T7，只读；包扫描见 WxSyncServerApplication 的 @MapperScan）。
 *
 * <p>两个 COUNT 都走显式 SQL 而非 MP {@code selectCount}：谓词含 {@code LIKE 'fb:%'} 前缀匹配
 * （uk_wx_message_dedup_key 的 btree 可做前缀扫描）与布尔列判断，{@code @Select} 一眼可见口径。
 */
public interface WxStatusMapper {

    /**
     * 兜底键未合并计数：{@code dedup_key} 仍是 {@code fb:<chat>|<sender>|<ts>|<sha>} 形态，
     * 即 server_id 尚未补齐（fb→srv 合并只发生在拿到有效 server_id 的后续上报）。
     */
    @Select("SELECT count(*) FROM wx_message WHERE dedup_key LIKE 'fb:%'")
    long countFbPending();

    /** 疑似误合并计数：{@code suspect_duplicate=TRUE}（ingest 只标记不删，人工核对口径）。 */
    @Select("SELECT count(*) FROM wx_message WHERE suspect_duplicate = TRUE")
    long countSuspectDuplicate();

    /**
     * 订阅投递观测（SUBSCRIPTION_DESIGN §7 可观测行，T10-6）：三活动/终态计数 + 最近成功投递时间。
     * 单条聚合（FILTER 子句）避免四次扫表；DELIVERED 不计数（只看在途与死信），DELIVERED 行
     * 本身由 30 天清理任务批量移除——「最近投递时间」是唯一保留的 DELIVERED 观测口径。
     */
    @Select("""
            SELECT count(*) FILTER (WHERE status = 'PENDING')    AS pendingCount,
                   count(*) FILTER (WHERE status = 'DISPATCHED') AS dispatchedCount,
                   count(*) FILTER (WHERE status = 'DEAD')       AS deadCount,
                   max(delivered_at)                             AS lastDeliveredAt
              FROM wx_subscription_delivery
            """)
    SubscriptionDeliveryStatsRow subscriptionDeliveryStats();
}
