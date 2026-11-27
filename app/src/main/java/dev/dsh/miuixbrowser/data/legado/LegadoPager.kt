/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data.legado

/**
 * 搜索 / 发现结果的分页累加器：按 [LegadoBook.bookUrl] 去重，并判断还有没有下一页。
 * 不少书源的搜索只有一页（翻页后返回同一批结果），所以「新增 0 本」就当作没有下一页。
 * 纯逻辑、不依赖 Android，可直接单测；非线程安全，只在 UI 线程使用。
 */
class LegadoPager {
    private val seen = HashSet<String>()
    private val all = ArrayList<LegadoBook>()

    /** 已累计的结果（按到达顺序） */
    val items: List<LegadoBook> get() = all

    /** 下一次要请求的页码，从 1 开始 */
    var nextPage: Int = 1
        private set

    /** 没有更多页了 */
    var exhausted: Boolean = false
        private set

    fun reset() {
        seen.clear()
        all.clear()
        nextPage = 1
        exhausted = false
    }

    /** 加入一页结果，返回其中新增（未出现过）的本数。 */
    fun add(page: List<LegadoBook>): Int {
        var added = 0
        for (b in page) {
            if (seen.add(b.bookUrl)) {
                all.add(b)
                added++
            }
        }
        nextPage++
        if (added == 0) exhausted = true
        return added
    }
}
