package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebRequestApiTest {
    @Test fun filterMatchesUrlsTypesAndTabId() {
        val filter = JSONObject()
            .put("urls", JSONArray().put("https://*.example.com/*"))
            .put("types", JSONArray().put("script"))
            .put("tabId", 7)
        assertTrue(WebRequestApi.matchesFilter(filter, "https://cdn.example.com/a.js", 7, "script"))
        assertFalse(WebRequestApi.matchesFilter(filter, "https://cdn.example.com/a.js", 8, "script"))
        assertFalse(WebRequestApi.matchesFilter(filter, "https://cdn.example.com/a.png", 7, "image"))
        assertFalse(WebRequestApi.matchesFilter(filter, "https://outside.test/a.js", 7, "script"))
    }

    @Test fun excludedUrlsOverrideIncludedUrls() {
        val filter = JSONObject()
            .put("urls", JSONArray().put("https://*.example.com/*"))
            .put("excludedUrls", JSONArray().put("https://private.example.com/*"))
        assertTrue(WebRequestApi.matchesFilter(filter, "https://cdn.example.com/a", 1, "other"))
        assertFalse(WebRequestApi.matchesFilter(filter, "https://private.example.com/a", 1, "other"))
    }
}
