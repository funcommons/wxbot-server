package com.wechat.sync.server.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.message.entity.WxMessageAttachmentEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** wx_message_attachment Mapper（upsert 走 service 先查后插/改）。 */
public interface WxMessageAttachmentMapper extends BaseMapper<WxMessageAttachmentEntity> {

    /**
     * fb 行合并删除前把其附件改指保留行（SYNCH_PLAN §6.4「改指过去」同样适用于事务表）。
     * 子查询与 UPDATE 同快照（PG 语义），已撞保留行 uk (message_id, kind, tier, sha256) 的附件跳过不迁，
     * 既不产生孤儿行也不触发唯一冲突；保留行已有同内容附件，元数据等价。
     *
     * @return 实际改指的行数
     */
    @Update("""
            UPDATE wx_message_attachment t
               SET message_id = #{keptId},
                   updated_at = CURRENT_TIMESTAMP
             WHERE t.message_id = #{removedId}
               AND NOT EXISTS (SELECT 1 FROM wx_message_attachment k
                                WHERE k.message_id = #{keptId}
                                  AND k.kind = t.kind AND k.tier = t.tier AND k.sha256 = t.sha256)
            """)
    int repointToKept(@Param("removedId") long removedId, @Param("keptId") long keptId);
}
