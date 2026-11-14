package dev.dsh.miuixbrowser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * 加密引擎：把任意字节 / 字符串 / 文件封进版本化信封。
 *
 * 具体算法在「算法套件」里（见 CipherSuite.kt，含新增 / 升级套件的步骤）；引擎只做三件事：
 *   - 加密：用 [writeSuiteId] 指定的套件；
 *   - 解密：读信封前缀里的套件号，分派给对应套件（所以旧套件加密的数据永远能解开）；
 *   - 升级：[isCurrent] / [reseal] 让调用方把旧套件的数据重新加密成当前套件。
 *
 * 私钥由 [KeyProvider] 提供：设备数据用 Android Keystore 保险库（[AndroidKeyVault]），
 * 备份文件用一次性的内存密钥（[StaticKeyProvider]）。
 */

open class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 私钥取不回来了（Keystore 密钥丢失 / 失效 / 密钥文件损坏）。此时旧数据已无法解密。 */
class KeyUnavailableException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

class EncryptionEngine(
    private val keys: KeyProvider,
    private val rng: SecureRandom = SecureRandom(),
    /** 新加密的数据使用的套件；解密不受它影响。 */
    val writeSuiteId: Int = CipherSuites.DEFAULT_ID,
) {

    fun seal(plaintext: ByteArray, context: String): ByteArray {
        val suite = CipherSuites.get(writeSuiteId)
        return suite.seal(keys.publicKey(suite), plaintext, context, rng)
    }

    fun open(envelope: ByteArray, context: String): ByteArray {
        val prefix = Envelope.parsePrefix(envelope)
        val suite = CipherSuites.get(prefix.suiteId)
        val pub = keys.publicKey(suite)
        if (!pub.keyId.contentEquals(prefix.keyId)) throw CryptoException("信封所用密钥与当前密钥不匹配")
        return suite.open(pub, keys.privateKey(suite), envelope, context)
    }

    /** 这个信封已经是当前写入套件、当前信封版本了吗？（只看前缀，不解密。） */
    fun isCurrent(envelope: ByteArray): Boolean = isCurrentEnvelope(envelope, writeSuiteId)

    /** 旧套件的信封 → 解开后用当前套件重新加密；已经是当前套件则返回 null（无需改动）。 */
    fun reseal(envelope: ByteArray, context: String): ByteArray? {
        if (isCurrent(envelope)) return null
        val plain = open(envelope, context)
        try {
            return seal(plain, context)
        } finally {
            plain.fill(0)
        }
    }

    // ---- 字符串 / 文件便利接口 ----

    /** 加密成 "mfe1:<base64>"（1 = 信封版本），可直接存进 SharedPreferences / JSON。 */
    fun sealString(text: String, context: String): String =
        STRING_PREFIX + Base64.getEncoder().encodeToString(seal(text.toByteArray(Charsets.UTF_8), context))

    fun openString(stored: String, context: String): String {
        if (!isEncryptedString(stored)) throw CryptoException("不是加密字符串")
        return String(open(decodeString(stored), context), Charsets.UTF_8)
    }

    /** 加密整个文件（例如 SQLite 数据库文件）；先写临时文件再改名，写到一半断电不会损坏旧文件。 */
    fun sealFile(src: File, dst: File, context: String) {
        if (src.length() > MAX_FILE_BYTES) throw CryptoException("文件过大（上限 ${MAX_FILE_BYTES shr 20} MiB）")
        writeAtomically(dst, seal(src.readBytes(), context))
    }

    fun openFile(src: File, dst: File, context: String) {
        if (src.length() > MAX_FILE_BYTES + MAX_OVERHEAD) throw CryptoException("文件过大")
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

    companion object {
        /** 字符串形式的前缀：mfe<信封版本>: 。写入永远用当前版本；读取按信封自身的版本分派。 */
        val STRING_PREFIX = "mfe${Envelope.VERSION}:"
        private const val STRING_PREFIX_LEN = 5
        private const val MAX_FILE_BYTES = 128L shl 20
        private const val MAX_OVERHEAD = 8 * 1024

        /** 形如 "mfe<数字>:" */
        fun isEncryptedString(s: String) =
            s.length > STRING_PREFIX_LEN && s.startsWith("mfe") && s[3].isDigit() && s[4] == ':'

        /**
         * 存下来的东西需要重新加密吗？——旧版明文，或者不是 [targetSuiteId] / 当前信封版本。
         * 只看信封前缀，不解密、不碰密钥。无法判断（格式损坏）时返回 false。
         */
        fun needsUpgrade(stored: String, targetSuiteId: Int = CipherSuites.DEFAULT_ID): Boolean {
            if (!isEncryptedString(stored)) return true
            return try {
                !isCurrentEnvelope(decodeString(stored, prefixOnly = true), targetSuiteId)
            } catch (_: CryptoException) {
                false
            }
        }

        private fun isCurrentEnvelope(envelope: ByteArray, suiteId: Int): Boolean = try {
            Envelope.parsePrefix(envelope).suiteId == suiteId
        } catch (_: CryptoException) {
            false
        }

        private fun decodeString(stored: String, prefixOnly: Boolean = false): ByteArray = try {
            // 前缀只需 14 字节：取 20 个 Base64 字符（= 15 字节，恰好 4 的倍数，不涉及填充）
            val body = stored.substring(STRING_PREFIX_LEN)
            Base64.getDecoder().decode(if (prefixOnly) body.take(20) else body)
        } catch (e: IllegalArgumentException) {
            throw CryptoException("Base64 无效", e)
        }

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
 * 私钥保险库：每个套件一对密钥，互不影响，用到时才生成。
 *  - 公钥明文落盘（加密时不需要碰 Keystore）；
 *  - 私钥经 Android Keystore 里不可导出的 AES-256-GCM 密钥包裹后落盘
 *    （AAD = 文件头 + 公钥，防止把别的密钥文件换进来）。所有套件共用同一把 Keystore 包裹密钥。
 *
 * 文件（目录 filesDir/mafari_vault/）：
 *   suite<套件号>.key（v2）：魔数 "MFK" | 版本 2 | 套件号(1) | 公钥长度 u16 | 公钥 | iv(12) | 包裹后的私钥(私钥长度 + 16)
 *   hybrid_keys.v1（旧版，仅套件 1，只读兼容）：魔数 "MFK" | 版本 1 | 公钥(1216) | iv(12) | 包裹后的私钥(2432 + 16)
 *   两种格式的 AAD 都是「文件开头到公钥末尾」。旧文件保持原样继续使用，不会被迁移或改写。
 */
class AndroidKeyVault(context: Context) : KeyProvider {

    private val dir = File(context.filesDir, "mafari_vault")
    private val legacyFile = File(dir, LEGACY_FILE)
    private val pubs = HashMap<Int, SuitePublicKey>()
    private val privs = HashMap<Int, SuitePrivateKey>()

    private class Record(val pub: ByteArray, val aad: ByteArray, val iv: ByteArray, val wrapped: ByteArray)

    @Synchronized
    override fun publicKey(suite: CipherSuite): SuitePublicKey {
        pubs[suite.id]?.let { return it }
        val record = readOrCreate(suite)
        return SuitePublicKey(suite.id, record.pub).also { pubs[suite.id] = it }
    }

    @Synchronized
    override fun privateKey(suite: CipherSuite): SuitePrivateKey {
        privs[suite.id]?.let { return it }
        val record = readOrCreate(suite)
        // 刚生成的密钥已经缓存在 privs 里
        privs[suite.id]?.let { return it }
        try {
            val key = keystoreKey(create = false) ?: throw KeyUnavailableException("Keystore 里的包裹密钥已丢失")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.iv))
            cipher.updateAAD(record.aad)
            val plain = cipher.doFinal(record.wrapped)
            if (plain.size != suite.privateKeyLen) throw KeyUnavailableException("私钥长度异常")
            pubs[suite.id] = SuitePublicKey(suite.id, record.pub)
            return SuitePrivateKey(suite.id, plain).also { privs[suite.id] = it }
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

    /** 删除全部密钥文件与 Keystore 密钥；下次使用时自动生成新密钥。旧数据无法再解密。 */
    @Synchronized
    fun reset() {
        dir.listFiles()?.forEach { it.delete() }
        runCatching { KeyStore.getInstance(KS_PROVIDER).apply { load(null) }.deleteEntry(KS_ALIAS) }
        pubs.clear()
        privs.clear()
    }

    private fun keyFile(suite: CipherSuite) = File(dir, "suite${suite.id}.key")

    private fun readOrCreate(suite: CipherSuite): Record {
        val f = keyFile(suite)
        if (f.exists()) return parseGeneric(suite, f.readBytes())
        if (suite.id == CipherSuites.HYBRID_768 && legacyFile.exists()) return parseLegacy(suite, legacyFile.readBytes())
        return create(suite)
    }

    private fun parseGeneric(suite: CipherSuite, b: ByteArray): Record {
        if (b.size < GENERIC_HEADER_LEN ||
            !b.copyOfRange(0, 3).contentEquals(MAGIC) ||
            b[3].toInt() != 2 ||
            (b[4].toInt() and 0xFF) != suite.id
        ) {
            throw KeyUnavailableException("密钥文件损坏")
        }
        val pubLen = ((b[5].toInt() and 0xFF) shl 8) or (b[6].toInt() and 0xFF)
        val pubEnd = GENERIC_HEADER_LEN + pubLen
        if (pubLen != suite.publicKeyLen || b.size != pubEnd + IV_LEN + suite.privateKeyLen + TAG_LEN) {
            throw KeyUnavailableException("密钥文件损坏")
        }
        return Record(
            pub = b.copyOfRange(GENERIC_HEADER_LEN, pubEnd),
            aad = b.copyOfRange(0, pubEnd),
            iv = b.copyOfRange(pubEnd, pubEnd + IV_LEN),
            wrapped = b.copyOfRange(pubEnd + IV_LEN, b.size),
        )
    }

    private fun parseLegacy(suite: CipherSuite, b: ByteArray): Record {
        val pubEnd = 4 + suite.publicKeyLen
        if (b.size != pubEnd + IV_LEN + suite.privateKeyLen + TAG_LEN ||
            !b.copyOfRange(0, 3).contentEquals(MAGIC) ||
            b[3].toInt() != 1
        ) {
            throw KeyUnavailableException("密钥文件损坏")
        }
        return Record(
            pub = b.copyOfRange(4, pubEnd),
            aad = b.copyOfRange(0, pubEnd),
            iv = b.copyOfRange(pubEnd, pubEnd + IV_LEN),
            wrapped = b.copyOfRange(pubEnd + IV_LEN, b.size),
        )
    }

    private fun create(suite: CipherSuite): Record {
        val pair = suite.generateKeyPair(SecureRandom())
        val pub = pair.pub.bytes
        val header = MAGIC + byteArrayOf(
            2,
            suite.id.toByte(),
            (pub.size shr 8).toByte(),
            pub.size.toByte(),
        ) + pub

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(create = true)!!)
        cipher.updateAAD(header)
        val wrapped = cipher.doFinal(pair.priv.bytes)
        val iv = cipher.iv
        check(iv.size == IV_LEN && wrapped.size == suite.privateKeyLen + TAG_LEN)

        val file = keyFile(suite)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(header + iv + wrapped)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw CryptoException("无法写入密钥文件")
        }
        privs[suite.id] = pair.priv
        pubs[suite.id] = pair.pub
        return Record(pub, header, iv, wrapped)
    }

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
        const val LEGACY_FILE = "hybrid_keys.v1"
        const val IV_LEN = 12
        const val TAG_LEN = 16

        /** 魔数 3 + 版本 1 + 套件号 1 + 公钥长度 2 */
        const val GENERIC_HEADER_LEN = 7
        val MAGIC = byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'K'.code.toByte())
    }
}

