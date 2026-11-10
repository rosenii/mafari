package dev.dsh.miuixbrowser

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 一个订阅源（URL 订阅，或从本地文件导入的规则，后者 [url] 为空）。 */
data class AdSub(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** 最近一次成功下载的时间，0 = 还没下载过 */
    val updatedAt: Long = 0L,
    val ruleCount: Int = 0,
    /** 最近一次更新失败的原因，空 = 正常 */
    val error: String = "",
)

data class AdPreset(val name: String, val url: String, val summary: String)

/** 推荐订阅（点一下即可订阅）。地址都是各规则项目官方对外提供的订阅链接。 */
val AD_PRESETS = listOf(
    AdPreset(
        "AdGuard 中文",
        "https://filters.adtidy.org/extension/ublock/filters/224.txt",
        "国内网站广告，首选",
    ),
    AdPreset(
        "EasyList China",
        "https://easylist-downloads.adblockplus.org/easylistchina.txt",
        "EasyList 的中文补充规则",
    ),
    AdPreset(
        "EasyList",
        "https://easylist.to/easylist/easylist.txt",
        "国际通用广告规则",
    ),
    AdPreset(
        "AdGuard 移动广告",
        "https://filters.adtidy.org/extension/ublock/filters/11.txt",
        "针对移动端网页的广告",
    ),
    AdPreset(
        "anti-AD",
        "https://anti-ad.net/easylist.txt",
        "国内广告域名合集",
    ),
)

/** 旧版本存放在脚本列表里的去广告订阅，由 [ExtensionStore] 读出后交给 [AdBlock.migrateLegacy]。 */
data class LegacyAdSub(val name: String, val url: String, val enabled: Boolean)

/**
 * 去广告：订阅管理 + 网络拦截 + 元素隐藏。
 *
 * - 规则文件存放在 filesDir/adblock/，每个订阅一个文本文件；custom.txt 是用户自定义规则
 *   （手写的规则、长按「标记为广告」产生的规则都在里面）。
 * - 订阅变化后在后台线程重新合并成 [AdEngine]，构建期间旧引擎继续工作，完成后原子替换。
 * - 网络拦截走 WebView 的 shouldInterceptRequest（[intercept]）；元素隐藏由
 *   document-start 脚本通过 [AdBlockBridge] 取回本站点的 CSS 注入（所有框架）。
 */
object AdBlock {

    const val FP_CANVAS = 1
    const val FP_ALL = 15
    const val FP_UNPROTECTED_OBSERVE = 0
    const val FP_UNPROTECTED_RELOAD = 1
    const val FP_UNPROTECTED_STOP = 2
    const val FP_WEBGL = 2
    const val FP_AUDIO = 4
    const val FP_FONTS = 8

    private const val PREFS = "mafari_adblock"
    private const val MAX_BYTES = 24 * 1024 * 1024
    private const val CUSTOM_FILE = "custom.txt"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private var inited = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val compileLock = Mutex()
    private val generation = AtomicInteger(0)
    private val subsLock = Any()

    private class CachedList(val stamp: Long, val list: ParsedList)

    private val parsedCache = ConcurrentHashMap<String, CachedList>()

    /* ---------- 界面状态 ---------- */

    val subs = mutableStateListOf<AdSub>()
    val allowHosts = mutableStateListOf<String>()

    /**
     * 指纹保护豁免列表：命中的站点（含子域名）不注入指纹保护。
     * 与 [allowHosts]（去广告豁免）是两个独立的概念，互不影响；只按「frame 自己的 host」判断，从不看顶层站点。
     */
    val fpExemptHosts = mutableStateListOf<String>()

    /**
     * 「落地在未预注册 origin」时的处理（见 FingerprintInjector.UnprotectedPolicy）：
     * 0 仅记录并补注册（对之后的加载生效）　1 停止并在保护下重新加载　2 停止加载。
     */
    var fpUnprotectedPolicy by mutableIntStateOf(FP_UNPROTECTED_RELOAD)
        private set

    var enabled by mutableStateOf(true)
        private set
    var cosmetic by mutableStateOf(true)
        private set

    /** 自动更新：0 手动 1 每天 2 每 3 天 3 每周 */
    var autoUpdate by mutableIntStateOf(2)
        private set

    /* 指纹保护：默认全部关闭；每类一个开关（设置 → 隐私与安全），受站点白名单控制，独立于去广告总开关 */
    var fpCanvas by mutableStateOf(false)
        private set
    var fpWebgl by mutableStateOf(false)
        private set
    var fpAudio by mutableStateOf(false)
        private set
    var fpFonts by mutableStateOf(false)
        private set

