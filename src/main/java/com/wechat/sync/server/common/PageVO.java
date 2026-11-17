package com.wechat.sync.server.common;

import java.util.List;
import java.util.function.Function;

/**
 * Offset 分页响应体（API_DESIGN §2.4：list / total / page / page_size / has_more）。
 *
 * <p>仅供管理类小数据量接口（accounts / ingest-batches 等）；消息流走 Cursor 分页，禁用本结构。
 *
 * @param list     当前页数据
 * @param total    满足条件的总条数
 * @param page     当前页码（从 1 起）
 * @param pageSize 每页条数
 * @param hasMore  是否还有下一页
 */
public record PageVO<T>(List<T> list, long total, long page, long pageSize, boolean hasMore) {

    /** 单页上限，与 {@code MybatisPlusConfig} 的 PaginationInnerInterceptor.maxLimit 保持一致。 */
    public static final long MAX_PAGE_SIZE = 200;

    /** 缺省页大小（pageSize 非法时兜底）。 */
    public static final long DEFAULT_PAGE_SIZE = 20;

    /**
     * 分页参数钳制的单一入口，供所有 offset 分页接口复用：{@code page < 1 → 1}；
     * {@code pageSize <= 0 → 20}；上限 {@link #MAX_PAGE_SIZE}。
     *
     * @param page     请求页码
     * @param pageSize 请求页大小
     * @return 钳制后的 {@code (page, pageSize)}
     */
    public static PageParams clamp(long page, long pageSize) {
        long safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        long safePage = page <= 0 ? 1 : page;
        return new PageParams(safePage, safeSize);
    }

    /** 钳制结果：{@code page} 从 1 起、{@code pageSize} 落在 {@code [1, MAX_PAGE_SIZE]}。 */
    public record PageParams(long page, long pageSize) {
    }

    public static <T> PageVO<T> of(List<T> list, long total, long page, long pageSize) {
        PageParams params = clamp(page, pageSize);
        boolean hasMore = params.page() * params.pageSize() < total;
        return new PageVO<>(list, total, params.page(), params.pageSize(), hasMore);
    }

    /** 元素类型转换（实体 → VO）。 */
    public static <S, T> PageVO<T> map(List<S> source, long total, long page, long pageSize,
                                       Function<S, T> mapper) {
        return of(source.stream().map(mapper).toList(), total, page, pageSize);
    }
}
