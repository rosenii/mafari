package dev.dsh.miuixbrowser

import android.content.Context
import android.content.Intent
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
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 加密备份文件（.mfbak）——可跨设备恢复，所以不依赖本机 Keystore：
 *
 *   每次导出现生成一对全新的密钥（算法套件见 CipherSuite.kt），备份里的每个单元都用
 *   [EncryptionEngine] 封进版本化信封。备份私钥再用「口令 → KDF → AES-256-GCM」包裹后放进文件头部之后。
 *   恢复时：口令 → 解出备份私钥 → 逐单元解信封。
 *
 * 文件头里记录了「用的是哪个算法套件、哪个口令 KDF」，所以以后新增 / 更换套件，旧备份依然能读，
 * 新备份也只是文件头里的套件号不同。写入永远用最新版本，读取支持所有已发布版本。
 *
 * 版本 2（当前写入，整数一律大端）：
 *   "MFBK" | 版本 u8 = 2 | KDF 编号 u8（1 = Argon2id v1.3） | m_KiB u32 | t u8 | p u8
 *   | 套件号 u8 | 单元数 u16 | 创建时间 i64 | 标签长度 u16 | 标签 UTF-8 | salt 16
 *   | 公钥长度 u16 | 公钥 | 包裹私钥长度 u16
 *   ↑ 以上全部是包裹私钥的 AAD（头部、KDF 参数、套件号、公钥任何一位被改，口令都解不开）
 *   | 包裹 IV 12 | 包裹后的私钥
 *   | 重复 n 次：名称长度 u16 | 名称 | 信封长度 u32 | 信封
 *
 * 版本 1（只读兼容；套件固定为 1，公钥 / 私钥长度由套件决定，没有显式的套件号 / 长度字段）：
 *   "MFBK" | 版本 u8 = 1 | KDF 编号 u8 | m_KiB u32 | t u8 | p u8
 *   | 单元数 u16 | 创建时间 i64 | 标签长度 u16 | 标签 UTF-8 | salt 16 | 公钥
 *   | 包裹 IV 12 | 包裹后的私钥 | 单元…（同上）
 *
 * 单元名同时是该信封的上下文（AAD 的一部分），把单元改名 / 互换会解密失败。
 *
 * 新增口令 KDF：在 [BackupFormat.deriveKey] 里加一个编号；改变包裹方式或头布局：新增文件版本。
 */
object BackupFormat {

    /** 当前写入的文件版本；读取支持 1..VERSION */
    const val VERSION = 2
    const val EXTENSION = "mfbak"

    data class KdfParams(
        val memoryKiB: Int = 65_536, // 64 MiB
        val iterations: Int = 3,
        val parallelism: Int = 1,
    )

    class Contents(
        val createdAt: Long,
        val label: String,
        val units: Map<String, ByteArray>,
        /** 文件版本 */
        val version: Int = VERSION,
        /** 备份使用的算法套件号 */
        val suiteId: Int = CipherSuites.DEFAULT_ID,
    )

    private val MAGIC = "MFBK".toByteArray(Charsets.US_ASCII)
    private const val KDF_ARGON2ID = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    private const val V1_SUITE_ID = CipherSuites.HYBRID_768

    // 读取时的上限：防止被构造的文件拖垮内存
    private const val MAX_MEMORY_KIB = 262_144
    private const val MAX_ITERATIONS = 10
    private const val MAX_UNITS = 4096

    private fun unitContext(version: Int) = "mafari.backup.v$version/"

    private class Header(
        val version: Int,
        val kdfId: Int,
        val kdf: KdfParams,
        val suite: CipherSuite,
        val unitCount: Int,
        val createdAt: Long,
        val label: ByteArray,
        val salt: ByteArray,
        val pub: ByteArray,
        val aad: ByteArray,
    )

