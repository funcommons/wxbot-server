package com.wechat.sync.server.query.service;

import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.query.dto.QueryVOs;
import com.wechat.sync.server.query.mapper.WxMessageQueryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.2 会话页摘要真库集成：每会话取最新一条（同刻按 id 决胜）、body 截 200、账号过滤不被别的账号的
 * 更新消息顶掉、无时间会话排末且不被当成最新一条、查无消息仍出一行。
 *
 * <p><b>数据卫生</b>（先例 SubscriptionDeliveryIntegrationTest 口径）：会话 {@code t12chatsum_*}、
 * dedup_key {@code t12chatsum:}、账号 {@code wxid_t12chatsum*}——全部带独占前缀，前后各清一次。
 * 直写 wx_message 而不走 ingest：避免触发真实消费者的投递钩子；正文与发送者刻意不含数字 1
 * （库里有启用的 keyword=1 订阅），投递表仍按 message_id 兜底清理。
 */
@SpringBootTest(properties = "spring.test.context.failure.threshold=2")
class QueryChatsSummaryIntegrationTest {

    private static final String P = "t12chatsum_";
    private static final String KEY_PREFIX = P.replace('_', ':');
    private static final String ACC = "wxid_" + P + "me";
    private static final String ACC_OTHER = "wxid_" + P + "other";
    private static final String CHAT_ROOM = P + "room@chatroom";
    private static final String CHAT_TIE = P + "tie@chatroom";
    private static final String CHAT_LONG = P + "long";
    private static final String CHAT_MINE = P + "mine";
    private static final String CHAT_NOTIME = P + "notime@chatroom";
    /** 微信 4.x 带高位标志的 appmsg 类型码（&amp; 0xFFFFFFFF = 49） */
    private static final String TC_APPMSG_HI = "244813135921";

