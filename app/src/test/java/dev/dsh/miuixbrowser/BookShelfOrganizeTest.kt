/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.data.Book
import dev.dsh.miuixbrowser.data.BookCategory
import dev.dsh.miuixbrowser.data.BookShelf
import dev.dsh.miuixbrowser.data.BookSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书架的搜索 / 排序 / 分类过滤，以及分类名校验。 */
class BookShelfOrganizeTest {
    private fun book(id: String, title: String, author: String = "", added: Long = 0, progress: Float = 0f, category: String = "") =
        Book(id = id, title = title, author = author, fileName = "book.txt", addedAt = added, progress = progress, category = category)

    private val shelf = listOf(
        book("1", "apple", "Zed", added = 10, progress = 0.5f, category = "c1"),
        book("2", "Banana", "amy", added = 30, progress = 0f, category = "c2"),
        book("3", "cherry", "Bob", added = 20, progress = 1f),
    )

    private fun ids(l: List<Book>) = l.map { it.id }

    @Test fun blankQueryKeepsEverything() {
        assertEquals(3, BookShelf.visible(shelf, "   ", null, BookSort.ADDED, true).size)
    }

    @Test fun searchMatchesTitleOrAuthorIgnoringCase() {
        assertEquals(listOf("2"), ids(BookShelf.visible(shelf, "BANAN", null, BookSort.ADDED, true)))
        assertEquals(listOf("2"), ids(BookShelf.visible(shelf, "amy", null, BookSort.ADDED, true)))
        assertTrue(BookShelf.visible(shelf, "nothing", null, BookSort.ADDED, true).isEmpty())
    }

    @Test fun categoryFilter() {
        assertEquals(listOf("1"), ids(BookShelf.visible(shelf, "", "c1", BookSort.ADDED, true)))
        // 分类和搜索叠加
        assertTrue(BookShelf.visible(shelf, "banana", "c1", BookSort.ADDED, true).isEmpty())
    }

    @Test fun sortByAddedDefaultsToNewestFirst() {
        assertEquals(listOf("2", "3", "1"), ids(BookShelf.visible(shelf, "", null, BookSort.ADDED, true)))
        assertEquals(listOf("1", "3", "2"), ids(BookShelf.visible(shelf, "", null, BookSort.ADDED, false)))
    }

    @Test fun sortByTitleIgnoresCase() {
        assertEquals(listOf("1", "2", "3"), ids(BookShelf.visible(shelf, "", null, BookSort.TITLE, false)))
        assertEquals(listOf("3", "2", "1"), ids(BookShelf.visible(shelf, "", null, BookSort.TITLE, true)))
    }

    @Test fun sortByAuthorAndProgress() {
        assertEquals(listOf("2", "3", "1"), ids(BookShelf.visible(shelf, "", null, BookSort.AUTHOR, false)))
        assertEquals(listOf("3", "1", "2"), ids(BookShelf.visible(shelf, "", null, BookSort.PROGRESS, true)))
    }

    @Test fun ties_fallBackToNewestFirst() {
        val same = listOf(book("a", "x", added = 1), book("b", "x", added = 2))
        assertEquals(listOf("b", "a"), ids(BookShelf.visible(same, "", null, BookSort.TITLE, false)))
    }

    @Test fun sortIdsRoundTripAndFallBack() {
        BookSort.entries.forEach { assertEquals(it, BookSort.fromId(it.id)) }
        assertEquals(BookSort.ADDED, BookSort.fromId(99))
    }

    @Test fun categoryNameRules() {
        val cats = listOf(BookCategory("c1", "Fantasy"), BookCategory("c2", "科幻"))
        assertFalse(BookShelf.isCategoryNameFree(cats, "   "))
        assertFalse(BookShelf.isCategoryNameFree(cats, " fantasy "))
        assertFalse(BookShelf.isCategoryNameFree(cats, "科幻"))
        assertTrue(BookShelf.isCategoryNameFree(cats, "悬疑"))
        // 改名时可以保留自己的名字（只改大小写）
        assertTrue(BookShelf.isCategoryNameFree(cats, "FANTASY", exceptId = "c1"))
        assertFalse(BookShelf.isCategoryNameFree(cats, "科幻", exceptId = "c1"))
    }
}
