package com.wechat.sync.server.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.message.entity.WxBlobEntity;

/**
 * wx_blob Mapper。T4 只用读侧「按 sha256 批量查存在性」（{@code is_deleted=0} 同谓词，部分唯一索引）；
 * 写入归 T5 的 PUT /v1/blobs/{sha256}。
 */
public interface WxBlobMapper extends BaseMapper<WxBlobEntity> {
}
