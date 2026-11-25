package com.wechat.sync.server.query.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 查询侧专用 Mapper（只读，T6 新增；不复用 T4 的 ingest Mapper——那边的实体带 content_raw
 * 等大字段，{@code BaseMapper} 会按实体列清单全取，违反 DB_DESIGN §12.2 大字段纪律）。
 *
 * <p>纪律（DB_DESIGN §12）：① 全部显式列清单，<strong>绝不 SELECT *</strong>；消息列表/检索
 * <strong>不取 content_raw</strong>（详情单查才取）；② LIMIT 由服务层钳到 ≤200（规范上限 1000）；
 * ③ keyset 谓词 {@code (create_time, id) < (?, ?)} 走 idx_wx_message_chat_time 前缀。
 */
public interface WxMessageQueryMapper {

    /** 列表/检索共用的行载体（@Data 而非 record：MyBatis 自动映射走 setter，最稳）。 */
    @Data
    class MessageRow {
        private Long id;
        private String chatUsername;
        private Boolean isGroup;
        private Long serverId;
        private String serverIdRaw;
        private String senderUsername;
        private String senderSource;
        private String typeCode;
        private String typeName;
        private OffsetDateTime createTime;
        private Long createTimeRaw;
        private String body;
        private String contentSha256;
        private Boolean isRevoked;
        private OffsetDateTime revokedAt;
        private Integer seenAccounts;
        private OffsetDateTime createdAt;
        private OffsetDateTime lastSeenAt;
        /** 仅详情查询取（大字段）；列表/检索恒 null */
        private String contentRaw;
        /** 仅详情查询取；列表恒 null */
        private String dedupKey;
    }

