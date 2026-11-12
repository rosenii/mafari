package dev.dsh.miuixbrowser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
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
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * 后量子混合加密引擎
 *
 *  第 1 层  混合 KEM：ML-KEM-768 Encaps(pk_pq) → ss_pq, ct_pq
 *                     X25519 ECDH(eph_sk, pk_x) → ss_x
 *                     HKDF-SHA-384(ss_pq ‖ ss_x, info = 标签 ‖ eph_pk ‖ ct_pq ‖ pk_x ‖ SHA-384(pk_pq)) → KEK
 *  第 2 层  DEK 封装：DEK = 随机 32 字节；AES-256-GCM(KEK, DEK, AAD = 信封头) → 密钥密文
 *  第 3 层  数据加密：AES-256-GCM(DEK, 数据, AAD = 信封头 ‖ 上下文) → 数据密文
 *
 * 每个加密单元（一次 seal 调用）都有独立的临时 X25519 密钥、独立的 ML-KEM 封装、独立的 DEK。
 * 加密只需要公钥；解密需要私钥，私钥由 Android Keystore 里的 AES-256-GCM 密钥包裹后落盘。
 *
 * 信封格式 v1（大端无关，全部为定长字段）：
 *   头（1146 字节，同时是 DEK 封装的 AAD）
 *     [0..3)      魔数 "MFE"
 *     [3]         信封版本 = 1
 *     [4]         算法套件 = 1（X25519 + ML-KEM-768 + HKDF-SHA-384 + AES-256-GCM）
 *     [5]         标志位（保留，= 0）
 *     [6..14)     key_id = SHA-384(pk_x ‖ pk_pq) 前 8 字节
 *     [14..46)    X25519 临时公钥
 *     [46..1134)  ML-KEM-768 密文 ct_pq
 *     [1134..1146) 数据 GCM 随机数
 *   体
 *     [1146..1158) DEK GCM 随机数
 *     [1158..1206) 封装后的 DEK（32 字节密文 + 16 字节标签）
 *     [1206..)     数据密文 + 16 字节标签
 */

open class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 私钥取不回来了（Keystore 密钥丢失 / 失效 / 密钥文件损坏）。此时旧数据已无法解密。 */
class KeyUnavailableException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

class HybridPublicKey(val x: ByteArray, val pq: ByteArray) {
    val keyId: ByteArray by lazy { sha384(x + pq).copyOf(KEY_ID_LEN) }

    companion object {
        const val KEY_ID_LEN = 8
    }
}

class HybridPrivateKey(val x: ByteArray, val pq: ByteArray)

class HybridKeyPair(val pub: HybridPublicKey, val priv: HybridPrivateKey)

interface HybridKeyProvider {
    /** 加密用：只需要公钥。 */
    fun publicKey(): HybridPublicKey

    /** 解密用：需要私钥。 */
    fun privateKey(): HybridPrivateKey
}

internal fun sha384(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-384").digest(data)

/** RFC 5869 HKDF，哈希为 SHA-384。 */
internal fun hkdfSha384(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..(255 * 48))
    val mac = Mac.getInstance("HmacSHA384")
    mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(48) else salt, "HmacSHA384"))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, "HmacSHA384"))
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

/** 第 1 层：X25519 + ML-KEM-768 混合封装。 */
internal object HybridKem {
    const val X_LEN = 32
    const val PQ_PK_LEN = 1184
    const val PQ_SK_LEN = 2400
    const val PQ_CT_LEN = 1088
    private const val SS_LEN = 32

    private val PQ_PARAMS: MLKEMParameters = MLKEMParameters.ml_kem_768
    private val INFO_LABEL = "Mafari/HybridKEM/X25519+ML-KEM-768/HKDF-SHA384/v1".toByteArray(Charsets.UTF_8)

    class Encapsulation(val ephPub: ByteArray, val ctPq: ByteArray, val kek: ByteArray)

