package com.wechat.sync.server.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.account.entity.WxRegCodeEntity;

/**
 * wx_reg_code Mapper。签发/列表/作废归 RegCodeService、消费归自助注册流程（T9-4）；
 * 哈希唯一性由部分唯一索引 uk_wx_reg_code_hash（is_deleted=0）兜底。
 */
public interface WxRegCodeMapper extends BaseMapper<WxRegCodeEntity> {
}