    /** 当前引擎里生效的规则条数（含内置基础规则与自定义规则） */
    var loadedRules by mutableIntStateOf(0)
        private set
    var compiling by mutableStateOf(false)
        private set
    var customLines by mutableIntStateOf(0)
        private set

    /* ---------- 后台线程读取的快照 ---------- */

    @Volatile private var engine: AdEngine? = null
    @Volatile private var subsShadow: List<AdSub> = emptyList()
    @Volatile private var fastEnabled = false
    @Volatile private var fastCosmetic = true
    @Volatile private var fastAllow: Set<String> = emptySet()
    @Volatile private var fpMask = 0
    @Volatile private var fastFpExempt: Set<String> = emptySet()

    /** 指纹保护设置 / 豁免列表的版本号：变化后各 [FingerprintInjector] 会撤销旧注册（旧脚本里内联着旧配置）。 */
    private val fpGen = AtomicInteger(0)
    val fingerprintGeneration: Int get() = fpGen.get()

    /** 本次启动的会话密钥：站点 seed 由它派生，同一站点本次启动内稳定，重启后变化；密钥本身不离开原生层。 */
    private val sessionSecret: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

    private val blockedSession = AtomicLong(0)
    private var baseTotal = 0L

    /** 累计拦截的请求数（跨启动） */
    val blockedTotal: Long get() = baseTotal + blockedSession.get()

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dir = File(appContext.filesDir, "adblock").also { it.mkdirs() }

        enabled = prefs.getBoolean("enabled", true)
        cosmetic = prefs.getBoolean("cosmetic", true)
        autoUpdate = prefs.getInt("auto_update", 2).coerceIn(0, 3)
        baseTotal = prefs.getLong("blocked_total", 0L)
        fastEnabled = enabled
        fastCosmetic = cosmetic
        fpCanvas = prefs.getBoolean("fp_canvas", false)
        fpUnprotectedPolicy = prefs.getInt("fp_unprotected_policy", FP_UNPROTECTED_RELOAD).coerceIn(0, 2)
        fpWebgl = prefs.getBoolean("fp_webgl", false)
        fpAudio = prefs.getBoolean("fp_audio", false)
        fpFonts = prefs.getBoolean("fp_fonts", false)
        fpReads = prefs.getLong("fp_reads", 0L)
        fpSince = prefs.getLong("fp_since", 0L)
        refreshFpMask()

        subs.addAll(loadSubs())
        subsShadow = subs.toList()
        allowHosts.addAll(loadAllow())
        fastAllow = allowHosts.toSet()
        loadFpExempt()

