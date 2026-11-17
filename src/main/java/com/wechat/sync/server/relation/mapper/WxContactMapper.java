package com.wechat.sync.server.relation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.relation.entity.WxContactEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * wx_contact Mapper（包扫描见 {@code WxSyncServerApplication} 的 @MapperScan）。
 */
public interface WxContactMapper extends BaseMapper<WxContactEntity> {

    /**
     * 标签映射：把 wx_label_dict.id 追加进 {@code tag_ids}（int4[]，去重幂等，SYNCH_PLAN §6.5 / DB_DESIGN §7）。
     *
     * <p>{@code unnest(tag_ids || ARRAY[?]::int4[])} + {@code array_agg(DISTINCT x)}：已含该 id 时结果不变
     * （重放无害）；数组顺序不保证（集合语义，筛选走 GIN 不依赖顺序）。只命中 {@code is_deleted=0} 行。
     *
     * @return 命中行数（0 = 该 (account, username) 无未删除联系人，映射失败转 hint 表）
     */
    @Update("""
            UPDATE wx_contact
               SET tag_ids = (SELECT array_agg(DISTINCT x)
                                FROM unnest(tag_ids || ARRAY[#{dictId}]::int4[]) AS x),
                   updated_at = CURRENT_TIMESTAMP
             WHERE account = #{account} AND username = #{username} AND is_deleted = 0
            """)
    int appendTagId(@Param("account") String account,
                    @Param("username") String username,
                    @Param("dictId") Integer dictId);
}
