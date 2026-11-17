package com.wechat.sync.server.subscription.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;

/**
 * wx_subscription Mapper：纯 MP 通用 CRUD（SUBSCRIPTION_DESIGN §2.2）——无自定义 SQL。
 * 防重复订阅 = 部分唯一索引 {@code uk_wx_subscription_filter}（consumer_id, filter_hash，
 * 仅未删行）：插入撞索引即「同条件已存在」，上层复查询返回既有行 + already_exists=true，
 * 不报错；{@code filter} jsonb 列的写入经实体 {@code JsonbTypeHandler}（wrapper 自定义
 * set 时须带 typeHandler mapping 串，先例 {@code WxTaskEntity.payload}）。
 */
public interface WxSubscriptionMapper extends BaseMapper<WxSubscriptionEntity> {
}
