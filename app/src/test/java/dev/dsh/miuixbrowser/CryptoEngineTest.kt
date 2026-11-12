package dev.dsh.miuixbrowser

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 纯 JVM 测试：用内存密钥代替 Android Keystore，验证信封格式与各层绑定。 */
class CryptoEngineTest {

    private class MemoryKeys(private val pair: HybridKeyPair) : HybridKeyProvider {
        override fun publicKey() = pair.pub
        override fun privateKey() = pair.priv
    }

    private val engine = EncryptionEngine(MemoryKeys(HybridKem.generate(SecureRandom())))
    private val json = """[{"url":"https://example.com","title":"例子","time":1}]""".toByteArray()

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("应当抛出 CryptoException")
        } catch (_: CryptoException) {
        }
    }

    @Test
    fun roundTrip() {
        val env = engine.seal(json, "ctx")
        assertArrayEquals(json, engine.open(env, "ctx"))
        assertEquals(1222 + json.size, env.size) // 固定开销 1222 字节（头 1146 + 随机数 12 + 封装 DEK 48 + 标签 16）
    }

    @Test
    fun emptyPlaintext() {
        assertArrayEquals(ByteArray(0), engine.open(engine.seal(ByteArray(0), "ctx"), "ctx"))
    }

    @Test
    fun everySealIsDifferent() {
        assertFalse(engine.seal(json, "ctx").contentEquals(engine.seal(json, "ctx")))
    }

    @Test
    fun wrongContextRejected() {
        val env = engine.seal(json, "history")
        assertRejected { engine.open(env, "favorites") }
    }

    @Test
    fun anyTamperedByteRejected() {
        val env = engine.seal(json, "ctx")
        // 头 / ct_pq / DEK 随机数 / 封装的 DEK / 数据密文 / 标签各取几个位置
        for (i in listOf(4, 6, 14, 100, 1133, 1140, 1150, 1170, 1210, env.size - 1)) {
            val bad = env.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertRejected { engine.open(bad, "ctx") }
        }
    }

    @Test
    fun truncatedRejected() {
        val env = engine.seal(json, "ctx")
        assertRejected { engine.open(env.copyOf(env.size - 1), "ctx") }
        assertRejected { engine.open(env.copyOf(100), "ctx") }
    }

    @Test
    fun otherKeyRejected() {
        val env = engine.seal(json, "ctx")
        val other = EncryptionEngine(MemoryKeys(HybridKem.generate(SecureRandom())))
        assertRejected { other.open(env, "ctx") }
    }

    @Test
    fun stringEnvelope() {
        val s = engine.sealString("你好 😀", "ctx")
        assertTrue(EncryptionEngine.isEncryptedString(s))
        assertEquals("你好 😀", engine.openString(s, "ctx"))
        assertNotEquals("你好 😀", s)
    }

    @Test
    fun hkdfSha384Rfc5869Style() {
        // 自洽性：同输入同输出，不同 info 输出不同，长度正确
        val a = hkdfSha384(ByteArray(32) { 1 }, ByteArray(0), "a".toByteArray(), 32)
        val b = hkdfSha384(ByteArray(32) { 1 }, ByteArray(0), "a".toByteArray(), 32)
        val c = hkdfSha384(ByteArray(32) { 1 }, ByteArray(0), "b".toByteArray(), 32)
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(32, a.size)
    }
}
