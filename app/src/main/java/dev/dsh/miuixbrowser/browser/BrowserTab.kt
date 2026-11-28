/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

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
import dev.dsh.miuixbrowser.appContainer
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
internal object InjectAssets {
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
    internal val startIncognito: Boolean = false,
    /** 拓展 tabs.create 时已交给 Gecko 的 session（仅 Gecko 内核）；普通标签为 null，由引擎自己创建 */
    internal val adoptedGeckoSession: org.mozilla.geckoview.GeckoSession? = null,
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
    /** 页面自己带固定在底部的整宽栏（如 YouTube 的「首页 / 我」）：悬浮三段式据此决定是否让位。WebView 轮询检测，Gecko 由内容脚本回报 */
    var pageHasBottomBar by mutableStateOf(false)
    /** 页面自己带固定在顶部的整宽栏：网页态状态栏沉浸据此决定收起时是否让网页铺到状态栏下。仅 WebView 轮询检测，见 PageTopBar.kt */
    var pageHasTopBar by mutableStateOf(false)
    internal var topBarMisses = 0
    var errorText by mutableStateOf<String?>(null)
    /** 应用当前深浅色：访问失败页按它配色 */
    @Volatile internal var displayDark = false
    /** Gecko：刚交给内核显示的错误页 data: 地址；它触发的页面回调不应改写地址栏、也不记入历史 */
    private var geckoErrorUri: String? = null
    private var geckoShowingError = false
    /** 当前错误页对应的失败地址 + 是否允许「忽略风险继续」；「继续访问」链接只认这里记住的目标，不信任页面传参 */
    @Volatile internal var proceedTarget: String? = null
    /** WebView：最近一次主框架证书错误的主机 / 证书指纹 / 错误集合；用户点「继续」时据此登记例外（只放行同一张证书） */
    @Volatile internal var pendingSsl: PendingSsl? = null
    /** WebView：已在 onReceivedSslError 里自己渲染过证书错误页的地址与时间，避免随后的 onReceivedError 再渲染一次 */
    @Volatile internal var sslPageShownFor: String? = null
    @Volatile internal var sslPageShownAt = 0L

    /** Cloudflare 等 CDN 的瞬时 5xx（空响应体）自动重试：记录正在重试的地址与已重试次数，页面成功加载后清零 */
    internal var httpRetryUrl: String? = null
    internal var httpRetryCount = 0
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
        internal set

    /** 打开本标签的标签 id（-1 = 无）：关闭本标签时优先回到它，形成层级回退 */
    var openerId: Int = -1

    /**
     * 本标签是由另一个标签里的链接（点击 / 长按「新标签页打开」/ window.open）打开的。
     * 这种标签没有「主页」可回：在它的第一页点返回，直接关闭它并回到来源标签。
     */
    var openedFromLink = false

    /** 请求关闭自己（由 [BrowserState] 接线），用于 [openedFromLink] 标签在第一页返回 */
    var onRequestClose: (() -> Unit)? = null

    /**
     * WebView 渲染进程崩溃 / 被系统回收（由 [BrowserState] 接线）：这个标签的 WebView 已经不能用了，
     * 回调里由 [BrowserState] 建一个新标签顶替它的位置。参数是 [android.webkit.RenderProcessGoneDetail.didCrash]。
     */
    var onRenderGone: ((didCrash: Boolean) -> Unit)? = null

    /** 渲染进程已经没了（只会置一次）：之后这个标签等着被替换，不再处理第二次回调 */
    @Volatile internal var renderGone = false

    /** 从主页进入页面时 WebView 历史的当前下标（-1 = 历史为空）：回退到这一层就回主页，不再穿透到主页之前的旧页面 */
    internal var homeAnchor = -1

    /** 标签正显示主页：WebView 里的页面（若有）被盖住，页面侧回调不得再改写 url，否则主页会被页面「顶」出来 */
    internal var parked = false
        internal set(value) {
            if (field == value) return
            field = value
            // 页面被主页盖住期间（同一个标签、引擎仍存活）禁止播放媒体：回到页面前一律保持暂停
            applyMediaBlock(value || PageMediaGate.nativePlaying)
        }

