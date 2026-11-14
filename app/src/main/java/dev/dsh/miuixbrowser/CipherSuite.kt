package dev.dsh.miuixbrowser

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * 可演进的加密层：算法套件（Cipher Suite）+ 版本化信封
 *
 * ── 三层「可变性」，各管各的 ──────────────────────────────────────────────
 *   信封版本（Envelope.VERSION）  信封的公共前缀布局变了才升。目前 = 1。
 *   算法套件（CipherSuite.id）    一整套算法组合（KEM / KDF / AEAD / 各长度）。新增算法 = 新增一个套件。
 *   存储格式（备份 v1/v2、密钥库 v1/v2）各容器自己的版本号，里面记录用的套件号。
 *
 * ── 信封公共前缀（所有套件共用，14 字节，之后的布局完全由套件自己决定）────────
 *   魔数 "MFE"(3) | 信封版本(1) | 套件号(1) | 标志位(1，保留 = 0) | key_id(8)
 *   key_id = SHA-384(公钥字节) 前 8 字节；解密时用来确认「这是不是用当前密钥加的」。
 *
 * ── 如何新增一个套件（例如换成别的 KEM / 换 AEAD）────────────────────────────
 *   1. 实现 [CipherSuite]（或用不同参数再实例化 [HybridSuite]）。套件号一旦发布永不复用、永不改动其格式。
 *   2. 在 [CipherSuites] 的注册表里登记。此时旧客户端读不了新数据，新客户端仍能读所有旧数据。
 *   3. 想让新数据默认用它：把 [CipherSuites.DEFAULT_ID] 改成新套件号。
 *      旧数据在下次被读出并保存时会自动重新加密（见 SecureStore.needsUpgrade / EncryptionEngine.reseal）；
 *      旧备份文件永远按文件头里记录的套件解密。
 *   4. 套件对应的密钥由 [KeyProvider] 按套件分别提供：Keystore 保险库会为新套件自动生成并保存一对新密钥。
 *   5. 在 CryptoEngineTest 里它会被自动覆盖（测试遍历 CipherSuites.all）。
 *
 * 被淘汰的套件：不要从注册表里删除，只改 DEFAULT_ID 即可——否则这个套件加密过的数据就永远打不开了。
 */

/** 套件的公钥：[bytes] 的内部布局由套件定义，外层只把它当不透明字节。 */
class SuitePublicKey(val suiteId: Int, val bytes: ByteArray) {
    val keyId: ByteArray by lazy { sha384(bytes).copyOf(KEY_ID_LEN) }

    companion object {
        const val KEY_ID_LEN = 8
    }
}

class SuitePrivateKey(val suiteId: Int, val bytes: ByteArray)

class SuiteKeyPair(val pub: SuitePublicKey, val priv: SuitePrivateKey)

/** 按套件提供密钥。加密只需公钥；解密才需要私钥（可能要碰 Keystore）。 */
interface KeyProvider {
    fun publicKey(suite: CipherSuite): SuitePublicKey

    fun privateKey(suite: CipherSuite): SuitePrivateKey
}

/** 固定密钥对（内存里），用于备份文件：每次导出现生成一对，不依赖设备 Keystore。 */
class StaticKeyProvider(private val pair: SuiteKeyPair) : KeyProvider {
    override fun publicKey(suite: CipherSuite): SuitePublicKey {
        if (pair.pub.suiteId != suite.id) throw CryptoException("密钥与算法套件不匹配")
        return pair.pub
    }

    override fun privateKey(suite: CipherSuite): SuitePrivateKey {
        if (pair.priv.suiteId != suite.id) throw CryptoException("密钥与算法套件不匹配")
        return pair.priv
    }
}

/** 一个算法套件：负责「公钥加密 → 完整信封」与「私钥解密」。信封前缀由 [Envelope] 统一处理。 */
interface CipherSuite {
    /** 套件号，写进每个信封与存储容器。发布后永不改变、永不复用。 */
    val id: Int

    /** 完整描述，进备份文件的标签 */
    val name: String

    /** 简称，给界面用 */
    val shortName: String

    /** 界面展示：密钥封装 / 密钥派生 的说明 */
    val kemDescription: String
    val kdfDescription: String

    val publicKeyLen: Int
    val privateKeyLen: Int

    /** 信封的固定额外开销（字节）：密文长度 = 明文长度 + overhead */
    val overhead: Int

    fun generateKeyPair(rng: SecureRandom): SuiteKeyPair

    /** 返回完整信封（含公共前缀）。 */
    fun seal(pub: SuitePublicKey, plaintext: ByteArray, context: String, rng: SecureRandom): ByteArray

