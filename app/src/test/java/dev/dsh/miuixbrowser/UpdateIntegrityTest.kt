package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.browser.UpdateIntegrity
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateIntegrityTest {
    private val hex = "a".repeat(64)
    private val apk = "Mafari-1.0-lite-arm64-v8a.apk"

    private fun json(digest: String?): String {
        val d = if (digest != null) ",\"digest\":\"$digest\"" else ""
        return "{\"assets\":[{\"name\":\"x-gecko.apk\",\"digest\":\"sha256:" + "b".repeat(64) + "\"},{\"name\":\"$apk\"$d}]}"
    }

    @Test fun parsesDigestByFileName() {
        assertEquals(hex, UpdateIntegrity.parseDigest(json("sha256:$hex"), apk))
        assertEquals("b".repeat(64), UpdateIntegrity.parseDigest(json(null), "x-gecko.apk"))
    }

    @Test fun uppercaseHexIsNormalized() {
        assertEquals(hex, UpdateIntegrity.parseDigest(json("SHA256:" + hex.uppercase()), apk))
    }

    @Test fun badOrMissingDigestIsNull() {
        assertNull(UpdateIntegrity.parseDigest(json(null), apk))
        assertNull(UpdateIntegrity.parseDigest(json("sha1:$hex"), apk))
        assertNull(UpdateIntegrity.parseDigest(json("sha256:xyz"), apk))
        assertNull(UpdateIntegrity.parseDigest(json("sha256:$hex"), "other.apk"))
        assertNull(UpdateIntegrity.parseDigest("not json", "a.apk"))
    }

    @Test fun sha256OfKnownInput() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateIntegrity.sha256Hex(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test fun sameIsCaseInsensitiveAndStrict() {
        assertTrue(UpdateIntegrity.same(hex, hex.uppercase()))
        assertFalse(UpdateIntegrity.same(hex, "b".repeat(64)))
    }
}
