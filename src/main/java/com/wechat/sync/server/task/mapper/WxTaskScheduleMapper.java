package com.wechat.sync.server.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wechat.sync.server.task.entity.WxTaskScheduleEntity;

/**
 * wx_task_schedule Mapper。调度器物化扫描（next_materialize_at <= now()，enabled=1）与
 * next_materialize_at 乐观锁推进属 TaskService（T9-3）。
 */
public interface WxTaskScheduleMapper extends BaseMapper<WxTaskScheduleEntity> {
}
