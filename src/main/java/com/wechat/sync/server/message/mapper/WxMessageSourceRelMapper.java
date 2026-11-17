package com.wechat.sync.server.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.message.entity.WxMessageSourceRelEntity;

/** wx_message_source_rel Mapper（upsert 走 service 先查后插/改）。 */
public interface WxMessageSourceRelMapper extends BaseMapper<WxMessageSourceRelEntity> {
}
