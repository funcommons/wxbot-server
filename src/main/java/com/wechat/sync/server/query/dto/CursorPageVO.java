package com.wechat.sync.server.query.dto;

import java.util.List;

/**
 * Cursor 分页响应体（API_DESIGN §2.4：list / next_cursor / has_more），只用于
 * GET /v1/messages 与 POST /v1/messages/search（海量无限滚动）；Offset 接口禁用本结构。
 *
 * <p>口径：查询取 {@code limit + 1} 条，{@code hasMore = 命中数 > limit}（多取的那条不返回，
 * 裁剪到 limit）；{@code nextCursor} 取裁剪后最后一条的 {@code (create_time, id)} 位点
 * （见 {@code CursorCodec}），hasMore=false 时为 null。
 */
public record CursorPageVO<T>(List<T> list, String nextCursor, boolean hasMore) {
}
