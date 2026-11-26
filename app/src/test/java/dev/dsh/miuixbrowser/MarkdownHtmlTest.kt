/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.util.LocalHtml
import dev.dsh.miuixbrowser.util.MarkdownHtml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：Markdown → HTML 的常见语法与安全转义。 */
class MarkdownHtmlTest {

    @Test fun headingAndEmphasis() {
        val html = MarkdownHtml.toHtml("# Hi\n\n**bold** and *it*")
        assertTrue(html.contains("<h1 id=\"hi\">Hi</h1>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>it</em>"))
    }

    @Test fun listsTablesAndCode() {
        assertTrue(MarkdownHtml.toHtml("- a\n- b").contains("<li>a</li>"))
        val table = MarkdownHtml.toHtml("|a|b|\n|---|:-:|\n|1|2|")
        assertTrue(table.contains("<th>a</th>"))
        assertTrue(table.contains("<td style=\"text-align:center\">2</td>"))
        val code = MarkdownHtml.toHtml("```kotlin\nval a = 1 < 2\n```")
        assertTrue(code.contains("language-kotlin"))
        assertTrue(code.contains("1 &lt; 2"))
    }

    @Test fun rawHtmlAndScriptLinksAreNeutralized() {
        assertFalse(MarkdownHtml.toHtml("<script>alert(1)</script>").contains("<script"))
        assertFalse(MarkdownHtml.toHtml("[x](javascript:alert(1))").contains("href=\"javascript"))
    }

    @Test fun bareUrlBecomesLink() {
        assertTrue(MarkdownHtml.toHtml("see https://example.com/a.").contains("href=\"https://example.com/a\""))
    }

    @Test fun markdownDetection() {
        assertTrue(LocalHtml.isMarkdown("README.md", null))
        assertTrue(LocalHtml.isMarkdown("noext", "text/markdown"))
        assertFalse(LocalHtml.isMarkdown("a.txt", "text/markdown"))
        assertTrue(LocalHtml.isText("noext", "text/x-markdown"))
    }
}
