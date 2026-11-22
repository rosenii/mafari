package dev.dsh.miuixbrowser.engine.webview

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.looksLikeUrl
import dev.dsh.miuixbrowser.util.toUrl
import java.net.URI

/**
 * 预连接（WebView 内核）：在用户「很可能马上要访问某个站点」的时刻，先把 DNS / TCP / TLS（或 QUIC）握手做掉，
 * 之后真正加载时省掉握手耗时。用 androidx.webkit 的 `Profile#enqueuePreconnect`（1.17+）：
 * 请求会排队，等 WebView 启动完成后再发，不会为了预连接强制拉起 WebView；系统只支持旧的 `Profile#preconnect`
 * 时退回它（要求 WebView 已启动，所以只在已经有 WebView 标签时才调）。
 *
 * 触发时机（都只处理 http / https 的「完整站点」，不碰搜索词）：
 * - 地址栏进入编辑：预连接当前搜索引擎的 origin；
 * - 地址栏输入停顿 [INPUT_DEBOUNCE_MS] 后，输入已经是完整网址：预连接它；
 * - 手指按下主页快捷方式（比抬手触发加载早约 100 ms）。
 *
 * 开销与隐私：预连接只建连接、不发请求，连接约 30 秒后自动关闭；同一 origin [GATE_WINDOW_MS] 内只发一次。
 * 为避免把还没打完的域名（「github.c」）泄漏给 DNS，输入时只在域名带完整后缀时才触发；纯 IP / 局域网主机一律不预连接。
 * 设置里可整体关闭（[enabled]，默认开）。所有入口都必须在主线程调用。
 */
object WebViewSpeculative {

    const val INPUT_DEBOUNCE_MS = 450L
    const val GATE_WINDOW_MS = 25_000L
    private const val FILE = "mafari_webview_speculative"

    private var prefs: SharedPreferences? = null
    private val main = Handler(Looper.getMainLooper())
    private val gate = PreconnectGate()
    private var pendingInput: Runnable? = null

    /** 总开关：默认开。 */
    var enabled by mutableStateOf(true); private set

    /** 当前搜索引擎的模板（BrowserTabEffects 随设置同步进来），地址栏进入编辑时预连接它的 origin。 */
    @Volatile var searchTemplate: String = ""

