package com.wechat.sync.server.heartbeat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.heartbeat.dto.HeartbeatRequest;
import com.wechat.sync.server.heartbeat.dto.HeartbeatVO;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 心跳落库（API_DESIGN §4.3）：更新 {@code wx_account} 的
 * {@code last_seen_at = now} / {@code hostname} / {@code client_version}。
 *
 * <p>不碰 {@code last_sync_at}（那是游标推进的语义，归消息 ingest）；不在心跳里做任何
 * 合并/上报动作——心跳是纯活性信号，失败不应拖累同步链路。观测字段（wechat_version /
 * db_size_bytes / logged_in）无对应列，只进日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private static final int NORMAL = 0;

    private final WxAccountMapper accountMapper;

    /** 10101：account 必填。public：Controller 须在绑定校验（10300/10601）之前先调（次序不能反）。 */
    public String requireAccount(String account) {
        if (account == null || account.isBlank()) {
            throw new ApiException(ApiCode.PARAM_MISSING, "account 必填");
        }
        return account.trim();
    }

    /**
     * 更新活性信息并回服务器时间。
     *
     * <p>hostname / client_version 缺省时<b>不覆写</b>旧值（心跳字段全量可选，缺字段 ≠ 清字段）；
     * {@code requireRegistered} 已验过注册，这里 updated=0 只可能是并发被注销 → 10400。
     */
    public HeartbeatVO heartbeat(String account, HeartbeatRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<WxAccountEntity> update = new LambdaUpdateWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, account)
                .eq(WxAccountEntity::getIsDeleted, NORMAL)
                .set(WxAccountEntity::getLastSeenAt, now);
        if (notBlank(request.hostname())) {
            update.set(WxAccountEntity::getHostname, request.hostname().trim());
        }
        if (notBlank(request.clientVersion())) {
            update.set(WxAccountEntity::getClientVersion, request.clientVersion().trim());
        }
        int updated = accountMapper.update(null, update);
        if (updated != 1) {
            throw new ApiException(ApiCode.NOT_FOUND, "账号不存在: " + account);
        }
        // 观测字段：wx_account 无 wechat_version / db_size / logged_in 列，不落库（DB_DESIGN 为权威，
        // 不为心跳私加列），日志留痕供排障/容量观测
        log.info("[Heartbeat] account={} hostname={} clientVersion={} wechatVersion={} dbSizeBytes={} loggedIn={}",
                account, request.hostname(), request.clientVersion(), request.wechatVersion(),
                request.dbSizeBytes(), request.loggedIn());
        return new HeartbeatVO(now);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
