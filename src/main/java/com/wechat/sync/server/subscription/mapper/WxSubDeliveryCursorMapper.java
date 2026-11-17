package com.wechat.sync.server.subscription.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.subscription.entity.WxSubDeliveryCursorEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * wx_sub_delivery_cursor Mapper：补扫水位行级 upsert（SUBSCRIPTION_DESIGN §2.4）。
 */
public interface WxSubDeliveryCursorMapper extends BaseMapper<WxSubDeliveryCursorEntity> {

    /**
     * 高水位推进（§2.4/§3.2）：无行则建（scope 唯一索引 {@code uk_wx_sub_delivery_cursor_scope}
     * 承接 ON CONFLICT），有行取 {@code GREATEST} **乐观防回退**——并发补扫轮次交错时水位
     * 只前进不后退；返回 1 = 落行成功（INSERT 与 UPDATE 分支都计 1）。
     *
     * <p>游标读取走 BaseMapper（selectOne by scope）；本方法只管推进。
     */
    @Update("""
            INSERT INTO wx_sub_delivery_cursor (scope, last_message_id)
            VALUES (#{scope}, #{lastMessageId})
            ON CONFLICT (scope)
            DO UPDATE SET last_message_id = GREATEST(wx_sub_delivery_cursor.last_message_id,
                                                     EXCLUDED.last_message_id),
                         updated_at = now()
            """)
    int upsertGlobalCursor(@Param("scope") String scope,
                           @Param("lastMessageId") long lastMessageId);
}
