/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CrxTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
            val bo = ByteArrayOutputStream()
            ZipOutputStream(bo).use { z ->
                for ((n, c) in entries) {
                    z.putNextEntry(ZipEntry(n))
                    z.write(c.toByteArray())
                    z.closeEntry()
                }
            }
            return bo.toByteArray()
        }

        private fun varint(v: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var x = v
            while (x >= 0x80) {
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
            return out.toByteArray()
        }

        private fun field(no: Int, payload: ByteArray): ByteArray =
            varint((no.toLong() shl 3) or 2) + varint(payload.size.toLong()) + payload

        private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

        fun newKey(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        /** [crxIdOverride] 用来构造「ID 与公钥不匹配」的包；签名仍然对这份 signed_header_data 有效 */
        fun buildCrx3(kp: KeyPair, zip: ByteArray, crxIdOverride: ByteArray? = null): ByteArray {
            val pub = kp.public.encoded
            val crxId = crxIdOverride ?: MessageDigest.getInstance("SHA-256").digest(pub).copyOf(16)
            val signedData = field(1, crxId)
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(kp.private)
            sig.update("CRX3 SignedData\u0000".toByteArray(Charsets.ISO_8859_1))
            sig.update(le32(signedData.size))
            sig.update(signedData)
            sig.update(zip)
            val proof = field(1, pub) + field(2, sig.sign())
            val header = field(2, proof) + field(10000, signedData)
            return "Cr24".toByteArray(Charsets.ISO_8859_1) + le32(3) + le32(header.size) + header + zip
        }
    }

    private val manifest = """{"manifest_version":3,"name":"X","version":"1"}"""

    @Test fun parsesAndVerifiesCrx3() {
        val kp = newKey()
        val f = tmp.newFile("a.crx").apply { writeBytes(buildCrx3(kp, zipBytes("manifest.json" to manifest))) }
        assertTrue(Crx.isCrx(f))
        val info = Crx.parse(f)
        assertEquals(3, info.version)
        assertTrue(info.signatureVerified)
        assertTrue(Regex("^[a-p]{32}$").matches(info.extensionId))
        assertEquals(ExtensionIds.fromPublicKey(kp.public.encoded), info.extensionId)

        val zip = File(tmp.root, "inner.zip")
        Crx.extractZip(f, info, zip)
        val out = File(tmp.root, "out")
        PackageUnpacker.unpack(zip, out)
        assertEquals(manifest, File(out, "manifest.json").readText())
    }

    @Test fun tamperedArchiveFailsVerification() {
        val kp = newKey()
        val bytes = buildCrx3(kp, zipBytes("manifest.json" to manifest))
        bytes[bytes.size - 30] = (bytes[bytes.size - 30].toInt() xor 0x01).toByte()
        val f = tmp.newFile("t.crx").apply { writeBytes(bytes) }
        try {
            Crx.parse(f)
            fail("应当校验失败")
        } catch (_: PackageException) {
        }
        // 关掉校验时仍可解析头部（仅用于诊断，安装路径不会这样调用）
        assertFalse(Crx.parse(f, verify = false).signatureVerified)
    }

    @Test fun declaredIdMustMatchSigningKey() {
        val kp = newKey()
        val f = tmp.newFile("m.crx").apply {
            writeBytes(buildCrx3(kp, zipBytes("manifest.json" to manifest), crxIdOverride = ByteArray(16) { 7 }))
        }
        try {
            Crx.parse(f)
            fail("应当拒绝")
        } catch (_: PackageException) {
        }
    }

    @Test fun truncatedAndNonCrx() {
        val plain = tmp.newFile("p.zip").apply { writeBytes(zipBytes("manifest.json" to manifest)) }
        assertFalse(Crx.isCrx(plain))
        val short = tmp.newFile("s.crx").apply { writeBytes("Cr24".toByteArray() + byteArrayOf(3, 0, 0, 0)) }
        try {
            Crx.parse(short)
            fail()
        } catch (_: PackageException) {
        }
    }

    @Test fun idHelpers() {
        assertTrue(ExtensionIds.isSafeId("{d10d0bf8-f5b5-c8b4-a8b2-2b9879e08c5d}"))
        assertTrue(ExtensionIds.isSafeId("gm@mafari.dev"))
        assertFalse(ExtensionIds.isSafeId("../x"))
        assertFalse(ExtensionIds.isSafeId("a/b"))
        assertFalse(ExtensionIds.isSafeId(".hidden"))
        assertFalse(ExtensionIds.isSafeId(""))
        assertNull(ExtensionIds.fromManifestKey("!!!not base64!!!"))
        val der = newKey().public.encoded
        val b64 = java.util.Base64.getEncoder().encodeToString(der)
        assertEquals(ExtensionIds.fromPublicKey(der), ExtensionIds.fromManifestKey(b64))
        assertEquals(ExtensionIds.localId("A"), ExtensionIds.localId(" A "))
    }
}
