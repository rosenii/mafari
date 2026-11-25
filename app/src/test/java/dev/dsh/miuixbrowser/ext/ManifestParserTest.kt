/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Test

class ManifestParserTest {
    @Test fun mv2SplitsPermissionsAndBackground() {
        val s = ManifestParser.parse(
            """{"manifest_version":2,"name":"X","version":"1.0",
              "permissions":["storage","tabs","https://*.a.com/*","<all_urls>"],
              "background":{"scripts":["bg.js"]},
              "browser_action":{"default_title":"t","default_popup":"p.html","default_icon":"i.png"},
              "content_scripts":[{"matches":["https://a.com/*"],"js":["c.js"],"run_at":"document_start"}],
              "web_accessible_resources":["img/*"],
              "applications":{"gecko":{"id":"x@y"}}}""", "id1"
        )
        assertEquals(ManifestVersion.MV2, s.manifestVersion)
        assertEquals(setOf("storage", "tabs"), s.apiPermissions)
        assertEquals(2, s.hostPermissions.size)
        assertTrue(s.background!!.persistent)
        assertEquals("p.html", s.action!!.popup)
        assertEquals(RunAt.DOCUMENT_START, s.contentScripts[0].runAt)
        assertTrue(s.contentScripts[0].appliesTo("https://a.com/x"))
        assertEquals(listOf("img/*"), s.webAccessibleResources[0].resources)
        assertEquals("x@y", s.geckoId)
    }

    @Test fun mv3() {
        val s = ManifestParser.parse(
            """{"manifest_version":3,"name":"X","version":"2",
              "permissions":["scripting","nativeMessaging"],"host_permissions":["https://*/*"],
              "background":{"service_worker":"sw.js"},"action":{},
              "web_accessible_resources":[{"resources":["a.png"],"matches":["https://*/*"]}],
              "side_panel":{"default_path":"p.html"}}""", "id2"
        )
        assertEquals(ManifestVersion.MV3, s.manifestVersion)
        assertEquals("sw.js", s.background!!.serviceWorker)
        assertFalse(s.background!!.persistent)
        val features = s.compat.map { it.feature }
        assertTrue("permission:nativeMessaging" in features)
        assertTrue("manifest:side_panel" in features)
        assertTrue("background.service_worker" in features)
    }

    @Test fun rejectsBadManifest() {
        assertThrows(ManifestException::class.java) { ManifestParser.parse("{", "i") }
        assertThrows(ManifestException::class.java) { ManifestParser.parse("""{"manifest_version":1,"name":"a","version":"1"}""", "i") }
        assertThrows(ManifestException::class.java) { ManifestParser.parse("""{"manifest_version":2,"version":"1"}""", "i") }
    }
}
