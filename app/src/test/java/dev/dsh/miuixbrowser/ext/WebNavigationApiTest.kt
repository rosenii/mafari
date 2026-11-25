/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNavigationApiTest {
    private fun extension(): InstalledExtension {
        val host = MatchPattern.parse("https://example.com/*")!!
        val spec = ExtensionSpec(
            id = "webnav@test",
            name = "WebNav Test",
            version = "1",
            description = "",
            manifestVersion = ManifestVersion.MV3,
            apiPermissions = setOf("webNavigation"),
            optionalApiPermissions = emptySet(),
            hostPermissions = listOf(host),
            optionalHostPermissions = emptyList(),
            contentScripts = emptyList(),
            background = null,
            action = null,
            optionsPage = null,
            webAccessibleResources = emptyList(),
            icons = emptyMap(),
            geckoId = null,
            compat = emptyList(),
        )
        return InstalledExtension(
            spec = spec,
            rootDir = File("."),
            enabled = true,
            grantedApiPermissions = setOf("webNavigation"),
            grantedHostPermissions = listOf(host),
            installedAt = 0,
            sourceUrl = null,
            engines = setOf(EngineKind.WEBVIEW),
        )
    }

    @Test fun redirectLifecycleEmitsOneBeforeThenCommitAndComplete() {
        val received = mutableListOf<Pair<String, JSONObject>>()
        val ext = extension()
        val api = WebNavigationApi(EventSink { _, event, payload -> received += event to payload }, { listOf(ext) }) { null }

        api.beforeNavigate(4, "https://example.com/start", "link")
        // 重定向后的 onPageStarted 只补缺，不应再次发送 beforeNavigate。
        api.ensureBeforeNavigate(4, "https://example.com/final")
        api.committed(4, "https://example.com/final")
        api.completed(4, "https://example.com/final")
        api.completed(4, "https://example.com/final") // 重复 finish 不重复发事件

        assertEquals(
            listOf("webNavigation.onBeforeNavigate", "webNavigation.onCommitted", "webNavigation.onCompleted"),
            received.map { it.first },
        )
        assertEquals("https://example.com/final", received[1].second.getString("url"))
        assertEquals("link", received[1].second.getString("transitionType"))
        assertEquals(0, received[1].second.getInt("frameId"))
    }

    @Test fun failedNavigationDoesNotBecomeCompletedWhenErrorPageFinishes() {
        val events = mutableListOf<String>()
        val ext = extension()
        val api = WebNavigationApi(EventSink { _, event, _ -> events += event }, { listOf(ext) }) { null }

        api.beforeNavigate(2, "https://example.com/offline")
        api.errorOccurred(2, "https://example.com/offline", "net::ERR_INTERNET_DISCONNECTED")
        api.completed(2, "https://example.com/offline") // WebView 错误页可能沿用失败 URL

        assertEquals(listOf("webNavigation.onBeforeNavigate", "webNavigation.onErrorOccurred"), events)
        assertTrue(events.contains("webNavigation.onErrorOccurred"))
        assertFalse(events.contains("webNavigation.onCompleted"))
    }

    @Test fun hostPermissionFiltersNavigationEvents() {
        val received = mutableListOf<String>()
        val ext = extension()
        val api = WebNavigationApi(EventSink { _, event, _ -> received += event }, { listOf(ext) }) { null }
        api.beforeNavigate(1, "https://outside.test/")
        assertTrue(received.isEmpty())
    }
}
