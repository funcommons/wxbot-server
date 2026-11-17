package com.wechat.sync.server.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.sync.server.message.dto.MessagesIngestVO;
import com.wechat.sync.server.message.dto.MessagesReportRequest;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.entity.WxMessageSourceRelEntity;
import com.wechat.sync.server.message.entity.WxMergeLogEntity;
import com.wechat.sync.server.message.mapper.WxMergeLogMapper;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import com.wechat.sync.server.message.mapper.WxMessageSourceRelMapper;
import com.wechat.sync.server.message.service.MessagesIngestService;
import fun.commons.framework4j.web.ApiError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息 ingest 集成冒烟（直连真实 PG 5433 / Redis 6379，走真实 Mapper + Service，不起 HTTP）。
 *
 * <p>验证三条业务主断言：跨账号同 server_id 只留一条 wx_message + N 条来源行 + seen_accounts 反算
 * （i1，全项目去重核心）、fb 键补 server_id 后原地合并（i2）、BIGINT 溢出 server_id 落 raw 且 CHECK 放行
 * （i3）、单 chat 落库失败只回滚本 chat（i4）。数据用 wxid_t4itest_ / t4itest_ 前缀，前后各清一次。
 */
@SpringBootTest(properties = "spring.test.context.failure.threshold=2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageIngestIntegrationTest {

    private static final String ACC_PREFIX = "wxid_t4itest_";
    private static final String CHAT_PREFIX = "t4itest_";
    private static final String ALICE = ACC_PREFIX + "a";
    private static final String BOB = ACC_PREFIX + "b";
    private static final String CHAT_DEDUP = CHAT_PREFIX + "dedup@chatroom";
    private static final String CHAT_MERGE = CHAT_PREFIX + "merge";
    private static final String CHAT_RAW = CHAT_PREFIX + "raw";
    private static final String CHAT_OK = CHAT_PREFIX + "ok@chatroom";
    /** >64 字符（ChatItem @Size 上限）→ wx_message.chat_username VARCHAR(64) 拒收 → 整 chat 回滚 */
    private static final String CHAT_FAIL = CHAT_PREFIX + "fail" + "x".repeat(60) + "@chatroom";

    @Autowired private MessagesIngestService service;
    @Autowired private WxMessageMapper messageMapper;
    @Autowired private WxMessageSourceRelMapper sourceRelMapper;
    @Autowired private WxMergeLogMapper mergeLogMapper;
    @Autowired private DataSource dataSource;

    @BeforeAll
    void purgeBefore() throws SQLException { purge(); }

    @AfterAll
    void purgeAfter() throws SQLException { purge(); }

    @Test
    void sameServerIdAcrossAccountsKeepsOneMessageRowAndTwoSourceRows() {
        service.ingest(report(ALICE, CHAT_DEDUP, "db/MSG0.db", 10L, "9001"));
        service.ingest(report(BOB, CHAT_DEDUP, "db/MSG0.db", 10L, "9001"));
        service.ingest(report(ALICE, CHAT_DEDUP, "db/MSG0.db", 10L, "9001"));

        WxMessageEntity row = messageMapper.selectOne(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getDedupKey, "srv:9001"));
        assertThat(row).as("同 server_id 只留一条全局消息").isNotNull();
        Long relCount = sourceRelMapper.selectCount(new LambdaQueryWrapper<WxMessageSourceRelEntity>()
                .eq(WxMessageSourceRelEntity::getMessageId, row.getId()));
        assertThat(relCount).as("alice+bob 各一条来源行（重放不新增）").isEqualTo(2L);
        assertThat(row.getSeenAccounts()).as("seen_accounts 由来源行反算（alice+bob）").isEqualTo(2);
        assertThat(messageMapper.selectCount(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getChatUsername, CHAT_DEDUP))).isEqualTo(1L);
    }

    @Test
    void fbKeyRowMergesInPlaceWhenServerIdArrivesLater() {
        String fbKey = "fb:" + CHAT_MERGE + "||1760001000|" + sha(1);
        service.ingest(report(ALICE, CHAT_MERGE, "db/MSG1.db", 11L, null));
        WxMessageEntity fbRow = messageMapper.selectOne(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getDedupKey, fbKey));
        assertThat(fbRow).as("缺 server_id 先落 fb 键").isNotNull();
        assertThat(fbRow.getServerId()).isNull();

        MessagesIngestVO data = service.ingest(report(ALICE, CHAT_MERGE, "db/MSG1.db", 11L, "7001")).data();

        assertThat(data.results()).hasSize(1);
        assertThat(data.results().get(0).action()).isEqualTo("merged");
        assertThat(data.results().get(0).messageId()).isEqualTo(String.valueOf(fbRow.getId()));
        WxMessageEntity merged = messageMapper.selectById(fbRow.getId());
        assertThat(merged.getDedupKey()).isEqualTo("srv:7001");
        assertThat(merged.getServerId()).isEqualTo(7001L);
        assertThat(merged.getServerIdRaw()).isNull();
        assertThat(messageMapper.selectCount(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getChatUsername, CHAT_MERGE))).as("原地改键不产生第二行").isEqualTo(1L);
        assertThat(mergeLogMapper.selectCount(new LambdaQueryWrapper<WxMergeLogEntity>()
                .eq(WxMergeLogEntity::getKeptId, fbRow.getId()))).isEqualTo(1L);
    }

    @Test
    void overflowServerIdIsStoredAsRawAndPassesCheckConstraint() {
        MessagesIngestService.IngestOutcome out = service.ingest(report(ALICE, CHAT_RAW, "db/MSG2.db", 12L, "99999999999999999999"));

        assertThat(out.errors()).isEmpty();
        WxMessageEntity row = messageMapper.selectOne(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getDedupKey, "srv:99999999999999999999"));
        assertThat(row).as("ck_wx_message_srv_dedup 的 NULL 分支放行 raw 键").isNotNull();
        assertThat(row.getServerId()).isNull();
        assertThat(row.getServerIdRaw()).isEqualTo("99999999999999999999");
    }

    @Test
    void failingChatRollsBackAloneAndLaterChatStillCommits() {
        MessagesReportRequest request = new MessagesReportRequest(ALICE, "t4itest-batch", List.of(
                chat(CHAT_FAIL, "db/MSG3.db", 13L, "9002"),
                chat(CHAT_OK, "db/MSG4.db", 14L, "9003")));

        MessagesIngestService.IngestOutcome out = service.ingest(request);

        assertThat(out.data().results()).hasSize(1);
        assertThat(out.data().results().get(0).dedupKey()).isEqualTo("srv:9003");
        assertThat(out.errors()).hasSize(1);
        ApiError error = out.errors().get(0);
        assertThat(error.field()).isEqualTo("chats[0].messages[0].local_id");
        assertThat(error.code()).isEqualTo("DB_ERROR");
        assertThat(messageMapper.selectCount(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getChatUsername, CHAT_FAIL))).as("失败 chat 整体回滚").isZero();
        assertThat(messageMapper.selectCount(new LambdaQueryWrapper<WxMessageEntity>()
                .eq(WxMessageEntity::getChatUsername, CHAT_OK))).isEqualTo(1L);
        assertThat(sourceRelMapper.selectCount(new LambdaQueryWrapper<WxMessageSourceRelEntity>()
                .eq(WxMessageSourceRelEntity::getChatUsername, CHAT_OK))).isEqualTo(1L);
    }

    // ==================== 造数与清理 ====================

    private MessagesReportRequest report(String account, String chat, String shard, long localId, String serverId) {
        return new MessagesReportRequest(account, null, List.of(chat(chat, shard, localId, serverId)));
    }

    private MessagesReportRequest.ChatItem chat(String chat, String shard, long localId, String serverId) {
        return new MessagesReportRequest.ChatItem(chat, null, List.of(new MessagesReportRequest.MessageItem(
                shard, localId, serverId, null, 1760001000L * 1000, 1760001000L, "10000", "文本", null,
                "SHARD_NAME2ID", "hello", null, sha(1), Boolean.FALSE, null)));
    }

    /** 64 位小写十六进制；四条用例的正文哈希相同（fb 键靠 chat 区分）。 */
    private String sha(int seed) {
        return String.format("%064d", seed);
    }

    /** 只清本前缀（账号 wxid_t4itest_ / 会话 t4itest_），幂等且不碰他人数据。 */
    private void purge() throws SQLException {
        exec("DELETE FROM wx_message_attachment WHERE message_id IN "
                + "(SELECT id FROM wx_message WHERE chat_username LIKE '" + CHAT_PREFIX + "%')");
        exec("DELETE FROM wx_merge_log WHERE kept_id IN "
                + "(SELECT id FROM wx_message WHERE chat_username LIKE '" + CHAT_PREFIX + "%')");
        exec("DELETE FROM wx_message_source_rel WHERE account LIKE '" + ACC_PREFIX + "%'");
        exec("DELETE FROM wx_message WHERE chat_username LIKE '" + CHAT_PREFIX + "%'");
        exec("DELETE FROM wx_sync_cursor WHERE account LIKE '" + ACC_PREFIX + "%'");
        exec("DELETE FROM wx_ingest_batch_log WHERE account LIKE '" + ACC_PREFIX + "%'");
    }

    private void exec(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
