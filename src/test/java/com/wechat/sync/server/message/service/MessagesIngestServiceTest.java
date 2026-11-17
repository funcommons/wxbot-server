package com.wechat.sync.server.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wechat.sync.server.message.dto.MessagesIngestVO;
import com.wechat.sync.server.message.dto.MessagesReportRequest;
import com.wechat.sync.server.message.dto.MessagesReportRequest.ChatItem;
import com.wechat.sync.server.message.dto.MessagesReportRequest.MessageItem;
import com.wechat.sync.server.message.entity.WxBlobEntity;
import com.wechat.sync.server.message.entity.WxIngestBatchLogEntity;
import com.wechat.sync.server.message.entity.WxMergeLogEntity;
import com.wechat.sync.server.message.entity.WxMessageAttachmentEntity;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.entity.WxMessageSourceRelEntity;
import com.wechat.sync.server.message.entity.WxSyncCursorEntity;
import com.wechat.sync.server.message.mapper.WxBlobMapper;
import com.wechat.sync.server.message.mapper.WxIngestBatchLogMapper;
import com.wechat.sync.server.message.mapper.WxMergeLogMapper;
import com.wechat.sync.server.message.mapper.WxMessageAttachmentMapper;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import com.wechat.sync.server.message.mapper.WxMessageSourceRelMapper;
import com.wechat.sync.server.message.mapper.WxSyncCursorMapper;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 消息批 ingest（T4 去重核心）：dedup_key 三分支、fb→srv 合并两分支、五步事务体、信封取值。 */
@ExtendWith(MockitoExtension.class)
class MessagesIngestServiceTest {

    private static final String ACCOUNT = "wxid_t4unit_a";
    private static final String CHAT = "t4unit_room@chatroom";
    private static final String CHAT_PLAIN = "t4unit_friend";
    private static final OffsetDateTime REVOKED_AT = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    /** 大写入参归一化后的 64 位小写十六进制（附件明文哈希） */
    private static final String LOWER_SHA = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    /** wrapper.sqlSet 里 #{ew.paramNameValuePairs.MPGENVALn} 的取值定位 */
    private static final Pattern PARAM = Pattern.compile("=#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)}");

    @Mock private WxMessageMapper messageMapper;
    @Mock private WxMessageSourceRelMapper sourceRelMapper;
    @Mock private WxMessageAttachmentMapper attachmentMapper;
    @Mock private WxSyncCursorMapper cursorMapper;
    @Mock private WxIngestBatchLogMapper batchLogMapper;
    @Mock private WxMergeLogMapper mergeLogMapper;
    @Mock private WxBlobMapper blobMapper;
    @Mock private TransactionTemplate transactionTemplate;

