package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Test

class DeclarativeNetRequestApiTest {
    @Test fun urlFilterSupportsDomainAnchorsAndSeparator() {
        assertTrue(DeclarativeNetRequestApi.matchesUrlFilter("||ads.example^", "https://ads.example/banner.js"))
        assertTrue(DeclarativeNetRequestApi.matchesUrlFilter("||ads.example^", "https://cdn.ads.example/banner.js"))
        assertFalse(DeclarativeNetRequestApi.matchesUrlFilter("||ads.example^", "https://notads.example/banner.js"))
        assertFalse(DeclarativeNetRequestApi.matchesUrlFilter("||ads.example^", "https://ads.example.evil.test/banner.js"))
    }

    @Test fun urlFilterSupportsStartAndEndAnchors() {
        assertTrue(DeclarativeNetRequestApi.matchesUrlFilter("|https://example.com/path|", "https://example.com/path"))
        assertFalse(DeclarativeNetRequestApi.matchesUrlFilter("|https://example.com/path|", "https://example.com/path/extra"))
        assertTrue(DeclarativeNetRequestApi.matchesUrlFilter("example.com/*/image", "https://example.com/a/image?id=1"))
    }
}
