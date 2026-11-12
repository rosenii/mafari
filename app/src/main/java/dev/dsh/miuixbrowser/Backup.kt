package dev.dsh.miuixbrowser

import android.content.Context
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * 加密备份文件（.mfbak）——可跨设备恢复，所以不依赖本机 Keystore：
 *
 *   每次导出现生成一对全新的混合密钥（X25519 + ML-KEM-768），备份里的每个单元都用
 *   [EncryptionEngine] 封进版本化信封（KEM → KEK → 封装随机 DEK → AES-256-GCM）。
 *   备份私钥再用「口令 → Argon2id → AES-256-GCM」包裹后放进文件头部之后。
 *   恢复时：口令 → 解出备份私钥 → 逐单元解信封。
 *
 * 文件布局（整数一律大端）：
 *   "MFBK" | 版本 u8 | KDF 编号 u8（1 = Argon2id v1.3） | m_KiB u32 | t u8 | p u8
 *   | 单元数 u16 | 创建时间 i64 | 标签长度 u16 | 标签 UTF-8 | salt 16 | pk_x 32 | pk_pq 1184
 *   ↑ 以上全部是包裹私钥的 AAD（头部、KDF 参数、公钥任何一位被改，口令都解不开）
 *   | 包裹 IV 12 | 包裹后的私钥 2448
 *   | 重复 n 次：名称长度 u16 | 名称 | 信封长度 u32 | 信封
 *
 * 单元名同时是该信封的上下文（AAD 的一部分），把单元改名 / 互换会解密失败。
 */
object BackupFormat {

    const val VERSION = 1
    const val EXTENSION = "mfbak"

    /** 界面上展示的方案名 */
    const val SCHEME_NAME = "X25519 + ML-KEM-768 混合封装 · HKDF-SHA-384 · AES-256-GCM"

    data class KdfParams(
        val memoryKiB: Int = 65_536, // 64 MiB
        val iterations: Int = 3,
        val parallelism: Int = 1,
    )

    class Contents(val createdAt: Long, val label: String, val units: Map<String, ByteArray>)

    private val MAGIC = "MFBK".toByteArray(Charsets.US_ASCII)
    private const val KDF_ARGON2ID = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val WRAPPED_LEN = HybridKem.X_LEN + HybridKem.PQ_SK_LEN + 16
    private const val UNIT_CONTEXT = "mafari.backup.v1/"

    // 读取时的上限：防止被构造的文件拖垮内存
    private const val MAX_MEMORY_KIB = 262_144
    private const val MAX_ITERATIONS = 10
    private const val MAX_UNITS = 4096

    fun write(
        units: Map<String, ByteArray>,
        password: CharArray,
        label: String,
        kdf: KdfParams = KdfParams(),
        createdAt: Long = System.currentTimeMillis(),
        rng: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(password.isNotEmpty()) { "口令不能为空" }
        require(units.size <= MAX_UNITS)

        val pair = HybridKem.generate(rng)
        val engine = EncryptionEngine(StaticKeyProvider(pair), rng)
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }

        val head = ByteArrayOutputStream()
        DataOutputStream(head).apply {
            write(MAGIC)
            writeByte(VERSION)
            writeByte(KDF_ARGON2ID)
            writeInt(kdf.memoryKiB)
            writeByte(kdf.iterations)
            writeByte(kdf.parallelism)
            writeShort(units.size)
            writeLong(createdAt)
            val labelBytes = label.toByteArray(Charsets.UTF_8)
            writeShort(labelBytes.size)
            write(labelBytes)
            write(salt)
            write(pair.pub.x)
            write(pair.pub.pq)
        }
        val aad = head.toByteArray()

        val wrapKey = argon2id(password, salt, kdf)
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val privBlob = pair.priv.x + pair.priv.pq
        val wrapped = try {
            gcm(true, wrapKey, iv, aad, privBlob)
        } finally {
            wrapKey.fill(0)
            privBlob.fill(0)
        }

