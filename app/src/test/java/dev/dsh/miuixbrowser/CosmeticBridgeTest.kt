package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.engine.webview.CosmeticBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CosmeticBridgeTest {

    @Test fun hostComesFromOriginNotFromPage() {
        val r = CosmeticBridge.parseRequest(true, "Top.Example.org", "ads.example.com")
        assertEquals("ads.example.com" to "top.example.org", r)
    }

    @Test fun invalidTopHostFallsBackToFrameHost() {
        assertEquals("a.com" to "a.com", CosmeticBridge.parseRequest(true, "evil.com/x?y", "a.com"))
        assertEquals("a.com" to "a.com", CosmeticBridge.parseRequest(true, "", "a.com"))
        assertEquals("a.com" to "a.com", CosmeticBridge.parseRequest(true, null, "a.com"))
        assertEquals("a.com" to "a.com", CosmeticBridge.parseRequest(true, "a".repeat(300), "a.com"))
    }

    @Test fun acceptsIpv6TopHost() {
        assertEquals("a.com" to "[::1]", CosmeticBridge.parseRequest(true, "[::1]", "a.com"))
    }

    @Test fun rejectsNonStringAndMissingOriginHost() {
        assertNull(CosmeticBridge.parseRequest(false, "a.com", "a.com"))
        assertNull(CosmeticBridge.parseRequest(true, "a.com", null))
        assertNull(CosmeticBridge.parseRequest(true, "a.com", ""))
    }
}
