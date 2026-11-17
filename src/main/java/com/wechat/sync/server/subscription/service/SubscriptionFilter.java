package com.wechat.sync.server.subscription.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 订阅 filter 的单一事实源（SUBSCRIPTION_DESIGN §2.2）：校验 / 规范化 / filter_hash / 匹配谓词
 * 全在这一份实现——{@link SubscriptionService}（create 落库侧）与 {@link SubscriptionMatcher}
 * （内存匹配侧）都只调它，杜绝「写入口径」与「匹配口径」两处漂移。
 *
 * <p><b>六键语义</b>（键之间 AND；数组值 = IN 精确；全部可选；'{}' = 全量订阅）：
 * <pre>
 * accounts        账号精确（匹配用 ingest 自带 account，不查来源表；SQL 补扫才 join 来源表）
 * chats           会话精确（chat_username）
 * senders         发送者精确（sender_username；条件存在而消息 sender 缺失 → 不命中）
 * type_codes      类型码精确（String 化比较，"1"/"49"/"10002"）
 * keyword         body 大小写敏感子串（≤64 字符）
 * include_revoked 缺省 true；false 时滤掉 is_revoked=true 的消息
 * </pre>
 *
 * <p><b>规范化（v1.1）</b>：键排序（TreeMap）+ 去空值 + 列表去重排序 +
 * <b>数值与字符串值按字符串字面量归一</b>（{@code 25} 与 {@code "25"} 同 hash，杜绝同义两形
 * 漏判重复）；{@code include_revoked=true}（语义缺省）不落键——显式 true 与缺省在规范形里等价。
 * filter_hash = 规范形 JSON（TreeMap 序列化，键序确定）的 sha256 小写十六进制。
 */
public final class SubscriptionFilter {

    /** filter 支持的全部键（未知键 → 10604，防拼写错误静默失效） */
    public static final Set<String> SUPPORTED_KEYS =
            Set.of("accounts", "chats", "senders", "type_codes", "keyword", "include_revoked");

    /** 数组条件键（值必须是字符串数组；≤{@link #MAX_LIST_ITEMS} 项） */
    private static final Set<String> LIST_KEYS = Set.of("accounts", "chats", "senders", "type_codes");

    /** 单个数组条件的最大项数（§9 10604：列表>100 拒绝，防全表级条件滥用） */
    public static final int MAX_LIST_ITEMS = 100;

    /** keyword 最大长度（§9 10604：>64 字符拒绝） */
    public static final int KEYWORD_MAX = 64;

    private static final String KEY_KEYWORD = "keyword";
    private static final String KEY_INCLUDE_REVOKED = "include_revoked";

