/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.legado

import dev.dsh.miuixbrowser.data.BookFeedParser
import dev.dsh.miuixbrowser.data.legado.LegadoRssSourceParser
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legado 订阅源（RSS / JSON / 网页）：识别、解析、与书源 / 通用订阅源区分。 */
class LegadoRssSourceTest {
    private val rss = """
        {
          "sourceName": "示例订阅",
          "sourceUrl": "https://example.com/feed",
          "sourceGroup": "资讯",
          "sourceType": 0,
          "enabled": true,
          "ruleArticles": "item",
          "ruleTitle": "title",
          "ruleLink": "link",
          "ruleDescription": "description",
          "ruleContent": "description"
        }
    """.trimIndent()

    @Test fun detectsAndParsesSubscription() {
        val root = JSONTokener("[$rss]").nextValue()
        assertTrue(LegadoRssSourceParser.looksLikeLegado(root))
        val feed = BookFeedParser.parse("[$rss]")
        assertTrue(feed.books.isEmpty())
        assertTrue(feed.sources.isEmpty())
        assertEquals(1, feed.rssSources.size)
        assertEquals("示例订阅", feed.name)
        val s = feed.rssSources.single()
        assertEquals("https://example.com/feed", s.url)
        assertEquals("item", s.ruleArticles)
        assertTrue(s.canBrowse)
    }

    @Test fun supportsRssSourcesWrapper() {
        val text = """{"rssSources":[$rss]}"""
        val feed = BookFeedParser.parse(text)
        assertEquals(1, feed.rssSources.size)
        assertEquals("示例订阅", feed.rssSources.single().name)
    }

    @Test fun genericFeedWithSourceUrlIsNotMistakenForRss() {
        val text = """{"books":[{"title":"书","url":"https://example.com/a.txt","sourceUrl":"https://example.com"}]}"""
        val feed = BookFeedParser.parse(text)
        assertTrue(feed.rssSources.isEmpty())
        assertEquals(1, feed.books.size)
    }

    @Test fun disabledSubscriptionIsNotBrowsable() {
        val text = rss.replace("\"enabled\": true", "\"enabled\": false")
        val feed = BookFeedParser.parse("[$text]")
        assertTrue(!feed.rssSources.single().canBrowse)
    }
}