    var closed = false
    internal var destroyed = false
        private set

    /** 当前标签是否「请求桌面网站」 */
    var desktopMode by mutableStateOf(false)
        internal set

    /** 无痕浏览标签 */
    var incognito by mutableStateOf(false)
        private set

    fun applyIncognito(value: Boolean) {
        if (incognito == value) return
        incognito = value
        syncUserScripts()
        applyWebExtensions() // 无痕标签不装拓展；切回普通时补装
        // Cookie 开关是进程级的，不能在这里改；改为在该标签成为当前标签时由 applyPrivacy 设置
    }

    internal val defaultUserAgent: String by lazy { webViewOrNull?.settings?.userAgentString.orEmpty() }

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

    /** 手动标记广告开关：关闭后长按元素不再检测 / 注入标记逻辑（仅 WebView） */
    @Volatile var adMarkLongPress: Boolean = true

    /** 网页增强总开关：关闭后不再注入任何脚本 / 长按逻辑，用于排查（去广告有自己的开关，见 AdBlock） */
    var pageEnhancements: Boolean = dev.dsh.miuixbrowser.engine.EngineSupport.pageEnhancementsOn(appContext)
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

    /** 搜索模板，由设置注入 */
    var searchTemplate: String = "https://www.baidu.com/s?wd=%s"

    /** 下拉进度 0..1，用于顶部动画 */
    var pullProgress by mutableFloatStateOf(0f)
        internal set

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

    /** WebView 标签自己的嗅探结果；Gecko 标签的在 [dev.dsh.miuixbrowser.engine.gecko.GeckoSniff]（按页面地址） */
    internal val webSniffs = dev.dsh.miuixbrowser.sniff.SniffList()

    /** 当前页嗅探到的视频（界面读它：Compose 状态，有新的会重组） */
    val sniffs: dev.dsh.miuixbrowser.sniff.SniffList
        get() = if (isGecko) dev.dsh.miuixbrowser.engine.gecko.GeckoSniff.listFor(url) else webSniffs

    /** 只有 WebView 内核的标签才有 WebView；Gecko 标签不创建（省内存） */
    internal val webViewOrNull: WebView? = if (engineKind == EngineKind.WEBVIEW) {
        dev.dsh.miuixbrowser.engine.EngineSupport.markWebViewUsed(appContext)
        // 无痕标签：创建后立刻绑到独立 Profile（必须在 WebView 首次使用前）。不支持多 Profile 时沿用进程级 Cookie 开关
        WebView(appContext).also { if (startIncognito) dev.dsh.miuixbrowser.engine.webview.IncognitoProfile.bind(it) }
    } else {
        null
    }

    /** WebView 内核专用 API 的入口。Gecko 标签访问它会抛异常，界面层应改用 [contentView] 等与内核无关的成员 */
    val webView: WebView
        get() = webViewOrNull ?: error(tr("Gecko 标签没有 WebView，请使用与内核无关的接口"))

    /** 当前存活的 Gecko 引擎：延迟创建，也会被 [discardEngine] 回收，所以可能为 null。读它不会触发创建，创建走 [ensureGecko] */
    internal var geckoEngine: GeckoTabEngine? = null

    internal val gecko: GeckoTabEngine? get() = geckoEngine

    init {
        // 非延迟标签保持原行为：构造时就建引擎，建不出来让 BrowserState.createTab 退回 WebView
        if (engineKind == EngineKind.GECKO && !lazyEngine) {
            geckoEngine = GeckoTabEngine(appContext, startIncognito, this, adoptedGeckoSession)
        }
    }

    internal val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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

    /** Gecko 标签且渲染后端是 TextureView：网页画面能被 layerBackdrop 采样（底栏 / 弹窗可用高级材质） */
    val geckoTextureGlass: Boolean get() = isGecko && geckoEngine?.usesTexture == true

