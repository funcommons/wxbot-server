package com.wechat.sync.server.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.message.entity.WxSyncCursorEntity;

/** wx_sync_cursor Mapper（镜像 upsert 走 service 先查后插/改）。 */
public interface WxSyncCursorMapper extends BaseMapper<WxSyncCursorEntity> {
}
