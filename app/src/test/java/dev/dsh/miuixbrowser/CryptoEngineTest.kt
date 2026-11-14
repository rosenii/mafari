package dev.dsh.miuixbrowser

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 纯 JVM 测试：用内存密钥代替 Android Keystore，验证信封格式、套件分派与各层绑定。所有已注册套件都会被覆盖。 */
class CryptoEngineTest {

    /** 每个套件按需生成一对密钥，模拟保险库。 */
    private class MemoryKeys : KeyProvider {
        private val pairs = HashMap<Int, SuiteKeyPair>()

        @Synchronized
        private fun pair(suite: CipherSuite) = pairs.getOrPut(suite.id) { suite.generateKeyPair(SecureRandom()) }

        override fun publicKey(suite: CipherSuite) = pair(suite).pub

        override fun privateKey(suite: CipherSuite) = pair(suite).priv
    }

    private val json = """[{"url":"https://example.com","title":"例子","time":1}]""".toByteArray()

    private fun engineFor(suite: CipherSuite, keys: KeyProvider = MemoryKeys()) =
        EncryptionEngine(keys, writeSuiteId = suite.id)

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("应当抛出 CryptoException")
        } catch (_: CryptoException) {
        }
    }

    @Test
    fun registryIsConsistent() {
        val ids = CipherSuites.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNotNull(CipherSuites.find(CipherSuites.DEFAULT_ID))
        assertNull(CipherSuites.find(0))
        assertRejected { CipherSuites.get(99) }
    }

    @Test
    fun roundTripEverySuite() {
        for (suite in CipherSuites.all) {
            val engine = engineFor(suite)
            val env = engine.seal(json, "ctx")
            assertArrayEquals(suite.name, json, engine.open(env, "ctx"))
            assertEquals(suite.name, suite.overhead + json.size, env.size)
            assertEquals(suite.id, env[4].toInt())
        }
    }

    /** 锁定已发布套件 1 的字节布局：变了就意味着旧数据读不出来。 */
    @Test
    fun suite1LayoutIsFrozen() {
        val suite = CipherSuites.get(CipherSuites.HYBRID_768)
        assertEquals(1222, suite.overhead) // 头 1146 + 随机数 12 + 封装 DEK 48 + 标签 16
        assertEquals(1216, suite.publicKeyLen) // X25519 32 + ML-KEM-768 1184
        assertEquals(2432, suite.privateKeyLen) // 32 + 2400
        val env = engineFor(suite).seal(json, "ctx")
        assertArrayEquals(byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'E'.code.toByte(), 1, 1, 0), env.copyOf(6))
    }

    @Test
    fun emptyPlaintext() {
        for (suite in CipherSuites.all) {
            val engine = engineFor(suite)
            assertArrayEquals(ByteArray(0), engine.open(engine.seal(ByteArray(0), "ctx"), "ctx"))
        }
    }

    @Test
    fun everySealIsDifferent() {
        val engine = engineFor(CipherSuites.default)
        assertFalse(engine.seal(json, "ctx").contentEquals(engine.seal(json, "ctx")))
    }

    @Test
    fun wrongContextRejected() {
        for (suite in CipherSuites.all) {
            val engine = engineFor(suite)
            val env = engine.seal(json, "history")
            assertRejected { engine.open(env, "favorites") }
        }
    }

    @Test
    fun anyTamperedByteRejected() {
        for (suite in CipherSuites.all) {
            val engine = engineFor(suite)
            val env = engine.seal(json, "ctx")
            // 前缀 / 头 / ct_pq / DEK 随机数 / 封装的 DEK / 数据密文 / 标签：隔几个字节抽样，再加首尾
            val positions = (env.indices step 11).toList() + listOf(3, 4, 5, 6, env.size - 1)
            for (i in positions) {
                val bad = env.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
                assertRejected { engine.open(bad, "ctx") }
            }
        }
    }

    @Test
    fun truncatedRejected() {
        for (suite in CipherSuites.all) {
            val engine = engineFor(suite)
            val env = engine.seal(json, "ctx")
            assertRejected { engine.open(env.copyOf(env.size - 1), "ctx") }
            assertRejected { engine.open(env.copyOf(100), "ctx") }
            assertRejected { engine.open(env.copyOf(5), "ctx") }
        }
    }

    @Test
    fun unknownSuiteRejected() {
        val engine = engineFor(CipherSuites.default)
        val env = engine.seal(json, "ctx").also { it[4] = 99 }
        assertRejected { engine.open(env, "ctx") }
    }

    @Test
    fun otherKeyRejected() {
        for (suite in CipherSuites.all) {
            val env = engineFor(suite).seal(json, "ctx")
            assertRejected { engineFor(suite).open(env, "ctx") } // 另一套全新密钥
        }
    }

    @Test
    fun keyOfAnotherSuiteRejected() {
        val a = CipherSuites.get(CipherSuites.HYBRID_768)
        val b = CipherSuites.get(CipherSuites.HYBRID_1024)
        val pair = a.generateKeyPair(SecureRandom())
        assertRejected { b.seal(pair.pub, json, "ctx", SecureRandom()) }
    }

    /** 升级路径：旧套件加密的数据，在默认套件换了之后仍能读，并能重新加密成新套件。 */
    @Test
    fun oldSuiteDataStillOpensAndCanBeResealed() {
        val keys = MemoryKeys()
        val oldEngine = engineFor(CipherSuites.get(CipherSuites.HYBRID_768), keys)
        val newEngine = engineFor(CipherSuites.get(CipherSuites.HYBRID_1024), keys)

        val old = oldEngine.seal(json, "ctx")
        assertArrayEquals(json, newEngine.open(old, "ctx"))
        assertFalse(newEngine.isCurrent(old))
        assertTrue(oldEngine.isCurrent(old))

        val upgraded = newEngine.reseal(old, "ctx")
        assertNotNull(upgraded)
        assertEquals(CipherSuites.HYBRID_1024, upgraded!![4].toInt())
        assertTrue(newEngine.isCurrent(upgraded))
        assertArrayEquals(json, newEngine.open(upgraded, "ctx"))
        assertNull(newEngine.reseal(upgraded, "ctx")) // 已是当前套件，无需改动

        // 新套件数据对只会旧套件的写入端同样可读（同一把保险库里有两对密钥）
        assertArrayEquals(json, oldEngine.open(upgraded, "ctx"))
    }

    @Test
    fun stringEnvelope() {
        val engine = engineFor(CipherSuites.default)
        val s = engine.sealString("你好 😀", "ctx")
        assertTrue(EncryptionEngine.isEncryptedString(s))
        assertTrue(s.startsWith("mfe1:"))
        assertEquals("你好 😀", engine.openString(s, "ctx"))
        assertNotEquals("你好 😀", s)
    }

    @Test
    fun isEncryptedStringRecognizesFormats() {
        assertTrue(EncryptionEngine.isEncryptedString("mfe1:AAAA"))
        assertTrue(EncryptionEngine.isEncryptedString("mfe2:AAAA"))
        assertFalse(EncryptionEngine.isEncryptedString("[{\"url\":1}]"))
        assertFalse(EncryptionEngine.isEncryptedString("mfe:AAAA"))
        assertFalse(EncryptionEngine.isEncryptedString("mfe1:"))
        assertFalse(EncryptionEngine.isEncryptedString(""))
    }

    @Test
    fun needsUpgradeDetection() {
        val keys = MemoryKeys()
        val s1 = engineFor(CipherSuites.get(CipherSuites.HYBRID_768), keys).sealString("x", "ctx")
        val s2 = engineFor(CipherSuites.get(CipherSuites.HYBRID_1024), keys).sealString("x", "ctx")

        assertTrue(EncryptionEngine.needsUpgrade("[{\"url\":1}]", CipherSuites.HYBRID_768)) // 旧版明文
        assertFalse(EncryptionEngine.needsUpgrade(s1, CipherSuites.HYBRID_768))
        assertTrue(EncryptionEngine.needsUpgrade(s1, CipherSuites.HYBRID_1024))
        assertFalse(EncryptionEngine.needsUpgrade(s2, CipherSuites.HYBRID_1024))
        assertTrue(EncryptionEngine.needsUpgrade(s2, CipherSuites.HYBRID_768))
        // 损坏的密文：无法判断，不要触发重写
        assertFalse(EncryptionEngine.needsUpgrade("mfe1:***", CipherSuites.HYBRID_768))
        assertFalse(EncryptionEngine.needsUpgrade("mfe1:AAAA", CipherSuites.HYBRID_768))
    }

    @Test
    fun hkdfSha384SelfConsistent() {
        // 自洽性：同输入同输出，不同 info 输出不同，长度正确
        val a = hkdfSha384(ByteArray(32) { 1 }, ByteArray(0), "a".toByteArray(), 32)
        val b = hkdfSha384(ByteArray(32) { 1 }, ByteArray(0), "a".toByteArray(), 32)
        val c = hkdfSha384(ByteArray(32) { 1 }, ByteArray(0), "b".toByteArray(), 32)
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(32, a.size)
    }

    @Test
    fun hkdfHashLengthsDiffer() {
        val k384 = hkdf("HmacSHA384", 48, ByteArray(32) { 1 }, ByteArray(0), "a".toByteArray(), 32)
        val k512 = hkdf("HmacSHA512", 64, ByteArray(32) { 1 }, ByteArray(0), "a".toByteArray(), 32)
        assertFalse(k384.contentEquals(k512))
        assertEquals(100, hkdf("HmacSHA512", 64, ByteArray(32), ByteArray(0), ByteArray(0), 100).size) // 跨多个块
    }
}
