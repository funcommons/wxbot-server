package com.wechat.sync.server.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.dto.AccountVO;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.common.PageVO;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/**
 * 账号注册 / 查询 / 注销（API_DESIGN §5.1、§6）。
 *
 * <p>软删语义：{@code is_deleted=1}（应用层更新，唯一索引 uk_wx_account_account 仅约束
 * {@code is_deleted=0}，故删后可重注册）；注销附带吊销该账号全部令牌。
 * 规范禁外键、无 ON DELETE CASCADE，来源行/游标/批次的关系层清理由 ingest 任务在各自表上做。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    /** account 形如 wxid_xxx_8316：末尾下划线后的 4 位是安装序号后缀 */
    private static final Pattern SUFFIXED_ACCOUNT = Pattern.compile("^(.+)_\\d{4}$");

    private static final int DELETED = 1;
    private static final int NORMAL = 0;

    private final WxAccountMapper accountMapper;
    private final TokenService tokenService;

    /**
     * 注册账号：预检查重 + INSERT 撞唯一索引兜底（并发下预检查不可靠——双方都可能通过
     * {@code findByAccount}，唯一索引 {@code uk_wx_account_account} 才是最终裁决；
     * 裸 {@link DuplicateKeyException} 会被全局兜底译成 500/10001，此处翻成契约码 10401）。
     * catch 只包 INSERT 一步：异常继续外抛，本事务照常整体回滚（与预检失败同语义）。
     */
    @Transactional
    public AccountVO register(AccountRegisterRequest request) {
        String account = request.account().trim();
        if (findByAccount(account) != null) {
            throw new ApiException(ApiCode.UNIQUE_CONFLICT, "账号已注册: " + account);
        }

        WxAccountEntity entity = new WxAccountEntity();
        entity.setAccount(account);
        entity.setWxid(deriveWxid(account, request.wxid()));
        entity.setIsDeleted(NORMAL);
        try {
            accountMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ApiCode.UNIQUE_CONFLICT, "账号已注册: " + account);
        }
        log.info("[Account] 注册账号 account={} id={}", account, entity.getId());
        return AccountVO.from(entity);
    }

    public AccountVO detail(String account) {
        return AccountVO.from(require(account));
    }

    public PageVO<AccountVO> list(long page, long pageSize) {
        PageVO.PageParams params = PageVO.clamp(page, pageSize);
        Page<WxAccountEntity> result = accountMapper.selectPage(
                Page.of(params.page(), params.pageSize()),
                new LambdaQueryWrapper<WxAccountEntity>()
                        .eq(WxAccountEntity::getIsDeleted, NORMAL)
                        .orderByDesc(WxAccountEntity::getId));
        return PageVO.map(result.getRecords(), result.getTotal(), params.page(), params.pageSize(),
                AccountVO::from);
    }

    @Transactional
    public void delete(String account) {
        WxAccountEntity entity = require(account);
        int updated = accountMapper.update(null, new LambdaUpdateWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getId, entity.getId())
                .eq(WxAccountEntity::getIsDeleted, NORMAL)
                .set(WxAccountEntity::getIsDeleted, DELETED)
                .set(WxAccountEntity::getUpdatedAt, OffsetDateTime.now()));
        if (updated != 1) {
            throw new ApiException(ApiCode.STATE_CONFLICT, "账号状态已变更，删除失败: " + account);
        }
        int revoked = tokenService.revokeByAccount(account);
        log.info("[Account] 注销账号 account={} id={} revokedTokens={}", account, entity.getId(), revoked);
    }

    /** 取未删除账号，不存在 → 10400。 */
    public WxAccountEntity require(String account) {
        WxAccountEntity entity = findByAccount(account);
        if (entity == null) {
            throw new ApiException(ApiCode.NOT_FOUND, "账号不存在: " + account);
        }
        return entity;
    }

    private WxAccountEntity findByAccount(String account) {
        return accountMapper.selectOne(new LambdaQueryWrapper<WxAccountEntity>()
                .eq(WxAccountEntity::getAccount, account)
                .eq(WxAccountEntity::getIsDeleted, NORMAL)
                .last("LIMIT 1"));
    }

    /** wxid 缺省时按 schema 口径推导：剥掉 account 末尾 4 位后缀。 */
    private String deriveWxid(String account, String wxid) {
        if (wxid != null && !wxid.isBlank()) {
            return wxid.trim();
        }
        var matcher = SUFFIXED_ACCOUNT.matcher(account);
        return matcher.matches() ? matcher.group(1) : account;
    }
}
