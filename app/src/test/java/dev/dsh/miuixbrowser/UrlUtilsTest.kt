/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.util.isSameSite
import dev.dsh.miuixbrowser.util.looksLikeUrl
import dev.dsh.miuixbrowser.util.toUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：地址栏输入判断、补协议、同站判断（hostOf / isUserScriptUrl 依赖 android.net.Uri，不在这里测）。 */
class UrlUtilsTest {

    @Test
    fun looksLikeUrl_urlsAndHosts() {
        assertTrue(looksLikeUrl("example.com"))
        assertTrue(looksLikeUrl("https://a.b"))
        assertTrue(looksLikeUrl("about:blank"))
        assertTrue(looksLikeUrl("localhost"))
        assertTrue(looksLikeUrl("192.168.1.1:8080/path"))
        assertTrue(looksLikeUrl("[::1]:8080"))
        assertTrue("局域网主机名带端口", looksLikeUrl("nas:5000"))
    }

    @Test
    fun looksLikeUrl_searchTerms() {
        assertFalse(looksLikeUrl(""))
        assertFalse(looksLikeUrl("hello world"))
        assertFalse("单个词没有端口，当搜索词", looksLikeUrl("hello"))
        assertFalse(looksLikeUrl("foo."))
    }

    @Test
    fun toUrl_addsScheme() {
        assertEquals("https://example.com", toUrl("example.com"))
        assertEquals("http://192.168.1.1:8080", toUrl("192.168.1.1:8080"))
        assertEquals("http://nas:5000", toUrl("nas:5000"))
        assertEquals("http://dev.local", toUrl("dev.local"))
        assertEquals("https://x.y/a", toUrl("https://x.y/a"))
    }

    @Test
    fun toUrl_searchesWhenNotUrl() {
        assertEquals("https://s?q=kotlin+%E5%8D%8F%E7%A8%8B", toUrl("kotlin 协程", "https://s?q=%s"))
    }

    @Test
    fun isSameSite() {
        assertTrue(isSameSite("m.example.com", "www.example.com"))
        assertTrue(isSameSite("x.example.co.uk", "y.example.co.uk"))
        assertFalse("co.uk 这类二级后缀要多留一段", isSameSite("a.co.uk", "b.co.uk"))
        assertFalse("github.io 是公共后缀", isSameSite("a.github.io", "b.github.io"))
        assertFalse(isSameSite("example.com", "example.org"))
        assertTrue(isSameSite("1.2.3.4", "1.2.3.4"))
        assertFalse(isSameSite("1.2.3.4", "5.6.7.8"))
    }
}