        migrateUserAdRules()
        customLines = countCustomLines()
        requestRebuild()
    }

    /* ------------------------------------------------------------------ */
    /* 开关                                                                */
    /* ------------------------------------------------------------------ */

    fun updateEnabled(value: Boolean) {
        enabled = value
        fastEnabled = value
        prefs.edit().putBoolean("enabled", value).apply()
    }

    fun updateCosmetic(value: Boolean) {
        cosmetic = value
        fastCosmetic = value
        prefs.edit().putBoolean("cosmetic", value).apply()
    }

    /** kind：[FP_CANVAS] / [FP_WEBGL] / [FP_AUDIO] / [FP_FONTS]；对新打开（或刷新）的页面生效。 */
    fun updateFpUnprotectedPolicy(policy: Int) {
        fpUnprotectedPolicy = policy.coerceIn(0, 2)
        prefs.edit().putInt("fp_unprotected_policy", fpUnprotectedPolicy).apply()
    }

    fun updateFingerprint(kind: Int, value: Boolean) {
        val wasOn = fpMask != 0
        when (kind) {
            FP_CANVAS -> { fpCanvas = value; prefs.edit().putBoolean("fp_canvas", value).apply() }
            FP_WEBGL -> { fpWebgl = value; prefs.edit().putBoolean("fp_webgl", value).apply() }
            FP_AUDIO -> { fpAudio = value; prefs.edit().putBoolean("fp_audio", value).apply() }
            FP_FONTS -> { fpFonts = value; prefs.edit().putBoolean("fp_fonts", value).apply() }
        }
        refreshFpMask()
        if (!wasOn && fpMask != 0) resetFpReport()
    }

    private fun refreshFpMask() {
        var m = 0
        if (fpCanvas) m = m or FP_CANVAS
        if (fpWebgl) m = m or FP_WEBGL
        if (fpAudio) m = m or FP_AUDIO
        if (fpFonts) m = m or FP_FONTS
        fpMask = m
        fpGen.incrementAndGet()
    }

    fun updateAutoUpdate(mode: Int) {
        autoUpdate = mode.coerceIn(0, 3)
        prefs.edit().putInt("auto_update", autoUpdate).apply()
    }

    /* ------------------------------------------------------------------ */
    /* 订阅管理                                                            */
    /* ------------------------------------------------------------------ */

    /** 从 URL 添加订阅：下载 → 校验是规则列表 → 保存。 */
    suspend fun subscribe(url: String, nameHint: String = ""): Result<AdSub> {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            return Result.failure(IllegalArgumentException("请输入 http(s) 开头的订阅地址"))
        }
        if (subsShadow.any { it.url.equals(u, ignoreCase = true) }) {
            return Result.failure(IllegalStateException("已经订阅过这个地址"))
        }
        val fetched = withContext(Dispatchers.IO) { runCatching { fetchList(u) } }
        val f = fetched.getOrElse { return Result.failure(it) }
        val id = UUID.randomUUID().toString()
        val saved = withContext(Dispatchers.IO) { runCatching { writeList(id, f.text, f.parsed) } }
        saved.getOrElse { return Result.failure(it) }
        val sub = AdSub(
            id = id,
            name = f.parsed.title.ifBlank { nameHint.ifBlank { hostOf(u) } },
            url = u,
            enabled = true,
            updatedAt = System.currentTimeMillis(),
            ruleCount = f.parsed.ruleCount,
        )
        synchronized(subsLock) {
            subs.add(sub)
            persistSubs()
        }
        requestRebuild()
        return Result.success(sub)
    }

    /** 导入本地规则文件。没有来源地址，之后无法自动更新。 */
    suspend fun importLocal(name: String, text: String): Result<AdSub> {
        if (!AdParser.looksLikeFilterList(text)) {
            return Result.failure(IllegalArgumentException("这不是有效的去广告规则列表"))
        }
        val parsed = withContext(Dispatchers.Default) { AdParser.parse(text) }
        if (parsed.ruleCount == 0) return Result.failure(IllegalArgumentException("列表里没有可用规则"))
        val id = UUID.randomUUID().toString()
        val saved = withContext(Dispatchers.IO) { runCatching { writeList(id, text, parsed) } }
        saved.getOrElse { return Result.failure(it) }
        val sub = AdSub(
            id = id,
            name = parsed.title.ifBlank { name.ifBlank { "本地规则" } },
            url = "",
            enabled = true,
            updatedAt = System.currentTimeMillis(),
            ruleCount = parsed.ruleCount,
        )
        synchronized(subsLock) {
            subs.add(sub)
            persistSubs()
        }
        requestRebuild()
        return Result.success(sub)
    }

    /** 重新下载某个订阅，成功返回规则条数。 */
    suspend fun refresh(id: String): Result<Int> = refresh(id, rebuild = true)

    private suspend fun refresh(id: String, rebuild: Boolean): Result<Int> {
        val sub = subsShadow.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalStateException("订阅不存在"))
        if (sub.url.isBlank()) return Result.failure(IllegalStateException("本地导入的规则没有更新地址"))
        val fetched = withContext(Dispatchers.IO) { runCatching { fetchList(sub.url) } }
        val f = fetched.getOrElse {
            updateSub(id) { s -> s.copy(error = it.message ?: "更新失败") }
            return Result.failure(it)
        }
        val saved = withContext(Dispatchers.IO) { runCatching { writeList(id, f.text, f.parsed) } }
        saved.getOrElse {
            updateSub(id) { s -> s.copy(error = it.message ?: "保存失败") }
            return Result.failure(it)
        }
        updateSub(id) { s ->
            s.copy(
                name = s.name.ifBlank { f.parsed.title },
                updatedAt = System.currentTimeMillis(),
                ruleCount = f.parsed.ruleCount,
                error = "",
            )
        }
        if (rebuild) requestRebuild()
        return Result.success(f.parsed.ruleCount)
    }

    /** 更新所有启用且有地址的订阅，返回 (成功数, 总数)。 */
    suspend fun refreshAll(): Pair<Int, Int> {
        val targets = subsShadow.filter { it.enabled && it.url.isNotBlank() }
        var ok = 0
        // 每个订阅各自成功后都重建一次引擎会白白重复十几次（每次都要重新合并索引）；全部下载完只重建一次
        for (t in targets) if (refresh(t.id, rebuild = false).isSuccess) ok++
        if (ok > 0) requestRebuild()
        return ok to targets.size
    }

    fun setSubEnabled(id: String, value: Boolean) {
        updateSub(id) { it.copy(enabled = value) }
        requestRebuild()
    }

    fun removeSub(id: String) {
        synchronized(subsLock) {
            subs.removeAll { it.id == id }
            persistSubs()
        }
        parsedCache.remove(id)
        runCatching { fileOf(id).delete() }
        requestRebuild()
    }

    /** 按设置的间隔在后台自动更新订阅，启动时调用一次。 */
    suspend fun autoUpdateIfDue() {
        val days = when (autoUpdate) {
            1 -> 1
            2 -> 3
            3 -> 7
            else -> return
        }
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_auto", 0L) < days * 86_400_000L) return
        if (subsShadow.none { it.enabled && it.url.isNotBlank() }) return
        // 至少有一个订阅更新成功才记时间；没网时全部失败，下次启动再试，而不是白白等几天
        val (ok, _) = refreshAll()
        if (ok > 0) prefs.edit().putLong("last_auto", now).apply()
    }

    /** 旧版本脚本列表里的去广告订阅 → 迁移到这里，并在后台把规则下载回来。 */
    fun migrateLegacy(items: List<LegacyAdSub>) {
        if (items.isEmpty()) return
        val added = ArrayList<String>()
        synchronized(subsLock) {
            for (item in items) {
                if (item.url.isBlank() || subs.any { s -> s.url.equals(item.url, ignoreCase = true) }) continue
                val id = UUID.randomUUID().toString()
                subs.add(AdSub(id = id, name = item.name.ifBlank { hostOf(item.url) }, url = item.url, enabled = item.enabled))
                added.add(id)
            }
            if (added.isNotEmpty()) persistSubs()
        }
        if (added.isNotEmpty()) {
            scope.launch { added.forEach { runCatching { refresh(it) } } }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 自定义规则 / 站点白名单                                              */
    /* ------------------------------------------------------------------ */

    fun readCustom(): String =
        runCatching { File(dir, CUSTOM_FILE).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    fun saveCustom(text: String) {
        synchronized(this) {
            val f = File(dir, CUSTOM_FILE)
            if (text.isBlank()) f.delete() else f.writeText(text.trim() + "\n")
            customLines = countCustomLines()
        }
        requestRebuild()
    }

    /** 长按「标记为广告」：记成一条 `站点##选择器`。选择器不安全（可能逃出 CSS）时拒绝。 */
    fun addUserCosmetic(host: String, selector: String): Boolean {
        val h = host.trim().lowercase()
        val s = selector.trim()
        if (!AdParser.isSafeSelector(s)) return false
        if (h.isEmpty() || h.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') }) return false
        val line = "$h##$s"
        synchronized(this) {
            val f = File(dir, CUSTOM_FILE)
            val current = if (f.exists()) f.readText() else ""
            if (current.lineSequence().any { it.trim() == line }) return true
            f.writeText((current.trimEnd() + "\n" + line).trimStart() + "\n")
            customLines = countCustomLines()
        }
        requestRebuild()
        return true
    }

    fun allowSite(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("www.")
        if (h.isEmpty() || h.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') }) return false
        if (h in allowHosts) return true
        allowHosts.add(h)
        persistAllow()
        return true
    }

    fun disallowSite(host: String) {
        allowHosts.remove(host)
        persistAllow()
    }


    private fun hostAllowed(host: String): Boolean = hostIn(fastAllow, host)

    /** host 或其任一父域名在 [set] 中。 */
    private fun hostIn(set: Set<String>, host: String): Boolean {
        if (set.isEmpty() || host.isEmpty()) return false
        // 请求热路径每个子资源都会走到这里：已经是小写（绝大多数情况）就不再复制字符串
        var h = if (host.any { it in 'A'..'Z' }) host.lowercase() else host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /* ------------------------------------------------------------------ */
    /* WebView 接入                                                        */
    /* ------------------------------------------------------------------ */

    /** shouldInterceptRequest 里调用：命中返回空响应，否则返回 null 放行。 */
    fun intercept(request: WebResourceRequest, pageHost: String, trackers: Boolean): WebResourceResponse? {
        if (!fastEnabled || request.isForMainFrame) return null
        val uri = request.url
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        // pageHost 来自 Uri.host，不保证小写；绝大多数情况已是小写，只在含大写时才复制字符串
        val page = if (pageHost.any { it in 'A'..'Z' }) pageHost.lowercase() else pageHost
        if (hostAllowed(page)) return null
        val accept = request.requestHeaders?.get("Accept")
        val url = uri.toString().lowercase()

        var hit = false
        if (trackers && TrackerList.matches(host) && AdMatch.isThirdParty(host, page)) hit = true
        if (!hit) {
            val eng = engine
            if (eng != null && eng.shouldBlock(url, page, accept)) hit = true
        }
        if (!hit) return null

        val n = blockedSession.incrementAndGet()
        if (n % 40L == 0L) prefs.edit().putLong("blocked_total", baseTotal + n).apply()
        return blockedResponse(AdMatch.inferType(url, accept))
    }

    /** 元素隐藏 CSS（JS 桥调用，任意线程）。 */
    fun cosmeticCss(hostRaw: String?, topHostRaw: String?): String {
        if (!fastEnabled || !fastCosmetic) return ""
        val host = hostRaw?.trim()?.lowercase().orEmpty()
        if (host.isEmpty()) return ""
        val top = topHostRaw?.trim()?.lowercase().orEmpty()
        if (hostAllowed(host) || (top.isNotEmpty() && hostAllowed(top))) return ""
        return engine?.cssFor(host).orEmpty()
    }

    /* ------------------------------------------------------------------ */
    /* 指纹保护：豁免列表 + 按 origin 生成配置                                 */
    /* ------------------------------------------------------------------ */

    /** 规范化域名输入；格式不合法返回 null。 */
    private fun normalizeSite(host: String): String? {
        val h = host.trim().lowercase().removePrefix("www.")
        if (h.isEmpty() || h.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') }) return null
        return h
    }

    fun exemptFingerprintSite(host: String): Boolean {
        val h = normalizeSite(host) ?: return false
        if (h in fpExemptHosts) return true
        fpExemptHosts.add(h)
        persistFpExempt()
        return true
    }

    fun unexemptFingerprintSite(host: String) {
        fpExemptHosts.remove(host)
        persistFpExempt()
    }

    /**
     * 豁免列表里是否有落在 [site]（可注册域名）之下的条目（site 自己或它的任一子域名）。
     * 有的话不能给该站点注册 `*.site` 通配规则，否则会把保护注入到被豁免的子域名上。
     */
    fun fpExemptUnder(site: String): Boolean {
        val s = site.lowercase()
        return fastFpExempt.any { it == s || it.endsWith(".$s") }
    }

    /** frame 自己的 host（或其父域名）在豁免列表里。 */
    fun isFingerprintExempt(host: String): Boolean = hostIn(fastFpExempt, host.lowercase())

    private fun persistFpExempt() {
        fastFpExempt = fpExemptHosts.toSet()
        prefs.edit().putString("fp_exempt", JSONArray(fpExemptHosts.toList()).toString()).apply()
        fpGen.incrementAndGet()
    }

    /**
     * 读取指纹豁免列表。旧版本里指纹保护复用去广告的站点白名单：首次升级时把现有白名单一次性复制过来，
     * 保持用户已有的豁免效果；之后两个列表各自独立。
     */
    private fun loadFpExempt() {
        val raw = prefs.getString("fp_exempt", null)
        val list = if (raw == null) {
            allowHosts.toList()
        } else {
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }
        fpExemptHosts.addAll(list)
        fastFpExempt = fpExemptHosts.toSet()
        if (raw == null) prefs.edit().putString("fp_exempt", JSONArray(list).toString()).apply()
    }

    /** 四项开关里至少开了一项（豁免列表与 origin 另算）。 */
    val fingerprintActive: Boolean get() = fpMask != 0

    /**
     * 自上次「从全关到开启」以来被指纹保护处理过的读取次数（页面经 [TrustedChannel] 低频上报，仅作展示）；
     * 持久化，重启不清零，全部关闭后再开启时重新计数。
     */
    var fpReads by mutableLongStateOf(0L)
        private set

    /** 上次开启保护的时间（毫秒），0 表示从未开启过。 */
    var fpSince by mutableLongStateOf(0L)
        private set

    fun recordFingerprintReads(n: Long) {
        if (n <= 0) return
        fpReads += n
        prefs.edit().putLong("fp_reads", fpReads).apply()
    }

    private fun resetFpReport() {
        fpReads = 0L
        fpSince = System.currentTimeMillis()
        prefs.edit().putLong("fp_reads", 0L).putLong("fp_since", fpSince).apply()
    }

    /** 总开关：任一项开启即为开。 */
    val fpMaster: Boolean get() = fpMask != 0

    /**
     * 总开关。开启：恢复上次的选择（没有记录则四项全开）；关闭：记住当前选择后全部关闭。
     * 只有「全关 → 有开」才重置隐私报告的计数。
     */
    fun setFingerprintMaster(on: Boolean) {
        val wasOn = fpMask != 0
        if (on) {
            val last = prefs.getInt("fp_last_mask", FP_ALL).and(FP_ALL).let { if (it == 0) FP_ALL else it }
            fpCanvas = last and FP_CANVAS != 0
            fpWebgl = last and FP_WEBGL != 0
            fpAudio = last and FP_AUDIO != 0
            fpFonts = last and FP_FONTS != 0
        } else {
            if (fpMask != 0) prefs.edit().putInt("fp_last_mask", fpMask).apply()
            fpCanvas = false; fpWebgl = false; fpAudio = false; fpFonts = false
        }
        prefs.edit()
            .putBoolean("fp_canvas", fpCanvas).putBoolean("fp_webgl", fpWebgl)
            .putBoolean("fp_audio", fpAudio).putBoolean("fp_fonts", fpFonts).apply()
        refreshFpMask()
        if (!wasOn && fpMask != 0) resetFpReport()
    }

    /** 某个 origin 的指纹保护配置，内联进该 origin 专属的注入脚本。 */
    class FpConfig(val mask: Int, val seed: Long)

    /**
     * 给定 frame origin，返回它应得的配置；四项全关 / 命中豁免列表返回 null（= 不注入）。
     * 指纹保护在「隐私与安全」里，独立于去广告总开关。只看 frame 自己的 origin，不涉及顶层站点。
     */
    fun fingerprintConfigFor(origin: WebOrigin): FpConfig? {
        if (isFingerprintExempt(origin.host)) return null
        return fingerprintSiteConfig(AdMatch.registrable(origin.host))
    }

    /** 按可注册域名取配置（同一站点下的所有来源规则共用一份脚本、一个 seed）；四项全关返回 null。豁免由调用方先过滤。 */
    fun fingerprintSiteConfig(site: String): FpConfig? {
        val mask = fpMask
        if (mask == 0) return null
        return FpConfig(mask, deriveSeed(site))
    }

    /** seed = HMAC-SHA256(会话密钥, 可注册域名) 的前 32 位。 */
    private fun deriveSeed(site: String): Long {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(sessionSecret, "HmacSHA256"))
        val d = mac.doFinal(site.toByteArray(Charsets.UTF_8))
        return ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
    }

    private const val FP_CFG_TOKEN = "/*@MAFARI_FP_CFG@*/null"

    /**
     * 把配置内联进脚本模板，得到这组来源规则专属的 document-start 脚本。
     * rules：脚本运行时 self.origin 必须命中的规则（同时也是注册规则）；
     * embedders：允许嵌入它的跨源祖先规则（没有预注册的嵌入方清单时为空）。
     */
    fun fingerprintScriptFor(rules: Collection<String>, embedders: Collection<String>, config: FpConfig): String {
        val template = fingerprintJs()
        if (template.isEmpty() || !template.contains(FP_CFG_TOKEN) || rules.isEmpty()) return ""
        val json = JSONObject()
            .put("mask", config.mask)
            .put("seed", config.seed)
            .put("rules", JSONArray(rules.toList()))
            .put("embedders", JSONArray(embedders.toList()))
            .toString()
        return template.replace(FP_CFG_TOKEN, json)
    }

    @Volatile private var fpJs: String? = null

    /** 指纹保护脚本模板（assets/mafari_fingerprint.js，配置占位符为 null），读一次后缓存。 */
    fun fingerprintJs(): String {
        fpJs?.let { return it }
        val text = runCatching {
            appContext.assets.open("mafari_fingerprint.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onFailure { Diag.log("adblock: fingerprint script load failed ${it.message}") }.getOrDefault("")
        fpJs = text
        return text
    }

    @Volatile private var fpPreregJson: String? = null

    /** 预注册清单（assets/mafari_fp_prereg.json，见 [FpPreregistry]），读一次后缓存；缺失 / 读取失败按空清单。 */
    fun fingerprintPreregJson(): String {
        fpPreregJson?.let { return it }
        val text = runCatching {
            appContext.assets.open("mafari_fp_prereg.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onFailure { Diag.log("fingerprint: prereg list load failed ${it.message}") }.getOrNull()
            ?: return "" // 读取失败（如 AdBlock 尚未初始化）不缓存，下次重试
        fpPreregJson = text
        return text
    }

    private val EMPTY = ByteArray(0)

    // 1x1 透明 GIF
    private val PIXEL_GIF = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
        0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
    )

    private fun blockedResponse(type: Int): WebResourceResponse = when {
        type == AdType.IMAGE -> WebResourceResponse("image/gif", null, ByteArrayInputStream(PIXEL_GIF))
        type == AdType.STYLESHEET -> WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(EMPTY))
        type == AdType.SUBDOC -> WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(EMPTY))
        (type and AdType.SCRIPT) != 0 -> WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(EMPTY))
        else -> WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(EMPTY))
    }

    /**
     * document-start 注入到所有框架：向 [AdBlockBridge] 要本站点的隐藏 CSS，插入 <style>。
     * 带重入保护，系统不支持 document-start 脚本时由页面加载回调再补注入一次。
     */
    const val COSMETIC_JS = """(function(){
  try {
    if (window.__mafariAdblockInit) return;
    // 先确认桥已就绪再占位：桥没准备好时，后面的兜底注入还能再试
    if (typeof MafariAdblock === 'undefined') return;
    var host = location.hostname;
    if (!host) return;
    var topHost = host;
    try {
      var ao = location.ancestorOrigins;
      if (ao && ao.length) topHost = new URL(ao[ao.length - 1]).hostname;
    } catch (e) {}
    var css = '';
    try { css = MafariAdblock.css(host, topHost); } catch (e) { return; }
    window.__mafariAdblockInit = 1;
    if (!css) return;
    // 首选 adoptedStyleSheets：不受页面 CSP（style-src）限制，严格 CSP 的站点（GitHub 等）也能生效
    try {
      if (typeof CSSStyleSheet === 'function' && 'adoptedStyleSheets' in document) {
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(css);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
        return;
      }
    } catch (e) {}
    var st = null;
    function mount() {
      var root = document.head || document.documentElement;
      if (!root) return false;
      if (!st) {
        st = document.createElement('style');
        st.setAttribute('data-mafari-adblock', '1');
        st.textContent = css;
      }
      if (!st.isConnected) root.appendChild(st);
      return true;
    }
    if (!mount()) {
      var wait = new MutationObserver(function () { if (mount()) wait.disconnect(); });
      wait.observe(document, { childList: true, subtree: true });
    }
    var watch = new MutationObserver(function () { if (st && !st.isConnected) mount(); });
    if (document.documentElement) watch.observe(document.documentElement, { childList: true });
  } catch (e) {}
})();"""

    /* ------------------------------------------------------------------ */
    /* 引擎构建                                                            */
    /* ------------------------------------------------------------------ */

    private val baseList: ParsedList by lazy { AdParser.parse(BASE_RULES) }

    fun requestRebuild() {
        val gen = generation.incrementAndGet()
        scope.launch {
            delay(150)
            if (gen != generation.get()) return@launch
            compileLock.withLock { compileNow() }
        }
    }

    private fun compileNow() {
        main.post { compiling = true }
        try {
            val lists = ArrayList<ParsedList>()
            lists.add(baseList)
            val counts = HashMap<String, Int>()
            val liveIds = HashSet<String>()
            for (s in subsShadow) {
                if (!s.enabled) continue
                val file = fileOf(s.id)
                if (!file.exists()) continue
                liveIds.add(s.id)
                val stamp = file.lastModified()
                val cached = parsedCache[s.id]
                val parsed = if (cached != null && cached.stamp == stamp) {
                    cached.list
                } else {
                    AdParser.parse(file.readText(Charsets.UTF_8)).also { parsedCache[s.id] = CachedList(stamp, it) }
                }
                lists.add(parsed)
                counts[s.id] = parsed.ruleCount
            }
            // 停用 / 删除的订阅不占内存
            parsedCache.keys.retainAll(liveIds)

            val custom = File(dir, CUSTOM_FILE)
            if (custom.exists()) lists.add(AdParser.parse(custom.readText(Charsets.UTF_8)))

            val built = AdEngine(lists)
            engine = built
            val total = built.networkRuleCount + built.cosmeticRuleCount
            main.post {
                loadedRules = total
                compiling = false
                counts.forEach { (id, c) ->
                    val cur = subs.firstOrNull { it.id == id }
                    if (cur != null && cur.ruleCount != c) updateSub(id) { s -> s.copy(ruleCount = c) }
                }
            }
        } catch (t: Throwable) {
            Diag.log("adblock: compile failed ${t.message}")
            main.post { compiling = false }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 下载 / 存储                                                         */
    /* ------------------------------------------------------------------ */

    private class Fetched(val text: String, val parsed: ParsedList)

    private fun fetchList(url: String): Fetched {
        val text = download(url)
        if (!AdParser.looksLikeFilterList(text)) throw IOException("不是有效的去广告规则列表")
        val parsed = AdParser.parse(text)
        if (parsed.ruleCount == 0) throw IOException("列表里没有可用规则")
        return Fetched(text, parsed)
    }

    private fun download(start: String): String {
        var current = start
        for (hop in 0..5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 Mafari")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("重定向缺少目标地址")
                    current = URL(URL(current), loc).toString()
                    if (!current.startsWith("http://", true) && !current.startsWith("https://", true)) {
                        throw IOException("不支持的重定向目标")
                    }
                    continue
                }
                if (code !in 200..299) throw IOException("服务器返回 HTTP $code")
                if (conn.contentLengthLong > MAX_BYTES) throw IOException("文件过大")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("文件过大")
                        out.write(buf, 0, n)
                    }
                }
                return out.toString("UTF-8")
            } catch (e: java.net.UnknownHostException) {
                throw IOException("无法解析域名，请检查网络或链接")
            } catch (e: java.net.SocketTimeoutException) {
                throw IOException("连接超时")
            } catch (e: javax.net.ssl.SSLException) {
                throw IOException("HTTPS 连接失败（证书或协议错误）")
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e.message ?: "下载失败")
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("重定向次数过多")
    }

    private fun fileOf(id: String) = File(dir, "$id.txt")

    private fun writeList(id: String, text: String, parsed: ParsedList?) {
        val target = fileOf(id)
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("写入规则文件失败")
        }
        if (parsed != null) parsedCache[id] = CachedList(target.lastModified(), parsed)
    }

    private fun updateSub(id: String, change: (AdSub) -> AdSub) {
        synchronized(subsLock) {
            val i = subs.indexOfFirst { it.id == id }
            if (i < 0) return
            subs[i] = change(subs[i])
            persistSubs()
        }
    }

    private fun persistSubs() {
        val snapshot = subs.toList()
        subsShadow = snapshot
        val arr = JSONArray()
        snapshot.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("enabled", it.enabled)
                    .put("updatedAt", it.updatedAt)
                    .put("ruleCount", it.ruleCount)
                    .put("error", it.error),
            )
        }
        prefs.edit().putString("subs", arr.toString()).apply()
    }

    private fun loadSubs(): List<AdSub> {
        val raw = prefs.getString("subs", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) null else AdSub(
                    id = id,
                    name = o.optString("name"),
                    url = o.optString("url"),
                    enabled = o.optBoolean("enabled", true),
                    updatedAt = o.optLong("updatedAt"),
                    ruleCount = o.optInt("ruleCount"),
                    error = o.optString("error"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persistAllow() {
        fastAllow = allowHosts.toSet()
        prefs.edit().putString("allow", JSONArray(allowHosts.toList()).toString()).apply()
    }

    private fun loadAllow(): List<String> {
        val raw = prefs.getString("allow", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun countCustomLines(): Int {
        val f = File(dir, CUSTOM_FILE)
        if (!f.exists()) return 0
        return runCatching {
            f.readLines().count { l ->
                val t = l.trim()
                t.isNotEmpty() && !t.startsWith("!") && !t.startsWith("[")
            }
        }.getOrDefault(0)
    }

    /** 旧版「标记为广告」存在 mafari_ad_rules 里，迁移成自定义规则后清掉。 */
    private fun migrateUserAdRules() {
        val legacy = appContext.getSharedPreferences("mafari_ad_rules", Context.MODE_PRIVATE)
        val all = legacy.all
        if (all.isEmpty()) return
        val lines = ArrayList<String>()
        for ((host, value) in all) {
            val h = host.trim().lowercase()
            if (h.isEmpty() || h.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') }) continue
            @Suppress("UNCHECKED_CAST")
            val selectors = (value as? Set<String>).orEmpty()
            for (s in selectors) if (AdParser.isSafeSelector(s.trim())) lines.add("$h##${s.trim()}")
        }
        if (lines.isNotEmpty()) {
            runCatching {
                val f = File(dir, CUSTOM_FILE)
                val current = if (f.exists()) f.readText() else ""
                val merged = (current.lines().map { it.trim() } + lines).filter { it.isNotEmpty() }.distinct()
                f.writeText(merged.joinToString("\n") + "\n")
            }.onFailure { return }
        }
        legacy.edit().clear().apply()
    }

    /** 内置基础规则：离线也能挡掉最常见的广告联盟，不依赖任何订阅。 */
    private const val BASE_RULES = """[Adblock Plus 2.0]
! Title: Mafari 内置基础规则
||doubleclick.net^
||googlesyndication.com^
||googleadservices.com^
||adservice.google.com^
||cpro.baidu.com^
||pos.baidu.com^
||eclick.baidu.com^
||cpro.baidustatic.com^
||dup.baidustatic.com^
##.adsbygoogle
##ins.adsbygoogle
##.google-auto-placed
##[id^="div-gpt-ad"]
##iframe[src*="doubleclick.net"]
##iframe[src*="googlesyndication.com"]
##iframe[src*="cpro.baidu.com"]
##a[href*="doubleclick.net"]
"""
}

/** 暴露给页面脚本的桥：只返回某个站点的元素隐藏 CSS，不泄露任何用户数据。指纹保护不经过这个桥。 */
class AdBlockBridge(private val cosmeticAllowed: () -> Boolean = { true }) {
    /** [cosmeticAllowed] 为 false（如无痕 / 轻应用里关闭了去广告）时不返回任何隐藏 CSS。 */
    @JavascriptInterface
    fun css(host: String?, topHost: String?): String =
        if (cosmeticAllowed()) AdBlock.cosmeticCss(host, topHost) else ""
}
