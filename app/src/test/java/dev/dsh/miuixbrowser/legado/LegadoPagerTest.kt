/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.legado

import dev.dsh.miuixbrowser.data.legado.LegadoBook
import dev.dsh.miuixbrowser.data.legado.LegadoPager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索 / 发现弹窗用的分页累加器：去重、页码递增、「新增 0 本」即没有下一页。 */
class LegadoPagerTest {
    private fun book(url: String, name: String = url) = LegadoBook(name = name, author = "", bookUrl = url)

    @Test fun startsAtPageOneAndNotExhausted() {
        val p = LegadoPager()
        assertEquals(1, p.nextPage)
        assertFalse(p.exhausted)
        assertTrue(p.items.isEmpty())
    }

    @Test fun addsPagesInOrderAndAdvancesPage() {
        val p = LegadoPager()
        assertEquals(2, p.add(listOf(book("a"), book("b"))))
        assertEquals(2, p.nextPage)
        assertEquals(1, p.add(listOf(book("c"))))
        assertEquals(3, p.nextPage)
        assertEquals(listOf("a", "b", "c"), p.items.map { it.bookUrl })
        assertFalse(p.exhausted)
    }

    @Test fun duplicatesAreDroppedAndCountOnlyNewOnes() {
        val p = LegadoPager()
        p.add(listOf(book("a"), book("b")))
        assertEquals(1, p.add(listOf(book("b"), book("c"), book("c"))))
        assertEquals(listOf("a", "b", "c"), p.items.map { it.bookUrl })
    }

    /** 单页书源（翻页返回同一批结果）：第二页没有新书 → 没有更多了 */
    @Test fun samePageAgainMeansExhausted() {
        val p = LegadoPager()
        p.add(listOf(book("a"), book("b")))
        assertFalse(p.exhausted)
        assertEquals(0, p.add(listOf(book("a"), book("b"))))
        assertTrue(p.exhausted)
    }

    @Test fun emptyFirstPageIsExhausted() {
        val p = LegadoPager()
        assertEquals(0, p.add(emptyList()))
        assertTrue(p.exhausted)
        assertTrue(p.items.isEmpty())
    }

    @Test fun resetStartsOver() {
        val p = LegadoPager()
        p.add(listOf(book("a")))
        p.add(listOf(book("a")))
        assertTrue(p.exhausted)
        p.reset()
        assertEquals(1, p.nextPage)
        assertFalse(p.exhausted)
        assertTrue(p.items.isEmpty())
        // 重置后同一本书可以再进来
        assertEquals(1, p.add(listOf(book("a"))))
    }
}