    fun write(
        units: Map<String, ByteArray>,
        password: CharArray,
        label: String,
        kdf: KdfParams = KdfParams(),
        createdAt: Long = System.currentTimeMillis(),
        rng: SecureRandom = SecureRandom(),
        suiteId: Int = CipherSuites.DEFAULT_ID,
    ): ByteArray {
        require(password.isNotEmpty()) { "口令不能为空" }
        require(units.size <= MAX_UNITS)

        val suite = CipherSuites.get(suiteId)
        val pair = suite.generateKeyPair(rng)
        val engine = EncryptionEngine(StaticKeyProvider(pair), rng, suite.id)
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val wrappedLen = suite.privateKeyLen + TAG_LEN

        val head = ByteArrayOutputStream()
        DataOutputStream(head).apply {
            write(MAGIC)
            writeByte(VERSION)
            writeByte(KDF_ARGON2ID)
            writeInt(kdf.memoryKiB)
            writeByte(kdf.iterations)
            writeByte(kdf.parallelism)
            writeByte(suite.id)
            writeShort(units.size)
            writeLong(createdAt)
            val labelBytes = label.toByteArray(Charsets.UTF_8)
            writeShort(labelBytes.size)
            write(labelBytes)
            write(salt)
            writeShort(pair.pub.bytes.size)
            write(pair.pub.bytes)
            writeShort(wrappedLen)
        }
        val aad = head.toByteArray()

        val wrapKey = deriveKey(KDF_ARGON2ID, password, salt, kdf)
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val wrapped = try {
            aesGcm(true, wrapKey, iv, aad, pair.priv.bytes)
        } finally {
            wrapKey.fill(0)
        }
        check(wrapped.size == wrappedLen)

        val out = ByteArrayOutputStream()
        out.write(aad)
        out.write(iv)
        out.write(wrapped)
        val data = DataOutputStream(out)
        for ((name, bytes) in units) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val envelope = engine.seal(bytes, unitContext(VERSION) + name)
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
            val header = readHeader(file, input)
            val suite = header.suite
            val iv = ByteArray(IV_LEN).also { input.readFully(it) }
            val wrapped = ByteArray(suite.privateKeyLen + TAG_LEN).also { input.readFully(it) }

            val wrapKey = deriveKey(header.kdfId, password, header.salt, header.kdf)
            val privBytes = try {
                aesGcm(false, wrapKey, iv, header.aad, wrapped)
            } catch (e: CryptoException) {
                throw CryptoException("口令错误，或备份文件已损坏 / 被篡改", e)
            } finally {
                wrapKey.fill(0)
            }
            val pair = SuiteKeyPair(SuitePublicKey(suite.id, header.pub), SuitePrivateKey(suite.id, privBytes))
            val engine = EncryptionEngine(StaticKeyProvider(pair), writeSuiteId = suite.id)
            val context = unitContext(header.version)

            val units = LinkedHashMap<String, ByteArray>()
            repeat(header.unitCount) {
                val name = String(ByteArray(input.readUnsignedShort()).also { b -> input.readFully(b) }, Charsets.UTF_8)
                val envLen = input.readInt()
                if (envLen < 0 || envLen > input.available()) throw CryptoException("备份文件被截断")
                val envelope = ByteArray(envLen).also { b -> input.readFully(b) }
                units[name] = engine.open(envelope, context + name)
            }
            if (input.available() != 0) throw CryptoException("备份文件尾部有多余数据")
            return Contents(header.createdAt, String(header.label, Charsets.UTF_8), units, header.version, suite.id)
        } catch (e: IOException) {
            throw CryptoException("备份文件格式错误或被截断", e)
        }
    }

    /** 解析到「包裹 IV」之前的全部头部（也就是 AAD 的范围）。 */
    private fun readHeader(file: ByteArray, input: DataInputStream): Header {
        val magic = ByteArray(4).also { input.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw CryptoException("不是 Mafari 备份文件")
        val version = input.readUnsignedByte()
        if (version !in 1..VERSION) throw CryptoException("不支持的备份版本：$version")

        val kdfId = input.readUnsignedByte()
        if (kdfId != KDF_ARGON2ID) throw CryptoException("不支持的口令派生算法")
        val kdf = KdfParams(input.readInt(), input.readUnsignedByte(), input.readUnsignedByte())
        if (kdf.memoryKiB !in 8..MAX_MEMORY_KIB || kdf.iterations !in 1..MAX_ITERATIONS || kdf.parallelism !in 1..4) {
            throw CryptoException("口令派生参数超出允许范围")
        }
        // v1 没有套件号字段，固定为套件 1
        val suite = if (version >= 2) CipherSuites.get(input.readUnsignedByte()) else CipherSuites.get(V1_SUITE_ID)
        val unitCount = input.readUnsignedShort()
        if (unitCount > MAX_UNITS) throw CryptoException("备份单元数异常")
        val createdAt = input.readLong()
        val label = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
        val salt = ByteArray(SALT_LEN).also { input.readFully(it) }
        if (version >= 2 && input.readUnsignedShort() != suite.publicKeyLen) throw CryptoException("备份公钥长度与算法套件不符")
        val pub = ByteArray(suite.publicKeyLen).also { input.readFully(it) }
        if (version >= 2 && input.readUnsignedShort() != suite.privateKeyLen + TAG_LEN) {
            throw CryptoException("备份私钥长度与算法套件不符")
        }
        val aadLen = file.size - input.available()
        return Header(version, kdfId, kdf, suite, unitCount, createdAt, label, salt, pub, file.copyOfRange(0, aadLen))
    }

    /** 口令 → 32 字节密钥。新增 KDF：在这里加一个编号，并在 [readHeader] 放行。 */
    internal fun deriveKey(kdfId: Int, password: CharArray, salt: ByteArray, kdf: KdfParams): ByteArray {
        if (kdfId != KDF_ARGON2ID) throw CryptoException("不支持的口令派生算法")
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
}

/**
 * 备份内容类别：备份时可自选；恢复时只覆盖备份里「有数据」的类别，
 * 没选 / 当时为空（书签、历史等没有内容）的类别一律保持原样，不会用空数据覆盖。
 */
enum class BackupCategory(val id: String, val title: String, val summary: String) {
    BOOKMARKS("bookmarks", "书签", "收藏与文件夹"),
    HISTORY("history", "历史记录", "浏览历史"),
    SETTINGS("settings", "应用设置", "各项设置、搜索引擎、代理、Cookie 策略、菜单等"),
    SCRIPTS("scripts", "用户脚本", "油猴脚本及其开关"),
    SCRIPT_DATA("script_data", "脚本数据", "脚本用 GM_setValue 保存的值"),
    ADBLOCK("adblock", "去广告规则", "订阅、开关与自定义规则"),
    GECKO_COOKIES("gecko_cookies", "Gecko Cookie", "Gecko 内核里的登录状态（仅持久 Cookie，不含会话 Cookie）");

    companion object {
        fun byId(id: String): BackupCategory? = entries.firstOrNull { it.id == id }

        /** 默认勾选：除 Cookie（含登录凭据）外的全部 */
        val DEFAULT_SELECTION: Set<BackupCategory> = entries.filter { it != GECKO_COOKIES }.toSet()
    }
}

/** 备份内容的收集与导出（Android 侧）。 */
object BackupManager {

    /** 进备份的 SharedPreferences（都是用户数据 / 设置）。诊断日志开关、Gecko 调试等不进。 */
    private val PREFS = listOf(
        "mafari", // 书签（favorites*）+ 其它杂项设置
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
        "mafari" to mapOf(
            "favorites" to SecureStore.PURPOSE_FAVORITES,
            // 文件夹注册表（文件夹 id / 顺序 / 空文件夹）：Floccus 靠它认文件夹，必须一起进备份
            "favorites_folders" to SecureStore.PURPOSE_FAVORITES,
        ),
        "mafari_history" to mapOf("entries" to SecureStore.PURPOSE_HISTORY),
    )

    private val FILES = listOf("adblock/custom.txt")

    /** Gecko Cookie 在备份里的单元名（明文 JSON 数组，由备份信封加密） */
    private const val COOKIES_UNIT = "gecko/cookies"

    /** 「mafari」里属于书签的键；其余键算应用设置 */
    private val BOOKMARK_KEYS = setOf("favorites", "favorites_folders")

    /** 数据实际存在数据库里的键：备份时从数据库导出，不读 SharedPreferences（迁移后那里是空的） */
    private val DB_BACKED = setOf(
        "mafari" to "favorites",
        "mafari" to "favorites_folders",
        "mafari_history" to "entries",
        "mafari_ext" to "scripts",
    )

    /** 这些键的值是 JSON 数组；备份里是空数组时视为「没有数据」，恢复时不据此覆盖 */
    private val ARRAY_KEYS = setOf("mafari" to "favorites", "mafari_history" to "entries", "mafari_ext" to "scripts")

    /** (SharedPreferences 名, 键) → 所属类别 */
    private fun categoryOf(pref: String, key: String): BackupCategory? = when (pref) {
        "mafari" -> if (key in BOOKMARK_KEYS) BackupCategory.BOOKMARKS else BackupCategory.SETTINGS
        "mafari_history" -> BackupCategory.HISTORY
        "mafari_ext" -> BackupCategory.SCRIPTS
        "userscript_gm" -> BackupCategory.SCRIPT_DATA
        "mafari_ad_rules" -> BackupCategory.ADBLOCK
        "mafari_settings", "mafari_cookies", "mafari_menu", "mafari_gecko_protect" -> BackupCategory.SETTINGS
        else -> null
    }

    class Summary(
        val units: Int,
        val bytes: Int,
        val skipped: Int,
        /** 实际写进备份的类别 */
        val included: List<BackupCategory> = emptyList(),
        /** 选了但当前没有数据、因此没写进备份的类别 */
        val empty: List<BackupCategory> = emptyList(),
    )

    /** [collect] 的结果 */
    class Collected(
        val units: Map<String, ByteArray>,
        val included: List<BackupCategory>,
        val empty: List<BackupCategory>,
        val skipped: Int,
    )

    fun suggestedFileName(): String =
        "mafari-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "." + BackupFormat.EXTENSION

    private fun jsonArrayLength(text: String): Int = runCatching { JSONArray(text).length() }.getOrDefault(-1)

    /**
     * 收集 [categories] 里各类别的数据；没有数据的类别不写进备份（也就不会在恢复时覆盖）。
     * 选了 Gecko Cookie 时会等 Gecko 内核就绪后读取，读不到抛 [IOException]（不会悄悄生成缺 Cookie 的备份）。
     * 调用方在 IO 线程调用。
     */
    fun collect(context: Context, categories: Set<BackupCategory>): Collected {
        val app = context.applicationContext
        val units = LinkedHashMap<String, ByteArray>()
        val included = LinkedHashSet<BackupCategory>()
        val roots = LinkedHashMap<String, JSONObject>()
        var skipped = 0

        fun add(pref: String, key: String, entry: JSONObject, cat: BackupCategory) {
            roots.getOrPut(pref) { JSONObject() }.put(key, entry)
            included.add(cat)
        }
        fun text(value: String) = JSONObject().put("t", "s").put("v", value)

        // 1) 普通 SharedPreferences 键
        for (name in PREFS) {
            if (name == "userscript_gm") continue // GM 值在数据库里，见下
            val all = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
            for ((key, value) in all) {
                if ((name to key) in DB_BACKED) continue
                val cat = categoryOf(name, key) ?: continue
                if (cat !in categories) continue
                val entry = JSONObject()
                when (value) {
                    is String -> {
                        var plain: String = value
                        if (EncryptionEngine.isEncryptedString(value)) {
                            val purpose = DEVICE_ENCRYPTED[name]?.get(key)
                            val opened = purpose?.let { SecureStore.read(app, value, it) }
                            if (opened == null) {
                                skipped++
                                continue
                            }
                            plain = opened
                        }
                        entry.put("t", "s").put("v", plain)
                    }
                    is Int -> entry.put("t", "i").put("v", value)
                    is Long -> entry.put("t", "l").put("v", value)
                    is Float -> entry.put("t", "f").put("v", value.toDouble())
                    is Boolean -> entry.put("t", "b").put("v", value)
                    is Set<*> -> entry.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                    else -> continue
                }
                add(name, key, entry, cat)
            }
        }

        // 2) 数据库里的类别：导出成旧格式的明文 JSON（恢复后下次启动自动导入）。导出为空 = 没有数据 = 不进备份
        if (BackupCategory.HISTORY in categories) {
            HistoryDb.exportJson(app)?.let { add("mafari_history", "entries", text(it), BackupCategory.HISTORY) }
        }
        if (BackupCategory.BOOKMARKS in categories) {
            BookmarkDb.exportBackup(app)?.let { (favorites, folders) ->
                if (jsonArrayLength(favorites) > 0) {
                    add("mafari", "favorites", text(favorites), BackupCategory.BOOKMARKS)
                    add("mafari", "favorites_folders", text(folders), BackupCategory.BOOKMARKS)
                }
            }
        }
        if (BackupCategory.SCRIPTS in categories) {
            ExtensionStore.exportBackup(app)?.let { scripts ->
                if (jsonArrayLength(scripts) > 0) add("mafari_ext", "scripts", text(scripts), BackupCategory.SCRIPTS)
            }
        }
        if (BackupCategory.SCRIPT_DATA in categories) {
            GmStore.exportJson(app)?.let {
                roots["userscript_gm"] = JSONObject(it) // 已是 {"sid::k": {t, v}} 条目格式
                included.add(BackupCategory.SCRIPT_DATA)
            }
        }

        // 3) 文件
        if (BackupCategory.ADBLOCK in categories) {
            for (rel in FILES) {
                val f = File(app.filesDir, rel)
                if (f.isFile && f.length() in 1..(8L shl 20)) {
                    units["files/$rel"] = f.readBytes()
                    included.add(BackupCategory.ADBLOCK)
                }
            }
        }

        // 4) Gecko Cookie：经内置拓展读取；失败直接中止，不生成缺 Cookie 的备份
        if (BackupCategory.GECKO_COOKIES in categories && EngineSupport.GECKO_BUNDLED) {
            val json = GeckoGm.exportCookies()
            if (jsonArrayLength(json) > 0) {
                units[COOKIES_UNIT] = json.toByteArray(Charsets.UTF_8)
                included.add(BackupCategory.GECKO_COOKIES)
            }
        }

        for ((name, root) in roots) units["prefs/$name"] = root.toString().toByteArray(Charsets.UTF_8)

        val done = BackupCategory.entries.filter { it in included }
        val meta = JSONObject()
            .put("app", app.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("categories", JSONArray(done.map { it.id }))
            .put("units", JSONArray(units.keys.toList()))
        units["meta.json"] = meta.toString().toByteArray(Charsets.UTF_8)
        return Collected(units, done, BackupCategory.entries.filter { it in categories && it !in included }, skipped)
    }

    /**
     * 解密并校验后的恢复计划。所有校验、解析、设备密文重新加密都在 [prepareRestore] 里完成，
     * 所以 [applyRestore] 开始写入之前任何失败都不会改动现有数据。
     */
    class RestorePlan internal constructor(
        val createdAt: Long,
        val label: String,
        val versionName: String,
        /** 备份文件使用的算法套件简称（界面展示） */
        val suiteName: String,
        /** 备份文件格式版本 */
        val formatVersion: Int,
        /** 备份里有数据的类别（固定顺序）；不在其中的类别恢复时保持原样 */
        val categories: List<BackupCategory>,
        /** 备份里有、但本机恢复不了的类别（如 Gecko Cookie 在不支持 Gecko 的安装包 / 设备上） */
        val unavailable: Set<BackupCategory>,
        internal val prefs: Map<BackupCategory, Map<String, Map<String, Any>>>,
        internal val files: Map<String, ByteArray>,
        internal val cookies: ByteArray?,
    ) {
        /** 默认要恢复的类别：备份里有且本机能恢复的全部 */
        val defaultSelection: Set<BackupCategory> get() = categories.filter { it !in unavailable }.toSet()
    }

    /** 读取备份文件的字节数上限（防止选错文件拖垮内存） */
    const val MAX_BACKUP_BYTES = 64 shl 20

    /** 从 [input] 读出备份文件全部字节；超过上限抛 [CryptoException]。 */
    fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BACKUP_BYTES) throw CryptoException("文件过大，不像是 Mafari 备份")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 解密备份并生成恢复计划（耗时：Argon2id，调用方放 IO 线程）。口令用完会被清零。
     * 只接受白名单内的单元名（[PREFS] / [FILES] / Cookie），其余一律忽略；
     * 备份里是空数组的书签 / 历史 / 脚本不算「有数据」，不会进入计划。
     */
    fun prepareRestore(context: Context, file: ByteArray, password: CharArray): RestorePlan {
        val app = context.applicationContext
        val contents = try {
            BackupFormat.read(file, password)
        } finally {
            password.fill('\u0000')
        }

        val meta = contents.units["meta.json"]
            ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
            ?: throw CryptoException("备份缺少元数据，可能不是 Mafari 备份")

        val prefs = LinkedHashMap<BackupCategory, LinkedHashMap<String, LinkedHashMap<String, Any>>>()
        val files = LinkedHashMap<String, ByteArray>()
        var cookies: ByteArray? = null
        for ((name, bytes) in contents.units) {
            when {
                name.startsWith("prefs/") -> {
                    val pref = name.removePrefix("prefs/")
                    if (pref in PREFS) {
                        for ((key, value) in parsePrefs(app, pref, bytes)) {
                            val cat = categoryOf(pref, key) ?: continue
                            prefs.getOrPut(cat) { LinkedHashMap() }.getOrPut(pref) { LinkedHashMap() }[key] = value
                        }
                    }
                }
                name.startsWith("files/") -> {
                    val rel = name.removePrefix("files/")
                    if (rel in FILES) files[rel] = bytes
                }
                name == COOKIES_UNIT -> {
                    val ok = runCatching { JSONArray(String(bytes, Charsets.UTF_8)).length() > 0 }.getOrDefault(false)
                    if (ok) cookies = bytes
                }
            }
        }
        // 书签 / 历史必须带着真正的数据键才算有数据（只剩文件夹注册表之类的不算），避免用空数据覆盖
        if (prefs[BackupCategory.BOOKMARKS]?.get("mafari")?.containsKey("favorites") != true) prefs.remove(BackupCategory.BOOKMARKS)
        if (prefs[BackupCategory.HISTORY]?.get("mafari_history")?.containsKey("entries") != true) prefs.remove(BackupCategory.HISTORY)

        val present = LinkedHashSet<BackupCategory>(prefs.keys)
        if (files.isNotEmpty()) present.add(BackupCategory.ADBLOCK)
        if (cookies != null) present.add(BackupCategory.GECKO_COOKIES)
        if (present.isEmpty()) throw CryptoException("备份里没有可恢复的数据")

        val unavailable = if (BackupCategory.GECKO_COOKIES in present && !EngineSupport.geckoAvailable()) {
            setOf(BackupCategory.GECKO_COOKIES)
        } else {
            emptySet()
        }

        return RestorePlan(
            createdAt = contents.createdAt,
            label = contents.label,
            versionName = meta.optString("versionName"),
            suiteName = CipherSuites.find(contents.suiteId)?.shortName ?: "套件 ${contents.suiteId}",
            formatVersion = contents.version,
            categories = BackupCategory.entries.filter { it in present },
            unavailable = unavailable,
            prefs = prefs,
            files = files,
            cookies = cookies,
        )
    }

    private fun parsePrefs(app: Context, pref: String, bytes: ByteArray): Map<String, Any> {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw CryptoException("备份中的 $pref 数据已损坏", e)
        }
        val out = LinkedHashMap<String, Any>()
        for (key in root.keys()) {
            val entry = root.optJSONObject(key) ?: throw CryptoException("备份中的 $pref 数据格式错误")
            try {
                val value: Any = when (entry.getString("t")) {
                    "s" -> {
                        val text = entry.getString("v")
                        // 空数组 = 备份时这一类就是空的：不放进恢复计划，恢复时也就不会用空数据覆盖
                        if ((pref to key) in ARRAY_KEYS && jsonArrayLength(text) <= 0) continue
                        val purpose = DEVICE_ENCRYPTED[pref]?.get(key)
                        if (purpose != null) {
                            // 备份里存的是明文：写回前用本机密钥重新封装，和正常运行时的存储形态一致
                            if (EncryptionEngine.isEncryptedString(text)) throw CryptoException("备份中的 $pref 数据格式错误")
                            SecureStore.write(app, text, purpose) ?: throw CryptoException("无法用本机密钥加密收藏 / 历史，恢复已取消")
                        } else {
                            text
                        }
                    }
                    "i" -> entry.getInt("v")
                    "l" -> entry.getLong("v")
                    "f" -> entry.getDouble("v").toFloat()
                    "b" -> entry.getBoolean("v")
                    "ss" -> {
                        val arr = entry.getJSONArray("v")
                        LinkedHashSet<String>().also { set -> for (i in 0 until arr.length()) set.add(arr.getString(i)) }
                    }
                    else -> throw CryptoException("备份中的 $pref 含有未知类型的数据")
                }
                out[key] = value
            } catch (e: CryptoException) {
                throw e
            } catch (e: Exception) {
                throw CryptoException("备份中的 $pref 数据格式错误", e)
            }
        }
        return out
    }

    private enum class WriteMode { WHOLE, ONLY_KEYS, EXCEPT_KEYS }

    /**
     * 写入 [selected] 里（且备份里有数据、本机能恢复）的类别：每个类别只替换它自己的数据，
     * 备份里没有 / 没选的类别保持不动。收藏 / 历史等有内存缓存的数据，必须在这之后重启进程才会生效。
     * 在 IO 线程调用。
     */
    fun applyRestore(context: Context, plan: RestorePlan, selected: Set<BackupCategory> = plan.defaultSelection) {
        val app = context.applicationContext
        val chosen = plan.categories.filter { it in selected && it !in plan.unavailable }
        if (chosen.isEmpty()) throw IOException("没有选择要恢复的内容")

        for (cat in chosen) {
            val byPref = plan.prefs[cat].orEmpty()
            // 数据库里的类别：先清空再由备份里的明文 JSON 在重启后导入（备份里没有该数据键时不清，见 prepareRestore）
            when (cat) {
                BackupCategory.HISTORY -> runCatching { HistoryDb.clearAll(app) }
                BackupCategory.BOOKMARKS -> BookmarkDb.clearAll(app)
                BackupCategory.SCRIPT_DATA -> runCatching { GmStore.clearAll(app) }
                BackupCategory.SCRIPTS -> if (byPref["mafari_ext"]?.containsKey("scripts") == true) ScriptDb.clearAll(app)
                else -> {}
            }
            for ((name, values) in byPref) {
                val mode = if (name == "mafari") {
                    if (cat == BackupCategory.BOOKMARKS) WriteMode.ONLY_KEYS else WriteMode.EXCEPT_KEYS
                } else {
                    WriteMode.WHOLE
                }
                writePrefs(app, name, values, mode)
            }
            if (cat == BackupCategory.ADBLOCK) {
                for ((rel, bytes) in plan.files) {
                    val target = File(app.filesDir, rel)
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, target.name + ".restore")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        if (!tmp.renameTo(target)) throw IOException("写入 $rel 失败")
                    }
                }
            }
            if (cat == BackupCategory.GECKO_COOKIES && EngineSupport.GECKO_BUNDLED) {
                val bytes = plan.cookies ?: continue
                // Cookie 不在这里直接写：重启后 Gecko 内置拓展就绪时再导入（此时 Gecko 会把它们落盘，不怕进程被杀）
                GeckoGm.stagePendingCookies(app, String(bytes, Charsets.UTF_8))
            }
        }
    }

    private fun writePrefs(app: Context, name: String, values: Map<String, Any>, mode: WriteMode) {
        val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = sp.edit()
        when (mode) {
            WriteMode.WHOLE -> editor.clear()
            WriteMode.ONLY_KEYS -> BOOKMARK_KEYS.forEach { editor.remove(it) }
            WriteMode.EXCEPT_KEYS -> sp.all.keys.filter { it !in BOOKMARK_KEYS }.forEach { editor.remove(it) }
        }
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
            }
        }
        if (!editor.commit()) throw IOException("写入 $name 失败")
    }

    /** 重启应用：拉起启动 Activity（清空任务栈），随后结束当前进程，让所有内存缓存重新从磁盘加载。 */
    fun restartApp(context: Context) {
        val app = context.applicationContext
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            app.startActivity(it)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** 导出 [categories]：收集 → 加密 → 写入 [out]（调用方负责在 IO 线程调用并关闭流）。口令用完会被清零。 */
    fun export(
        context: Context,
        out: OutputStream,
        password: CharArray,
        categories: Set<BackupCategory> = BackupCategory.DEFAULT_SELECTION,
    ): Summary {
        try {
            if (categories.isEmpty()) throw IOException("没有选择要备份的内容")
            val c = collect(context, categories)
            if (c.included.isEmpty()) throw IOException("所选内容里目前没有数据，未生成备份")
            val label = "Mafari ${BuildConfig.VERSION_NAME} | ${CipherSuites.default.name} | Argon2id"
            val bytes = BackupFormat.write(c.units, password, label)
            out.write(bytes)
            out.flush()
            return Summary(c.units.size, bytes.size, c.skipped, c.included, c.empty)
        } finally {
            password.fill('\u0000')
        }
    }
}
