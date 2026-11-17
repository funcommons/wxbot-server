package com.wechat.sync.server.task.dto;

import com.wechat.sync.server.task.entity.WxTaskEntity;

import java.time.OffsetDateTime;

/**
 * claim 响应元素（TASK_DISPATCH §6）：认领凭据包——{@code leaseId} 是后续 complete/fail/renew
 * 的凭据；{@code leaseUntil} 是 renew 节拍（lease/3）的基准（v1.1：以服务端返回为准，
 * 客户端不靠本地配置猜租约时长）。taskId 为雪花 Long（framework4j-web 全局 Long→String 序列化，
 * mc-api-spec ID 必须 String 的口径在序列化层落地）。
 */
public record TaskClaimVO(
        Long taskId,
        String taskType,
        String target,
        String payload,
        String leaseId,
        OffsetDateTime leaseUntil,
        Integer attempts) {

    public static TaskClaimVO from(WxTaskEntity task) {
        return new TaskClaimVO(task.getId(), task.getTaskType(), task.getTarget(), task.getPayload(),
                task.getLeaseId(), task.getLeaseUntil(), task.getAttempts());
    }
}
