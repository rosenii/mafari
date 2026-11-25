/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：脚本匹配范围 → origin 规则 / 每条消息的 sourceOrigin 校验（见 docs/WEBVIEW_GM_CHANNEL.md 第三节第 2、3 条） */
class GmOriginsTest {
    private fun scope(match: List<String> = emptyList(), include: List<String> = emptyList(), user: List<String> = emptyList()) =
        GmOriginScope.of(user, match, include)

    @Test
    fun noRulesMeansEverywhere() {
        val s = scope()
        assertTrue(s.matchesAny)
        assertEquals(setOf("*"), s.originRules())
        assertTrue(s.allows("https://anything.example"))
        assertTrue(s.allows("null")) // 放宽就是放宽：连不透明 origin 也放行，精确判断在页面里的运行时
    }

    @Test
    fun exactHostIgnoresPath() {
        val s = scope(match = listOf("https://example.com/a/*"))
        assertEquals(setOf("https://example.com"), s.originRules())
        assertTrue(s.allows("https://example.com"))
        assertFalse(s.allows("http://example.com"))
        assertFalse(s.allows("https://www.example.com"))
        assertFalse(s.allows("https://evil.com"))
    }

    @Test
    fun wildcardSchemeMeansHttpAndHttpsOnly() {
        val s = scope(match = listOf("*://example.com/*"))
        assertEquals(setOf("http://example.com", "https://example.com"), s.originRules())
        assertTrue(s.allows("http://example.com"))
        assertTrue(s.allows("https://example.com"))
        assertFalse(s.allows("ftp://example.com"))
    }

    @Test
    fun subdomainWildcardCoversBaseAndChildren() {
        val s = scope(match = listOf("https://*.example.com/*"))
        assertEquals(setOf("https://example.com", "https://*.example.com"), s.originRules())
        assertTrue(s.allows("https://example.com"))
        assertTrue(s.allows("https://a.b.example.com"))
        assertFalse(s.allows("https://notexample.com")) // 后缀相同但不是子域
        assertFalse(s.allows("https://example.com.evil.com"))
    }

    @Test
    fun explicitPortIsEnforcedButMissingPortMeansAny() {
        val withPort = scope(match = listOf("https://example.com:8443/*"))
        assertEquals(setOf("https://example.com:8443"), withPort.originRules())
        assertTrue(withPort.allows("https://example.com:8443"))
        assertFalse(withPort.allows("https://example.com"))
        assertFalse(withPort.allows("https://example.com:9000"))

        val noPort = scope(match = listOf("https://example.com/*"))
        assertTrue(noPort.allows("https://example.com"))
        assertTrue(noPort.allows("https://example.com:8443")) // 与运行时 matchPatternTest 一致：规则没写端口就不限端口
        val defaultPort = scope(match = listOf("https://example.com:443/*"))
        assertTrue(defaultPort.allows("https://example.com")) // 未写端口的 origin 按 scheme 默认端口算
    }

    @Test
    fun inexpressibleRulesWidenToEverything() {
        for (rule in listOf("*", "<all_urls>", "/^https:\\/\\/a\\.com\\//", "http*://example.com/*", "*example.com*", "https://www.*.com/*", "garbage")) {
            val s = scope(include = listOf(rule))
            assertTrue("$rule 应放宽成任意 origin", s.matchesAny)
            assertEquals(setOf("*"), s.originRules())
        }
        // 一条表达不了，整个范围放宽，哪怕其它规则很精确
        assertTrue(scope(match = listOf("https://example.com/*"), include = listOf("*example*")).matchesAny)
    }

    @Test
    fun includeGlobsWithHostAreExpressible() {
        val s = scope(include = listOf("https://example.com/*", "http://*.foo.org/x*"))
        assertEquals(setOf("https://example.com", "http://foo.org", "http://*.foo.org"), s.originRules())
    }

    @Test
    fun nonWebSchemesContributeNothing() {
        val s = scope(match = listOf("file:///*", "ftp://example.com/*"))
        assertFalse(s.matchesAny)
        assertEquals(setOf("*"), s.originRules()) // 没有可表达规则：规则位退回 *，放行与否完全由 allows 决定
        assertFalse(s.allows("https://example.com"))
        assertFalse(s.allows("file://"))
        // 和 web 规则混写：只有 web 部分生效
        val mixed = scope(match = listOf("file:///*", "https://example.com/*"))
        assertEquals(setOf("https://example.com"), mixed.originRules())
        assertFalse(mixed.allows("https://other.com"))
    }

    @Test
    fun userOverrideReplacesScriptHeader() {
        val s = scope(match = listOf("https://a.com/*"), user = listOf("https://b.com/*"))
        assertTrue(s.allows("https://b.com"))
        assertFalse(s.allows("https://a.com"))
    }

    @Test
    fun malformedOriginsAreRejectedWhenRestricted() {
        val s = scope(match = listOf("https://example.com/*"))
        assertFalse(s.allows("null"))
        assertFalse(s.allows(""))
        assertFalse(s.allows("not a url"))
    }

    @Test
    fun unionAcrossScripts() {
        val a = scope(match = listOf("https://a.com/*"))
        val b = scope(match = listOf("https://b.com/*"))
        assertEquals(setOf("https://a.com", "https://b.com"), GmOriginScope.unionRules(listOf(a, b)))
        assertEquals(setOf("*"), GmOriginScope.unionRules(listOf(a, scope()))) // 有一个放宽，并集就是 *
        assertEquals(emptySet<String>(), GmOriginScope.unionRules(emptyList()))
    }
}
