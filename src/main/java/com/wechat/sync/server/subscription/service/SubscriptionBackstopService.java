package com.wechat.sync.server.subscription.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import com.wechat.sync.server.subscription.entity.WxSubDeliveryCursorEntity;
import com.wechat.sync.server.subscription.mapper.WxSubDeliveryCursorMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 漏匹配补扫（SUBSCRIPTION_DESIGN §3.2，保险带不是主路径）：同事务钩子已做到零丢失，
 * 补扫只覆盖三类残余——匹配器快照延迟边缘（订阅恰在批处理中创建）/ 未来重构把钩子拆出事务 /
 * 人工数据修复产生的「无钩子消息」。
 *
 * <p>节奏（T10-5 调度）：60s 一轮、按 id 游标、10 分钟窗；SQL 匹配（含账号 EXISTS
 * wx_message_source_rel / GIN filter）全在 {@code backstopEnqueue} 一条语句里，
 * ON CONFLICT 与触发钩子并发/重放无害。
 *
 * <p>事务边界：三步各自单语句、<b>不包整体事务</b>——入队与水位推进都幂等（ON CONFLICT +
 * GREATEST），中断后下一轮重扫同一窗口自动收敛；入队成功而水位未推进的最坏代价是重复扫描
 * （零副作用）。调度器（T10-5）负责 catch-all。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionBackstopService {

    /** 游标域（§2.4：现阶段固定单行 global） */
    public static final String SCOPE_GLOBAL = "global";

    /** 补扫窗口缺省 600s（10 分钟；keyword LIKE 无索引但窗口内消息量可忽略，§3.2） */
    public static final int DEFAULT_WINDOW_SECONDS = 600;

    private final WxSubDeliveryCursorMapper cursorMapper;
    private final WxSubscriptionDeliveryMapper deliveryMapper;
    private final WxMessageMapper messageMapper;

    /** 缺省窗口补扫（T10-5 调度入口）。 */
    public int runBackstop() {
        return runBackstop(DEFAULT_WINDOW_SECONDS);
    }

    /**
     * 一轮补扫：读水位（无行视 0）→ 窗口入队 → 水位推进到<b>窗口内</b> max(wx_message.id)（GREATEST 防回退）。
     *
     * <p>P1 审查修订：推进目标从全局 max 改为窗口内 max——backstopEnqueue 只补
     * {@code created_at > now()-window} 的窗内消息，若水位按全局 max 推进，调度停摆超过窗口
     * 而 ingest 持续时，窗与旧水位之间的缺口消息会被永久跳过。水位只认「已补窗内最大 id」，
     * 缺口逐轮追赶（每轮只推进到本轮实际覆盖的 max(id)，停摆期积压分多轮补齐）。
     *
     * @return 本轮实际新入队投递行数（0 = 无漏）
     */
    public int runBackstop(int windowSeconds) {
        long highWater = readHighWater();
        int enqueued = deliveryMapper.backstopEnqueue(highWater, windowSeconds);
        long maxMessageId = readMaxMessageId(windowSeconds);
        if (maxMessageId > highWater) {
            cursorMapper.upsertGlobalCursor(SCOPE_GLOBAL, maxMessageId);
        }
        if (enqueued > 0 || log.isDebugEnabled()) {
            log.info("[Subscription] 补扫一轮：水位 {} → 窗口内 max(id) {}，入队 {} 行",
                    highWater, maxMessageId, enqueued);
        }
        return enqueued;
    }

    /** 读全局水位（无行视 0；scope='global' 单行行式，§2.4）。 */
    private long readHighWater() {
        WxSubDeliveryCursorEntity row = cursorMapper.selectOne(
                new LambdaQueryWrapper<WxSubDeliveryCursorEntity>()
                        .eq(WxSubDeliveryCursorEntity::getScope, SCOPE_GLOBAL)
                        .last("LIMIT 1"));
        return row == null || row.getLastMessageId() == null ? 0L : row.getLastMessageId();
    }

    /**
     * 本轮水位目标 = 补扫窗口内 max(wx_message.id)（与 {@code backstopEnqueue} 同一 DB now() 窗口
     * 表达式，杜绝双钟偏差；空窗返回 0 不推进）。窗口边界消息语义：created_at &gt; now()-window 的
     * max id 必然 ≥ 任何窗内已入队 id。
     */
    private long readMaxMessageId(int windowSeconds) {
        WxMessageEntity last = messageMapper.selectOne(new LambdaQueryWrapper<WxMessageEntity>()
                .select(WxMessageEntity::getId)
                .apply("created_at > now() - ({0} * interval '1 second')", windowSeconds)
                .orderByDesc(WxMessageEntity::getId)
                .last("LIMIT 1"));
        return last == null || last.getId() == null ? 0L : last.getId();
    }
}
