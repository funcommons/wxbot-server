package com.wechat.sync.server.subscription.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wechat.sync.server.common.JsonbTypeHandler;
import com.wechat.sync.server.subscription.mapper.WxConsumerMapper;
import com.wechat.sync.server.subscription.mapper.WxSubDeliveryCursorMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import com.wechat.sync.server.support.MpTestTables;
import com.wechat.sync.server.WxSyncServerApplication;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.UnknownTypeHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v6 订阅域四实体（wx_consumer / wx_subscription / wx_subscription_delivery / wx_sub_delivery_cursor）
 * 与 Mapper 的静态契约单测：表名、property→column snake_case、雪花 IdType、JSONB=String 透传 +
 * JsonbTypeHandler 只标在 filter、注解 SQL 关键片段（SKIP LOCKED / 两形互斥 / lease 凭据条件 /
 * ON CONFLICT 幂等 / 游标 GREATEST）——对齐 schema.sql v6 增量块与 SUBSCRIPTION_DESIGN §2/§4/§5.3/§6。
 */
class SubscriptionEntitiesMappingTest {

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxConsumerEntity.class, WxSubscriptionEntity.class,
                WxSubscriptionDeliveryEntity.class, WxSubDeliveryCursorEntity.class);
    }

    // ------------------------------------------------------------ 实体映射（先例口径照抄 TaskEntitiesMappingTest）

    private static TableInfo info(Class<?> entity) {
        return TableInfoHelper.getTableInfo(entity);
    }

    /** property → 字段元数据（不含主键：PK 在 TableInfo 的 keyProperty/keyColumn 上，故单断）。 */
    private static Map<String, TableFieldInfo> fieldsOf(Class<?> entity) {
        return info(entity).getFieldList().stream()
                .collect(Collectors.toMap(TableFieldInfo::getProperty, Function.identity()));
    }

    private static void assertColumns(Class<?> entity, Map<String, String> expected, String label) {
        Map<String, TableFieldInfo> fields = fieldsOf(entity);
        expected.forEach((property, column) -> {
            TableFieldInfo field = fields.get(property);
            assertThat(field).as("%s.%s 字段存在", label, property).isNotNull();
            assertThat(field.getColumn()).as("%s.%s → 列名 snake_case", label, property).isEqualTo(column);
        });
    }

    private static void assertSnowflakeKey(Class<?> entity, String label) {
        TableInfo table = info(entity);
        assertThat(table.getKeyProperty()).as("%s 主键 property = id", label).isEqualTo("id");
        assertThat(table.getKeyColumn()).as("%s 主键 column = id", label).isEqualTo("id");
        assertThat(table.getIdType()).as("%s 雪花主键（BIGSERIAL 仅兜底）", label).isEqualTo(IdType.ASSIGN_ID);
    }

    @Test
    void wxConsumerMappingMatchesSchemaV6() {
        assertSnowflakeKey(WxConsumerEntity.class, "wx_consumer");
        assertColumns(WxConsumerEntity.class, Map.ofEntries(
                Map.entry("name", "name"),
                Map.entry("tokenHash", "token_hash"),
                Map.entry("secretHash", "secret_hash"),
                Map.entry("webhookUrl", "webhook_url"),
                Map.entry("remark", "remark"),
                Map.entry("isDeleted", "is_deleted"),
                Map.entry("createdAt", "created_at"),
                Map.entry("updatedAt", "updated_at")), "wx_consumer");

        Map<String, TableFieldInfo> fields = fieldsOf(WxConsumerEntity.class);
        assertThat(fields.get("tokenHash").getPropertyType())
                .as("tokenHash sha256 文本（凭据哈希入库不存明文）").isEqualTo(String.class);
        assertThat(fields.get("secretHash").getPropertyType())
                .as("secretHash sha256 文本").isEqualTo(String.class);
        assertThat(fields.get("isDeleted").getPropertyType())
                .as("is_deleted Integer").isEqualTo(Integer.class);
    }

    @Test
    void wxSubscriptionMappingMatchesSchemaV6() {
        assertSnowflakeKey(WxSubscriptionEntity.class, "wx_subscription");
        assertColumns(WxSubscriptionEntity.class, Map.ofEntries(
                Map.entry("consumerId", "consumer_id"),
                Map.entry("name", "name"),
                Map.entry("filter", "filter"),
                Map.entry("filterHash", "filter_hash"),
                Map.entry("deliveryMode", "delivery_mode"),
                Map.entry("webhookUrl", "webhook_url"),
                Map.entry("orderMode", "order_mode"),
                Map.entry("visibleSeconds", "visible_seconds"),
                Map.entry("maxAttempts", "max_attempts"),
                Map.entry("backoffBaseSeconds", "backoff_base_seconds"),
                Map.entry("enabled", "enabled"),
                Map.entry("isDeleted", "is_deleted"),
                Map.entry("createdAt", "created_at"),
                Map.entry("updatedAt", "updated_at")), "wx_subscription");

        Map<String, TableFieldInfo> fields = fieldsOf(WxSubscriptionEntity.class);
        assertThat(fields.get("filter").getPropertyType())
                .as("filter JSONB 以 String 文本透传").isEqualTo(String.class);
        assertThat(fields.get("filterHash").getPropertyType())
                .as("filterHash 规范化条件 sha256").isEqualTo(String.class);
        assertThat(fields.get("deliveryMode").getPropertyType())
                .as("deliveryMode POLL|WEBHOOK 大写字符串").isEqualTo(String.class);
        assertThat(fields.get("enabled").getPropertyType())
                .as("enabled SMALLINT → Integer").isEqualTo(Integer.class);
        assertThat(fields.get("isDeleted").getPropertyType())
                .as("is_deleted Integer").isEqualTo(Integer.class);
    }

    @Test
    void wxSubscriptionDeliveryMappingMatchesSchemaV6() {
        assertSnowflakeKey(WxSubscriptionDeliveryEntity.class, "wx_subscription_delivery");
        assertColumns(WxSubscriptionDeliveryEntity.class, Map.ofEntries(
                Map.entry("subscriptionId", "subscription_id"),
                Map.entry("consumerId", "consumer_id"),
                Map.entry("messageId", "message_id"),
                Map.entry("status", "status"),
                Map.entry("attempts", "attempts"),
                Map.entry("maxAttempts", "max_attempts"),
                Map.entry("leaseSeconds", "lease_seconds"),
                Map.entry("leaseId", "lease_id"),
                Map.entry("leaseUntil", "lease_until"),
                Map.entry("nextAttemptAt", "next_attempt_at"),
                Map.entry("responseCode", "response_code"),
                Map.entry("error", "error"),
                Map.entry("deliveredAt", "delivered_at"),
                Map.entry("createdAt", "created_at"),
                Map.entry("updatedAt", "updated_at")), "wx_subscription_delivery");

        Map<String, TableFieldInfo> fields = fieldsOf(WxSubscriptionDeliveryEntity.class);
        assertThat(fields.get("status").getPropertyType())
                .as("status PENDING|DISPATCHED|DELIVERED|DEAD 大写字符串").isEqualTo(String.class);
        assertThat(fields.get("attempts").getPropertyType())
                .as("attempts Integer（认领时 +1）").isEqualTo(Integer.class);
        assertThat(fields.get("maxAttempts").getPropertyType())
                .as("maxAttempts 入队时从订阅快照（Integer）").isEqualTo(Integer.class);
        assertThat(fields.get("leaseSeconds").getPropertyType())
                .as("leaseSeconds 入队时从订阅快照（Integer）").isEqualTo(Integer.class);
        assertThat(fields.get("leaseUntil").getPropertyType())
                .as("leaseUntil TIMESTAMPTZ → OffsetDateTime").isEqualTo(OffsetDateTime.class);
        assertThat(fields.get("nextAttemptAt").getPropertyType())
                .as("nextAttemptAt 退避调度键 → OffsetDateTime").isEqualTo(OffsetDateTime.class);
        assertThat(fields.get("deliveredAt").getPropertyType())
                .as("deliveredAt 终态时点 → OffsetDateTime").isEqualTo(OffsetDateTime.class);
        assertThat(fields)
                .as("投递队列行不设 is_deleted/终态行不删（SUBSCRIPTION_DESIGN §2.3 清理任务兜底）")
                .doesNotContainKeys("isDeleted");
    }

    @Test
    void wxSubDeliveryCursorMappingMatchesSchemaV6() {
        assertSnowflakeKey(WxSubDeliveryCursorEntity.class, "wx_sub_delivery_cursor");
        assertColumns(WxSubDeliveryCursorEntity.class, Map.ofEntries(
                Map.entry("scope", "scope"),
                Map.entry("lastMessageId", "last_message_id"),
                Map.entry("updatedAt", "updated_at")), "wx_sub_delivery_cursor");

        Map<String, TableFieldInfo> fields = fieldsOf(WxSubDeliveryCursorEntity.class);
        assertThat(fields.get("scope").getPropertyType())
                .as("scope 游标域（现阶段固定单行 'global'）").isEqualTo(String.class);
        assertThat(fields.get("lastMessageId").getPropertyType())
                .as("lastMessageId wx_message.id 高水位 → Long").isEqualTo(Long.class);
        assertThat(fields)
                .as("纯调度水位：无软删、DDL 无 created_at（SUBSCRIPTION_DESIGN §2.4）")
                .doesNotContainKeys("isDeleted", "createdAt");
    }

    // ------------------------------------------------------------ JSONB handler 契约

    /** 声明了非缺省 typeHandler 的字段（即误标/真标 JsonbTypeHandler 的字段）名清单。 */
    private static List<String> jsonbHandlerFields(Class<?> entity) {
        return Arrays.stream(entity.getDeclaredFields())
                .filter(f -> {
                    TableField ann = f.getAnnotation(TableField.class);
                    return ann != null && ann.typeHandler() != UnknownTypeHandler.class;
                })
                .map(Field::getName)
                .collect(Collectors.toList());
    }

    @Test
    void jsonbHandlerOnlyOnSubscriptionFilter() throws NoSuchFieldException {
        Field filter = WxSubscriptionEntity.class.getDeclaredField("filter");
        TableField ann = filter.getAnnotation(TableField.class);
        assertThat(ann).as("wx_subscription.filter 必须显式 @TableField（写侧 handler 入口）").isNotNull();
        assertThat(ann.typeHandler())
                .as("filter JSONB 写侧经 JsonbTypeHandler（setObject Types.OTHER，PG 驱动无 PGobject 先例）")
                .isEqualTo(JsonbTypeHandler.class);
        assertThat(WxSubscriptionEntity.class.getAnnotation(TableName.class).autoResultMap())
                .as("autoResultMap=true 是 typeHandler 读侧生效的前提（WxTaskEntity 先例同款）").isTrue();

        for (Class<?> entity : List.of(WxConsumerEntity.class,
                WxSubscriptionDeliveryEntity.class, WxSubDeliveryCursorEntity.class)) {
            assertThat(jsonbHandlerFields(entity))
                    .as("%s 无 JSONB 列，不得误标 typeHandler", entity.getSimpleName())
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------ Mapper 注解 SQL 契约（片段锚，空白归一后 substring）

    private static Method methodOf(Class<?> mapper, String name) {
        // 注意：getMethod(name) 单参形式是「按名字 + 零参数列表」查找——带参方法（如
        // claimDueWebhook(int,int)）全部 NoSuchMethodException。按名字遍历 public 方法。
        return java.util.Arrays.stream(mapper.getMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        mapper.getSimpleName() + "." + name + " 方法缺失（约定名漂移）"));
    }

    /** 读方法上的 @Select/@Update/@Insert/@Delete 文本，空白归一（多行 SQL → 单空格）后返回；无注解返回空串。 */
    private static String sqlOf(Class<?> mapper, String name) throws Exception {
        Method method = methodOf(mapper, name);
        for (Class<? extends java.lang.annotation.Annotation> type :
                List.of(Select.class, Update.class, Insert.class, Delete.class)) {
            java.lang.annotation.Annotation ann = method.getAnnotation(type);
            if (ann == null) {
                continue;
            }
            String[] value = (String[]) type.getMethod("value").invoke(ann);
            return String.join(" ", value).replaceAll("\\s+", " ");
        }
        return "";
    }

    /** 方法 → 必含 SQL 片段（SUBSCRIPTION_DESIGN §2.3/§3.2/§4/§5.3/§6 的契约点）。 */
    private static final Map<String, List<String>> REQUIRED_SQL = Map.ofEntries(
            Map.entry("claimDueWebhook", List.of(
                    "FOR UPDATE OF d SKIP LOCKED", "s.delivery_mode = 'WEBHOOK'",
                    "CASE WHEN s.order_mode = 'STRICT_FIFO' THEN 1", "ORDER BY d.next_attempt_at, d.id",
                    "RETURNING")),
            Map.entry("claimForPull", List.of(
                    "s.delivery_mode = 'POLL'", "ORDER BY d.id", "FOR UPDATE OF d SKIP LOCKED")),
            Map.entry("completeDelivered", List.of("AND status = 'DISPATCHED'", "lease_id = #{leaseId}",
                    "response_code = #{responseCode}", "error = NULL")),
            Map.entry("requeueForRetry", List.of("AND status = 'DISPATCHED'", "lease_id = #{leaseId}")),
            Map.entry("failDead", List.of("AND status = 'DISPATCHED'", "lease_id = #{leaseId}")),
            Map.entry("sweepExpiredLeases", List.of("lease_until < now()", "'PENDING'", "DEAD")),
            Map.entry("backstopEnqueue", List.of(
                    "ON CONFLICT (subscription_id, message_id) DO NOTHING", "wx_message_source_rel",
                    "include_revoked", "s.enabled = 1")),
            Map.entry("cleanupDelivered", List.of("status = 'DELIVERED'", "IN (SELECT", "LIMIT #{cap}")),
            Map.entry("upsertGlobalCursor", List.of("ON CONFLICT (scope) DO UPDATE", "GREATEST")));

    @Test
    void mapperSqlFragmentsMatchDesignV11() throws Exception {
        Class<?> delivery = WxSubscriptionDeliveryMapper.class;
        for (Map.Entry<String, List<String>> entry : REQUIRED_SQL.entrySet()) {
            Class<?> owner = entry.getKey().equals("upsertGlobalCursor")
                    ? WxSubDeliveryCursorMapper.class : delivery;
            String sql = sqlOf(owner, entry.getKey());
            assertThat(sql)
                    .as("%s.%s 注解 SQL 存在", owner.getSimpleName(), entry.getKey())
                    .isNotEmpty();
            for (String fragment : entry.getValue()) {
                assertThat(sql)
                        .as("%s.%s 缺契约片段：%s", owner.getSimpleName(), entry.getKey(), fragment)
                        .contains(fragment);
            }
        }
        assertThat(sqlOf(delivery, "claimDueWebhook"))
                .as("claimDueWebhook 是 UPDATE 认领，不得携带 INSERT 的 ON CONFLICT")
                .doesNotContain("ON CONFLICT");
    }

    // ------------------------------------------------------------ Mapper 接口契约（MapperScan 口径 + 签名抽查）

    /** 从泛型父接口解析 BaseMapper 的实体实参；无则 null。 */
    @SuppressWarnings("unchecked")
    private static Class<?> baseMapperEntityArg(Class<?> mapper) {
        for (Type t : mapper.getGenericInterfaces()) {
            if (t instanceof ParameterizedType pt && pt.getRawType() == BaseMapper.class) {
                return (Class<?>) pt.getActualTypeArguments()[0];
            }
        }
        return null;
    }

    @Test
    void mappersFollowProjectScanConvention() {
        Map<Class<?>, Class<?>> pairs = Map.of(
                WxConsumerMapper.class, WxConsumerEntity.class,
                WxSubscriptionMapper.class, WxSubscriptionEntity.class,
                WxSubscriptionDeliveryMapper.class, WxSubscriptionDeliveryEntity.class,
                WxSubDeliveryCursorMapper.class, WxSubDeliveryCursorEntity.class);
        pairs.forEach((mapper, entity) -> {
            assertThat(baseMapperEntityArg(mapper))
                    .as("%s extends BaseMapper<%s>", mapper.getSimpleName(), entity.getSimpleName())
                    .isEqualTo(entity);
            assertThat(mapper.getAnnotation(Mapper.class))
                    .as("%s 不带 @Mapper（项目口径走 @MapperScan 包扫描，WxTaskMapper 先例）",
                            mapper.getSimpleName())
                    .isNull();
        });

        MapperScan scan = WxSyncServerApplication.class.getAnnotation(MapperScan.class);
        assertThat(scan).as("应用类 @MapperScan 在位").isNotNull();
        String packages = String.join("", scan.value()) + String.join("", scan.basePackages());
        assertThat(packages)
                .as("@MapperScan 模式须覆盖 subscription.mapper（com.wechat.sync.server.**.mapper）")
                .contains("com.wechat.sync.server.**.mapper");
    }

    @Test
    void keyMapperSignaturesStayStable() {
        Method claim = methodOf(WxSubscriptionDeliveryMapper.class, "claimDueWebhook");
        assertThat(claim.getParameterCount())
                .as("claimDueWebhook 参数 = (limit, perSubInflight) 两个 int（§5.3 LIMIT/在飞上限）")
                .isEqualTo(2);
        assertThat(claim.getParameterTypes()[0]).isEqualTo(int.class);
        assertThat(claim.getParameterTypes()[1]).isEqualTo(int.class);

        Method pull = methodOf(WxSubscriptionDeliveryMapper.class, "claimForPull");
        assertThat(pull.getParameterCount())
                .as("claimForPull 参数 = (subscriptionId, max) 两个").isEqualTo(2);
        assertThat(pull.getParameterTypes()[0])
                .as("subscription_id 整型（雪花 Long 口径，int/Integer 均视为漂移前兼容）")
                .isIn(Long.class, long.class);
        assertThat(pull.getParameterTypes()[1]).isIn(int.class, Integer.class);

        Method nack = methodOf(WxSubscriptionDeliveryMapper.class, "requeueForRetry");
        assertThat(nack.getParameterCount())
                .as("requeueForRetry 参数 = (id, leaseId, backoffSeconds, responseCode, error) 五个")
                .isEqualTo(5);
        assertThat(nack.getParameterTypes()).isEqualTo(
                new Class<?>[]{long.class, String.class, int.class, Integer.class, String.class});

        Method cleanup = methodOf(WxSubscriptionDeliveryMapper.class, "cleanupDelivered");
        assertThat(cleanup.getParameterCount())
                .as("cleanupDelivered 参数 = (before 截止, cap 每轮上限) 两个").isEqualTo(2);
        assertThat(cleanup.getParameterTypes()).isEqualTo(
                new Class<?>[]{OffsetDateTime.class, int.class});

        Method cursor = methodOf(WxSubDeliveryCursorMapper.class, "upsertGlobalCursor");
        assertThat(cursor.getParameterCount())
                .as("upsertGlobalCursor 参数 = (scope, lastMessageId) 两个（scope 形参化，非硬编码）")
                .isEqualTo(2);
        assertThat(cursor.getParameterTypes()[0]).isEqualTo(String.class);
        assertThat(cursor.getParameterTypes()[1]).isIn(long.class, Long.class);
    }
}
