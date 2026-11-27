/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.legado

import dev.dsh.miuixbrowser.data.BookFeedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPDS 1.0（Atom）订阅源：书目 + acquisition 下载链接 + 相对地址补全。 */
class BookFeedOpdsTest {
    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
          <title>示例 OPDS 书库</title>
          <entry>
            <title>三体</title>
            <author><name>刘慈欣</name></author>
            <link rel="http://opds-spec.org/image" href="/covers/santi.jpg" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition/open-access" href="/books/santi.epub" type="application/epub+zip"/>
          </entry>
          <entry>
            <title>凡人修仙传</title>
            <author><name>忘语</name></author>
            <link rel="http://opds-spec.org/acquisition" href="books/fanren.txt" type="text/plain"/>
          </entry>
          <entry>
            <title>只有导航，没有下载链接</title>
            <link rel="subsection" href="/more" type="application/atom+xml"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test fun parsesOpdsAtomBooks() {
        val feed = BookFeedParser.parse(xml, "https://books.example.com/catalog/opds.xml")
        assertEquals("示例 OPDS 书库", feed.name)
        assertEquals(2, feed.books.size)
        val a = feed.books[0]
        assertEquals("三体", a.title)
        assertEquals("刘慈欣", a.author)
        assertEquals("https://books.example.com/books/santi.epub", a.url)
        assertEquals("https://books.example.com/covers/santi.jpg", a.cover)
        assertEquals("epub", a.format)
        val b = feed.books[1]
        assertEquals("凡人修仙传", b.title)
        assertEquals("https://books.example.com/catalog/books/fanren.txt", b.url)
        assertEquals("txt", b.format)
        assertTrue(feed.books.none { it.title == "只有导航，没有下载链接" })
    }

    @Test fun jsonFeedStillUsesBaseUrl() {
        val feed = BookFeedParser.parse(
            """{"name":"相对链接","books":[{"title":"书","url":"a/b.epub"}]}""",
            "https://example.com/list/feed.json",
        )
        assertEquals("https://example.com/list/a/b.epub", feed.books.single().url)
    }
}
