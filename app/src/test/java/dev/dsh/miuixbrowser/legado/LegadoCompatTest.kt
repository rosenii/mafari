/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.legado

import dev.dsh.miuixbrowser.data.BookFeedParser
import dev.dsh.miuixbrowser.data.legado.LegadoSourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书源合集包装、searchKey 占位符等兼容性补充。 */
class LegadoCompatTest {
    private fun sourceJson(name: String, url: String) = """
        {
          "bookSourceName": "$name",
          "bookSourceUrl": "$url",
          "bookSourceType": 0,
          "enabled": true,
          "enabledExplore": false,
          "searchUrl": "$url/search?q=searchKey&page={{page}}",
          "ruleSearch": {"bookList": ".item", "name": "a@text", "bookUrl": "a@href"}
        }
    """.trimIndent()

    @Test fun parsesBookSourcesWrapper() {
        val text = """{"bookSources":[${sourceJson("源一", "https://a.example.com")},${sourceJson("源二", "https://b.example.com")}]}"""
        val feed = BookFeedParser.parse(text)
        assertTrue(feed.books.isEmpty())
        assertEquals(2, feed.sources.size)
        assertEquals(listOf("源一", "源二"), feed.sources.map { it.name })
        assertTrue(feed.sources[0].canSearch)
        // enabledExplore = false → 不参与发现
        assertTrue(!feed.sources[0].canExplore)
    }

    @Test fun searchKeyPlaceholderIsEncoded() {
        val root = org.json.JSONTokener(sourceJson("源", "https://a.example.com")).nextValue()
        val s = LegadoSourceParser.parse(root).single()
        val u = s.engine.analyzeUrl(s.searchUrl!!, "三体", 2, s.url)
        assertEquals("https://a.example.com/search?q=%E4%B8%89%E4%BD%93&page=2", u.url)
    }

    @Test fun disabledSourceIsNotSearchable() {
        val text = """{"bookSourceName":"停用的源","bookSourceUrl":"https://c.example.com","enabled":false,"searchUrl":"https://c.example.com/s?q={{key}}","ruleSearch":{"bookList":".item","name":"a@text","bookUrl":"a@href"}}"""
        val s = BookFeedParser.parse(text).sources.single()
        assertTrue(!s.canSearch)
        assertTrue(!s.canExplore)
    }
}
