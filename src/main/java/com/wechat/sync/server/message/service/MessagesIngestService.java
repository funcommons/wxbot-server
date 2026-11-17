package com.wechat.sync.server.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wechat.sync.server.message.dto.MessagesIngestVO;
import com.wechat.sync.server.message.dto.MessagesReportRequest;
import com.wechat.sync.server.message.dto.MessagesReportRequest.AttachmentItem;
import com.wechat.sync.server.message.dto.MessagesReportRequest.ChatItem;
import com.wechat.sync.server.message.dto.MessagesReportRequest.CursorItem;
import com.wechat.sync.server.message.dto.MessagesReportRequest.MessageItem;
import com.wechat.sync.server.message.entity.WxIngestBatchLogEntity;
import com.wechat.sync.server.message.entity.WxMergeLogEntity;
import com.wechat.sync.server.message.entity.WxMessageAttachmentEntity;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.entity.WxMessageSourceRelEntity;
import com.wechat.sync.server.message.entity.WxBlobEntity;
import com.wechat.sync.server.message.entity.WxSyncCursorEntity;
import com.wechat.sync.server.message.mapper.WxBlobMapper;
import com.wechat.sync.server.message.mapper.WxIngestBatchLogMapper;
import com.wechat.sync.server.message.mapper.WxMergeLogMapper;
import com.wechat.sync.server.message.mapper.WxMessageAttachmentMapper;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import com.wechat.sync.server.message.mapper.WxMessageSourceRelMapper;
import com.wechat.sync.server.message.mapper.WxSyncCursorMapper;
import com.wechat.sync.server.subscription.service.SubscriptionMatcher;
import com.wechat.sync.server.subscription.service.SubscriptionTrigger;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 消息批 ingest：POST /v1/reports/messages 落库（API_DESIGN §4.2 / SYNCH_PLAN §4 + §6.4）——去重核心。
 *
 * <p>事务边界 = 一个 (account, chat) 批：编程式 {@link TransactionTemplate} 逐 chat 开事务，<strong>整 chat
 * 批原子、chat 间独立</strong>（一个 chat 落库失败只把该 chat 消息记入 error[]，其余 chat 照常提交）。
 * 批内五步同事务：消息 upsert（dedup_key 先查后插/改，fb→srv 原地合并）→ 来源行 upsert（四元组 uk）→
 * 附件元数据 upsert → 游标镜像 → seen_accounts 反算。
 *
 * <p>结果码（§4.2）：全部成功 → 0；部分失败 → 10700（data.results 只含成功条目，error[] 列失败字段）；
 * 整批无一条合法 → 10100（data=null）。校验分级：account 缺失 10101（Controller 先于绑定校验）、
 * 元素必填/格式 10102、整批 chats 空 10100——三者响应均带 {@code error[]}（field + value，§2.1）。
 *
 * <p>并发取舍（SYNCH_PLAN §8）：多客户端同时上报靠 dedup_key UNIQUE 与来源行四元组 uk 兜住，不做分布式锁——
 * 「先查后写」窗口内两客户端同撞一条 fb→srv 合并时，后到者在 UPDATE 命中唯一索引抛 DuplicateKeyException，
 * 该 chat 整体回滚进 error[]，客户端幂等重放自愈（fb 键已被改写，重放走 srv 命中 unchanged）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessagesIngestService {

    /** sender_source 三值闭集（无 UNKNOWN 兜底，越界 10102）；附件 kind/tier/status 未识别宽松归 UNKNOWN/PENDING */
    private static final Set<String> SENDER_SOURCES = Set.of("SHARD_NAME2ID", "BODY_PREFIX", "CARD_XML");
    private static final Set<String> KINDS = Set.of("IMAGE", "VOICE", "VIDEO", "FILE");
    private static final Set<String> TIERS = Set.of("ORIGINAL", "FULL", "MID", "THUMB");
    private static final Set<String> STATUSES = Set.of("PENDING", "OK", "MID_ONLY", "ONLY_THUMBNAIL", "NO_LOCAL_COPY", "FAILED");
    private static final String UNKNOWN = "UNKNOWN";
    private static final String STATUS_PENDING = "PENDING";
    private static final String GROUP_SUFFIX = "@chatroom";
    private static final String KEY_SRV = "srv:";
    private static final String KEY_FB = "fb:";
    private static final String ACTION_INSERTED = "inserted";
    private static final String ACTION_UNCHANGED = "unchanged";
    private static final String ACTION_MERGED = "merged";
    private static final String MERGE_ACTION = "MERGED";
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    /** IN 列表分块大小（seen_accounts 反算 / blob 存在性批量查询） */
    private static final int CHUNK = 500;
    private static final int ERROR_SUMMARY_MAX = 800;

    private final WxMessageMapper messageMapper;
    private final WxMessageSourceRelMapper sourceRelMapper;
    private final WxMessageAttachmentMapper attachmentMapper;
    private final WxSyncCursorMapper cursorMapper;
    private final WxIngestBatchLogMapper batchLogMapper;
    private final WxMergeLogMapper mergeLogMapper;
    private final WxBlobMapper blobMapper;
    /** 单例 TransactionTemplate（Spring Boot 对唯一 PlatformTransactionManager 自动装配） */
    private final TransactionTemplate transactionTemplate;

    /**
     * 订阅触发钩子（T10-3，SUBSCRIPTION_DESIGN §3.1；可选依赖）。<b>字段注入而非构造器注入</b>：
     * 本类的构造器（Lombok {@code @RequiredArgsConstructor}）自 T4 起被既有单测直接 new——
     * 加参数即破坏既有测试与调用点；订阅域是增强能力而非 ingest 的必要依赖，缺省 null 零行为
     * （守卫见 {@link #triggerSubscriptions}）。
     */
    @Autowired(required = false)
    private SubscriptionTrigger subscriptionTrigger;

    /**
     * 入口：绑定校验在 Controller 已做完（10300/10601），这里只做参数校验 + 逐 chat 事务落库 + 批次日志。
     * 返回错误清单与 data，由 Controller 决定信封（0 / 10700 / 10100）。
     */
    public IngestOutcome ingest(MessagesReportRequest request) {
        String account = requireAccount(request.account());
        List<ChatItem> chats = requireChats(request.chats());
        long startedAt = System.currentTimeMillis();
        Batch batch = new Batch();
        for (int ci = 0; ci < chats.size(); ci++) {
            ChatItem chat = chats.get(ci);
            String chatUsername = trimToNull(chat.chatUsername());
            if (chatUsername == null) {
                batch.errors.add(ApiError.of("chats[" + ci + "].chat_username", "PARAM_MISSING",
                        "chat_username 必填且非空串", chat.chatUsername()));
                continue;
            }
            List<MessageItem> items = chat.messages() == null ? List.of() : chat.messages();
            List<ValidMessage> valid = new ArrayList<>(items.size());
            for (int mi = 0; mi < items.size(); mi++) {
                ValidMessage vm = validate("chats[" + ci + "].messages[" + mi + "]", items.get(mi),
                        chatUsername, batch.errors);
                if (vm != null) {
                    valid.add(vm);
                }
            }
            batch.nMessages += valid.size();
            if (valid.isEmpty()) {
                continue;   // 该 chat 无一条合法：不进库、不动游标，失败明细已在 batch.errors
            }
            try {
                merge(batch, transactionTemplate.execute(tx -> applyChat(account, chatUsername, chat.cursor(), valid)));
            } catch (RuntimeException e) {
                log.warn("[Messages] chat={} 落库失败，整 chat 回滚: {}", chatUsername, rootMessage(e));
                for (ValidMessage vm : valid) {
                    batch.errors.add(ApiError.of(vm.field() + ".local_id", "DB_ERROR",
                            "会话批落库失败: " + rootMessage(e), vm.localId()));
                }
            }
        }
        String status = batch.results.isEmpty() ? "FAILED" : (batch.errors.isEmpty() ? "OK" : "PARTIAL");
        String batchId = writeBatchLog(account, request.batchRef(), startedAt, batch, status);
        log.info("[Messages] ingest account={} batch={} chats={} nMessages={} inserted={} merged={} sources={} "
                        + "errors={} status={}", account, batchId, chats.size(), batch.nMessages, batch.nInserted,
                batch.nMerged, batch.nSources, batch.errors.size(), status);
        return new IngestOutcome(batch.errors,
                new MessagesIngestVO(batchId, batch.results, resolveBlobsNeeded(batch.blobCandidates)));
    }

    // ==================== chat 事务体 ====================

    /** 单 chat 批五步（同事务）：消息 → 来源行 → 附件 → 游标 → seen_accounts 反算（+订阅触发钩子收尾）。 */
    private ChatOutcome applyChat(String account, String chatUsername, CursorItem cursor, List<ValidMessage> messages) {
        ChatOutcome outcome = new ChatOutcome();
        for (ValidMessage vm : messages) {
            long messageId = upsertMessage(account, chatUsername, vm, outcome);
            upsertSourceRel(account, chatUsername, vm, messageId, outcome);
            upsertAttachments(messageId, vm, outcome);
        }
        mirrorCursor(account, chatUsername, cursor);
        recomputeSeenAccounts(outcome.touchedIds);
        triggerSubscriptions(account, outcome.inserted);
        return outcome;
    }

    /**
     * 订阅触发钩子（T10-3，SUBSCRIPTION_DESIGN §3.1）：<b>同一 chat 事务内</b>对「本批真正 INSERT
     * 的新消息」匹配入队（merged/unchanged 不触发——旧消息早已投递过），投递行与消息原子提交
     * （零丢失窗口；客户端幂等重放经 dedup_key 去重不会二次触发）。失败口径（v1.1 定稿）：
     * trigger 内部自吞（log.warn）不回滚消息事务，缺口由 SubscriptionBackstopService 补扫兜底。
     */
    private void triggerSubscriptions(String account, List<SubscriptionMatcher.IncomingMessage> inserted) {
        if (subscriptionTrigger == null || inserted.isEmpty()) {
            return;
        }
        subscriptionTrigger.trigger(account, inserted);
    }

    /**
     * 消息 upsert（三级查找，键先查后插）：
     * <pre>
     * existing = selectByDedupKey(入参键)                       // ① 主查找
     * if (existing == null &amp;&amp; 入参键是 srv:)                    // fb→srv 补键的两级兜底
     *     existing = selectByDedupKey(fb 键)                    // ② 兜底一：fb 兜底键
     *     if (命中 fb 行) return mergeFbIntoSrv(...)
     *     existing = selectBySourceCoord(account, chat, shard, local_id)   // ③ 兜底二：源侧四元组
     *     if (命中 fb 行) return mergeFbIntoSrv(...)
     * if (existing == null) INSERT，action=inserted              // ④ 落空才插新行
     * else 覆盖观测字段，action=unchanged
     * </pre>
     *
     * <p>为什么要兜底（SYNCH_PLAN §4.3 实测）：自己发的消息 <strong>server_id 落库瞬间为 0、≈160ms 后被
     * 原地改写为非 0</strong>（local_id / sort_seq 不变）——上一轮只能按 fb: 键落库，本轮带着 srv: 键回来。
     * 只按入参键查会查无 → INSERT → <strong>同一条消息裂成两行</strong>，且 fb 行永远没人改键、
     * API_DESIGN §4.2 的 action=merged 永远不出现。两级兜底覆盖两种真实形态：
     * ② fb 键可重算（sender 观测一致）→ 直接按键命中；③ fb 键不可重算（两次上报 sender 一个缺失一个
     * 解析出来了，拼出来的键必然不同）→ 用 {@code wx_message_source_rel} 的 (account, chat_username,
     * shard, local_id) 四元组定位「同一源侧键 ↔ 同一行」的权威映射；命中的行若已是 srv: 形态（srv 键
     * 本身不同，如 BIGINT 溢出走原始串），视为同一行的重复上报，走 ④ 的观测覆盖。
     *
     * <p>结果码：INSERT → inserted；fb→srv 补键合并 → merged；其余命中 → 覆盖观测字段
     * （is_revoked / body，保留 created_at / id），action=unchanged（契约只有 inserted|unchanged|merged
     * 三值，撤回传播不单列，客户端可经 /v1/messages/{id} 读到）。
     */
    private long upsertMessage(String account, String chatUsername, ValidMessage vm, ChatOutcome outcome) {
        WxMessageEntity existing = selectByDedupKey(vm.dedupKey());
        if (existing == null && vm.dedupKey().startsWith(KEY_SRV)) {
            existing = selectByDedupKey(fbKey(chatUsername, vm.senderUsername(),
                    vm.createTimeRaw(), vm.contentSha256()));                        // 兜底一
            if (existing != null && existing.getDedupKey().startsWith(KEY_FB)) {
                return mergeFbIntoSrv(existing, vm, outcome);
            }
            existing = selectBySourceCoord(account, chatUsername, vm);               // 兜底二
            if (existing != null && existing.getDedupKey().startsWith(KEY_FB)) {
                return mergeFbIntoSrv(existing, vm, outcome);
            }
            // 命中但已是 srv: 形态 → existing 留给下方按「键命中」处理（同一行重复上报）
        }
        if (existing == null) {
            WxMessageEntity row = buildRow(chatUsername, vm);
            messageMapper.insert(row);
            outcome.nInserted++;
            // 仅 inserted 进触发候选（§3.1：触发源只认本批真正 INSERT 的新消息）
            outcome.inserted.add(new SubscriptionMatcher.IncomingMessage(row.getId(), chatUsername,
                    vm.senderUsername(), vm.typeCode(), vm.body(), vm.isRevoked()));
            return recordOutcome(outcome, vm, row.getId(), ACTION_INSERTED);
        }
        applyObservation(existing.getId(), existing.getIsRevoked(), existing.getRevokedAt(), vm);
        return recordOutcome(outcome, vm, existing.getId(), ACTION_UNCHANGED);
    }

    /** 按 dedup_key 查单行（① 主查找与 ② 兜底一共用；LIMIT 1 兜住脏数据的重复键）。 */
    private WxMessageEntity selectByDedupKey(String dedupKey) {
        return messageMapper.selectOne(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getDedupKey, dedupKey)
                .last("LIMIT 1"));
    }

    /**
     * fb 兜底键拼装（SYNCH_PLAN §4.1）：{@code fb:&lt;chat&gt;|&lt;sender&gt;|&lt;create_time_raw&gt;|&lt;sha256&gt;}，
     * sender 缺失空串占位——「未解析出发送者」是合法观测，键必须稳定可重算。
     * validate 首次落库与 upsertMessage 兜底一共用这一份实现，避免两处拼装漂移导致兜底永远查不中。
     */
    private String fbKey(String chatUsername, String senderUsername, Long createTimeRaw, String contentSha256) {
        return KEY_FB + chatUsername + "|" + (senderUsername == null ? "" : senderUsername)
                + "|" + createTimeRaw + "|" + contentSha256;
    }

    /**
     * 兜底二（SYNCH_PLAN §6.4）：按源侧四元组反查消息行——{@code wx_message_source_rel} 是
     * 「同一源侧键 ↔ 同一行」的权威映射，fb 键漂移（sender 观测前后不一致）时靠它兜住。
     */
    private WxMessageEntity selectBySourceCoord(String account, String chatUsername, ValidMessage vm) {
        WxMessageSourceRelEntity rel = sourceRelMapper.selectOne(
                new LambdaQueryWrapper<WxMessageSourceRelEntity>()
                        .eq(WxMessageSourceRelEntity::getAccount, account)
                        .eq(WxMessageSourceRelEntity::getChatUsername, chatUsername)
                        .eq(WxMessageSourceRelEntity::getShard, vm.shard())
                        .eq(WxMessageSourceRelEntity::getLocalId, vm.localId())
                        .last("LIMIT 1"));
        return rel == null ? null : messageMapper.selectById(rel.getMessageId());
    }

    /** 记一条逐条结果 + 收集 message_id（seen_accounts 反算集合），返回 message_id 供来源行/附件续写。 */
    private long recordOutcome(ChatOutcome outcome, ValidMessage vm, long messageId, String action) {
        outcome.results.add(new MessagesIngestVO.MessageResult(vm.shard(), vm.localId(), vm.dedupKey(),
                String.valueOf(messageId), action));
        outcome.touchedIds.add(messageId);
        return messageId;
    }

    /**
     * fb→srv 补键合并（SYNCH_PLAN §6.4 / §4.3「原地改写」实测姿势）。
     * 唯一入口是 {@link #upsertMessage} 的两级兜底（入参 srv: 键 ① 主查找落空后，兜底一 fb 键 /
     * 兜底二源侧四元组命中的 fb: 行）——即「同一源侧键的 server_id 从 0 变非 0」这一实测场景：
     * ① srv 新键无主 → 原 fb 行同一条 UPDATE 改键 + 写 server_id（ck_wx_message_srv_dedup 同步满足），
     *    merge_log 记 MERGED；
     * ② srv 行已存在（另一客户端先到）→ 保留先到行：本 fb 行的来源行原地改指过去、删除本行、
     *    撤回状态传播到保留行、merge_log 记 MERGED；本消息 action=merged、message_id=保留行。
     */
    private long mergeFbIntoSrv(WxMessageEntity existing, ValidMessage vm, ChatOutcome outcome) {
        WxMessageEntity srvRow = messageMapper.selectOne(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getDedupKey, vm.dedupKey())
                .last("LIMIT 1"));
        if (srvRow == null || srvRow.getId().equals(existing.getId())) {
            applyObservation(existing.getId(), existing.getIsRevoked(), existing.getRevokedAt(), vm);
            messageMapper.update(null, new LambdaUpdateWrapper<WxMessageEntity>()
                    .eq(WxMessageEntity::getId, existing.getId())
                    .set(WxMessageEntity::getDedupKey, vm.dedupKey())
                    .set(WxMessageEntity::getServerId, vm.serverId())
                    .set(WxMessageEntity::getServerIdRaw, vm.serverIdRaw()));
            insertMergeLog(vm.dedupKey(), existing.getId(),
                    "fb→srv 原地改键 kept_id=" + existing.getId() + " server_id=" + vm.serverId());
            outcome.nMerged++;
            return recordOutcome(outcome, vm, existing.getId(), ACTION_MERGED);
        }
        // srv 行已存在：保留先到行，本 fb 行的来源行 / 附件改指过去后删除本行（先到者赢，宁合并不报错）
        sourceRelMapper.update(null, new LambdaUpdateWrapper<WxMessageSourceRelEntity>()
                .eq(WxMessageSourceRelEntity::getMessageId, existing.getId())
                .set(WxMessageSourceRelEntity::getMessageId, srvRow.getId()));
        attachmentMapper.repointToKept(existing.getId(), srvRow.getId());
        applyObservation(srvRow.getId(), srvRow.getIsRevoked(), srvRow.getRevokedAt(), vm);
        messageMapper.deleteById(existing.getId());
        insertMergeLog(vm.dedupKey(), srvRow.getId(),
                "srv 冲突保留先到行 kept_id=" + srvRow.getId() + " 删除 fb 行 id=" + existing.getId());
        outcome.nMerged++;
        return recordOutcome(outcome, vm, srvRow.getId(), ACTION_MERGED);
    }

    /** 覆盖观测字段：is_revoked 以最新观测为准、body 非 null 才覆盖（不抹已有正文）、last_seen_at 必刷新；created_at 不碰。 */
    private void applyObservation(long messageId, Boolean currentRevoked, OffsetDateTime currentRevokedAt, ValidMessage vm) {
        boolean revokedNow = vm.isRevoked() && !Boolean.TRUE.equals(currentRevoked);
        LambdaUpdateWrapper<WxMessageEntity> wrapper = new LambdaUpdateWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getId, messageId)
                .set(WxMessageEntity::getIsRevoked, vm.isRevoked())
                .set(WxMessageEntity::getRevokedAt, revokedNow ? OffsetDateTime.now() : currentRevokedAt)
                .set(WxMessageEntity::getLastSeenAt, OffsetDateTime.now())
                .set(WxMessageEntity::getUpdatedAt, OffsetDateTime.now());
        if (vm.body() != null) {
            wrapper.set(WxMessageEntity::getBody, vm.body());
        }
        messageMapper.update(null, wrapper);
    }

    private WxMessageEntity buildRow(String chatUsername, ValidMessage vm) {
        WxMessageEntity row = new WxMessageEntity();
        row.setDedupKey(vm.dedupKey());
        row.setChatUsername(chatUsername);
        row.setIsGroup(chatUsername.endsWith(GROUP_SUFFIX));
        row.setServerId(vm.serverId());
        row.setServerIdRaw(vm.serverIdRaw());
        row.setSenderUsername(vm.senderUsername());
        row.setSenderSource(vm.senderSource());
        row.setTypeCode(vm.typeCode());
        row.setTypeName(vm.typeName());
        row.setSortSeq(vm.sortSeq());
        row.setCreateTime(vm.createTime());
        row.setCreateTimeRaw(vm.createTimeRaw());
        row.setBody(vm.body());
        row.setContentRaw(vm.contentRaw());
        row.setContentSha256(vm.contentSha256());
        row.setIsRevoked(vm.isRevoked());
        row.setRevokedAt(vm.isRevoked() ? OffsetDateTime.now() : null);
        row.setSeenAccounts(1);
        return row;
    }

    /** 来源行 upsert：四元组 uk 命中即跳过（幂等），仅 message_id 不一致（合并改指）时原地改指。 */
    private void upsertSourceRel(String account, String chatUsername, ValidMessage vm, long messageId, ChatOutcome outcome) {
        WxMessageSourceRelEntity existing = sourceRelMapper.selectOne(new LambdaQueryWrapper<WxMessageSourceRelEntity>()
                .eq(WxMessageSourceRelEntity::getAccount, account)
                .eq(WxMessageSourceRelEntity::getChatUsername, chatUsername)
                .eq(WxMessageSourceRelEntity::getShard, vm.shard())
                .eq(WxMessageSourceRelEntity::getLocalId, vm.localId())
                .last("LIMIT 1"));
        if (existing == null) {
            WxMessageSourceRelEntity rel = new WxMessageSourceRelEntity();
            rel.setAccount(account);
            rel.setChatUsername(chatUsername);
            rel.setShard(vm.shard());
            rel.setLocalId(vm.localId());
            rel.setMessageId(messageId);
            rel.setSeenAt(OffsetDateTime.now());
            sourceRelMapper.insert(rel);
            outcome.nSources++;
        } else if (!Long.valueOf(messageId).equals(existing.getMessageId())) {
            sourceRelMapper.update(null, new LambdaUpdateWrapper<WxMessageSourceRelEntity>()
                    .eq(WxMessageSourceRelEntity::getId, existing.getId())
                    .set(WxMessageSourceRelEntity::getMessageId, messageId)
                    .set(WxMessageSourceRelEntity::getSeenAt, OffsetDateTime.now()));
        }
    }

    /** 附件元数据 upsert：uk (message_id, kind, tier, sha256)，命中覆盖元数据（blob_sha256 留给 T5 回填）。 */
    private void upsertAttachments(long messageId, ValidMessage vm, ChatOutcome outcome) {
        for (Att att : vm.attachments()) {
            WxMessageAttachmentEntity existing = attachmentMapper.selectOne(
                    new LambdaQueryWrapper<WxMessageAttachmentEntity>()
                            .eq(WxMessageAttachmentEntity::getMessageId, messageId)
                            .eq(WxMessageAttachmentEntity::getKind, att.kind())
                            .eq(WxMessageAttachmentEntity::getTier, att.tier())
                            .eq(WxMessageAttachmentEntity::getSha256, att.sha256())
                            .last("LIMIT 1"));
            if (existing == null) {
                WxMessageAttachmentEntity row = new WxMessageAttachmentEntity();
                row.setMessageId(messageId);
                row.setKind(att.kind());
                row.setTier(att.tier());
                row.setFilename(att.filename());
                row.setExt(att.ext());
                row.setSize(att.size());
                row.setMd5(att.md5());
                row.setSha256(att.sha256());
                row.setLocalSource(att.localSource());
                row.setStatus(att.status());
                attachmentMapper.insert(row);
            } else {
                attachmentMapper.update(null, new LambdaUpdateWrapper<WxMessageAttachmentEntity>()
                        .eq(WxMessageAttachmentEntity::getId, existing.getId())
                        .set(WxMessageAttachmentEntity::getFilename, att.filename())
                        .set(WxMessageAttachmentEntity::getExt, att.ext())
                        .set(WxMessageAttachmentEntity::getSize, att.size())
                        .set(WxMessageAttachmentEntity::getMd5, att.md5())
                        .set(WxMessageAttachmentEntity::getLocalSource, att.localSource())
                        .set(WxMessageAttachmentEntity::getStatus, att.status())
                        .set(WxMessageAttachmentEntity::getUpdatedAt, OffsetDateTime.now()));
            }
            if (SHA256_PATTERN.matcher(att.sha256()).matches()) {
                outcome.blobCandidates.putIfAbsent(att.sha256(), new MessagesIngestVO.BlobNeeded(att.sha256(),
                        att.size() == null ? 0L : att.size(),
                        messageId + ":" + att.kind().toLowerCase(Locale.ROOT) + ":" + att.tier().toLowerCase(Locale.ROOT)));
            }
        }
    }

    /** 游标镜像 upsert：uk (account, chat_username, shard)；缺 shard / 缺 cursor 不镜像（观测表，不阻断）。 */
    private void mirrorCursor(String account, String chatUsername, CursorItem cursor) {
        String shard = cursor == null ? null : trimToNull(cursor.shard());
        if (shard == null) {
            log.debug("[Messages] cursor 缺 shard，跳过镜像 chat={}", chatUsername);
            return;
        }
        long maxLocalId = cursor.maxLocalId() == null ? 0L : cursor.maxLocalId();
        long maxSortSeq = cursor.maxSortSeq() == null ? 0L : cursor.maxSortSeq();
        OffsetDateTime now = OffsetDateTime.now();
        WxSyncCursorEntity existing = cursorMapper.selectOne(new LambdaQueryWrapper<WxSyncCursorEntity>()
                .eq(WxSyncCursorEntity::getAccount, account)
                .eq(WxSyncCursorEntity::getChatUsername, chatUsername)
                .eq(WxSyncCursorEntity::getShard, shard)
                .last("LIMIT 1"));
        if (existing == null) {
            WxSyncCursorEntity row = new WxSyncCursorEntity();
            row.setAccount(account);
            row.setChatUsername(chatUsername);
            row.setShard(shard);
            row.setMaxLocalId(maxLocalId);
            row.setMaxSortSeq(maxSortSeq);
            row.setLastSyncAt(now);
            cursorMapper.insert(row);
            return;
        }
        // 镜像只前进不后退（乱序/并发上报取较大值），last_sync_at 每批刷新供 /v1/status 观测
        cursorMapper.update(null, new LambdaUpdateWrapper<WxSyncCursorEntity>()
                .eq(WxSyncCursorEntity::getId, existing.getId())
                .set(WxSyncCursorEntity::getMaxLocalId,
                        Math.max(maxLocalId, existing.getMaxLocalId() == null ? 0L : existing.getMaxLocalId()))
                .set(WxSyncCursorEntity::getMaxSortSeq,
                        Math.max(maxSortSeq, existing.getMaxSortSeq() == null ? 0L : existing.getMaxSortSeq()))
                .set(WxSyncCursorEntity::getLastSyncAt, now));
    }

    /** seen_accounts 反算：批内 id 分块 UPDATE … FROM (SELECT count(DISTINCT account) GROUP BY message_id)。 */
    private void recomputeSeenAccounts(Set<Long> messageIds) {
        List<Long> ids = new ArrayList<>(messageIds);
        for (int i = 0; i < ids.size(); i += CHUNK) {
            messageMapper.recomputeSeenAccounts(ids.subList(i, Math.min(ids.size(), i + CHUNK)));
        }
    }

    // ==================== 批次日志与 blobs_needed ====================

    /** 批次日志独立提交（不在任何事务内）：整批全失败也留 FAILED 一行供排障。 */
    private String writeBatchLog(String account, String batchRef, long startedAt, Batch batch, String status) {
        WxIngestBatchLogEntity row = new WxIngestBatchLogEntity();
        row.setAccount(account);
        row.setBatchRef(trimToNull(batchRef));
        row.setStartedAt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(startedAt), ZoneOffset.UTC));
        row.setDurationMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - startedAt));
        row.setNMessages(batch.nMessages);
        row.setNSources(batch.nSources);
        row.setNInserted(batch.nInserted);
        row.setNMerged(batch.nMerged);
        row.setStatus(status);
        row.setError(summarizeErrors(batch.errors));
        batchLogMapper.insert(row);
        return String.valueOf(row.getId());
    }

    /** blobs_needed = 批内附件非空 sha256 减去 wx_blob 已有（is_deleted=0），按 sha 去重、首个 attachment_key 取胜。 */
    private List<MessagesIngestVO.BlobNeeded> resolveBlobsNeeded(LinkedHashMap<String, MessagesIngestVO.BlobNeeded> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> existing = new HashSet<>();
        List<String> shas = new ArrayList<>(candidates.keySet());
        for (int i = 0; i < shas.size(); i += CHUNK) {
            List<String> chunk = shas.subList(i, Math.min(shas.size(), i + CHUNK));
            for (WxBlobEntity blob : blobMapper.selectList(new LambdaQueryWrapper<WxBlobEntity>()
                    .select(WxBlobEntity::getSha256)
                    .eq(WxBlobEntity::getIsDeleted, 0)
                    .in(WxBlobEntity::getSha256, chunk))) {
                existing.add(blob.getSha256());
            }
        }
        List<MessagesIngestVO.BlobNeeded> needed = new ArrayList<>();
        for (String sha : shas) {
            if (!existing.contains(sha)) {
                needed.add(candidates.get(sha));
            }
        }
        return needed;
    }

    // ==================== 校验与归一化 ====================

    /**
     * 元素级校验（10102）：shard / local_id / content_sha256 / create_time_raw 必填且格式合法，
     * sender_source 三值闭集越界即拒；kind / tier / status 宽松归一化（未识别归 UNKNOWN / PENDING）。
     * 失败条目记入 error[]（chats[i].messages[j].&lt;field&gt;），不进库。
     */
    private ValidMessage validate(String field, MessageItem m, String chatUsername, List<ApiError> errors) {
        String shard = trimToNull(m.shard());
        if (shard == null) {
            return invalid(errors, field + ".shard", "shard 必填且非空串", m.shard());
        }
        if (m.localId() == null || m.localId() < 0) {
            return invalid(errors, field + ".local_id", "local_id 必填且为非负整数", m.localId());
        }
        String sha = trimToNull(m.contentSha256());
        if (sha == null) {
            return invalid(errors, field + ".content_sha256", "content_sha256 必填", m.contentSha256());
        }
        if (!SHA256_PATTERN.matcher(sha).matches()) {
            return invalid(errors, field + ".content_sha256", "content_sha256 必须为 64 位十六进制", sha);
        }
        String shaLower = sha.toLowerCase(Locale.ROOT);
        if (m.createTimeRaw() == null || m.createTimeRaw() < 0) {
            return invalid(errors, field + ".create_time_raw",
                    "create_time_raw 必填且为非负整数（Unix 秒）", m.createTimeRaw());
        }
        String senderSource = null;
        String rawSource = trimToNull(m.senderSource());
        if (rawSource != null) {
            String upper = rawSource.toUpperCase(Locale.ROOT);
            if (!SENDER_SOURCES.contains(upper)) {
                return invalid(errors, field + ".sender_source",
                        "sender_source 只允许 shard_name2id | body_prefix | card_xml（收小写）", rawSource);
            }
            senderSource = upper;
        }
        ServerIdentity server = resolveServerId(m);
        String sender = trimToNull(m.senderUsername());
        String dedupKey = server.dedupKey() != null ? server.dedupKey()
                : fbKey(chatUsername, sender, m.createTimeRaw(), shaLower);
        return new ValidMessage(field, shard, m.localId(), dedupKey, server.serverId(), server.serverIdRaw(),
                m.sortSeq(), OffsetDateTime.ofInstant(Instant.ofEpochSecond(m.createTimeRaw()), ZoneOffset.UTC),
                m.createTimeRaw(), trimToNull(m.typeCode()), trimToNull(m.typeName()), sender, senderSource,
                m.body(), m.contentRaw(), shaLower, Boolean.TRUE.equals(m.isRevoked()),
                normalizeAttachments(m.attachments()));
    }

    /**
     * server_id 归一化（API_DESIGN §2.2 / SYNCH_PLAN §4.1）：有效（非 null 非 "0" 非 blank 且解析后 &gt;0）
     * → srv:&lt;id&gt;；{@link Long#parseLong} 失败（BIGINT 溢出，实测 7.7×10¹⁸）→ server_id=NULL +
     * server_id_raw=&lt;串&gt; + srv:&lt;串&gt;（ck_wx_message_srv_dedup 的 NULL 分支放行）；0 / 负数 → 兜底键 fb:。
     */
    private ServerIdentity resolveServerId(MessageItem m) {
        String raw = trimToNull(m.serverId());
        if (raw == null) {
            raw = trimToNull(m.serverIdRaw());
        }
        if (raw == null) {
            return new ServerIdentity(null, null, null);
        }
        try {
            long parsed = Long.parseLong(raw);
            return parsed > 0 ? new ServerIdentity(parsed, null, KEY_SRV + parsed) : new ServerIdentity(null, null, null);
        } catch (NumberFormatException e) {
            return new ServerIdentity(null, raw, KEY_SRV + raw);
        }
    }

    /** 附件归一化（宽松，避免单条枚举污染整批）：kind/tier 未识别归 UNKNOWN，status 归 PENDING；sha256 缺省空串。 */
    private List<Att> normalizeAttachments(List<AttachmentItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Att> out = new ArrayList<>(items.size());
        for (AttachmentItem item : items) {
            String sha = trimToNull(item.sha256());
            out.add(new Att(normalizeLoose(item.kind(), KINDS, UNKNOWN),
                    normalizeLoose(item.tier(), TIERS, UNKNOWN),
                    trimToNull(item.filename()), trimToNull(item.ext()), item.size(), trimToNull(item.md5()),
                    sha == null ? "" : sha.toLowerCase(Locale.ROOT), trimToNull(item.localSource()),
                    normalizeLoose(item.status(), STATUSES, STATUS_PENDING)));
        }
        return out;
    }

    /** 宽松归一化：null/blank → fallback，大小写归一，未识别 → fallback（kind/tier 有 UNKNOWN，status 有 PENDING）。 */
    private String normalizeLoose(String value, Set<String> allowed, String fallback) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return fallback;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : fallback;
    }

    /** 10102：元素级必填 / 格式错误记入 error[]（部分成功语义，不中断整批），返回 null = 该条不进库。 */
    private ValidMessage invalid(List<ApiError> errors, String field, String message, Object rejected) {
        errors.add(ApiError.of(field, "FORMAT_INVALID", message, rejected));
        return null;
    }

    // ==================== 参数级校验（10100/10101） ====================

    /**
     * 参数校验类抛错的统一出口：field / rejectedValue 装进 {@code errorDetail}（{@code List<ApiError>}），
     * GlobalExceptionHandler 翻成 {@code error[]}（API_DESIGN §2.1：field/code/message/value）。
     * 框架 1.10.0 只有三参构造器可同时带诊断 message 与 errorDetail（标了 @Deprecated，但无替代签名）。
     */
    private static ApiException paramError(ApiCode apiCode, String errorCode, String field,
            String message, Object rejectedValue) {
        return new ApiException(apiCode, message, List.of(ApiError.of(field, errorCode, message, rejectedValue)));
    }

    /** 10101：顶层 account 缺失。public：Controller 须在绑定校验（10300）前先调（次序不能反）。 */
    public String requireAccount(String account) {
        String trimmed = trimToNull(account);
        if (trimmed == null) {
            throw paramError(ApiCode.PARAM_MISSING, "PARAM_MISSING", "account", "account 必填", null);
        }
        return trimmed;
    }

    /** chats 缺失 → 10101；空数组 → 10100（整批无一条合法消息，data=null）。 */
    private List<ChatItem> requireChats(List<ChatItem> chats) {
        if (chats == null) {
            throw paramError(ApiCode.PARAM_MISSING, "PARAM_MISSING", "chats", "chats 必填", null);
        }
        if (chats.isEmpty()) {
            throw paramError(ApiCode.PARAM_ERROR, "FORMAT_INVALID", "chats",
                    "chats 不能为空：整批无一条合法消息", null);
        }
        return chats;
    }

    // ==================== 辅助 ====================

    private void merge(Batch batch, ChatOutcome outcome) {
        if (outcome == null) {
            return;
        }
        batch.results.addAll(outcome.results);
        batch.blobCandidates.putAll(outcome.blobCandidates);
        batch.nSources += outcome.nSources;
        batch.nInserted += outcome.nInserted;
        batch.nMerged += outcome.nMerged;
    }

    private void insertMergeLog(String dedupKey, Long keptId, String detail) {
        WxMergeLogEntity row = new WxMergeLogEntity();
        row.setDedupKey(dedupKey);
        row.setKeptId(keptId);
        row.setAction(MERGE_ACTION);
        row.setDetail(detail);
        mergeLogMapper.insert(row);
    }

    private String summarizeErrors(List<ApiError> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size() && sb.length() < ERROR_SUMMARY_MAX; i++) {
            ApiError e = errors.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(e.field()).append(": ").append(e.message());
        }
        if (sb.length() > ERROR_SUMMARY_MAX) {
            sb.setLength(ERROR_SUMMARY_MAX);
            sb.append("…");
        }
        if (errors.size() > 1) {
            sb.append("（共 ").append(errors.size()).append(" 条）");
        }
        return sb.toString();
    }

    /** 沿 cause 链取根因（DuplicateKeyException 的可读信息在深层）。 */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================== 内部类型 ====================

    /** ingest 产出：错误清单 + data（Controller 据此挑信封：errors 空 → 0，data.results 空 → 10100，否则 10700）。 */
    public record IngestOutcome(List<ApiError> errors, MessagesIngestVO data) {
    }

    /** 校验通过、待落库的消息（dedup_key 已算好，附件已归一化）。 */
    private record ValidMessage(String field, String shard, Long localId, String dedupKey, Long serverId,
                                String serverIdRaw, Long sortSeq, OffsetDateTime createTime, Long createTimeRaw,
                                String typeCode, String typeName, String senderUsername, String senderSource,
                                String body, String contentRaw, String contentSha256, boolean isRevoked,
                                List<Att> attachments) {
    }

    /** 归一化附件元数据（kind/tier/status 大写，sha256 缺省空串）。 */
    private record Att(String kind, String tier, String filename, String ext, Long size, String md5,
                       String sha256, String localSource, String status) {
    }

    /** server_id 归一化结果：dedupKey 为 null 表示走 fb: 兜底键。 */
    private record ServerIdentity(Long serverId, String serverIdRaw, String dedupKey) {
    }

    /** 单 chat 事务的产出（事务提交后才并入批次，回滚不留痕）。 */
    private static final class ChatOutcome {
        private final List<MessagesIngestVO.MessageResult> results = new ArrayList<>();
        private final LinkedHashMap<String, MessagesIngestVO.BlobNeeded> blobCandidates = new LinkedHashMap<>();
        private final Set<Long> touchedIds = new LinkedHashSet<>();
        /** 本批真正 INSERT 的新消息（触发钩子候选，§3.1；merged/unchanged 不进） */
        private final List<SubscriptionMatcher.IncomingMessage> inserted = new ArrayList<>();
        private int nSources;
        private int nInserted;
        private int nMerged;
    }

    /** 整批累计（事务外）。 */
    private static final class Batch {
        private final List<MessagesIngestVO.MessageResult> results = new ArrayList<>();
        private final List<ApiError> errors = new ArrayList<>();
        private final LinkedHashMap<String, MessagesIngestVO.BlobNeeded> blobCandidates = new LinkedHashMap<>();
        private int nMessages;
        private int nSources;
        private int nInserted;
        private int nMerged;
    }
}
