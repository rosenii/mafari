/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.engine.webview.OneWayBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneWayBridgeTest {
    private val ok = "https://example.com"

    @Test fun acceptsMainFrameHttpsString() {
        assertEquals("{\"a\":1}", OneWayBridge.accept(true, "{\"a\":1}", ok, true, 100))
        assertEquals("x", OneWayBridge.accept(true, "x", "http://example.com:8080", true, 100))
    }

    @Test fun rejectsSubframes() {
        assertNull(OneWayBridge.accept(true, "x", ok, false, 100))
    }

    @Test fun rejectsNonStringAndMissingData() {
        assertNull(OneWayBridge.accept(false, "x", ok, true, 100))
        assertNull(OneWayBridge.accept(true, null, ok, true, 100))
    }

    @Test fun rejectsNonWebOrOpaqueOrigins() {
        assertNull(OneWayBridge.accept(true, "x", "null", true, 100))
        assertNull(OneWayBridge.accept(true, "x", "file://", true, 100))
        assertNull(OneWayBridge.accept(true, "x", "", true, 100))
    }

    @Test fun enforcesLengthLimit() {
        assertEquals("abc", OneWayBridge.accept(true, "abc", ok, true, 3))
        assertNull(OneWayBridge.accept(true, "abcd", ok, true, 3))
    }
}
