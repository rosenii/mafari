/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.data.BookContent
import dev.dsh.miuixbrowser.data.BookFeedImport
import dev.dsh.miuixbrowser.data.BookFeedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 通用 JSON 小说订阅源：解析各种写法、相对链接、章节列表，以及按章拼 TXT 后阅读器能切回章节。 */
class BookFeedTest {
    @Test fun objectWithBooksAndRelativeUrls() {
        val feed = BookFeedParser.parse(
            """{"name":"我的书源","books":[
                 {"title":"三体","author":"刘慈欣","cover":"c/1.jpg","url":"files/santi.epub","format":"epub"},
                 {"name":"无链接的书"}
               ]}""",
            "https://example.com/feeds/index.json",
        )
        assertEquals("我的书源", feed.name)
        assertEquals(1, feed.books.size)
        val b = feed.books[0]
        assertEquals("三体", b.title)
        assertEquals("刘慈欣", b.author)
        assertEquals("https://example.com/feeds/files/santi.epub", b.url)
        assertEquals("https://example.com/feeds/c/1.jpg", b.cover)
        assertEquals("epub", b.format)
    }

    @Test fun topLevelArrayWithAliasKeys() {
        val feed = BookFeedParser.parse(
            """[{"bookName":"A","authors":["甲","乙"],"downloadUrl":"https://x.test/a.txt","coverUrl":"https://x.test/a.jpg"}]""",
        )
        assertEquals("", feed.name)
        assertEquals("甲、乙", feed.books[0].author)
        assertEquals("https://x.test/a.txt", feed.books[0].url)
    }

    @Test fun nestedDataList() {
        val feed = BookFeedParser.parse("""{"code":0,"data":{"list":[{"title":"B","url":"https://x.test/b.txt"}]}}""")
        assertEquals("B", feed.books[0].title)
    }

    @Test fun inlineAndLinkedChapters() {
        val feed = BookFeedParser.parse(
            """{"books":[
                 {"title":"C","chapters":[{"title":"一","url":"1.txt"},{"title":"二","text":"正文"},"3.txt"]},
                 {"title":"D","chapters":"toc.json"}
               ]}""",
            "https://x.test/feed/index.json",
        )
        val c = feed.books[0].chapters
        assertEquals(3, c.size)
        assertEquals("https://x.test/feed/1.txt", c[0].url)
        assertEquals("正文", c[1].text)
        assertEquals("", c[2].title)
        assertEquals("https://x.test/feed/toc.json", feed.books[1].chaptersUrl)
    }

    @Test fun chapterListFile() {
        val l = BookFeedParser.parseChapterList("""{"chapters":[{"title":"一","link":"a"},{"title":"二","link":"b"}]}""", "https://x.test/d/toc.json")
        assertEquals(listOf("https://x.test/d/a", "https://x.test/d/b"), l.map { it.url })
    }

    @Test fun opdsPublications() {
        val feed = BookFeedParser.parse(
            """{"metadata":{"title":"OPDS 书库"},"publications":[
                 {"metadata":{"title":"E","author":{"name":"某人"}},
                  "links":[{"rel":"http://opds-spec.org/acquisition","href":"/e.epub","type":"application/epub+zip"}],
                  "images":[{"href":"/e.jpg"}]}]}""",
            "https://lib.test/opds",
        )
        assertEquals("OPDS 书库", feed.name)
        val b = feed.books[0]
        assertEquals("某人", b.author)
        assertEquals("https://lib.test/e.epub", b.url)
        assertEquals("epub", b.format)
        assertEquals("https://lib.test/e.jpg", b.cover)
    }

    @Test fun unsafeSchemesAndLocalRelativeAreDropped() {
        assertNull(BookFeedParser.resolve("file:///sdcard/a.txt", "https://x.test/"))
        assertNull(BookFeedParser.resolve("javascript:alert(1)", "https://x.test/"))
        assertNull(BookFeedParser.resolve("a.txt", null))
        assertEquals("https://x.test/a.txt", BookFeedParser.resolve("//x.test/a.txt", "https://y.test/"))
    }

    @Test fun invalidFeedsThrowReadableErrors() {
        for (bad in listOf("not json", """{"foo":1}""", """{"books":[]}""", """{"books":[{"title":"只有书名"}]}""")) {
            try {
                BookFeedParser.parse(bad)
                fail("should reject: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test fun extPrefersContentOverHints() {
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0)
        assertEquals("epub", BookFeedImport.pickExt("txt", "https://x.test/a.txt", "text/plain", zip))
        val text = "正文".toByteArray()
        assertEquals("txt", BookFeedImport.pickExt(null, "https://x.test/dl.php?id=1", "", text))
        assertEquals("epub", BookFeedImport.pickExt(null, "https://x.test/a.EPUB", "", text))
        assertEquals("epub", BookFeedImport.pickExt(null, "https://x.test/dl", "application/epub+zip", text))
    }

    @Test fun builtTxtSplitsBackIntoChaptersEvenWithOddTitles() {
        val txt = BookFeedImport.buildTxt(
            listOf("Prologue", "", "1. 开始"),
            listOf("第一段\n第二段", "<p>甲</p><p>乙</p>", null),
        )
        val ch = BookContent.parseTxt(txt)
        assertEquals(3, ch.size)
        assertEquals("Prologue", ch[0].title)
        assertEquals("第 2 章", ch[1].title)
        assertEquals("1. 开始", ch[2].title)
        assertTrue(ch[1].text.contains("甲") && ch[1].text.contains("乙"))
        assertFalse(ch[1].text.contains("<p>"))
        assertTrue(ch[2].text.contains("下载失败"))
    }
}