    /** 是否已经创建过 WebView 标签。纯 Gecko 会话里不要去碰 WebView，免得白白拉起它。 */
    @Volatile private var webViewInUse = false

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("enabled", true)
    }

    fun updateEnabled(v: Boolean) {
        enabled = v
        prefs?.edit()?.putBoolean("enabled", v)?.apply()
        if (!v) { pendingInput?.let(main::removeCallbacks); pendingInput = null }
    }

    /** WebView 标签创建时调用一次即可。 */
    fun noteWebViewCreated() { webViewInUse = true }

    /* ---------------------------- 入口 ---------------------------- */

    /** 地址栏进入编辑。 */
    fun onAddressFocus() {
        val tpl = searchTemplate
        if (tpl.isEmpty()) return
        // 模板形如 https://www.baidu.com/s?wd=%s，取 origin 即可
        preconnect(tpl.replace("%s", ""))
    }

    /** 地址栏文字被用户改了：停顿后再判断。 */
    fun onAddressInput(text: String) {
        pendingInput?.let(main::removeCallbacks)
        pendingInput = null
        if (!enabled || !webViewInUse) return
        val origin = SpeculativePolicy.originForInput(text) ?: return
        val r = Runnable { pendingInput = null; preconnect(origin) }
        pendingInput = r
        main.postDelayed(r, INPUT_DEBOUNCE_MS)
    }

    /** 手指按下主页快捷方式 / 其它「马上要打开」的网址。 */
    fun onLikelyNavigation(url: String) = preconnect(url)

    /* ---------------------------- 实现 ---------------------------- */

    fun preconnect(urlOrOrigin: String) {
        if (!enabled || !webViewInUse) return
        val origin = SpeculativePolicy.originOf(urlOrOrigin) ?: return
        if (!gate.shouldFire(origin, System.currentTimeMillis())) return
        val ok = runCatching { fire(origin) }
            .onFailure { Diag.log("preconnect failed ${it.message}") }
            .getOrDefault(false)
        if (!ok) gate.forget(origin) // 没发出去：别占着去重窗口
        else if (WebViewDebug.pageLog && Diag.enabled) Diag.log("preconnect $origin")
    }

    @OptIn(Profile.ExperimentalPreconnect::class)
    private fun fire(origin: String): Boolean {
        if (!supported(WebViewFeature.MULTI_PROFILE)) return false
        val profile = ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME)
        return when {
            supported(WebViewFeature.ENQUEUE_PRECONNECT) -> { profile.enqueuePreconnect(origin); true }
            supported(WebViewFeature.PRECONNECT) -> { profile.preconnect(origin); true }
            else -> false
        }
    }

    /**
     * 调试页「预连接」一行的文字：分别说明能不能用、走哪条路径、卡在哪。
     * 实际能否预连接 = 多 Profile + (enqueuePreconnect 或 preconnect 二选一)；任何一项系统 WebView 没提供，[fire] 都会直接返回 false。
     */
    fun statusText(): String = when {
        !supported(WebViewFeature.MULTI_PROFILE) -> tr("不支持（系统 WebView 不支持多 Profile）")
        supported(WebViewFeature.ENQUEUE_PRECONNECT) -> tr("支持（排队模式 enqueuePreconnect）")
        supported(WebViewFeature.PRECONNECT) -> tr("支持（直连模式 preconnect；排队模式需要更新的系统 WebView）")
        else -> tr("不支持（当前系统 WebView 未提供 preconnect / enqueuePreconnect）")
    }

    private fun supported(feature: String) = runCatching { WebViewFeature.isFeatureSupported(feature) }.getOrDefault(false)

    /** 调试页展示用：这台设备上相关特性的支持情况。 */
    fun featureLines(): List<Pair<String, Boolean>> = listOf(
        "ENQUEUE_PRECONNECT" to supported(WebViewFeature.ENQUEUE_PRECONNECT),
        "PRECONNECT" to supported(WebViewFeature.PRECONNECT),
        "PROFILE_URL_PREFETCH" to supported(WebViewFeature.PROFILE_URL_PREFETCH),
        "WEBVIEW_NAVIGATE_DRAIN_PREFETCH" to supported(WebViewFeature.WEBVIEW_NAVIGATE_DRAIN_PREFETCH),
        "NAVIGATION_LISTENER_NAVIGATION_STATE" to supported(WebViewFeature.NAVIGATION_LISTENER_NAVIGATION_STATE),
    )
}

/** 不依赖 Android 的判断逻辑，单独拿出来方便单测。 */
object SpeculativePolicy {

    /** 取 http(s) 地址的 origin；不是 http(s) 返回 null。接受不带路径的 origin。 */
    fun originOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (!isPublicHost(host)) return null
        val port = uri.port
        return if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** 地址栏文字 → 值得预连接的 origin：必须像网址，且带完整的域名后缀；搜索词返回 null。 */
    fun originForInput(input: String): String? {
        val s = input.trim()
        if (s.length < 4 || !looksLikeUrl(s)) return null
        return originOf(toUrl(s))
    }

    /** 带点、后缀至少两个字母、不是 IP：排除 localhost / 局域网单标签主机 / IPv4 / IPv6。 */
    fun isPublicHost(host: String): Boolean {
        if (host.startsWith("[") || host.contains(':')) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return false
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it.isLetter() || it == '-' || it.isDigit() } && tld.any { it.isLetter() }
    }
}

/** 同一 origin 在窗口内只放行一次；记录数有上限。时间由调用方传入，方便测试。 */
class PreconnectGate(private val windowMs: Long = WebViewSpeculative.GATE_WINDOW_MS, private val max: Int = 32) {
    private val last = LinkedHashMap<String, Long>()

    @Synchronized fun shouldFire(origin: String, now: Long): Boolean {
        val prev = last[origin]
        if (prev != null && now - prev < windowMs) return false
        last.remove(origin)
        last[origin] = now
        while (last.size > max) last.remove(last.keys.first())
        return true
    }

    @Synchronized fun forget(origin: String) { last.remove(origin) }
}
