package com.wechat.sync.server.subscription.scheduler;

import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.service.DeliveryService;
import com.wechat.sync.server.subscription.service.SubscriptionBackstopService;
import com.wechat.sync.server.subscription.service.SubscriptionMatcher;
import com.wechat.sync.server.subscription.webhook.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;

/**
 * 订阅投递调度接线（SUBSCRIPTION_DESIGN §8 拨轮参数，T10-5）。风格照
 * {@link com.wechat.sync.server.task.scheduler.TaskSchedulerConfig}：fixedDelay（上一轮没结束
 * 绝不并发下一轮）+ initialDelay 错峰 + catch-all warn（可恢复常态「本轮失败下轮重试」，不升告警）。
 *
 * <p>五轮参数表（§8）：dispatch 200ms / sweep 30s / 补扫 60s / 快照刷新 30s / DELIVERED 清理 60s。
 * {@code @EnableScheduling} 幂等（task 模块已挂一处，本模块自包含再挂不重复注册调度器）。
 *
 * <p>多实例并发：认领（SKIP LOCKED + ON CONFLICT）与汇报（lease 条件 UPDATE）天然并发安全
 * （§9 单实例假设行），仅 worker 数与节拍需按实例复核；不引入 ShedLock（口径同任务域）。
 *
 * <p>测试上下文关闭后台调度：{@code wx-sync.scheduling.enabled=false}（口径与原因见
 * {@link com.wechat.sync.server.task.scheduler.TaskSchedulerConfig}；本类的 @EnableScheduling
 * 须同步加条件，否则调度器仍会被它拉起）。生产不设该属性即为启用。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "wx-sync.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@RequiredArgsConstructor
public class SubscriptionSchedulerConfig {

    private final WebhookDispatcher dispatcher;
    private final DeliveryService deliveryService;
    private final SubscriptionBackstopService backstopService;
    private final SubscriptionMatcher matcher;
    private final WxSubscriptionDeliveryMapper deliveryMapper;

    /** DELIVERED 行保留天数（§8 清理口径，yml 覆盖）。 */
    @Value("${wx-sync.sub-delivered-retention-days:30}")
    private int retainedDays;

    /** 每轮清理删除上限（千万级表批删无压力，yml 覆盖）。 */
    @Value("${wx-sync.sub-cleanup-batch:5000}")
    private int cleanupBatch;

    /** WEBHOOK 派发拨轮（§5.3/§8，200ms 一轮；认领即置 DISPATCHED+在飞租约）。 */
    @Scheduled(fixedDelay = 200L, initialDelay = 5_000L)
    public void dispatchWebhookDeliveries() {
        try {
            dispatcher.dispatchOnce();
        } catch (Exception e) {
            log.warn("[SubScheduler] 派发轮失败（下一轮重试）: {}", e.getMessage(), e);
        }
    }

    /** 过期租约回收（§4：attempts≥max → DEAD，否则回 PENDING 立即重投；30s 一轮）。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void sweepExpiredDeliveryLeases() {
        try {
            int recycled = deliveryService.sweepExpired();
            if (recycled > 0) {
                log.info("[SubScheduler] 回收过期投递租约 count={}", recycled);
            }
        } catch (Exception e) {
            log.warn("[SubScheduler] 投递回收失败（下一轮重试）: {}", e.getMessage(), e);
        }
    }

    /** 漏匹配补扫（§3.2 保险带：60s 一轮、10 分钟窗、按 id 游标、GREATEST 防回退）。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void backstopScan() {
        try {
            int enqueued = backstopService.runBackstop();
            if (enqueued > 0) {
                log.info("[SubScheduler] 补扫入队 count={}", enqueued);
            }
        } catch (Exception e) {
            log.warn("[SubScheduler] 补扫失败（下一轮重试）: {}", e.getMessage(), e);
        }
    }

    /** 订阅快照刷新（§3.3：30s 全量载入——新订阅最迟一个快照周期开始收消息，边界文档明示）。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 3_000L)
    public void refreshSubscriptionSnapshot() {
        try {
            matcher.refresh();
        } catch (Exception e) {
            log.warn("[SubScheduler] 订阅快照刷新失败（下一轮重试）: {}", e.getMessage(), e);
        }
    }

    /** DELIVERED 行清理（§8：按 created_at 部分索引批删，DEAD 审计行不在范围）。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 45_000L)
    public void cleanupDeliveredRows() {
        try {
            int removed = deliveryMapper.cleanupDelivered(
                    OffsetDateTime.now().minusDays(retainedDays), cleanupBatch);
            if (removed > 0) {
                log.info("[SubScheduler] 清理 DELIVERED 行 count={}", removed);
            }
        } catch (Exception e) {
            log.warn("[SubScheduler] DELIVERED 清理失败（下一轮重试）: {}", e.getMessage(), e);
        }
    }
}
