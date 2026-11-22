package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.net.HttpFetcher
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpsOnlyTest {

    private fun rejects(url: String, allowLocal: Boolean = false) {
        try {
            HttpFetcher.requireHttps(url, allowLocal)
            fail("should reject $url")
        } catch (_: IOException) {
        }
    }

    @Test fun httpsPasses() {
        HttpFetcher.requireHttps("https://api.github.com/repos/x/y")
        HttpFetcher.requireHttps("HTTPS://example.com")
    }

    @Test fun plainHttpAndOtherSchemesRejected() {
        rejects("http://example.com/a.apk")
        rejects("ftp://example.com/a")
        rejects("not a url")
    }

    @Test fun localHttpOnlyWhenAllowed() {
        rejects("http://192.168.1.5:8080/ext.xpi")
        HttpFetcher.requireHttps("http://192.168.1.5:8080/ext.xpi", allowLocal = true)
        HttpFetcher.requireHttps("http://localhost:3000/ext.zip", allowLocal = true)
        rejects("http://example.com/ext.zip", allowLocal = true)
    }

    @Test fun localHostClassification() {
        for (h in listOf("localhost", "127.0.0.1", "10.0.0.2", "172.16.0.1", "172.31.255.1", "192.168.0.9", "nas.local", "[::1]")) {
            assertTrue(h, HttpFetcher.isLocalHost(h))
        }
        for (h in listOf("172.32.0.1", "172.15.0.1", "8.8.8.8", "192.169.0.1", "example.com", "999.1.1.1", "", "10.0.0")) {
            assertFalse(h, HttpFetcher.isLocalHost(h))
        }
    }
}
