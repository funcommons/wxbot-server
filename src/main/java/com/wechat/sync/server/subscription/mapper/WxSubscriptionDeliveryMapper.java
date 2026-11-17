package com.wechat.sync.server.subscription.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.subscription.entity.WxSubscriptionDeliveryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * wx_subscription_delivery Mapper：投递队列的带状态条件 SQL（SUBSCRIPTION_DESIGN §3.2/§4/§5.3/§6）。
 *
 * <p>队列原语与 wx_task 同款（v1.1 教训全数继承）：认领走 {@code FOR UPDATE SKIP LOCKED}
 * 子查询 + UPDATE…RETURNING；所有汇报类 UPDATE 带 {@code lease_id + status='DISPATCHED'}
 * 条件——0 行命中即「租约已易主/被回收/已终态」，上层复查询定性为终态重放（同 lease 幂等）
 * 或 10606 DELIVERY_LEASE_LOST（异 lease）；**终态行保留 lease_id**（重放判定凭据）。
 * WEBHOOK 认领（{@link #claimDueWebhook}）与 POLL 拉取（{@link #claimForPull}）按
 * {@code delivery_mode} 互斥——一条投递行只会被推送或被拉取之一接管，不存在两形争抢。
 */
public interface WxSubscriptionDeliveryMapper extends BaseMapper<WxSubscriptionDeliveryEntity> {

    /**
     * WEBHOOK dispatcher 原子认领（§5.3 v1.1 逐字）：只认投 {@code delivery_mode='WEBHOOK'} 行
     * （POLL 行只由消费者拉取，两形互斥）；同订阅在飞上限门
     * {@code CASE WHEN STRICT_FIFO THEN 1 ELSE :perSubInflight}——单飞窗口与共享池公平性
     * 一条 SQL 两档语义（挂死消费者至多占 perSubInflight 席，不 HOL 饿死其它订阅）。
     *
     * <p>注：在飞计数子查询按语句快照取数，同一语句内刚置 DISPATCHED 的行彼此不可见——
     * 单语句批量上限为 LIMIT 本身（调度侧 LIMIT=空闲 worker 数），跨语句累计才受门约束
     * （调度节奏 200ms/轮，实际影响可忽略，登记为已知快照语义）。RETURNING 只回认领所需列，
     * 实体其余字段为 null（按需取，勿整实体落库）。
     */
    @Select("""
            UPDATE wx_subscription_delivery
               SET status = 'DISPATCHED', lease_id = gen_random_uuid()::text,
                   lease_until = now() + (lease_seconds * interval '1 second'),
                   attempts = attempts + 1, updated_at = now()
             WHERE id IN (
                    SELECT d.id
                      FROM wx_subscription_delivery d
                      JOIN wx_subscription s ON s.id = d.subscription_id
                                             AND s.enabled = 1 AND s.is_deleted = 0
                     WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
                       AND s.delivery_mode = 'WEBHOOK'
                       AND (SELECT count(*) FROM wx_subscription_delivery f
                             WHERE f.subscription_id = d.subscription_id
                               AND f.status = 'DISPATCHED')
                           < CASE WHEN s.order_mode = 'STRICT_FIFO' THEN 1
                                  ELSE #{perSubInflight} END
                     ORDER BY d.next_attempt_at, d.id
                     LIMIT #{limit}
                      FOR UPDATE OF d SKIP LOCKED)
            RETURNING id, subscription_id, consumer_id, message_id, status,
                      attempts, max_attempts, lease_seconds, lease_id, lease_until
            """)
    List<WxSubscriptionDeliveryEntity> claimDueWebhook(@Param("limit") int limit,
                                                       @Param("perSubInflight") int perSubInflight);

    /**
     * POLL 消费方拉取认领（§6 SQS 式可见性语义）：只认投 {@code delivery_mode='POLL'} 行
     * （与 {@link #claimDueWebhook} 互斥），按 {@code id} 升序（消费方自守序的批次内保证），
     * 拉走即 DISPATCHED + 可见性租约 {@code lease_until = now()+lease_seconds}；不 ack 的行
     * 到期由 {@link #sweepExpiredLeases} 回队 → at-least-once。attempts 同样认领时 +1。
     */
    @Select("""
            UPDATE wx_subscription_delivery
               SET status = 'DISPATCHED', lease_id = gen_random_uuid()::text,
                   lease_until = now() + (lease_seconds * interval '1 second'),
                   attempts = attempts + 1, updated_at = now()
             WHERE id IN (
                    SELECT d.id
                      FROM wx_subscription_delivery d
                      JOIN wx_subscription s ON s.id = d.subscription_id
                                             AND s.enabled = 1 AND s.is_deleted = 0
                     WHERE d.subscription_id = #{subscriptionId}
                       AND d.status = 'PENDING' AND d.next_attempt_at <= now()
                       AND s.delivery_mode = 'POLL'
                     ORDER BY d.id
                     LIMIT #{maxPull}
                      FOR UPDATE OF d SKIP LOCKED)
            RETURNING id, subscription_id, consumer_id, message_id, status,
                      attempts, max_attempts, lease_seconds, lease_id, lease_until
            """)
    List<WxSubscriptionDeliveryEntity> claimForPull(@Param("subscriptionId") long subscriptionId,
                                                    @Param("maxPull") int maxPull);

    /**
     * ack / WEBHOOK 2xx 落 DELIVERED 终态（§4）：条件不命中返回 0 = 租约失效/已重派/已终态，
     * 上层复查询定性（同 lease 重放幂等返回成功 / 异 lease 10606）。终态行保留 lease_id（不清空）。
     *
     * <p><b>{@code responseCode} 必须落库</b>（T10-9 真机验收揪出）：WEBHOOK 派发的成功判据是
     * HTTP 2xx，消费方排障与 DLQ 分析都靠这一列（DeliveryRowVO.responseCode）；漏写会让「投递成功」
     * 行显示不出真实状态码（与失败行的 response_code 语义不对称）。
     * <b>{@code error = NULL}</b>：重试成功的行不得残留上一次失败的错误摘要。
     * POLL ack 路径无 HTTP 响应 → 传 {@code null}（该列保持 NULL，与失败行可区分）。
     */
    @Update("""
            UPDATE wx_subscription_delivery
               SET status = 'DELIVERED', delivered_at = now(), updated_at = now(),
                   response_code = #{responseCode}, error = NULL
             WHERE id = #{id} AND lease_id = #{leaseId} AND status = 'DISPATCHED'
            """)
    int completeDelivered(@Param("id") long id,
                          @Param("leaseId") String leaseId,
                          @Param("responseCode") Integer responseCode);

    /**
     * nack / 失败重试回 PENDING（§4）：租约即弃，按调用方算好的退避秒排
     * {@code next_attempt_at}（§5.2 指数退避的落库点；服务层组合：attempts≥max 时改调
     * {@link #failDead} 而非本方法——Mapper 只提供原子原语，超限判定在服务层）。
     */
    @Update("""
            UPDATE wx_subscription_delivery
               SET status = 'PENDING', lease_id = NULL, lease_until = NULL,
                   next_attempt_at = now() + (#{backoffSeconds} * interval '1 second'),
                   response_code = #{responseCode}, error = #{error}, updated_at = now()
             WHERE id = #{id} AND lease_id = #{leaseId} AND status = 'DISPATCHED'
            """)
    int requeueForRetry(@Param("id") long id,
                        @Param("leaseId") String leaseId,
                        @Param("backoffSeconds") int backoffSeconds,
                        @Param("responseCode") Integer responseCode,
                        @Param("error") String error);

    /**
     * 重试额度用尽 → DEAD 终态（DLQ，§4）：终态行保留 lease_id（重放判定凭据，v1.1）；
     * response_code/error 落库供死信视图排障。
     */
    @Update("""
            UPDATE wx_subscription_delivery
               SET status = 'DEAD', response_code = #{responseCode}, error = #{error},
                   updated_at = now()
             WHERE id = #{id} AND lease_id = #{leaseId} AND status = 'DISPATCHED'
            """)
    int failDead(@Param("id") long id,
                 @Param("leaseId") String leaseId,
                 @Param("responseCode") Integer responseCode,
                 @Param("error") String error);

    /**
     * 过期在飞租约回收巡检（§4，调度 30s 一轮）：DISPATCHED 且 lease_until < now()——
     * attempts ≥ max_attempts → DEAD（error 置 'lease expired'，lease_id 保留）；
     * 否则回 PENDING（清 lease_id/lease_until，next_attempt_at=now() 立即重投）。
     * 一条 CASE 式 SQL 两分支；返回受影响行数（回收量观测）。
     */
    @Update("""
            UPDATE wx_subscription_delivery
               SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                   lease_id = CASE WHEN attempts >= max_attempts THEN lease_id ELSE NULL END,
                   lease_until = NULL,
                   next_attempt_at = now(),
                   error = CASE WHEN attempts >= max_attempts THEN 'lease expired' ELSE error END,
                   updated_at = now()
             WHERE status = 'DISPATCHED' AND lease_until < now()
            """)
    int sweepExpiredLeases();

    /**
     * 漏匹配补扫入队（§3.2 v1.1 逐字，兜底保险带）：扫 (高水位, now()-窗口] 的新消息 × 全部
     * enabled 订阅，命中即插 PENDING 行；filter 各键与 ingest 内存匹配器同语义（键之间 AND、
     * 数组值 = ANY 精确、keyword 子串、include_revoked 缺省 true）。两点设计要点：
     * ① **账号条件必须 EXISTS wx_message_source_rel**——wx_message 不带 account 维度（跨账号
     * 全局事实）；② {@code ON CONFLICT (subscription_id, message_id) DO NOTHING} 与触发钩子
     * 并发/重放/人工补数全部无害（uk 双闸门的第二道）。返回新插入行数（0 = 无漏）。
     *
     * <p>游标推进不在此做（服务层先读水位 → 补扫 → upsertGlobalCursor，GREATEST 防回退）；
     * {@code windowSeconds} 缺省 600（10 分钟窗，keyword 无索引但窗口内消息量可忽略）。
     *
     * <p><b>keyword 子串匹配用 {@code position(kw in m.body) > 0}，不用 {@code LIKE}（两处理由）</b>：
     * ① <b>PG 运算符优先级陷阱</b>——{@code ->}、{@code ->>}、{@code ||} 同属「其它运算符」同级左结合，
     * {@code '%' || s.filter->>'keyword' || '%'} 会被解析成 {@code (('%' || s.filter) ->> 'keyword') || '%'}，
     * 其中 {@code '%' || jsonb} 落到 {@code jsonb || jsonb} 重载 → PG 把 {@code '%'} 当 JSON 解析 →
     * {@code invalid input syntax for type json}，<b>该语句每轮必炸</b>（补扫保险带整条链死）；
     * ② <b>语义对齐</b>——内存匹配器 {@code SubscriptionFilter.Parsed.matches} 用
     * {@code body.contains(keyword)}（大小写敏感的纯子串，无通配符），{@code LIKE '%kw%'} 会把
     * keyword 里的 {@code %} / {@code _} 当通配符 → 补扫命中集宽于钩子命中集（同一条消息在两条
     * 路径上口径不一致）。{@code position(...)} 逐字节等价于 {@code contains}，且 NULL body 两边
     * 同落 NULL（不命中），语义完全一致。
     */
    @Update("""
            INSERT INTO wx_subscription_delivery
                   (subscription_id, consumer_id, message_id, status, max_attempts, lease_seconds)
            SELECT s.id, s.consumer_id, m.id, 'PENDING', s.max_attempts, s.visible_seconds
              FROM wx_subscription s
              JOIN wx_message m
                ON m.id > #{cursorHighWater}
               AND m.created_at > now() - (#{windowSeconds} * interval '1 second')
               AND (s.filter->'chats'      IS NULL OR m.chat_username
                    = ANY(ARRAY(SELECT jsonb_array_elements_text(s.filter->'chats'))))
               AND (s.filter->'senders'    IS NULL OR m.sender_username
                    = ANY(ARRAY(SELECT jsonb_array_elements_text(s.filter->'senders'))))
               AND (s.filter->'type_codes' IS NULL OR m.type_code
                    = ANY(ARRAY(SELECT jsonb_array_elements_text(s.filter->'type_codes'))))
               AND (s.filter->>'keyword'   IS NULL OR position((s.filter->>'keyword') in m.body) > 0)
               AND (COALESCE((s.filter->>'include_revoked')::boolean, true) OR m.is_revoked = false)
               AND (s.filter->'accounts'   IS NULL OR EXISTS (
                    SELECT 1 FROM wx_message_source_rel r
                     WHERE r.message_id = m.id
                       AND r.account = ANY(ARRAY(SELECT jsonb_array_elements_text(s.filter->'accounts'))))
               )
             WHERE s.enabled = 1 AND s.is_deleted = 0
            ON CONFLICT (subscription_id, message_id) DO NOTHING
            """)
    int backstopEnqueue(@Param("cursorHighWater") long cursorHighWater,
                        @Param("windowSeconds") int windowSeconds);

    /**
     * DELIVERED 保留期批量清理（§8，调度 60s 一轮 DELETE ≤ cap）：子查询 LIMIT 语义
     * （PG 无 DELETE 直 LIMIT），按 created_at 升序（idx_wx_sub_dlv_cleanup 部分索引精确覆盖）。
     * 返回删除行数。DEAD/终态审计行**不在清理范围**（只删 DELIVERED）。
     */
    @Update("""
            DELETE FROM wx_subscription_delivery
             WHERE id IN (SELECT id FROM wx_subscription_delivery
                           WHERE status = 'DELIVERED' AND created_at < #{before}
                           ORDER BY created_at
                           LIMIT #{cap})
            """)
    int cleanupDelivered(@Param("before") OffsetDateTime before,
                         @Param("cap") int cap);

    /**
     * 触发钩子批量入队（§3.1 主路径）：ingest 同事务内对「本批真正 INSERT 的新消息」× 命中订阅
     * 多行 INSERT，{@code ON CONFLICT (subscription_id, message_id) DO NOTHING}——与兜底补扫 /
     * 并发触发 / 人工补数全部无害（uk 双闸门第二道）。返回<b>实际新插入</b>行数（撞 uk 的行不计）。
     *
     * <p>调用方（SubscriptionTrigger）保证 rows 非空（foreach 空集合会产出残缺 SQL）；
     * 事务边界由调用方决定——ingest 钩子路径随消息事务提交（零丢失窗口，§3.1）。
     */
    @Insert("""
            <script>
            INSERT INTO wx_subscription_delivery
                   (subscription_id, consumer_id, message_id, status, max_attempts, lease_seconds)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                   (#{r.subscriptionId}, #{r.consumerId}, #{r.messageId}, 'PENDING',
                    #{r.maxAttempts}, #{r.leaseSeconds})
            </foreach>
            ON CONFLICT (subscription_id, message_id) DO NOTHING
            </script>
            """)
    int enqueueBatch(@Param("rows") List<DeliveryEnqueueRow> rows);

    /**
     * 拉取后 {@code has_more} 判定（§6）：同订阅还有多少「PENDING 且到期」行——认领完成后计数，
     * &gt;0 即建议消费方立刻再拉。只数到期行（未到期的不算，消费方拉了也空）。
     */
    @Select("""
            SELECT count(*) FROM wx_subscription_delivery
             WHERE subscription_id = #{subscriptionId}
               AND status = 'PENDING' AND next_attempt_at <= now()
            """)
    long countDuePending(@Param("subscriptionId") long subscriptionId);

    /**
     * 死信重排（§4 DLQ 救济）：整订阅 DEAD → PENDING（attempts=0 重置额度、清 lease 凭据、
     * 立即到期）。终态行 lease_id 在此处<b>清空</b>（与 DELIVERED/DEAD 保留口径不冲突——
     * 行已离开终态，重放判定只对终态行有意义；消费方拿旧 lease 汇报会命中 0 行 → 10606）。
     * 归属校验在服务层（consumer 必须持有该订阅）。返回重排行数。
     */
    @Update("""
            UPDATE wx_subscription_delivery
               SET status = 'PENDING', attempts = 0, lease_id = NULL, lease_until = NULL,
                   next_attempt_at = now(), updated_at = now()
             WHERE subscription_id = #{subscriptionId} AND status = 'DEAD'
            """)
    int requeueDeadRows(@Param("subscriptionId") long subscriptionId);

    /**
     * 误投重投（§4 v1.1 救济）：DELIVERED → PENDING（attempts <b>续算不清零</b>、立即到期）——
     * 覆盖「服务端已标成功但消费方声称未收到」的对账场景。仅 DELIVERED 可 redeliver
     * （其它状态 10402，服务层判定）；终态 lease_id 清空（同上口径）。
     */
    @Update("""
            UPDATE wx_subscription_delivery
               SET status = 'PENDING', lease_id = NULL, lease_until = NULL,
                   next_attempt_at = now(), delivered_at = NULL, updated_at = now()
             WHERE id = #{id} AND status = 'DELIVERED'
            """)
    int redeliver(@Param("id") long id);
}
