package com.wechat.sync.server.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.task.entity.WxTaskEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * wx_task Mapper：MP 通用 CRUD + 任务分发的带状态条件 SQL（TASK_DISPATCH §4）。
 *
 * <p>认领/物化用 {@code @Select} 执行 {@code UPDATE/INSERT .. RETURNING}（PG JDBC 对 RETURNING
 * 返回结果集，MyBatis 按方法返回类型 + map-underscore-to-camel-case 映射到实体）。
 * 所有汇报类 UPDATE 都带 {@code lease_id + 活动状态} 条件——0 行命中即「租约已易主/被回收/已终态」，
 * 上层复查询判定为终态重放（同 lease）或 10603 TASK_LEASE_LOST（异 lease / CANCELLED）。
 */
public interface WxTaskMapper extends BaseMapper<WxTaskEntity> {

    /**
     * 原子认领（§4.1 方案命门）：子查询 {@code FOR UPDATE SKIP LOCKED} 锁定待派行（并发认领不锁死
     * 不重复），外层 UPDATE 改状态发租约并 {@code attempts+1}，RETURNING 带回 {@code lease_until}
     * （客户端 renew 节拍基准，v1.1——以服务端返回为准）。
     *
     * <p>taskTypesCsv 为 null 不过滤；非空用 {@code string_to_array = ANY} 匹配（调用方已按白名单
     * 校验且走参数绑定，无动态 SQL、无注入面）。pgcrypto 已启用（schema.sql CREATE EXTENSION），
     * gen_random_uuid() 直接可用。
     */
    @Select("""
            UPDATE wx_task
               SET status = 'DISPATCHED', lease_id = gen_random_uuid()::text,
                   lease_until = now() + (#{leaseSeconds} * interval '1 second'),
                   attempts = attempts + 1, dispatched_at = now(), updated_at = now()
             WHERE id IN (SELECT id FROM wx_task
                           WHERE status = 'PENDING' AND account = #{account} AND is_deleted = 0
                             AND (#{taskTypesCsv}::text IS NULL
                                  OR task_type = ANY(string_to_array(#{taskTypesCsv}, ',')))
                           ORDER BY priority DESC, created_at
                           LIMIT #{maxTasks}
                           FOR UPDATE SKIP LOCKED)
            RETURNING id, task_type, target, payload, lease_id, lease_until, attempts
            """)
    List<WxTaskEntity> claimTasks(@Param("account") String account,
                                  @Param("maxTasks") int maxTasks,
                                  @Param("leaseSeconds") int leaseSeconds,
                                  @Param("taskTypesCsv") String taskTypesCsv);

    /**
     * 物化/手工建任务（§4.3/§8）：撞部分唯一索引 uk_wx_task_active_target（活动态）即 DO NOTHING
     * 防堆积；ON CONFLICT 目标必须带 WHERE 谓词（PG 对部分唯一索引的推断语法）。
     * RETURNING id 有行 = 新建成功，空 = 已有同键活动任务被跳过——上层据此区分。
     */
    @Select("""
            INSERT INTO wx_task (task_type, account, target, priority, payload, schedule_id)
            VALUES (#{taskType}, #{account}, #{target}, #{priority}, #{payload}::jsonb, #{scheduleId})
            ON CONFLICT (account, task_type, target)
                WHERE status IN ('PENDING', 'DISPATCHED', 'RUNNING')
            DO NOTHING
            RETURNING id
            """)
    List<Long> insertTaskIgnoreReturnId(@Param("taskType") String taskType,
                                        @Param("account") String account,
                                        @Param("target") String target,
                                        @Param("priority") int priority,
                                        @Param("payload") String payload,
                                        @Param("scheduleId") Long scheduleId);

    /**
     * 同上，但支持 max_attempts 覆盖（手工建任务 T9-5b 扩参）：null → COALESCE 落 DDL 缺省 3。
     * 刻意**不重载同名**——MyBatis mapper statement id = 方法名，同名重载会注册冲突。
     */
    @Select("""
            INSERT INTO wx_task (task_type, account, target, priority, payload, schedule_id, max_attempts)
            VALUES (#{taskType}, #{account}, #{target}, #{priority}, #{payload}::jsonb, #{scheduleId},
                    COALESCE(#{maxAttempts}, 3))
            ON CONFLICT (account, task_type, target)
                WHERE status IN ('PENDING', 'DISPATCHED', 'RUNNING')
            DO NOTHING
            RETURNING id
            """)
    List<Long> insertTaskIgnoreReturnIdMax(@Param("taskType") String taskType,
                                           @Param("account") String account,
                                           @Param("target") String target,
                                           @Param("priority") int priority,
                                           @Param("payload") String payload,
                                           @Param("scheduleId") Long scheduleId,
                                           @Param("maxAttempts") Integer maxAttempts);

