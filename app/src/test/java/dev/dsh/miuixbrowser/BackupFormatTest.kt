package dev.dsh.miuixbrowser

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 纯 JVM：验证 .mfbak 的往返、口令错误、篡改、截断，以及旧版 v1 文件仍可读。Argon2 用小参数加速。 */
class BackupFormatTest {

    private val kdf = BackupFormat.KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val pw = "correct horse battery".toCharArray()
    private val units = linkedMapOf(
        "prefs/mafari" to """{"favorites":{"t":"s","v":"[]"}}""".toByteArray(),
        "files/adblock/custom.txt" to "||ads.example^".toByteArray(),
        "meta.json" to "{}".toByteArray(),
    )

    private fun backup(suiteId: Int = CipherSuites.DEFAULT_ID) =
        BackupFormat.write(units, pw.copyOf(), "test", kdf, suiteId = suiteId)

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("应当抛出 CryptoException")
        } catch (_: CryptoException) {
        }
    }

    @Test
    fun roundTripEverySuite() {
        for (suite in CipherSuites.all) {
            val c = BackupFormat.read(backup(suite.id), pw.copyOf())
            assertEquals("test", c.label)
            assertEquals(BackupFormat.VERSION, c.version)
            assertEquals(suite.id, c.suiteId)
            assertEquals(units.keys.toList(), c.units.keys.toList())
            for ((k, v) in units) assertArrayEquals(v, c.units[k])
        }
    }

    @Test
    fun wrongPasswordRejected() {
        assertRejected { BackupFormat.read(backup(), "wrong password!".toCharArray()) }
    }

    @Test
    fun tamperedBytesRejected() {
        for (suite in CipherSuites.all) {
            val file = backup(suite.id)
            // 头部 / KDF 参数 / 套件号 / 标签 / 公钥 / 包裹私钥 / 信封内部 / 末尾：按步长抽样
            val positions = (0 until file.size step 97).toList() + listOf(4, 5, 6, 12, file.size - 1)
            for (i in positions) {
                val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
                assertRejected { BackupFormat.read(bad, pw.copyOf()) }
            }
        }
    }

    @Test
    fun truncatedOrExtendedRejected() {
        val file = backup()
        assertRejected { BackupFormat.read(file.copyOf(file.size - 5), pw.copyOf()) }
        assertRejected { BackupFormat.read(file + byteArrayOf(0), pw.copyOf()) }
        assertRejected { BackupFormat.read(file.copyOf(30), pw.copyOf()) }
    }

    @Test
    fun unknownSuiteInHeaderRejected() {
        // v2 头：MFBK(4) | 版本 | KDF | m(4) | t | p | 套件号 → 套件号在偏移 12
        val bad = backup().also { it[12] = 99 }
        assertRejected { BackupFormat.read(bad, pw.copyOf()) }
    }

    @Test
    fun unsupportedVersionRejected() {
        val bad = backup().also { it[4] = 9 }
        assertRejected { BackupFormat.read(bad, pw.copyOf()) }
    }

    @Test
    fun everyExportUsesFreshKeys() {
        val a = backup()
        val b = backup()
        assert(!a.contentEquals(b))
    }

    /** 旧版（引擎支持套件之前）导出的 v1 备份文件必须继续能恢复。这里按 v1 规格独立构造一份。 */
    @Test
    fun legacyV1BackupStillReadable() {
        val c = BackupFormat.read(legacyV1(units, pw.copyOf(), "old"), pw.copyOf())
        assertEquals("old", c.label)
        assertEquals(1, c.version)
        assertEquals(CipherSuites.HYBRID_768, c.suiteId)
        assertEquals(123L, c.createdAt)
        assertEquals(units.keys.toList(), c.units.keys.toList())
        for ((k, v) in units) assertArrayEquals(v, c.units[k])
    }

    @Test
    fun legacyV1WrongPasswordRejected() {
        assertRejected { BackupFormat.read(legacyV1(units, pw.copyOf(), "old"), "wrong password!".toCharArray()) }
    }

    private fun legacyV1(units: Map<String, ByteArray>, password: CharArray, label: String): ByteArray {
        val rng = SecureRandom()
        val suite = CipherSuites.get(CipherSuites.HYBRID_768)
        val pair = suite.generateKeyPair(rng)
        val engine = EncryptionEngine(StaticKeyProvider(pair), rng, suite.id)
        val salt = ByteArray(16).also { rng.nextBytes(it) }

        val head = ByteArrayOutputStream()
        DataOutputStream(head).apply {
            write("MFBK".toByteArray(Charsets.US_ASCII))
            writeByte(1) // 版本 1
            writeByte(1) // Argon2id
            writeInt(kdf.memoryKiB)
            writeByte(kdf.iterations)
            writeByte(kdf.parallelism)
            writeShort(units.size)
            writeLong(123L)
            val labelBytes = label.toByteArray(Charsets.UTF_8)
            writeShort(labelBytes.size)
            write(labelBytes)
            write(salt)
            write(pair.pub.bytes) // x ‖ pq
        }
        val aad = head.toByteArray()
        val key = BackupFormat.deriveKey(1, password, salt, kdf)
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val wrapped = aesGcm(true, key, iv, aad, pair.priv.bytes)

        val out = ByteArrayOutputStream()
        out.write(aad)
        out.write(iv)
        out.write(wrapped)
        val data = DataOutputStream(out)
        for ((name, bytes) in units) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val envelope = engine.seal(bytes, "mafari.backup.v1/$name")
            data.writeShort(nameBytes.size)
            data.write(nameBytes)
            data.writeInt(envelope.size)
            data.write(envelope)
        }
        data.flush()
        return out.toByteArray()
    }
}