    fun generate(rng: SecureRandom): HybridKeyPair {
        val xPriv = X25519PrivateKeyParameters(rng)
        val xPub = xPriv.generatePublicKey().encoded

        val gen = MLKEMKeyPairGenerator()
        gen.init(MLKEMKeyGenerationParameters(rng, PQ_PARAMS))
        val kp = gen.generateKeyPair()
        val pqPub = (kp.public as MLKEMPublicKeyParameters).encoded
        val pqPriv = (kp.private as MLKEMPrivateKeyParameters).encoded

        check(xPub.size == X_LEN && pqPub.size == PQ_PK_LEN && pqPriv.size == PQ_SK_LEN) { "unexpected key sizes" }
        return HybridKeyPair(HybridPublicKey(xPub, pqPub), HybridPrivateKey(xPriv.encoded, pqPriv))
    }

    fun encapsulate(pub: HybridPublicKey, rng: SecureRandom): Encapsulation {
        val pqEnc = MLKEMGenerator(rng).generateEncapsulated(MLKEMPublicKeyParameters(PQ_PARAMS, pub.pq))
        val ssPq = pqEnc.secret
        val ctPq = pqEnc.encapsulation

        val ephPriv = X25519PrivateKeyParameters(rng)
        val ephPub = ephPriv.generatePublicKey().encoded
        val ssX = ByteArray(X_LEN)
        ephPriv.generateSecret(X25519PublicKeyParameters(pub.x), ssX, 0) // 全零结果会抛异常

        val kek = deriveKek(ssPq, ssX, ephPub, ctPq, pub)
        ssPq.fill(0)
        ssX.fill(0)
        return Encapsulation(ephPub, ctPq, kek)
    }

    fun decapsulate(priv: HybridPrivateKey, pub: HybridPublicKey, ephPub: ByteArray, ctPq: ByteArray): ByteArray {
        // ML-KEM 对被篡改的密文是「隐式拒绝」：不抛异常，返回伪随机密钥，后面的 GCM 校验会失败
        val ssPq = MLKEMExtractor(MLKEMPrivateKeyParameters(PQ_PARAMS, priv.pq)).extractSecret(ctPq)
        val ssX = ByteArray(X_LEN)
        X25519PrivateKeyParameters(priv.x).generateSecret(X25519PublicKeyParameters(ephPub), ssX, 0)

        val kek = deriveKek(ssPq, ssX, ephPub, ctPq, pub)
        ssPq.fill(0)
        ssX.fill(0)
        return kek
    }

    private fun deriveKek(ssPq: ByteArray, ssX: ByteArray, ephPub: ByteArray, ctPq: ByteArray, pub: HybridPublicKey): ByteArray {
        check(ssPq.size == SS_LEN)
        val ikm = ssPq + ssX // ss_pq ‖ ss_x
        val info = INFO_LABEL + ephPub + ctPq + pub.x + sha384(pub.pq)
        val kek = hkdfSha384(ikm, ByteArray(0), info, 32)
        ikm.fill(0)
        return kek
    }
}