    /** [envelope] 的公共前缀已由引擎校验过（魔数 / 版本 / 套件号 / key_id）。 */
    fun open(pub: SuitePublicKey, priv: SuitePrivateKey, envelope: ByteArray, context: String): ByteArray
}

/** 套件注册表。新增套件在这里登记；[DEFAULT_ID] 决定新数据用哪个套件。 */
object CipherSuites {
    /** X25519 + ML-KEM-768 · HKDF-SHA-384 · AES-256-GCM（已发布，格式永不改动） */
    const val HYBRID_768 = 1

    /** X25519 + ML-KEM-1024 · HKDF-SHA-512 · AES-256-GCM */
    const val HYBRID_1024 = 2

    /** 新写入的数据使用的套件。切换它即「升级」：旧数据读出后保存时会自动重新加密。 */
    const val DEFAULT_ID = HYBRID_768

    private val registry: Map<Int, CipherSuite> = listOf<CipherSuite>(
        HybridSuite(
            id = HYBRID_768,
            name = "X25519+ML-KEM-768 / HKDF-SHA-384 / AES-256-GCM",
            shortName = "X25519 + ML-KEM-768",
            kemDescription = "X25519 + ML-KEM-768 混合 KEM（后量子）；两个共享密钥拼接后派生，任一算法被攻破仍安全",
            kdfDescription = "HKDF-SHA-384 → KEK",
            pq = MLKEMParameters.ml_kem_768,
            pqPkLen = 1184,
            pqSkLen = 2400,
            pqCtLen = 1088,
            digestAlg = "SHA-384",
            hmacAlg = "HmacSHA384",
            infoLabel = "Mafari/HybridKEM/X25519+ML-KEM-768/HKDF-SHA384/v1",
        ),
        HybridSuite(
            id = HYBRID_1024,
            name = "X25519+ML-KEM-1024 / HKDF-SHA-512 / AES-256-GCM",
            shortName = "X25519 + ML-KEM-1024",
            kemDescription = "X25519 + ML-KEM-1024 混合 KEM（后量子，更高安全等级）；两个共享密钥拼接后派生",
            kdfDescription = "HKDF-SHA-512 → KEK",
            pq = MLKEMParameters.ml_kem_1024,
            pqPkLen = 1568,
            pqSkLen = 3168,
            pqCtLen = 1568,
            digestAlg = "SHA-512",
            hmacAlg = "HmacSHA512",
            infoLabel = "Mafari/HybridKEM/X25519+ML-KEM-1024/HKDF-SHA512/v1",
        ),
    ).associateBy { it.id }

    val all: List<CipherSuite> get() = registry.values.sortedBy { it.id }

    val default: CipherSuite get() = get(DEFAULT_ID)

    fun find(id: Int): CipherSuite? = registry[id]

    fun get(id: Int): CipherSuite = registry[id] ?: throw CryptoException("不支持的算法套件：$id")
}

/** 信封公共前缀：所有套件共用，其后的布局由套件自己决定。 */
internal object Envelope {
    val MAGIC = byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'E'.code.toByte())
    const val VERSION = 1

    /** 魔数 3 + 版本 1 + 套件 1 + 标志位 1 + key_id 8 */
    const val PREFIX_LEN = 3 + 1 + 1 + 1 + SuitePublicKey.KEY_ID_LEN

    class Prefix(val version: Int, val suiteId: Int, val keyId: ByteArray)

    fun writePrefix(out: ByteArrayOutputStream, suiteId: Int, keyId: ByteArray) {
        out.write(MAGIC)
        out.write(VERSION)
        out.write(suiteId)
        out.write(0) // 标志位：保留
        out.write(keyId)
    }

    fun parsePrefix(envelope: ByteArray): Prefix {
        if (envelope.size < PREFIX_LEN) throw CryptoException("信封过短")
        if (!envelope.copyOfRange(0, 3).contentEquals(MAGIC)) throw CryptoException("不是 Mafari 加密信封")
        val version = envelope[3].toInt() and 0xFF
        if (version != VERSION) throw CryptoException("不支持的信封版本：$version")
        if (envelope[5].toInt() != 0) throw CryptoException("不支持的信封标志位")
        return Prefix(version, envelope[4].toInt() and 0xFF, envelope.copyOfRange(6, PREFIX_LEN))
    }
}

