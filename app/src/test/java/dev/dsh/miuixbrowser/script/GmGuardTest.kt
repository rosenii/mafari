/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：令牌 + origin 校验、令牌作废、限频（对应规格第三节第 3、8 条与 tools/gm-wv-test 里「偷令牌换 origin」那几条） */
class GmGuardTest {
    private fun scope(vararg match: String) = GmOriginScope.of(emptyList(), match.toList(), emptyList())

    @Test
    fun tokenIsRandom128BitHexAndStablePerScript() {
        val g = GmGuard()
        val t1 = g.register("s1", scope("https://a.com/*"))
        assertTrue(Regex("[0-9a-f]{32}").matches(t1))
        assertEquals(t1, g.register("s1", scope("https://a.com/*"))) // 重建 handler 沿用令牌：已打开页面里的旧令牌继续有效
        assertNotEquals(t1, g.register("s2", scope("https://a.com/*")))
        assertNotEquals(t1, GmGuard().register("s1", scope("https://a.com/*"))) // 进程内随机
    }

    @Test
    fun authorizeNeedsTokenAndOrigin() {
        val g = GmGuard()
        val t1 = g.register("s1", scope("https://a.com/*"))
        val t2 = g.register("s2", scope("https://b.com/*"))
        assertTrue(g.authorize("s1", t1, "https://a.com"))
        assertFalse("错令牌", g.authorize("s1", "00".repeat(16), "https://a.com"))
        assertFalse("别的脚本的令牌", g.authorize("s1", t2, "https://a.com"))
        assertFalse("偷到令牌换 origin", g.authorize("s1", t1, "https://b.com"))
        assertFalse("未知脚本", g.authorize("nope", t1, "https://a.com"))
        assertFalse("空令牌", g.authorize("s1", "", "https://a.com"))
        assertFalse("空 sid", g.authorize("", t1, "https://a.com"))
    }

    @Test
    fun scopeUpdateTakesEffectButKeepsToken() {
        val g = GmGuard()
        val t = g.register("s1", scope("https://a.com/*"))
        assertFalse(g.authorize("s1", t, "https://b.com"))
        assertEquals(t, g.register("s1", scope("https://b.com/*")))
        assertTrue(g.authorize("s1", t, "https://b.com"))
        assertFalse(g.authorize("s1", t, "https://a.com"))
    }

    @Test
    fun retainRevokesRemovedScripts() {
        val g = GmGuard()
        val t1 = g.register("s1", scope("*://*/*"))
        val t2 = g.register("s2", scope("*://*/*"))
        g.retain(setOf("s2"))
        assertNull(g.tokenOf("s1"))
        assertFalse("被停用 / 删除的脚本立刻失效", g.authorize("s1", t1, "https://a.com"))
        assertTrue(g.authorize("s2", t2, "https://a.com"))
        // 重新启用会拿到新令牌，旧页面里的旧令牌依旧无效
        val again = g.register("s1", scope("*://*/*"))
        assertNotEquals(t1, again)
        assertFalse(g.authorize("s1", t1, "https://a.com"))
        g.clear()
        assertFalse(g.authorize("s2", t2, "https://a.com"))
    }

    @Test
    fun rateLimitWindowAndCost() {
        var now = 0L
        val g = GmGuard(clockMs = { now })
        repeat(5) { assertTrue(g.allow("s1", "openTab", 5, 10_000)) }
        assertFalse(g.allow("s1", "openTab", 5, 10_000))
        assertTrue("别的脚本不受影响", g.allow("s2", "openTab", 5, 10_000))
        assertTrue("别的能力不受影响", g.allow("s1", "toast", 10, 10_000))
        now = 10_001
        assertTrue("窗口过后重新计数", g.allow("s1", "openTab", 5, 10_000))

        // 按字节计费：次数不多，但总量超了也要拒
        assertTrue(g.allow("s1", "setBytes", 100, 10_000, 60))
        assertFalse(g.allow("s1", "setBytes", 100, 10_000, 60))
        assertTrue(g.allow("s1", "setBytes", 100, 10_000, 40))
    }

    @Test
    fun retainDropsBucketsOfRemovedScripts() {
        var now = 0L
        val g = GmGuard(clockMs = { now })
        g.register("s1", scope("*://*/*"))
        repeat(5) { g.allow("s1", "openTab", 5, 10_000) }
        assertFalse(g.allow("s1", "openTab", 5, 10_000))
        g.retain(emptySet())
        g.register("s1", scope("*://*/*"))
        assertTrue("重新启用后计数从头开始", g.allow("s1", "openTab", 5, 10_000))
    }
}
