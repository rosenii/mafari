/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.data.Block
import dev.dsh.miuixbrowser.data.BookContent
import dev.dsh.miuixbrowser.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读器的内容解析：TXT 分章 / 编码识别、EPUB 目录与 XHTML 转段落。 */
class BookContentTest {
    private val indent = "\u3000\u3000"

    @Test fun txtSplitsByChapterTitles() {
        val cs = BookContent.parseTxt("前言文字\n第一章 开始\n内容a\n内容b\n\n第二章 继续\n内容c\n")
        assertEquals(listOf("开篇", "第一章 开始", "第二章 继续"), cs.map { it.title })
        assertEquals(indent + "内容a\n" + indent + "内容b", cs[1].text)
        assertEquals(indent + "内容c", cs[2].text)
    }

    @Test fun txtWithoutChaptersIsChunked() {
        val raw = (1..300).joinToString("\n") { "这是第${it}段，" + "字".repeat(60) }
        val cs = BookContent.parseTxt(raw)
        assertTrue(cs.size > 1)
        assertEquals("第 1 节", cs[0].title)
        assertEquals("第 2 节", cs[1].title)
    }

    @Test fun decodesGbkAndUtf8Bom() {
        assertEquals("你好，世界", BookContent.decodeText("你好，世界".toByteArray(charset("GB18030"))))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray(Charsets.UTF_8)
        assertEquals("你好", BookContent.decodeText(bom))
    }

    @Test fun longChapterIsSplit() {
        val text = (1..400).joinToString("\n") { indent + "字".repeat(100) }
        val parts = BookContent.splitLong(Chapter("第一章", text))
        assertTrue(parts.size >= 3)
        assertEquals("第一章", parts[0].title)
        assertEquals("第一章（2）", parts[1].title)
    }

    @Test fun htmlToParagraphs() {
        val html = "<html><head><title>x</title><style>p{}</style></head><body><h1>标题</h1><p>第一段&nbsp;内容</p><p>第二<br/>段 &amp; 完</p></body></html>"
        assertEquals(listOf("标题", "第一段 内容", "第二", "段 & 完"), BookContent.htmlToParagraphs(html))
        assertEquals("标题", BookContent.firstHeading(html))
    }

    @Test fun navAndNcxTitles() {
        val nav = "<nav><ol><li><a href=\"ch1.xhtml#a\">第一章</a></li><li><a href=\"ch2.xhtml\">第二章</a></li></ol></nav>"
        assertEquals(mapOf("OEBPS/ch1.xhtml" to "第一章", "OEBPS/ch2.xhtml" to "第二章"), BookContent.parseNavTitles(nav, "OEBPS/nav.xhtml"))
        val ncx = "<navMap><navPoint id=\"a\"><navLabel><text>第一章</text></navLabel><content src=\"ch1.xhtml\"/></navPoint>" +
            "<navPoint id=\"b\"><navLabel><text>第二章</text></navLabel><content src=\"ch2.xhtml\"/></navPoint></navMap>"
        assertEquals(mapOf("OEBPS/ch1.xhtml" to "第一章", "OEBPS/ch2.xhtml" to "第二章"), BookContent.parseNcxTitles(ncx, "OEBPS/toc.ncx"))
    }

    // ---- EPUB 图片页

    private fun lookup(vararg files: Pair<String, Long>): (String) -> Pair<String, Long>? {
        val map = files.toMap()
        return { name -> map[name]?.let { name to it } }
    }

    @Test fun imageOnlyPageKeepsImage() {
        val html = "<html><head><title>x</title></head><body><div><img src=\"../images/p%201.jpg\" alt=\"\"/></div></body></html>"
        // 路径相对 XHTML 解析（../ 已折叠）；返回的是压缩包里的真实条目名（百分号编码在查找时处理）
        val pieces = BookContent.htmlPieces(html, "OEBPS/text/p1.xhtml") { n ->
            if (n == "OEBPS/images/p%201.jpg") "OEBPS/images/p 1.jpg" to 800L else null
        }
        assertEquals(1, pieces.size)
        assertEquals("OEBPS/images/p 1.jpg", (pieces[0] as BookContent.Piece.Img).path)
    }

    @Test fun svgWrappedCoverIsKept() {
        val html = "<body><svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">" +
            "<image width=\"600\" height=\"800\" xlink:href=\"cover.jpeg\"/></svg></body>"
        val pieces = BookContent.htmlPieces(html, "OEBPS/cover.xhtml", lookup("OEBPS/cover.jpeg" to 1000L))
        assertEquals(listOf("OEBPS/cover.jpeg"), pieces.map { (it as BookContent.Piece.Img).path })
    }

    @Test fun missingOrRemoteImagesAreSkipped() {
        val html = "<body><img src=\"gone.png\"/><img src=\"https://x.test/a.png\"/><img src=\"data:image/png;base64,AAAA\"/></body>"
        assertTrue(BookContent.htmlPieces(html, "a.xhtml", lookup("a.png" to 1000L)).isEmpty())
    }

    @Test fun textPageDropsSmallImagesButKeepsIllustrations() {
        val big = BookContent.INLINE_IMAGE_MIN + 1
        val html = "<body><p>前文<img src=\"dot.png\"/>接着</p><p>第二段</p><img src=\"art.jpg\"/><p>后文</p></body>"
        val pieces = BookContent.htmlPieces(html, "t.xhtml", lookup("dot.png" to 300L, "art.jpg" to big))
        assertEquals(3, pieces.size)
        // 小图标没有把一个段落拆开
        assertEquals(listOf("前文接着", "第二段"), (pieces[0] as BookContent.Piece.Paras).lines)
        assertEquals("art.jpg", (pieces[1] as BookContent.Piece.Img).path)
        assertEquals(listOf("后文"), (pieces[2] as BookContent.Piece.Paras).lines)
    }

    @Test fun chapterBlocksKeepOrderAndPlainChapterHasOneTextBlock() {
        val plain = Chapter("a", "文字")
        assertEquals(1, plain.blocks.size)
        assertTrue(!plain.hasImages)
        val mixed = Chapter("b", "文字", listOf(Block.Text("文字"), Block.Image("x.jpg")))
        assertTrue(mixed.hasImages)
        // 带图的章节不会被按字数切开（切开会弄乱图片的位置）
        val longMixed = Chapter("c", "字".repeat(30000), listOf(Block.Text("字".repeat(30000)), Block.Image("x.jpg")))
        assertEquals(1, BookContent.splitLong(longMixed).size)
    }
}
