package com.wechat.sync.server.common;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL {@code jsonb} ↔ {@code String} TypeHandler（v5 任务表 payload/result 统一口径）。
 *
 * <p>为什��需要：实体字段是 String（文本透传，WxContactEntity.attributes 先例），JDBC 落库时
 * {@code setString} 把参数标成 varchar，PG 对 {@code jsonb} 列拒收 varchar 表达式
 * （{@code column "payload" is of type jsonb but expression is of type character varying}）。
 * 写侧用 {@code setObject(i, json, Types.OTHER)}——PG 驱动把 String+OTHER 按「unknown 类型」
 * 发送，服务端按目标列自动落 jsonb（PG JDBC 经典等价写法；不 import {@code PGobject}，
 * 因为 pom 里 postgresql 驱动是 runtime scope，main 编译期不可见）；读侧 {@code getString}
 * 天然返回 jsonb 文本，无需转换。自定义 SQL 里的等价写法是 {@code #{p}::jsonb} 显式 cast
 * （WxTaskMapper 物化 INSERT 用它），两条路并存、语义一致：能写 wrapper 的走本 handler，
 * 能改 SQL 的走 cast。
 *
 * <p>引用方式与 {@code relation/support/IntArrayTypeHandler} 同款：实体字段
 * {@code @TableField(typeHandler = JsonbTypeHandler.class)} + 类上
 * {@code @TableName(autoResultMap = true)}，不注册全局 Bean；LambdaUpdateWrapper 的
 * {@code .set(...)} 路径用第三参 mapping 串 {@code "typeHandler=..."} 指定。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
