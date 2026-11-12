package dev.dsh.miuixbrowser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 纯 JVM：验证 .mfbak 的往返、口令错误、篡改、截断。Argon2 用小参数加速。 */
class BackupFormatTest {

    private val kdf = BackupFormat.KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val pw = "correct horse battery".toCharArray()
    private val units = linkedMapOf(
        "prefs/mafari" to """{"favorites":{"t":"s","v":"[]"}}""".toByteArray(),
        "files/adblock/custom.txt" to "||ads.example^".toByteArray(),
        "meta.json" to "{}".toByteArray(),
    )

    private fun backup() = BackupFormat.write(units, pw.copyOf(), "test", kdf)

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("应当抛出 CryptoException")
        } catch (_: CryptoException) {
        }
    }

    @Test
    fun roundTrip() {
        val c = BackupFormat.read(backup(), pw.copyOf())
        assertEquals("test", c.label)
        assertEquals(units.keys.toList(), c.units.keys.toList())
        for ((k, v) in units) assertArrayEquals(v, c.units[k])
    }

    @Test
    fun wrongPasswordRejected() {
        assertRejected { BackupFormat.read(backup(), "wrong password!".toCharArray()) }
    }

    @Test
    fun tamperedBytesRejected() {
        val file = backup()
        // 头部 / KDF 参数 / 标签 / 公钥 / 包裹私钥 / 信封内部 / 末尾
        for (i in listOf(4, 6, 20, 60, 300, 1300, 1500, 1700, 2000, file.size - 1)) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertRejected { BackupFormat.read(bad, pw.copyOf()) }
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
    fun everyExportUsesFreshKeys() {
        val a = backup()
        val b = backup()
        assert(!a.contentEquals(b))
    }
}
