package dev.dsh.miuixbrowser

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
}
