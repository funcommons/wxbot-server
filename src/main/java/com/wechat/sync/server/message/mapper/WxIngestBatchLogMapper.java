package com.wechat.sync.server.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.message.entity.WxIngestBatchLogEntity;

/** wx_ingest_batch_log Mapper（每批 insert 一行，无更新）。 */
public interface WxIngestBatchLogMapper extends BaseMapper<WxIngestBatchLogEntity> {
}
