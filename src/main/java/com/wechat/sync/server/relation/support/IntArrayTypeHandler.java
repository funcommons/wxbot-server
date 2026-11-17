package com.wechat.sync.server.relation.support;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL {@code INT[]}（int4[]）↔ {@code int[]} TypeHandler。
 *
 * <p>写侧：{@code Connection.createArrayOf("int4", Integer[])} 造 {@link Array} 后
 * {@link PreparedStatement#setArray}（pgjdbc 把参数序列化推迟到 execute，故此处不 free）。
 * 读侧：{@code ResultSet.getArray(...).getArray()}，pgjdbc 对 int4[] 返回 {@code int[]} 或
 * {@code Integer[]}，两种形状都收敛成 {@code int[]}（列 NOT NULL DEFAULT '{}'，空数组/null 统一归 {@code int[0]}）。
 *
 * <p>仅由 {@code @TableField(typeHandler = IntArrayTypeHandler.class)} +
 * {@code @TableName(autoResultMap = true)} 引用（wx_contact.tagIds），无需注册为全局 Bean。
 * 标签必须走 {@code INT[] + intarray}，禁 JSONB / varchar[] / bigint[]（DB_DESIGN §7）。
 */
@MappedTypes(int[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class IntArrayTypeHandler extends BaseTypeHandler<int[]> {

    /** PG 数组类型名（int4 = integer[] 的内部名） */
    private static final String PG_ARRAY_TYPE = "int4";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, int[] parameter, JdbcType jdbcType)
            throws SQLException {
        Integer[] boxed = new Integer[parameter.length];
        for (int k = 0; k < parameter.length; k++) {
            boxed[k] = parameter[k];
        }
        ps.setArray(i, ps.getConnection().createArrayOf(PG_ARRAY_TYPE, boxed));
    }

    @Override
    public int[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toIntArray(rs.getArray(columnName));
    }

    @Override
    public int[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toIntArray(rs.getArray(columnIndex));
    }

    @Override
    public int[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toIntArray(cs.getArray(columnIndex));
    }

    /** 任意驱动形状（int[] / Integer[] / 单元素 Number）→ int[]；null → 空数组。 */
    private int[] toIntArray(Array array) throws SQLException {
        if (array == null) {
            return new int[0];
        }
        Object raw = array.getArray();
        if (raw instanceof int[] primitive) {
            return primitive;
        }
        if (raw instanceof Integer[] boxed) {
            int[] result = new int[boxed.length];
            for (int k = 0; k < boxed.length; k++) {
                result[k] = boxed[k] == null ? 0 : boxed[k];
            }
            return result;
        }
        if (raw instanceof Number number) {
            return new int[]{number.intValue()};
        }
        return new int[0];
        // 不调 array.free()：pgjdbc 的 PgArray.free() 仅清内部引用，结果集消费完后交给 GC；
        // 写侧更不能 free（pgjdbc 把参数序列化推迟到 execute）。
    }
}