/**
 * 混合套件：X25519 + ML-KEM（参数化）→ HKDF → KEK；KEK 封装随机 DEK；DEK 加密数据（均为 AES-256-GCM）。
 *
 *   KEK = HKDF(ss_pq ‖ ss_x, info = 标签 ‖ eph_pk ‖ ct_pq ‖ pk_x ‖ H(pk_pq))
 *
 * 信封（公共前缀之后）：
 *   头：X25519 临时公钥(32) | ML-KEM 密文 | 数据 GCM 随机数(12)   ← 整个头（含前缀）同时是 DEK 封装的 AAD
 *   体：DEK 随机数(12) | 封装后的 DEK(32 + 16) | 数据密文 + 16 字节标签
 *
 * 套件 1 的字节布局与引擎引入套件之前的 v1 信封完全一致（旧数据原样可读）。
 * 每个加密单元都有独立的临时 X25519 密钥、独立的 ML-KEM 封装、独立的 DEK。
 */
internal class HybridSuite(
    override val id: Int,
    override val name: String,
    override val shortName: String,
    override val kemDescription: String,
    override val kdfDescription: String,
    private val pq: MLKEMParameters,
    private val pqPkLen: Int,
    private val pqSkLen: Int,
    private val pqCtLen: Int,
    private val digestAlg: String,
    private val hmacAlg: String,
    infoLabel: String,
) : CipherSuite {

    private val hashLen = MessageDigest.getInstance(digestAlg).digestLength
    private val infoLabelBytes = infoLabel.toByteArray(Charsets.UTF_8)
    private val headerLen = Envelope.PREFIX_LEN + X_LEN + pqCtLen + NONCE_LEN

    override val publicKeyLen = X_LEN + pqPkLen
    override val privateKeyLen = X_LEN + pqSkLen
    override val overhead = headerLen + NONCE_LEN + WRAPPED_DEK_LEN + TAG_LEN

    override fun generateKeyPair(rng: SecureRandom): SuiteKeyPair {
        val xPriv = X25519PrivateKeyParameters(rng)
        val xPub = xPriv.generatePublicKey().encoded

        val gen = MLKEMKeyPairGenerator()
        gen.init(MLKEMKeyGenerationParameters(rng, pq))
        val kp = gen.generateKeyPair()
        val pqPub = (kp.public as MLKEMPublicKeyParameters).encoded
        val pqPriv = (kp.private as MLKEMPrivateKeyParameters).encoded

        check(xPub.size == X_LEN && pqPub.size == pqPkLen && pqPriv.size == pqSkLen) { "unexpected key sizes" }
        return SuiteKeyPair(SuitePublicKey(id, xPub + pqPub), SuitePrivateKey(id, xPriv.encoded + pqPriv))
    }

    override fun seal(pub: SuitePublicKey, plaintext: ByteArray, context: String, rng: SecureRandom): ByteArray {
        requireKey(pub.suiteId, pub.bytes.size, publicKeyLen)
        val xPub = pub.bytes.copyOfRange(0, X_LEN)
        val pqPub = pub.bytes.copyOfRange(X_LEN, publicKeyLen)

        val pqEnc = MLKEMGenerator(rng).generateEncapsulated(MLKEMPublicKeyParameters(pq, pqPub))
        val ssPq = pqEnc.secret
        val ctPq = pqEnc.encapsulation
        check(ctPq.size == pqCtLen) { "unexpected ciphertext size" }

        val ephPriv = X25519PrivateKeyParameters(rng)
        val ephPub = ephPriv.generatePublicKey().encoded
        val ssX = ByteArray(X_LEN)
        ephPriv.generateSecret(X25519PublicKeyParameters(xPub), ssX, 0) // 全零结果会抛异常

        val kek = deriveKek(ssPq, ssX, ephPub, ctPq, xPub, pqPub)
        ssPq.fill(0)
        ssX.fill(0)

        val dek = ByteArray(DEK_LEN).also { rng.nextBytes(it) }
        try {
            val dataNonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
            val dekNonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }

            val head = ByteArrayOutputStream(headerLen).also {
                Envelope.writePrefix(it, id, pub.keyId)
                it.write(ephPub)
                it.write(ctPq)
                it.write(dataNonce)
            }.toByteArray()
            check(head.size == headerLen)

            val wrappedDek = aesGcm(true, kek, dekNonce, head, dek)
            val ciphertext = aesGcm(true, dek, dataNonce, head + context.toByteArray(Charsets.UTF_8), plaintext)
            return head + dekNonce + wrappedDek + ciphertext
        } finally {
            dek.fill(0)
            kek.fill(0)
        }
    }

    override fun open(pub: SuitePublicKey, priv: SuitePrivateKey, envelope: ByteArray, context: String): ByteArray {
        requireKey(pub.suiteId, pub.bytes.size, publicKeyLen)
        requireKey(priv.suiteId, priv.bytes.size, privateKeyLen)
        if (envelope.size < overhead) throw CryptoException("信封过短")

        val head = envelope.copyOfRange(0, headerLen)
        val ephPub = envelope.copyOfRange(Envelope.PREFIX_LEN, Envelope.PREFIX_LEN + X_LEN)
        val ctPq = envelope.copyOfRange(Envelope.PREFIX_LEN + X_LEN, Envelope.PREFIX_LEN + X_LEN + pqCtLen)
        val dataNonce = envelope.copyOfRange(headerLen - NONCE_LEN, headerLen)
        val dekNonce = envelope.copyOfRange(headerLen, headerLen + NONCE_LEN)
        val wrappedDek = envelope.copyOfRange(headerLen + NONCE_LEN, headerLen + NONCE_LEN + WRAPPED_DEK_LEN)
        val ciphertext = envelope.copyOfRange(headerLen + NONCE_LEN + WRAPPED_DEK_LEN, envelope.size)

        val xPub = pub.bytes.copyOfRange(0, X_LEN)
        val pqPub = pub.bytes.copyOfRange(X_LEN, publicKeyLen)
        val xPriv = priv.bytes.copyOfRange(0, X_LEN)
        val pqPriv = priv.bytes.copyOfRange(X_LEN, privateKeyLen)

        // ML-KEM 对被篡改的密文是「隐式拒绝」：不抛异常，返回伪随机密钥，后面的 GCM 校验会失败
        val ssPq = MLKEMExtractor(MLKEMPrivateKeyParameters(pq, pqPriv)).extractSecret(ctPq)
        val ssX = ByteArray(X_LEN)
        X25519PrivateKeyParameters(xPriv).generateSecret(X25519PublicKeyParameters(ephPub), ssX, 0)
        val kek = deriveKek(ssPq, ssX, ephPub, ctPq, xPub, pqPub)
        ssPq.fill(0)
        ssX.fill(0)

        var dek: ByteArray? = null
        try {
            dek = aesGcm(false, kek, dekNonce, head, wrappedDek)
            return aesGcm(false, dek, dataNonce, head + context.toByteArray(Charsets.UTF_8), ciphertext)
        } finally {
            kek.fill(0)
            dek?.fill(0)
        }
    }

    private fun deriveKek(
        ssPq: ByteArray,
        ssX: ByteArray,
        ephPub: ByteArray,
        ctPq: ByteArray,
        xPub: ByteArray,
        pqPub: ByteArray,
    ): ByteArray {
        check(ssPq.size == SS_LEN)
        val ikm = ssPq + ssX // ss_pq ‖ ss_x
        val info = infoLabelBytes + ephPub + ctPq + xPub + digest(digestAlg, pqPub)
        val kek = hkdf(hmacAlg, hashLen, ikm, ByteArray(0), info, KEK_LEN)
        ikm.fill(0)
        return kek
    }

    private fun requireKey(suiteId: Int, size: Int, expected: Int) {
        if (suiteId != id || size != expected) throw CryptoException("密钥与算法套件不匹配")
    }

    private companion object {
        const val X_LEN = 32
        const val SS_LEN = 32
        const val KEK_LEN = 32
        const val DEK_LEN = 32
        const val NONCE_LEN = 12
        const val TAG_LEN = 16
        const val WRAPPED_DEK_LEN = DEK_LEN + TAG_LEN
    }
}

