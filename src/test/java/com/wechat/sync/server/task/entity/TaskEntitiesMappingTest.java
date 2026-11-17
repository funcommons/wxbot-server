package com.wechat.sync.server.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wechat.sync.server.account.entity.WxRegCodeEntity;
import com.wechat.sync.server.support.MpTestTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v5 四实体（wx_task / wx_task_run / wx_task_schedule / wx_reg_code）的 MyBatis Plus 映射单测：
 * 表名、property→column snake_case、雪花 IdType、JSONB=String 透传 / TIME=LocalTime /
 * TIMESTAMPTZ=OffsetDateTime、is_deleted 存在且 Integer（对齐 schema.sql v5 增量块，TASK_DISPATCH §2）。
 */
class TaskEntitiesMappingTest {

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxTaskEntity.class, WxTaskRunEntity.class,
                WxTaskScheduleEntity.class, WxRegCodeEntity.class);
    }

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
    void wxTaskMappingMatchesSchemaV5() {
        assertSnowflakeKey(WxTaskEntity.class, "wx_task");
        assertColumns(WxTaskEntity.class, Map.ofEntries(
                Map.entry("taskType", "task_type"),
                Map.entry("account", "account"),
                Map.entry("target", "target"),
                Map.entry("priority", "priority"),
                Map.entry("status", "status"),
                Map.entry("attempts", "attempts"),
                Map.entry("maxAttempts", "max_attempts"),
                Map.entry("leaseId", "lease_id"),
                Map.entry("leaseUntil", "lease_until"),
                Map.entry("payload", "payload"),
                Map.entry("result", "result"),
                Map.entry("lastError", "last_error"),
                Map.entry("dispatchedAt", "dispatched_at"),
                Map.entry("finishedAt", "finished_at"),
                Map.entry("scheduleId", "schedule_id"),
                Map.entry("isDeleted", "is_deleted"),
                Map.entry("createdAt", "created_at"),
                Map.entry("updatedAt", "updated_at")), "wx_task");

        Map<String, TableFieldInfo> fields = fieldsOf(WxTaskEntity.class);
        assertThat(fields.get("payload").getPropertyType())
                .as("payload JSONB 以 String 文本透传").isEqualTo(String.class);
        assertThat(fields.get("result").getPropertyType())
                .as("result JSONB 以 String 文本透传").isEqualTo(String.class);
        assertThat(fields.get("leaseUntil").getPropertyType())
                .as("leaseUntil TIMESTAMPTZ → OffsetDateTime").isEqualTo(OffsetDateTime.class);
        assertThat(fields.get("status").getPropertyType())
                .as("status 七态大写字符串").isEqualTo(String.class);
        assertThat(fields.get("isDeleted").getPropertyType())
                .as("is_deleted Integer").isEqualTo(Integer.class);
    }

    @Test
    void wxTaskRunLogTableMapping() {
        assertSnowflakeKey(WxTaskRunEntity.class, "wx_task_run");
        assertColumns(WxTaskRunEntity.class, Map.ofEntries(
                Map.entry("taskId", "task_id"),
                Map.entry("attemptNo", "attempt_no"),
                Map.entry("leaseId", "lease_id"),
                Map.entry("account", "account"),
                Map.entry("startedAt", "started_at"),
                Map.entry("finishedAt", "finished_at"),
                Map.entry("outcome", "outcome"),
                Map.entry("result", "result"),
                Map.entry("error", "error"),
                Map.entry("createdAt", "created_at")), "wx_task_run");

        Map<String, TableFieldInfo> fields = fieldsOf(WxTaskRunEntity.class);
        assertThat(fields.get("result").getPropertyType())
                .as("result JSONB 以 String 文本透传").isEqualTo(String.class);
        assertThat(fields.get("startedAt").getPropertyType())
                .as("startedAt TIMESTAMPTZ → OffsetDateTime").isEqualTo(OffsetDateTime.class);
        assertThat(fields)
                .as("日志表无 is_deleted/updated_at（mc-database-spec 日志表约定）")
                .doesNotContainKeys("isDeleted", "updatedAt");
    }

    @Test
    void wxTaskScheduleMappingMatchesSchemaV5() {
        assertSnowflakeKey(WxTaskScheduleEntity.class, "wx_task_schedule");
        assertColumns(WxTaskScheduleEntity.class, Map.ofEntries(
                Map.entry("account", "account"),
                Map.entry("taskType", "task_type"),
                Map.entry("target", "target"),
                Map.entry("intervalSeconds", "interval_seconds"),
                Map.entry("dailyAt", "daily_at"),
                Map.entry("priority", "priority"),
                Map.entry("payload", "payload"),
                Map.entry("activityWindowDays", "activity_window_days"),
                Map.entry("enabled", "enabled"),
                Map.entry("isDeleted", "is_deleted"),
                Map.entry("lastMaterializedAt", "last_materialized_at"),
                Map.entry("nextMaterializeAt", "next_materialize_at"),
                Map.entry("createdAt", "created_at"),
                Map.entry("updatedAt", "updated_at")), "wx_task_schedule");

        Map<String, TableFieldInfo> fields = fieldsOf(WxTaskScheduleEntity.class);
        assertThat(fields.get("dailyAt").getPropertyType())
                .as("daily_at PG TIME → java.time.LocalTime").isEqualTo(LocalTime.class);
        assertThat(fields.get("payload").getPropertyType())
                .as("payload JSONB 以 String 文本透传").isEqualTo(String.class);
        assertThat(fields.get("nextMaterializeAt").getPropertyType())
                .as("nextMaterializeAt TIMESTAMPTZ → OffsetDateTime").isEqualTo(OffsetDateTime.class);
        assertThat(fields.get("isDeleted").getPropertyType())
                .as("is_deleted Integer").isEqualTo(Integer.class);
    }

    @Test
    void wxRegCodeMappingMatchesSchemaV5() {
        assertSnowflakeKey(WxRegCodeEntity.class, "wx_reg_code");
        assertColumns(WxRegCodeEntity.class, Map.ofEntries(
                Map.entry("codeHash", "code_hash"),
                Map.entry("account", "account"),
                Map.entry("label", "label"),
                Map.entry("expiresAt", "expires_at"),
                Map.entry("usedAt", "used_at"),
                Map.entry("usedBy", "used_by"),
                Map.entry("isDeleted", "is_deleted"),
                Map.entry("createdAt", "created_at"),
                Map.entry("updatedAt", "updated_at")), "wx_reg_code");

        Map<String, TableFieldInfo> fields = fieldsOf(WxRegCodeEntity.class);
        assertThat(fields.get("expiresAt").getPropertyType())
                .as("expiresAt TIMESTAMPTZ → OffsetDateTime").isEqualTo(OffsetDateTime.class);
        assertThat(fields.get("codeHash").getPropertyType())
                .as("codeHash sha256 文本").isEqualTo(String.class);
        assertThat(fields.get("isDeleted").getPropertyType())
                .as("is_deleted Integer").isEqualTo(Integer.class);
    }
}
