package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackageUnpackerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String>) =
        tmp.newFile().apply { writeBytes(CrxTest.zipBytes(*entries)) }

    private fun assertRejected(zip: File, limits: UnpackLimits = UnpackLimits()) {
        val dest = File(tmp.root, "dest_${System.nanoTime()}")
        try {
            PackageUnpacker.unpack(zip, dest, limits)
            fail("应当拒绝")
        } catch (_: PackageException) {
        }
    }

    @Test fun normalPackageUnpacks() {
        val dest = File(tmp.root, "ok")
        PackageUnpacker.unpack(zipOf("manifest.json" to "{}", "js/a.js" to "1", "./b.txt" to "2"), dest)
        assertEquals("1", File(dest, "js/a.js").readText())
        assertEquals("2", File(dest, "b.txt").readText())
    }

    @Test fun zipSlipIsRejected() {
        assertRejected(zipOf("../evil.txt" to "x"))
        assertRejected(zipOf("a/../../evil.txt" to "x"))
        assertRejected(zipOf("..\\evil.txt" to "x"))
        assertRejected(zipOf("/abs/evil.txt" to "x"))
        assertRejected(zipOf("C:/evil.txt" to "x"))
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun caseInsensitiveDuplicateIsRejected() {
        assertRejected(zipOf("A.txt" to "1", "a.txt" to "2"))
    }

    @Test fun limitsAreEnforcedOnActualBytes() {
        val big = "0".repeat(2000)
        assertRejected(zipOf("big.bin" to big), UnpackLimits(maxEntryBytes = 100))
        assertRejected(zipOf("a" to big, "b" to big), UnpackLimits(maxTotalBytes = 3000))
        assertRejected(zipOf("a" to "1", "b" to "2", "c" to "3"), UnpackLimits(maxEntries = 2))
    }

    @Test fun notAZip() {
        assertRejected(tmp.newFile().apply { writeText("hello") })
    }

    @Test fun safeRelativePath() {
        assertEquals("a/b.js", PackageUnpacker.safeRelativePath("a//./b.js"))
        assertEquals("a/b.js", PackageUnpacker.safeRelativePath("a\\b.js"))
        assertNull(PackageUnpacker.safeRelativePath(""))
        assertNull(PackageUnpacker.safeRelativePath("a/../b"))
        assertNull(PackageUnpacker.safeRelativePath("a\u0000b"))
    }
}
