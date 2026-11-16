package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.util.ErrorKind
import dev.dsh.miuixbrowser.util.ErrorPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：错误码 → 文案分类、HTTP 状态码文案、HTML 转义。 */
class ErrorPagesTest {

    @Test
    fun webView_prefersNetName() {
        assertEquals(ErrorKind.OFFLINE, ErrorPages.fromWebView(-2, "net::ERR_INTERNET_DISCONNECTED").kind)
        assertEquals(ErrorKind.DNS, ErrorPages.fromWebView(-2, "net::ERR_NAME_NOT_RESOLVED").kind)
        assertEquals(ErrorKind.CERT_DATE, ErrorPages.fromWebView(-11, "net::ERR_CERT_DATE_INVALID").kind)
        assertEquals(ErrorKind.CERT_AUTHORITY, ErrorPages.fromWebView(-11, "net::ERR_CERT_AUTHORITY_INVALID").kind)
        assertEquals(ErrorKind.CERT_NAME, ErrorPages.fromWebView(-11, "net::ERR_CERT_COMMON_NAME_INVALID").kind)
        assertEquals(ErrorKind.SSL_PROTOCOL, ErrorPages.fromWebView(-11, "net::ERR_SSL_PROTOCOL_ERROR").kind)
        assertEquals(ErrorKind.CONNECTION_REFUSED, ErrorPages.fromWebView(-6, "net::ERR_CONNECTION_REFUSED").kind)
        assertEquals(ErrorKind.CONNECTION_RESET, ErrorPages.fromWebView(-6, "net::ERR_CONNECTION_RESET").kind)
        assertEquals(ErrorKind.TIMEOUT, ErrorPages.fromWebView(-8, "net::ERR_CONNECTION_TIMED_OUT").kind)
        assertEquals(ErrorKind.PROXY, ErrorPages.fromWebView(-6, "net::ERR_PROXY_CONNECTION_FAILED").kind)
        assertEquals(ErrorKind.TOO_MANY_REDIRECTS, ErrorPages.fromWebView(-9, "net::ERR_TOO_MANY_REDIRECTS").kind)
        assertEquals(ErrorKind.PORT_BLOCKED, ErrorPages.fromWebView(-6, "net::ERR_UNSAFE_PORT").kind)
        assertEquals("ERR_NAME_NOT_RESOLVED", ErrorPages.fromWebView(-2, "net::ERR_NAME_NOT_RESOLVED").code)
    }

    @Test
    fun webView_fallsBackToErrorCode() {
        assertEquals(ErrorKind.DNS, ErrorPages.fromWebView(-2, "").kind)
        assertEquals(ErrorKind.TIMEOUT, ErrorPages.fromWebView(-8, null).kind)
        assertEquals(ErrorKind.UNSUPPORTED_SCHEME, ErrorPages.fromWebView(-10, "未知").kind)
        assertEquals(ErrorKind.UNKNOWN, ErrorPages.fromWebView(-999, "weird").kind)
        // 名字没收录时也回落到 errorCode，而不是直接未知
        assertEquals(ErrorKind.TIMEOUT, ErrorPages.fromWebView(-8, "net::ERR_SOMETHING_NEW").kind)
    }

    @Test
    fun http_commonStatuses() {
        assertEquals("页面不存在", ErrorPages.fromHttp(404).title)
        assertEquals("没有访问权限", ErrorPages.fromHttp(403).title)
        assertEquals("需要登录", ErrorPages.fromHttp(401).title)
        assertEquals("请求过于频繁", ErrorPages.fromHttp(429).title)
        assertEquals("网关错误", ErrorPages.fromHttp(502).title)
        assertEquals("网站暂时不可用", ErrorPages.fromHttp(503).title)
        assertEquals("网关超时", ErrorPages.fromHttp(504).title)
        assertEquals("访问被服务器拒绝", ErrorPages.fromHttp(499).title)
        assertEquals("网站出了点问题", ErrorPages.fromHttp(599).title)
        assertEquals("404", ErrorPages.fromHttp(404).code)
        assertTrue(ErrorPages.fromHttp(404).tips.isNotEmpty())
    }

    @Test
    fun http_onlyReplacesEmptyBodies() {
        assertTrue(ErrorPages.shouldReplaceHttpError(502, mapOf("Content-Length" to "0")))
        assertTrue(ErrorPages.shouldReplaceHttpError(404, mapOf("content-length" to " 0 ")))
        assertFalse(ErrorPages.shouldReplaceHttpError(404, mapOf("Content-Length" to "1234")))
        assertFalse(ErrorPages.shouldReplaceHttpError(404, emptyMap()))
        assertFalse(ErrorPages.shouldReplaceHttpError(200, mapOf("Content-Length" to "0")))
        assertFalse(ErrorPages.shouldReplaceHttpError(404, null))
    }

