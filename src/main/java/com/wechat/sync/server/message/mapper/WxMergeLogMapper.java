package com.wechat.sync.server.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.message.entity.WxMergeLogEntity;

/** wx_merge_log Mapper（fb→srv 合并审计，只 insert）。 */
public interface WxMergeLogMapper extends BaseMapper<WxMergeLogEntity> {
}