        val out = ByteArrayOutputStream()
        out.write(aad)
        out.write(iv)
        out.write(wrapped)
        val data = DataOutputStream(out)
        for ((name, bytes) in units) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val envelope = engine.seal(bytes, UNIT_CONTEXT + name)
            data.writeShort(nameBytes.size)
            data.write(nameBytes)
            data.writeInt(envelope.size)
            data.write(envelope)
        }
        data.flush()
        return out.toByteArray()
    }

    fun read(file: ByteArray, password: CharArray): Contents {
        try {
            val input = DataInputStream(ByteArrayInputStream(file))
            val magic = ByteArray(4).also { input.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw CryptoException("不是 Mafari 备份文件")
            val version = input.readUnsignedByte()
            if (version != VERSION) throw CryptoException("不支持的备份版本：$version")
            if (input.readUnsignedByte() != KDF_ARGON2ID) throw CryptoException("不支持的口令派生算法")
            val kdf = KdfParams(input.readInt(), input.readUnsignedByte(), input.readUnsignedByte())
            if (kdf.memoryKiB !in 8..MAX_MEMORY_KIB || kdf.iterations !in 1..MAX_ITERATIONS || kdf.parallelism !in 1..4) {
                throw CryptoException("口令派生参数超出允许范围")
            }
            val unitCount = input.readUnsignedShort()
            if (unitCount > MAX_UNITS) throw CryptoException("备份单元数异常")
            val createdAt = input.readLong()
            val labelBytes = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            val salt = ByteArray(SALT_LEN).also { input.readFully(it) }
            val pkX = ByteArray(HybridKem.X_LEN).also { input.readFully(it) }
            val pkPq = ByteArray(HybridKem.PQ_PK_LEN).also { input.readFully(it) }
            val aadLen = file.size - input.available()
            val aad = file.copyOfRange(0, aadLen)
            val iv = ByteArray(IV_LEN).also { input.readFully(it) }
            val wrapped = ByteArray(WRAPPED_LEN).also { input.readFully(it) }

            val wrapKey = argon2id(password, salt, kdf)
            val privBlob = try {
                gcm(false, wrapKey, iv, aad, wrapped)
            } catch (e: CryptoException) {
                throw CryptoException("口令错误，或备份文件已损坏 / 被篡改", e)
            } finally {
                wrapKey.fill(0)
            }
            val priv = HybridPrivateKey(
                privBlob.copyOfRange(0, HybridKem.X_LEN),
                privBlob.copyOfRange(HybridKem.X_LEN, privBlob.size),
            )
            privBlob.fill(0)
            val engine = EncryptionEngine(StaticKeyProvider(HybridKeyPair(HybridPublicKey(pkX, pkPq), priv)))

            val units = LinkedHashMap<String, ByteArray>()
            repeat(unitCount) {
                val name = String(ByteArray(input.readUnsignedShort()).also { b -> input.readFully(b) }, Charsets.UTF_8)
                val envLen = input.readInt()
                if (envLen < 0 || envLen > input.available()) throw CryptoException("备份文件被截断")
                val envelope = ByteArray(envLen).also { b -> input.readFully(b) }
                units[name] = engine.open(envelope, UNIT_CONTEXT + name)
            }
            if (input.available() != 0) throw CryptoException("备份文件尾部有多余数据")
            return Contents(createdAt, String(labelBytes, Charsets.UTF_8), units)
        } catch (e: IOException) {
            throw CryptoException("备份文件格式错误或被截断", e)
        }
    }

    private fun argon2id(password: CharArray, salt: ByteArray, kdf: KdfParams): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(kdf.iterations)
            .withMemoryAsKB(kdf.memoryKiB)
            .withParallelism(kdf.parallelism)
            .withSalt(salt)
            .build()
        val pw = String(password).toByteArray(Charsets.UTF_8)
        val out = ByteArray(32)
        try {
            Argon2BytesGenerator().apply { init(params) }.generateBytes(pw, out)
        } finally {
            pw.fill(0)
        }
        return out
    }

    private fun gcm(encrypt: Boolean, key: ByteArray, iv: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv),
            )
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        } catch (e: GeneralSecurityException) {
            throw CryptoException(if (encrypt) "加密失败" else "解密失败", e)
        }
    }
}

