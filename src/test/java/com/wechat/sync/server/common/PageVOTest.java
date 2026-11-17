package com.wechat.sync.server.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Offset 分页体（API_DESIGN §2.4）：边界钳制 / has_more 口径 / 元素映射。 */
class PageVOTest {

    @Test
    void ofClampsPageAndSizeAndComputesHasMore() {
        // of(list, total, page, pageSize)：page/size 低于下限 → 钳到 1/20；total=25 > 首页 20 条 → has_more
        PageVO<String> defaults = PageVO.of(List.of(), 25, 0, 0);
        assertThat(defaults.page()).isEqualTo(1);
        assertThat(defaults.pageSize()).isEqualTo(20);
        assertThat(defaults.hasMore()).isTrue();

        // size 999 钳到 MAX_PAGE_SIZE=200；2*200=400 < 500 → has_more
        PageVO<String> capped = PageVO.of(List.of(), 500, 2, 999);
        assertThat(capped.pageSize()).isEqualTo(PageVO.MAX_PAGE_SIZE);
        assertThat(capped.hasMore()).isTrue();

        // 边界：safePage*safeSize < total（20 < 21）→ true；== / > 时 → false
        assertThat(PageVO.of(List.of("a"), 21, 1, 20).hasMore()).isTrue();
        assertThat(PageVO.of(List.of("a", "b"), 2, 1, 20).hasMore()).isFalse();
    }

    @Test
    void mapConvertsElementTypes() {
        PageVO<String> vo = PageVO.map(List.of(1, 2, 3), 3, 1, 20, String::valueOf);
        assertThat(vo.list()).containsExactly("1", "2", "3");
        assertThat(vo.total()).isEqualTo(3);
        assertThat(vo.hasMore()).isFalse();
    }
}
