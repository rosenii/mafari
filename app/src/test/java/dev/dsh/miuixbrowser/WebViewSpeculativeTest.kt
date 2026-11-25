/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.engine.webview.PreconnectGate
import dev.dsh.miuixbrowser.engine.webview.SpeculativePolicy
import dev.dsh.miuixbrowser.engine.webview.WebViewPerf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebViewSpeculativeTest {
    @Test fun originKeepsSchemeHostAndExplicitPort() {
        assertEquals("https://example.com", SpeculativePolicy.originOf("https://example.com/a/b?q=1#x"))
        assertEquals("http://example.com:8080", SpeculativePolicy.originOf("http://Example.com:8080/x"))
        assertEquals("https://www.baidu.com", SpeculativePolicy.originOf("https://www.baidu.com/s?wd="))
    }

    @Test fun nonWebAndNonPublicHostsAreSkipped() {
        assertNull(SpeculativePolicy.originOf("about:blank"))
        assertNull(SpeculativePolicy.originOf("javascript:alert(1)"))
        assertNull(SpeculativePolicy.originOf("ftp://example.com"))
        assertNull(SpeculativePolicy.originOf("http://localhost:3000"))
        assertNull(SpeculativePolicy.originOf("http://192.168.1.1/admin"))
        assertNull(SpeculativePolicy.originOf("http://nas:5000"))
        assertNull(SpeculativePolicy.originOf("http://[::1]/"))
    }

    @Test fun inputOnlyTriggersForCompleteUrls() {
        assertEquals("https://github.com", SpeculativePolicy.originForInput("github.com"))
        assertEquals("https://github.com", SpeculativePolicy.originForInput("  https://github.com/foo  "))
        assertNull(SpeculativePolicy.originForInput("how to cook rice"))   // 搜索词
        assertNull(SpeculativePolicy.originForInput("github"))             // 还没打完
        assertNull(SpeculativePolicy.originForInput("github."))            // 域名没打完
        assertNull(SpeculativePolicy.originForInput("gi"))
        assertNull(SpeculativePolicy.originForInput("192.168.1.1"))
    }

    @Test fun gateDedupesWithinWindowAndReleasesAfter() {
        val g = PreconnectGate(windowMs = 1000, max = 2)
        assertTrue(g.shouldFire("https://a.com", 0))
        assertFalse(g.shouldFire("https://a.com", 999))
        assertTrue(g.shouldFire("https://a.com", 1000))
        assertTrue(g.shouldFire("https://b.com", 1001))
    }

    @Test fun gateForgetAllowsRetryAndCapsSize() {
        val g = PreconnectGate(windowMs = 1000, max = 2)
        assertTrue(g.shouldFire("https://a.com", 0))
        g.forget("https://a.com")
        assertTrue(g.shouldFire("https://a.com", 1))
        g.shouldFire("https://b.com", 2)
        g.shouldFire("https://c.com", 3) // 挤掉最老的 a.com
        assertTrue(g.shouldFire("https://a.com", 4))
    }
}

class WebViewPerfTest {
    @Before fun reset() = WebViewPerf.clear()

    @Test fun metricsLandOnNewestRecordOfTheSameTab() {
        WebViewPerf.begin(1, "https://a.com/", now = 1)
        WebViewPerf.begin(2, "https://b.com/", now = 2)
        WebViewPerf.onFcp(1, 500)
        WebViewPerf.onLcp(1, 900)
        WebViewPerf.onLcp(1, 1200) // LCP 取最新
        val a = WebViewPerf.latestFor(1)!!
        assertEquals(500L, a.fcpMs)
        assertEquals(1200L, a.lcpMs)
        assertNull(WebViewPerf.latestFor(2)!!.fcpMs)
    }

    @Test fun eventsBeforeAnyCommitAreDropped() {
        WebViewPerf.onFcp(9, 100)
        assertNull(WebViewPerf.latestFor(9))
    }

    @Test fun newCommitStartsFreshRecord() {
        WebViewPerf.begin(1, "https://a.com/", now = 1)
        WebViewPerf.onFcp(1, 500)
        WebViewPerf.begin(1, "https://a.com/next", now = 2)
        assertNull(WebViewPerf.latestFor(1)!!.fcpMs)
        assertEquals(2, WebViewPerf.entries.size)
    }

    @Test fun keepsOnlyMaxRecords() {
        repeat(WebViewPerf.MAX + 5) { WebViewPerf.begin(1, "https://a.com/$it", now = it.toLong()) }
        assertEquals(WebViewPerf.MAX, WebViewPerf.entries.size)
        assertEquals("https://a.com/${WebViewPerf.MAX + 4}", WebViewPerf.entries.first().url)
    }

    @Test fun ratingUsesWebDevThresholds() {
        assertEquals(WebViewPerf.Rating.GOOD, WebViewPerf.rate(WebViewPerf.Metric.FCP, 1800))
        assertEquals(WebViewPerf.Rating.NEEDS_IMPROVEMENT, WebViewPerf.rate(WebViewPerf.Metric.FCP, 1801))
        assertEquals(WebViewPerf.Rating.POOR, WebViewPerf.rate(WebViewPerf.Metric.FCP, 3001))
        assertEquals(WebViewPerf.Rating.GOOD, WebViewPerf.rate(WebViewPerf.Metric.LCP, 2500))
        assertEquals(WebViewPerf.Rating.POOR, WebViewPerf.rate(WebViewPerf.Metric.LCP, 4001))
    }

    @Test fun formatSwitchesToSeconds() {
        assertEquals("820 ms", WebViewPerf.format(820))
        assertEquals("2.35 s", WebViewPerf.format(2350))
    }
}

class QuotaMathTest {
    private val choices = listOf(64, 128, 256, 512)
    private fun mb(n: Long) = n * 1024 * 1024

    @org.junit.Test fun exactQuotaPicksItsChoice() {
        org.junit.Assert.assertEquals(0, dev.dsh.miuixbrowser.engine.webview.QuotaMath.nearestChoice(mb(64), choices))
        org.junit.Assert.assertEquals(2, dev.dsh.miuixbrowser.engine.webview.QuotaMath.nearestChoice(mb(256), choices))
    }

    @org.junit.Test fun clampedQuotaPicksNearest() {
        org.junit.Assert.assertEquals(0, dev.dsh.miuixbrowser.engine.webview.QuotaMath.nearestChoice(mb(10), choices))
        org.junit.Assert.assertEquals(1, dev.dsh.miuixbrowser.engine.webview.QuotaMath.nearestChoice(mb(150), choices))
        org.junit.Assert.assertEquals(3, dev.dsh.miuixbrowser.engine.webview.QuotaMath.nearestChoice(mb(4000), choices))
    }
}
