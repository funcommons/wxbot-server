package com.wechat.sync.server.status.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.message.entity.WxIngestBatchLogEntity;
import com.wechat.sync.server.message.mapper.WxIngestBatchLogMapper;
import com.wechat.sync.server.status.dto.SubscriptionDeliveryStatsRow;
import com.wechat.sync.server.status.dto.StatusVOs;
import com.wechat.sync.server.status.dto.StatusVOs.AccountStatusVO;
import com.wechat.sync.server.status.dto.StatusVOs.BatchSummaryVO;
import com.wechat.sync.server.status.dto.StatusVOs.StatusVO;
import com.wechat.sync.server.status.dto.StatusVOs.SubscriptionDeliveryStatsVO;
import com.wechat.sync.server.status.mapper.WxStatusMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 运维观测聚合（GET /v1/status，API_DESIGN 未定义、T7 任务口径）：一次读三个只读查询，
 * 无写路径、无事务边界（单条 SELECT 各自一致即可）。
 *
 * <p>复用 T2 的 {@link WxAccountMapper} / T4 的 {@link WxIngestBatchLogMapper}（零改动），
 * 只投影观测列；批次摘要 {@code .last("LIMIT 10")} 与 QueryService.ingestBatches 同款
 * 显式列清单纪律（不带 error 文本）。
 */
@Service
@RequiredArgsConstructor
public class StatusService {

    private static final int NORMAL = 0;
    /** recent_batches 固定取最近 10 批（T7 任务口径），Offset 分页语义请走 GET /v1/ingest-batches。 */
    private static final long RECENT_BATCHES = 10;

    private final WxStatusMapper statusMapper;
    private final WxAccountMapper accountMapper;
    private final WxIngestBatchLogMapper batchLogMapper;

    public StatusVO status() {
        List<AccountStatusVO> accounts = accountMapper.selectList(new LambdaQueryWrapper<WxAccountEntity>()
                        .eq(WxAccountEntity::getIsDeleted, NORMAL)
                        .select(WxAccountEntity::getAccount, WxAccountEntity::getHostname,
                                WxAccountEntity::getClientVersion, WxAccountEntity::getLastSyncAt,
                                WxAccountEntity::getLastSeenAt)
                        .orderByAsc(WxAccountEntity::getAccount))
                .stream().map(AccountStatusVO::from).toList();
        List<BatchSummaryVO> batches = batchLogMapper.selectList(
                        new LambdaQueryWrapper<WxIngestBatchLogEntity>()
                                .select(WxIngestBatchLogEntity::getId, WxIngestBatchLogEntity::getAccount,
                                        WxIngestBatchLogEntity::getBatchRef, WxIngestBatchLogEntity::getStartedAt,
                                        WxIngestBatchLogEntity::getDurationMs, WxIngestBatchLogEntity::getNMessages,
                                        WxIngestBatchLogEntity::getNInserted, WxIngestBatchLogEntity::getNMerged,
                                        WxIngestBatchLogEntity::getStatus)
                                .orderByDesc(WxIngestBatchLogEntity::getStartedAt)
                                .orderByDesc(WxIngestBatchLogEntity::getId)
                                .last("LIMIT " + RECENT_BATCHES))
                .stream().map(BatchSummaryVO::from).toList();
        SubscriptionDeliveryStatsRow stats = statusMapper.subscriptionDeliveryStats();
        return new StatusVO(accounts, statusMapper.countFbPending(), statusMapper.countSuspectDuplicate(),
                batches, SubscriptionDeliveryStatsVO.from(stats));
    }
}