// ---- 公共原语 ----

internal fun digest(algorithm: String, data: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(data)

internal fun sha384(data: ByteArray): ByteArray = digest("SHA-384", data)

/** RFC 5869 HKDF。[hmacAlg] 如 "HmacSHA384"，[hashLen] 为对应哈希的输出长度。 */
internal fun hkdf(hmacAlg: String, hashLen: Int, ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..(255 * hashLen))
    val mac = Mac.getInstance(hmacAlg)
    mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(hashLen) else salt, hmacAlg))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, hmacAlg))
    val out = ByteArrayOutputStream()
    var block = ByteArray(0)
    var counter = 1
    while (out.size() < length) {
        mac.update(block)
        mac.update(info)
        mac.update(counter.toByte())
        block = mac.doFinal()
        out.write(block)
        counter++
    }
    prk.fill(0)
    return out.toByteArray().copyOf(length)
}

internal fun hkdfSha384(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
    hkdf("HmacSHA384", 48, ikm, salt, info, length)

/** AES-256-GCM（128 位标签）。信封与备份文件共用。 */
internal fun aesGcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
    try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    } catch (e: GeneralSecurityException) {
        throw CryptoException(if (encrypt) "加密失败" else "解密失败：数据被篡改、上下文不符或密钥不对", e)
    }
}
