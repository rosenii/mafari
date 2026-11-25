/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

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
