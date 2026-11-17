-- ============================================================================
-- framework4j 基础设施表（框架自有，非业务表）
--
-- 本文件是 framework4j 中间件自身的运行时表，幂等（IF NOT EXISTS），可重复执行。
-- 它 **不属于** 业务 schema.sql —— 那是 wx_ 业务表（wx_account / wx_message 等 13 表）
-- 的设计权威（见 ../../../schema.sql + DB_DESIGN.md），两者互不归属、互不引用。
--
-- 表结构来源（严格对齐，勿擅自增删列/改类型）：
--   framework4j-audit/src/main/java/fun/commons/framework4j/audit/service/JdbcAuditSink.java
--     类 javadoc「建表 DDL」段（@since 2.2.0）
--   与 framework4j/docs/modules/audit.md「生产 DDL (PostgreSQL)」段一致
--
-- 框架对该表的读写口径：
--   写：INSERT (action, target_type, target_id, actor, result, error_message,
--               args_json, result_json, ip, user_agent, trace_id,
--               timestamp, prev_hash, hash) —— append-only
--   读：SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1  （启动期取链尾 hash，
--      空表 → EmptyResultDataAccessException → fallback GENESIS）
--   hash 列 UNIQUE：重复 hash 写入抛 DuplicateKeyException，即 hash chain 冲突信号。
--
-- 备注：`timestamp` 裸用作列名在 PostgreSQL 可解析（已实测），与框架 SQL 的裸写法
--       保持一致，故不加引号。
-- ============================================================================

CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGSERIAL PRIMARY KEY,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(64)  NOT NULL,
    target_id     VARCHAR(64),
    actor         VARCHAR(64),
    result        VARCHAR(16)  NOT NULL,
    error_message TEXT,
    args_json     TEXT,
    result_json   TEXT,
    ip            VARCHAR(45),          -- IPv6 最长 45
    user_agent    VARCHAR(255),         -- JdbcAuditSink 写入前已截断到 255
    trace_id      VARCHAR(64),
    timestamp     TIMESTAMPTZ NOT NULL,
    prev_hash     VARCHAR(128) NOT NULL,
    hash          VARCHAR(128) NOT NULL UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_audit_target     ON audit_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_actor_time ON audit_log (actor, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_time       ON audit_log (timestamp DESC);

COMMENT ON TABLE audit_log                IS 'framework4j-audit 审计日志（hash chain 防篡改，append-only）；结构由 framework4j JdbcAuditSink 定义';
COMMENT ON COLUMN audit_log.id            IS '自增主键；hash chain 链序即 id 序（loadLastHash 按 id DESC 取链尾）';
COMMENT ON COLUMN audit_log.action        IS '操作名（@Auditable.action）';
COMMENT ON COLUMN audit_log.target_type   IS '目标对象类型（@Auditable.targetType）';
COMMENT ON COLUMN audit_log.target_id     IS '目标对象 ID（targetIdSpel 求值结果）';
COMMENT ON COLUMN audit_log.actor         IS '操作人；取自 actor-header（默认 X-User-Id），须经网关覆写才可信';
COMMENT ON COLUMN audit_log.result        IS '执行结果（OK / FAIL 等）';
COMMENT ON COLUMN audit_log.error_message IS '失败信息';
COMMENT ON COLUMN audit_log.args_json     IS '入参 JSON（logArgs=true 时记录）';
COMMENT ON COLUMN audit_log.result_json   IS '返回值 JSON';
COMMENT ON COLUMN audit_log.ip            IS '客户端 IP；取自 ip-header（默认 X-Forwarded-For 优先）';
COMMENT ON COLUMN audit_log.user_agent    IS 'User-Agent，写入前截断至 255';
COMMENT ON COLUMN audit_log.trace_id      IS '链路追踪 ID（X-Trace-Id）';
COMMENT ON COLUMN audit_log.timestamp     IS '审计时间（AuditRecord 时间戳，TIMESTAMPTZ）';
COMMENT ON COLUMN audit_log.prev_hash     IS '上一条 hash（链头为 GENESIS）';
COMMENT ON COLUMN audit_log.hash          IS '本条 hash = SHA-256(prev_hash || 记录摘要)；UNIQUE 为链冲突检测';
