package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileExtensionRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val manifest = """{"manifest_version":2,"name":"X","version":"1.0","permissions":["storage","https://a.com/*"]}"""

    private fun install(repo: FileExtensionRepository, id: String, at: Long = 1L): InstalledExtension {
        val dir = repo.extensionDir(id).apply { mkdirs() }
        File(dir, "manifest.json").writeText(manifest)
        val ext = InstalledExtension(
            spec = ManifestParser.parse(manifest, id),
            rootDir = dir,
            enabled = false,
            grantedApiPermissions = setOf("storage"),
            grantedHostPermissions = listOfNotNull(MatchPattern.parse("https://a.com/*")),
            installedAt = at,
            sourceUrl = "https://example.com/x.xpi",
            engines = setOf(EngineKind.WEBVIEW, EngineKind.GECKO),
        )
        repo.put(ext)
        return ext
    }

    @Test fun roundTripSurvivesRestart() {
        val base = File(tmp.root, "ext")
        install(FileExtensionRepository(base), "gm@mafari.dev", at = 5)
        val again = FileExtensionRepository(base)
        val e = again.get("gm@mafari.dev")!!
        assertFalse(e.enabled)
        assertEquals(setOf("storage"), e.grantedApiPermissions)
        assertEquals(listOf("https://a.com/*"), e.grantedHostPermissions.map { it.raw })
        assertEquals(5L, e.installedAt)
        assertEquals("https://example.com/x.xpi", e.sourceUrl)
        assertEquals(setOf(EngineKind.WEBVIEW, EngineKind.GECKO), e.engines)
        assertEquals("X", e.spec.name)
        assertEquals(1, again.list().size)
    }

    @Test fun removeDeletesPackageStateAndData() {
        val base = File(tmp.root, "ext")
        val repo = FileExtensionRepository(base)
        install(repo, "abc")
        repo.dataDir("abc").apply { mkdirs() }.resolve("k").writeText("v")
        repo.remove("abc")
        assertNull(repo.get("abc"))
        assertFalse(repo.extensionDir("abc").exists())
        assertFalse(repo.dataDir("abc").exists())
        assertTrue(FileExtensionRepository(base).list().isEmpty())
    }

    @Test fun unsafeIdsAreRejected() {
        val repo = FileExtensionRepository(File(tmp.root, "ext"))
        for (bad in listOf("../x", "a/b", "", ".x")) {
            try {
                repo.extensionDir(bad)
                fail("应当拒绝：$bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun corruptEntryIsSkippedAndReported() {
        val base = File(tmp.root, "ext")
        install(FileExtensionRepository(base), "good")
        File(base, "state").resolve("bad.json").writeText("{ not json")
        val errors = mutableListOf<String>()
        val repo = FileExtensionRepository(base) { id, _ -> errors.add(id) }
        assertEquals(listOf("good"), repo.list().map { it.spec.id })
        assertEquals(listOf("bad"), errors)
    }
}