    /** 规范形序列化/解析共用（TreeMap 进 → 键序确定出；线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SubscriptionFilter() {
    }

    /**
     * 校验 + 规范化原始 filter（create 入口）。非法即抛 10604（SUBSCRIPTION_FILTER_INVALID）：
     * 未知键 / 数组值非 List / 列表&gt;100 / keyword&gt;64 / include_revoked 非布尔。
     * null 值与缺键同义（跳过）；空数组/空串视为「未设条件」（跳过）。
     *
     * @return 规范形（TreeMap，可直接序列化为落库 JSON 文本）；{@code raw == null} → 空 Map（全量订阅）
     */
    public static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = new TreeMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String key : raw.keySet()) {
            if (!SUPPORTED_KEYS.contains(key)) {
                throw invalid("未知 filter 键: " + key);
            }
            Object value = raw.get(key);
            if (value == null) {
                continue;                       // null = 未设条件（与缺键同义）
            }
            if (LIST_KEYS.contains(key)) {
                if (!(value instanceof List<?> list)) {
                    throw invalid(key + " 必须是字符串数组");
                }
                if (list.size() > MAX_LIST_ITEMS) {
                    throw invalid(key + " 超过最大 " + MAX_LIST_ITEMS + " 项");
                }
                TreeSet<String> items = new TreeSet<>();
                for (Object item : list) {
                    if (item == null) {
                        continue;
                    }
                    String s = String.valueOf(item).trim();   // 字符串字面量归一：25 → "25"
                    if (!s.isEmpty()) {
                        items.add(s);
                    }
                }
                if (!items.isEmpty()) {
                    out.put(key, new ArrayList<>(items));     // 去重 + 排序（确定序）
                }
            } else if (KEY_KEYWORD.equals(key)) {
                String s = String.valueOf(value).trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.length() > KEYWORD_MAX) {
                    throw invalid("keyword 超过最大 " + KEYWORD_MAX + " 字符");
                }
                out.put(key, s);
            } else {                                      // include_revoked
                Boolean b = toBoolean(value);
                if (b == null) {
                    throw invalid("include_revoked 必须是布尔（true/false）");
                }
                if (!b) {
                    out.put(key, Boolean.FALSE);          // 缺省 true → 仅 false 落键（规范形等价收敛）
                }
            }
        }
        return out;
    }

    /** 规范形 → 落库 JSON 文本（键序确定；空 Map → "{}" = 全量订阅）。 */
    public static String toJson(Map<String, Object> normalized) {
        try {
            return MAPPER.writeValueAsString(normalized == null ? Map.of() : normalized);
        } catch (Exception e) {                          // 规范形全为 String/Boolean/List，理论不可达
            throw new IllegalStateException("filter 序列化失败: " + e.getMessage(), e);
        }
    }

    /** filter_hash：规范化 JSON 文本的 sha256 小写十六进制（VARCHAR(64) 口径）。 */
    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {           // JVM 必带 SHA-256
            throw new IllegalStateException(e);
        }
    }

    /**
     * 落库 JSON 文本 → 匹配谓词（matcher refresh 用）。文本损坏/形态异常抛
     * {@link IllegalArgumentException}（调用方逐订阅 catch 跳过，不让一颗坏订阅拖垮快照）。
     */
    public static Parsed parse(String filterJson) {
        if (filterJson == null || filterJson.isBlank() || "{}".equals(filterJson.trim())) {
            return Parsed.ALL;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(filterJson, Map.class);
            return new Parsed(
                    toStringSet(map.get("accounts")),
                    toStringSet(map.get("chats")),
                    toStringSet(map.get("senders")),
                    toStringSet(map.get("type_codes")),
                    map.get("keyword") == null ? null : String.valueOf(map.get("keyword")),
                    !Boolean.FALSE.equals(toNullableBoolean(map.get("include_revoked"))));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("filter 解析失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部 ====================

    /** 10604 工厂（WxErrorCode 同文案口径）。 */
    private static ApiException invalid(String reason) {
        return new ApiException(
                com.wechat.sync.server.common.WxErrorCode.SUBSCRIPTION_FILTER_INVALID,
                "订阅 filter 非法: " + reason);
    }

    /** include_revoked 宽松读：Boolean 直收；"true"/"false" 字符串字面量归一（v1.1）；其余 null=非法。 */
    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** parse 侧宽松读：null/非 List → null（=未设条件；parse 对落库文本信任 create 已校验）。 */
    private static Set<String> toStringSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        TreeSet<String> items = new TreeSet<>();
        for (Object item : list) {
            if (item != null) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) {
                    items.add(s);
                }
            }
        }
        return items.isEmpty() ? null : items;
    }

    private static Boolean toNullableBoolean(Object value) {
        return value == null ? null : toBoolean(value);
    }

    /** 匹配谓词（不可变，快照元素持有；六个字段全 null/false 即 ALL 语义）。 */
    public record Parsed(Set<String> accounts, Set<String> chats, Set<String> senders,
                         Set<String> typeCodes, String keyword, boolean includeRevoked) {

        /** 全量订阅谓词（'{}'）。 */
        public static final Parsed ALL =
                new Parsed(null, null, null, null, null, true);

        /**
         * 六键 AND 判定（§2.2）。{@code account} 来自 ingest 自带参数（不查来源表，§3.3）；
         * 条件存在而对应字段为 null → 不命中（显式 allowlist 不命中未知值，宁窄勿宽）。
         */
        public boolean matches(String account, String chatUsername, String senderUsername,
                               String typeCode, String body, boolean isRevoked) {
            if (accounts != null && (account == null || !accounts.contains(account))) {
                return false;
            }
            if (chats != null && (chatUsername == null || !chats.contains(chatUsername))) {
                return false;
            }
            if (senders != null && (senderUsername == null || !senders.contains(senderUsername))) {
                return false;
            }
            if (typeCodes != null && (typeCode == null || !typeCodes.contains(typeCode))) {
                return false;
            }
            if (keyword != null && (body == null || !body.contains(keyword))) {
                return false;
            }
            return includeRevoked || !isRevoked;
        }
    }
}
