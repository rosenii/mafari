/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.browser.PasswordAutofill
import dev.dsh.miuixbrowser.util.hostOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：自动填充的站点候选匹配（宁可多给候选，但不能把别的站点的凭据交出去）。 */
class PasswordAutofillTest {

    @Test
    fun hostOfStripsSchemePortAndCase() {
        assertEquals("example.com", PasswordAutofill.hostOf("https://Example.com"))
        assertEquals("example.com", PasswordAutofill.hostOf("http://example.com:8080"))
        assertEquals("example.com", PasswordAutofill.hostOf("example.com:8443"))
        assertEquals("example.com", PasswordAutofill.hostOf("https://user:pw@example.com/path?q=1"))
        assertEquals("[::1]", PasswordAutofill.hostOf("http://[::1]:3000"))
        assertNull(PasswordAutofill.hostOf(""))
        assertNull(PasswordAutofill.hostOf("https://"))
    }

    @Test
    fun sameHostAndSubdomainsMatchBothWays() {
        assertTrue(PasswordAutofill.hostMatches("https://example.com", "example.com"))
        assertTrue(PasswordAutofill.hostMatches("https://example.com", "www.example.com"))
        assertTrue(PasswordAutofill.hostMatches("https://login.example.com", "example.com"))
        // 端口、协议不参与（精确匹配交给 Gecko）
        assertTrue(PasswordAutofill.hostMatches("http://example.com:8080", "https://example.com"))
    }

    @Test
    fun lookalikeAndUnrelatedHostsNeverMatch() {
        assertFalse(PasswordAutofill.hostMatches("https://evilexample.com", "example.com"))
        assertFalse(PasswordAutofill.hostMatches("https://example.com", "evilexample.com"))
        assertFalse(PasswordAutofill.hostMatches("https://example.com.evil.net", "example.com"))
        assertFalse(PasswordAutofill.hostMatches("https://other.org", "example.com"))
        assertFalse(PasswordAutofill.hostMatches("https://example.com", ""))
    }

    @Test
    fun systemAutofillNeedsTheSameHostNotJustASubdomain() {
        assertTrue(PasswordAutofill.sameSite("https://example.com", "example.com"))
        assertTrue(PasswordAutofill.sameSite("https://www.example.com", "example.com"))
        assertTrue(PasswordAutofill.sameSite("https://example.com", "www.example.com"))
        // 子域不给：sites.example.com 这类用户可自建内容的子站不能拿到 example.com 的密码
        assertFalse(PasswordAutofill.sameSite("https://example.com", "sites.example.com"))
        assertFalse(PasswordAutofill.sameSite("https://login.example.com", "example.com"))
        assertFalse(PasswordAutofill.sameSite("https://evilexample.com", "example.com"))
        assertFalse(PasswordAutofill.sameSite("https://example.com", ""))
    }

    @Test
    fun httpsCredentialsAreNotOfferedOnHttpPages() {
        assertFalse(PasswordAutofill.schemeAllows("https://example.com", "http"))
        assertTrue(PasswordAutofill.schemeAllows("https://example.com", "https"))
        assertTrue(PasswordAutofill.schemeAllows("http://example.com", "http"))
        assertTrue(PasswordAutofill.schemeAllows("http://example.com", "https")) // 升级到 https 没问题
        assertTrue(PasswordAutofill.schemeAllows("https://example.com", null)) // 协议未知不拦
    }

    @Test
    fun usableCombinesHostSchemeAndHttpAuthRules() {
        assertTrue(PasswordAutofill.usable("https://example.com", null, "example.com", "https"))
        assertFalse(PasswordAutofill.usable("https://example.com", "Restricted", "example.com", "https")) // HTTP 认证凭据
        assertFalse(PasswordAutofill.usable("https://example.com", null, "example.com", "http"))
        assertFalse(PasswordAutofill.usable("https://example.com", null, "other.org", "https"))
    }

    @Test
    fun appOriginsNeverMatchWebPages() {
        // 包名长得像主机名：主机名恰好等于包名的网站不能拿到原生 App 的凭据
        assertFalse(PasswordAutofill.sameSite("androidapp://com.example.bank", "com.example.bank"))
        assertFalse(PasswordAutofill.usable("androidapp://com.example.bank", null, "com.example.bank", "https"))
        assertFalse(PasswordAutofill.hostMatches("androidapp://com.example.bank", "com.example.bank"))
        assertTrue(PasswordAutofill.sameSite("https://example.com", "example.com"))
    }
}
