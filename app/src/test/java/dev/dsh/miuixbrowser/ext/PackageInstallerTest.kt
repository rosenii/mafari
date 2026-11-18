package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackageInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo by lazy { FileExtensionRepository(File(tmp.root, "ext")) }
    private val installer by lazy { PackageInstaller(repo, File(tmp.root, "work")) }

    private fun pkg(vararg entries: Pair<String, String>) =
        tmp.newFile().apply { writeBytes(CrxTest.zipBytes(*entries)) }

    private fun mf(extra: String = "") =
        """{"manifest_version":2,"name":"Demo","version":"1.0"$extra}"""

    @Test fun geckoIdBecomesExtensionIdAndBothEnginesAllowed() {
        val p = installer.prepare(
            pkg("manifest.json" to mf(""","applications":{"gecko":{"id":"demo@x.org"}}"""), "bg.js" to "1")
        )
        assertEquals("demo@x.org", p.spec.id)
        assertEquals(PackageFormat.ZIP, p.format)
        assertEquals(setOf(EngineKind.WEBVIEW, EngineKind.GECKO), p.engines)
        val ext = installer.commit(p, setOf("storage"), emptyList(), now = 100)
        assertTrue(ext.enabled)
        assertEquals(100L, ext.installedAt)
        assertTrue(File(repo.extensionDir("demo@x.org"), "bg.js").isFile)
        assertFalse(p.stagingDir.exists())
        assertEquals(1, repo.list().size)
    }

    @Test fun updateKeepsInstalledAtAndEnabledState() {
        val file = { pkg("manifest.json" to mf(""","applications":{"gecko":{"id":"demo@x.org"}}""")) }
        installer.commit(installer.prepare(file()), emptySet(), emptyList(), enabled = false, now = 10)
        val updated = installer.commit(installer.prepare(file(), forcedId = "demo@x.org"), emptySet(), emptyList(), now = 99)
        assertEquals(10L, updated.installedAt)
        assertFalse(updated.enabled)
    }

    @Test fun singleTopLevelFolderIsFlattened() {
        val p = installer.prepare(pkg("my-ext/manifest.json" to mf(), "my-ext/js/a.js" to "1"))
        assertTrue(File(p.stagingDir, "manifest.json").isFile)
        assertTrue(File(p.stagingDir, "js/a.js").isFile)
        installer.discard(p)
        assertFalse(p.stagingDir.exists())
    }

    @Test fun missingManifestAndEscapingPathsFailWithoutLeavingStaging() {
        for (f in listOf(pkg("a.js" to "1"), pkg("../evil" to "1"))) {
            try {
                installer.prepare(f)
                fail()
            } catch (_: PackageException) {
            }
        }
        val leftovers = File(tmp.root, "work").listFiles { x -> x.name.startsWith("staging_") }
        assertTrue(leftovers.isNullOrEmpty())
    }

    @Test fun serviceWorkerOnlyAndCrxAreNotOfferedToGecko() {
        val sw = installer.prepare(
            pkg("manifest.json" to """{"manifest_version":3,"name":"S","version":"1","background":{"service_worker":"sw.js"}}""")
        )
        assertEquals(setOf(EngineKind.WEBVIEW), sw.engines)

        val kp = CrxTest.newKey()
        val crx = tmp.newFile("c.crx").apply {
            writeBytes(CrxTest.buildCrx3(kp, CrxTest.zipBytes("manifest.json" to mf())))
        }
        val p = installer.prepare(crx)
        assertEquals(PackageFormat.CRX, p.format)
        assertEquals(ExtensionIds.fromPublicKey(kp.public.encoded), p.spec.id)
        assertEquals(setOf(EngineKind.WEBVIEW), p.engines)

        try {
            installer.prepare(crx, forcedId = "someone-else")
            fail("ID 不一致应拒绝")
        } catch (_: PackageException) {
        }
    }

    @Test fun noIdSourceFallsBackToStableLocalId() {
        val a = installer.prepare(pkg("manifest.json" to mf()))
        val b = installer.prepare(pkg("manifest.json" to mf()))
        assertTrue(a.spec.id.startsWith("local-"))
        assertEquals(a.spec.id, b.spec.id)
    }
}