    /**
     * 主页盖住网页时内容视图用的「隐藏」可见性。
     * Gecko 的 SurfaceView 后端一旦 INVISIBLE 就会销毁 Surface，回到网页再重建，重建时 Gecko 合成器可能没接上而黑屏；
     * 所以该后端只靠 alpha=0 隐藏（View 不绘制，也不会在窗口上打洞，Surface 保持存活）。其余情况仍用 INVISIBLE。
     */
    val hiddenVisibility: Int
        get() = if (isGecko && geckoEngine?.usesTexture != true) View.VISIBLE else View.INVISIBLE

    /** 两种内核共有的基础操作（导航 / 查找 / 滚动 / 标题）：内核分支都收在 [TabEngineOps] 的两个实现里。 */
    private val ops: TabEngineOps by lazy {
        if (isGecko) GeckoTabOps(current = { geckoEngine }, ensure = { ensureGecko() })
        else WebViewTabOps(webView, extraHeaders = { extraRequestHeaders() })
    }

    /** 指纹保护：按 origin 注册 document-start 脚本（见 FingerprintInjector / docs/FINGERPRINT.md）。 */
    internal val fpInjector: FingerprintInjector? = webViewOrNull?.let { FingerprintInjector(it) }

    /** blob / data URL 抽取（分片会话 + JS 桥），见 [TabBlob] */
    internal val blob = TabBlob(this)

    /** 待恢复 / 引擎回收，见 [TabRestore] */
    private val restore = TabRestore(this)

    /** 阅读模式，见 [TabReader] */
    internal val reader = TabReader(this)

    /** 油猴脚本注入 / 菜单命令，见 [TabScripts] */
    internal val scripts = TabScripts(this)

    /** 长按元素检测 / 手动标记广告（仅 WebView），见 [TabElementInspector] */
    internal val inspector = TabElementInspector(this)

    /** 本标签 WebView 的油猴原生能力实现（旧桥 MafariGM 与隔离世界通道 [GmChannel] 共用），由 setupWebView 创建 */
    internal var gmBridge: GmBridge? = null

    /** 打开新标签页（GM_openInTab 用）：桥生成的 [id]、地址、是否切到前台 */
    var onGmOpenTab: ((id: String, url: String, active: Boolean) -> Unit)? = null

    /** 关闭 [onGmOpenTab] 打开的标签（GM_openInTab 返回对象的 close()） */
    var onGmCloseTab: ((id: String) -> Unit)? = null

    /** GM_download：带结果回调的下载请求（走与普通下载相同的确认弹窗） */
    var onGmDownload: ((url: String, name: String, onResult: ((Boolean, String?) -> Unit)?) -> Unit)? = null


