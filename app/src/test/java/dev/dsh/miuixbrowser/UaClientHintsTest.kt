/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.engine.webview.UaClientHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UaClientHintsTest {
    private val phone = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val desktopWin = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val mac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
    private val iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"
    private val linux = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    @Test fun androidPhoneIsMobile() {
        val h = UaClientHints.parse(phone)!!
        assertEquals("Android", h.platform)
        assertTrue(h.mobile)
        assertEquals("", h.model) // 精简 UA 里的 "K" 不是真机型
        assertEquals("10.0.0", h.platformVersion)
    }

    @Test fun androidTabletIsNotMobile() {
        val tablet = "Mozilla/5.0 (Linux; Android 13; SM-X700) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        val h = UaClientHints.parse(tablet)!!
        assertFalse(h.mobile)
        assertEquals("SM-X700", h.model)
        assertEquals("13.0.0", h.platformVersion)
    }

    @Test fun desktopPlatformsParse() {
        val w = UaClientHints.parse(desktopWin)!!
        assertEquals("Windows", w.platform); assertFalse(w.mobile); assertEquals(64, w.bitness); assertEquals("x86", w.architecture)
        val m = UaClientHints.parse(mac)!!
        assertEquals("macOS", m.platform); assertEquals("10.15.7", m.platformVersion)
        val l = UaClientHints.parse(linux)!!
        assertEquals("Linux", l.platform); assertEquals(64, l.bitness)
    }

    @Test fun iosParses() {
        val h = UaClientHints.parse(iphone)!!
        assertEquals("iOS", h.platform)
        assertEquals("17.2.0", h.platformVersion)
        assertTrue(h.mobile)
    }

    @Test fun unknownUaIsNull() {
        assertNull(UaClientHints.parse("curl/8.0"))
        assertNull(UaClientHints.parse(""))
    }

    @Test fun sameAsDefaultDoesNotOverride() {
        // 默认 UA 本身，或另一个 Android 手机 Chrome UA：保持 WebView 的真实客户端提示
        assertNull(UaClientHints.overrideFor(phone, phone))
        assertNull(UaClientHints.overrideFor(phone.replace("130", "125"), phone))
    }

    @Test fun desktopUaOverridesPhoneDefault() {
        val o = UaClientHints.overrideFor(desktopWin, phone)
        assertNotNull(o)
        assertEquals("Windows", o!!.platform)
        assertFalse(o.mobile)
    }

    @Test fun phoneUaOverridesDesktopDefault() {
        // 桌面 → 手机的反向切换也要覆盖（默认是桌面时）
        val o = UaClientHints.overrideFor(phone, desktopWin)
        assertNotNull(o)
        assertEquals("Android", o!!.platform)
        assertTrue(o.mobile)
    }

    @Test fun unparsableTargetFallsBackToDefault() {
        assertNull(UaClientHints.overrideFor("curl/8.0", phone))
    }
}
