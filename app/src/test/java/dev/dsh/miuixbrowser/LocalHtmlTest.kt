/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.util.LocalHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHtmlTest {
    @Test fun keepsHtmlNames() {
        assertEquals("index.html", LocalHtml.cleanName("index.html"))
        assertEquals("a.b.HTM", LocalHtml.cleanName("a.b.HTM"))
        assertEquals("doc.xhtml", LocalHtml.cleanName("doc.xhtml"))
    }

    @Test fun appendsExtensionWhenMissing() {
        assertEquals("page.html", LocalHtml.cleanName(null))
        assertEquals("page.html", LocalHtml.cleanName("   "))
        assertEquals("readme.txt.html", LocalHtml.cleanName("readme.txt"))
    }

    @Test fun stripsPathAndIllegalChars() {
        assertEquals("passwd.html", LocalHtml.cleanName("../../etc/passwd"))
        assertEquals("evil.html", LocalHtml.cleanName("C:\\x\\evil.html"))
        assertEquals("ab.html", LocalHtml.cleanName("a<b>?.html"))
    }

    @Test fun limitsLength() {
        val name = LocalHtml.cleanName("x".repeat(300) + ".html")
        assertTrue(name.length <= 85)
        assertTrue(name.endsWith(".html"))
    }

    @Test fun keepsMoreWebExtensions() {
        assertEquals("a.mhtml", LocalHtml.cleanName("a.mhtml"))
        assertEquals("a.SHTML", LocalHtml.cleanName("a.SHTML"))
        assertEquals("a.xht", LocalHtml.cleanName("a.xht"))
    }

    @Test fun detectsText() {
        assertTrue(LocalHtml.isText("a.txt", null))
        assertTrue(LocalHtml.isText("a.LOG", "application/octet-stream"))
        assertTrue(LocalHtml.isText("noext", "text/plain"))
        assertTrue(!LocalHtml.isText("a.html", "text/plain"))
        assertTrue(!LocalHtml.isText("a.mp4", null))
    }

    @Test fun decodesCommonEncodings() {
        assertEquals("你好", LocalHtml.decodeText("你好".toByteArray(Charsets.UTF_8)))
        assertEquals("你好", LocalHtml.decodeText("你好".toByteArray(java.nio.charset.Charset.forName("GBK"))))
        assertEquals("hi", LocalHtml.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte(), 'i'.code.toByte())))
    }

    @Test fun escapesHtml() {
        assertEquals("&lt;script&gt;a&amp;b", LocalHtml.escape("<script>a&b"))
    }

    @Test fun sharedLinkOnlyForWholeUrl() {
        assertEquals("https://example.com/a?b=1", LocalHtml.sharedLink("  https://example.com/a?b=1\n"))
        assertEquals("HTTP://EXAMPLE.COM", LocalHtml.sharedLink("HTTP://EXAMPLE.COM"))
        assertEquals(null, LocalHtml.sharedLink("看这个 https://example.com"))
        assertEquals(null, LocalHtml.sharedLink("example.com"))
        assertEquals(null, LocalHtml.sharedLink("https://"))
        assertEquals(null, LocalHtml.sharedLink("   "))
        assertEquals(null, LocalHtml.sharedLink("javascript:alert(1)"))
    }

    @Test fun textPageEscapesContent() {
        val html = LocalHtml.textPageHtml("<t>", "<script>alert(1)</script> & more", truncated = false)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; more"))
        assertTrue(html.contains("<title>&lt;t&gt;</title>"))
        assertTrue(!html.contains("<script>"))
    }
}
