/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMappingTest {
    @Test fun transitionPrefersReloadThenPageInitiated() {
        assertEquals("reload", NavigationMapping.transitionType(isReload = true, wasInitiatedByPage = true))
        assertEquals("link", NavigationMapping.transitionType(isReload = false, wasInitiatedByPage = true))
        assertEquals("typed", NavigationMapping.transitionType(isReload = false, wasInitiatedByPage = false))
    }

    @Test fun qualifiersReflectHistoryAndServerRedirect() {
        assertEquals(emptyList<String>(), NavigationMapping.qualifiers(false, false))
        assertEquals(listOf("forward_back"), NavigationMapping.qualifiers(true, false))
        assertEquals(listOf("server_redirect"), NavigationMapping.qualifiers(false, true))
        assertEquals(listOf("forward_back", "server_redirect"), NavigationMapping.qualifiers(true, true))
    }

    @Test fun nonPositiveStatusIsUnknown() {
        assertNull(NavigationMapping.statusOrNull(0))
        assertNull(NavigationMapping.statusOrNull(-1))
        assertEquals(404, NavigationMapping.statusOrNull(404))
    }

    @Test fun headersAcceptSingleAndMultiValueMapsAndRejectOtherShapes() {
        val single = NavigationMapping.headersJson(mapOf("Content-Type" to "text/html"))!!
        assertEquals(1, single.length())
        assertEquals("Content-Type", single.getJSONObject(0).getString("name"))
        assertEquals("text/html", single.getJSONObject(0).getString("value"))

        val multi = NavigationMapping.headersJson(mapOf("Set-Cookie" to listOf("a=1", "b=2")))!!
        assertEquals(2, multi.length())
        assertEquals("b=2", multi.getJSONObject(1).getString("value"))

        assertNull(NavigationMapping.headersJson(null))
        assertNull(NavigationMapping.headersJson("not a map"))
    }
}

class WebNavigationListenerDrivenTest {
    private fun extension(): InstalledExtension {
        val host = MatchPattern.parse("https://example.com/*")!!
        val spec = ExtensionSpec(
            id = "webnav-listener@test", name = "t", version = "1", description = "",
            manifestVersion = ManifestVersion.MV3, apiPermissions = setOf("webNavigation"),
            optionalApiPermissions = emptySet(), hostPermissions = listOf(host), optionalHostPermissions = emptyList(),
            contentScripts = emptyList(), background = null, action = null, optionsPage = null,
            webAccessibleResources = emptyList(), icons = emptyMap(), geckoId = null, compat = emptyList(),
        )
        return InstalledExtension(
            spec = spec, rootDir = File("."), enabled = true, grantedApiPermissions = setOf("webNavigation"),
            grantedHostPermissions = listOf(host), installedAt = 0, sourceUrl = null, engines = setOf(EngineKind.WEBVIEW),
        )
    }

    @Test fun committedCarriesTransitionQualifiers() {
        val received = mutableListOf<Pair<String, JSONObject>>()
        val ext = extension()
        val api = WebNavigationApi(EventSink { _, e, p -> received += e to p }, { listOf(ext) }) { null }
        api.beforeNavigate(3, "https://example.com/a", "link")
        api.committed(3, "https://example.com/b", "link", listOf("forward_back", "server_redirect"))
        val committed = received.first { it.first == "webNavigation.onCommitted" }.second
        assertEquals("link", committed.getString("transitionType"))
        assertEquals(2, committed.getJSONArray("transitionQualifiers").length())
        assertEquals("server_redirect", committed.getJSONArray("transitionQualifiers").getString(1))
    }

    @Test fun domContentLoadedIsEmittedAndListenerFlagLivesPerTab() {
        val received = mutableListOf<String>()
        val ext = extension()
        val api = WebNavigationApi(EventSink { _, e, _ -> received += e }, { listOf(ext) }) { null }
        api.domContentLoaded(5, "https://example.com/")
        assertEquals(listOf("webNavigation.onDOMContentLoaded"), received)

        assertFalse(api.isListenerDriven(5))
        api.setListenerDriven(5, true)
        assertTrue(api.isListenerDriven(5))
        api.forgetTab(5)
        assertFalse(api.isListenerDriven(5))
    }
}
