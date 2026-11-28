/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.books.data.BookContent
import dev.dsh.miuixbrowser.books.data.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书源正文里各种写法的 `<br>`（转义 / 多层转义 / 数字实体 / \\u003c）都要变成换行，阅读器里不能残留。 */
class BookTextMarkupTest {
    private val expected = listOf("他走了进去。", "门在身后关上。")

    @Test fun escapedBr() {
        assertEquals(expected, TextCleaner.paragraphs(listOf("他走了进去。&lt;br&gt;&lt;br&gt;门在身后关上。")))
    }

    @Test fun doubleEscapedBr() {
        assertEquals(expected, TextCleaner.paragraphs(listOf("他走了进去。&amp;lt;br&amp;gt;&amp;lt;br&amp;gt;门在身后关上。")))
        assertEquals(expected, TextCleaner.paragraphs(listOf("他走了进去。&amp;amp;lt;br/&amp;amp;gt;门在身后关上。")))
    }

    @Test fun numericAndUnicodeEscapes() {
        assertEquals(expected, TextCleaner.paragraphs(listOf("他走了进去。&#60;br&#62;门在身后关上。")))
        assertEquals(expected, TextCleaner.paragraphs(listOf("他走了进去。\\u003cbr\\u003e\\u003cbr\\u003e门在身后关上。")))
    }

    @Test fun brVariants() {
        assertEquals(expected, TextCleaner.paragraphs(listOf("他走了进去。<BR />门在身后关上。")))
        assertEquals(expected, TextCleaner.paragraphs(listOf("他走了进去。<br class=\"x\">门在身后关上。")))
        assertEquals(expected, TextCleaner.paragraphs(listOf("他走了进去。< br >门在身后关上。")))
    }

    @Test fun plainTextWithAngleBracketsIsKept() {
        assertEquals(listOf("1 < 2 且 3 > 2"), TextCleaner.paragraphs(listOf("1 &lt; 2 且 3 &gt; 2")))
    }

    @Test fun htmlToParagraphsNoResidue() {
        val out = BookContent.htmlToParagraphs("<p>他走了进去。&lt;br&gt;&lt;br&gt;</p><p>门在身后关上。&amp;lt;br&amp;gt;</p>")
        assertEquals(expected, out)
        assertTrue(out.none { it.contains("<") || it.contains("br>") })
    }
}
