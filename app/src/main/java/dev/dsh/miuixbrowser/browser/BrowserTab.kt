package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.AdBlockBridge
import dev.dsh.miuixbrowser.crypto.TrustedChannel
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.DESKTOP_UA
import dev.dsh.miuixbrowser.data.PrivacyStats
import dev.dsh.miuixbrowser.data.TabDb
import dev.dsh.miuixbrowser.data.TabThumbs
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.GeckoTabOps
import dev.dsh.miuixbrowser.engine.TabEngineOps
import dev.dsh.miuixbrowser.engine.WebViewTabOps
import dev.dsh.miuixbrowser.engine.gecko.GeckoGm
import dev.dsh.miuixbrowser.engine.gecko.GeckoReader
import dev.dsh.miuixbrowser.engine.gecko.GeckoSitePerms
import dev.dsh.miuixbrowser.engine.gecko.GeckoTabEngine
import dev.dsh.miuixbrowser.engine.webview.FingerprintInjector
import dev.dsh.miuixbrowser.engine.webview.FpFallbackBridge
import dev.dsh.miuixbrowser.engine.webview.WebOrigin
import dev.dsh.miuixbrowser.net.WebViewProxy
import dev.dsh.miuixbrowser.script.GmBridge
import dev.dsh.miuixbrowser.script.ScriptResources
import dev.dsh.miuixbrowser.script.UserScript
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.ErrorPages
import dev.dsh.miuixbrowser.util.ErrorSpec
import dev.dsh.miuixbrowser.engine.webview.WebViewDebug
import dev.dsh.miuixbrowser.adblock.RequestLog
import dev.dsh.miuixbrowser.util.isSameSite
import dev.dsh.miuixbrowser.util.isUserScriptUrl
import dev.dsh.miuixbrowser.util.toUrl
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.setValue
import dev.dsh.miuixbrowser.download.Downloader

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

    /** 页内翻译（状态 + 与页面脚本 / 翻译服务的衔接），见 [TabTranslate] */
    val translate = TabTranslate(this)

    var title by mutableStateOf(tr("新标签页"))
    var url by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var errorText by mutableStateOf<String?>(null)
    /** 应用当前深浅色：访问失败页按它配色 */
    @Volatile internal var displayDark = false
    /** Gecko：刚交给内核显示的错误页 data: 地址；它触发的页面回调不应改写地址栏、也不记入历史 */
    private var geckoErrorUri: String? = null
    private var geckoShowingError = false
    /** 当前错误页对应的失败地址 + 是否允许「忽略风险继续」；「继续访问」链接只认这里记住的目标，不信任页面传参 */
    @Volatile private var proceedTarget: String? = null
    /** WebView：最近一次主框架证书错误的主机 / 证书指纹 / 错误集合；用户点「继续」时据此登记例外（只放行同一张证书） */
    @Volatile private var pendingSsl: PendingSsl? = null
    /** WebView：已在 onReceivedSslError 里自己渲染过证书错误页的地址与时间，避免随后的 onReceivedError 再渲染一次 */
    @Volatile private var sslPageShownFor: String? = null
    @Volatile private var sslPageShownAt = 0L
    /** Gecko：用户点了「继续」后，下一次对该地址的证书错误交给内核自带证书页（它自带「接受风险并继续」） */
    @Volatile private var geckoNativeCertFor: String? = null
    /** 标签卡片用的页面缩略图（页面加载完成后截取） */
    var thumbnail by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        internal set  // 仅 [ThumbnailCapture] 写

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
    internal var parked = false
        private set

    var closed = false
    internal var destroyed = false
        private set

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

    /** 本页的请求域名汇总（WebView 内核，「请求」面板用）；只放内存，换页 / 关标签清空，无痕标签不记录 */
    val requestLog = RequestLog()

    /** 本页被去广告拦掉的请求数（WebView 内核）：换页清零；只在菜单打开时读一次，不轮询 */
    val pageAdBlocked = java.util.concurrent.atomic.AtomicInteger(0)

    /** 只有 WebView 内核的标签才有 WebView；Gecko 标签不创建（省内存） */
    private val webViewOrNull: WebView? = if (engineKind == EngineKind.WEBVIEW) WebView(appContext) else null

    /** WebView 内核专用 API 的入口。Gecko 标签访问它会抛异常，界面层应改用 [contentView] 等与内核无关的成员 */
    val webView: WebView
        get() = webViewOrNull ?: error(tr("Gecko 标签没有 WebView，请使用与内核无关的接口"))

    /** 当前存活的 Gecko 引擎：延迟创建，也会被 [discardEngine] 回收，所以可能为 null。读它不会触发创建，创建走 [ensureGecko] */
    private var geckoEngine: GeckoTabEngine? = null

    internal val gecko: GeckoTabEngine? get() = geckoEngine

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
            errorText = tr("Gecko 内核启动失败")
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

    /** 两种内核共有的基础操作（导航 / 查找 / 滚动 / 标题）：内核分支都收在 [TabEngineOps] 的两个实现里。 */
    private val ops: TabEngineOps by lazy {
        if (isGecko) GeckoTabOps(current = { geckoEngine }, ensure = { ensureGecko() })
        else WebViewTabOps(webView, extraHeaders = { extraRequestHeaders() })
    }

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
                reportBlobError(tr("存储空间不足，无法读取该文件"))
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
                reportBlobError(tr("文件读取不完整或写入失败"))
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
            reportBlobError(tr("无法读取 blob：{0}", msg))
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

    /** 脚本宿主能力（画中画 / 投屏）要用到的 Activity */
    fun hostActivity(): android.app.Activity? = resolveActivity()

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
            reportBlobError(tr("无法创建缓存文件"))
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
        if (!h.isNullOrEmpty()) {
            pageHost = h
            applyMediaGesture()
        }
    }

    /** 去广告元素隐藏脚本是否已作为 document-start 脚本装上（没装上时由页面加载回调兜底注入） */
    private var adblockDocStart = false

    private fun initWebView() {
        // 显式开启 WebView 的硬件层：WebView 内容进 GPU 离屏层后，被 layerBackdrop 录制并送入 RuntimeShader
        // 时不再整页闪烁 / 崩溃，底栏、弹窗才能在网页态下采样 WebView 使用高级材质（miuix-glass / 液态玻璃）。
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // 原生滚动回调（API 23+）：底栏收起 / 展开由滚动事件唤醒，不再靠定时轮询
        webView.setOnScrollChangeListener { _, _, _, _, _ -> scrollSignal.trySend(Unit) }
        runCatching { webView.addJavascriptInterface(AdBlockBridge { !incognito || adblockInIncognito }, "MafariAdblock") }
        runCatching { webView.addJavascriptInterface(blobBridge, "MafariBlob") }
        // 页内翻译：页面脚本经它把待译文本交给原生（只在翻译进行中受理）
        runCatching { webView.addJavascriptInterface(translate.webBridge(), "MafariTr") }
        // 仅降级路径：WebView 不支持 DOCUMENT_START_SCRIPT 时才安装无参的指纹桥；主路径不向页面暴露任何指纹接口
        val fp = fpInjector ?: return
        if (!fp.docStartSupported) {
            runCatching { webView.addJavascriptInterface(FpFallbackBridge(fp), "MafariFp") }
        }
        // 落在未预注册 origin 时的兜底：重载走带请求头的 loadUrl；STOP 策略在页面上给出提示
        fp.loader = { target -> loadUrl(target) }
        fp.onUnprotectedBlocked = { target ->
            loading = false
            errorText = tr("指纹保护：{0} 未能在加载前注册保护，已停止加载", runCatching { Uri.parse(target).host }.getOrNull().orEmpty())
        }
        // 低频可信通道（豁免请求、统计上报）：来源由 WebView 校验，与指纹配置无关；不支持时静默跳过
        TrustedChannel.install(webView, object : TrustedChannel.Host {
            override fun onFingerprintExemptRequested(origin: WebOrigin) = handleFpExemptRequest(origin)
        })
        // 密码填充 / 保存：和 Gecko 标签同一套应用内弹窗（见 WebLoginChannel）。装上后关掉本 WebView 的系统自动填充，
        // 否则系统的下拉 / 保存提示会和应用弹窗同时出现；不支持（WebView 太旧）时保持系统自动填充不变
        runCatching {
            val ok = WebLoginChannel(object : WebLoginChannel.Host {
                override val appContext: Context get() = this@BrowserTab.appContext
                override val incognito: Boolean get() = this@BrowserTab.incognito
                override fun activity() = geckoActivity()
                override fun currentUrl() = url
                override fun showPrompt(p: LoginPrompt): Boolean {
                    val ui = onLoginPrompt ?: return false
                    ui(p)
                    return true
                }
                override fun evalJs(js: String) { webView.evaluateJavascript(js, null) }
            }).install(webView)
            if (ok) webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }.onFailure { Diag.log("web login channel failed: ${it.message}") }
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
                    { resolveActivity() },
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
                WebViewDebug.logPage("start", pageUrl)
                requestLog.clear() // 换页：本页请求重新统计
                pageAdBlocked.set(0)
                if (!parked) pageUrl?.let { url = it }
                pageHost = runCatching { Uri.parse(pageUrl).host }.getOrNull().orEmpty()
                resetReaderState()
        translate.onNavigation()
                applyMediaGesture()
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
                WebViewDebug.logPage("finish", pageUrl)
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
                // 阅读模式：页面加载完后检测能否进入
                if (!parked) onWebPageSettled(pageUrl ?: url, 400L)
                // 「始终翻译此页面」
                if (!parked) translate.autoStartIfNeeded()
            }

            override fun doUpdateVisitedHistory(view: WebView, pageUrl: String?, isReload: Boolean) {
                // 服务端重定向的最终落点只有这里看得到：未预注册就按策略兜底
                fpInjector?.onCommitted(pageUrl)
                if (!parked) pageUrl?.let { url = it }
                updatePageHost(pageUrl)
                syncNav()
                // 单页应用站内跳转（pushState）：文档没换，需要重新判断阅读模式
                if (!parked && !isReload && !loading) onWebPageSettled(pageUrl, 800L)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (incognito && !adblockInIncognito) null
                else AdBlock.intercept(request, pageHost, blockTrackers, if (incognito || !AdBlock.requestLogOn) null else requestLog)
                    ?.also { pageAdBlocked.incrementAndGet() }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                val errUrl = error.url.orEmpty()
                val host = runCatching { Uri.parse(errUrl).host }.getOrNull().orEmpty()
                val fp = certFingerprint(error.certificate)
                val mask = sslErrorMask(error)
                // 用户在错误页里明确选择过「继续访问」：只放行当时那张证书（指纹一致）且错误类型没有变多
                if (host.isNotEmpty() && fp != null && SslExceptions.allows(host, fp, mask, incognito)) {
                    handler.proceed()
                    return
                }
                handler.cancel()
                Diag.log("webview ssl error primary=${error.primaryError} url=$errUrl page=$url")
                // 主框架：直接在这里渲染引导页。取消之后系统不一定还会回调 onReceivedError，
                // 只靠它的话页面会停在空白；子资源（图片 / 脚本）的证书错误只取消，不动页面
                if (ErrorPages.sameUrl(errUrl, url) || ErrorPages.sameUrl(errUrl, view.url)) {
                    pendingSsl = if (host.isNotEmpty() && fp != null) PendingSsl(errUrl, host, fp, mask) else null
                    val spec = ErrorPages.fromSslError(error.primaryError)
                    errorText = "SSL ${error.primaryError}"
                    sslPageShownFor = errUrl
                    sslPageShownAt = android.os.SystemClock.uptimeMillis()
                    view.post { if (!closed && !destroyed) showWebViewErrorPage(view, errUrl, spec) }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val failing = request.url.toString()
                    val shownBySsl = ErrorPages.sameUrl(failing, sslPageShownFor) &&
                        android.os.SystemClock.uptimeMillis() - sslPageShownAt < 5_000
                    if (!shownBySsl) {
                        errorText = error.description?.toString()
                        showWebViewErrorPage(view, failing, ErrorPages.fromWebView(error.errorCode, error.description?.toString()))
                    }
                }
                WebViewDebug.logLoadError(request, error)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                // 只接管空响应体的 4xx / 5xx；带正文的是网站自己的错误页，照常展示
                if (request.isForMainFrame && ErrorPages.shouldReplaceHttpError(errorResponse.statusCode, errorResponse.responseHeaders)) {
                    showWebViewErrorPage(view, request.url.toString(), ErrorPages.fromHttp(errorResponse.statusCode, errorResponse.reasonPhrase))
                }
                WebViewDebug.logHttpError(request, errorResponse)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (ErrorPages.isProceedUrl(target)) {
                    // 只认「用户在当前正显示的证书错误页上点击」：主框架 + 有用户手势 + 当前页就是那个失败地址
                    if (request.isForMainFrame && request.hasGesture() && ErrorPages.sameUrl(view.url, proceedTarget)) {
                        proceedAfterCertWarning(view)
                    } else {
                        Diag.log("webview: ignored proceed link (mainFrame=${request.isForMainFrame} gesture=${request.hasGesture()})")
                    }
                    return true
                }
                if (EngineSupport.SYNC_BUNDLED && FxaSync.interceptRedirect(target)) return true
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
                    // requestHeaders 是平台类型，个别 WebView 版本 / 局域网地址（如路由器 192.168.x.x）的导航会返回 null，
                    // 直接 ["DNT"] 会编译成对 null 调 Map.get() 而闪退，必须先判空
                    request.requestHeaders?.get("DNT") == null && target != lastDntUrl
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

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                WebViewDebug.logConsole(message)
                return super.onConsoleMessage(message)
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
                    runCatching { android.widget.Toast.makeText(appContext, tr("收到保存请求: {0}", fileChooserParams.filenameHint), android.widget.Toast.LENGTH_SHORT).show() }
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
                    val host = Uri.parse(origin).host ?: origin
                    decide(
                        // 例外网站优先于全局设置
                        GeckoSitePerms.override(host, GeckoSitePerms.LOCATION) ?: locationMode,
                        host,
                        tr("获取你的位置"),
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                        anyOk = true,
                        permKey = GeckoSitePerms.LOCATION,
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

    private fun engineCanGoBack(): Boolean = ops.canGoBack

    private fun engineCanGoForward(): Boolean = ops.canGoForward

    private fun engineUrl(): String = ops.currentUrl()

    private fun engineStop() = ops.stop()

    private fun engineReload() = ops.reload()

    private fun engineLoad(target: String) {
        proceedTarget = null // 换了地址：之前错误页上的「继续」不再有效
        pendingSsl = null
        ops.load(target)
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
        get() = ops.scrollY

    /** 引擎当前的页面标题（切换地址时用来清掉旧标题） */
    fun currentPageTitle(): String = ops.pageTitle()

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
        // 切到别的标签：暂停这个标签里正在播放的媒体
        if (!active) pauseMedia()
    }

    /**
     * 暂停本标签正在播放的音视频：切走标签 / 应用退到后台时调用。
     * Gecko 走 MediaSession；WebView 没有对应接口，用脚本暂停页面（含同源 iframe）里的 video / audio。
     */
    fun pauseMedia() {
        val g = geckoEngine
        if (g != null) {
            g.pauseMedia()
            return
        }
        if (isGecko) return
        runCatching { webViewOrNull?.evaluateJavascript(PAUSE_MEDIA_JS, null) }
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
    // 阅读模式（两种内核共用 assets/mafari_reader.js，见 ReaderMode / GeckoReader）
    // ======================================================================

    /** 当前页面能进入阅读模式（地址栏最左侧显示阅读图标）。每次导航开始清零，页面加载后重新检测 */
    var readerable by mutableStateOf(false)
        private set

    /** 正处于阅读模式（图标点亮） */
    var readerActive by mutableStateOf(false)
        private set

    /** 进入阅读模式时的页面地址：单页应用换了地址就自动退出 */
    private var readerUrl = ""

    /** 最近一次检测对应的地址（WebView：避免同一地址重复清零） */
    private var readerDetectUrl = ""

    /** 检测已重试的次数（页面内容晚渲染时多测几次） */
    private var readerAttempt = 0

    /** 每次导航 +1：丢弃上一个文档遗留的异步回调 */
    private var readerGeneration = 0

    /** Gecko：已向内容脚本发出 enter、等它回报结果（用来在提取失败时给用户提示） */
    private var readerEnterPending = false

    /** Gecko：当前页面里内容脚本的原生端口（页面离开 / 重新加载后由 [geckoReaderGone] 清掉） */
    @Volatile private var readerPort: org.mozilla.geckoview.WebExtension.Port? = null

    private val readerDetectRunnable = Runnable { runReaderDetect() }

    /** 新文档开始加载：阅读模式状态随旧文档一起失效 */
    private fun resetReaderState() {
        mainHandler.removeCallbacks(readerDetectRunnable)
        readerEnterPending = false
        readerGeneration++
        readerAttempt = 0
        readerDetectUrl = ""
        readerUrl = ""
        readerable = false
        readerActive = false
    }

    /** WebView：延迟检测（页面刚结束加载时正文可能还在渲染） */
    private fun scheduleReaderDetect(delayMs: Long) {
        if (isGecko || closed || destroyed) return
        mainHandler.removeCallbacks(readerDetectRunnable)
        mainHandler.postDelayed(readerDetectRunnable, delayMs)
    }

    private fun runReaderDetect() {
        if (isGecko || closed || destroyed || parked || readerActive) return
        val u = url
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return
        readerDetectUrl = u
        val gen = readerGeneration
        evaluateJs(ReaderMode.detectScript(ReaderMode.source(appContext))) { raw ->
            if (gen != readerGeneration || closed || destroyed || readerActive) return@evaluateJs
            val ok = raw == "true"
            readerable = ok
            if (!ok && readerAttempt < 2) {
                readerAttempt++
                scheduleReaderDetect(if (readerAttempt == 1) 1500L else 3500L)
            }
        }
    }

    /** WebView 页面加载结束 / 单页应用换了地址：重新检测；阅读中换了地址则先退出 */
    private fun onWebPageSettled(newUrl: String?, delayMs: Long) {
        if (isGecko || newUrl.isNullOrEmpty()) return
        if (readerActive) {
            if (readerUrl.isNotEmpty() && newUrl != readerUrl) exitReader()
            else return
        }
        if (newUrl == readerDetectUrl && readerable) return
        // 地址变了：上一个地址的检测结果作废，图标先收起，等新结果
        if (newUrl != readerDetectUrl) readerable = false
        readerAttempt = 0
        scheduleReaderDetect(delayMs)
    }

    /**
     * 地址栏阅读图标的点击：未进入 → 进入；已进入 → 退出。[dark] 是当前应用的深浅色，阅读层按它配色。
     */
    fun toggleReader(dark: Boolean) {
        if (readerActive) {
            exitReader()
            return
        }
        if (!readerable || closed || destroyed) return
        if (isGecko) {
            val port = readerPort
            if (port == null) {
                android.widget.Toast.makeText(appContext, tr("阅读模式尚未就绪，请稍后再试"), android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // 结果由内容脚本回报（geckoReaderState）：active=true 时图标才点亮；提取失败则 readerable 会被置回 false
            readerEnterPending = true
            GeckoReader.send(port, org.json.JSONObject().put("t", "enter").put("dark", dark))
            return
        }
        evaluateJs(ReaderMode.enterScript(ReaderMode.source(appContext), dark)) { raw ->
            if (closed || destroyed) return@evaluateJs
            if (raw == "true") {
                readerActive = true
                readerUrl = url
            } else {
                readerable = false
                android.widget.Toast.makeText(appContext, tr("无法提取这个页面的正文"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 退出阅读模式，回到原页面（滚动位置还原） */
    fun exitReader() {
        if (!readerActive) return
        readerActive = false
        readerUrl = ""
        if (isGecko) {
            readerPort?.let { GeckoReader.send(it, org.json.JSONObject().put("t", "exit")) }
        } else {
            evaluateJs(ReaderMode.EXIT_SCRIPT, null)
        }
    }

    /** Gecko：内容脚本回报状态（主线程）。active 为 false 且 readerable 为 false 时图标消失 */
    internal fun geckoReaderState(port: org.mozilla.geckoview.WebExtension.Port, canRead: Boolean, active: Boolean) {
        if (closed || destroyed) return
        readerPort = port
        if (readerEnterPending) {
            readerEnterPending = false
            if (!active) {
                android.widget.Toast.makeText(appContext, tr("无法提取这个页面的正文"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        readerActive = active
        readerable = canRead || active
        if (active) readerUrl = url
    }

    /** Gecko：内容脚本断开（页面离开 / 重新加载）。只处理当前端口，旧页面迟到的断开不能清掉新页面的状态 */
    internal fun geckoReaderGone(port: org.mozilla.geckoview.WebExtension.Port) {
        if (port !== readerPort) return
        readerPort = null
        readerable = false
        readerActive = false
    }

    // ======================================================================
    // Gecko 回调 → 标签状态（由 GeckoTabEngine 调用，均在主线程）
    // ======================================================================

    internal fun isBlankPage(u: String) = u == "about:blank"

    internal fun geckoPageStart(u: String) {
        if (closed || destroyed) return
        if (ErrorPages.isProceedUrl(u)) return
        if (u == geckoErrorUri) { loading = true; return } // 错误页自身的加载：保留失败地址与错误信息
        geckoShowingError = false
        // 主页（url 为空）或被主页盖住（parked）时，页面侧回调不得改写 url；引擎初始化产生的 about:blank 也忽略
        if (!parked && url.isNotEmpty() && !(isBlankPage(u) && !isBlankPage(url))) url = u
        pageHost = runCatching { Uri.parse(u).host }.getOrNull().orEmpty()
        resetReaderState()
        translate.onNavigation()
        loading = true
        errorText = null
        gmResetPage()
        syncNav()
    }

    internal fun geckoLocation(u: String) {
        if (closed || destroyed) return
        if (u == geckoErrorUri || ErrorPages.isProceedUrl(u)) return
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
        if (success && !geckoShowingError && !parked && visited.isNotEmpty() && !visited.startsWith("about:")) {
            onVisited?.invoke(visited, title)
        }
        // 「始终翻译此页面」：内容脚本没连上时由握手补上
        if (success && !geckoShowingError && !parked) translate.autoStartIfNeeded()
    }

    internal fun geckoNavChanged() {
        if (closed || destroyed) return
        syncNav()
    }

    /** 返回要让 Gecko 显示的错误页地址（data: URI）；标签已关闭时返回 null，让内核走默认页 */
    internal fun geckoLoadError(text: String, failingUri: String?, spec: ErrorSpec): String? {
        if (closed || destroyed) return null
        loading = false
        errorText = text
        val target = failingUri?.takeIf { it.isNotBlank() } ?: url
        // 用户已在自绘页点过「继续」：这一次让 Gecko 显示自带证书页（含「接受风险并继续」），由内核登记证书例外
        if (spec.canProceed && geckoNativeCertFor != null && geckoNativeCertFor == target) {
            geckoNativeCertFor = null
            proceedTarget = null
            geckoShowingError = true
            return null
        }
        proceedTarget = if (spec.canProceed && target.startsWith("https://", ignoreCase = true)) target else null
        val html = ErrorPages.render(spec, target, displayDark, nativeProceed = true)
        val uri = "data:text/html;charset=utf-8;base64," +
            android.util.Base64.encodeToString(html.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        geckoErrorUri = uri
        geckoShowingError = true
        if (!parked && target.isNotEmpty() && !isBlankPage(target)) url = target
        return uri
    }

    /** WebView：用自绘错误页替换系统默认页，地址栏仍显示失败的网址 */
    private fun showWebViewErrorPage(view: WebView, failingUrl: String, spec: ErrorSpec) {
        // data: / about: 等没有「重试」意义，也避免错误页再出错时死循环
        if (failingUrl.startsWith("data:") || failingUrl.startsWith("about:")) return
        val p = pendingSsl
        // 没有这次错误的证书指纹（或不是同一个地址）就没法把例外绑定到具体证书：不给入口；
        // 同一张证书已经放行过却仍失败（例如 HSTS 站点不允许覆盖）：也不再给入口，避免点了没反应
        val canBind = p != null && ErrorPages.sameUrl(p.url, failingUrl) && !SslExceptions.allows(p.host, p.fp, p.mask, incognito)
        val shown = if (spec.canProceed && !canBind) ErrorPages.withoutProceed(spec) else spec
        proceedTarget = if (shown.canProceed && failingUrl.startsWith("https://", ignoreCase = true)) failingUrl else null
        view.loadDataWithBaseURL(failingUrl, ErrorPages.render(shown, failingUrl, displayDark), "text/html", "UTF-8", failingUrl)
    }

    /** WebView：用户在证书错误页点了「忽略风险，继续访问」——记住「该主机 + 这张证书」（本次运行内有效）并重新加载 */
    private fun proceedAfterCertWarning(view: WebView) {
        val target = proceedTarget ?: return
        val p = pendingSsl ?: return
        if (!ErrorPages.sameUrl(p.url, target)) return
        SslExceptions.add(p.host, p.fp, p.mask, incognito)
        proceedTarget = null
        pendingSsl = null
        errorText = null
        view.loadUrl(target)
    }

    /** Gecko：同上，但放行由内核的证书页完成——下一次加载该地址时不接管错误页 */
    internal fun geckoProceedAfterCertWarning(): Boolean {
        val target = proceedTarget ?: return false
        if (closed || destroyed) return false
        geckoNativeCertFor = target
        proceedTarget = null
        return true
    }

    /** Gecko：[geckoProceedAfterCertWarning] 之后应重新加载的地址（下次 geckoLoadError 命中后清除） */
    internal fun geckoProceedReloadUrl(): String? = geckoNativeCertFor

    /** window.open / target=_blank：沿用 WebView 版的新窗口规则（同站 / 点击 / 重定向权限） */
    internal fun geckoNewWindow(target: String, linkClick: Boolean) {
        val uri = Uri.parse(target)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            // window.open("weixin://…") / <a target=_blank href="alipays://…">：交给「打开其他应用」处理，不能直接丢掉
            if (isExternalAppScheme(scheme)) openExternalFromWindow(uri, linkClick)
            return
        }
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
        if (promptBusy()) return true
        askRedirect(from, tr("跳转到 {0}", targetHost.ifEmpty { target.take(60) })) { allow ->
            if (allow) open?.invoke(target) else notifyRedirectBlocked(false, targetHost)
        }
        return true
    }

    /** Gecko 视频全屏中：界面据此隐藏应用自己的顶栏 / 底栏 */
    var geckoFullscreen by mutableStateOf(false)
        private set

    /** Gecko：页面声明的 Web App Manifest（PWA）。由 GeckoView 的 onWebAppManifest 回调写入 */
    var pwaManifest: PwaManifest? = null
        private set

    internal fun geckoWebAppManifest(json: org.json.JSONObject) {
        if (closed || destroyed) return
        pwaManifest = PwaManifest.parse(json, url)
    }

    /** 只在 manifest 来自当前页面同一个 origin 时才返回（换站后旧 manifest 不再适用） */
    fun pwaManifestForCurrentPage(): PwaManifest? {
        val m = pwaManifest ?: return null
        val a = runCatching { Uri.parse(m.sourceUrl) }.getOrNull() ?: return null
        val b = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val same = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && a.port == b.port
        return if (same) m else null
    }

    /**
     * Gecko 主框架导航闸门（独立应用窗口用）：参数 = 目标地址 / 是否有用户手势 / 是否重定向；
     * 返回 true 表示已被接管（比如交给浏览器打开），引擎应拒绝本次导航。浏览器里的普通标签不设置它。
     */
    var geckoNavGate: ((target: String, hasGesture: Boolean, isRedirect: Boolean) -> Boolean)? = null

    /**
     * Gecko 版：页面无手势自动唤起其他应用（intent:// weixin:// 等），套用独立的「打开其他应用」权限，
     * 规则与 [interceptRedirect]（external = true）一致：空白页 / 首次加载上的唤起不算；总是允许 → 直接唤起；
     * 总是拒绝 → 提示已拦截；询问 → 弹窗，同意才唤起。当前页面始终原地不动。
     */
    internal fun geckoExternalRequest(uri: Uri) {
        val from = pageHost
        // 例外网站优先（与 WebView 一致）；带 permKey 才能在弹窗里「记住此网站的选择」
        val mode = effExternalAppMode()
        if (from.isEmpty() || mode == 1) {
            launchExternalApp(uri)
            return
        }
        if (mode == 2) {
            notifyRedirectBlocked(true, "")
            return
        }
        if (promptBusy()) return
        askRedirect(from, tr("打开其他应用"), GeckoSitePerms.EXTERNAL_APP) { allow ->
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
            tr("获取你的位置"),
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
        val what = listOfNotNull(if (camOk) tr("摄像头") else null, if (micOk) tr("麦克风") else null).joinToString(tr("、"))
        val mustAsk = (camOk && camMode == 0) || (micOk && micModeEff == 0)
        val keys = listOfNotNull(
            if (camOk) GeckoSitePerms.CAMERA else null,
            if (micOk) GeckoSitePerms.MIC else null,
        ).joinToString(",")
        decide(if (mustAsk) 0 else 1, origin, tr("使用{0}", what), perms, permKey = keys, result = result)
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
        displayDark = dark
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
        externalAppMode: Int = 0,
        blockTrackers: Boolean = false,
        fingerprintProtect: Boolean = false,
    ) {
        GeckoSitePerms.load(appContext) // 例外网站存储：纯 WebView 引擎下也要先载入
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
        this.externalAppMode = externalAppMode
        this.mediaModeGlobal = mediaMode
        this.blockTrackers = blockTrackers
        // 媒体发声：允许=可自动播放；询问/拒绝=需用户手势才能出声
        // 以下全是 WebView 专用：脚本注入 / 媒体自动播放策略。Gecko 对应能力见 docs/GECKO.md
        val wv = webViewOrNull ?: return
        applyMediaGesture()

        // 例外网站：脚本是对所有站点注册的，所以把相关例外一并写进脚本，在页面里按 location.hostname 取生效模式
        fun excJs(key: String, global: Int): String {
            val obj = org.json.JSONObject()
            GeckoSitePerms.exceptions.forEach { (host, m) ->
                m[key]?.let { v -> obj.put(host, v) }
            }
            return "var m=(($obj)[String(location.hostname).toLowerCase()])||$global;"
        }
        fun hasExc(key: String) = GeckoSitePerms.exceptions.values.any { it.containsKey(key) }
        val script = buildString {
            if (webrtcDisabled) {
                InjectAssets.read(appContext, "mafari_no_webrtc.js")?.let {
                    append(it)
                    append('\n')
                }
            }
            if (clipboardMode == 2 || hasExc(GeckoSitePerms.CLIPBOARD)) {
                append(
                    "(function(){try{" + excJs(GeckoSitePerms.CLIPBOARD, clipboardMode) + "if(m!=2)return;var d=function(){return Promise.reject(new DOMException('Permission denied','NotAllowedError'))};" +
                        "if(navigator.clipboard){['read','readText','write','writeText'].forEach(function(m){try{navigator.clipboard[m]=d}catch(e){}})}}catch(e){}})();\n",
                )
            }
            if (sendDnt) {
                append(
                    "(function(){try{Object.defineProperty(Navigator.prototype,'doNotTrack',{get:function(){return '1'},configurable:true});" +
                        "Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:function(){return true},configurable:true})}catch(e){}})();\n",
                )
            }
            if (mediaMode == 2 || hasExc(GeckoSitePerms.MEDIA)) {
                append(
                    "(function(){try{" + excJs(GeckoSitePerms.MEDIA, mediaMode) + "if(m!=2)return;Object.defineProperty(HTMLMediaElement.prototype,'muted',{get:function(){return true},set:function(v){},configurable:true});" +
                        "var p=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){try{this.volume=0}catch(e){}return p.apply(this,arguments)}}catch(e){}})();\n",
                )
            }
            // 网站通知拒绝：JS Notification 权限固定为 denied
            if (notificationMode == 2 || hasExc(GeckoSitePerms.NOTIFICATION)) {
                append(
                    "(function(){try{" + excJs(GeckoSitePerms.NOTIFICATION, notificationMode) + "if(m!=2)return;" +
                        "if(window.Notification){Object.defineProperty(Notification,'permission',{get:function(){return 'denied'},configurable:true});" +
                        "Notification.requestPermission=function(){return Promise.resolve('denied')}}" +
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

    /** 全局「网站媒体发声」：0 询问 / 1 允许 / 2 拒绝（按站点的例外见 [effMediaMode]） */
    @Volatile
    private var mediaModeGlobal = 1

    private var cameraMode = 0
    private var micMode = 0
    private var locationMode = 0
    private var notificationMode = 0

    /** 网页重定向 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    @Volatile
    private var redirectMode = 0

    /** 打开其他应用 0=每次使用时询问 1=总是允许 2=总是拒绝（与「网页重定向」相互独立） */
    @Volatile
    private var externalAppMode = 0

    /** 正在等待用户决定的重定向，避免脚本循环跳转时连续弹窗 */
    private var redirectPromptPending = false
    private var redirectPromptAt = 0L

    /**
     * 是否有权限弹窗正在等用户决定。超过 30 秒还没结果的视为过期（弹窗被切走标签 / 异常关闭而没回调时，
     * 否则 [redirectPromptPending] 会一直为 true，之后所有「打开其他应用 / 重定向」都被静默吞掉）。
     */
    private fun promptBusy(): Boolean =
        redirectPromptPending && android.os.SystemClock.elapsedRealtime() - redirectPromptAt < 30_000L
    private var lastRedirectToast = 0L

    /**
     * 网页重定向权限：只管「页面自己发起、没有用户手势」的主框架跳转——
     * JS（location / window.open）、meta refresh。
     * 自动唤起其他应用（intent:// 等）由独立的「打开其他应用」权限控制（[externalAppMode]），两者互不影响。
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
        val mode = if (external) effExternalAppMode() else effRedirectMode()
        if (mode == 1) {
            if (external) return false
            onAllowed()
            return true
        }
        val label = if (external) tr("打开其他应用") else tr("跳转到 {0}", targetHost.ifEmpty { request.url.toString().take(60) })
        if (mode == 2) {
            notifyRedirectBlocked(external, targetHost)
            return true
        }
        if (promptBusy()) return true
        // 拒绝只是不执行这次跳转 / 唤起：这次导航在 shouldOverrideUrlLoading 里就已被截下，
        // 当前页面一直没有离开，所以这里什么都不用回退 —— 停留在网页，不能再 goBack 把页面「关掉」
        askRedirect(from, label, if (external) GeckoSitePerms.EXTERNAL_APP else GeckoSitePerms.REDIRECT) { allow ->
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
        if (promptBusy()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val record = fpExemptAsked.getOrPut(origin.origin) { longArrayOf(0L, 0L) }
        if (record[0] >= 3 || (record[0] > 0 && now - record[1] < 30_000L)) return
        if (fpExemptAsked.size > 64) fpExemptAsked.clear()
        record[0]++
        record[1] = now
        askRedirect(origin.origin, tr("关闭对它的指纹保护")) { allow ->
            if (allow && AdBlock.exemptFingerprintSite(origin.host)) {
                android.widget.Toast.makeText(
                    appContext, tr("已豁免 {0}，刷新后生效", origin.host), android.widget.Toast.LENGTH_SHORT,
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

    /** WebView：当前页面生效的「网页重定向」模式（例外网站优先） */
    private fun effRedirectMode(): Int =
        GeckoSitePerms.override(pageHost, GeckoSitePerms.REDIRECT) ?: redirectMode

    /** WebView：当前页面生效的「打开其他应用」模式（例外网站优先） */
    private fun effExternalAppMode(): Int =
        GeckoSitePerms.override(pageHost, GeckoSitePerms.EXTERNAL_APP) ?: externalAppMode

    /** WebView：当前页面生效的「网站媒体发声」模式（例外网站优先）；1 = 允许自动出声 */
    private fun effMediaMode(): Int =
        GeckoSitePerms.override(pageHost, GeckoSitePerms.MEDIA) ?: mediaModeGlobal

    private fun applyMediaGesture() {
        val wv = webViewOrNull ?: return
        runCatching { wv.settings.mediaPlaybackRequiresUserGesture = effMediaMode() != 1 }
    }

    private fun askRedirect(origin: String, what: String, permKey: String? = null, onResult: (Boolean) -> Unit) {
        redirectPromptPending = true
        redirectPromptAt = android.os.SystemClock.elapsedRealtime()
        val decide = { allow: Boolean ->
            redirectPromptPending = false
            onResult(allow)
        }
        val prompt = onPermissionPrompt
        if (prompt != null) {
            prompt(origin, what, permKey, decide)
        } else {
            deferredPrompt = DeferredPrompt(origin, what, permKey, decide, android.os.SystemClock.elapsedRealtime())
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
            android.widget.Toast.makeText(appContext, tr("无法打开该链接"), android.widget.Toast.LENGTH_SHORT).show()
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
            android.widget.Toast.makeText(appContext, tr("未找到可打开的应用"), android.widget.Toast.LENGTH_SHORT).show()
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
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                // window.open("weixin://dl/business/?t=…") 这类唤起微信小程序 / 支付宝的写法，不能直接丢掉
                if (isExternalAppScheme(scheme)) mainHandler.post { openExternalFromWindow(uri, isUserGesture) }
                return
            }
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
        val popupMode = effRedirectMode()
        if (popupMode == 1) {
            open(target)
            return
        }
        if (popupMode == 2) {
            notifyRedirectBlocked(false, targetHost)
            return
        }
        if (promptBusy()) return
        askRedirect(pageHost, tr("跳转到 {0}", targetHost.ifEmpty { target.take(60) }), GeckoSitePerms.REDIRECT) { allow ->
            if (allow) open(target) else notifyRedirectBlocked(false, targetHost)
        }
    }

    /** 需要交给系统处理的应用协议（weixin / alipays / intent / mailto / tel …）；about / javascript / data / blob / file 不算 */
    internal fun isExternalAppScheme(scheme: String?): Boolean =
        !scheme.isNullOrEmpty() && scheme !in setOf(
            "http", "https", "about", "javascript", "data", "blob", "file", "view-source", "resource", "moz-extension",
        )

    /**
     * 新窗口（window.open / target=_blank）或 Gecko 子框架里要唤起其他应用：
     * 有用户手势 / 总是允许 / 空白页 → 直接唤起；总是拒绝 → 提示已拦截；询问 → 弹窗，同意才唤起。
     */
    internal fun openExternalFromWindow(uri: Uri, userGesture: Boolean) {
        val from = pageHost
        val mode = effExternalAppMode()
        if (userGesture || from.isEmpty() || mode == 1) {
            launchExternalApp(uri)
            return
        }
        if (mode == 2) {
            notifyRedirectBlocked(true, "")
            return
        }
        if (promptBusy()) return
        askRedirect(from, tr("打开其他应用"), GeckoSitePerms.EXTERNAL_APP) { allow ->
            if (allow) launchExternalApp(uri) else notifyRedirectBlocked(true, "")
        }
    }

    private fun notifyRedirectBlocked(external: Boolean, host: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRedirectToast < 2000) return
        lastRedirectToast = now
        val msg = if (external) tr("已拦截网页打开其他应用") else tr("已拦截网页重定向") + if (host.isNotEmpty()) tr("：{0}", host) else ""
        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 网页权限「询问」：由界面弹窗，decide(true/false) 只能调用一次 */
    var onPermissionPrompt: ((origin: String, what: String, permKey: String?, decide: (Boolean) -> Unit) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) flushDeferredPrompt()
        }

    /**
     * Gecko 的登录提示（保存 / 更新密码、选择要填的账号）：界面用应用自己的玻璃弹窗展示。
     * 为 null（界面还没挂上）时调用方回退到系统对话框；请求里的回调都只会生效一次。
     */
    internal var onLoginPrompt: ((LoginPrompt) -> Unit)? = null

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
        val host = request.origin.host ?: request.origin.toString()
        // 例外网站优先于全局设置
        val camMode = GeckoSitePerms.override(host, GeckoSitePerms.CAMERA) ?: cameraMode
        val micModeEff = GeckoSitePerms.override(host, GeckoSitePerms.MIC) ?: micMode
        val camOk = PermissionRequest.RESOURCE_VIDEO_CAPTURE in res && camMode != 2
        val micOk = PermissionRequest.RESOURCE_AUDIO_CAPTURE in res && micModeEff != 2
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
        val what = listOfNotNull(if (camOk) tr("摄像头") else null, if (micOk) tr("麦克风") else null).joinToString(tr("、"))
        val mustAsk = (camOk && camMode == 0) || (micOk && micModeEff == 0)
        val keys = listOfNotNull(
            if (camOk) GeckoSitePerms.CAMERA else null,
            if (micOk) GeckoSitePerms.MIC else null,
        ).joinToString(",")
        decide(if (mustAsk) 0 else 1, host, tr("使用{0}", what), perms, permKey = keys) { ok ->
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
        ops.findAll(text)
    }

    private val thumbs = ThumbnailCapture(this)

    /** 按需截取标签缩略图（主线程发起），逻辑见 [ThumbnailCapture.captureNow] */
    fun captureThumbnailNow() = thumbs.captureNow()

    /** [TabThumbs] 回到主线程：一次截图处理结束（成功或失败） */
    internal fun thumbCaptureDone() = thumbs.captureDone()

    /** 新截的缩略图 */
    internal fun applyThumbnail(bitmap: Bitmap) = thumbs.apply(bitmap)

    /** 从库里读回的缩略图（不代表当前页面状态，所以不更新 thumbUrl，下次切走时仍会重新截图） */
    internal fun applyThumbnailFromDb(bitmap: Bitmap) = thumbs.applyFromDb(bitmap)

    /** 释放内存里的缩略图（LRU 淘汰 / 标签关闭）；普通标签的图还在库里，卡片再显示时会读回 */
    internal fun dropThumbnail() = thumbs.drop()

    fun findNext(forward: Boolean) {
        ops.findNext(forward)
    }

    fun clearFind() {
        ops.clearFind()
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
        val db = AppDatabase.get(appContext)
        if (g == null || db == null) {
            engineLoad(target)
            return
        }
        val epoch = navEpoch
        val key = dbKey
        AppDatabase.io.execute {
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
        val db = AppDatabase.get(appContext) ?: return false
        val page = persistedPageUrl
        // 页面还没加载进引擎（待恢复 / 正在恢复）时，引擎里的状态是空白的，库里那份才是真的：不能用它覆盖
        val dbStateAuthoritative = pendingRestore || stateRestorePending
        val state = if (dbStateAuthoritative) null else g.serializedState()
        if (state != null) {
            val key = dbKey
            AppDatabase.io.execute {
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
        val db = AppDatabase.get(appContext) ?: return
        val key = dbKey
        AppDatabase.io.execute {
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

    private fun historyIndex(): Int = ops.historyIndex()

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
        // 阅读模式中：返回先退出阅读模式，回到原页面
        if (readerActive) {
            exitReader()
            return
        }
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
            ops.goBack()
        } else if (openedFromLink) {
            // 链接打开的标签已经在第一页：关掉它，回到来源标签（而不是先落到本标签的主页）
            onRequestClose?.invoke()
        }
    }

    fun goForward() {
        if (url.isEmpty()) return
        if (engineCanGoForward()) {
            fpInjector?.prepareHistory(1)
            ops.goForward()
        }
    }

    fun destroyNow() {
        if (destroyed) return
        destroyed = true
        // 这几步任何一步抛异常都不能挡住后面的引擎销毁，否则会话没关，媒体停不下来
        runCatching { cancelFileChooser() }
        runCatching { exitCustomView() }
        runCatching { discardAllBlobSessions() }
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

    /**
     * 关闭标签：销毁引擎。
     * 当前标签的视图还挂在界面上，等它被摘下来再销毁；后台标签的视图早就不在界面里，立即销毁。
     * 不能用 View.post：没挂在窗口上的 View 会把任务攒着，等它重新挂上去才执行，
     * 后台标签（例如正在播放媒体的）就永远不会被销毁，声音停不下来。
     */
    fun close() {
        closed = true
        TabThumbs.forget(this)
        destroyWhenDetached(0)
    }

    private fun destroyWhenDetached(attempt: Int) {
        if (destroyed) return
        // 不能读 contentView：没有引擎的 Gecko 标签（延迟创建 / 已回收）会为了关它先建一个引擎
        val v: View? = if (isGecko) geckoEngine?.view else webView
        // 没有引擎 / 视图已不在窗口里 → 直接销毁；等了约 250ms 还没摘下来也强制销毁（destroyNow 会先把视图摘掉）。
        // 判断用 isAttachedToWindow 而不是 parent：切走的标签，Compose 的 AndroidView 容器被释放后，视图仍是容器的子视图（parent 非空），
        // 但已经不在窗口里了
        if (v == null || !v.isAttachedToWindow || attempt >= CLOSE_WAIT_TRIES) {
            destroyNow()
            return
        }
        mainHandler.postDelayed({ destroyWhenDetached(attempt + 1) }, CLOSE_WAIT_MS)
    }

    private companion object {
        private const val PAUSE_MEDIA_JS =
            "(function(){try{var p=function(d){d.querySelectorAll('video,audio').forEach(function(m){" +
                "try{if(!m.paused)m.pause()}catch(e){}})};p(document);" +
                "for(var i=0;i<window.frames.length;i++){try{p(window.frames[i].document)}catch(e){}}}catch(e){}})()"
        private const val CLOSE_WAIT_TRIES = 5
        private const val CLOSE_WAIT_MS = 50L
        private var counter = 0

        fun nextId(): Int {
            counter += 1
            return counter
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