    @Test
    fun everyKindHasCopy() {
        for (k in ErrorKind.values()) {
            val s = ErrorPages.fromKind(k, "X")
            assertTrue("$k title", s.title.isNotBlank())
            assertTrue("$k summary", s.summary.isNotBlank())
            assertTrue("$k tips", s.tips.isNotEmpty())
        }
    }

    @Test
    fun render_escapesAndKeepsRetryUrl() {
        val spec = ErrorPages.fromWebView(-2, "net::ERR_NAME_NOT_RESOLVED")
        val html = ErrorPages.render(spec, "https://exa\"mple.com/<x>?a=1&b=2", dark = false)
        assertFalse(html.contains("<x>"))
        assertTrue(html.contains("&lt;x&gt;"))
        assertTrue(html.contains("&quot;"))
        assertTrue(html.contains("href=\"https://exa&quot;mple.com/&lt;x&gt;?a=1&amp;b=2\""))
        assertTrue(html.contains("找不到该网站"))
        assertTrue(ErrorPages.render(spec, "https://a.b", dark = true).contains("#1A1A1A"))
    }

    @Test
    fun proceed_onlyForOverridableCertErrors() {
        for (k in listOf(ErrorKind.CERT_DATE, ErrorKind.CERT_AUTHORITY, ErrorKind.CERT_NAME, ErrorKind.CERT_OTHER)) {
            assertTrue("$k", ErrorPages.fromKind(k, "X").canProceed)
        }
        for (k in listOf(ErrorKind.CERT_REVOKED, ErrorKind.SSL_PROTOCOL, ErrorKind.UNSAFE_SITE, ErrorKind.DNS, ErrorKind.HTTPS_ONLY)) {
            assertFalse("$k", ErrorPages.fromKind(k, "X").canProceed)
        }
        assertFalse(ErrorPages.fromHttp(404).canProceed)
    }

    @Test
    fun proceed_renderShowsAdvancedLinkOnlyWhenAllowed() {
        val spec = ErrorPages.fromWebView(-11, "net::ERR_CERT_AUTHORITY_INVALID")
        val html = ErrorPages.render(spec, "https://self.signed/", dark = false)
        assertTrue(html.contains("href=\"mafari-error://proceed\""))
        assertTrue(html.contains("忽略风险，继续访问"))
        assertFalse(ErrorPages.render(ErrorPages.withoutProceed(spec), "https://self.signed/", dark = false).contains("mafari-error://"))
        assertFalse(ErrorPages.render(ErrorPages.fromWebView(-2, "net::ERR_NAME_NOT_RESOLVED"), "https://a.b/", dark = false).contains("mafari-error://"))
        assertTrue(ErrorPages.render(spec, "https://self.signed/", dark = false, nativeProceed = true).contains("证书页"))
    }

    @Test
    fun proceed_urlAndPrivateHost() {
        assertTrue(ErrorPages.isProceedUrl("mafari-error://proceed"))
        assertFalse(ErrorPages.isProceedUrl("mafari-error://proceed?u=https://bank.com"))
        assertFalse(ErrorPages.isProceedUrl(null))
        for (h in listOf("192.168.1.2", "10.0.0.5", "172.16.0.1", "127.0.0.1", "localhost", "nas.local", "nas", "[::1]", "169.254.1.1")) assertTrue(h, ErrorPages.isPrivateHost(h))
        for (h in listOf("example.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "")) assertFalse(h, ErrorPages.isPrivateHost(h))
    }

    @Test
    fun sslError_mapsPrimaryErrorToOverridableCertKinds() {
        assertEquals(ErrorKind.CERT_DATE, ErrorPages.fromSslError(0).kind)
        assertEquals(ErrorKind.CERT_DATE, ErrorPages.fromSslError(1).kind)
        assertEquals(ErrorKind.CERT_NAME, ErrorPages.fromSslError(2).kind)
        assertEquals(ErrorKind.CERT_AUTHORITY, ErrorPages.fromSslError(3).kind)
        assertEquals(ErrorKind.CERT_DATE, ErrorPages.fromSslError(4).kind)
        assertEquals(ErrorKind.CERT_OTHER, ErrorPages.fromSslError(5).kind)
        assertEquals(ErrorKind.CERT_OTHER, ErrorPages.fromSslError(99).kind)
        for (c in 0..5) assertTrue("$c", ErrorPages.fromSslError(c).canProceed)
    }

    @Test
    fun sameUrl_ignoresFragmentSlashAndCase() {
        assertTrue(ErrorPages.sameUrl("https://a.com/x#top", "https://a.com/x"))
        assertTrue(ErrorPages.sameUrl("https://A.com/", "https://a.com"))
        assertFalse(ErrorPages.sameUrl("https://a.com/x", "https://a.com/y"))
        assertFalse(ErrorPages.sameUrl("", ""))
        assertFalse(ErrorPages.sameUrl(null, "https://a.com"))
    }
}