    @Autowired private QueryService queryService;
    @Autowired private WxMessageQueryMapper messageQueryMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM wx_subscription_delivery WHERE message_id IN "
                + "(SELECT id FROM wx_message WHERE chat_username LIKE ?)", P + "%");
        jdbcTemplate.update("DELETE FROM wx_message_source_rel WHERE account IN (?, ?) OR chat_username LIKE ?",
                ACC, ACC_OTHER, P + "%");
        jdbcTemplate.update("DELETE FROM wx_message WHERE chat_username LIKE ?", P + "%");
    }

    @Test
    void summaryTakesEachChatsNewestMessageTruncatesBodyAndHonoursAccountFilter() {
        OffsetDateTime now = OffsetDateTime.now();
        // room：旧文本 + 新卡片 → 摘要是卡片那一条
        String card = "<msg><appmsg><title>card</title></appmsg></msg>";
        insert("a:old", CHAT_ROOM, "wxid_qa", "1", "old", now.minusHours(2));
        insert("a:card", CHAT_ROOM, "wxid_qa", TC_APPMSG_HI, card, now.minusHours(1));
        // tie：同一时刻两条 → 后写入（id 大）的为准
        OffsetDateTime tie = now.minusMinutes(40);
        insert("b:tie-first", CHAT_TIE, "wxid_qa", "1", "first", tie);
        insert("b:tie-second", CHAT_TIE, "wxid_qa", "1", "second", tie);
        // long：300 字符正文 → 截 200
        insert("c:long", CHAT_LONG, "wxid_qb", "1", "x".repeat(300), now.minusMinutes(30));
        // mine：两条消息分属两个账号上报，较新的那条不是 ACC 上报的
        long mineOld = insert("d:mine", CHAT_MINE, "wxid_qa", "1", "mine", now.minusHours(3));
        long theirsNew = insert("e:theirs", CHAT_MINE, "wxid_qb", "1", "theirs", now.minusHours(1));
        source(ACC, mineOld, 9301);
        source(ACC_OTHER, theirsNew, 9302);
        // notime：只有无时间行（测试夹具形态）
        insert("f:notime", CHAT_NOTIME, "wxid_qa", "1", "zz", null);

        PageVO<QueryVOs.ChatVO> all = queryService.chats(null, null, 1, 200);
        List<QueryVOs.ChatVO> rows = all.list();
        Map<String, QueryVOs.ChatVO> byChat = rows.stream()
                .collect(Collectors.toMap(QueryVOs.ChatVO::username, Function.identity()));

        assertThat(byChat).containsKeys(CHAT_ROOM, CHAT_TIE, CHAT_LONG, CHAT_MINE, CHAT_NOTIME);
        QueryVOs.ChatVO room = byChat.get(CHAT_ROOM);
        assertThat(room.isGroup()).isTrue();
        assertThat(room.messageCount()).isEqualTo(2L);
        assertThat(room.lastSenderUsername()).isEqualTo("wxid_qa");
        assertThat(room.lastTypeCode()).isEqualTo(TC_APPMSG_HI);
        assertThat(room.lastBody()).isEqualTo(card);
        assertThat(byChat.get(CHAT_TIE).lastBody()).isEqualTo("second");
        assertThat(byChat.get(CHAT_LONG).lastBody()).isEqualTo("x".repeat(200));
        assertThat(byChat.get(CHAT_MINE).lastBody()).isEqualTo("theirs");

        // 无时间行不算最新一条：仅有无时间行的会话没有摘要
        QueryVOs.ChatVO noTime = byChat.get(CHAT_NOTIME);
        assertThat(noTime.lastMessageAt()).isNull();
        assertThat(noTime.messageCount()).isEqualTo(1L);
        assertThat(noTime.lastBody()).isNull();

        // 排序：无时间会话排在全部有时间会话之后（PG 的 DESC 默认 NULL 在前）
        int noTimeAt = index(rows, CHAT_NOTIME);
        assertThat(noTimeAt).isGreaterThan(index(rows, CHAT_ROOM));
        assertThat(rows.subList(0, noTimeAt)).allMatch(c -> c.lastMessageAt() != null);

        // 账号过滤：只见 ACC 上报的会话，摘要也只取 ACC 上报的最新一条
        PageVO<QueryVOs.ChatVO> mine = queryService.chats(ACC, null, 1, 200);
        assertThat(mine.total()).isEqualTo(1);
        assertThat(mine.list()).extracting(QueryVOs.ChatVO::username).containsExactly(CHAT_MINE);
        assertThat(mine.list().get(0).lastBody()).isEqualTo("mine");

        // 查无消息的会话仍出一行（摘要列皆 null）
        String noSuchChat = P + "nosuch@chatroom";
        List<WxMessageQueryMapper.ChatLastRow> none =
                messageQueryMapper.lastMessagesByChats(List.of(noSuchChat), null);
        assertThat(none).hasSize(1);
        assertThat(none.get(0).getChatUsername()).isEqualTo(noSuchChat);
        assertThat(none.get(0).getBody()).isNull();
        assertThat(none.get(0).getSenderUsername()).isNull();
    }

    /** 直写一行消息（dedup_key 带独占前缀），返回 id；time 为 null 即无时间行。 */
    private long insert(String key, String chat, String sender, String type, String body, OffsetDateTime time) {
        return jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO wx_message (dedup_key, chat_username, "
                    + "is_group, sender_username, type_code, create_time, create_time_raw, body) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id");
            ps.setString(1, KEY_PREFIX + key);
            ps.setString(2, chat);
            ps.setBoolean(3, chat.endsWith("@chatroom"));
            ps.setString(4, sender);
            ps.setString(5, type);
            if (time == null) {
                ps.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setTimestamp(6, Timestamp.from(time.toInstant()));
                ps.setLong(7, time.toEpochSecond());
            }
            ps.setString(8, body);
            return ps;
        }, rs -> {
            rs.next();
            return rs.getLong(1);
        });
    }

    private void source(String account, long messageId, long localId) {
        jdbcTemplate.update("INSERT INTO wx_message_source_rel (account, chat_username, shard, local_id, message_id) "
                + "VALUES (?, ?, ?, ?, ?)", account, CHAT_MINE, P + "shard", localId, messageId);
    }

    private static int index(List<QueryVOs.ChatVO> rows, String username) {
        for (int i = 0; i < rows.size(); i++) {
            if (username.equals(rows.get(i).username())) {
                return i;
            }
        }
        throw new AssertionError("会话不在首页: " + username);
    }
}