/**
 * 给「JSON 存在 SharedPreferences」这类场景用的薄封装：
 *  - 读：旧版明文（不以 mfe<数字>: 开头）原样返回；
 *  - 升级：[needsUpgrade] 为真（旧明文 / 旧套件）时，调用方在成功读出后正常 persist，就会自动换成当前套件的密文；
 *  - 写：永远写当前套件的密文，加密失败时返回 null（绝不回退写明文）。
 */
object SecureStore {

    /** 各加密存储单元的上下文（进 AAD）。备份导出时也要靠它解开设备密文。 */
    const val PURPOSE_FAVORITES = "mafari.favorites.items"
    const val PURPOSE_HISTORY = "mafari.history.entries"
    const val PURPOSE_HISTORY_DB = "mafari.history.dbkey"
    const val PURPOSE_WEBDAV = "mafari.webdav.password"
    const val PURPOSE_GIT = "mafari.git.token"
    const val PURPOSE_GIT_KEY = "mafari.git.deploykey"
    const val PURPOSE_GIT_KEY_PASS = "mafari.git.keypass"
    const val PURPOSE_BACKUP_PASSWORD = "mafari.backup.defaultpassword"
    const val PURPOSE_GECKO_COOKIES = "mafari.backup.geckocookies"

    /** 密码库每条记录的上下文前缀，后面接记录 id（见 PasswordDb.purposeOf）。 */
    const val PURPOSE_PASSWORD_ITEM = "mafari.password.item."

    fun isLegacyPlaintext(raw: String) = !EncryptionEngine.isEncryptedString(raw)

    /** 旧版明文，或用旧套件 / 旧信封版本加密的，需要在下次保存时重新加密。 */
    fun needsUpgrade(raw: String) = EncryptionEngine.needsUpgrade(raw)

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
