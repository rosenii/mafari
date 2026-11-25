/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DynamicContentScriptStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun extension(): InstalledExtension {
        val dir = tmp.newFolder("extension")
        File(dir, "manifest.json").writeText("""{"manifest_version":3,"name":"Demo","version":"1"}""")
        File(dir, "a.js").writeText("window.a = true")
        return TestExt.make(dir, manifest = """{"manifest_version":3,"name":"Demo","version":"1"}""", grantedHosts = emptyList())
    }

    private fun entry(persist: Boolean) = DynamicContentScriptStore.Entry(
        id = if (persist) "persisted" else "session",
        spec = ContentScriptSpec(
            matches = listOf(MatchPattern.parse("https://example.com/*")!!),
            excludeMatches = emptyList(), js = listOf("a.js"), css = emptyList(),
            runAt = RunAt.DOCUMENT_START, allFrames = false, matchAboutBlank = false, world = ScriptWorld.ISOLATED,
        ),
        persistAcrossSessions = persist,
    )

    @Test fun persistentRegistrationsRestoreAndSessionRegistrationsDoNot() {
        val ext = extension()
        val store = DynamicContentScriptStore()
        store.register(ext, listOf(entry(true), entry(false)))
        assertEquals(setOf("persisted", "session"), store.all(ext).map { it.id }.toSet())

        val restored = DynamicContentScriptStore().all(ext)
        assertEquals(listOf("persisted"), restored.map { it.id })
        assertTrue(File(ext.rootDir, ".mafari-dynamic-content-scripts.json").readText().contains("schemaVersion"))
    }

    @Test fun corruptStateIsQuarantinedInsteadOfSilentlyOverwritten() {
        val ext = extension()
        val state = File(ext.rootDir, ".mafari-dynamic-content-scripts.json")
        state.writeText("{not-json")
        assertTrue(DynamicContentScriptStore().all(ext).isEmpty())
        assertFalse(state.exists())
        assertTrue(ext.rootDir.listFiles().orEmpty().any { it.name.startsWith(state.name + ".corrupt-") })
    }

    @Test fun restoredEntryIsDroppedWhenItsScriptFileIsMissing() {
        val ext = extension()
        DynamicContentScriptStore().register(ext, listOf(entry(true)))
        File(ext.rootDir, "a.js").delete()
        assertTrue(DynamicContentScriptStore().all(ext).isEmpty())
    }


    @Test fun legacyArrayIsMigratedToVersionedState() {
        val ext = extension()
        val legacy = JSONArray().put(
            JSONObject()
                .put("id", "legacy")
                .put("matches", JSONArray(listOf("https://example.com/*")))
                .put("excludeMatches", JSONArray())
                .put("js", JSONArray(listOf("a.js")))
                .put("css", JSONArray())
                .put("runAt", "document_start")
                .put("allFrames", false)
                .put("persistAcrossSessions", true)
        )
        val state = File(ext.rootDir, ".mafari-dynamic-content-scripts.json")
        state.writeText(legacy.toString())
        assertEquals(listOf("legacy"), DynamicContentScriptStore().all(ext).map { it.id })
        assertTrue(state.readText().startsWith("{"))
    }

    @Test fun persistedFileCannotReanimateSessionOnlyRegistration() {
        val ext = extension()
        val item = JSONObject()
            .put("id", "session")
            .put("matches", JSONArray(listOf("https://example.com/*")))
            .put("js", JSONArray(listOf("a.js")))
            .put("persistAcrossSessions", false)
        val state = JSONObject()
            .put("schemaVersion", 1)
            .put("extensionId", ext.spec.id)
            .put("scripts", JSONArray().put(item))
        File(ext.rootDir, ".mafari-dynamic-content-scripts.json").writeText(state.toString())
        assertTrue(DynamicContentScriptStore().all(ext).isEmpty())
    }

    @Test fun unknownSchemaVersionIsQuarantined() {
        val ext = extension()
        File(ext.rootDir, ".mafari-dynamic-content-scripts.json").writeText(
            JSONObject().put("schemaVersion", 999).put("extensionId", ext.spec.id).put("scripts", JSONArray()).toString()
        )
        assertTrue(DynamicContentScriptStore().all(ext).isEmpty())
        assertTrue(ext.rootDir.listFiles().orEmpty().any { it.name.contains("corrupt-") })
    }
}
