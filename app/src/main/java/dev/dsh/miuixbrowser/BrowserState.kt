package dev.dsh.miuixbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.setValue
import java.net.URLEncoder

private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")
/**
 * assets 里的注入脚本只读一次：applyPrivacy 在每次切换标签 / 改设置时都会跑，
 * 原来每次都在主线程重新打开 asset 并读全文。
 */
private object InjectAssets {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun read(context: Context, name: String): String? {
        cache[name]?.let { return it }
        val text = runCatching {
            context.applicationContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        cache[name] = text
        return text
    }
}

private val CHROME_MAJOR_REGEX = Regex("Chrome/(\\d+)")

private val DOMAIN_REGEX = Regex("^[A-Za-z0-9\\-._~%]+(\\.[A-Za-z0-9\\-._~%]+)+$")
private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
private val SINGLE_LABEL_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9\\-_]*$")

/** 拆出「主机」「端口」；不合法返回 null。支持 [IPv6]:端口。 */
private fun splitHostPort(authority: String): Pair<String, Int?>? {
    var host = authority.substringAfterLast('@') // 去掉 user:pass@
    var port: Int? = null
    if (host.startsWith("[")) {
        val end = host.indexOf(']')
        if (end < 0) return null
        val rest = host.substring(end + 1)
        if (rest.isNotEmpty()) {
            if (!rest.startsWith(":")) return null
            port = rest.drop(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        host = host.substring(0, end + 1)
        return host to port
    }
    val idx = host.lastIndexOf(':')
    if (idx >= 0) {
        val p = host.substring(idx + 1)
        if (p.isNotEmpty()) port = p.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        host = host.substring(0, idx)
    }
    return host to port
}

private fun isIpv4(host: String): Boolean {
    val m = IPV4_REGEX.matchEntire(host) ?: return false
    return m.groupValues.drop(1).all { it.toInt() in 0..255 }
}

/** 没写协议时该补 http 还是 https：IP / localhost / .local / 单段主机名（局域网服务）走 http，其余走 https */
private fun defaultSchemeFor(host: String, port: Int?): String {
    val h = host.lowercase()
    val local = h == "localhost" || h.startsWith("[") || isIpv4(h) || h.endsWith(".local") || !h.contains('.')
    return if (local && port != 443) "http" else "https"
}

/** 判断输入是网址还是搜索词。 */
fun looksLikeUrl(input: String): Boolean {
    val s = input.trim()
    if (s.isEmpty() || s.any { it.isWhitespace() }) return false
    if (s.startsWith("about:", ignoreCase = true)) return true
    if (SCHEME_REGEX.containsMatchIn(s)) return true
    // 无协议：主机[:端口][/路径?查询#片段]
    val authority = s.substringBefore('/').substringBefore('?').substringBefore('#')
    val (host, port) = splitHostPort(authority) ?: return false
    if (host.isEmpty()) return false
    return when {
        host.equals("localhost", ignoreCase = true) -> true
        host.startsWith("[") -> true                       // IPv6 字面量
        isIpv4(host) -> true                               // 192.168.1.1 / 192.168.1.1:8080
        DOMAIN_REGEX.matches(host) -> true                 // example.com / example.com:8443/path
        port != null && SINGLE_LABEL_REGEX.matches(host) -> true // nas:5000 这类局域网主机名
        else -> false
    }
}

/** 把地址栏输入变成可加载的 URL，不像网址就走搜索。 */
fun toUrl(input: String, searchTemplate: String = "https://www.baidu.com/s?wd=%s"): String {
    val s = input.trim()
    if (!looksLikeUrl(s)) return searchTemplate.replace("%s", URLEncoder.encode(s, "UTF-8"))
    if (s.startsWith("about:", ignoreCase = true) || SCHEME_REGEX.containsMatchIn(s)) return s
    val authority = s.substringBefore('/').substringBefore('?').substringBefore('#')
    val (host, port) = splitHostPort(authority) ?: return "https://$s"
    return "${defaultSchemeFor(host, port)}://$s"
}

fun hostOf(url: String): String {
    val trimmed = url.trim()
    if (trimmed.startsWith("data:", ignoreCase = true)) return "内嵌图片"
    val host = runCatching { Uri.parse(trimmed).host }.getOrNull()
    return if (!host.isNullOrBlank()) host else trimmed.take(48)
}

private val SECOND_LEVEL_LABELS = setOf("com", "net", "org", "gov", "edu", "co", "ac")
private val PRIVATE_SUFFIXES = setOf("github.io", "vercel.app", "pages.dev", "netlify.app", "blogspot.com", "workers.dev")

/** 取站点标识（近似可注册域名）：m.example.com / www.example.com → example.com，a.co.uk → a.co.uk */
private fun siteOf(host: String): String {
    val h = host.lowercase().trim('.')
    if (h.isEmpty() || h.contains(':') || h.all { it.isDigit() || it == '.' }) return h
    val parts = h.split('.')
    if (parts.size <= 2) return h
    val tail2 = parts.takeLast(2).joinToString(".")
    val n = if ((parts.last().length == 2 && parts[parts.size - 2] in SECOND_LEVEL_LABELS) || tail2 in PRIVATE_SUFFIXES) 3 else 2
    return parts.takeLast(n).joinToString(".")
}

/** 两个 host 是否同站 */
fun isSameSite(a: String, b: String): Boolean = siteOf(a) == siteOf(b)

/** 脚本菜单命令（GM_registerMenuCommand） */
data class ScriptMenuCommand(val id: String, val title: String, val script: String)

/** 链接是否指向 .user.js（忽略查询串与片段） */
fun isUserScriptUrl(url: String): Boolean {
    val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
    return path.endsWith(".user.js", ignoreCase = true)
}

/** 浏览器内核。标签页创建时确定，之后不变。 */
enum class EngineKind { WEBVIEW, GECKO }

/**
 * 一个标签页：持有自己的内核（WebView 或 GeckoView）和页面状态。
 * 标题 / 地址 / 进度 / 前进后退等状态全部是与内核无关的 Compose 状态，两种内核共用同一套界面。
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserTab(
    internal val appContext: Context,
    /** 本标签使用的内核：创建时确定，之后不变 */
    val engineKind: EngineKind = EngineKind.WEBVIEW,
    /** 无痕标签：Gecko 的私有模式必须在会话创建时指定 */
    private val startIncognito: Boolean = false,
    /** 拓展 tabs.create 时已交给 Gecko 的 session（仅 Gecko 内核）；普通标签为 null，由引擎自己创建 */
    private val adoptedGeckoSession: org.mozilla.geckoview.GeckoSession? = null,
    /**
     * 延迟创建 Gecko 引擎（仅 Gecko 内核）：true 时构造时不建 GeckoSession / GeckoView，
     * 第一次真正需要时（成为当前标签、加载页面）才由 [ensureGecko] 创建；用于重启后恢复出来的后台标签。
     * 拓展 session 必须由 Gecko 先建好，所以不能延迟。
     */
    lazyEngine: Boolean = false,
) {

    /** 每个标签的唯一 id，Compose 用它做 key。 */
    val id: Int = nextId()

    var title by mutableStateOf("新标签页")
    var url by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var errorText by mutableStateOf<String?>(null)
    /** 标签卡片用的页面缩略图（页面加载完成后截取） */
    var thumbnail by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set

    /** tabs 表主键（见 [TabDb]）：新标签随机生成，从库里恢复出来的标签沿用库里的值 */
    var dbKey: Long = TabDb.newKey()
        internal set

    /** 最近一次被切到前台的时间（毫秒，墙钟）：恢复「最近使用」顺序 / 重启后回到最后用的标签 */
    var lastUsedAt = 0L
        internal set

    /** 正在从库里读缩略图（防止卡片重组时重复触发） */
    internal var thumbLoading = false
    /** 是否是从主页打开的第一个页面（用于「返回到主页」） */
    var fromHome by mutableStateOf(false)
        private set

    /** 打开本标签的标签 id（-1 = 无）：关闭本标签时优先回到它，形成层级回退 */
    var openerId: Int = -1

    /**
     * 本标签是由另一个标签里的链接（点击 / 长按「新标签页打开」/ window.open）打开的。
     * 这种标签没有「主页」可回：在它的第一页点返回，直接关闭它并回到来源标签。
     */
    var openedFromLink = false

    /** 请求关闭自己（由 [BrowserState] 接线），用于 [openedFromLink] 标签在第一页返回 */
    var onRequestClose: (() -> Unit)? = null

    /** 从主页进入页面时 WebView 历史的当前下标（-1 = 历史为空）：回退到这一层就回主页，不再穿透到主页之前的旧页面 */
    private var homeAnchor = -1

    /** 标签正显示主页：WebView 里的页面（若有）被盖住，页面侧回调不得再改写 url，否则主页会被页面「顶」出来 */
    private var parked = false

    var closed = false
    private var destroyed = false

    /** 当前标签是否「请求桌面网站」 */
    var desktopMode by mutableStateOf(false)
        private set

    /** 无痕浏览标签 */
    var incognito by mutableStateOf(false)
        private set

    fun applyIncognito(value: Boolean) {
        if (incognito == value) return
        incognito = value
        syncUserScripts()
        // Cookie 开关是进程级的，不能在这里改；改为在该标签成为当前标签时由 applyPrivacy 设置
    }

    private val defaultUserAgent: String by lazy { webViewOrNull?.settings?.userAgentString.orEmpty() }

    /** 设置里的自定义 UA（空串 = 跟随系统） */
    var customUserAgent: String = ""

    /** 长按链接时由界面挂上回调。 */
    var onLongPressLink: ((String) -> Unit)? = null

    /** 长按图片 */
    var onLongPressImage: ((String) -> Unit)? = null

    /** 长按普通元素：由页面内 JS 判定为广告浮层后回调 */
    var onElementLongPress: ((ElementHit) -> Unit)? = null

    /** 用户脚本扩展：通过 document-start 注入，由运行时按各框架 URL 与 run-at 自行调度 */
    var userScripts: List<UserScript> = emptyList()
        set(value) {
            field = value
            syncUserScripts()
        }

    /** 网页增强总开关：关闭后不再注入任何脚本 / 长按逻辑，用于排查（去广告有自己的开关，见 AdBlock） */
    var pageEnhancements: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            syncUserScripts()
        }

    /** 脚本一键启停（菜单里的开关）：关闭后不再注入任何油猴脚本，需刷新页面生效 */
    var scriptsEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            syncUserScripts()
        }

    /** 启用范围：脚本 / 去广告是否在无痕标签中生效（由设置注入） */
    var scriptsInIncognito: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            syncUserScripts()
        }

    var adblockInIncognito: Boolean = true

    /** 当前标签是否应运行油猴脚本：网页增强、脚本总开关、无痕范围三者都允许 */
    private fun scriptsActive(): Boolean =
        pageEnhancements && scriptsEnabled && (!incognito || scriptsInIncognito)

    /** 搜索模板，由设置注入 */
    var searchTemplate: String = "https://www.baidu.com/s?wd=%s"

    /** 下拉进度 0..1，用于顶部动画 */
    var pullProgress by mutableFloatStateOf(0f)
        private set

    /** 下载请求回调（交给界面弹确认框）。blob/data 由 extractBlob 抽取后走 onBlobReady。 */
    var onDownload: ((url: String, userAgent: String?, disposition: String?, mime: String?) -> Unit)? = null

    /** 页面要求选文件 / 拍照 / 录像：由界面拉起系统选择器或相机 */
    var onShowFileChooser: (
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    ) -> Unit = { _, callback -> callback.onReceiveValue(null) }

    /** HTML5 视频全屏：把 WebView 给的 View 挂到全屏容器 */
    var onShowCustomView: ((view: View, callback: WebChromeClient.CustomViewCallback) -> Unit)? = null

    /** 退出全屏（视频结束 / 返回键） */
    var onHideCustomView: (() -> Unit)? = null

    /** blob / data URL 抽取完成：dataUri 或 tempPath 二选一（大文件走 tempPath） */
    var onBlobReady: ((dataUrl: String?, fileName: String, mime: String?, tempPath: String?) -> Unit)? = null

    /** 大 blob 开始读取（界面可提示“正在读取”） */
    var onBlobStart: ((fileName: String) -> Unit)? = null

    /** blob 抽取失败 */
    var onBlobError: ((message: String) -> Unit)? = null

    /** 下拉刷新回调 */
    var onPullRefresh: (() -> Unit)? = null

    /** 遇到 .user.js 链接 */
    var onUserScriptLink: ((String) -> Unit)? = null

    /** 遇到拓展安装包链接（.xpi）：Gecko 内核下由界面弹出安装确认 */
    var onExtensionLink: ((String) -> Unit)? = null

    /** Gecko 内核遇到 .user.js 链接：与 WebView 一致，交给界面弹出「导入脚本」 */
    internal fun geckoUserScriptLink(link: String) {
        if (!isGecko) return
        onUserScriptLink?.invoke(link)
    }

    internal fun geckoExtensionLink(link: String) {
        // 拓展只属于 Gecko：WebView 标签不识别拓展链接
        if (!isGecko) return
        onExtensionLink?.invoke(link)
    }

    /** 网页重定向 / window.open 被允许后，在新标签页打开（由 BrowserState 注入） */
    var onOpenInNewTab: ((String) -> Unit)? = null

    /** 同站 / 用户点击的新窗口链接是否新开标签页（设置里的开关） */
    @Volatile
    var sameSiteNewTab = false

    /** 记录历史 */
    var onVisited: ((url: String, title: String) -> Unit)? = null

    /** 只有 WebView 内核的标签才有 WebView；Gecko 标签不创建（省内存） */
    private val webViewOrNull: WebView? = if (engineKind == EngineKind.WEBVIEW) WebView(appContext) else null

    /** WebView 内核专用 API 的入口。Gecko 标签访问它会抛异常，界面层应改用 [contentView] 等与内核无关的成员 */
    val webView: WebView
        get() = webViewOrNull ?: error("Gecko 标签没有 WebView，请使用与内核无关的接口")

    /** 当前存活的 Gecko 引擎：延迟创建，也会被 [discardEngine] 回收，所以可能为 null。读它不会触发创建，创建走 [ensureGecko] */
    private var geckoEngine: GeckoTabEngine? = null

    private val gecko: GeckoTabEngine? get() = geckoEngine

    init {
        // 非延迟标签保持原行为：构造时就建引擎，建不出来让 BrowserState.createTab 退回 WebView
        if (engineKind == EngineKind.GECKO && !lazyEngine) {
            geckoEngine = GeckoTabEngine(appContext, startIncognito, this, adoptedGeckoSession)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 取 Gecko 引擎，没有就现在创建（主线程）。引擎建出来后补上会话级设置（UA / 桌面模式）。
     * 创建失败（Gecko 启动异常）返回 null 并在标签上报错，不会抛出。
     */
    internal fun ensureGecko(): GeckoTabEngine? {
        geckoEngine?.let { return it }
        if (engineKind != EngineKind.GECKO || closed || destroyed) return null
        val g = runCatching { GeckoTabEngine(appContext, startIncognito, this, adoptedGeckoSession) }
            .onFailure { Diag.log("gecko: lazy engine create failed: ${it.message}") }
            .getOrNull()
        if (g == null) {
            errorText = "Gecko 内核启动失败"
            return null
        }
        geckoEngine = g
        applyUserAgent()
        return g
    }

    /** Gecko 标签没能建出引擎时给界面一个占位 View，避免 [contentView] 抛异常 */
    private val placeholderView: View by lazy { View(appContext) }

    /** 拓展 tabs.create 建出的标签：地址栏先显示目标地址，Gecko 没有自己加载时由引擎兜底加载一次 */
    internal fun adoptExtensionUrl(target: String) {
        parked = false
        if (target.isNotBlank() && target != "about:blank") url = target
        gecko?.ensureLoaded(target)
    }

    /** Gecko 标签的 session（非 Gecko 内核为 null）：拓展弹出页按它路由 */
    val geckoSession: org.mozilla.geckoview.GeckoSession? get() = gecko?.session

    /**
     * 挂到界面里的内容视图：WebView 或 GeckoView。Gecko 标签在这里按需创建引擎，
     * 所以只应该对「要显示的」标签读它；后台标签要做的事（关闭 / 延迟投递）不要碰它，见 [mainHandler]。
     */
    val contentView: View
        get() = if (isGecko) (ensureGecko()?.view ?: placeholderView) else webView

    /** 本标签用的是 Gecko 内核（和引擎此刻是否已创建无关） */
    val isGecko: Boolean get() = engineKind == EngineKind.GECKO

    /** 指纹保护：按 origin 注册 document-start 脚本（见 FingerprintInjector / docs/FINGERPRINT.md）。 */
    private val fpInjector: FingerprintInjector? = webViewOrNull?.let { FingerprintInjector(it) }

    private val elementBridge = object {
        @android.webkit.JavascriptInterface
        fun onElement(selector: String, tag: String, imageUrl: String) {
            webView.post {
                onElementLongPress?.invoke(ElementHit(tag, imageUrl, selector))
            }
        }
    }

    /**
     * blob 分片接收：evaluateJavascript 返回值有大小上限，大文件必须走 JS→桥接分片。
     *
     * 每次 [extractBlob] 在原生侧创建一个独立会话（一次性 token + 独立缓存文件）：
     * - 桥接方法全部要求 token，网页自己调用 MafariBlob 拿不到 token，无法伪造下载或写缓存文件；
     * - 多个 blob 并发抽取互不干扰；
     * - 写入出错会记入会话，end 时校验总字节数，残缺文件不会被当成成功。
     */
    private class BlobSession(
        val file: java.io.File,
        val stream: java.io.FileOutputStream,
        var name: String,
        var mime: String?,
    ) {
        /** 文件名来自页面（download 属性 / File.name）：保存时不得再改名 */
        var keepName = false
        var written = 0L
        var failed = false
        var finished = false
    }

    private val blobSessions = java.util.concurrent.ConcurrentHashMap<String, BlobSession>()

    private fun discardBlobSession(token: String) {
        val session = blobSessions.remove(token) ?: return
        synchronized(session) {
            runCatching { session.stream.close() }
            runCatching { session.file.delete() }
        }
    }

    private fun discardAllBlobSessions() {
        blobSessions.keys.toList().forEach { discardBlobSession(it) }
    }

    private fun reportBlobError(message: String) {
        webView.post { onBlobError?.invoke(message) }
    }

    private val blobBridge = object {
        /** 返回 false 表示原生侧已拒绝（token 无效 / 空间不足），JS 端应直接停止。 */
        @android.webkit.JavascriptInterface
        fun begin(token: String, mime: String, size: Double, pageName: String): Boolean {
            val session = blobSessions[token] ?: return false
            if (size > 0 && size > appContext.cacheDir.usableSpace - MIN_FREE_BYTES) {
                discardBlobSession(token)
                reportBlobError("存储空间不足，无法读取该文件")
                return false
            }
            if (mime.isNotBlank()) session.mime = mime
            if (pageName.isNotBlank()) {
                session.name = Downloader.originalName(pageName)
                session.keepName = true
            }
            if (size >= LARGE_BLOB_NOTICE_BYTES) {
                val label = session.name
                webView.post { onBlobStart?.invoke(label) }
            }
            return true
        }

        @android.webkit.JavascriptInterface
        fun appendChunk(token: String, b64: String) {
            val session = blobSessions[token] ?: return
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                synchronized(session) {
                    if (session.finished || session.failed) return
                    session.stream.write(bytes)
                    session.written += bytes.size
                }
            } catch (e: Exception) {
                session.failed = true
                Diag.log("blob append failed: ${e.message}")
            }
        }

        @android.webkit.JavascriptInterface
        fun end(token: String, total: Double) {
            val session = blobSessions.remove(token) ?: return
            val ok: Boolean
            synchronized(session) {
                session.finished = true
                runCatching { session.stream.flush(); session.stream.close() }
                ok = !session.failed && session.written > 0L &&
                    (total < 0 || session.written == total.toLong()) &&
                    session.file.exists() && session.file.length() == session.written
            }
            if (!ok) {
                runCatching { session.file.delete() }
                reportBlobError("文件读取不完整或写入失败")
                return
            }
            val name = if (session.keepName) session.name else Downloader.withExtension(session.name, session.mime)
            val mime = session.mime
            val path = session.file.absolutePath
            webView.post { onBlobReady?.invoke(null, name, mime, path) }
        }

        @android.webkit.JavascriptInterface
        fun fail(token: String, msg: String) {
            if (!blobSessions.containsKey(token)) return
            discardBlobSession(token)
            reportBlobError("无法读取 blob：$msg")
        }
    }

    /** 打开新标签页（GM_openInTab 用）：桥生成的 [id]、地址、是否切到前台 */
    var onGmOpenTab: ((id: String, url: String, active: Boolean) -> Unit)? = null

    /** 关闭 [onGmOpenTab] 打开的标签（GM_openInTab 返回对象的 close()） */
    var onGmCloseTab: ((id: String) -> Unit)? = null

    /** GM_download：带结果回调的下载请求（走与普通下载相同的确认弹窗） */
    var onGmDownload: ((url: String, name: String, onResult: ((Boolean, String?) -> Unit)?) -> Unit)? = null

    private val scriptHandlers = mutableListOf<androidx.webkit.ScriptHandler>()
    private var installedScriptsSignature = ""
    private var prefetchingScripts = false

    /** 文件选择器：WebView 持有的回调，界面拉起选择器后必须回传（null = 取消） */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /** HTML5 视频全屏中的 View 与回调 */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** 界面取消文件选择（返回键 / 关闭弹层） */
    fun cancelFileChooser() {
        val cb = pendingFileCallback
        pendingFileCallback = null
        runCatching { cb?.onReceiveValue(null) }
    }


    /** 退出视频全屏（返回键调用） */
    fun exitCustomView() {
        val cb = customViewCallback
        if (cb == null && customView == null) return
        customView = null
        customViewCallback = null
        restoreOrientation()
        onHideCustomView?.invoke()
        runCatching { cb?.onCustomViewHidden() }
    }

    /** 全屏视频：强制横屏 */
    private fun lockLandscape() {
        val activity = resolveActivity() ?: return
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** 退出全屏：恢复系统方向 */
    private fun restoreOrientation() {
        val activity = resolveActivity() ?: return
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** LocalContext 可能是 ContextThemeWrapper，逐层解开找 Activity */
    private fun resolveActivity(): android.app.Activity? {
        var c: Context? = appContext
        while (c != null) {
            if (c is android.app.Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * 把 blob 分片写入缓存文件（不经 evaluateJavascript 返回大字符串）。
     * 取 Blob 顺序：createObjectURL 缓存（含同源 iframe）→ XHR → fetch。
     * 完成后回调 onBlobReady(tempPath=...)。
     */
    fun extractBlob(
        url: String,
        userAgent: String? = null,
        disposition: String? = null,
        mime: String? = null,
    ) {
        val fileName = Downloader.fileNameFrom(url, disposition)
        val preferredMime = mime ?: "application/octet-stream"
        val token = java.util.UUID.randomUUID().toString()
        val file = java.io.File(appContext.cacheDir, "mafari_blob_${token}.part")
        val stream = runCatching { java.io.FileOutputStream(file) }.getOrElse {
            reportBlobError("无法创建缓存文件")
            return
        }
        blobSessions[token] = BlobSession(file, stream, fileName, mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" })
        // 分片 256KB 二进制 → base64 约 350KB，避开 JS 桥接/字符串上限
        val js = """
            (function(){
              var B = window.MafariBlob;
              var token = ${org.json.JSONObject.quote(token)};
              var url = ${org.json.JSONObject.quote(url)};
              var preferredMime = ${org.json.JSONObject.quote(preferredMime)};
              var CHUNK = 256 * 1024;
              if (!B) return;

              function findCached(u) {
                try {
                  if (window.__mafariBlobCache && window.__mafariBlobCache[u]) {
                    return window.__mafariBlobCache[u];
                  }
                } catch (e) {}
                try {
                  var list = document.querySelectorAll('iframe');
                  for (var i = 0; i < list.length; i++) {
                    try {
                      var w = list[i].contentWindow;
                      if (w && w.__mafariBlobCache && w.__mafariBlobCache[u]) {
                        return w.__mafariBlobCache[u];
                      }
                    } catch (e) {}
                  }
                } catch (e) {}
                return null;
              }

              function viaXhr(u) {
                return new Promise(function(resolve, reject) {
                  try {
                    var x = new XMLHttpRequest();
                    x.open('GET', u, true);
                    x.responseType = 'blob';
                    x.onload = function() {
                      if (x.response && x.response.size >= 0) resolve(x.response);
                      else reject(new Error('xhr empty'));
                    };
                    x.onerror = function() { reject(new Error('xhr error')); };
                    x.send();
                  } catch (e) { reject(e); }
                });
              }

              function viaFetch(u) {
                return fetch(u).then(function(r){ return r.blob(); });
              }

              function getBlob(u) {
                var c = findCached(u);
                if (c) return Promise.resolve(c);
                return viaXhr(u).catch(function(){ return viaFetch(u); });
              }

              function findName(u, b) {
                try {
                  if (window.__mafariBlobNames && window.__mafariBlobNames[u]) return window.__mafariBlobNames[u];
                } catch (e) {}
                try {
                  var list = document.querySelectorAll('iframe');
                  for (var i = 0; i < list.length; i++) {
                    try {
                      var w = list[i].contentWindow;
                      if (w && w.__mafariBlobNames && w.__mafariBlobNames[u]) return w.__mafariBlobNames[u];
                    } catch (e) {}
                  }
                } catch (e) {}
                try { if (b && b.name) return String(b.name); } catch (e) {}
                return '';
              }

              getBlob(url).then(function(b){
                if (!b || !b.size) throw new Error('empty blob');
                if (!B.begin(token, b.type || preferredMime, b.size, findName(url, b))) return;
                var i = 0;
                function next(){
                  if (i >= b.size) { B.end(token, b.size); return; }
                  var end = Math.min(i + CHUNK, b.size);
                  var slice = b.slice(i, end);
                  i = end;
                  return slice.arrayBuffer().then(function(buf){
                    var bytes = new Uint8Array(buf);
                    var bin = '';
                    var BL = 0x8000;
                    for (var j = 0; j < bytes.length; j += BL) {
                      bin += String.fromCharCode.apply(null, bytes.subarray(j, j + BL));
                    }
                    B.appendChunk(token, btoa(bin));
                    return next();
                  });
                }
                return next();
              }).catch(function(e){ B.fail(token, String(e && e.message || e)); });
            })()
        """.trimIndent()
        // 结果由 blobBridge 回调，这里不解析返回值
        evaluateJs(js, null)
    }

    /**
     * onPageStarted 只在导航开始时给一次（重定向前）的地址：http→https、短链跳转、站内 pushState 之后
     * pageHost 会是旧站点，导致 $third-party / $domain= / 站点白名单判错。这里用最终地址刷新。
     */
    private fun updatePageHost(pageUrl: String?) {
        val h = runCatching { Uri.parse(pageUrl).host }.getOrNull()
        if (!h.isNullOrEmpty()) pageHost = h
    }

    /** 去广告元素隐藏脚本是否已作为 document-start 脚本装上（没装上时由页面加载回调兜底注入） */
    private var adblockDocStart = false

    private fun initWebView() {
        // 原生滚动回调（API 23+）：底栏收起 / 展开由滚动事件唤醒，不再靠定时轮询
        webView.setOnScrollChangeListener { _, _, _, _, _ -> scrollSignal.trySend(Unit) }
        runCatching { webView.addJavascriptInterface(AdBlockBridge { !incognito || adblockInIncognito }, "MafariAdblock") }
        runCatching { webView.addJavascriptInterface(blobBridge, "MafariBlob") }
        // 仅降级路径：WebView 不支持 DOCUMENT_START_SCRIPT 时才安装无参的指纹桥；主路径不向页面暴露任何指纹接口
        val fp = fpInjector ?: return
        if (!fp.docStartSupported) {
            runCatching { webView.addJavascriptInterface(FpFallbackBridge(fp), "MafariFp") }
        }
        // 落在未预注册 origin 时的兜底：重载走带请求头的 loadUrl；STOP 策略在页面上给出提示
        fp.loader = { target -> loadUrl(target) }
        fp.onUnprotectedBlocked = { target ->
            loading = false
            errorText = "指纹保护：${runCatching { Uri.parse(target).host }.getOrNull().orEmpty()} 未能在加载前注册保护，已停止加载"
        }
        // 低频可信通道（豁免请求、统计上报）：来源由 WebView 校验，与指纹配置无关；不支持时静默跳过
        TrustedChannel.install(webView, object : TrustedChannel.Host {
            override fun onFingerprintExemptRequested(origin: WebOrigin) = handleFpExemptRequest(origin)
        })
        cleanStaleBlobFiles(appContext)
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // 社区通用：缓存 createObjectURL 的 Blob，extractBlob 直接读，避免 fetch blob: 失败
                runCatching {
                    val cacheJs = appContext.assets.open("mafari_blob_cache.js")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, cacheJs, setOf("*"))
                }.onFailure { Diag.log("blob cache install failed: ${it.message}") }
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, AdBlock.COSMETIC_JS, setOf("*"))
                // 指纹保护不在这里注册：它按 origin 注册（fpInjector?.prepare），配置内联在各 origin 自己的脚本里
                adblockDocStart = true
            }
        }.onFailure { Diag.log("adblock: document-start install failed ${it.message}") }
        runCatching {
            webView.addJavascriptInterface(
                GmBridge(
                    appContext,
                    { webView },
                    { id, target, active -> onGmOpenTab?.invoke(id, target, active) },
                    { link, name, onResult ->
                        val gm = onGmDownload
                        if (gm != null) {
                            gm(link, name, onResult)
                        } else {
                            onDownload?.invoke(
                                link,
                                null,
                                if (name.isBlank()) null else "attachment; filename=\"$name\"",
                                null,
                            )
                            onResult?.invoke(false, "unsupported")
                        }
                    },
                    { id -> onGmCloseTab?.invoke(id) },
                ),
                "MafariGM",
            )
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString.replace("; wv", "")
        }
        // 先固定系统默认 UA（之后 applyUserAgent 会覆盖 settings.userAgentString）
        runCatching { defaultUserAgent.length }
        // 打开 WebView 的 WebAuthn（Passkey）支持。
        // 注意：之前这里传的是 SUPPORT_NONE，等于主动关闭了 Passkey。
        // 浏览器需要对任意网站的 origin 生效 → 优先 FOR_BROWSER；
        // 若 WebView / 系统不接受（读回仍是 NONE），退回 FOR_APP。
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_AUTHENTICATION)) {
                val settings = webView.settings
                androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(
                    settings,
                    androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER,
                )
                var mode = androidx.webkit.WebSettingsCompat.getWebAuthenticationSupport(settings)
                if (mode == androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE) {
                    androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(
                        settings,
                        androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
                    )
                    mode = androidx.webkit.WebSettingsCompat.getWebAuthenticationSupport(settings)
                }
                Diag.log("passkey: WebAuthn support mode=$mode")
            } else {
                Diag.log("passkey: WEB_AUTHENTICATION not supported by this WebView")
            }
        }.onFailure { Diag.log("passkey: enable failed ${it.message}") }
        // 默认浅色底；深浅色由 applyDisplayTheme 按应用主题覆盖
        webView.setBackgroundColor(0xFFFFFFFF.toInt())
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                if (!parked) pageUrl?.let { url = it }
                pageHost = runCatching { Uri.parse(pageUrl).host }.getOrNull().orEmpty()
                loading = true
                errorText = null
                syncNav()
                syncUserScripts()
                if (!adblockDocStart) view.evaluateJavascript(AdBlock.COSMETIC_JS, null)
                // 主路径：补注册（只对之后的加载生效）；降级路径：补跑脚本
                fpInjector?.onPageStarted(pageUrl)
            }

            /** HTTP 代理认证：挑战主机等于代理主机时自动提交设置里的账号密码 */
            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: android.webkit.HttpAuthHandler?,
                host: String?,
                realm: String?,
            ) {
                if (handler == null) return
                if (host != null && WebViewProxy.shouldAutoAuth(host)) {
                    handler.proceed(WebViewProxy.authUser, WebViewProxy.authPass)
                } else {
                    handler.cancel()
                }
            }

            override fun onPageFinished(view: WebView, pageUrl: String?) {
                if (!parked) pageUrl?.let { url = it }
                loading = false
                progress = 1f
                syncNav()
                val visited = pageUrl ?: url
                if (!parked && !visited.isNullOrEmpty()) onVisited?.invoke(visited, title)

                injectMissingUserScripts(view)
                if (!adblockDocStart) view.evaluateJavascript(AdBlock.COSMETIC_JS, null)
                // 重定向 / 跳转后以最终地址为准更新页面主机名，去广告的第三方判定、白名单、$domain 都依赖它
                updatePageHost(pageUrl)
                installElementInspector(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, pageUrl: String?, isReload: Boolean) {
                // 服务端重定向的最终落点只有这里看得到：未预注册就按策略兜底
                fpInjector?.onCommitted(pageUrl)
                if (!parked) pageUrl?.let { url = it }
                updatePageHost(pageUrl)
                syncNav()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (incognito && !adblockInIncognito) null else AdBlock.intercept(request, pageHost, blockTrackers)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) errorText = error.description?.toString()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (isUserScriptUrl(target)) {
                    onUserScriptLink?.invoke(target)
                    return true
                }
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https") {
                    val proceed = { openRedirectInNewTab(target) }
                    if (interceptRedirect(request, external = false, onAllowed = proceed)) return true
                }
                // 主框架导航放行前，先为目标 origin 注册指纹保护脚本（注入只作用于注册之后才开始加载的文档）
                if (request.isForMainFrame && (scheme == "http" || scheme == "https")) fpInjector?.prepare(target)
                // DNT / Sec-GPC：点击链接产生的主框架 GET 导航也带上请求头
                if (dntEnabled && (scheme == "http" || scheme == "https") && request.isForMainFrame &&
                    request.method == "GET" && !request.isRedirect &&
                    request.requestHeaders["DNT"] == null && target != lastDntUrl
                ) {
                    lastDntUrl = target
                    view.loadUrl(target, extraRequestHeaders())
                    return true
                }
                return when (scheme) {
                    "http", "https", "file", "about", "data", "javascript" -> false
                    else -> {
                        // 支付宝 / 微信 / 淘宝等外部协议交给系统处理（无用户手势的自动唤起受「网页重定向」权限控制）
                        val open = { launchExternalApp(request.url) }
                        if (!interceptRedirect(request, external = true, onAllowed = open)) open()
                        // 无论放行还是拒绝，当前页面都原地不动（拒绝后停留在网页）
                        true
                    }
                }
            }
        }

        webView.addJavascriptInterface(elementBridge, "MafariElement")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress / 100f
                loading = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                if (!pageTitle.isNullOrBlank()) title = pageTitle
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                // 诊断：确认 WebView 是否真的把 showSaveFilePicker 交给了 onShowFileChooser（mode=3 为保存）
                Diag.log("onShowFileChooser mode=${fileChooserParams.mode} hint=${fileChooserParams.filenameHint} accept=${fileChooserParams.acceptTypes?.joinToString()}")
                if (fileChooserParams.mode == FileChooserParams.MODE_SAVE) {
                    runCatching { android.widget.Toast.makeText(appContext, "收到保存请求: ${fileChooserParams.filenameHint}", android.widget.Toast.LENGTH_SHORT).show() }
                }
                // WebView 可能在上一次未回调时再次请求，先取消上一次
                cancelFileChooser()
                pendingFileCallback = filePathCallback
                this@BrowserTab.onShowFileChooser.invoke(fileChooserParams, filePathCallback)
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                lockLandscape()
                this@BrowserTab.onShowCustomView?.invoke(view, callback)
            }

            override fun onHideCustomView() {
                val cb = customViewCallback
                customView = null
                customViewCallback = null
                restoreOrientation()
                this@BrowserTab.onHideCustomView?.invoke()
                // 界面摘掉 View 后必须通知 WebView
                cb?.onCustomViewHidden()
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean = handleCreateWindow(view, isUserGesture, resultMsg)

            override fun onPermissionRequest(request: PermissionRequest) {
                webView.post { handleWebPermission(request) }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                webView.post {
                    decide(
                        locationMode,
                        Uri.parse(origin).host ?: origin,
                        "获取你的位置",
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                        anyOk = true,
                    ) { allow -> callback.invoke(origin, allow, false) }
                }
            }
        }

        webView.setDownloadListener { link, userAgent, contentDisposition, mimeType, _ ->
            // 一些站点把 .user.js 以附件形式返回，会走到下载回调；这里改成「导入脚本」
            when {
                isUserScriptUrl(link) -> onUserScriptLink?.invoke(link)
                // blob：HTTP 下载器拉不到，走页面内抽取
                link.startsWith("blob:", ignoreCase = true) ->
                    extractBlob(link, userAgent, contentDisposition, mimeType)
                // data URL 附件：直接交给界面落盘
                link.startsWith("data:", ignoreCase = true) -> {
                    val dataMime = link.substringAfter("data:").substringBefore(',').substringBefore(';')
                        .ifBlank { null }
                    val name = Downloader.withExtension(
                        Downloader.fileNameFrom(link, contentDisposition),
                        mimeType?.takeIf { it.isNotBlank() } ?: dataMime,
                    )
                    onBlobReady?.invoke(link, name, mimeType ?: dataMime, null)
                }
                else -> onDownload?.invoke(link, userAgent, contentDisposition, mimeType)
            }
        }

        // 约 2.5cm 的拉动距离，避免误触
        val pullThresholdPx = webView.context.resources.displayMetrics.xdpi / 2.54f * 4f
        var touchDownY = 0f
        var pulled = 0f
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownY = event.y
                    lastTouchX = event.x
                    lastTouchY = event.y
                    pulled = 0f
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (url.isNotEmpty() && webView.scrollY <= 0 && event.y > touchDownY) {
                        pulled = event.y - touchDownY
                        pullProgress = (pulled / pullThresholdPx).coerceIn(0f, 1f)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (url.isNotEmpty() && pulled > pullThresholdPx && webView.scrollY <= 0) {
                        onPullRefresh?.invoke()
                    }
                    pulled = 0f
                    pullProgress = 0f
                }
            }
            false
        }

        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val extra = result.extra
            when {
                result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && !extra.isNullOrEmpty() -> {
                    onLongPressLink?.invoke(extra)
                    true
                }
                (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE ||
                    result.type == WebView.HitTestResult.IMAGE_TYPE) && !extra.isNullOrEmpty() -> {
                    // 图片链接的 extra 是图片地址（可能是 data: base64），走图片菜单
                    onLongPressImage?.invoke(extra)
                    true
                }
                // 文字/空白区域交还给系统长按选词与页面内 JS 判定
                else -> false
            }
        }
    }

    init {
        // 引擎初始化放在这里（而不是各自的构造处），保证上面所有属性初始化完毕
        if (webViewOrNull != null) initWebView()
    }

    data class ElementHit(val tag: String, val imageUrl: String, val selector: String)

    /** 最近一次手指按下的位置（WebView 像素），长按图片 / 链接后用它定位对应的页面元素 */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /**
     * 「标记为广告」：长按图片 / 链接时系统只给出图片地址或链接，拿不到元素。
     * 这里按最近一次按下的位置取页面元素，向上找到广告位容器（浮层 / 带广告类名 / iframe / ins / 紧贴的包装层），
     * 生成选择器后走与「长按广告浮层」相同的 ElementHit 流程（标记并移除 / 仅移除）。
     * [onNotFound] 在没找到可标记元素时回调。
     */
    fun markAdAtTouch(onNotFound: () -> Unit) {
        if (isGecko || webViewOrNull == null) { onNotFound(); return }
        @Suppress("DEPRECATION")
        val scale = webView.scale.takeIf { it > 0f } ?: webView.context.resources.displayMetrics.density
        val cssX = lastTouchX / scale
        val cssY = lastTouchY / scale
        val js = """(function(){
  try {
    var el = document.elementFromPoint($cssX, $cssY);
    if (!el) return 0;
    var AD = /(^|[\s_-])(ad|ads|adv|advert|advertisement|banner|popup|float|floating|modal|overlay|mask|sponsor|sponsored|promo|gg|dsp)([\s_-]|${'$'})/i;
    function keyOf(n){
      var cls = (typeof n.className === 'string') ? n.className : '';
      return cls + ' ' + (n.id || '');
    }
    function selectorOf(node){
      var parts = [], cur = node;
      while (cur && cur.nodeType === 1 && cur !== document.body && cur !== document.documentElement && parts.length < 8) {
        if (cur.id) {
          var idSel = '#' + CSS.escape(cur.id);
          if (document.querySelectorAll(idSel).length === 1) { parts.unshift(idSel); break; }
        }
        var seg = cur.tagName.toLowerCase();
        var cls = (typeof cur.className === 'string' ? cur.className : '').trim().split(/\s+/).filter(Boolean).slice(0, 2);
        for (var k = 0; k < cls.length; k++) seg += '.' + CSS.escape(cls[k]);
        var parent = cur.parentElement;
        if (parent) seg += ':nth-child(' + (Array.prototype.indexOf.call(parent.children, cur) + 1) + ')';
        parts.unshift(seg);
        var sel = parts.join(' > ');
        try { if (document.querySelectorAll(sel).length === 1 && document.querySelector(sel) === node) return sel; } catch (e) {}
        cur = parent;
      }
      return parts.join(' > ');
    }
    var vw = innerWidth, vh = innerHeight;
    var target = null, cur = el, depth = 0;
    var base = el.getBoundingClientRect();
    while (cur && cur.nodeType === 1 && cur !== document.body && cur !== document.documentElement && depth < 8) {
      var tag = cur.tagName;
      var pos = getComputedStyle(cur).position;
      var r = cur.getBoundingClientRect();
      // 接近整页的容器不是广告位本身，不再往上找
      if (depth > 0 && r.width * r.height > vw * vh * 0.9) break;
      if (tag === 'INS' || tag === 'IFRAME' || pos === 'fixed' || pos === 'sticky' || AD.test(keyOf(cur))) { target = cur; break; }
      // 紧贴内容的包装层（宽高与触摸点元素相近）也算广告位的一部分，继续向上
      if (depth > 0 && r.width <= base.width * 1.2 + 2 && r.height <= base.height * 1.2 + 2) target = cur;
      cur = cur.parentElement; depth++;
    }
    if (!target) target = el.closest('a') || el;
    var img = target.tagName === 'IMG' ? target : (target.querySelector ? target.querySelector('img') : null);
    var src = img ? (img.currentSrc || img.src || '') : '';
    var sel = selectorOf(target);
    if (!sel) return 0;
    MafariElement.onElement(sel, target.tagName, src);
    return 1;
  } catch (e) { return 0; }
})()"""
        evaluateJs(js) { raw ->
            if (raw?.trim() != "1") onNotFound()
        }
    }

    /** 只在广告浮层（fixed/absolute 且像广告或面积大）上响应长按，文字区域交给系统选词 */
    private fun installElementInspector(view: WebView) {
        if (!pageEnhancements) return
        val js = """(function(){
  if (window.__mafariInspector) return;
  window.__mafariInspector = true;
  function selectorOf(el){
    try {
      if (el.id) return '#' + CSS.escape(el.id);
      var parts = []; var cur = el;
      while (cur && cur.nodeType === 1 && parts.length < 4) {
        var p = cur.tagName.toLowerCase();
        var cls = (typeof cur.className === 'string' ? cur.className : '').trim().split(/\s+/).filter(Boolean).slice(0, 2);
        for (var k = 0; k < cls.length; k++) { p += '.' + CSS.escape(cls[k]); }
        var parent = cur.parentElement;
        if (parent) { p += ':nth-child(' + (Array.prototype.indexOf.call(parent.children, cur) + 1) + ')'; }
        parts.unshift(p); cur = parent;
      }
      return parts.join(' > ');
    } catch (e) { return ''; }
  }
  function isAdLike(el){
    var st = getComputedStyle(el);
    var pos = st.position;
    if (pos !== 'fixed' && pos !== 'absolute') return false;
    var cls = (typeof el.className === 'string') ? el.className : '';
    var key = (cls + ' ' + (el.id || '')).toLowerCase();
    if (/(^|[\s_-])(ad|ads|advert|banner|popup|float|modal|overlay|mask|sponsor)([\s_-]|$)/.test(key)) return true;
    var r = el.getBoundingClientRect();
    return r.width > innerWidth * 0.5 && r.height > innerHeight * 0.25;
  }
  function fire(el){
    var tag = el.tagName;
    if (tag === 'IMG' || tag === 'INPUT' || tag === 'TEXTAREA') return;
    if (!isAdLike(el)) return;
    var img = el.querySelector ? el.querySelector('img') : null;
    var src = img ? (img.currentSrc || img.src || '') : '';
    try { MafariElement.onElement(selectorOf(el), tag, src); } catch (e) {}
    try { var sel = getSelection(); if (sel) sel.removeAllRanges(); } catch (e) {}
  }
  var timer = null, sx = 0, sy = 0, moved = false;
  document.addEventListener('touchstart', function(e){
    if (e.touches.length !== 1) return;
    var el = e.target;
    if (!el || el.nodeType !== 1) return;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; moved = false;
    clearTimeout(timer);
    timer = setTimeout(function(){ if (!moved) fire(el); }, 520);
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function(e){
    if (!e.touches.length) return;
    if (Math.abs(e.touches[0].clientX - sx) > 12 || Math.abs(e.touches[0].clientY - sy) > 12) {
      moved = true; clearTimeout(timer);
    }
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function(){ clearTimeout(timer); }, { capture: true, passive: true });
  document.addEventListener('touchcancel', function(){ clearTimeout(timer); }, { capture: true, passive: true });
})();"""
        view.evaluateJavascript(js, null)
    }

    /**
     * 所有启用的脚本都用 addDocumentStartJavaScript 注入（覆盖 iframe、真正的 document-start），
     * 具体的 URL 匹配与 run-at 等待在运行时（assets/mafari_gm_runtime.js）里按各框架处理。
     * 脚本列表 / 缓存资源变化时重建；系统不支持该特性时由 injectMissingUserScripts 兜底。
     */
    private fun syncUserScripts() {
        // Gecko 内核：走内置 WebExtension（GeckoGm），与 WebView 共用同一份运行时（见 docs/GECKO_GM.md）
        if (isGecko) { syncGeckoScripts(); return }
        if (destroyed || webViewOrNull == null) return
        if (!scriptsActive()) {
            scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
            scriptHandlers.clear()
            installedScriptsSignature = ""
            return
        }
        val enabled = userScripts.filter { it.enabled }
        val signature = enabled.joinToString("|") {
            "${it.id}:${it.code.hashCode()}:${it.match.hashCode()}:${ScriptResources.cachedCount(appContext, it.code)}"
        }

        // @require / @resource 还没缓存的先在后台取回来，取到后自动重建（失败的 60 秒后才会重试）
        val needFetch = enabled.filter { ScriptResources.missing(appContext, it.code).isNotEmpty() }
        if (needFetch.isNotEmpty() && !prefetchingScripts) {
            prefetchingScripts = true
            Thread {
                val changed = needFetch.map { ScriptResources.prefetch(appContext, it.code) }.any { it }
                webView.post {
                    prefetchingScripts = false
                    if (changed) syncUserScripts()
                }
            }.start()
        }
        if (signature == installedScriptsSignature) return

        scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
        scriptHandlers.clear()
        installedScriptsSignature = signature
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }
        enabled.forEach { script ->
            runCatching {
                val source = UserscriptEngine.buildSource(appContext, script)
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, source, setOf("*"))
                    ?.let { scriptHandlers.add(it) }
            }.onFailure { Diag.log("userscript: install failed ${script.name}: ${it.message}") }
        }
        Diag.log("userscript: document-start handlers=${scriptHandlers.size}/${enabled.size}")
    }

    /** Gecko 下没有「网页增强」总开关：只看脚本总开关（脚本菜单里的开关） */
    internal fun gmScriptsEnabled(): Boolean = scriptsEnabled

    /** Gecko：注册是全局的（不是按标签），所以这里只汇报「当前脚本集 + 总开关」，无痕范围由扩展的「允许在无痕中运行」决定 */
    private fun syncGeckoScripts() {
        if (destroyed) return
        val enabledAll = scriptsEnabled
        GeckoGm.remember(userScripts, enabledAll)
        val needFetch = userScripts.filter { it.enabled && ScriptResources.missing(appContext, it.code).isNotEmpty() }
        if (needFetch.isNotEmpty() && !prefetchingScripts) {
            prefetchingScripts = true
            Thread {
                val changed = needFetch.map { ScriptResources.prefetch(appContext, it.code) }.any { it }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    prefetchingScripts = false
                    if (changed) syncGeckoScripts()
                }
            }.start()
        }
        GeckoGm.update(appContext, userScripts, enabledAll)
    }

    /** Gecko 主框架上报的菜单命令 JSON 与端口（navigation 时清空） */
    @Volatile private var gmMenuJson: String = "[]"
    @Volatile private var gmMenuPort: org.mozilla.geckoview.WebExtension.Port? = null
    private val gmRan = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal fun gmMenuReported(json: String, port: org.mozilla.geckoview.WebExtension.Port) {
        gmMenuJson = json
        gmMenuPort = port
    }

    internal fun gmRanReported(id: String) { gmRan.add(id) }

    internal fun gmResetPage() {
        gmMenuJson = "[]"
        gmMenuPort = null
        gmRan.clear()
    }

    /**
     * 兜底：页面加载完后问一下页面里已经「见过」哪些脚本（运行时一进来就登记），
     * 没见过的（特性不支持、或 handler 安装晚于本次导航）再用 evaluateJavascript 补注入主框架。
     */
    private fun injectMissingUserScripts(view: WebView) {
        if (!scriptsActive()) return
        val enabled = userScripts.filter { it.enabled }
        if (enabled.isEmpty()) return
        val query = "(function(){try{return JSON.stringify(Object.keys(window.__mafariSeen||{}))}catch(e){return '[]'}})()"
        view.evaluateJavascript(query) { raw ->
            val seen = runCatching {
                val text = org.json.JSONTokener(raw ?: "null").nextValue() as? String ?: "[]"
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { array.getString(it) }.toSet()
            }.getOrDefault(emptySet())
            val pending = enabled.filter { it.id !in seen }
            pending.forEach { script ->
                runCatching { view.evaluateJavascript(UserscriptEngine.buildSource(appContext, script), null) }
            }
            Diag.log("userscript: page=$url injected-late=${pending.size}/${enabled.size}")
        }
    }

    /** 当前页面里脚本通过 GM_registerMenuCommand 注册的命令（仅主框架） */
    fun queryMenuCommands(onResult: (List<ScriptMenuCommand>) -> Unit) {
        if (isGecko) {
            onResult(runCatching {
                val array = org.json.JSONArray(gmMenuJson)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ScriptMenuCommand(o.optString("id"), o.optString("title"), o.optString("script"))
                }
            }.getOrDefault(emptyList()))
            return
        }
        val js = "(function(){try{return window.__mafariMenuList?window.__mafariMenuList():'[]'}catch(e){return '[]'}})()"
        evaluateJs(js) { raw ->
            val list = runCatching {
                val text = org.json.JSONTokener(raw ?: "null").nextValue() as? String ?: "[]"
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ScriptMenuCommand(o.optString("id"), o.optString("title"), o.optString("script"))
                }
            }.getOrDefault(emptyList())
            onResult(list)
        }
    }

    /** 当前页面（主框架）里真正匹配并运行的脚本数量 */
    fun queryRunningScripts(onResult: (Int) -> Unit) {
        if (isGecko) { onResult(gmRan.size); return }
        val js = "(function(){try{return Object.keys(window.__mafariRan||{}).length}catch(e){return 0}})()"
        evaluateJs(js) { raw -> onResult(raw?.trim()?.toIntOrNull() ?: 0) }
    }

    /** 当前页面（主框架）里真正匹配并运行的脚本 id */
    fun queryRunningScriptIds(onResult: (List<String>) -> Unit) {
        if (isGecko) { onResult(gmRan.toList()); return }
        val js = "(function(){try{return JSON.stringify(Object.keys(window.__mafariRan||{}))}catch(e){return '[]'}})()"
        evaluateJs(js) { raw ->
            val ids = runCatching {
                val text = org.json.JSONTokener(raw ?: "null").nextValue() as? String ?: "[]"
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { array.getString(it) }
            }.getOrDefault(emptyList())
            onResult(ids)
        }
    }

    fun runMenuCommand(id: String) {
        if (isGecko) { gmMenuPort?.let { GeckoGm.runMenuCommand(it, id) }; return }
        evaluateJs(
            "(function(){try{window.__mafariMenuRun&&window.__mafariMenuRun(" + org.json.JSONObject.quote(id) + ")}catch(e){}})()",
            null,
        )
    }

    private fun syncNav() {
        if (url.isEmpty()) {
            // 主页上：只有「页面被盖住」时才能返回（还原页面）；WebView 里遗留的历史不算
            canGoBack = savedUrl.isNotBlank()
            canGoForward = false
            return
        }
        canGoBack = engineCanGoBack() || fromHome || openedFromLink
        canGoForward = engineCanGoForward()
    }

    // ======================================================================
    // 与内核无关的统一接口：界面层只应该调用这些，不要直接碰 webView
    // ======================================================================

    private fun engineCanGoBack(): Boolean =
        if (isGecko) gecko?.canGoBack ?: false else runCatching { webView.canGoBack() }.getOrDefault(false)

    private fun engineCanGoForward(): Boolean =
        if (isGecko) gecko?.canGoForward ?: false else runCatching { webView.canGoForward() }.getOrDefault(false)

    private fun engineUrl(): String =
        if (isGecko) gecko?.currentUrl().orEmpty() else runCatching { webView.url }.getOrNull().orEmpty()

    private fun engineStop() {
        if (isGecko) { gecko?.stop(); return }
        runCatching { webView.stopLoading() }
    }

    private fun engineReload() {
        if (isGecko) { gecko?.reload(); return }
        webView.reload()
    }

    private fun engineLoad(target: String) {
        if (isGecko) { ensureGecko()?.loadUri(target); return }
        val headers = extraRequestHeaders()
        if (headers.isEmpty()) webView.loadUrl(target) else webView.loadUrl(target, headers)
    }

    /** 强制刷新（下拉刷新 / 脚本开关后刷新）：不像 [reload] 那样在加载中变成「停止」 */
    fun refresh() = engineReload()

    /**
     * 滚动事件信号（WebView 的 OnScrollChangeListener / Gecko 的 ScrollDelegate 触发，CONFLATED 合并）。
     * 底栏手势协程据此休眠 / 唤醒，页面静止时不再每 80ms 空转。
     */
    val scrollSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** 页面滚动位置（像素）：底栏自动收起 / 下拉刷新判断用 */
    val scrollOffsetY: Int
        get() = if (isGecko) gecko?.scrollY ?: 0 else webViewOrNull?.scrollY ?: 0

    /** 引擎当前的页面标题（切换地址时用来清掉旧标题） */
    fun currentPageTitle(): String = if (isGecko) gecko?.pageTitle.orEmpty() else webViewOrNull?.title.orEmpty()

    /**
     * 在页面里执行 JS。Gecko 没有公开的 evaluateJavascript，
     * 这类功能（油猴脚本 / 元素标记 / 页面内查询）在 Gecko 下需要走 WebExtension，这里直接回调 null。
     */
    fun evaluateJs(js: String, callback: ((String?) -> Unit)? = null) {
        val wv = webViewOrNull
        if (wv == null) {
            callback?.invoke(null)
            return
        }
        if (callback == null) {
            wv.evaluateJavascript(js, null)
        } else {
            wv.evaluateJavascript(js) { raw -> callback(raw) }
        }
    }

    /** 可见区域尺寸变化后：让内容视图重新布局，并通知页面重新计算响应式布局 */
    fun onViewportChanged() {
        // GeckoView 由 AndroidView/自身的 View 测量流程接收 Surface 尺寸变化；
        // GeckoSession 没有 WebView 式 evaluateJavascript，这里再 requestLayout/invalidate
        // 只会制造重复布局，不会给 Gecko 页面带来有效的 resize 通知。
        if (isGecko) return
        val v = contentView
        v.post {
            if (!v.isAttachedToWindow) return@post
            v.requestLayout()
            v.invalidate()
            evaluateJs(
                """
                (function() {
                    try {
                        window.dispatchEvent(new Event('resize'));
                        if (window.visualViewport) {
                            window.visualViewport.dispatchEvent(new Event('resize'));
                        }
                    } catch (_) {}
                })();
                """.trimIndent(),
            )
        }
    }

    /** 当前标签是否是正被显示的那个：后台的 Gecko 会话停止渲染以省电 */
    fun setEngineActive(active: Boolean) {
        // 记下「最近一次是当前标签」的时间：后台回收按它算闲置多久
        if (active || engineActiveFlag) lastActiveUptime = android.os.SystemClock.uptimeMillis()
        engineActiveFlag = active
        gecko?.setActive(active)
    }

    /** 最近一次作为当前标签（或被创建）的时间，uptime 毫秒；闲置时长 = now - 它 */
    internal var lastActiveUptime = android.os.SystemClock.uptimeMillis()
        private set
    private var engineActiveFlag = false

    /** Gecko 原生沉浸：最大可收起高度 + 当前底部被推出屏幕的高度（像素）；WebView 标签无操作 */
    fun geckoApplyImmersive(maxPx: Int, clipPx: Int) {
        gecko?.applyImmersive(maxPx, clipPx)
    }

    // ======================================================================
    // Gecko 回调 → 标签状态（由 GeckoTabEngine 调用，均在主线程）
    // ======================================================================

    private fun isBlankPage(u: String) = u == "about:blank"

    internal fun geckoPageStart(u: String) {
        if (closed || destroyed) return
        // 主页（url 为空）或被主页盖住（parked）时，页面侧回调不得改写 url；引擎初始化产生的 about:blank 也忽略
        if (!parked && url.isNotEmpty() && !(isBlankPage(u) && !isBlankPage(url))) url = u
        pageHost = runCatching { Uri.parse(u).host }.getOrNull().orEmpty()
        loading = true
        errorText = null
        gmResetPage()
        syncNav()
    }

    internal fun geckoLocation(u: String) {
        if (closed || destroyed) return
        if (stateRestorePending && !isBlankPage(u)) stateRestorePending = false
        if (!parked && url.isNotEmpty() && !(isBlankPage(u) && !isBlankPage(url))) url = u
        updatePageHost(u)
        syncNav()
    }

    internal fun geckoProgress(p: Int) {
        if (closed || destroyed) return
        progress = p / 100f
        loading = p < 100
    }

    internal fun geckoTitle(t: String) {
        if (t.isNotBlank()) title = t
    }

    internal fun geckoPageStop(success: Boolean) {
        if (closed || destroyed) return
        loading = false
        progress = 1f
        syncNav()
        val visited = url
        if (success && !parked && visited.isNotEmpty() && !visited.startsWith("about:")) {
            onVisited?.invoke(visited, title)
        }
    }

    internal fun geckoNavChanged() {
        if (closed || destroyed) return
        syncNav()
    }

    internal fun geckoLoadError(text: String) {
        if (closed || destroyed) return
        loading = false
        errorText = text
    }

    private fun captureGeckoThumbnail() {
        val g = gecko ?: return
        if (closed || destroyed || url.isBlank() || isBlankPage(url) || parked) return
        // 全局最多同时 N 张在途（Gecko 的整屏原图每张约 10MB），超了这次就不拍
        if (!TabThumbs.tryBegin(this)) return
        capturingSince = android.os.SystemClock.uptimeMillis()
        // Gecko 的截图本身是异步的；拿到整屏原图后，缩小 / 压缩 / 落盘都交给 TabThumbs 的后台线程，主线程不做图像处理
        g.capture { bmp ->
            if (closed || destroyed) {
                capturingSince = 0L
                TabThumbs.end(this)
                return@capture
            }
            TabThumbs.submit(this, bmp, ownsSource = true)
        }
    }

    /** window.open / target=_blank：沿用 WebView 版的新窗口规则（同站 / 点击 / 重定向权限） */
    internal fun geckoNewWindow(target: String, linkClick: Boolean) {
        val uri = Uri.parse(target)
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") return
        handlePopupTarget(target, uri.host.orEmpty(), linkClick)
    }

    internal fun geckoLaunchExternal(uri: Uri) = launchExternalApp(uri)

    /** PromptDelegate 弹对话框用 */
    internal fun geckoActivity(): android.app.Activity? = resolveActivity()

    internal fun geckoContext(): Context = appContext

    /** 被拦截的无手势弹窗：与重定向拦截共用提示（去重 / 限频） */
    internal fun geckoPopupBlocked(target: String) {
        notifyRedirectBlocked(false, runCatching { Uri.parse(target).host }.getOrNull().orEmpty())
    }

    internal fun geckoBlobStart(name: String) {
        onBlobStart?.invoke(name)
    }

    /**
     * Gecko 版「网页重定向权限」，规则与 [interceptRedirect] 一致：只管页面自己发起的跨站主框架跳转
     * （地址栏 / 前进后退 / 服务器 3xx / 有用户手势的点击都不管；同站跳转不管）。
     * Gecko 取不到「触点下是不是 <a>」，所以有手势一律视为用户点击。
     * @return true = 本次导航已被接管（调用方应 DENY）
     */
    internal fun geckoInterceptRedirect(target: String, direct: Boolean, hasGesture: Boolean, serverRedirect: Boolean): Boolean {
        if (direct || hasGesture || serverRedirect) return false
        val from = pageHost
        if (from.isEmpty()) return false
        val targetHost = runCatching { Uri.parse(target).host }.getOrNull().orEmpty()
        if (targetHost.isNotEmpty() && isSameSite(targetHost, from)) return false
        val open = onOpenInNewTab
        when (redirectMode) {
            1 -> {
                if (open == null) return false
                open(target)
                return true
            }
            2 -> {
                notifyRedirectBlocked(false, targetHost)
                return true
            }
        }
        if (redirectPromptPending) return true
        askRedirect(from, "跳转到 ${targetHost.ifEmpty { target.take(60) }}") { allow ->
            if (allow) open?.invoke(target) else notifyRedirectBlocked(false, targetHost)
        }
        return true
    }

    /** Gecko 视频全屏中：界面据此隐藏应用自己的顶栏 / 底栏 */
    var geckoFullscreen by mutableStateOf(false)
        private set

    /**
     * Gecko 版：页面无手势自动唤起其他应用（intent:// weixin:// 等），套用「网页重定向」权限，
     * 规则与 [interceptRedirect]（external = true）一致：空白页 / 首次加载上的唤起不算；总是允许 → 直接唤起；
     * 总是拒绝 → 提示已拦截；询问 → 弹窗，同意才唤起。当前页面始终原地不动。
     */
    internal fun geckoExternalRequest(uri: Uri) {
        val from = pageHost
        if (from.isEmpty() || redirectMode == 1) {
            launchExternalApp(uri)
            return
        }
        if (redirectMode == 2) {
            notifyRedirectBlocked(true, "")
            return
        }
        if (redirectPromptPending) return
        askRedirect(from, "打开其他应用") { allow ->
            if (allow) launchExternalApp(uri) else notifyRedirectBlocked(true, "")
        }
    }

    internal fun geckoCloseRequest() {
        onRequestClose?.invoke()
    }

    internal fun geckoSetPull(p: Float) {
        pullProgress = p
    }

    internal fun geckoPullRefresh() {
        onPullRefresh?.invoke()
    }

    internal fun geckoDataUrlDownload(uri: String, disposition: String?, mime: String?) {
        val dataMime = uri.substringAfter("data:").substringBefore(',').substringBefore(';').ifBlank { null }
        val name = Downloader.withExtension(
            Downloader.fileNameFrom(uri, disposition),
            mime?.takeIf { it.isNotBlank() } ?: dataMime,
        )
        onBlobReady?.invoke(uri, name, mime ?: dataMime, null)
    }

    internal fun geckoBlobReady(name: String, mime: String?, path: String) {
        onBlobReady?.invoke(null, name, mime, path)
    }

    internal fun geckoBlobError(message: String) {
        onBlobError?.invoke(message)
    }

    internal fun geckoEnsureAndroid(perms: List<String>, result: (Boolean) -> Unit) {
        ensureAndroid(perms.toTypedArray(), anyOk = false, result = result)
    }

    internal fun geckoRequestLocation(origin: String, result: (Boolean) -> Unit) {
        decide(
            GeckoSitePerms.override(origin, GeckoSitePerms.LOCATION) ?: locationMode,
            origin,
            "获取你的位置",
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            anyOk = true,
            permKey = GeckoSitePerms.LOCATION,
            result = result,
        )
    }

    /** 摄像头 / 麦克风：规则与 WebView 版 handleWebPermission 一致；其中任一被设置为「拒绝」则整体拒绝。例外网站优先于全局设置 */
    internal fun geckoRequestMedia(origin: String, wantCam: Boolean, wantMic: Boolean, result: (Boolean) -> Unit) {
        val camMode = GeckoSitePerms.override(origin, GeckoSitePerms.CAMERA) ?: cameraMode
        val micModeEff = GeckoSitePerms.override(origin, GeckoSitePerms.MIC) ?: micMode
        val camOk = wantCam && camMode != 2
        val micOk = wantMic && micModeEff != 2
        if ((wantCam && !camOk) || (wantMic && !micOk) || (!camOk && !micOk)) {
            result(false)
            return
        }
        val perms = buildList {
            if (camOk) add(android.Manifest.permission.CAMERA)
            if (micOk) add(android.Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()
        val what = listOfNotNull(if (camOk) "摄像头" else null, if (micOk) "麦克风" else null).joinToString("、")
        val mustAsk = (camOk && camMode == 0) || (micOk && micModeEff == 0)
        val keys = listOfNotNull(
            if (camOk) GeckoSitePerms.CAMERA else null,
            if (micOk) GeckoSitePerms.MIC else null,
        ).joinToString(",")
        decide(if (mustAsk) 0 else 1, origin, "使用$what", perms, permKey = keys, result = result)
    }

    /**
     * 不涉及 Android 系统权限的网站权限（通知 / 持久存储 / 跨站 Cookie / DRM / 设备应用和服务 / 本地网络设备）：
     * 站点例外优先，没有例外就按全局默认（询问 / 允许 / 拒绝）。
     */
    internal fun geckoRequestSimple(origin: String, key: String, what: String, result: (Boolean) -> Unit) {
        val global = if (key == GeckoSitePerms.NOTIFICATION) notificationMode else GeckoSitePerms.mode(key)
        val mode = GeckoSitePerms.override(origin, key) ?: global
        decide(mode, origin, what, emptyArray(), permKey = key, result = result)
    }

    /** Gecko 视频全屏：系统栏隐藏 + 横屏（页面本身由 Gecko 撑满 GeckoView） */
    internal fun geckoFullScreen(on: Boolean) {
        geckoFullscreen = on
        val activity = resolveActivity() ?: return
        val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (on) {
            lockLandscape()
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            restoreOrientation()
        }
    }

    fun load(input: String) {
        dropPendingRestore()
        val target = toUrl(input, searchTemplate)
        if (url.isEmpty() && !openedFromLink) {
            // 从主页进入新页面：记下 WebView 历史的当前位置，返回到这一层就回主页
            fromHome = true
            // Gecko：清空旧历史，第一页就是历史起点，之后只看 canGoBack，不再依赖会过期 / 漏回调的历史下标
            // WebView：没有可靠的清空办法，沿用下标锚点
            if (isGecko) {
                ensureGecko()?.purgeHistory()
                homeAnchor = -1
            } else {
                homeAnchor = historyIndex()
            }
            savedUrl = ""
        }
        parked = false
        url = target
        fpInjector?.prepare(target)
        engineLoad(target)
        syncNav()
    }

    fun loadUrl(raw: String) {
        dropPendingRestore()
        parked = false
        url = raw
        fpInjector?.prepare(raw)
        engineLoad(raw)
    }

    fun reload() {
        if (loading) {
            engineStop()
            loading = false
        } else {
            // 设置 / 豁免列表变更后旧注册已作废，刷新前重新注册当前页的 origin
            fpInjector?.prepare(engineUrl())
            engineReload()
        }
    }

    /** 应用 UA：桌面模式优先，其次设置里的自定义 UA，最后系统默认 */
    fun applyUserAgent() {
        if (isGecko) {
            // 引擎还没建（延迟创建 / 已回收）时什么都不做：ensureGecko 建好后会再调用一次
            gecko?.let { g ->
                val desktop = desktopMode || customUserAgent == DESKTOP_UA
                g.applyUserAgent(if (desktop) "" else customUserAgent, desktop)
            }
            return
        }
        val ua = when {
            desktopMode -> desktopUserAgent()
            customUserAgent.isBlank() -> defaultUserAgent
            // 「桌面 Chrome」预设：按本机 WebView 的 Chrome 大版本生成桌面 UA（之前被当成默认 UA，等于没生效）
            customUserAgent == DESKTOP_UA -> desktopUserAgent()
            else -> customUserAgent
        }
        runCatching { webView.settings.userAgentString = ua }
    }

    /**
     * 显示模式深浅色同步到 WebView（公开 API，不自造 CSS 覆盖）：
     * 1. `WebSettings.setAlgorithmicDarkeningAllowed` — 系统/WebView 官方深色适配（API 33+）
     * 2. `setBackgroundColor` — 空白/加载底色跟随应用主题
     * 3. 对齐 Activity `Configuration.uiMode` — 让算法深色跟随应用内「深色/浅色/跟随系统」
     */
    fun applyDisplayTheme(dark: Boolean) {
        if (isGecko) {
            gecko?.applyTheme(dark)
        } else {
            runCatching { webView.settings.setAlgorithmicDarkeningAllowed(true) }
            webView.setBackgroundColor(
                if (dark) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt(),
            )
        }
        val activity = resolveActivity() ?: return
        runCatching {
            val res = activity.resources
            val conf = res.configuration
            val night = if (dark) {
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            } else {
                android.content.res.Configuration.UI_MODE_NIGHT_NO
            }
            if ((conf.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != night) {
                val next = android.content.res.Configuration(conf)
                next.uiMode = (next.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                @Suppress("DEPRECATION")
                res.updateConfiguration(next, res.displayMetrics)
            }
        }
    }

    /**
     * 应用隐私与权限设置（当前标签成为活动标签、或设置变化时调用）。
     * - Cookie：CookieManager 按 WebView 设置第三方开关；总开关随标签是否无痕 / 策略「拒绝全部」切换。
     * - DNT：请求头（地址栏 / 链接点击的主框架导航）+ navigator.doNotTrack / globalPrivacyControl。
     * - WebRTC / 剪贴板 / 媒体静音：document-start 脚本注入所有框架，刷新页面后生效。
     * - 摄像头 / 麦克风 / 定位：在 WebChromeClient 里按「询问 / 允许 / 拒绝」处理。
     * - 跟踪脚本：shouldInterceptRequest 拦截第三方统计域名（由去广告总开关控制，见 AdBlock）。
     */
    fun applyPrivacy(
        cookieMode: Int,
        sendDnt: Boolean,
        webrtcDisabled: Boolean,
        clipboardMode: Int = 0,
        mediaMode: Int = 1,
        cameraMode: Int = 0,
        micMode: Int = 0,
        locationMode: Int = 0,
        notificationMode: Int = 0,
        redirectMode: Int = 0,
        blockTrackers: Boolean = false,
        fingerprintProtect: Boolean = false,
    ) {
        gecko?.applyCookieMode(cookieMode)
        gecko?.applyPrivacy()
        webViewOrNull?.let { wv ->
            runCatching {
                // 官方 CookieManager：总开关（无痕 / 拒绝全部时关）+ 按 WebView 的第三方开关
                CookieManager.getInstance().setAcceptCookie(!incognito && cookieMode != 3)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, cookieMode == 0)
            }
        }
        dntEnabled = sendDnt
        this.webrtcDisabledFlag = webrtcDisabled
        this.cameraMode = cameraMode
        this.micMode = micMode
        this.locationMode = locationMode
        this.notificationMode = notificationMode
        this.redirectMode = redirectMode
        this.blockTrackers = blockTrackers
        // 媒体发声：允许=可自动播放；询问/拒绝=需用户手势才能出声
        // 以下全是 WebView 专用：脚本注入 / 媒体自动播放策略。Gecko 对应能力见 docs/GECKO.md
        val wv = webViewOrNull ?: return
        runCatching { wv.settings.mediaPlaybackRequiresUserGesture = mediaMode != 1 }

        val script = buildString {
            if (webrtcDisabled) {
                InjectAssets.read(appContext, "mafari_no_webrtc.js")?.let {
                    append(it)
                    append('\n')
                }
            }
            if (clipboardMode == 2) {
                append(
                    "(function(){try{var d=function(){return Promise.reject(new DOMException('Permission denied','NotAllowedError'))};" +
                        "if(navigator.clipboard){['read','readText','write','writeText'].forEach(function(m){try{navigator.clipboard[m]=d}catch(e){}})}}catch(e){}})();\n",
                )
            }
            if (sendDnt) {
                append(
                    "(function(){try{Object.defineProperty(Navigator.prototype,'doNotTrack',{get:function(){return '1'},configurable:true});" +
                        "Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:function(){return true},configurable:true})}catch(e){}})();\n",
                )
            }
            if (mediaMode == 2) {
                append(
                    "(function(){try{Object.defineProperty(HTMLMediaElement.prototype,'muted',{get:function(){return true},set:function(v){},configurable:true});" +
                        "var p=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){try{this.volume=0}catch(e){}return p.apply(this,arguments)}}catch(e){}})();\n",
                )
            }
            // 网站通知拒绝：JS Notification 权限固定为 denied
            if (notificationMode == 2) {
                append(
                    "(function(){try{" +
                        "if(window.Notification){Object.defineProperty(Notification,'permission',{get:function(){return 'denied'},configurable:true});" +
                        "Notification.requestPermission=function(){return Promise.resolve('denied')}}}" +
                        "}catch(e){}})();\n",
                )
            }
        }
        // 切换标签 / 改无关设置也会走到这里；脚本内容没变就不要卸载再重新注册
        if (script == appliedPrivacyScript) return
        appliedPrivacyScript = script
        runCatching { privacyHandler?.remove() }
        privacyHandler = null
        if (script.isNotBlank() &&
            androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            runCatching {
                privacyHandler = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            }.onFailure {
                appliedPrivacyScript = null // 安装失败：下次允许重试
                Diag.log("privacy script install failed: ${it.message}")
            }
        }
    }

    /** 上一次成功交给 WebView 的隐私脚本全文（null = 尚未安装 / 上次失败） */
    private var appliedPrivacyScript: String? = null

    private var privacyHandler: androidx.webkit.ScriptHandler? = null

    @Volatile
    private var blockTrackers = false

    @Volatile
    private var pageHost = ""

    private var lastDntUrl = ""

    private var cameraMode = 0
    private var micMode = 0
    private var locationMode = 0
    private var notificationMode = 0

    /** 网页重定向 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    @Volatile
    private var redirectMode = 0

    /** 正在等待用户决定的重定向，避免脚本循环跳转时连续弹窗 */
    private var redirectPromptPending = false
    private var lastRedirectToast = 0L

    /**
     * 网页重定向权限：只管「页面自己发起、没有用户手势」的主框架跳转——
     * JS（location / window.open）、meta refresh，以及自动唤起其他应用（intent:// 等）。
     * 用户点击、地址栏输入、前进后退，以及用户发起导航后的服务器 3xx 链都不受影响。
     * 同站跳转（host 去掉 www. 后相同）视为页面内部导航，不拦截。
     * @return true 表示本次导航已被接管（拦截，用户同意后由 [onAllowed] 放行）
     */
    private fun interceptRedirect(request: WebResourceRequest, external: Boolean, onAllowed: () -> Unit): Boolean {
        // 网页跳转只管主框架、非服务器 3xx；但「自动唤起其他应用」不受此限：
        // 隐藏 iframe 里的 weixin:// / intent:// 与服务器 302 到应用协议，同样是页面在无手势地拉起别的应用
        if (!external && (!request.isForMainFrame || request.isRedirect)) return false
        // 有用户手势：真实链接点击（触点下是 <a>）照常放行；
        // 点击后由脚本发起的跨站跳转（div onclick / location.href 等）也按重定向处理
        if (request.hasGesture()) {
            val hit = webView.hitTestResult
            val onAnchor = hit != null && !hit.extra.isNullOrEmpty() &&
                (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
            if (external || onAnchor || request.method != "GET") return false
        }
        // 首次加载 / 空白页上的跳转不算重定向
        val from = pageHost
        if (from.isEmpty()) return false
        val targetHost = request.url.host.orEmpty()
        // 同站（含子网站，如 m. / login. / www.）跳转视为页面内部导航，不拦截
        if (!external && targetHost.isNotEmpty() && isSameSite(targetHost, from)) return false
        // 总是允许：外部应用直接唤起；跨站跳转与「允许」一致，在新标签页打开（当前页不动）
        if (redirectMode == 1) {
            if (external) return false
            onAllowed()
            return true
        }
        val label = if (external) "打开其他应用" else "跳转到 ${targetHost.ifEmpty { request.url.toString().take(60) }}"
        if (redirectMode == 2) {
            notifyRedirectBlocked(external, targetHost)
            return true
        }
        if (redirectPromptPending) return true
        // 拒绝只是不执行这次跳转 / 唤起：这次导航在 shouldOverrideUrlLoading 里就已被截下，
        // 当前页面一直没有离开，所以这里什么都不用回退 —— 停留在网页，不能再 goBack 把页面「关掉」
        askRedirect(from, label) { allow ->
            if (allow) onAllowed() else notifyRedirectBlocked(external, targetHost)
        }
        return true
    }

    /** 每个 origin 本次运行最多弹几次豁免请求，以及两次之间的最短间隔，防止页面骚扰用户 */
    private val fpExemptAsked = HashMap<String, LongArray>() // origin -> [次数, 上次 elapsedRealtime]

    /**
     * 页面经 [TrustedChannel] 请求「关闭对我的指纹保护」。origin 是 WebView 校验过的主框架来源，
     * 只能豁免它自己的 host；必须用户确认，页面无法静默修改设置，也得不到任何回传。
     */
    private fun handleFpExemptRequest(origin: WebOrigin) {
        if (!AdBlock.fingerprintActive) return
        if (AdBlock.isFingerprintExempt(origin.host)) return
        // 只接受「当前正在显示的页面」发来的请求
        val current = WebOrigin.parse(webView.url) ?: return
        if (current != origin) return
        if (redirectPromptPending) return
        val now = android.os.SystemClock.elapsedRealtime()
        val record = fpExemptAsked.getOrPut(origin.origin) { longArrayOf(0L, 0L) }
        if (record[0] >= 3 || (record[0] > 0 && now - record[1] < 30_000L)) return
        if (fpExemptAsked.size > 64) fpExemptAsked.clear()
        record[0]++
        record[1] = now
        askRedirect(origin.origin, "关闭对它的指纹保护") { allow ->
            if (allow && AdBlock.exemptFingerprintSite(origin.host)) {
                android.widget.Toast.makeText(
                    appContext, "已豁免 ${origin.host}，刷新后生效", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private class DeferredPrompt(
        val origin: String,
        val what: String,
        val permKey: String?,
        val decide: (Boolean) -> Unit,
        val at: Long,
    )

    /** 后台标签没有弹窗载体时先挂起，切到该标签再弹，而不是悄悄当成拒绝 */
    private var deferredPrompt: DeferredPrompt? = null

    private fun askRedirect(origin: String, what: String, onResult: (Boolean) -> Unit) {
        redirectPromptPending = true
        val decide = { allow: Boolean ->
            redirectPromptPending = false
            onResult(allow)
        }
        val prompt = onPermissionPrompt
        if (prompt != null) {
            prompt(origin, what, null, decide)
        } else {
            deferredPrompt = DeferredPrompt(origin, what, null, decide, android.os.SystemClock.elapsedRealtime())
        }
    }

    private fun flushDeferredPrompt() {
        val d = deferredPrompt ?: return
        val prompt = onPermissionPrompt ?: return
        deferredPrompt = null
        // 挂起太久（超过 1 分钟）的请求已无意义，按拒绝收尾
        if (android.os.SystemClock.elapsedRealtime() - d.at > 60_000) d.decide(false) else prompt(d.origin, d.what, d.permKey, d.decide)
    }

    /**
     * 唤起外部应用。支持 intent:// （含 S.browser_fallback_url）；没装对应应用时给出提示，
     * 有回退网址就在当前页打开回退网址，否则什么都不发生 —— 页面本身保持不动。
     */
    private fun launchExternalApp(uri: Uri) {
        val raw = uri.toString()
        val isIntentScheme = uri.scheme.equals("intent", ignoreCase = true)
        val intent = runCatching {
            if (isIntentScheme) Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
        }.getOrNull()
        if (intent == null) {
            android.widget.Toast.makeText(appContext, "无法打开该链接", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 网页给出的 intent 不得指定组件 / selector，只允许按 BROWSABLE 解析，防止直指本应用或非导出组件
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { appContext.startActivity(intent) }.isSuccess
        if (started) return
        val fallback = if (isIntentScheme) intent.getStringExtra("browser_fallback_url") else null
        if (!fallback.isNullOrBlank() && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
            mainHandler.post { loadUrl(fallback) }
        } else {
            android.widget.Toast.makeText(appContext, "未找到可打开的应用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 允许重定向：目标在新标签页打开；原页面在导航被截下时就没有离开，无需回退 */
    private fun openRedirectInNewTab(target: String) {
        val open = onOpenInNewTab
        if (open != null) open(target) else mainHandler.post { loadUrl(target) }
    }

    /**
     * window.open / target=_blank 创建的新窗口：不创建真正的窗口，只截获它要打开的地址，
     * 再套用「网页重定向」权限——同站或用户点击的真实链接直接开新标签，
     * 其余按 询问 / 总是允许 / 总是拒绝 处理。拒绝时原页面完全不受影响。
     */
    fun handleCreateWindow(view: WebView, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val hit = view.hitTestResult
        val linkClick = isUserGesture && hit != null && !hit.extra.isNullOrEmpty() &&
            (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
        val temp = WebView(appContext)
        temp.settings.javaScriptEnabled = true
        var handled = false
        fun capture(target: String?) {
            if (handled || target.isNullOrBlank() || target == "about:blank") return
            handled = true
            val uri = Uri.parse(target)
            temp.post {
                runCatching { temp.stopLoading(); temp.destroy() }
            }
            if (uri.scheme != "http" && uri.scheme != "https") return
            handlePopupTarget(target, uri.host.orEmpty(), linkClick)
        }
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                capture(request.url.toString())
                return true
            }

            override fun onPageStarted(v: WebView, pageUrl: String?, favicon: Bitmap?) {
                capture(pageUrl)
            }
        }
        // 超时仍没有地址（空白弹窗）就回收
        temp.postDelayed({
            if (!handled) {
                handled = true
                runCatching { temp.stopLoading(); temp.destroy() }
            }
        }, 8_000)
        transport.webView = temp
        resultMsg.sendToTarget()
        return true
    }

    private fun handlePopupTarget(target: String, targetHost: String, linkClick: Boolean) {
        val open = onOpenInNewTab ?: return
        val sameSite = pageHost.isNotEmpty() && targetHost.isNotEmpty() && isSameSite(targetHost, pageHost)
        if (sameSite || pageHost.isEmpty()) {
            // 同站新窗口链接：默认在当前标签打开，「同站链接新标签页打开」开关打开才新开标签
            if (sameSiteNewTab) open(target) else mainHandler.post { loadUrl(target) }
            return
        }
        // 用户亲手点的跨站 target=_blank 链接：就是要新窗口，直接新标签打开，不走重定向权限
        if (linkClick) {
            open(target)
            return
        }
        if (redirectMode == 1) {
            open(target)
            return
        }
        if (redirectMode == 2) {
            notifyRedirectBlocked(false, targetHost)
            return
        }
        if (redirectPromptPending) return
        askRedirect(pageHost, "跳转到 ${targetHost.ifEmpty { target.take(60) }}") { allow ->
            if (allow) open(target) else notifyRedirectBlocked(false, targetHost)
        }
    }

    private fun notifyRedirectBlocked(external: Boolean, host: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRedirectToast < 2000) return
        lastRedirectToast = now
        val msg = if (external) "已拦截网页打开其他应用" else "已拦截网页重定向" + if (host.isNotEmpty()) "：$host" else ""
        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 网页权限「询问」：由界面弹窗，decide(true/false) 只能调用一次 */
    var onPermissionPrompt: ((origin: String, what: String, permKey: String?, decide: (Boolean) -> Unit) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) flushDeferredPrompt()
        }

    /** 申请 Android 运行时权限，完成后回调（调用方再自行检查授予结果） */
    var requestAndroidPermissions: ((Array<String>, () -> Unit) -> Unit)? = null

    private fun hasPermission(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(appContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun ensureAndroid(perms: Array<String>, anyOk: Boolean, result: (Boolean) -> Unit) {
        val need = perms.filter { it.isNotBlank() && !hasPermission(it) }.toTypedArray()
        fun granted() = if (anyOk) {
            perms.filter { it.isNotBlank() }.any { hasPermission(it) }
        } else {
            perms.filter { it.isNotBlank() }.all { hasPermission(it) }
        }
        if (need.isEmpty()) {
            result(granted())
            return
        }
        val request = requestAndroidPermissions
        if (request == null) {
            result(false)
        } else {
            request(need) { result(granted()) }
        }
    }

    private fun decide(
        mode: Int,
        origin: String,
        what: String,
        perms: Array<String>,
        anyOk: Boolean = false,
        permKey: String? = null,
        result: (Boolean) -> Unit,
    ) {
        // 隐私报告：记录请求本身（允许 / 拒绝都算），无痕标签页不记
        if (!incognito) PrivacyStats.recordPermission(appContext, origin, what)
        when (mode) {
            2 -> result(false)
            1 -> ensureAndroid(perms, anyOk, result)
            else -> {
                val prompt = onPermissionPrompt
                if (prompt == null) {
                    result(false)
                } else {
                    prompt(origin, what, permKey) { allow ->
                        if (allow) ensureAndroid(perms, anyOk, result) else result(false)
                    }
                }
            }
        }
    }

    private fun handleWebPermission(request: PermissionRequest) {
        // Android SDK 的 PermissionRequest 仅有：
        // RESOURCE_AUDIO_CAPTURE / RESOURCE_VIDEO_CAPTURE / RESOURCE_PROTECTED_MEDIA_ID / RESOURCE_MIDI_SYSEX
        // 定位走 onGeolocationPermissionsShowPrompt；网站通知 WebView 不走 PermissionRequest（设置里用 JS 拒绝生效）
        val res = request.resources.toSet()
        val keep = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in res) keep += PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        val camOk = PermissionRequest.RESOURCE_VIDEO_CAPTURE in res && cameraMode != 2
        val micOk = PermissionRequest.RESOURCE_AUDIO_CAPTURE in res && micMode != 2
        fun finish(extra: List<String>) {
            val all = keep + extra
            if (all.isEmpty()) request.deny() else request.grant(all.toTypedArray())
        }
        if (!camOk && !micOk) {
            finish(emptyList())
            return
        }
        val perms = buildList {
            if (camOk) add(android.Manifest.permission.CAMERA)
            if (micOk) add(android.Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()
        val what = listOfNotNull(if (camOk) "摄像头" else null, if (micOk) "麦克风" else null).joinToString("、")
        val mustAsk = (camOk && cameraMode == 0) || (micOk && micMode == 0)
        decide(if (mustAsk) 0 else 1, request.origin.host ?: request.origin.toString(), "使用$what", perms) { ok ->
            finish(
                if (ok) buildList {
                    if (camOk) add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (micOk) add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                } else emptyList(),
            )
        }
    }

    @Volatile
    private var dntEnabled = false

    @Volatile
    private var webrtcDisabledFlag = false

    /** 供 WebViewClient 附加 DNT 头 */
    fun extraRequestHeaders(): Map<String, String> =
        if (dntEnabled) {
            mapOf("DNT" to "1", "Sec-GPC" to "1")
        } else {
            emptyMap()
        }

    /** 与本机 WebView 内核主版本一致的桌面 Chrome UA（遵循 UA 精简格式 Chrome/主版本.0.0.0） */
    private fun desktopUserAgent(): String {
        val major = CHROME_MAJOR_REGEX.find(defaultUserAgent)?.groupValues?.get(1)
            ?: return DESKTOP_UA
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    private var desktopHandler: androidx.webkit.ScriptHandler? = null

    /** 安装 / 移除桌面模式脚本（viewport 重写、platform / 触控 / 媒体查询伪装），随该标签所有导航生效 */
    private fun installDesktopScript(enabled: Boolean) {
        runCatching { desktopHandler?.remove() }
        desktopHandler = null
        if (!enabled) return
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            val script = InjectAssets.read(appContext, "mafari_desktop.js") ?: return@runCatching
            desktopHandler = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }.onFailure { Diag.log("desktop script install failed: ${it.message}") }
    }

    fun toggleDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        if (isGecko) {
            applyUserAgent()
            gecko?.reload()
            return
        }
        runCatching {
            webView.settings.apply {
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                // 桌面模式关掉文字自动放大，保持桌面排版；退出后恢复
                layoutAlgorithm = if (enabled) {
                    WebSettings.LayoutAlgorithm.NORMAL
                } else {
                    WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                }
            }
        }
        installDesktopScript(enabled)
        applyUserAgent()
        val target = webView.url ?: url
        if (target.isNotBlank() && !target.startsWith("about:")) {
            fpInjector?.prepare(target)
            webView.loadUrl(target)
        } else {
            webView.reload()
        }
    }

    /** 页内查找 */
    fun findAll(text: String) {
        if (isGecko) { gecko?.let { if (text.isBlank()) it.clearFind() else it.find(text) }; return }
        if (text.isBlank()) webView.clearMatches() else webView.findAllAsync(text)
    }

    private var lastThumbAt = 0L

    /** 当前这次截图开始的时间（uptime），0 = 没有进行中的截图；Gecko 异步截图超过 3 秒没回调就当丢失 */
    private var capturingSince = 0L

    /** 缩略图对应的页面状态：地址、滚动位置都没变且已有缩略图时，不重复截图 */
    private var thumbUrl = ""
    private var thumbScrollY = -1
    private var pendingThumbUrl = ""
    private var pendingThumbScrollY = -1

    /**
     * 按需截取标签缩略图（主线程发起）：只在「切走这个标签」和「打开标签页面板」时调用，
     * 页面加载完成不再自动截图。1 秒内重复调用、上一张还没处理完、页面和滚动位置都没变，都直接跳过。
     */
    fun captureThumbnailNow() {
        if (closed || destroyed || parked) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastThumbAt < 1000L) return
        if (capturingSince != 0L && now - capturingSince < 3000L) return
        val scrollY = scrollOffsetY
        if (thumbnail != null && thumbUrl == url && thumbScrollY == scrollY && !loading) return
        lastThumbAt = now
        pendingThumbUrl = url
        pendingThumbScrollY = scrollY
        if (isGecko) captureGeckoThumbnail() else captureThumbnail(webView)
    }

    /**
     * 截取当前页面作为标签缩略图。WebView 的 View.draw 只能在主线程，所以这里直接画到最终大小的小图
     * （最长 [TabThumbs.MAX_W]×[TabThumbs.MAX_H]、RGB_565），压缩 / 落盘仍在后台。
     */
    private fun captureThumbnail(view: WebView) {
        if (closed || destroyed || url.isBlank() || url == "about:blank") return
        runCatching {
            if (view.width <= 0 || view.height <= 0) return
            if (!TabThumbs.tryBegin(this)) return
            val scale = minOf(TabThumbs.MAX_W.toFloat() / view.width, TabThumbs.MAX_H.toFloat() / view.height, 1f)
            val bitmap = android.graphics.Bitmap.createBitmap(
                (view.width * scale).toInt().coerceAtLeast(1),
                (view.height * scale).toInt().coerceAtLeast(1),
                android.graphics.Bitmap.Config.RGB_565,
            )
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(scale, scale)
            view.draw(canvas)
            capturingSince = android.os.SystemClock.uptimeMillis()
            TabThumbs.submit(this, bitmap, ownsSource = false)
        }.onFailure { TabThumbs.end(this) }
    }

    /** [TabThumbs] 回到主线程：一次截图处理结束（成功或失败） */
    internal fun thumbCaptureDone() {
        capturingSince = 0L
        TabThumbs.end(this)
    }

    /** 新截的缩略图 */
    internal fun applyThumbnail(bitmap: Bitmap) {
        thumbnail = bitmap.asImageBitmap()
        thumbUrl = pendingThumbUrl
        thumbScrollY = pendingThumbScrollY
    }

    /** 从库里读回的缩略图（不代表当前页面状态，所以不更新 thumbUrl，下次切走时仍会重新截图） */
    internal fun applyThumbnailFromDb(bitmap: Bitmap) {
        thumbnail = bitmap.asImageBitmap()
    }

    /** 释放内存里的缩略图（LRU 淘汰 / 标签关闭）；普通标签的图还在库里，卡片再显示时会读回 */
    internal fun dropThumbnail() {
        thumbnail = null
        thumbUrl = ""
        thumbScrollY = -1
    }

    fun findNext(forward: Boolean) {
        if (isGecko) { gecko?.findNext(forward); return }
        runCatching { webView.findNext(forward) }
    }

    fun clearFind() {
        if (isGecko) { gecko?.clearFind(); return }
        runCatching { webView.clearMatches() }
    }

    /** 回主页 */
    private var savedUrl: String = ""

    // ---------------------------------------------------------------- 从 tabs 表恢复（见 TabDb / BrowserState.restoreSavedTabs）
    //
    // 恢复出来的标签先是「被主页盖住、页面还没加载」的状态：地址记在 savedUrl，不碰网络。
    // 它成为当前标签时（[wakeIfPending]）、或用户从主页点回它的页面时（[restoreFromHome]）才真正加载，
    // 所以重启后不会同时把所有标签的页面都拉起来。

    private var pendingRestore = false
    private var pendingOnHome = false
    private var pendingDesktop = false

    /** 库里有这个页面的 Gecko 会话状态（前进后退栈 + 滚动位置）：唤醒时用 restoreState 而不是只加载地址 */
    private var pendingHasState = false

    /**
     * 正在用库里的状态恢复页面：从 [restoreWithState] 开始，到引擎报出第一个非空白地址为止。
     * 这段时间引擎里的会话状态还是空白的，不能把它存回库里（见 [persistSessionState]）。
     */
    private var stateRestorePending = false

    /** 每次用户 / 系统发起新导航（[load] / [loadUrl]）加一：异步恢复回来时据此判断自己是否已过期 */
    private var navEpoch = 0

    /** 标签的页面还没加载、只是用保存的地址 / 标题占位（卡片上要显示这个页面的域名，而不是「主页」） */
    internal val showsSavedPage: Boolean get() = pendingRestore && !pendingOnHome

    /** 要保存的桌面模式状态：还没加载的恢复标签也保留这个标志 */
    internal val wantsDesktop: Boolean get() = desktopMode || pendingDesktop

    internal fun prepareRestore(
        target: String,
        savedTitle: String,
        onHome: Boolean,
        desktop: Boolean,
        fromLink: Boolean,
        hasState: Boolean = false,
    ) {
        openedFromLink = fromLink
        if (target.isBlank()) return // 空白标签：就是一个主页标签，没有页面可恢复
        savedUrl = target
        if (savedTitle.isNotBlank()) title = savedTitle
        parked = true
        url = ""
        pendingRestore = true
        pendingOnHome = onHome
        pendingDesktop = desktop
        pendingHasState = hasState && isGecko
    }

    /** 页面被新的导航替掉：旧的「待恢复」作废（否则之后从主页点回来会把旧地址又加载一遍） */
    private fun dropPendingRestore() {
        navEpoch++
        pendingRestore = false
        pendingHasState = false
    }

    /** 成为当前标签时调用：该显示页面的恢复标签此刻才加载 */
    internal fun wakeIfPending() {
        if (pendingRestore && !pendingOnHome) loadPending()
    }

    private fun loadPending() {
        val target = savedUrl
        val withState = pendingHasState
        pendingRestore = false
        pendingHasState = false
        if (pendingDesktop) {
            pendingDesktop = false
            desktopMode = true
            if (!isGecko) {
                runCatching {
                    webView.settings.apply {
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                    }
                }
                installDesktopScript(true)
            }
            applyUserAgent()
        }
        if (target.isBlank()) return
        if (withState && isGecko) restoreWithState(target) else load(target)
    }

    /**
     * 用库里保存的 Gecko 会话状态恢复页面：前进后退栈、滚动位置都回来。
     * 引擎此刻同步建好（拓展 / 界面马上要用它的 session），状态从库里异步读，读不到或不匹配就按地址正常加载。
     * 等待期间用户又自己导航了（[navEpoch] 变了）就放弃恢复，不拿旧状态覆盖新页面。
     */
    private fun restoreWithState(target: String) {
        // 与 load() 从主页进入页面的约定一致：这一页是从主页进来的，回退栈到头再返回就回主页
        fromHome = !openedFromLink
        homeAnchor = -1
        stateRestorePending = true
        savedUrl = ""
        parked = false
        url = target
        errorText = null
        loading = true
        syncNav()
        val g = ensureGecko()
        val db = HistoryDb.get(appContext)
        if (g == null || db == null) {
            engineLoad(target)
            return
        }
        val epoch = navEpoch
        val key = dbKey
        HistoryDb.io.execute {
            val saved = runCatching { TabDb.loadState(db, key) }
                .onFailure { Diag.log("tabs: load state failed: ${it.message}") }
                .getOrNull()
            mainHandler.post {
                if (closed || destroyed || epoch != navEpoch) return@post
                val engine = geckoEngine ?: return@post
                val restored = saved != null && engine.restoreState(saved)
                if (!restored) {
                    Diag.log("tabs: state restore unavailable, loading url")
                    engineLoad(target)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 后台回收 / Gecko 会话状态持久化
    //
    // 后台标签闲置太久（或活着的引擎太多）时，[discardEngine] 把它的 GeckoSession 连同 GeckoView 一起销毁，
    // 标签变回和「重启后恢复」一样的「待恢复」状态（[pendingRestore]）：卡片、标题、缩略图都还在，
    // 再次切回去时由 [loadPending] 用保存的 [GeckoSession.SessionState] 恢复（前进后退栈、滚动位置）。
    // 会话状态存在 tabs 表的 state 列（见 [TabDb]），重启后恢复走的是同一条路径。

    /** 页面地址里落盘的那一份：about: 开头的不存（空白页） */
    internal val persistedPageUrl: String
        get() = effectiveUrl.let { if (it.startsWith("about:")) "" else it }

    /** Gecko 引擎当前是否存活 */
    internal val hasEngine: Boolean get() = geckoEngine != null

    /**
     * 能不能回收引擎：只回收「普通、Gecko 自建 session、已有页面、没在加载、没在全屏」的后台标签。
     * 无痕标签的状态不能落盘，回收就丢页面，所以永不回收；拓展 tabs.create 的 session 由拓展持有，也不回收。
     * （是不是当前标签由调用方判断。）
     */
    internal fun canDiscardEngine(): Boolean {
        val g = geckoEngine ?: return false
        if (closed || destroyed || incognito || startIncognito || adoptedGeckoSession != null) return false
        if (loading || g.inFullScreen) return false
        return persistedPageUrl.isNotBlank()
    }

    /**
     * 回收引擎（主线程）：先把会话状态写进库，再销毁 session / view，标签变回「待恢复」。
     * 写库失败（库不可用）就不回收，宁可多占内存也不丢页面。
     */
    internal fun discardEngine(): Boolean {
        if (!canDiscardEngine()) return false
        val g = geckoEngine ?: return false
        val db = HistoryDb.get(appContext) ?: return false
        val page = persistedPageUrl
        // 页面还没加载进引擎（待恢复 / 正在恢复）时，引擎里的状态是空白的，库里那份才是真的：不能用它覆盖
        val dbStateAuthoritative = pendingRestore || stateRestorePending
        val state = if (dbStateAuthoritative) null else g.serializedState()
        if (state != null) {
            val key = dbKey
            HistoryDb.io.execute {
                runCatching { TabDb.saveState(db, key, state, page) }
                    .onFailure { Diag.log("tabs: save state failed: ${it.message}") }
            }
        }
        runCatching { (g.view.parent as? ViewGroup)?.removeView(g.view) }
        runCatching { g.destroy() }
        geckoEngine = null
        // 变回「待恢复」：回过主页的（页面被盖住）仍是被盖住；正显示页面的退到「卡片显示域名」的状态
        val covered = url.isEmpty()
        if (!covered) {
            savedUrl = url
            url = ""
            parked = true
        }
        pendingHasState = if (pendingRestore) pendingHasState else (stateRestorePending || state != null)
        pendingRestore = true
        pendingOnHome = covered
        loading = false
        errorText = null
        syncNav()
        Diag.log("tabs: engine discarded (${if (state != null) "with" else "without"} state) $page")
        return true
    }

    /** 把有变化的会话状态写进库（退到后台时由 [BrowserState.persistSessionStates] 统一调用）；无痕标签不落盘 */
    internal fun persistSessionState() {
        if (incognito || startIncognito || closed || destroyed) return
        val g = geckoEngine ?: return
        val page = persistedPageUrl
        if (page.isBlank()) return
        // 待恢复 / 正在恢复：引擎里还是空白状态，库里那份才是真的，不能覆盖（见 discardEngine）
        if (pendingRestore || stateRestorePending) return
        val state = g.takeDirtyState() ?: return
        val db = HistoryDb.get(appContext) ?: return
        val key = dbKey
        HistoryDb.io.execute {
            runCatching { TabDb.saveState(db, key, state, page) }
                .onFailure { Diag.log("tabs: save state failed: ${it.message}") }
        }
    }

    /** 标签实际承载的页面地址：回过主页的标签 url 为空，页面还在 WebView 里，用 savedUrl */
    val effectiveUrl: String
        get() = url.ifEmpty { savedUrl }

    /** 完全空白的标签（没有页面、也没有被主页盖住的页面） */
    val isBlank: Boolean
        get() = url.isEmpty() && savedUrl.isBlank()

    /** 若这个标签正显示主页（页面被盖住），还原成原来的页面，不重新加载 */
    fun restoreFromHome() {
        // 恢复出来、页面还没加载的标签：这时才真正加载
        if (pendingRestore) {
            loadPending()
            return
        }
        if (url.isEmpty() && savedUrl.isNotBlank()) {
            // 页面在主页下面可能又自己跳转过，以 WebView 当前地址为准
            url = engineUrl().takeIf { it.isNotBlank() } ?: savedUrl
            savedUrl = ""
            parked = false
            syncNav()
        }
    }

    private fun historyIndex(): Int =
        if (isGecko) gecko?.historyIndex ?: -1 else runCatching { webView.copyBackForwardList().currentIndex }.getOrDefault(-1)

    fun goHome() {
        // 已经在主页：不能再覆盖 savedUrl，否则被盖住的页面就丢了
        if (url.isEmpty()) return
        savedUrl = url
        fromHome = false
        parked = true
        url = ""
        errorText = null
        loading = false
        syncNav()
    }

    fun goBack() {
        // Gecko 视频全屏：返回键先退出全屏
        val engine = gecko
        if (engine != null && engine.inFullScreen) {
            engine.exitFullScreen()
            return
        }
        // 从主页返回：恢复刚才那页（WebView 没销毁，直接还原状态即可）
        if (url.isEmpty()) {
            restoreFromHome()
            return
        }
        // 回到「从主页进入」的第一页：再返回就是主页，而不是继续翻进入主页之前的旧历史
        Diag.log("nav: goBack fromHome=$fromHome idx=${historyIndex()} anchor=$homeAnchor engineCanGoBack=${engineCanGoBack()} url=$url")
        // 下标可能还停在上一页（见 GeckoTabEngine.historyStale）：这时只信 canGoBack，不用下标去判断「已在第一页」
        val idxStale = gecko?.historyStale == true
        val atFirstPage = fromHome && when {
            isGecko -> !engineCanGoBack() && !idxStale
            else -> !engineCanGoBack() || historyIndex() <= homeAnchor + 1
        }
        if (atFirstPage) {
            engineStop()
            fromHome = false
            parked = true
            url = ""
            errorText = null
            loading = false
            syncNav()
            return
        }
        // 刚提交新页面、canGoBack 还没跟上时也先尝试后退：真在第一页时是空操作，不会误把标签收回主页
        if (engineCanGoBack() || (fromHome && idxStale)) {
            fpInjector?.prepareHistory(-1)
            if (isGecko) gecko?.goBack() else webView.goBack()
        } else if (openedFromLink) {
            // 链接打开的标签已经在第一页：关掉它，回到来源标签（而不是先落到本标签的主页）
            onRequestClose?.invoke()
        }
    }

    fun goForward() {
        if (url.isEmpty()) return
        if (engineCanGoForward()) {
            fpInjector?.prepareHistory(1)
            if (isGecko) gecko?.goForward() else webView.goForward()
        }
    }

    fun destroyNow() {
        if (destroyed) return
        destroyed = true
        cancelFileChooser()
        exitCustomView()
        discardAllBlobSessions()
        runCatching { fpInjector?.clear() }
        if (isGecko) {
            // 引擎从没建过（延迟创建的后台标签）就没有东西要销毁
            geckoEngine?.let { g ->
                runCatching { (g.view.parent as? ViewGroup)?.removeView(g.view) }
                runCatching { g.destroy() }
            }
            geckoEngine = null
            return
        }
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
    }

    /** 关闭标签：等界面把 WebView 从视图树摘下来后再销毁。 */
    fun close() {
        closed = true
        TabThumbs.forget(this)
        // 没有引擎的 Gecko 标签（延迟创建 / 已回收）直接收尾；不能读 contentView，否则为了关它还得先建一个引擎
        if (isGecko && geckoEngine == null) {
            destroyNow()
            return
        }
        contentView.post { if (contentView.parent == null) destroyNow() }
    }

    private companion object {
        private var counter = 0

        fun nextId(): Int {
            counter += 1
            return counter
        }
    }
}

/** 多标签状态。 */
class BrowserState(private val context: Context, initialUserAgent: String = "") : GeckoExtTabHost {

    val tabs = mutableStateListOf<BrowserTab>()

    /** 全局 UA，新标签也用它 */
    var userAgent: String = initialUserAgent

    var activeIndex by mutableIntStateOf(0)
        private set

    /** 最近激活过的标签 id（越靠后越新）：关闭当前标签时，没有 opener 就回到「上一个用过的」 */
    private val recentIds = mutableListOf<Int>()

    val activeTab: BrowserTab
        get() = tabs[activeIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))]

    // 下面两个必须声明在 init 之前：init 里就要用
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 周期性回收闲置的后台 Gecko 引擎；旧实例（界面重建后被新 BrowserState 取代）自己停掉 */
    private val reclaimTick = object : Runnable {
        override fun run() {
            if (current !== this@BrowserState) return
            reclaimEngines()
            mainHandler.postDelayed(this, RECLAIM_INTERVAL_MS)
        }
    }

    init {
        // 拓展（油猴等）通过 tabs.create / tabs.update / tabs.remove 操作浏览器标签时的回调入口
        if (EngineSupport.GECKO_BUNDLED) GeckoExtensionStore.tabHost = this
        current = this
        // 上次的普通标签（tabs 表）一律先恢复回来（页面不加载，见 restoreSavedTabs）；没有就开一个空白标签。
        // 「重启恢复标签页」关闭时，只在进程冷启动（本进程里第一个实例）改成「落在一个空白标签上」：
        // 之前打开的标签页仍然保留在标签列表里，只是启动后当前显示的是空白标签（已有空白标签就直接用它，不重复新建）。
        // 界面重建（同一进程里再建实例）不受影响，仍回到最近用的标签。
        val coldStart = !instanceCreated
        instanceCreated = true
        val landOnBlank = coldStart && !AppSettings(context).restoreTabs
        if (!restoreSavedTabs(activateLast = !landOnBlank)) {
            newTab()
        } else if (landOnBlank) {
            val blank = tabs.indexOfFirst { !it.incognito && it.persistedPageUrl.isBlank() }
            if (blank >= 0) activate(blank) else newTab(opener = null)
        }
        if (EngineSupport.GECKO_BUNDLED) mainHandler.postDelayed(reclaimTick, RECLAIM_INTERVAL_MS)
    }

    /**
     * 回收后台 Gecko 引擎（主线程）。当前标签永不回收；回收条件（见 [BrowserTab.canDiscardEngine]）之外还要满足之一：
     *  - 闲置超过 [IDLE_RECLAIM_MS]；
     *  - 活着的引擎超过上限（[MAX_LIVE_ENGINES]，[aggressive] 时收紧到 [TRIM_LIVE_ENGINES]），
     *    按「最久没看」的先收，且至少闲置过一小段时间，刚切走的不会被立刻收掉。
     * 系统正在放音乐时整轮跳过：后台标签可能就是声源，目前没有按标签判断的办法。
     */
    internal fun reclaimEngines(aggressive: Boolean = false) {
        if (!EngineSupport.GECKO_BUNDLED) return
        val live = tabs.filter { it.hasEngine }
        if (live.size <= 1) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audio?.isMusicActive == true) return
        val now = android.os.SystemClock.uptimeMillis()
        val activeNow = tabs.getOrNull(activeIndex)
        val maxLive = if (aggressive) TRIM_LIVE_ENGINES else MAX_LIVE_ENGINES
        val minIdleOverCap = if (aggressive) 15_000L else 60_000L
        var liveCount = live.size
        val candidates = live
            .filter { it !== activeNow && it.canDiscardEngine() }
            .sortedBy { it.lastActiveUptime }
        for (t in candidates) {
            val idle = now - t.lastActiveUptime
            val overCap = liveCount > maxLive && idle >= minIdleOverCap
            if ((overCap || idle >= IDLE_RECLAIM_MS) && t.discardEngine()) liveCount--
        }
    }

    /** 把有变化的 Gecko 会话状态写进库（前进后退栈 + 滚动位置，重启 / 回收后恢复用）。主线程。 */
    internal fun persistSessionStates() {
        tabs.forEach { it.persistSessionState() }
    }

    /**
     * 从 tabs 表恢复上次的普通标签：只建标签对象，页面不加载；回到最近用过的那个标签并只加载它，
     * 其余标签在被切到前台时才加载（见 [BrowserTab.wakeIfPending]）。没有可恢复的返回 false。
     */
    private fun restoreSavedTabs(activateLast: Boolean = true): Boolean {
        val db = HistoryDb.get(context) ?: return false
        val rows = runCatching { TabDb.load(db) }
            .onFailure { Diag.log("tabs: load failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (rows.isEmpty()) return false
        val byKey = HashMap<Long, BrowserTab>()
        for (row in rows) {
            if (tabs.size >= MAX_TABS) break
            // 恢复出来的标签不建 Gecko 引擎：只有成为当前标签 / 真正要加载页面时才创建（见 BrowserTab.ensureGecko）
            val tab = runCatching { createTab(incognito = false, opener = null, lazyEngine = true) }
                .onFailure { Diag.log("tabs: restore tab failed: ${it.message}") }
                .getOrNull() ?: continue
            tab.dbKey = row.key
            tab.lastUsedAt = row.lastUsed
            tab.prepareRestore(row.url, row.title, row.onHome, row.desktop, row.fromLink, row.hasState)
            tabs.add(tab)
            byKey[row.key] = tab
        }
        if (tabs.isEmpty()) return false
        for (row in rows) {
            val tab = byKey[row.key] ?: continue
            tab.openerId = byKey[row.openerKey]?.id ?: -1
        }
        tabs.sortedBy { it.lastUsedAt }.forEach { recentIds.add(it.id) }
        // [activateLast] 为 false（关闭「重启恢复标签页」）时不切到最近的标签，免得白白加载一遍它的页面；由调用方落到空白标签
        if (activateLast) {
            val last = tabs.maxByOrNull { it.lastUsedAt } ?: tabs.last()
            activate(tabs.indexOf(last).coerceAtLeast(0))
        }
        Diag.log("tabs: restored ${tabs.size} tabs")
        return true
    }

    /** 要落盘的标签列表（只含普通标签）。读取的都是 Compose 状态，可放进 snapshotFlow 里，变化时才触发保存。 */
    internal fun tabRows(): List<TabRow> {
        val byId = HashMap<Int, BrowserTab>(tabs.size * 2)
        tabs.forEach { byId[it.id] = it }
        val out = ArrayList<TabRow>(tabs.size)
        for (t in tabs) {
            if (t.incognito || t.closed) continue
            val page = t.persistedPageUrl
            val opener = byId[t.openerId]?.takeIf { !it.incognito }
            out.add(
                TabRow(
                    key = t.dbKey,
                    pos = out.size,
                    url = page,
                    title = t.title,
                    onHome = t.url.isEmpty() && page.isNotBlank() && !t.showsSavedPage,
                    desktop = t.wantsDesktop,
                    openerKey = opener?.dbKey ?: 0L,
                    fromLink = t.openedFromLink,
                    lastUsed = t.lastUsedAt,
                ),
            )
        }
        return out
    }

    /** 把标签列表写进 tabs 表（快照在主线程取，写入在 [HistoryDb.io] 上单事务执行）。 */
    internal fun saveTabs(rows: List<TabRow> = tabRows()) {
        val db = HistoryDb.get(context) ?: return
        HistoryDb.io.execute {
            runCatching { TabDb.sync(db, rows) }.onFailure { Diag.log("tabs: save failed: ${it.message}") }
        }
    }

    /** 唯一的切换入口：同时维护最近使用顺序 */
    private fun activate(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        val id = tabs[index].id
        tabs[index].lastUsedAt = System.currentTimeMillis()
        recentIds.remove(id)
        recentIds.add(id)
        // 当前标签一定有引擎（界面马上要挂它的 view、拓展要用它的 session）；延迟创建 / 被回收的后台标签保持没有
        tabs[index].ensureGecko()
        // Gecko 会话只有「当前标签」保持活动，后台标签停止渲染
        tabs.forEachIndexed { i, t -> t.setEngineActive(i == index) }
        // 从 tabs 表恢复出来的标签，成为当前标签时才真正加载页面
        tabs[index].wakeIfPending()
        // 告诉拓展哪个是当前标签：tabs.query({active:true}) / 安装页等都依赖它，不能只在打开拓展菜单时才登记
        if (EngineSupport.GECKO_BUNDLED) {
            GeckoExtensionStore.activeSession = tabs[index].geckoSession
            GeckoExtensionStore.markActiveTab()
        }
    }

    // ------------------------------------------------ GeckoExtTabHost：拓展对浏览器标签的操作

    private fun indexOfGeckoSession(session: org.mozilla.geckoview.GeckoSession): Int =
        tabs.indexOfFirst { it.geckoSession === session }

    /** tabs.create：用 Gecko 给拓展的 session 开一个真正的浏览器标签（而不是返回 null 让拓展以为失败） */
    override fun openExtensionTab(url: String, active: Boolean, session: org.mozilla.geckoview.GeckoSession): Boolean {
        if (tabs.size >= MAX_TABS) return false
        if (readBrowserEngine(context) != ENGINE_GECKO || !EngineSupport.geckoAvailable()) return false
        val opener = tabs.getOrNull(activeIndex)
        val tab = runCatching { createTab(false, opener, session) }
            .onFailure { Diag.log("ext: create extension tab failed: $it") }
            .getOrNull() ?: return false
        if (!tab.isGecko) {
            tab.close()
            return false
        }
        // 拓展页没有「主页」可回：第一页返回直接关闭并回到来源标签
        tab.openedFromLink = opener != null
        if (active) tabs.getOrNull(activeIndex)?.captureThumbnailNow()
        tabs.add(tab)
        if (active) activate(tabs.lastIndex) else tab.setEngineActive(false)
        tab.adoptExtensionUrl(url)
        Diag.log("ext: extension tab opened url=$url active=$active tabs=${tabs.size}")
        return true
    }

    /** tabs.remove / 安装完成后拓展自己关闭安装页 */
    override fun closeExtensionTab(session: org.mozilla.geckoview.GeckoSession): Boolean {
        val i = indexOfGeckoSession(session)
        if (i < 0) return false
        closeTab(i)
        return true
    }

    /** tabs.update({active:true}) */
    override fun activateExtensionTab(session: org.mozilla.geckoview.GeckoSession): Boolean {
        val i = indexOfGeckoSession(session)
        if (i < 0) return false
        tabs.getOrNull(activeIndex)?.captureThumbnailNow()
        activate(i)
        return true
    }

    /** tabs.update({url}) */
    override fun loadInExtensionTab(session: org.mozilla.geckoview.GeckoSession, url: String): Boolean {
        val i = indexOfGeckoSession(session)
        if (i < 0) return false
        tabs[i].load(url)
        return true
    }

    private fun createTab(
        incognito: Boolean,
        opener: BrowserTab?,
        adoptedGeckoSession: org.mozilla.geckoview.GeckoSession? = null,
        lazyEngine: Boolean = false,
    ): BrowserTab {
        val wantGecko = readBrowserEngine(context) == ENGINE_GECKO && EngineSupport.geckoAvailable()
        val tab = (if (wantGecko) {
            runCatching {
                // 延迟创建的标签构造时不碰 Gecko，所以先在这里把运行时拉起来：启动失败才能照旧退回 WebView
                if (lazyEngine) GeckoRuntimeHolder.get(context)
                BrowserTab(context, EngineKind.GECKO, incognito, adoptedGeckoSession, lazyEngine)
            }
                .onFailure {
                    Diag.log("gecko tab create failed, fallback to WebView: ${it.message}")
                    if (adoptedGeckoSession == null) {
                        android.widget.Toast.makeText(context, "Gecko 内核启动失败，已改用 WebView", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .getOrNull()
        } else {
            null
        }) ?: run {
            // 拓展 session 只能由 Gecko 承载：Gecko 标签没建成就不能退回 WebView
            if (adoptedGeckoSession != null) error("gecko tab for extension session unavailable")
            BrowserTab(context, EngineKind.WEBVIEW, incognito)
        }
        tab.customUserAgent = userAgent
        tab.applyUserAgent()
        tab.applyIncognito(incognito)
        tab.openerId = opener?.id ?: -1
        // 页面自己要开的新标签（window.open / 允许的重定向 / GM_openInTab）以它为 opener
        tab.onOpenInNewTab = { u -> newTab(u, tab.incognito, tab, fromLink = true) }
        tab.onRequestClose = { closeTab(tabs.indexOf(tab)) }
        tab.onGmOpenTab = { id, u, active ->
            val opened = openTabInternal(u, tab.incognito, tab, fromLink = active, makeActive = active)
            if (opened != null) gmTabs[id] = opened
        }
        tab.onGmCloseTab = { id ->
            gmTabs.remove(id)?.let { t ->
                val i = tabs.indexOf(t)
                if (i >= 0) closeTab(i)
            }
        }
        return tab
    }

    /** 「自定义起始页」选了「指定页面」且填了有效网址时返回该网址，否则 null */
    private fun customHomeUrl(): String? {
        if (readHomeMode(context) != HOME_MODE_CUSTOM) return null
        val raw = readHomeCustomUrl(context).trim()
        return if (raw.isEmpty()) null else toUrl(raw)
    }

    /**
     * 点击主页按钮（底栏 / 更多菜单）：按「定制主页 → 自定义起始页」处理。
     * 默认主页：回到内置主页；新建标签页：新建并切到一个新标签页；指定页面：当前标签打开该页面。
     */
    fun goHome(tab: BrowserTab = activeTab) {
        when (readHomeMode(context)) {
            HOME_MODE_NEW_TAB -> newTab(incognito = tab.incognito, opener = tab)
            HOME_MODE_CUSTOM -> {
                val target = customHomeUrl()
                if (target == null) tab.goHome()
                else if (tab.url.trimEnd('/') != target.trimEnd('/')) tab.load(target)
            }
            else -> tab.goHome()
        }
    }

    /**
     * 新建标签页。[opener] 是打开它的标签（默认当前标签）：关闭新标签时会优先回到 opener。
     * 当前已经是同模式的空白标签时，「新建空白标签」直接沿用它，不再叠出一张空卡片。
     */
    fun newTab(
        target: String = "",
        incognito: Boolean = false,
        opener: BrowserTab? = tabs.getOrNull(activeIndex),
        /** 由页面里的链接打开：第一页返回直接关闭并回到 opener，不经过本标签的主页 */
        fromLink: Boolean = false,
    ) {
        openTabInternal(target, incognito, opener, fromLink, makeActive = true)
    }

    /** [newTab] 的实现；[makeActive] = false 为后台打开（GM_openInTab({ active: false })），返回新标签（没建成返回 null） */
    private fun openTabInternal(
        target: String,
        incognito: Boolean,
        opener: BrowserTab?,
        fromLink: Boolean,
        makeActive: Boolean,
    ): BrowserTab? {
        // 新建空白标签时，若设置了「指定页面」就打开它（无痕标签保持空白）
        val loadTarget = if (target.isEmpty() && !incognito) customHomeUrl().orEmpty() else target
        if (loadTarget.isEmpty()) {
            val cur = tabs.getOrNull(activeIndex)
            if (cur != null && cur.isBlank && cur.incognito == incognito) return null
        }
        if (tabs.size >= MAX_TABS) {
            android.widget.Toast.makeText(
                context,
                "最多同时打开 $MAX_TABS 个标签页",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return null
        }
        // 切走前给当前标签拍一张缩略图
        if (makeActive) tabs.getOrNull(activeIndex)?.captureThumbnailNow()
        val tab = createTab(incognito, opener)
        // 翻译 / 拓展选项页 / 外部链接等没有标 fromLink 的入口也一样：有来源标签、有目标地址，返回就该直接回来源
        tab.openedFromLink = (fromLink || makeActive) && opener != null && target.isNotEmpty()
        tabs.add(tab)
        if (makeActive) activate(tabs.lastIndex)
        if (loadTarget.isNotEmpty()) tab.load(loadTarget)
        return tab
    }

    /** GM_openInTab 返回的 id → 标签；仅主线程访问，标签被移除时一并清掉 */
    private val gmTabs = HashMap<String, BrowserTab>()

    /** 站点标识：小写 host，忽略开头的 www. */
    private fun siteKey(url: String): String? {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
        if (host.isNullOrBlank()) return null
        return host.removePrefix("www.")
    }

    /** 是否站点根地址（无路径/查询/片段） */
    private fun isSiteRoot(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return true
        val path = u.path.orEmpty()
        return (path.isEmpty() || path == "/") && u.query.isNullOrEmpty() && u.fragment.isNullOrEmpty()
    }

    /** 找同站点、同无痕状态的已有标签（含回过主页、页面被盖住的标签） */
    private fun findSiteTab(target: String, incognito: Boolean): Int {
        if (!looksLikeUrl(target)) return -1
        val key = siteKey(toUrl(target)) ?: return -1
        return tabs.indexOfFirst { tab ->
            tab.incognito == incognito && siteKey(tab.effectiveUrl) == key
        }
    }

    /**
     * 从主页 / 外部 / 应用内打开网址：
     * 1. 已有同站标签 → 直接切过去显示它原来的页面，不新建、不重新加载；
     *    仅当 [navigateIfDeepLink] 为 true 且目标是带具体路径的深链、且与该标签当前页不同，才在该标签里跳转。
     * 2. 没有同站标签 → 当前是空白/主页就地加载，否则新开标签。
     * 切走后遗留的空白标签会被清掉，避免多出一张空卡片。
     */
    fun openOrReuse(
        target: String,
        incognito: Boolean = activeTab.incognito,
        navigateIfDeepLink: Boolean = false,
        fromStartPage: Boolean = false,
    ) {
        if (target.isBlank()) return
        val idx = findSiteTab(target, incognito)
        if (idx >= 0) {
            val existing = tabs[idx]
            val blank = activeTab.takeIf { it.isBlank && it !== existing && tabs.size > 1 }
            existing.restoreFromHome()
            val url = toUrl(target)
            if (navigateIfDeepLink && !isSiteRoot(url) &&
                !existing.effectiveUrl.equals(url, ignoreCase = true)
            ) {
                existing.load(url)
            }
            if (blank != null) detach(blank)
            activate(tabs.indexOf(existing).coerceAtLeast(0))
            return
        }
        val cur = activeTab
        if (cur.incognito == incognito && cur.url.isEmpty() && (fromStartPage || cur.isBlank)) {
            cur.load(target)
        } else {
            newTab(target, incognito)
        }
    }

    /** 切换当前标签无痕状态（无痕时新开标签，已有无痕则关闭回普通） */
    fun toggleIncognito() {
        if (activeTab.incognito) {
            // 回到普通：关闭当前无痕标签，回到最近用过的普通标签；没有就新建一个
            closeTab(activeIndex)
            val normal = lastUsed(incognito = false)
            if (normal == null) {
                newTab(incognito = false)
            } else {
                activate(tabs.indexOf(normal))
            }
        } else {
            newTab(incognito = true)
        }
    }

    /** 指定模式里最近用过的标签 */
    private fun lastUsed(incognito: Boolean): BrowserTab? {
        for (id in recentIds.asReversed()) {
            val t = tabs.firstOrNull { it.id == id }
            if (t != null && t.incognito == incognito) return t
        }
        return tabs.lastOrNull { it.incognito == incognito }
    }

    fun applyUserAgent(ua: String) {
        userAgent = ua
        tabs.forEach {
            it.customUserAgent = ua
            it.applyUserAgent()
        }
    }

    fun select(index: Int) {
        // 切走前给当前标签拍一张缩略图
        if (index != activeIndex) {
            tabs.getOrNull(activeIndex)?.let {
                it.captureThumbnailNow()
                // 手动切走后，关闭它就按最近使用顺序回退，不再强行跳回当初的来源标签
                it.openerId = -1
            }
        }
        activate(index)
    }

    /** 从列表里摘掉一个标签（不负责挑选下一个当前标签） */
    private fun detach(tab: BrowserTab) {
        tabs.remove(tab)
        gmTabs.values.removeAll { it === tab }
        recentIds.remove(tab.id)
        // 以它为 opener 的标签改挂到它的 opener 上，层级链不断
        tabs.forEach { if (it.openerId == tab.id) it.openerId = tab.openerId }
        tab.close()
    }

    /**
     * 关闭当前标签后回到哪一个：
     * 1. opener 链（打开它的标签，其次是打开 opener 的标签……）；
     * 2. 最近用过的标签；
     * 3. 列表里相邻的标签。
     * 候选里优先同模式（无痕 / 普通）的，避免关掉无痕页莫名落到普通页。
     */
    private fun pickSuccessor(closing: BrowserTab): BrowserTab? {
        val others = tabs.filter { it !== closing }
        if (others.isEmpty()) return null
        val candidates = mutableListOf<BrowserTab>()
        val seen = HashSet<Int>()
        var oid = closing.openerId
        while (oid != -1 && seen.add(oid)) {
            val parent = others.firstOrNull { it.id == oid } ?: break
            candidates.add(parent)
            oid = parent.openerId
        }
        for (id in recentIds.asReversed()) {
            others.firstOrNull { it.id == id }?.let { candidates.add(it) }
        }
        val i = tabs.indexOf(closing)
        tabs.getOrNull(i + 1)?.let { candidates.add(it) }
        tabs.getOrNull(i - 1)?.let { candidates.add(it) }
        candidates.addAll(others)
        return candidates.firstOrNull { it.incognito == closing.incognito } ?: candidates.first()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val closing = tabs[index]
        val activeBefore = tabs[activeIndex.coerceIn(0, tabs.lastIndex)]
        val wasActive = activeBefore === closing
        val successor = if (wasActive) pickSuccessor(closing) else activeBefore
        detach(closing)
        if (tabs.isEmpty()) {
            newTab()
            return
        }
        // 关掉的是后台标签时当前标签不能变；关掉当前标签才按层级回退
        val target = successor?.let { tabs.indexOf(it) } ?: -1
        if (wasActive) {
            activate(if (target >= 0) target else index.coerceAtMost(tabs.lastIndex))
        } else if (target >= 0) {
            activeIndex = target
        }
    }


    /**
     * 清空某一栏的标签：[incognito] 为 true 清无痕栏，false 清普通栏，另一栏不受影响。
     * 当前标签被清掉时：清无痕栏且还有普通标签 → 回到最近用过的普通标签；
     * 否则在被清空的那一栏新建一个空白标签。
     */
    fun closeAllOf(incognito: Boolean) {
        val removed = tabs.filter { it.incognito == incognito }
        if (removed.isEmpty()) return
        val keep = activeTab.takeIf { it.incognito != incognito }
        val fallback = if (incognito) lastUsed(incognito = false) else null
        removed.forEach { it.close() }
        tabs.removeAll(removed)
        recentIds.removeAll { id -> tabs.none { it.id == id } }
        when {
            keep != null -> activeIndex = tabs.indexOf(keep).coerceAtLeast(0)
            fallback != null && tabs.contains(fallback) -> activate(tabs.indexOf(fallback))
            else -> newTab(incognito = incognito)
        }
    }


    companion object {
        const val MAX_TABS = 99

        /** 后台引擎回收：检查周期 / 闲置多久必收 / 活着的引擎上限 / 内存吃紧时的上限 */
        private const val RECLAIM_INTERVAL_MS = 60_000L
        private const val IDLE_RECLAIM_MS = 20 * 60_000L
        private const val MAX_LIVE_ENGINES = 6
        private const val TRIM_LIVE_ENGINES = 2

        /** 本进程是否已经建过 BrowserState（用来区分冷启动和界面重建） */
        @Volatile
        private var instanceCreated = false

        /** 最近创建的实例：退到后台时由 MainActivity 通过它把标签列表立刻落盘。 */
        @Volatile
        var current: BrowserState? = null
            private set

        /** 退到后台时调用：不等延迟，立刻保存标签列表。主线程。 */
        fun flushCurrent() {
            val state = current ?: return
            state.saveTabs()
            state.persistSessionStates()
            state.reclaimEngines()
        }

        /** 系统内存吃紧：收紧后台引擎数量。主线程。 */
        fun trimCurrent() {
            current?.reclaimEngines(aggressive = true)
        }
    }
}


private const val MIN_FREE_BYTES = 64L * 1024 * 1024
private const val LARGE_BLOB_NOTICE_BYTES = 8L * 1024 * 1024

@Volatile
private var staleBlobFilesCleaned = false

/** 进程内第一次创建标签页时，清掉上次进程被杀 / 页面中途跳转遗留的 blob 缓存文件 */
private fun cleanStaleBlobFiles(context: Context) {
    if (staleBlobFilesCleaned) return
    staleBlobFilesCleaned = true
    runCatching {
        context.cacheDir.listFiles { f -> f.name.startsWith("mafari_blob_") }?.forEach { it.delete() }
    }
}