/** 备份内容的收集与导出（Android 侧）。 */
object BackupManager {

    /** 进备份的 SharedPreferences（都是用户数据 / 设置）。诊断日志开关、Gecko 调试等不进。 */
    private val PREFS = listOf(
        "mafari", // 书签
        "mafari_history", // 历史
        "mafari_settings", // 应用设置（含代理账号等）
        "mafari_cookies", // Cookie 策略
        "mafari_ext", // 脚本
        "userscript_gm", // 脚本 GM_setValue 数据
        "mafari_ad_rules", // 去广告订阅 / 开关
        "mafari_menu", // 菜单配置
        "mafari_gecko_protect", // Gecko 高级保护
    )

    /** 设备上是密文的键 → 解密用的上下文；备份里必须存明文（再由备份自己的信封加密），否则换机无法恢复。 */
    private val DEVICE_ENCRYPTED = mapOf(
        "mafari" to mapOf("favorites" to SecureStore.PURPOSE_FAVORITES),
        "mafari_history" to mapOf("entries" to SecureStore.PURPOSE_HISTORY),
    )

    private val FILES = listOf("adblock/custom.txt")

    class Summary(val units: Int, val bytes: Int, val skipped: Int)

    fun suggestedFileName(): String =
        "mafari-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "." + BackupFormat.EXTENSION

    /** 收集所有单元；返回 (单元, 因无法解密而跳过的项数)。 */
    fun collect(context: Context): Pair<Map<String, ByteArray>, Int> {
        val app = context.applicationContext
        val units = LinkedHashMap<String, ByteArray>()
        var skipped = 0

        for (name in PREFS) {
            val all = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            val root = JSONObject()
            for ((key, value) in all) {
                val entry = JSONObject()
                when (value) {
                    is String -> {
                        var text: String = value
                        if (EncryptionEngine.isEncryptedString(value)) {
                            val purpose = DEVICE_ENCRYPTED[name]?.get(key)
                            val plain = purpose?.let { SecureStore.read(app, value, it) }
                            if (plain == null) {
                                skipped++
                                continue
                            }
                            text = plain
                        }
                        entry.put("t", "s").put("v", text)
                    }
                    is Int -> entry.put("t", "i").put("v", value)
                    is Long -> entry.put("t", "l").put("v", value)
                    is Float -> entry.put("t", "f").put("v", value.toDouble())
                    is Boolean -> entry.put("t", "b").put("v", value)
                    is Set<*> -> entry.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                    else -> continue
                }
                root.put(key, entry)
            }
            units["prefs/$name"] = root.toString().toByteArray(Charsets.UTF_8)
        }

        for (rel in FILES) {
            val f = File(app.filesDir, rel)
            if (f.isFile && f.length() in 1..(8L shl 20)) units["files/$rel"] = f.readBytes()
        }

        val meta = JSONObject()
            .put("app", app.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("units", JSONArray(units.keys.toList()))
        units["meta.json"] = meta.toString().toByteArray(Charsets.UTF_8)
        return units to skipped
    }

    /** 导出：收集 → 加密 → 写入 [out]（调用方负责在 IO 线程调用并关闭流）。口令用完会被清零。 */
    fun export(context: Context, out: OutputStream, password: CharArray): Summary {
        try {
            val (units, skipped) = collect(context)
            val label = "Mafari ${BuildConfig.VERSION_NAME} | X25519+ML-KEM-768 / HKDF-SHA-384 / AES-256-GCM | Argon2id"
            val bytes = BackupFormat.write(units, password, label)
            out.write(bytes)
            out.flush()
            return Summary(units.size, bytes.size, skipped)
        } finally {
            password.fill('\u0000')
        }
    }
}