    /**
     * FULL_SCAN '*' 轮转候选（§4.3 v1.1）：按「最久未全扫」排序。last_full_scan 取该会话各分片的
     * 最小值（任一分片扫过即视为近期扫过，MIN 忽略 NULL）；{@code NULLS FIRST} 让从未全扫的会话
     * 排最前；无任何游标行的会话按 wx_conversation.created_at 兜底排序。数据源由 S7 起客户端游标
     * 镜像上报（v1.1），此前该列恒 NULL → 退化为按 created_at 顺序轮转，方案有明确兜底。
     */
    @Select("""
            SELECT c.username
              FROM wx_conversation c
              LEFT JOIN wx_sync_cursor s ON s.account = c.account AND s.chat_username = c.username
             WHERE c.account = #{account} AND c.is_deleted = 0
             GROUP BY c.username, c.created_at
             ORDER BY MIN(s.last_full_scan) ASC NULLS FIRST, c.created_at ASC
             LIMIT #{limit}
            """)
    List<String> selectChatsForFullScan(@Param("account") String account, @Param("limit") int limit);

    /**
     * 回收巡检第一步（§4.3）：锁定并取过期租约行。SKIP LOCKED——多实例巡检并发不互相踩；
     * 第二步（TaskService 逐行置 PENDING/DEAD + run 日志 LOST）与其同事务。
     * 显式列字段（mc-database-spec 禁 SELECT *）。
     */
    @Select("""
            SELECT id, account, task_type, target, status, attempts, max_attempts, lease_id, lease_until
              FROM wx_task
             WHERE status IN ('DISPATCHED', 'RUNNING') AND lease_until < now()
             FOR UPDATE SKIP LOCKED
            """)
    List<WxTaskEntity> selectExpiredForUpdate();

    /** complete 落终态（§4.2）：条件不命中返回 0 = 租约失效/已终态，上层复查询定性。result 是 jsonb 列，参数须显式 cast（varchar 表达式 PG 拒收）；complete 路径经 requireValidJsonObject 归一，null 已在服务层落 '{}'（服务层保证非空，与 wx_task_run.result 口径一致）。 */
    @Update("""
            UPDATE wx_task
               SET status = #{targetStatus}, finished_at = now(), result = #{result}::jsonb,
                   last_error = #{err}, updated_at = now()
             WHERE id = #{taskId} AND lease_id = #{leaseId}
               AND status IN ('DISPATCHED', 'RUNNING')
            """)
    int reportTerminal(@Param("targetStatus") String targetStatus,
                       @Param("taskId") long taskId,
                       @Param("leaseId") String leaseId,
                       @Param("result") String result,
                       @Param("err") String err);

    /** 续租（§4.2/§7）：每次都置 RUNNING（观测态口径，§3）；条件不命中返回 0。 */
    @Update("""
            UPDATE wx_task
               SET lease_until = now() + (#{leaseSeconds} * interval '1 second'),
                   status = 'RUNNING', updated_at = now()
             WHERE id = #{taskId} AND lease_id = #{leaseId}
               AND status IN ('DISPATCHED', 'RUNNING')
            """)
    int renewLease(@Param("taskId") long taskId,
                   @Param("leaseId") String leaseId,
                   @Param("leaseSeconds") int leaseSeconds);

    /** 可重试失败回 PENDING（§3）：清租约 + attempts<max 双条件；attempts 过线则 0 行，上层改判 DEAD。 */
    @Update("""
            UPDATE wx_task
               SET status = 'PENDING', lease_id = NULL, lease_until = NULL,
                   last_error = #{err}, updated_at = now()
             WHERE id = #{taskId} AND lease_id = #{leaseId}
               AND status IN ('DISPATCHED', 'RUNNING') AND attempts < max_attempts
            """)
    int failBackToPending(@Param("taskId") long taskId,
                          @Param("leaseId") String leaseId,
                          @Param("err") String err);

    /** 不可重试/重试额度用尽 → DEAD 终态：终态行保留 lease_id（§2.2，重放判定凭据），result 不覆写。 */
    @Update("""
            UPDATE wx_task
               SET status = 'DEAD', finished_at = now(), last_error = #{err}, updated_at = now()
             WHERE id = #{taskId} AND lease_id = #{leaseId}
               AND status IN ('DISPATCHED', 'RUNNING')
            """)
    int failToDead(@Param("taskId") long taskId,
                   @Param("leaseId") String leaseId,
                   @Param("err") String err);

    /** 管理员取消（§6）：PENDING/DISPATCHED → CANCELLED，保留 lease_id；迟到回报按 10603。 */
    @Update("""
            UPDATE wx_task
               SET status = 'CANCELLED', finished_at = now(), updated_at = now()
             WHERE id = #{taskId} AND status IN ('PENDING', 'DISPATCHED')
            """)
    int cancelTaskRow(@Param("taskId") long taskId);

    /** 管理员重置死信（§10）：DEAD → PENDING，attempts 归零清租约清错误，重新参与认领。 */
    @Update("""
            UPDATE wx_task
               SET status = 'PENDING', attempts = 0, lease_id = NULL, lease_until = NULL,
                   last_error = NULL, finished_at = NULL, updated_at = now()
             WHERE id = #{taskId} AND status = 'DEAD'
            """)
    int resetDeadRow(@Param("taskId") long taskId);
}