/** 加密引擎：把任意字节 / 字符串 / 文件（JSON、SQLite）封进版本化信封。 */
class EncryptionEngine(
    private val keys: HybridKeyProvider,
    private val rng: SecureRandom = SecureRandom(),
) {

    fun seal(plaintext: ByteArray, context: String): ByteArray {
        val pub = keys.publicKey()
        val enc = HybridKem.encapsulate(pub, rng)
        val dek = randomBytes(DEK_LEN)
        try {
            val dataNonce = randomBytes(NONCE_LEN)
            val dekNonce = randomBytes(NONCE_LEN)

            val head = ByteArrayOutputStream(HEADER_LEN).apply {
                write(MAGIC)
                write(VERSION)
                write(SUITE)
                write(0)
                write(pub.keyId)
                write(enc.ephPub)
                write(enc.ctPq)
                write(dataNonce)
            }.toByteArray()
            check(head.size == HEADER_LEN)

            val wrappedDek = gcm(true, enc.kek, dekNonce, head, dek)
            val ciphertext = gcm(true, dek, dataNonce, head + context.toByteArray(Charsets.UTF_8), plaintext)
            return head + dekNonce + wrappedDek + ciphertext
        } finally {
            dek.fill(0)
            enc.kek.fill(0)
        }
    }

    fun open(envelope: ByteArray, context: String): ByteArray {
        if (envelope.size < MIN_ENVELOPE_LEN) throw CryptoException("信封过短")
        if (!envelope.copyOfRange(0, 3).contentEquals(MAGIC)) throw CryptoException("不是 Mafari 加密信封")
        val version = envelope[3].toInt() and 0xFF
        if (version != VERSION) throw CryptoException("不支持的信封版本：$version")
        val suite = envelope[4].toInt() and 0xFF
        if (suite != SUITE) throw CryptoException("不支持的算法套件：$suite")
        if (envelope[5].toInt() != 0) throw CryptoException("不支持的信封标志位")

        val head = envelope.copyOfRange(0, HEADER_LEN)
        val keyId = envelope.copyOfRange(6, 14)
        val ephPub = envelope.copyOfRange(14, 46)
        val ctPq = envelope.copyOfRange(46, 46 + HybridKem.PQ_CT_LEN)
        val dataNonce = envelope.copyOfRange(HEADER_LEN - NONCE_LEN, HEADER_LEN)
        val dekNonce = envelope.copyOfRange(HEADER_LEN, HEADER_LEN + NONCE_LEN)
        val wrappedDek = envelope.copyOfRange(HEADER_LEN + NONCE_LEN, HEADER_LEN + NONCE_LEN + WRAPPED_DEK_LEN)
        val ciphertext = envelope.copyOfRange(MIN_ENVELOPE_LEN - TAG_LEN, envelope.size)

        val pub = keys.publicKey()
        if (!pub.keyId.contentEquals(keyId)) throw CryptoException("信封所用密钥与当前密钥不匹配")
        val priv = keys.privateKey()

        val kek = HybridKem.decapsulate(priv, pub, ephPub, ctPq)
        var dek: ByteArray? = null
        try {
            dek = gcm(false, kek, dekNonce, head, wrappedDek)
            return gcm(false, dek, dataNonce, head + context.toByteArray(Charsets.UTF_8), ciphertext)
        } finally {
            kek.fill(0)
            dek?.fill(0)
        }
    }

    // ---- 字符串 / 文件便利接口 ----

    /** 加密成 "mfe1:<base64>"，可直接存进 SharedPreferences / JSON。 */
    fun sealString(text: String, context: String): String =
        STRING_PREFIX + Base64.getEncoder().encodeToString(seal(text.toByteArray(Charsets.UTF_8), context))

    fun openString(stored: String, context: String): String {
        if (!stored.startsWith(STRING_PREFIX)) throw CryptoException("不是加密字符串")
        val bytes = try {
            Base64.getDecoder().decode(stored.substring(STRING_PREFIX.length))
        } catch (e: IllegalArgumentException) {
            throw CryptoException("Base64 无效", e)
        }
        return String(open(bytes, context), Charsets.UTF_8)
    }

    /** 加密整个文件（例如 SQLite 数据库文件）；先写临时文件再改名，写到一半断电不会损坏旧文件。 */
    fun sealFile(src: File, dst: File, context: String) {
        if (src.length() > MAX_FILE_BYTES) throw CryptoException("文件过大（上限 ${MAX_FILE_BYTES shr 20} MiB）")
        writeAtomically(dst, seal(src.readBytes(), context))
    }

    fun openFile(src: File, dst: File, context: String) {
        if (src.length() > MAX_FILE_BYTES + OVERHEAD) throw CryptoException("文件过大")
        writeAtomically(dst, open(src.readBytes(), context))
    }

    /** 把加密文件解到临时明文文件，交给 [block]（例如 SQLiteDatabase.openDatabase），用完覆写并删除。 */
    fun <T> withPlainFile(encrypted: File, tempDir: File, context: String, block: (File) -> T): T {
        tempDir.mkdirs()
        val plain = File.createTempFile("mfe", ".tmp", tempDir)
        try {
            openFile(encrypted, plain, context)
            return block(plain)
        } finally {
            runCatching { plain.writeBytes(ByteArray(plain.length().toInt())) }
            plain.delete()
        }
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    private fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LEN * 8, nonce),
            )
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        } catch (e: GeneralSecurityException) {
            throw CryptoException(if (encrypt) "加密失败" else "解密失败：数据被篡改、上下文不符或密钥不对", e)
        }
    }

    companion object {
        internal val MAGIC = byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'E'.code.toByte())
        const val VERSION = 1
        const val SUITE = 1
        const val STRING_PREFIX = "mfe1:"

        private const val DEK_LEN = 32
        private const val NONCE_LEN = 12
        private const val TAG_LEN = 16
        private const val WRAPPED_DEK_LEN = DEK_LEN + TAG_LEN
        private const val HEADER_LEN = 3 + 1 + 1 + 1 + HybridPublicKey.KEY_ID_LEN + HybridKem.X_LEN + HybridKem.PQ_CT_LEN + NONCE_LEN // 1146
        private const val OVERHEAD = HEADER_LEN + NONCE_LEN + WRAPPED_DEK_LEN + TAG_LEN
        private const val MIN_ENVELOPE_LEN = OVERHEAD
        private const val MAX_FILE_BYTES = 128L shl 20

        fun isEncryptedString(s: String) = s.startsWith(STRING_PREFIX)

        @Volatile
        private var instance: EncryptionEngine? = null
        @Volatile
        private var vault: AndroidKeyVault? = null

        /** 应用内共享的引擎（密钥由 Android Keystore 保护）。 */
        fun get(context: Context): EncryptionEngine {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val v = AndroidKeyVault(context.applicationContext)
                vault = v
                return EncryptionEngine(v).also { instance = it }
            }
        }

        /** 密钥彻底丢失时的出路：作废旧密钥并生成新的。旧数据无法再解密。 */
        fun resetVault(context: Context) {
            get(context)
            vault?.reset()
        }

        private fun writeAtomically(dst: File, data: ByteArray) {
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(dst)) {
                dst.delete()
                if (!tmp.renameTo(dst)) throw CryptoException("写入失败：${dst.name}")
            }
        }
    }
}

