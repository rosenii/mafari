package dev.dsh.miuixbrowser.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：@connect 目标主机策略，口径与 Gecko 侧 background.js 的 connectAllows / cookieAllows 一致 */
class GmConnectPolicyTest {
    private fun p(vararg c: String) = GmConnectPolicy(c.toList())

    @Test
    fun noDeclarationAllowsPublicButNotPrivate() {
        val g = p()
        assertTrue(g.xhrAllows("api.example.com", "a.com"))
        assertFalse(g.xhrAllows("localhost", "a.com"))
        assertFalse(g.xhrAllows("192.168.1.2", "a.com"))
        assertFalse(g.xhrAllows("router", "a.com"))
        assertFalse(g.declared)
    }

    @Test
    fun sameSiteAlwaysAllowedEvenWhenDeclaredOtherwise() {
        val g = p("cdn.example.org")
        assertTrue(g.xhrAllows("a.com", "a.com"))
        assertTrue(g.xhrAllows("api.a.com", "a.com"))
        assertFalse("不是子域", g.xhrAllows("evila.com", "a.com"))
        assertFalse(g.xhrAllows("other.com", "a.com"))
        assertTrue(g.xhrAllows("cdn.example.org", "a.com"))
        assertTrue("子域跟随", g.xhrAllows("x.cdn.example.org", "a.com"))
    }

    @Test
    fun starAllowsAnyPublicHostButNeverPrivate() {
        val g = p("*")
        assertTrue(g.xhrAllows("anything.net", "a.com"))
        assertFalse(g.xhrAllows("127.0.0.1", "a.com"))
        assertFalse(g.xhrAllows("[::1]", "a.com"))
    }

    @Test
    fun privateHostNeedsExplicitConnect() {
        val g = p("192.168.1.2", "nas.local")
        assertTrue(g.xhrAllows("192.168.1.2", "a.com"))
        assertTrue(g.xhrAllows("nas.local", "a.com"))
        assertFalse(g.xhrAllows("192.168.1.3", "a.com"))
        assertFalse("声明了就不能越界", g.xhrAllows("public.net", "a.com"))
    }

    @Test
    fun normalizeStripsSchemePathPortAndWildcardPrefix() {
        assertEquals(
            listOf("a.com", "b.com", "*", "self", "c.com"),
            GmConnectPolicy.normalize(listOf(" HTTPS://A.com/x?y ", "*.b.com", "*", "self", "c.com:8080", "", "   ")),
        )
    }

    @Test
    fun cookieIsStricter() {
        assertFalse("没声明也不算", p().cookieAllows("other.com", "a.com"))
        assertFalse("* 不算", p("*").cookieAllows("other.com", "a.com"))
        assertTrue(p().cookieAllows("a.com", "a.com"))
        assertTrue(p().cookieAllows("sub.a.com", "a.com"))
        assertTrue(p("other.com").cookieAllows("other.com", "a.com"))
        assertFalse("页面主机未知时没有同站", p().cookieAllows("a.com", ""))
    }

    @Test
    fun privateHostClassification() {
        listOf("localhost", "foo.localhost", "x.local", "10.1.2.3", "127.0.0.1", "172.16.0.1", "172.31.255.1", "192.168.0.9",
            "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "[::1]", "fd00::1", "fe80::1", "::ffff:10.0.0.1",
            "::ffff:c0a8:0101", "router", "").forEach { assertTrue("$it 应是内网", GmConnectPolicy.isPrivateHost(it)) }
        listOf("example.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "100.63.0.1", "2001:db8::1", "::ffff:8.8.8.8", "1.example.com.")
            .forEach { assertFalse("$it 应是公网", GmConnectPolicy.isPrivateHost(it)) }
    }

    @Test
    fun hostOfHandlesOriginsAndOpaque() {
        assertEquals("a.com", GmConnectPolicy.hostOf("https://A.com:8443"))
        assertEquals("a.com", GmConnectPolicy.hostOf("https://a.com/path?q=1"))
        assertEquals("", GmConnectPolicy.hostOf("null"))
        assertEquals("", GmConnectPolicy.hostOf(null))
        assertEquals("", GmConnectPolicy.hostOf("about:blank"))
    }
}
