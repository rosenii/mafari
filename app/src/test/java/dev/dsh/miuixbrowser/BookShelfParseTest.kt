/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.books.data.BookShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 书架导入的纯文本解析：TXT 文件名、EPUB 的 OPF 元数据、封面路径。 */
class BookShelfParseTest {
    @Test fun txtNameWithBracketsAndAuthor() {
        assertEquals("斗破苍穹" to "天蚕土豆", BookShelf.parseTxtName("《斗破苍穹》作者：天蚕土豆.txt"))
        assertEquals("斗破苍穹" to "", BookShelf.parseTxtName("《斗破苍穹》.txt"))
    }

    @Test fun txtNameWithAuthorLabel() {
        assertEquals("三体" to "刘慈欣", BookShelf.parseTxtName("三体 作者：刘慈欣.txt"))
    }

    @Test fun txtNamePlain() {
        assertEquals("我的小说" to "", BookShelf.parseTxtName("我的小说.txt"))
    }

    @Test fun opfTitleAuthorAndEpub3Cover() {
        val opf = """
            <package><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>Tom &amp; Jerry</dc:title><dc:creator opf:role="aut">Someone</dc:creator>
            </metadata><manifest>
              <item id="c" href="img/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
            </manifest></package>
        """.trimIndent()
        val info = BookShelf.parseOpf(opf)
        assertEquals("Tom & Jerry", info.title)
        assertEquals("Someone", info.author)
        assertEquals("img/cover.jpg", info.coverHref)
    }

    @Test fun opfEpub2CoverMeta() {
        val opf = """
            <package><metadata><dc:title>T</dc:title><meta name="cover" content="cvr"/></metadata>
            <manifest><item id="cvr" href="cover.png" media-type="image/png"/></manifest></package>
        """.trimIndent()
        assertEquals("cover.png", BookShelf.parseOpf(opf).coverHref)
    }

    @Test fun opfWithoutCover() {
        assertNull(BookShelf.parseOpf("<package><metadata><dc:title>T</dc:title></metadata></package>").coverHref)
    }

    @Test fun epubPathResolution() {
        assertEquals("OEBPS/img/c.jpg", BookShelf.resolveEpubPath("OEBPS/content.opf", "img/c.jpg"))
        assertEquals("img/c.jpg", BookShelf.resolveEpubPath("content.opf", "img/c.jpg"))
        assertEquals("img/c.jpg", BookShelf.resolveEpubPath("OEBPS/content.opf", "../img/c.jpg"))
    }
}