    /** 文件选择器：WebView 持有的回调，界面拉起选择器后必须回传（null = 取消） */
    internal var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /** HTML5 视频全屏中的 View 与回调 */
    internal var customView: View? = null
    internal var customViewCallback: WebChromeClient.CustomViewCallback? = null

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
    internal fun lockLandscape() {
        val activity = resolveActivity() ?: return
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** 退出全屏：恢复系统方向 */
    internal fun restoreOrientation() {
        val activity = resolveActivity() ?: return
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** 脚本宿主能力（画中画 / 投屏）要用到的 Activity */
    fun hostActivity(): android.app.Activity? = resolveActivity()

    /** LocalContext 可能是 ContextThemeWrapper，逐层解开找 Activity */
    internal fun resolveActivity(): android.app.Activity? {
        var c: Context? = appContext
        while (c != null) {
            if (c is android.app.Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * 把 blob 分片写入缓存文件（不经 evaluateJavascript 返回大字符串），完成后回调 onBlobReady(tempPath=...)。
     */
    fun extractBlob(
        url: String,
        userAgent: String? = null,
        disposition: String? = null,
        mime: String? = null,
    ) = blob.extract(url, userAgent, disposition, mime)

    /**
     * onPageStarted 只在导航开始时给一次（重定向前）的地址：http→https、短链跳转、站内 pushState 之后
     * pageHost 会是旧站点，导致 $third-party / $domain= / 站点白名单判错。这里用最终地址刷新。
     */
    internal fun updatePageHost(pageUrl: String?) {
        val h = runCatching { Uri.parse(pageUrl).host }.getOrNull()
        if (!h.isNullOrEmpty()) {
            pageHost = h
            applyMediaGesture()
        }
    }

    /** 去广告元素隐藏脚本是否已作为 document-start 脚本装上（没装上时由页面加载回调兜底注入） */
    internal var adblockDocStart = false

    /** WebView 拓展在本标签上的装配结果（content script 注入 + 消息桥）；拓展集合变化 / 切无痕 / 销毁时先撤销再重装 */
    private var extInstallation: dev.dsh.miuixbrowser.ext.WebViewExtensionHost.Installation? = null

    /** 已登记「拓展就绪后补装」；就绪前反复调用 [applyWebExtensions] 只登记一次 */
    private var extDeferred = false

    /** 本标签是否已接上 WebView 的 NavigationListener（拓展 webNavigation 用；晚加载时在 [applyWebExtensions] 里补接） */
    internal var webNavAttached = false

    init {
        // 引擎初始化放在这里（而不是各自的构造处），保证上面所有属性初始化完毕
        if (webViewOrNull != null) {
            setupWebView()
            applyWebExtensions() // 必须在首次加载网页之前
        }
    }

    /**
     * 把已启用的 WebView 拓展装到本标签（主线程，仅 WebView 内核）。会先撤销上一次的装配，所以可以反复调用；
     * 无痕标签不装拓展。对已加载的页面不生效，下一次导航起生效（addWebMessageListener / document-start 脚本的语义）。
     */
    fun applyWebExtensions() {
        val wv = webViewOrNull ?: return
        runCatching {
            extInstallation?.close()
            extInstallation = null
            if (incognito || destroyed) return
            // 冷启动期间拓展晚加载（见 ExtStartupGate）：首页加载完之前不装、不读仓库、不阻塞主线程；就绪后会回调 [onExtensionsReady]
            if (!dev.dsh.miuixbrowser.ext.ExtStartupGate.ready) {
                deferWebExtensions()
                return
            }
            attachWebNavigationIfNeeded()
            val system = appContext.appContainer.webExtensionsOrNull ?: return // 网页增强关着：不装、也不创建拓展系统
            val exts = system.webViewExtensions()
            if (exts.isEmpty()) return
            extInstallation = system.tabHost.install(wv, exts, system.shim(), id)
        }.onFailure { Diag.log("web extensions: apply failed ${it.message}") }
    }

    private fun deferWebExtensions() {
        if (extDeferred) return
        extDeferred = true
        val self = java.lang.ref.WeakReference(this)
        dev.dsh.miuixbrowser.ext.ExtStartupGate.runWhenReady(appContext) { self.get()?.onExtensionsReady() }
    }

    /**
     * 拓展晚加载就绪（主线程）：补装 content script。已经加载好的页面不会有 content script（注入只对之后的导航生效），
     * 所以前台标签如果正显示着、且有拓展的 content script 会匹配它，就重新加载一次，让拓展对当前页也生效。
     */
    private fun onExtensionsReady() {
        extDeferred = false
        if (destroyed) return
        val pageUp = url.isNotEmpty() && !loading && !parked
        applyWebExtensions()
        if (!RELOAD_AFTER_LATE_EXT || !pageUp || extInstallation == null) return
        if (BrowserState.current?.activeTab !== this) return // 后台标签：切到前台再加载页面时本来就会带上拓展
        val matches = runCatching {
            appContext.appContainer.webExtensionsOrNull?.hasContentScriptFor(url) == true
        }.getOrDefault(false)
        if (matches) {
            Diag.log("web extensions: reload foreground tab $id once so late-loaded extensions apply")
            reload()
        }
    }

    data class ElementHit(val tag: String, val imageUrl: String, val selector: String)

    /** 「标记为广告」：按最近一次按下的位置找广告位元素，没找到时回调 [onNotFound]（实现见 [TabElementInspector]） */
    fun markAdAtTouch(onNotFound: () -> Unit) = inspector.markAdAtTouch(onNotFound)

    /** 油猴脚本注入 / 菜单命令的实现见 [TabScripts]；这里保留原有入口，调用方不用改。 */
    internal fun syncUserScripts() = scripts.sync()

    /** Gecko 下没有「网页增强」总开关：只看脚本总开关（脚本菜单里的开关） */
    internal fun gmScriptsEnabled(): Boolean = scriptsEnabled

    internal fun gmMenuReported(json: String, port: org.mozilla.geckoview.WebExtension.Port) = scripts.menuReported(json, port)

    internal fun gmRanReported(id: String) = scripts.ranReported(id)

    internal fun gmResetPage() = scripts.resetPage()

    /** 当前页面里脚本通过 GM_registerMenuCommand 注册的命令（仅主框架） */
    fun queryMenuCommands(onResult: (List<ScriptMenuCommand>) -> Unit) = scripts.queryMenuCommands(onResult)

    /** 当前页面（主框架）里真正匹配并运行的脚本数量 */
    fun queryRunningScripts(onResult: (Int) -> Unit) = scripts.queryRunningScripts(onResult)

    /** 当前页面（主框架）里真正匹配并运行的脚本 id */
    fun queryRunningScriptIds(onResult: (List<String>) -> Unit) = scripts.queryRunningScriptIds(onResult)

    fun runMenuCommand(id: String) = scripts.runMenuCommand(id)

    internal fun syncNav() {
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

    internal fun engineLoad(target: String, replace: Boolean = false) {
        proceedTarget = null // 换了地址：之前错误页上的「继续」不再有效
        pendingSsl = null
        if (replace) ops.loadReplacing(target) else ops.load(target)
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

    /** 距页面底部还剩多少像素（0 = 已到底，-1 = 引擎给不出来）：固定在底部的导航栏据此决定何时让出位置 */
    val scrollRemainingY: Int
        get() = ops.scrollRemaining

    /** Gecko：内容脚本回报页面是否有固定在底部的整宽栏（主线程） */
    internal fun geckoBottomBar(has: Boolean) {
        if (closed || destroyed) return
        pageHasBottomBar = has
    }

    /** Gecko：内容脚本回报距页面底部的剩余像素（主线程）。顺手补发一次滚动信号，底栏判定是事件驱动的 */
    internal fun geckoScrollRemaining(px: Int) {
        if (closed || destroyed) return
        geckoEngine?.updateScrollRemaining(px)
        scrollSignal.trySend(Unit)
    }

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

    /** 重新套用拦截：原生播放器开始 / 停止播放、页面换了文档（脚本里的拦截标记随文档丢失）后调用 */
    internal fun refreshMediaBlock() {
        applyMediaBlock(parked || PageMediaGate.nativePlaying)
    }

    /**
     * 主页盖住页面时 / 原生播放器播放时：暂停并拦住页面里的媒体（含之后页面脚本自己触发的播放）；页面还原 / 重新加载后解除。
     * Gecko：onPlay 里补暂停；WebView：脚本暂停现有媒体 + 捕获阶段监听 play 事件。
     */
    private fun applyMediaBlock(block: Boolean) {
        val g = geckoEngine
        if (g != null) {
            g.setMediaBlocked(block)
            if (block) g.pauseMedia()
            return
        }
        if (isGecko) return
        runCatching { webViewOrNull?.evaluateJavascript(if (block) PARK_MEDIA_JS else UNPARK_MEDIA_JS, null) }
    }

    /** 最近一次作为当前标签（或被创建）的时间，uptime 毫秒；闲置时长 = now - 它 */
    internal var lastActiveUptime = android.os.SystemClock.uptimeMillis()
        private set
    private var engineActiveFlag = false

    /** Gecko 诊断：内容脚本回报的视口尺寸（JSON 一行），连同原生侧的沉浸参数写进运行日志；WebView 标签无操作 */
    fun geckoViewportDiag(pageJson: String) {
        gecko?.logViewport(pageJson)
    }

    /** Gecko 原生沉浸：最大可收起高度 + 当前底部被推出屏幕的高度（像素）；WebView 标签无操作 */
    fun geckoApplyImmersive(maxPx: Int, clipPx: Int) {
        gecko?.applyImmersive(maxPx, clipPx)
    }

    // ======================================================================
    // 阅读模式：状态与逻辑在 [TabReader]，这里保留原有入口
    // ======================================================================

    /** 当前页面能进入阅读模式（地址栏最左侧显示阅读图标）。每次导航开始清零，页面加载后重新检测 */
    val readerable: Boolean get() = reader.readerable

    /** 正处于阅读模式（图标点亮） */
    val readerActive: Boolean get() = reader.readerActive

    /** 地址栏阅读图标的点击：未进入 → 进入；已进入 → 退出。[dark] 是当前应用的深浅色，阅读层按它配色。 */
    fun toggleReader(dark: Boolean) = reader.toggleReader(dark)

    /** 听书：取当前页面正文纯文本。 */
    fun extractReadableText(callback: (String) -> Unit) = reader.extractText(callback)

    /** 退出阅读模式，回到原页面（滚动位置还原） */
    fun exitReader() = reader.exitReader()

    /** Gecko：内容脚本回报状态（主线程） */
    internal fun geckoReaderState(port: org.mozilla.geckoview.WebExtension.Port, canRead: Boolean, active: Boolean) =
        reader.geckoState(port, canRead, active)

    /** Gecko：内容脚本断开（页面离开 / 重新加载） */
    internal fun geckoReaderGone(port: org.mozilla.geckoview.WebExtension.Port) = reader.geckoGone(port)

    // ======================================================================
    // Gecko 回调 → 标签状态（由 GeckoTabEngine 调用，均在主线程）
    // ======================================================================

    internal fun isBlankPage(u: String) = u == "about:blank"

    internal fun geckoPageStart(u: String) {
        if (closed || destroyed) return
        geckoEngine?.updateScrollRemaining(-1) // 新文档：旧页面的剩余距离作废，新页面的内容脚本连上后再回报
        if (ErrorPages.isProceedUrl(u)) return
        if (u == geckoErrorUri) { loading = true; return } // 错误页自身的加载：保留失败地址与错误信息
        geckoShowingError = false
        // 主页（url 为空）或被主页盖住（parked）时，页面侧回调不得改写 url；引擎初始化产生的 about:blank 也忽略
        if (!parked && url.isNotEmpty() && !(isBlankPage(u) && !isBlankPage(url))) url = u
        pageHost = runCatching { Uri.parse(u).host }.getOrNull().orEmpty()
        reader.reset()
        translate.onNavigation()
        loading = true
        errorText = null
        gmResetPage()
        syncNav()
    }

    internal fun geckoLocation(u: String) {
        if (closed || destroyed) return
        if (u == geckoErrorUri || ErrorPages.isProceedUrl(u)) return
        if (restore.stateRestorePending && !isBlankPage(u)) restore.stateRestorePending = false
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
    internal fun showWebViewErrorPage(view: WebView, failingUrl: String, spec: ErrorSpec) {
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
    internal fun proceedAfterCertWarning(view: WebView) {
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
     * 规则与 [interceptRedirect]（external = true）一致：空白页 / 首次加载上的唤起同样要走权限；总是允许 → 直接唤起；
     * 总是拒绝 → 提示已拦截；询问 → 弹窗，同意才唤起。当前页面始终原地不动。
     */
    internal fun geckoExternalRequest(uri: Uri) {
        // 空白页 / 首次加载（还没有 host）也要走权限，否则新标签里首个请求 302 到 weixin:// 就能绕过
        val from = pageHost.ifEmpty { runCatching { Uri.parse(url).host }.getOrNull().orEmpty() }
        // 例外网站优先（与 WebView 一致）；带 permKey 才能在弹窗里「记住此网站的选择」
        val mode = effExternalAppMode()
        if (mode == 1) {
            launchExternalApp(uri)
            return
        }
        if (mode == 2) {
            notifyRedirectBlocked(true, "")
            return
        }
        if (promptBusy()) return
        askRedirect(from.ifEmpty { "about:blank" }, tr("打开其他应用"), GeckoSitePerms.EXTERNAL_APP.takeIf { from.isNotEmpty() }) { allow ->
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

    /** [replace]：替换当前历史项而不是新增（拓展 tabs.update 的 loadReplace；仅 WebView 且系统支持 navigate 时生效） */
    fun load(input: String, replace: Boolean = false) {
        restore.dropPending()
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
        engineLoad(target, replace)
        syncNav()
    }

    fun loadUrl(raw: String) {
        restore.dropPending()
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
        // 请求头 Sec-CH-UA-Mobile / Sec-CH-UA-Platform 和 navigator.userAgentData 跟着 UA 走，别和 UA 字符串矛盾
        dev.dsh.miuixbrowser.engine.webview.UaClientHints.apply(webView.settings, ua, defaultUserAgent)
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
                if (dev.dsh.miuixbrowser.engine.webview.IncognitoProfile.isBound(wv)) {
                    // 无痕 Profile 有自己的 CookieManager：只管它，不碰进程级（默认 Profile）的开关；无痕里登录也能保持，Profile 随最后一个无痕标签一起删
                    val cm = dev.dsh.miuixbrowser.engine.webview.IncognitoProfile.cookieManagerFor(wv)
                    cm.setAcceptCookie(cookieMode != 3)
                    cm.setAcceptThirdPartyCookies(wv, cookieMode == 0)
                } else {
                    // 官方 CookieManager：总开关（无痕 / 拒绝全部时关）+ 按 WebView 的第三方开关
                    CookieManager.getInstance().setAcceptCookie(!incognito && cookieMode != 3)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, cookieMode == 0)
                }
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
    internal var blockTrackers = false

    @Volatile
    internal var pageHost = ""

    internal var lastDntUrl = ""

    /** 全局「网站媒体发声」：0 询问 / 1 允许 / 2 拒绝（按站点的例外见 [effMediaMode]） */
    @Volatile
    internal var mediaModeGlobal = 1

    internal var cameraMode = 0
    internal var micMode = 0
    internal var locationMode = 0
    private var notificationMode = 0

    /** 网页重定向 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    @Volatile
    internal var redirectMode = 0

    /** 打开其他应用 0=每次使用时询问 1=总是允许 2=总是拒绝（与「网页重定向」相互独立） */
    @Volatile
    internal var externalAppMode = 0

    /** 正在等待用户决定的重定向，避免脚本循环跳转时连续弹窗 */
    internal var redirectPromptPending = false
    internal var redirectPromptAt = 0L
    internal var lastRedirectToast = 0L

    /** 每个 origin 本次运行最多弹几次豁免请求，以及两次之间的最短间隔，防止页面骚扰用户 */
    internal val fpExemptAsked = HashMap<String, LongArray>() // origin -> [次数, 上次 elapsedRealtime]

    /** 后台标签没有弹窗载体时先挂起，切到该标签再弹，而不是悄悄当成拒绝 */
    internal var deferredPrompt: DeferredPrompt? = null

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

    @Volatile
    internal var dntEnabled = false

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
    internal fun installDesktopScript(enabled: Boolean) {
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
    internal var savedUrl: String = ""

    /** 页面地址里落盘的那一份：about: 开头的不存（空白页） */
    internal val persistedPageUrl: String
        get() = effectiveUrl.let { if (it.startsWith("about:")) "" else it }

    /** Gecko 引擎当前是否存活 */
    internal val hasEngine: Boolean get() = geckoEngine != null

    // ---------------------------------------------------------------- 待恢复 / 后台回收：实现见 [TabRestore]

    /** 标签的页面还没加载、只是用保存的地址 / 标题占位（卡片上要显示这个页面的域名，而不是「主页」） */
    internal val showsSavedPage: Boolean get() = restore.showsSavedPage

    /** 要保存的桌面模式状态：还没加载的恢复标签也保留这个标志 */
    internal val wantsDesktop: Boolean get() = restore.wantsDesktop

    internal fun prepareRestore(
        target: String,
        savedTitle: String,
        onHome: Boolean,
        desktop: Boolean,
        fromLink: Boolean,
        hasState: Boolean = false,
    ) = restore.prepare(target, savedTitle, onHome, desktop, fromLink, hasState)

    /** 成为当前标签时调用：该显示页面的恢复标签此刻才加载 */
    internal fun wakeIfPending() = restore.wakeIfPending()

    /** 渲染进程崩溃后顶替上来的前台标签：不自动重载（页面可能一加载就崩，会死循环），显示崩溃页等用户点「重试」 */
    internal fun adoptCrashedPage(failingUrl: String, savedTitle: String, desktop: Boolean, fromLink: Boolean, didCrash: Boolean) =
        restore.adoptCrashed(failingUrl, savedTitle, desktop, fromLink, didCrash)

    internal fun canDiscardEngine(): Boolean = restore.canDiscard()

    internal fun discardEngine(): Boolean = restore.discardEngine()

    internal fun persistSessionState() = restore.persistSessionState()

    /** 标签实际承载的页面地址：回过主页的标签 url 为空，页面还在 WebView 里，用 savedUrl */
    val effectiveUrl: String
        get() = url.ifEmpty { savedUrl }

    /** 完全空白的标签（没有页面、也没有被主页盖住的页面） */
    val isBlank: Boolean
        get() = url.isEmpty() && savedUrl.isBlank()

    /** 若这个标签正显示主页（页面被盖住），还原成原来的页面，不重新加载 */
    fun restoreFromHome() {
        // 恢复出来、页面还没加载的标签：这时才真正加载
        if (restore.pendingRestore) {
            restore.loadPending()
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
        // 页面被主页盖在下面（引擎仍存活）：切走就要暂停正在播放的媒体
        pauseMedia()
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
            pauseMedia()
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

    /**
     * 拓展 tabs.goBack / goForward 用（仅 WebView 内核）：只在页面自己的历史里走一步，返回是否真的发出了导航。
     * 与 [goBack] 的区别：不退出阅读模式，也不会在「从主页进入的第一页」把标签收回主页，
     * 更不会在主页上把被盖住的页面还原出来——拓展没有办法替用户藏起 / 翻出页面。
     */
    internal fun stepHistoryForExtension(forward: Boolean): Boolean {
        if (isGecko || url.isEmpty()) return false
        if (forward) {
            if (!engineCanGoForward()) return false
            fpInjector?.prepareHistory(1)
            ops.goForward()
            return true
        }
        if (!engineCanGoBack()) return false
        if (fromHome && historyIndex() <= homeAnchor + 1) return false
        fpInjector?.prepareHistory(-1)
        ops.goBack()
        return true
    }

    fun destroyNow() {
        if (destroyed) return
        destroyed = true
        if (!isGecko) runCatching { appContext.appContainer.webExtensionsIfCreated?.onWebNavigationForgetTab(id) }
        // 这几步任何一步抛异常都不能挡住后面的引擎销毁，否则会话没关，媒体停不下来
        runCatching { cancelFileChooser() }
        runCatching { exitCustomView() }
        runCatching { blob.discardAll() }
        runCatching { fpInjector?.clear() }
        runCatching { extInstallation?.close() }
        extInstallation = null
        runCatching { scripts.release() }
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
        // 绑在无痕 Profile 上的最后一个 WebView 销毁后，整个 Profile（Cookie / 缓存 / 站点数据）一起删掉
        webViewOrNull?.let { dev.dsh.miuixbrowser.engine.webview.IncognitoProfile.release(it) }
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
        /** 拓展晚加载后，前台已加载好的页面若有 content script 匹配，是否重新加载一次让拓展生效（关掉则只对之后的导航生效） */
        const val RELOAD_AFTER_LATE_EXT = true

        private const val PARK_MEDIA_JS =
            "(function(){try{window.__mfPark=true;if(!window.__mfParkL){window.__mfParkL=function(e){" +
                "if(window.__mfPark){try{e.target.pause()}catch(x){}}};document.addEventListener('play',window.__mfParkL,true)}" +
                "document.querySelectorAll('video,audio').forEach(function(m){try{if(!m.paused)m.pause()}catch(e){}})}catch(e){}})()"
        private const val UNPARK_MEDIA_JS = "(function(){try{window.__mfPark=false}catch(e){}})()"
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