    /**
     * 消息流 / 检索共用 keyset 分页（API_DESIGN §5.3 / §5.5）：倒序 {@code (create_time, id)}，
     * 全部过滤条件可选（null = 不过滤）。{@code bodyPattern} 由服务层转义后拼好
     * {@code %q%}——<b>规范禁左模糊，检索接口经评审豁免：内容检索业务必需</b>（用户记不起原文前缀），
     * v1 用现有 btree 索引 + ILIKE，不建 trgm/tsvector（DB_DESIGN §14 同口径，接口不变可后升级）。
     * {@code account} 过滤走 EXISTS 子查询（一条消息 × N 来源，命中任一来源账号即算）。
     *
     * <p>cursor 位点拆成 cursorTime/cursorId 两个标量参数传（不传 record：MyBatis 参数属性访问
     * 只认 JavaBean 的 getX/isX，record 访问器不保证可用）。
     *
     * @param limit 已钳制（≤200），DB_DESIGN §12.1 的 LIMIT ≤ 1000 由此保证
     */
    @Select("""
            <script>
            SELECT m.id, m.chat_username, m.is_group, m.server_id, m.server_id_raw,
                   m.sender_username, m.sender_source, m.type_code, m.type_name,
                   m.create_time, m.create_time_raw, m.body, m.content_sha256,
                   m.is_revoked, m.revoked_at, m.seen_accounts, m.created_at, m.last_seen_at
              FROM wx_message m
             <where>
               <if test="chatUsername != null">AND m.chat_username = #{chatUsername}</if>
               <if test="senderUsername != null">AND m.sender_username = #{senderUsername}</if>
               <if test="typeName != null">AND m.type_name = #{typeName}</if>
               <if test="after != null">AND m.create_time &gt;= #{after}</if>
               <if test="before != null">AND m.create_time &lt; #{before}</if>
               <if test="account != null">
                 AND EXISTS (SELECT 1 FROM wx_message_source_rel sr
                              WHERE sr.message_id = m.id AND sr.account = #{account})
               </if>
               <if test="bodyPattern != null">AND m.body ILIKE #{bodyPattern}</if>
               <if test="cursorTime != null">AND (m.create_time, m.id) &lt; (#{cursorTime}, #{cursorId})</if>
             </where>
             ORDER BY m.create_time DESC, m.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<MessageRow> pageMessages(@Param("chatUsername") String chatUsername,
                                  @Param("senderUsername") String senderUsername,
                                  @Param("typeName") String typeName,
                                  @Param("account") String account,
                                  @Param("bodyPattern") String bodyPattern,
                                  @Param("after") OffsetDateTime after,
                                  @Param("before") OffsetDateTime before,
                                  @Param("cursorTime") OffsetDateTime cursorTime,
                                  @Param("cursorId") Long cursorId,
                                  @Param("limit") int limit);

    /**
     * 单条详情（API_DESIGN §5.4）：唯一取 content_raw / dedup_key 全文的入口（大字段按需取）。
     */
    @Select("""
            SELECT m.id, m.chat_username, m.is_group, m.server_id, m.server_id_raw,
                   m.sender_username, m.sender_source, m.type_code, m.type_name,
                   m.create_time, m.create_time_raw, m.body, m.content_raw, m.content_sha256,
                   m.dedup_key, m.is_revoked, m.revoked_at, m.seen_accounts,
                   m.created_at, m.last_seen_at
              FROM wx_message m
             WHERE m.id = #{messageId}
            """)
    MessageRow selectDetail(@Param("messageId") long messageId);

    /** chat_username 存在性判定（API_DESIGN §7：查询里 chat_username 不存在 → 10400）。只查首屏。 */
    /** 会话聚合兜底：wx_conversation 未上报时（C++ 客户端暂不产会话层），从消息表聚合会话维度。 */
    @Select("""
            SELECT m.chat_username                     AS chatUsername,
                   (m.chat_username LIKE '%@chatroom') AS isGroup,
                   count(*)                            AS messageCount,
                   max(m.create_time)                  AS lastMessageAt,
                   min(m.create_time)                  AS firstSeenAt
              FROM wx_message m
             WHERE (#{account}::text IS NULL OR EXISTS (SELECT 1 FROM wx_message_source_rel sr
                          WHERE sr.message_id = m.id AND sr.account = #{account}))
               AND (#{isGroup}::boolean IS NULL OR (m.chat_username LIKE '%@chatroom') = #{isGroup})
             GROUP BY m.chat_username
             ORDER BY max(create_time) DESC NULLS LAST
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<ChatAggRow> aggChatsByMessage(@Param("account") String account,
                                       @Param("isGroup") Boolean isGroup,
                                       @Param("limit") long limit,
                                       @Param("offset") long offset);

    @Select("SELECT count(DISTINCT m.chat_username) FROM wx_message m "
            + "WHERE (#{account}::text IS NULL OR EXISTS (SELECT 1 FROM wx_message_source_rel sr "
            + "WHERE sr.message_id = m.id AND sr.account = #{account})) "
            + "AND (#{isGroup}::boolean IS NULL OR (m.chat_username LIKE '%@chatroom') = #{isGroup})")
    long countAggChatsByMessage(@Param("account") String account, @Param("isGroup") Boolean isGroup);

    /** 聚合会话行（@Data：MyBatis setter 自动映射，同 MessageRow 约定）。 */
    @Data
    class ChatAggRow {
        private String chatUsername;
        private Boolean isGroup;
        private Long messageCount;
        private OffsetDateTime lastMessageAt;
        private OffsetDateTime firstSeenAt;
    }

    /**
     * 会话页摘要（§5.2）：给定当页会话的用户名清单，各取最新一条消息。驱动表是入参清单（VALUES），
     * 每个会话一次 LATERAL：等值 {@code chat_username} 沿 {@code idx_wx_message_chat_time} 索引序取
     * 首行即停（LIMIT 1），代价与当页会话数成正比、与各会话消息总量无关——不能用 DISTINCT ON + IN，
     * 那要把这些会话的全部消息排序去重。
     *
     * <p>排序键刻意与索引同序（{@code create_time DESC, id DESC}，id 走 Incremental Sort）：
     * 一旦写成 {@code NULLS LAST} 就与 DESC 索引的天然序（NULL 在前）不符，规划器会退化成
     * 「该会话全部行 + 排序 + LIMIT」。无时间行（仅测试夹具会出现）用 {@code IS NOT NULL} 谓词排除，
     * 既保住索引序又不让它被当成最新一条。
     *
     * <p>入参条数即页大小（≤200）。body 截 200 字符：只做列表第二行，全文走消息流 / 详情。
     * LEFT JOIN：查无（有时间的）消息的会话仍出一行，三个摘要列皆 null。
     */
    @Select("""
            <script>
            SELECT c.username        AS chatUsername,
                   m.sender_username AS senderUsername,
                   m.type_code       AS typeCode,
                   left(m.body, 200) AS body
              FROM (VALUES <foreach collection="usernames" item="u" separator=",">(#{u})</foreach>) AS c(username)
              LEFT JOIN LATERAL (
                    SELECT m2.sender_username, m2.type_code, m2.body
                      FROM wx_message m2
                     WHERE m2.chat_username = c.username
                       AND m2.create_time IS NOT NULL
                       AND (#{account}::text IS NULL OR EXISTS (SELECT 1 FROM wx_message_source_rel sr
                                WHERE sr.message_id = m2.id AND sr.account = #{account}))
                     ORDER BY m2.create_time DESC, m2.id DESC
                     LIMIT 1
              ) m ON true
            </script>
            """)
    List<ChatLastRow> lastMessagesByChats(@Param("usernames") Collection<String> usernames,
                                          @Param("account") String account);

    /** 会话页摘要行（每会话最新一条消息；@Data：MyBatis setter 自动映射，同 MessageRow 约定）。 */
    @Data
    class ChatLastRow {
        private String chatUsername;
        private String senderUsername;
        private String typeCode;
        private String body;
    }

    @Select("SELECT count(*) FROM wx_message WHERE chat_username = #{chatUsername}")
    long countByChat(@Param("chatUsername") String chatUsername);

    /** 去重视图行（API_DESIGN §5.10）：accounts 数组经 to_json 转字符串，服务层再解析成列表。 */
    @Data
    class DedupRow {
        private Long id;
        private String chatUsername;
        private Long serverId;
        private String dedupKey;
        private String senderUsername;
        private OffsetDateTime createTime;
        private String typeName;
        private Long sourceAccounts;
        /** v_wx_message_dedup.accounts（varchar[]）的 JSON 形态，如 ["a","b"] */
        private String accountsJson;
    }

    /**
     * v_wx_message_dedup 分页（视图已聚合好 source_accounts，DB_DESIGN §12.6 不在业务里重算）；
     * 传 IPage 由 PaginationInnerInterceptor 自动加 LIMIT/OFFSET 并跑 count。
     * 不取视图的 body 列（大字段，统计页用不到）。
     */
    @Select("""
            <script>
            SELECT id, chat_username, server_id, dedup_key, sender_username,
                   create_time, type_name, source_accounts, to_json(accounts) AS accounts_json
              FROM v_wx_message_dedup
             <where>
               <if test="chatUsername != null">AND chat_username = #{chatUsername}</if>
             </where>
             ORDER BY create_time DESC NULLS LAST, id DESC
            </script>
            """)
    List<DedupRow> pageDedup(IPage<DedupRow> page, @Param("chatUsername") String chatUsername);

    /** 群成员并集视图行（API_DESIGN §5.7 union=true）：跨账号聚合，只有 seen_in_accounts。 */
    @Data
    class UnionMemberRow {
        private String groupUsername;
        private String memberUsername;
        private Long seenInAccounts;
    }

    /**
     * v_wx_group_members_union 分页（union=true）：各账号成员集合的并集 + 见过它的账号数。
     */
    @Select("""
            <script>
            SELECT group_username, member_username, seen_in_accounts
              FROM v_wx_group_members_union
             WHERE group_username = #{groupUsername}
             ORDER BY member_username
            </script>
            """)
    List<UnionMemberRow> pageUnionMembers(IPage<UnionMemberRow> page,
                                          @Param("groupUsername") String groupUsername);
}
