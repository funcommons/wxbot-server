package com.wechat.sync.server.subscription.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.subscription.entity.WxConsumerEntity;

/**
 * wx_consumer Mapper：纯 MP 通用 CRUD（SUBSCRIPTION_DESIGN §2.1）——无自定义 SQL，
 * 查询一律 wrapper（软删过滤 {@code is_deleted=0} 由调用方拼）。
 * 部分唯一索引 {@code uk_wx_consumer_name} 拦同名未删行；凭据哈希（tokenHash/secretHash）
 * 的生成与比对属 T10-4 服务层，Mapper 不做哈希。
 */
public interface WxConsumerMapper extends BaseMapper<WxConsumerEntity> {
}