    private MessagesIngestService service;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxMessageEntity.class, WxMessageSourceRelEntity.class, WxMessageAttachmentEntity.class,
                WxSyncCursorEntity.class, WxIngestBatchLogEntity.class, WxMergeLogEntity.class, WxBlobEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new MessagesIngestService(messageMapper, sourceRelMapper, attachmentMapper, cursorMapper,
                batchLogMapper, mergeLogMapper, blobMapper, transactionTemplate);
    }

    // ==================== 1~3 dedup_key 三分支 ====================

    @Test
    void validServerIdBuildsSrvKeyAndStoresId() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null);
        nextMessageId(101L);

        MessagesIngestService.IngestOutcome out = service.ingest(
                requestOf(chatOf(CHAT, msg("db/MSG0.db", 10, "12345", 1760000000L, sha(1)))));

        // F9 三级查找：主键(srv)落空后兜底一(fb 键)再查一次，两次 selectOne
        ArgumentCaptor<LambdaQueryWrapper<WxMessageEntity>> lookup = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper, times(2)).selectOne(lookup.capture());
        lookup.getAllValues().get(0).getSqlSegment();
        assertThat(lookup.getAllValues().get(0).getParamNameValuePairs()).containsValue("srv:12345");
        lookup.getAllValues().get(1).getSqlSegment();
        assertThat(lookup.getAllValues().get(1).getParamNameValuePairs())
                .containsValue("fb:" + CHAT + "||1760000000|" + sha(1));

        ArgumentCaptor<WxMessageEntity> insert = ArgumentCaptor.forClass(WxMessageEntity.class);
        verify(messageMapper).insert(insert.capture());
        WxMessageEntity row = insert.getValue();
        assertThat(row.getDedupKey()).isEqualTo("srv:12345");
        assertThat(row.getServerId()).isEqualTo(12345L);
        assertThat(row.getServerIdRaw()).isNull();
        assertThat(row.getChatUsername()).isEqualTo(CHAT);
        assertThat(row.getIsGroup()).isTrue();
        assertThat(row.getSeenAccounts()).isEqualTo(1);
        assertThat(row.getCreateTime()).isEqualTo(OffsetDateTime.parse("2025-10-09T08:53:20Z"));

        MessagesIngestVO.MessageResult result = out.data().results().get(0);
        assertThat(result.action()).isEqualTo("inserted");
        assertThat(result.messageId()).isEqualTo("101");
        assertThat(result.dedupKey()).isEqualTo("srv:12345");
        assertThat(out.errors()).isEmpty();
    }

    @Test
    void overflowServerIdKeepsRawStringAndNullId() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null);
        nextMessageId(102L);

        service.ingest(requestOf(chatOf(CHAT, msg("db/MSG0.db", 11, "99999999999999999999", 1760000001L, sha(2)))));

        ArgumentCaptor<WxMessageEntity> insert = ArgumentCaptor.forClass(WxMessageEntity.class);
        verify(messageMapper).insert(insert.capture());
        assertThat(insert.getValue().getServerId()).isNull();
        assertThat(insert.getValue().getServerIdRaw()).isEqualTo("99999999999999999999");
        assertThat(insert.getValue().getDedupKey()).isEqualTo("srv:99999999999999999999");
    }

    @Test
    void missingZeroOrNegativeServerIdFallsBackToCompositeKey() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null);
        AtomicLong fbSeq = new AtomicLong(110);
        given(messageMapper.insert(any(WxMessageEntity.class))).willAnswer(inv -> {
            inv.getArgument(0, WxMessageEntity.class).setId(fbSeq.incrementAndGet()); // recordOutcome 拆箱需要 id
            return 1;
        });

        service.ingest(requestOf(chatOf(CHAT,
                msg("db/MSG0.db", 12, null, 1760000002L, sha(3)),
                msg("db/MSG0.db", 13, "0", 1760000003L, sha(4)),
                msg("db/MSG0.db", 14, "  ", 1760000004L, sha(5)),
                msg("db/MSG0.db", 15, "-5", 1760000005L, sha(6)))));

        ArgumentCaptor<WxMessageEntity> insert = ArgumentCaptor.forClass(WxMessageEntity.class);
        verify(messageMapper, times(4)).insert(insert.capture());
        String fbPrefix = "fb:" + CHAT + "||";
        assertThat(insert.getAllValues()).extracting(WxMessageEntity::getServerId).containsOnly((Long) null);
        assertThat(insert.getAllValues()).extracting(WxMessageEntity::getServerIdRaw).containsOnly((String) null);
        assertThat(insert.getAllValues()).extracting(WxMessageEntity::getDedupKey).containsExactly(
                fbPrefix + "1760000002|" + sha(3), fbPrefix + "1760000003|" + sha(4),
                fbPrefix + "1760000004|" + sha(5), fbPrefix + "1760000005|" + sha(6));
        assertThat(insert.getAllValues()).extracting(WxMessageEntity::getSenderUsername).containsOnly((String) null);
    }

    // ==================== 4~5 键命中：观测字段覆盖 ====================

    @Test
    void sameKeyHitRefreshesObservationOnlyAndNeverTouchesCreatedAt() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(row(300, "srv:9001", true));

        MessagesIngestService.IngestOutcome out = service.ingest(
                requestOf(chatOf(CHAT, msg("db/MSG0.db", 10, "9001", 1760000000L, sha(1), null, Boolean.TRUE))));

        verify(messageMapper, never()).insert(any(WxMessageEntity.class));
        ArgumentCaptor<LambdaUpdateWrapper<WxMessageEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messageMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet())
                .contains("is_revoked=", "revoked_at=", "last_seen_at=", "updated_at=")
                .doesNotContain("body=", "dedup_key=", "created_at=");
        assertThat(setParam(update.getValue(), "is_revoked")).isEqualTo(Boolean.TRUE);
        assertThat(setParam(update.getValue(), "revoked_at")).isEqualTo(REVOKED_AT);
        assertThat(out.data().results().get(0).action()).isEqualTo("unchanged");
        assertThat(out.data().results().get(0).messageId()).isEqualTo("300");
        verify(messageMapper).recomputeSeenAccounts(List.of(300L));
    }

    @Test
    void sameKeyHitWithRevocationAndBodyOverwritesBothFields() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(row(301, "srv:9002", false));

        service.ingest(requestOf(chatOf(CHAT,
                msg("db/MSG0.db", 10, "9002", 1760000000L, sha(1), "新正文", Boolean.TRUE))));

        ArgumentCaptor<LambdaUpdateWrapper<WxMessageEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messageMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("body=", "revoked_at=").doesNotContain("created_at=");
        assertThat(setParam(update.getValue(), "body")).isEqualTo("新正文");
        assertThat(setParam(update.getValue(), "revoked_at")).isInstanceOf(OffsetDateTime.class);
    }

    // ==================== 6~7 fb→srv 合并两分支 ====================

    @Test
    void fbRowUpgradedToUnusedSrvKeyIsRewrittenInPlace() {
        runTxInline();
        // F9 查找顺序：①主键 srv:7001 → null；②兜底一 fb 键 → 命中 fb 行；③merge 内部再查 srv 行 → null
        given(messageMapper.selectOne(any())).willReturn(
                null, row(400, "fb:" + CHAT + "||1760000000|" + sha(1), false), null);

        MessagesIngestService.IngestOutcome out = service.ingest(
                requestOf(chatOf(CHAT, msg("db/MSG0.db", 10, "7001", 1760000000L, sha(1)))));

        ArgumentCaptor<LambdaUpdateWrapper<WxMessageEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messageMapper, times(2)).update(isNull(), update.capture());
        assertThat(update.getAllValues().get(0).getSqlSet()).doesNotContain("dedup_key=");
        LambdaUpdateWrapper<WxMessageEntity> rewrite = update.getAllValues().get(1);
        assertThat(rewrite.getSqlSet()).contains("dedup_key=", "server_id=", "server_id_raw=");
        assertThat(setParam(rewrite, "dedup_key")).isEqualTo("srv:7001");
        assertThat(setParam(rewrite, "server_id")).isEqualTo(7001L);
        rewrite.getSqlSegment(); // WHERE eq(id,400) 参数懒求值，渲染后才进 paramNameValuePairs
        assertThat(rewrite.getParamNameValuePairs()).containsValue(400L);

        ArgumentCaptor<WxMergeLogEntity> mergeLog = ArgumentCaptor.forClass(WxMergeLogEntity.class);
        verify(mergeLogMapper).insert(mergeLog.capture());
        assertThat(mergeLog.getValue().getDedupKey()).isEqualTo("srv:7001");
        assertThat(mergeLog.getValue().getKeptId()).isEqualTo(400L);
        assertThat(mergeLog.getValue().getAction()).isEqualTo("MERGED");

        verify(messageMapper, never()).deleteById(any(Long.class));
        assertThat(out.data().results().get(0).action()).isEqualTo("merged");
        assertThat(out.data().results().get(0).messageId()).isEqualTo("400");
        assertThat(out.data().results().get(0).dedupKey()).isEqualTo("srv:7001");
        verify(messageMapper).recomputeSeenAccounts(List.of(400L));
    }

    @Test
    void srvRowAlreadyKeptRepointsSourcesAndDeletesFbRow() {
        runTxInline();
        // F9 查找顺序：①srv:7002 → null；②fb 键 → fb 行(500)；③merge 查 srv 行 → 已存在(501) → repoint 分支
        given(messageMapper.selectOne(any())).willReturn(
                null, row(500, "fb:" + CHAT + "||1760000000|" + sha(1), false), row(501, "srv:7002", false));

        MessagesIngestService.IngestOutcome out = service.ingest(
                requestOf(chatOf(CHAT, msg("db/MSG0.db", 10, "7002", 1760000000L, sha(1)))));

        verify(attachmentMapper).repointToKept(500L, 501L);
        ArgumentCaptor<LambdaUpdateWrapper<WxMessageSourceRelEntity>> rel = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sourceRelMapper).update(isNull(), rel.capture());
        assertThat(setParam(rel.getValue(), "message_id")).isEqualTo(501L);
        // 合并改指分支设计上只改 message_id，不刷 seen_at（该行由 upsertSourceRel 另行刷新）
        assertThat(rel.getValue().getSqlSet()).contains("message_id=").doesNotContain("seen_at=");

        ArgumentCaptor<LambdaUpdateWrapper<WxMessageEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messageMapper, times(1)).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).doesNotContain("dedup_key=");
        update.getValue().getSqlSegment(); // WHERE eq(id,501) 参数懒求值，渲染后才进 paramNameValuePairs
        assertThat(update.getValue().getParamNameValuePairs()).containsValue(501L);
        verify(messageMapper).deleteById(500L);

        ArgumentCaptor<WxMergeLogEntity> mergeLog = ArgumentCaptor.forClass(WxMergeLogEntity.class);
        verify(mergeLogMapper).insert(mergeLog.capture());
        assertThat(mergeLog.getValue().getKeptId()).isEqualTo(501L);
        assertThat(out.data().results().get(0).action()).isEqualTo("merged");
        assertThat(out.data().results().get(0).messageId()).isEqualTo("501");
    }

    // ==================== 8 seen_accounts 反算 ====================

    @Test
    void seenAccountsIsRecomputedFromSourceRelAndNeverIncremented() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null, row(600, "srv:9003", false));
        AtomicLong seq = new AtomicLong(600);
        doAnswer(inv -> {
            inv.getArgument(0, WxMessageEntity.class).setId(seq.incrementAndGet());
            return 1;
        }).when(messageMapper).insert(any(WxMessageEntity.class));

        service.ingest(requestOf(chatOf(CHAT,
                msg("db/MSG0.db", 10, "9003", 1760000000L, sha(1)),
                msg("db/MSG0.db", 11, "9003", 1760000001L, sha(2)))));

        verify(messageMapper).recomputeSeenAccounts(List.of(601L, 600L));
        ArgumentCaptor<LambdaUpdateWrapper<WxMessageEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messageMapper, times(1)).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).doesNotContain("seen_accounts");
        verify(sourceRelMapper, times(2)).insert(any(WxMessageSourceRelEntity.class));
    }

    // ==================== 9~11 附件 / 游标 / blobs_needed ====================

    @Test
    void attachmentsUpsertNormalizesEnumsAndDefaultsEmptySha() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null);
        given(blobMapper.selectList(any())).willReturn(List.of());
        nextMessageId(700L);
        MessageItem item = msg("db/MSG0.db", 10, "9004", 1760000000L, sha(1));
        item = itemWith(item, List.of(
                new MessagesReportRequest.AttachmentItem("image", "thumb", "a.jpg", "jpg", 100L, "md5",
                        "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789", "/src/a.jpg", "ok"),
                new MessagesReportRequest.AttachmentItem(null, " ", "b.bin", "bin", null, null, null, null, "weird")));

        MessagesIngestService.IngestOutcome out = service.ingest(requestOf(chatOf(CHAT, item)));

        ArgumentCaptor<WxMessageAttachmentEntity> insert = ArgumentCaptor.forClass(WxMessageAttachmentEntity.class);
        verify(attachmentMapper, times(2)).insert(insert.capture());
        assertThat(insert.getAllValues()).extracting(WxMessageAttachmentEntity::getKind,
                        WxMessageAttachmentEntity::getTier)
                .containsExactly(tuple("IMAGE", "THUMB"), tuple("UNKNOWN", "UNKNOWN"));
        assertThat(insert.getAllValues()).extracting(WxMessageAttachmentEntity::getStatus,
                        WxMessageAttachmentEntity::getSha256)
                .containsExactly(tuple("OK", LOWER_SHA), tuple("PENDING", ""));

        assertThat(out.data().blobsNeeded()).hasSize(1).extracting(MessagesIngestVO.BlobNeeded::sha256,
                        MessagesIngestVO.BlobNeeded::attachmentKey, MessagesIngestVO.BlobNeeded::size)
                .containsExactly(tuple(LOWER_SHA, "700:image:thumb", 100L));
    }

    @Test
    void cursorMirrorInsertsThenAdvancesMonotonicallyPerShard() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null);
        AtomicLong cmSeq = new AtomicLong(810);
        given(messageMapper.insert(any(WxMessageEntity.class))).willAnswer(inv -> {
            inv.getArgument(0, WxMessageEntity.class).setId(cmSeq.incrementAndGet()); // recordOutcome 拆箱需要 id
            return 1;
        });
        WxSyncCursorEntity existing = new WxSyncCursorEntity();
        existing.setId(800L);
        existing.setMaxLocalId(5L);
        existing.setMaxSortSeq(200L);
        given(cursorMapper.selectOne(any())).willReturn(null, existing);

        service.ingest(requestOf(
                chatWithCursor(CHAT_PLAIN, new MessagesReportRequest.CursorItem("db/MSG1.db", 9L, 100L), msg("db/MSG1.db", 20, null, 1760000000L, sha(1))),
                chatWithCursor(CHAT, new MessagesReportRequest.CursorItem("db/MSG2.db", 3L, 300L), msg("db/MSG2.db", 21, null, 1760000001L, sha(2)))));

        ArgumentCaptor<WxSyncCursorEntity> insert = ArgumentCaptor.forClass(WxSyncCursorEntity.class);
        verify(cursorMapper).insert(insert.capture());
        assertThat(insert.getValue().getAccount()).isEqualTo(ACCOUNT);
        assertThat(insert.getValue().getChatUsername()).isEqualTo(CHAT_PLAIN);
        assertThat(insert.getValue().getShard()).isEqualTo("db/MSG1.db");
        assertThat(insert.getValue().getMaxLocalId()).isEqualTo(9L);
        assertThat(insert.getValue().getMaxSortSeq()).isEqualTo(100L);

        ArgumentCaptor<LambdaUpdateWrapper<WxSyncCursorEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(cursorMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("max_local_id=", "max_sort_seq=", "last_sync_at=");
        assertThat(setParam(update.getValue(), "max_local_id")).isEqualTo(5L);
        assertThat(setParam(update.getValue(), "max_sort_seq")).isEqualTo(300L);
        update.getValue().getSqlSegment(); // WHERE eq(id,800) 参数懒求值，渲染后才进 paramNameValuePairs
        assertThat(update.getValue().getParamNameValuePairs()).containsValue(800L);

        ArgumentCaptor<LambdaQueryWrapper<WxSyncCursorEntity>> lookup = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(cursorMapper, times(2)).selectOne(lookup.capture());
        assertThat(lookup.getAllValues().get(1).getSqlSegment()).contains("account", "chat_username", "shard");
    }

    @Test
    void blobsNeededExcludesShasAlreadyPresentInBlobTable() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null);
        nextMessageId(900L);
        WxBlobEntity known = new WxBlobEntity();
        known.setSha256(sha(1));
        given(blobMapper.selectList(any())).willReturn(List.of(known));
        MessageItem item = itemWith(msg("db/MSG0.db", 10, null, 1760000000L, sha(9)), List.of(
                new MessagesReportRequest.AttachmentItem("image", "thumb", null, null, 10L, null, sha(1), null, null),
                new MessagesReportRequest.AttachmentItem("video", "original", null, null, 20L, null, sha(2), null, null)));

        MessagesIngestService.IngestOutcome out = service.ingest(requestOf(chatOf(CHAT, item)));

        ArgumentCaptor<LambdaQueryWrapper<WxBlobEntity>> blobQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(blobMapper).selectList(blobQuery.capture());
        blobQuery.getValue().getSqlSegment(); // MP 3.5.15: WHERE 参数懒求值，渲染后才进 paramNameValuePairs
        assertThat(blobQuery.getValue().getSqlSelect()).contains("sha256");
        assertThat(blobQuery.getValue().getParamNameValuePairs()).containsValue(0);
        assertThat(blobQuery.getValue().getParamNameValuePairs()).containsValue(sha(1));
        assertThat(blobQuery.getValue().getParamNameValuePairs()).containsValue(sha(2));

        assertThat(out.data().blobsNeeded()).extracting(MessagesIngestVO.BlobNeeded::sha256).containsExactly(sha(2));
    }

    // ==================== 12 校验分级与信封 ====================

    @Test
    void mixedBatchKeepsValidEntriesAndReportsInvalidOnesAsPartial() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null);
        nextMessageId(950L);
        MessageItem bad = msg(null, 10, null, 1760000000L, sha(1));

        MessagesIngestService.IngestOutcome out = service.ingest(requestOf(chatOf(CHAT, bad, msg("db/MSG0.db", 11, null, 1760000001L, sha(2)))));

        assertThat(out.data().results()).hasSize(1);
        assertThat(out.errors()).hasSize(1);
        ApiError error = out.errors().get(0);
        assertThat(error.field()).isEqualTo("chats[0].messages[0].shard");
        assertThat(error.code()).isEqualTo("FORMAT_INVALID");
        verify(messageMapper, times(1)).insert(any(WxMessageEntity.class));

        ArgumentCaptor<WxIngestBatchLogEntity> batchLog = ArgumentCaptor.forClass(WxIngestBatchLogEntity.class);
        verify(batchLogMapper).insert(batchLog.capture());
        assertThat(batchLog.getValue().getStatus()).isEqualTo("PARTIAL");
        assertThat(batchLog.getValue().getNMessages()).isEqualTo(1);
        assertThat(batchLog.getValue().getError()).contains("chats[0].messages[0].shard");
    }

    @Test
    void allInvalidBatchProducesEmptyResultsForEnvelope10100() {
        MessagesIngestService.IngestOutcome out = service.ingest(requestOf(chatOf(CHAT,
                msg("db/MSG0.db", 10, null, 1760000000L, "not-a-sha"))));

        assertThat(out.data().results()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).field()).isEqualTo("chats[0].messages[0].content_sha256");
        verifyNoInteractions(transactionTemplate, messageMapper, sourceRelMapper, attachmentMapper, blobMapper);

        ArgumentCaptor<WxIngestBatchLogEntity> batchLog = ArgumentCaptor.forClass(WxIngestBatchLogEntity.class);
        verify(batchLogMapper).insert(batchLog.capture());
        assertThat(batchLog.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void missingAccountAndEmptyChatsFailBeforeAnyPersistence() {
        assertThatThrownBy(() -> service.ingest(new MessagesReportRequest("   ", null, List.of(chatOf(CHAT, msg("db/MSG0.db", 10, null, 1760000000L, sha(1)))))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
        assertThatThrownBy(() -> service.ingest(new MessagesReportRequest(ACCOUNT, null, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
        // 空 chat_username 是逐条校验失败（10700 路径），service 层返回 errors 不抛；10100 由 Controller 判 results 空抛出
        var invalidChatOutcome = service.ingest(requestOf(new ChatItem("   ", null, List.of(msg("db/MSG0.db", 10, null, 1760000000L, sha(1))))));
        assertThat(invalidChatOutcome.errors()).extracting(e -> e.field()).contains("chats[0].chat_username");
        assertThat(invalidChatOutcome.data().results()).isEmpty();
        verifyNoInteractions(messageMapper, transactionTemplate, blobMapper);

        ArgumentCaptor<WxIngestBatchLogEntity> batchLog = ArgumentCaptor.forClass(WxIngestBatchLogEntity.class);
        verify(batchLogMapper).insert(batchLog.capture());
        assertThat(batchLog.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(batchLog.getValue().getNMessages()).isZero();
    }

    // ==================== 13 per-chat 事务边界 ====================

    @Test
    void transactionIsOpenedOncePerChatWithValidMessages() {
        runTxInline();
        given(messageMapper.selectOne(any())).willReturn(null);
        AtomicLong txSeq = new AtomicLong(920);
        given(messageMapper.insert(any(WxMessageEntity.class))).willAnswer(inv -> {
            inv.getArgument(0, WxMessageEntity.class).setId(txSeq.incrementAndGet()); // recordOutcome 拆箱需要 id
            return 1;
        });
        given(sourceRelMapper.insert(any(WxMessageSourceRelEntity.class))).willReturn(1);

        service.ingest(requestOf(
                chatOf(CHAT, msg("db/MSG1.db", 10, null, 1760000000L, sha(1))),
                chatOf("   ", msg("db/MSG2.db", 11, null, 1760000001L, sha(2))),
                chatOf(CHAT_PLAIN, msg("db/MSG3.db", 12, null, 1760000002L, sha(3)))));

        verify(transactionTemplate, times(2)).execute(any());
        ArgumentCaptor<WxMessageEntity> insert = ArgumentCaptor.forClass(WxMessageEntity.class);
        verify(messageMapper, times(2)).insert(insert.capture());
        assertThat(insert.getAllValues()).extracting(WxMessageEntity::getChatUsername)
                .containsExactly(CHAT, CHAT_PLAIN);
        ArgumentCaptor<WxIngestBatchLogEntity> batchLog = ArgumentCaptor.forClass(WxIngestBatchLogEntity.class);
        verify(batchLogMapper).insert(batchLog.capture());
        assertThat(batchLog.getValue().getNMessages()).isEqualTo(2);
        assertThat(batchLog.getValue().getNSources()).isEqualTo(2);
        assertThat(batchLog.getValue().getStatus()).isEqualTo("PARTIAL");
        assertThat(batchLog.getValue().getError()).contains("chats[1].chat_username");
    }

    // ==================== 造数与断言辅助 ====================

    /** 让 mock 的 TransactionTemplate 真跑回调，单测才能走到 mapper 参数层。 */
    private void runTxInline() {
        given(transactionTemplate.execute(any())).willAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private void nextMessageId(long id) {
        doAnswer(inv -> {
            inv.getArgument(0, WxMessageEntity.class).setId(id);
            return 1;
        }).when(messageMapper).insert(any(WxMessageEntity.class));
    }

    private WxMessageEntity row(long id, String dedupKey, boolean revoked) {
        WxMessageEntity e = new WxMessageEntity();
        e.setId(id);
        e.setDedupKey(dedupKey);
        e.setChatUsername(CHAT);
        e.setIsRevoked(revoked);
        e.setRevokedAt(revoked ? REVOKED_AT : null);
        return e;
    }

    /** 从 wrapper.sqlSet 反查某列的入参值（MyBatis Plus 3.5.15 生成 column=#{ew.paramNameValuePairs.MPGENVALn}）。 */
    private Object setParam(LambdaUpdateWrapper<?> wrapper, String column) {
        Matcher matcher = Pattern.compile(column + PARAM).matcher(wrapper.getSqlSet());
        assertThat(matcher.find()).as("sqlSet 含 %s: %s", column, wrapper.getSqlSet()).isTrue();
        return wrapper.getParamNameValuePairs().get(matcher.group(1));
    }

    private MessagesReportRequest requestOf(ChatItem... chats) {
        return new MessagesReportRequest(ACCOUNT, "batch-1", List.of(chats));
    }

    private ChatItem chatOf(String username, MessageItem... items) {
        return new ChatItem(username, null, List.of(items));
    }

    private ChatItem chatWithCursor(String username, MessagesReportRequest.CursorItem cursor, MessageItem... items) {
        return new ChatItem(username, cursor, List.of(items));
    }

    private MessageItem msg(String shard, long localId, String serverId, long createTimeRaw, String sha) {
        return msg(shard, localId, serverId, createTimeRaw, sha, null, Boolean.FALSE);
    }

    private MessageItem msg(String shard, long localId, String serverId, long createTimeRaw, String sha,
                            String body, Boolean isRevoked) {
        return new MessageItem(shard, localId, serverId, null, createTimeRaw * 1000, createTimeRaw,
                "10000", "文本", null, "SHARD_NAME2ID", body, null, sha, isRevoked, null);
    }

    private MessageItem itemWith(MessageItem item, List<MessagesReportRequest.AttachmentItem> attachments) {
        return new MessageItem(item.shard(), item.localId(), item.serverId(), item.serverIdRaw(), item.sortSeq(),
                item.createTimeRaw(), item.typeCode(), item.typeName(), item.senderUsername(), item.senderSource(),
                item.body(), item.contentRaw(), item.contentSha256(), item.isRevoked(), attachments);
    }

    /** 64 位小写十六进制（content_sha256 校验合法）。 */
    private String sha(int seed) {
        return String.format("%064d", seed);
    }
}
