package com.wechat.sync.server.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * wx_message Mapper（包扫描见 {@code WxSyncServerApplication} 的 @MapperScan）。
 */
public interface WxMessageMapper extends BaseMapper<WxMessageEntity> {

    /**
     * seen_accounts 反算（SYNCH_PLAN §6.4）：口径恒等于
     * {@code count(DISTINCT wx_message_source_rel.account)}，幂等且和来源表永不漂移——
     * 绝不用 {@code seen_accounts + 1}（重放同批会虚增，verify_dedup.sql §3 专门测过）。
     *
     * <p>批内 id 分块传入（每块 ≤{@code CHUNK}）避免 IN 列表过长；无命中行的 id 不产生 UPDATE。
     *
     * @return 本块实际被更新的行数
     */
    @Update("""
            <script>
            UPDATE wx_message m
               SET seen_accounts = s.cnt,
                   updated_at = CURRENT_TIMESTAMP
              FROM (SELECT message_id, count(DISTINCT account) AS cnt
                      FROM wx_message_source_rel
                     WHERE message_id IN
                     <foreach collection="messageIds" item="mid" open="(" separator="," close=")">#{mid}</foreach>
                     GROUP BY message_id) s
             WHERE m.id = s.message_id
            </script>
            """)
    int recomputeSeenAccounts(@Param("messageIds") List<Long> messageIds);
}
