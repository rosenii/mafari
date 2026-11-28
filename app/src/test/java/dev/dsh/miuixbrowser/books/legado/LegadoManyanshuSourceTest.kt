/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.legado

import dev.dsh.miuixbrowser.books.data.BookFeedParser
import dev.dsh.miuixbrowser.books.data.legado.LegadoSource
import dev.dsh.miuixbrowser.books.data.legado.LegadoSourceParser
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实书源「蔓延书城」（src/test/resources/legado/manyanshu.json）验证导入路径：
 * 解析、识别为 Legado、发现入口。搜索 / 目录 / 正文需要联网和 Android 的 BitmapFactory（验证码识别），
 * 单元测试跑不了，要在设备上用「书源搜索 / 发现」手动验证。
 */
class LegadoManyanshuSourceTest {
    private fun text(): String =
        javaClass.getResourceAsStream("/legado/manyanshu.json")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun source(): LegadoSource {
        val root = JSONTokener(text().trim().trimStart('\uFEFF')).nextValue()
        return LegadoSourceParser.parse(root).single()
    }

    @Test fun detectedAsLegado() {
        val root = JSONTokener(text().trim().trimStart('\uFEFF')).nextValue()
        assertTrue(LegadoSourceParser.looksLikeLegado(root))
    }

    @Test fun feedParserReturnsSourcesNotBooks() {
        val feed = BookFeedParser.parse(text())
        assertTrue(feed.books.isEmpty())
        assertEquals(1, feed.sources.size)
        assertTrue(feed.name.contains("蔓延书城"))
    }

    @Test fun basicFieldsAndRules() {
        val s = source()
        assertEquals("https://www.manyanshu.com", s.url)
        assertEquals(0, s.type)
        assertEquals("1/1500", s.concurrentRate)
        assertTrue(s.canSearch)
        assertTrue(s.canExplore)
        assertNotNull(s.search["bookList"])
        assertNotNull(s.bookInfo["name"])
        assertNotNull(s.toc["chapterList"])
        assertNotNull(s.content["content"])
    }

    @Test fun exploreKindsAreNineCategories() {
        val kinds = source().engine.exploreKinds()
        assertEquals(9, kinds.size)
        assertEquals("玄幻", kinds.first().title)
        assertEquals("🔥热门榜", kinds.last().title)
        assertTrue(kinds.all { it.url.contains("{{page}}") })
    }

    /** 走 Rhino 渲染 `{{page}}`；单独一个用例，出问题时不影响上面的解析断言。 */
    @Test fun explorePageTemplateIsRendered() {
        val s = source()
        val kind = s.engine.exploreKinds().first()
        val u = s.engine.analyzeUrl(kind.url, null, 2, s.url)
        assertEquals("https://www.manyanshu.com/4qi6r1/2.html", u.url)
    }
}