/**
 * 私钥保险库：
 *  - 公钥明文落盘（加密时不需要碰 Keystore）；
 *  - X25519 私钥 ‖ ML-KEM 私钥 经 Android Keystore 里不可导出的 AES-256-GCM 密钥包裹后落盘
 *    （AAD = 公钥，防止把别的密钥文件换进来）。
 *
 * 文件格式：魔数 "MFK" | 版本 1 | pk_x(32) | pk_pq(1184) | iv(12) | 包裹后的私钥(2432 + 16)
 */
class AndroidKeyVault(context: Context) : HybridKeyProvider {

    private val file = File(context.filesDir, "mafari_vault/hybrid_keys.v1")
    private var pub: HybridPublicKey? = null
    private var priv: HybridPrivateKey? = null

    @Synchronized
    override fun publicKey(): HybridPublicKey {
        pub?.let { return it }
        val bytes = readOrCreate()
        return parsePublic(bytes).also { pub = it }
    }

    @Synchronized
    override fun privateKey(): HybridPrivateKey {
        priv?.let { return it }
        val bytes = readOrCreate()
        val p = parsePublic(bytes)
        try {
            val iv = bytes.copyOfRange(PUB_END, PUB_END + IV_LEN)
            val wrapped = bytes.copyOfRange(PUB_END + IV_LEN, bytes.size)
            val key = keystoreKey(create = false) ?: throw KeyUnavailableException("Keystore 里的包裹密钥已丢失")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(p))
            val plain = cipher.doFinal(wrapped)
            return HybridPrivateKey(
                plain.copyOfRange(0, HybridKem.X_LEN),
                plain.copyOfRange(HybridKem.X_LEN, plain.size),
            ).also { priv = it; pub = p; plain.fill(0) }
        } catch (e: KeyUnavailableException) {
            throw e
        } catch (e: UnrecoverableKeyException) {
            throw KeyUnavailableException("包裹密钥无法使用", e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyUnavailableException("包裹密钥已失效", e)
        } catch (e: GeneralSecurityException) {
            throw KeyUnavailableException("私钥解包失败（密钥文件损坏或 Keystore 密钥已变）", e)
        }
    }

