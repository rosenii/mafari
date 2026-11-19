package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Test

class MatchPatternTest {
    private fun p(s: String) = MatchPattern.parse(s)!!

    @Test fun allUrls() {
        assertTrue(p("<all_urls>").matches("https://a.com/x"))
        assertTrue(p("<all_urls>").matches("file:///sdcard/a.html"))
    }

    @Test fun wildcardSchemeOnlyWeb() {
        val m = p("*://example.com/*")
        assertTrue(m.matches("http://example.com/"))
        assertTrue(m.matches("https://example.com/a?b=1"))
        assertFalse(m.matches("ftp://example.com/"))
    }

    @Test fun subdomains() {
        val m = p("https://*.example.com/*")
        assertTrue(m.matches("https://example.com/"))
        assertTrue(m.matches("https://a.b.example.com/x"))
        assertFalse(m.matches("https://badexample.com/"))
    }

    @Test fun pathAndQuery() {
        val m = p("https://example.com/a/*/c")
        assertTrue(m.matches("https://example.com/a/b/c"))
        assertFalse(m.matches("https://example.com/a/b/d"))
        assertTrue(p("https://example.com/s?q=*").matches("https://example.com/s?q=hi#frag"))
    }

    @Test fun portHandling() {
        assertTrue(p("http://localhost/*").matches("http://localhost:8080/x"))
        assertTrue(p("http://localhost:8080/*").matches("http://localhost:8080/x"))
        assertFalse(p("http://localhost:8080/*").matches("http://localhost:9090/x"))
        assertTrue(p("https://a.com:443/*").matches("https://a.com/"))
    }

    @Test fun invalid() {
        assertNull(MatchPattern.parse("example.com"))
        assertNull(MatchPattern.parse("https://example.com"))
        assertNull(MatchPattern.parse("https://ex*mple.com/*"))
        assertNull(MatchPattern.parse("gopher://a.com/*"))
        assertNull(MatchPattern.parse("https:///*"))
    }

    @Test fun originRules() {
        assertEquals(setOf("*"), p("<all_urls>").toOriginRules())
        assertEquals(setOf("https://example.com", "https://*.example.com"), p("https://*.example.com/*").toOriginRules())
        assertEquals(setOf("http://a.com", "https://a.com"), p("*://a.com/*").toOriginRules())
        assertTrue(p("file:///*").toOriginRules().isEmpty())
    }
    @Test fun coversRequestedPatternsConservatively() {
        assertTrue(p("<all_urls>").covers(p("https://example.com/*")))
        assertTrue(p("https://*.example.com/*").covers(p("https://a.example.com/*")))
        assertTrue(p("https://example.com/*").covers(p("https://example.com/path/*")))
        assertFalse(p("https://example.com/path/*").covers(p("https://example.com/*")))
        assertFalse(p("https://example.com/*").covers(p("http://example.com/*")))
    }

}
