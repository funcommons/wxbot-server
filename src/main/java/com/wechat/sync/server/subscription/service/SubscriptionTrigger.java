package com.wechat.sync.server.subscription.service;

import com.wechat.sync.server.subscription.mapper.DeliveryEnqueueRow;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订阅触发钩子（SUBSCRIPTION_DESIGN §3.1 主路径）：ingest 同事务内，对「本批真正 INSERT 的新消息」
 * 内存匹配入队——消息落库与投递行<b>原子提交</b>（零丢失窗口；客户端幂等重放经 dedup_key 去重
 * 不会二次触发）。
 *
 * <p><b>失败口径（v1.1 评审定稿）</b>：{@link #trigger} 整段 try/catch 吞掉（log.warn），
 * <b>绝不回滚消息事务</b>——「吞掉 + 60s 补扫兜底」严格优于「投递失败拖垮 ingest 让客户端重试」
 * （后者把一次可补的投递缺口放大成一次全批 ingest 重入）。残余缺口三类均有
 * {@link SubscriptionBackstopService} 兜底：匹配器快照延迟边缘 / 未来重构拆钩子 / 人工补数。
 *
 * <p><b>事务边界</b>：本组件自身无事务注解——被 ingest 的 chat 事务调用时，INSERT 随消息事务
 * 提交；被调度器/IT 直接调用时自行成单语句事务。PG 语句级错误因 PG 事务中止语义无法在事务内
 * 真正吞掉（提交时会暴露，随该 chat 整体回滚、由客户端幂等重放自愈）——Java 层异常（匹配器/
 * 序列化等）在本方法内完全吞掉，这是「吞掉+兜底」的实际覆盖面，登记为已知残余。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionTrigger {

    private final SubscriptionMatcher matcher;
    private final WxSubscriptionDeliveryMapper deliveryMapper;

    /**
     * 匹配 + 入队（ingest 同事务调用点：MessagesIngestService.applyChat 最后一步）。
     *
     * @param account  上报账号（六键 accounts 条件的比对值）
     * @param messages 本批<b>仅 inserted</b> 的新消息（merged/unchanged 不触发——旧消息早已投递过）
     * @return 实际新插入投递行数；失败吞掉后返回 -1（观测口径，调用方不判值）
     */
    public int trigger(String account, List<SubscriptionMatcher.IncomingMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        try {
            List<DeliveryEnqueueRow> rows = matcher.match(account, messages);
            if (rows.isEmpty()) {
                return 0;
            }
            int inserted = deliveryMapper.enqueueBatch(rows);
            if (log.isDebugEnabled()) {
                log.debug("[Subscription] 触发入队：消息 {} 条 × 快照订阅 → 候选 {} 行，实际插入 {}",
                        messages.size(), rows.size(), inserted);
            }
            return inserted;
        } catch (Exception e) {
            log.warn("[Subscription] 触发钩子失败（吞掉不回滚消息事务，缺口由补扫兜底）: {}", e.toString());
            return -1;
        }
    }
}
