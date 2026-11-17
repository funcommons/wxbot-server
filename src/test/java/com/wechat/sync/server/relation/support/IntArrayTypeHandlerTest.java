package com.wechat.sync.server.relation.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

/** {@code wx_contact.tag_ids} 的 int4[] TypeHandler：写侧装箱 createArrayOf、读侧三形状收敛。 */
@ExtendWith(MockitoExtension.class)
class IntArrayTypeHandlerTest {

    private final IntArrayTypeHandler handler = new IntArrayTypeHandler();

    @Mock private PreparedStatement ps;
    @Mock private Connection connection;
    @Mock private ResultSet rs;
    @Mock private CallableStatement cs;
    @Mock private Array array;

    /** 写侧：int[] 装箱为 Integer[] 后 createArrayOf("int4", …)，Array 直接交给 setArray（不 free）。 */
    @Test
    void setNonNullParameterBoxesIntsIntoPgArrayOfInt4() throws SQLException {
        given(ps.getConnection()).willReturn(connection);
        given(connection.createArrayOf(eq("int4"), any(Object[].class))).willReturn(array, mock(Array.class));

        handler.setNonNullParameter(ps, 3, new int[]{1, 2, 3}, null);
        handler.setNonNullParameter(ps, 1, new int[0], null);   // 空数组也是合法写入

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(connection, times(2)).createArrayOf(eq("int4"), captor.capture());
        assertThat(captor.getAllValues().get(0)).containsExactly(1, 2, 3);
        assertThat(captor.getAllValues().get(1)).isEmpty();
        verify(ps).setArray(3, array);
        verify(array, never()).free();
    }

    /** createArrayOf 失败（驱动不识别 int4 等）原样上抛 SQLException，不落半截参数。 */
    @Test
    void setNonNullParameterPropagatesDriverFailure() throws SQLException {
        given(ps.getConnection()).willReturn(connection);
        given(connection.createArrayOf(anyString(), any(Object[].class)))
                .willThrow(new SQLException("datatype unknown"));

        assertThatThrownBy(() -> handler.setNonNullParameter(ps, 1, new int[]{1}, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("datatype unknown");
        verify(ps, never()).setArray(anyInt(), any());
    }

    /** 读侧形状一：pgjdbc 返回 int[] 原语数组 → 原样返回（列名重载），且不 free。 */
    @Test
    void readByColumnNameReturnsPrimitiveArrayAsIs() throws SQLException {
        int[] primitive = {5, 6};
        given(rs.getArray("tag_ids")).willReturn(array);
        given(array.getArray()).willReturn(primitive);

        assertThat(handler.getNullableResult(rs, "tag_ids")).isSameAs(primitive);
        verify(array, never()).free();
    }

    /** 读侧形状二：Integer[]（含 null 元素）→ 收敛为 int[]，null 归 0（结果集列序号重载）。 */
    @Test
    void readConvergesBoxedArrayWithNullElement() throws SQLException {
        given(rs.getArray(2)).willReturn(array);
        given(array.getArray()).willReturn(new Integer[]{null, 7, 9});

        assertThat(handler.getNullableResult(rs, 2)).containsExactly(0, 7, 9);
    }

    /** 存储过程出参同样收敛（CallableStatement 重载，Integer[] 形状）。 */
    @Test
    void readFromCallableStatementConvergesBoxedArray() throws SQLException {
        given(cs.getArray(1)).willReturn(array);
        given(array.getArray()).willReturn(new Integer[]{3});

        assertThat(handler.getNullableResult(cs, 1)).containsExactly(3);
    }

    /** 读侧形状三：单元素 Number（驱动降级返回标量）→ 包成单元素数组。 */
    @Test
    void readWrapsSingleNumberShapeIntoSingleton() throws SQLException {
        given(rs.getArray("tag_ids")).willReturn(array);
        given(array.getArray()).willReturn(11L);

        assertThat(handler.getNullableResult(rs, "tag_ids")).containsExactly(11);
    }

    /** NULL 列 / 未知形状（非 int[]、非 Integer[]、非 Number）→ 统一归 int[0]，不抛。 */
    @Test
    void readYieldsEmptyArrayForNullColumnAndUnknownShape() throws SQLException {
        given(rs.getArray("tag_ids")).willReturn(null, array);
        given(array.getArray()).willReturn(new String[]{"not-a-number"});

        assertThat(handler.getNullableResult(rs, "tag_ids")).isEmpty();
        assertThat(handler.getNullableResult(rs, "tag_ids")).isEmpty();
    }

    /** getArray 的 SQLException 不被吞（声明上抛，交给 MyBatis 统一包装）。 */
    @Test
    void readPropagatesResultSetFailure() throws SQLException {
        given(rs.getArray("tag_ids")).willThrow(new SQLException("closed result set"));

        assertThatThrownBy(() -> handler.getNullableResult(rs, "tag_ids"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("closed result set");
    }
}
