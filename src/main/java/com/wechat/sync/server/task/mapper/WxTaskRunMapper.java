package com.wechat.sync.server.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.task.entity.WxTaskRunEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * wx_task_run Mapper：认领事务内插历史行（TaskService，MP insert）；汇报/回收时补 outcome。
 *
 * <p>两条补写路径刻意分开：complete/fail 走 {@link #closeRun}（{@code finished_at=now()}）；
 * 租约回收/管理��取消走 {@link #markRunLost}——只落 outcome，{@code finished_at} 保持 NULL，
 * 与 schema.sql「NULL = 租约丢失被回收」的口径一致（verify_task_dispatch.sql 断言 8 锚定此约定）。
 *
 * <p>jsonb NOT NULL 口径：{@code wx_task_run.result} 列 NOT NULL（schema.sql §2.3，run 行必有
 * 结果摘要）——服务层保证语义上「无结果」时传 {@code null}，SQL 侧 {@code coalesce} 落
 * {@code '{}'::jsonb} 兜底（真机验收 P1 2026-11-10：fail 路径直插 NULL 撞约束）。
 */
public interface WxTaskRunMapper extends BaseMapper<WxTaskRunEntity> {

    /** complete/fail 关闭本次派发行：{@code finished_at IS NULL} 条件保证只关当前未回报的这次派发。result 是 jsonb 列，参数须显式 cast（varchar 表达式 PG 拒收）；fail 无 result 时 coalesce 落 '{}'（列 NOT NULL）。 */
    @Update("""
            UPDATE wx_task_run
               SET finished_at = now(), outcome = #{outcome},
                   result = coalesce(#{result}::jsonb, '{}'::jsonb), error = #{error}
             WHERE task_id = #{taskId} AND lease_id = #{leaseId} AND finished_at IS NULL
            """)   // fail 无 result 落 '{}'——wx_task_run.result NOT NULL（真机验收 P1 2026-11-10）
    int closeRun(@Param("taskId") long taskId,
                 @Param("leaseId") String leaseId,
                 @Param("outcome") String outcome,
                 @Param("result") String result,
                 @Param("error") String error);

    /** 回收/取消落 LOST：finished_at 刻意保持 NULL（§2.3 排障口径：NULL = 没有回报时点）。 */
    @Update("""
            UPDATE wx_task_run
               SET outcome = 'LOST', error = #{error}
             WHERE task_id = #{taskId} AND lease_id = #{leaseId} AND finished_at IS NULL
            """)
    int markRunLost(@Param("taskId") long taskId,
                    @Param("leaseId") String leaseId,
                    @Param("error") String error);
}