    /** 删除密钥文件与 Keystore 密钥；下次使用时自动生成新密钥。旧数据无法再解密。 */
    @Synchronized
    fun reset() {
        file.delete()
        runCatching { KeyStore.getInstance(KS_PROVIDER).apply { load(null) }.deleteEntry(KS_ALIAS) }
        pub = null
        priv = null
    }

    private fun readOrCreate(): ByteArray {
        if (file.exists()) {
            val bytes = file.readBytes()
            if (bytes.size != FILE_LEN || !bytes.copyOfRange(0, 3).contentEquals(MAGIC) || bytes[3].toInt() != 1) {
                throw KeyUnavailableException("密钥文件损坏")
            }
            return bytes
        }
        val pair = HybridKem.generate(SecureRandom())
        val plain = pair.priv.x + pair.priv.pq
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(create = true)!!)
        cipher.updateAAD(aad(pair.pub))
        val wrapped = cipher.doFinal(plain)
        val iv = cipher.iv
        plain.fill(0)
        check(iv.size == IV_LEN && wrapped.size == WRAPPED_LEN)

        val bytes = MAGIC + byteArrayOf(1) + pair.pub.x + pair.pub.pq + iv + wrapped
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) throw CryptoException("无法写入密钥文件")
        priv = HybridPrivateKey(pair.priv.x, pair.priv.pq)
        return bytes
    }

    private fun parsePublic(bytes: ByteArray) = HybridPublicKey(
        bytes.copyOfRange(4, 4 + HybridKem.X_LEN),
        bytes.copyOfRange(4 + HybridKem.X_LEN, PUB_END),
    )

    private fun aad(p: HybridPublicKey) = MAGIC + byteArrayOf(1) + p.x + p.pq

    private fun keystoreKey(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(KS_PROVIDER).apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            KS_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS_PROVIDER)
        return try {
            gen.init(spec(true))
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            gen.init(spec(false))
            gen.generateKey()
        }
    }

    private companion object {
        const val KS_PROVIDER = "AndroidKeyStore"
        const val KS_ALIAS = "mafari_vault_wrap_v1"
        const val IV_LEN = 12
        const val WRAPPED_LEN = HybridKem.X_LEN + HybridKem.PQ_SK_LEN + 16
        const val PUB_END = 4 + HybridKem.X_LEN + HybridKem.PQ_PK_LEN
        const val FILE_LEN = PUB_END + IV_LEN + WRAPPED_LEN
        val MAGIC = byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'K'.code.toByte())
    }
}

/**
 * 给「JSON 存在 SharedPreferences」这类场景用的薄封装：
 *  - 读：旧版明文（不以 mfe1: 开头）原样返回，调用方随后正常 persist 就会自动迁移成密文；
 *  - 写：永远写密文，加密失败时返回 null（绝不回退写明文）。
 */
object SecureStore {

    fun isLegacyPlaintext(raw: String) = !EncryptionEngine.isEncryptedString(raw)

    fun read(context: Context, raw: String, purpose: String): String? {
        if (isLegacyPlaintext(raw)) return raw
        return try {
            EncryptionEngine.get(context).openString(raw, purpose)
        } catch (e: KeyUnavailableException) {
            Diag.log("secure: key unavailable, vault reset: ${e.message}")
            EncryptionEngine.resetVault(context)
            null
        } catch (e: Exception) {
            Diag.log("secure: open failed: ${e.message}")
            null
        }
    }

    fun write(context: Context, plain: String, purpose: String): String? = try {
        EncryptionEngine.get(context).sealString(plain, purpose)
    } catch (e: Exception) {
        Diag.log("secure: seal failed: ${e.message}")
        null
    }
}
