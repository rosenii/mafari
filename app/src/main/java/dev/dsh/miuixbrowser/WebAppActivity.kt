package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.AdBlockBridge
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.EngineKind
import dev.dsh.miuixbrowser.browser.ShortcutHelper
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoRuntimeHolder
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.engine.gecko.GeckoSitePerms
import dev.dsh.miuixbrowser.engine.webview.FingerprintInjector
import dev.dsh.miuixbrowser.engine.webview.FpFallbackBridge
import dev.dsh.miuixbrowser.net.WebViewProxy
import dev.dsh.miuixbrowser.engine.webview.WebViewDebug
import dev.dsh.miuixbrowser.script.ExtensionStore
import dev.dsh.miuixbrowser.script.GmBridge
import dev.dsh.miuixbrowser.script.ScriptResources
import dev.dsh.miuixbrowser.script.UserScript
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.ErrorPages
import dev.dsh.miuixbrowser.util.ErrorSpec
import dev.dsh.miuixbrowser.util.isSameSite
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * 「添加到桌面」生成的轻应用：只有一个全屏 WebView，没有地址栏、底栏、标签页。
 *
 * - 与 Mafari 浏览器共用同一份 WebView 数据（Cookie / 登录态 / 本地存储），也沿用代理、去广告网络拦截、深浅色设置。
 * - 同站跳转留在轻应用内；用户点击的跨站链接交给 Mafari 浏览器打开；登录 / 支付等无手势的跨站跳转原地放行。
 * - 返回键先回退网页历史，到头后退出；在最近任务里作为独立任务显示（标题 + 网站图标）。
 * - 不含油猴脚本、元素隐藏、标签页、下载页等浏览器功能；下载仍交给 Mafari 的下载器。
 */
class WebAppActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView

    /** 指纹保护：按 origin 注册 document-start 脚本（见 FingerprintInjector / docs/FINGERPRINT.md） */
    private lateinit var fpInjector: FingerprintInjector
    private lateinit var progress: ProgressBar
    private lateinit var settings: AppSettings
    private lateinit var insetsController: WindowInsetsControllerCompat

    private var startUrl = ""
    private var startHost = ""
    private var pageHost = ""
    private var appTitle = ""
    private var dark = false
    private var defaultBg = Color.WHITE

    // Gecko 内核（PWA）：intent 里 engine = gecko 且本包带了 GeckoView 时，用 BrowserTab 承载 GeckoView，
    // 窗口 / 状态栏 / 返回键 / 最近任务等与 WebView 轻应用共用同一套；WebView 专属的那些（指纹、去广告桥、脚本桥）不走
    private var geckoMode = false
    private var geckoTab: BrowserTab? = null
    private var scopeUrl = ""
    private var displayMode = "standalone"
    private var orientationHint = ""
    private var imeAnimating = false

    // 沉浸浏览：顶部状态栏留白随滚动收起 / 回落（与浏览器主界面一致）
    private var topInset = 0
    private var sideLeft = 0
    private var sideRight = 0
    private var imeBottom = 0
    private var topPad = 0
    private var topCollapsed = false
    private var topAnimator: ValueAnimator? = null

    // 下拉刷新
    private lateinit var refreshSpinner: RefreshSpinnerView

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: ((Boolean) -> Unit)? = null

    private companion object {
        const val REQ_FILE = 7101
        const val REQ_PERM = 7102
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        fileCallback = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val callback = pendingPermission
        pendingPermission = null
        callback?.invoke(grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED })
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val scheme = data?.scheme?.lowercase()
        if (data == null || (scheme != "http" && scheme != "https")) {
            finish()
            return
        }
        startUrl = data.toString()
        startHost = data.host.orEmpty()
        pageHost = startHost
        appTitle = intent.getStringExtra(ShortcutHelper.EXTRA_TITLE).orEmpty().ifBlank { startHost }
        updateTaskDescription(appTitle)

        geckoMode = intent.getStringExtra(ShortcutHelper.EXTRA_ENGINE) == ShortcutHelper.ENGINE_GECKO &&
            EngineSupport.GECKO_BUNDLED && GeckoRuntimeHolder.supported()
        settings = appContainer.settings
        GeckoSitePerms.load(this) // 例外网站（与浏览器共用）
        AdBlock.init(this)
        if (!geckoMode) {
            WebViewDebug.load(this)
            AdBlock.startCompile() // 独立 PWA 窗口没有主界面的启动阶段，直接编译
            WebViewProxy.applyFrom(settings)
        }

        val systemDark =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        dark = when (settings.themeMode) {
            1 -> false
            2 -> true
            else -> systemDark
        }
        defaultBg = if (dark) 0xFF1A1A1A.toInt() else Color.WHITE

        // 全屏沉浸：页面画到状态栏 / 手势条下面，由根布局按系统栏留出边距
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        insetsController = WindowCompat.getInsetsController(window, window.decorView)
        applySystemBarIcons(defaultBg)

        root = FrameLayout(this).apply { setBackgroundColor(defaultBg) }
        if (geckoMode) {
            setContentView(root)
            installInsets()
            startGecko()
            return
        }
        webView = WebView(this).apply { setBackgroundColor(defaultBg) }
        fpInjector = FingerprintInjector(webView)
        fpInjector.onUnprotectedBlocked = { target ->
            showErrorPage(webView, target, ErrorPages.fingerprintBlocked())
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            isIndeterminate = false
        }
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            progress,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 2.5f).toInt()),
        )
        val density = resources.displayMetrics.density
        refreshSpinner = RefreshSpinnerView(this).apply {
            visibility = View.GONE
            elevation = 6f * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (dark) 0xEB2A2A2A.toInt() else 0xEBFFFFFF.toInt())
            }
        }
        root.addView(
            refreshSpinner,
            FrameLayout.LayoutParams((34 * density).toInt(), (34 * density).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = (18 * density).toInt() },
        )
        setContentView(root)

        installInsets()

        configureWebView()
        setupUserScripts()
        setupCosmeticFilter()
        setupImmersiveScroll()
        setupPullRefresh()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> {
                        fpInjector.prepareHistory(-1)
                        webView.goBack()
                    }
                    else -> finish()
                }
            }
        })

        // 先为起始 origin 注册再加载；恢复的历史页若 origin 不同，onPageStarted 会补注册（只对之后的加载生效）
        fpInjector.prepare(startUrl)
        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        if (webView.url == null) webView.loadUrl(startUrl)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        geckoTab?.let {
            it.setEngineActive(true)
            applyGeckoWindowMode() // 视频全屏退出时会把方向恢复成「不限制」，这里按 manifest 重新设置
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        geckoTab?.destroyNow()
        geckoTab = null
        scriptHandlers.forEach { runCatching { it.remove() } }
        scriptHandlers.clear()
        if (::fpInjector.isInitialized) runCatching { fpInjector.clear() }
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 窗口边距（两种内核共用）

    /** 沉浸：网页一直铺到手势小白条下方（底部只在软键盘弹出时让出），顶部状态栏留白由滚动控制 */
    private fun installInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            topInset = bars.top
            sideLeft = bars.left
            sideRight = bars.right
            // Gecko：键盘动画期间不跟着每一帧改边距（GeckoView 每帧重排会让窗口抖），只在动画开始 / 结束时各定一次
            if (!(geckoMode && imeAnimating)) imeBottom = ime.bottom
            if (topAnimator?.isRunning != true) topPad = if (topCollapsed) 0 else topInset
            applyRootPadding()
            insets
        }
        if (!geckoMode) return
        fun imeEnd(): Int =
            ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeBottom = imeEnd()
                        applyRootPadding()
                    }
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat = insets

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimating = false
                        imeBottom = imeEnd()
                        applyRootPadding()
                    }
                }
            },
        )
    }

    // ---------------------------------------------------------------- Gecko（PWA）

    /**
     * Gecko 内核的轻应用：BrowserTab 承载 GeckoView（文件选择 / 权限 / 全屏 / 重定向规则等沿用浏览器里的同一套），
     * 这里只补独立窗口需要的：站外链接交给浏览器、manifest 的主题色 / 方向 / 显示模式、返回键。
     */
    private fun startGecko() {
        scopeUrl = intent.getStringExtra(ShortcutHelper.EXTRA_SCOPE).orEmpty()
        displayMode = intent.getStringExtra(ShortcutHelper.EXTRA_DISPLAY) ?: "standalone"
        orientationHint = intent.getStringExtra(ShortcutHelper.EXTRA_ORIENT).orEmpty()
        val theme = if (intent.hasExtra(ShortcutHelper.EXTRA_THEME)) intent.getIntExtra(ShortcutHelper.EXTRA_THEME, 0) else null
        val bg = if (intent.hasExtra(ShortcutHelper.EXTRA_BG)) intent.getIntExtra(ShortcutHelper.EXTRA_BG, 0) else null
        // 状态栏 / 手势条区域与网页加载前的底色：manifest 的 theme_color，其次 background_color
        val chrome = (theme ?: bg ?: defaultBg) or 0xFF000000.toInt()
        root.setBackgroundColor(chrome)
        applySystemBarIcons(chrome)

        extensions = appContainer.extensions
        val tab = BrowserTab(this, EngineKind.GECKO) // 传 Activity：弹窗 / 权限 / 全屏都要靠它找到窗口
        geckoTab = tab
        // 这些要在引擎创建（读 contentView）之前设好：GeckoView 创建时就会取用
        tab.pageEnhancements = settings.pageEnhancements
        tab.scriptsEnabled = settings.scriptsEnabled
        tab.userScripts = activeScripts()
        tab.customUserAgent = settings.userAgent
        tab.searchTemplate = settings.searchTemplate
        val view = tab.contentView
        tab.applyUserAgent()
        tab.applyPrivacy(
            cookieMode = settings.cookieMode,
            sendDnt = settings.sendDnt,
            webrtcDisabled = settings.webrtcDisabled,
            clipboardMode = settings.clipboardPermission,
            mediaMode = settings.mediaAutoplay,
            cameraMode = settings.cameraPermission,
            micMode = settings.micPermission,
            locationMode = settings.locationPermission,
            notificationMode = settings.notificationPermission,
            redirectMode = settings.redirectPermission,
            externalAppMode = settings.externalAppPermission,
            blockTrackers = settings.blockTrackingScripts,
            fingerprintProtect = AdBlock.fpCanvas || AdBlock.fpWebgl || AdBlock.fpAudio || AdBlock.fpFonts,
        )
        tab.applyDisplayTheme(dark)

        // 站外的主框架导航：只有用户亲手点的才弹到浏览器（登录 / 支付回调等自动跳转原地放行，与 WebView 轻应用一致）；
        // manifest 声明了 scope 时，同站但在 scope 之外的链接也算站外
        tab.geckoNavGate = { target, gesture, redirect ->
            val uri = Uri.parse(target)
            val host = uri.host.orEmpty()
            val crossSite = host.isNotEmpty() && !isSameSite(host, startHost)
            val outOfScope = !crossSite && scopeUrl.isNotEmpty() && sameOrigin(target, scopeUrl) && !target.startsWith(scopeUrl)
            if ((crossSite || outOfScope) && gesture && !redirect) {
                openInBrowser(uri)
                true
            } else {
                false
            }
        }
        // window.open / target=_blank：同站在本窗口打开，跨站交给浏览器
        tab.onOpenInNewTab = { u ->
            val uri = Uri.parse(u)
            val host = uri.host.orEmpty()
            if (host.isNotEmpty() && !isSameSite(host, startHost)) openInBrowser(uri) else tab.loadUrl(u)
        }
        tab.onRequestClose = { finish() }
        // 下载：轻应用没有下载管理，http(s) 交给浏览器处理（带登录态的文件、blob 暂不支持）
        tab.onDownload = { url, _, _, _ ->
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                openInBrowser(Uri.parse(url))
            } else {
                Toast.makeText(this, tr("请在浏览器中下载"), Toast.LENGTH_SHORT).show()
            }
        }
        tab.onShowFileChooser = { params, callback ->
            if (params.mode == 3) {
                callback.onReceiveValue(null) // 「另存为」类请求不接，避免页面一直等待
            } else {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                runCatching { startActivityForResult(params.createIntent(), REQ_FILE) }.onFailure {
                    fileCallback = null
                    callback.onReceiveValue(null)
                }
            }
        }
        tab.onPermissionPrompt = { origin, what, _, decide ->
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    decide(false)
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(origin)
                        .setMessage(what)
                        .setCancelable(false)
                        .setPositiveButton(tr("允许")) { _, _ -> decide(true) }
                        .setNegativeButton(tr("拒绝")) { _, _ -> decide(false) }
                        .show()
                }
            }
        }
        tab.requestAndroidPermissions = { perms, done ->
            ensurePermissions(perms.toList(), anyOk = false) { done() }
        }

        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyGeckoWindowMode()
        tab.setEngineActive(true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = geckoTab
                if (t == null) {
                    finish()
                } else if (t.geckoFullscreen || t.canGoBack) {
                    t.goBack() // 全屏中先退全屏；其余按页面历史后退
                } else {
                    finish()
                }
            }
        })
        tab.loadUrl(startUrl)
    }

    /** manifest 的方向 / 显示模式：portrait* / landscape* 跟随系统自动旋转开关；fullscreen 隐藏系统栏 */
    private fun applyGeckoWindowMode() {
        if (geckoTab?.geckoFullscreen == true) return // 视频全屏自己管方向和系统栏
        requestedOrientation = when {
            orientationHint.startsWith("portrait") -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            orientationHint.startsWith("landscape") -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (displayMode == "fullscreen") {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun sameOrigin(a: String, b: String): Boolean {
        val x = Uri.parse(a)
        val y = Uri.parse(b)
        return x.scheme.equals(y.scheme, true) && x.host.equals(y.host, true) && x.port == y.port
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
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
            setSupportMultipleWindows(false) // target=_blank / window.open 都在当前页内加载
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString.replace("; wv", "")
        }
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(settings.cookieMode != 3)
            setAcceptThirdPartyCookies(webView, settings.cookieMode == 0)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
                if (!cosmeticDocStart) view.evaluateJavascript(AdBlock.COSMETIC_JS, null)
                fpInjector.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                injectMissingUserScripts(view)
                if (!cosmeticDocStart) view.evaluateJavascript(AdBlock.COSMETIC_JS, null)
                syncThemeColor()
                view.postDelayed({ syncThemeColor() }, 600)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                fpInjector.onCommitted(url)
                runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() }?.let { pageHost = it }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (!settings.adblockInWebApp) null else AdBlock.intercept(request, pageHost, settings.blockTrackingScripts)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> {
                        val host = uri.host.orEmpty()
                        val crossSite = host.isNotEmpty() && !isSameSite(host, startHost)
                        // 只有「用户亲手点的」跨站跳转才弹出到浏览器；登录 / 支付回调等自动跳转原地放行
                        if (crossSite && request.hasGesture() && !request.isRedirect && request.isForMainFrame) {
                            openInBrowser(uri)
                            true
                        } else {
                            // 主框架导航放行前，先为目标 origin 注册指纹保护脚本
                            if (request.isForMainFrame) fpInjector.prepare(uri.toString())
                            false
                        }
                    }
                    "file", "about", "data", "javascript", "blob" -> false
                    // intent:// weixin:// alipays:// 等：与浏览器一致，走「打开其他应用」权限。
                    // 点击与自动唤起一律按 询问 / 总是允许 / 总是拒绝 处理
                    else -> {
                        requestExternalApp(uri)
                        true
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                showErrorPage(view, request.url.toString(), ErrorPages.fromWebView(error.errorCode, error.description?.toString()))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                // 只接管空响应体的 4xx / 5xx；带正文的是网站自己的错误页，照常展示
                if (!request.isForMainFrame) return
                if (!ErrorPages.shouldReplaceHttpError(errorResponse.statusCode, errorResponse.responseHeaders)) return
                showErrorPage(view, request.url.toString(), ErrorPages.fromHttp(errorResponse.statusCode, errorResponse.reasonPhrase))
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // WebView 渲染进程崩溃 / 被系统回收：返回 true 避免整个应用被带崩，直接退出，下次点图标重新打开
                finish()
                return true
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String?,
                realm: String?,
            ) {
                if (host != null && WebViewProxy.shouldAutoAuth(host)) {
                    handler.proceed(WebViewProxy.authUser, WebViewProxy.authPass)
                } else {
                    handler.cancel()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                WebViewDebug.logConsole(message)
                return super.onConsoleMessage(message)
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank() && !title.startsWith("http")) updateTaskDescription(title)
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean {
                // 「另存为」类请求（showSaveFilePicker）轻应用不接，避免页面一直等待
                if (fileChooserParams.mode == 3) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return runCatching { startActivityForResult(fileChooserParams.createIntent(), REQ_FILE) }
                    .onFailure {
                        fileCallback = null
                        filePathCallback.onReceiveValue(null)
                    }
                    .let { true }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermission(request) }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                runOnUiThread {
                    val host = runCatching { Uri.parse(origin).host }.getOrNull().orEmpty()
                    // 例外网站优先于全局设置：拒绝 → 直接拒；允许 → 不受「只放行本站」限制
                    val exc = GeckoSitePerms.override(host, GeckoSitePerms.LOCATION)
                    val mode = exc ?: settings.locationPermission
                    if (mode == 2 || (exc != 1 && !isSameSite(host, startHost))) {
                        callback.invoke(origin, false, false)
                    } else {
                        ensurePermissions(
                            listOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                            anyOk = true,
                        ) { ok -> callback.invoke(origin, ok, false) }
                    }
                }
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                (window.decorView as ViewGroup).addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() = hideCustomView()
        }

        webView.setDownloadListener { link, userAgent, disposition, mime, _ ->
            if (link.startsWith("http", ignoreCase = true)) {
                val name = Downloader.withExtension(Downloader.fileNameFrom(link, disposition), mime)
                Downloader.start(
                    this, link, userAgent, name, mime,
                    referer = webView.url,
                    withCookies = true,
                )
                Toast.makeText(this, tr("已开始下载，可在 Mafari「下载」中查看"), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, tr("这类下载请在 Mafari 浏览器中打开该网站再试"), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------- 去广告：元素隐藏

    /** 元素隐藏脚本是否已作为 document-start 脚本装上（没装上时由页面回调兜底注入） */
    private var cosmeticDocStart = false

    /**
     * 与浏览器一致：装上 MafariAdblock 桥，把 COSMETIC_JS 作为 document-start 脚本注入所有框架，
     * 页面里的脚本向桥取本站点的隐藏 CSS。开关、规则订阅与站点白名单都在 AdBlock.cosmeticCss 里判断。
     * 必须在首次加载网页之前调用。
     */
    private fun setupCosmeticFilter() {
        runCatching { webView.addJavascriptInterface(AdBlockBridge { settings.adblockInWebApp }, "MafariAdblock") }
            .onFailure { Diag.log("webapp adblock: bridge install failed ${it.message}") }
        // 仅降级路径：不支持 DOCUMENT_START_SCRIPT 时才安装无参指纹桥；轻应用没有确认弹窗，不安装可信通道
        if (!fpInjector.docStartSupported) {
            runCatching { webView.addJavascriptInterface(FpFallbackBridge(fpInjector), "MafariFp") }
        }
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, AdBlock.COSMETIC_JS, setOf("*"))
                cosmeticDocStart = true
            }
        }.onFailure { Diag.log("webapp adblock: document-start install failed ${it.message}") }
    }

    // ------------------------------------------------------------ 油猴脚本

    private var destroyed = false
    private val scriptHandlers = mutableListOf<androidx.webkit.ScriptHandler>()
    private var installedScriptsSignature = ""
    private var prefetchingScripts = false
    private lateinit var extensions: ExtensionStore

    /** 当前应启用的脚本：受「网页增强」「脚本」两个总开关控制，与 Mafari 浏览器一致 */
    private fun activeScripts(): List<UserScript> {
        if (!settings.pageEnhancements || !settings.scriptsEnabled || !settings.scriptsInWebApp) return emptyList()
        return extensions.items.filter { it.enabled }
    }

    /**
     * 轻应用里的油猴脚本：装上 GM 桥（GM_setValue / GM_xmlhttpRequest / GM_openInTab 等），
     * 再把启用的脚本作为 document-start 脚本注入（URL 匹配与 run-at 由运行时按各框架处理）。
     * 脚本列表与浏览器共用同一份存储；必须在首次加载网页之前调用。
     */
    private fun setupUserScripts() {
        extensions = appContainer.extensions
        runCatching {
            webView.addJavascriptInterface(
                GmBridge(
                    applicationContext,
                    { webView },
                    // 轻应用把链接交给外部浏览器：没有可追踪的标签，id 用不上（close 为空操作）
                    { _, target, _ ->
                        val uri = runCatching { Uri.parse(target) }.getOrNull()
                        val scheme = uri?.scheme?.lowercase()
                        if (uri != null && (scheme == "http" || scheme == "https")) openInBrowser(uri)
                    },
                    { link, name, onResult ->
                        if (link.startsWith("http", ignoreCase = true)) {
                            val fileName = name.ifBlank { Downloader.fileNameFrom(link, null) }
                            Downloader.start(
                                this, link, runCatching { webView.settings.userAgentString }.getOrNull(), fileName, null, onResult,
                                referer = webView.url,
                                withCookies = true,
                            )
                            Toast.makeText(this, tr("已开始下载，可在 Mafari「下载」中查看"), Toast.LENGTH_SHORT).show()
                        } else {
                            onResult?.invoke(false, "unsupported url")
                        }
                    },
                ),
                "MafariGM",
            )
        }.onFailure { Diag.log("webapp userscript: bridge install failed ${it.message}") }
        syncUserScripts()
    }

    private fun syncUserScripts() {
        if (destroyed) return
        val enabled = activeScripts()
        val signature = enabled.joinToString("|") {
            "${it.id}:${it.code.hashCode()}:${it.match.hashCode()}:${ScriptResources.cachedCount(applicationContext, it.code)}"
        }

        // @require / @resource 还没缓存的先在后台取回来，取到后自动重建
        val needFetch = enabled.filter { ScriptResources.missing(applicationContext, it.code).isNotEmpty() }
        if (needFetch.isNotEmpty() && !prefetchingScripts) {
            prefetchingScripts = true
            Thread {
                val changed = needFetch.map { ScriptResources.prefetch(applicationContext, it.code) }.any { it }
                runOnUiThread {
                    prefetchingScripts = false
                    if (changed) syncUserScripts()
                }
            }.start()
        }
        if (signature == installedScriptsSignature) return

        scriptHandlers.forEach { runCatching { it.remove() } }
        scriptHandlers.clear()
        installedScriptsSignature = signature
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        enabled.forEach { script ->
            runCatching {
                val source = UserscriptEngine.buildSource(applicationContext, script)
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, source, setOf("*"))
                    ?.let { scriptHandlers.add(it) }
            }.onFailure { Diag.log("webapp userscript: install failed ${script.name}: ${it.message}") }
        }
        Diag.log("webapp userscript: document-start handlers=${scriptHandlers.size}/${enabled.size}")
    }

    /** 兜底：系统不支持 document-start 注入、或 handler 装得晚于本次导航时，页面加载完后补注入主框架 */
    private fun injectMissingUserScripts(view: WebView) {
        syncUserScripts()
        val enabled = activeScripts()
        if (enabled.isEmpty()) return
        val query = "(function(){try{return JSON.stringify(Object.keys(window.__mafariSeen||{}))}catch(e){return '[]'}})()"
        view.evaluateJavascript(query) { raw ->
            val seen = runCatching {
                val text = org.json.JSONTokener(raw ?: "null").nextValue() as? String ?: "[]"
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { array.getString(it) }.toSet()
            }.getOrDefault(emptySet())
            enabled.filter { it.id !in seen }.forEach { script ->
                runCatching { view.evaluateJavascript(UserscriptEngine.buildSource(applicationContext, script), null) }
            }
        }
    }

    // ------------------------------------------------- 沉浸浏览 / 下拉刷新

    private fun applyRootPadding() = root.setPadding(sideLeft, topPad, sideRight, imeBottom)

    private fun setTopCollapsed(collapsed: Boolean) {
        if (topCollapsed == collapsed) return
        topCollapsed = collapsed
        val target = if (collapsed) 0 else topInset
        topAnimator?.cancel()
        topAnimator = ValueAnimator.ofInt(topPad, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                topPad = it.animatedValue as Int
                applyRootPadding()
            }
            start()
        }
    }

    /** 状态栏：初始留白，向下滚动顶满，回顶回落（阈值与浏览器一致：下滚约 100px 收起，上滚约 120px 或回顶恢复） */
    private fun setupImmersiveScroll() {
        var lastShow = 0
        var downAccum = 0f
        var upAccum = 0f
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val offset = scrollY.coerceAtLeast(0)
            val dy = offset - oldScrollY.coerceAtLeast(0)
            when {
                offset <= 12 -> {
                    setTopCollapsed(false)
                    lastShow = offset
                    downAccum = 0f
                    upAccum = 0f
                }
                dy > 0 -> {
                    upAccum = 0f
                    downAccum += dy
                    if (downAccum >= 100f && offset - lastShow >= 96) {
                        setTopCollapsed(true)
                        downAccum = 0f
                        upAccum = 0f
                    }
                }
                dy < 0 -> {
                    downAccum = 0f
                    upAccum += -dy
                    if (topCollapsed && upAccum >= 120f) {
                        setTopCollapsed(false)
                        lastShow = offset
                        upAccum = 0f
                    } else if (!topCollapsed) {
                        lastShow = offset
                    }
                }
            }
        }
    }

    /** 下拉刷新：页面在顶部时下拉约 4cm 松手触发（与浏览器一致），带触感反馈与顶部旋转指示 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPullRefresh() {
        val thresholdPx = resources.displayMetrics.xdpi / 2.54f * 4f
        var touchDownY = 0f
        var pulled = 0f
        fun reset() {
            pulled = 0f
            refreshSpinner.visibility = View.GONE
        }
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownY = event.y
                    pulled = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (webView.scrollY <= 0 && event.y > touchDownY) {
                        pulled = event.y - touchDownY
                        val progress = (pulled / thresholdPx).coerceIn(0f, 1f)
                        if (progress > 0.05f) {
                            // 顶部栏收起时根布局没有顶部留白：补上状态栏 / 摄像头挖孔的高度，避免图标压在摄像头上
                            (refreshSpinner.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                                val want = (18 * resources.displayMetrics.density).toInt() + (topInset - topPad).coerceAtLeast(0)
                                if (lp.topMargin != want) { lp.topMargin = want; refreshSpinner.layoutParams = lp }
                            }
                            refreshSpinner.visibility = View.VISIBLE
                            refreshSpinner.progress = progress
                            refreshSpinner.alpha = 0.4f + 0.6f * progress
                            val scale = 0.7f + 0.3f * progress
                            refreshSpinner.scaleX = scale
                            refreshSpinner.scaleY = scale
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulled > thresholdPx && webView.scrollY <= 0) {
                        webView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        fpInjector.prepare(webView.url)
                        webView.reload()
                    }
                    reset()
                }
            }
            false
        }
    }

    // ------------------------------------------------------------- helpers

    private fun hideCustomView() {
        val view = customView ?: return
        (window.decorView as ViewGroup).removeView(view)
        customView = null
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun handleWebPermission(request: PermissionRequest) {
        val host = request.origin?.host.orEmpty()
        val camExc = GeckoSitePerms.override(host, GeckoSitePerms.CAMERA)
        val micExc = GeckoSitePerms.override(host, GeckoSitePerms.MIC)
        // 例外网站有「允许」时不受「只放行本站」限制
        if (!isSameSite(host, startHost) && camExc != 1 && micExc != 1) {
            request.deny()
            return
        }
        val camMode = camExc ?: settings.cameraPermission
        val micMode = micExc ?: settings.micPermission
        val needs = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) {
            if (camMode == 2) { request.deny(); return }
            needs += android.Manifest.permission.CAMERA
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
            if (micMode == 2) { request.deny(); return }
            needs += android.Manifest.permission.RECORD_AUDIO
        }
        val supported = setOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
        )
        val grantable = request.resources.filter { it in supported }.toTypedArray()
        if (grantable.isEmpty()) {
            request.deny()
            return
        }
        ensurePermissions(needs, anyOk = false) { ok -> if (ok) request.grant(grantable) else request.deny() }
    }

    /** 缺哪个系统权限就申请哪个；[anyOk] 为 true 时任意一个通过即可（定位粗略 / 精确） */
    private fun ensurePermissions(permissions: List<String>, anyOk: Boolean, done: (Boolean) -> Unit) {
        fun granted(p: String) =
            checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val missing = permissions.filterNot { granted(it) }
        val already = if (anyOk) permissions.any { granted(it) } else missing.isEmpty()
        if (permissions.isEmpty() || already) {
            done(true)
            return
        }
        pendingPermission?.invoke(false)
        pendingPermission = { _ ->
            done(if (anyOk) permissions.any { granted(it) } else permissions.all { granted(it) })
        }
        requestPermissions(missing.toTypedArray(), REQ_PERM)
    }

    /** 用户亲手点的跨站链接：交给 Mafari 浏览器新开页面 */
    private fun openInBrowser(uri: Uri) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .setClass(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }

    private var externalPromptShowing = false
    private var lastExternalToast = 0L

    /** 唤起其他应用的统一入口（权限：例外网站优先，其次全局「打开其他应用」） */
    private fun requestExternalApp(uri: Uri) {
        val host = (webView.url?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: pageHost).orEmpty()
        val mode = GeckoSitePerms.override(host, GeckoSitePerms.EXTERNAL_APP) ?: settings.externalAppPermission
        val launch = {
            if (uri.scheme.equals("intent", ignoreCase = true)) {
                launchIntentUri(uri.toString())
            } else {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, uri).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            component = null
                            selector = null
                        },
                    )
                }
            }
        }
        val blocked = {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastExternalToast >= 2000) {
                lastExternalToast = now
                Toast.makeText(this, tr("已拦截网页打开其他应用"), Toast.LENGTH_SHORT).show()
            }
        }
        if (mode == 1) {
            launch()
            return
        }
        if (mode == 2) {
            blocked()
            return
        }
        if (externalPromptShowing || isFinishing || isDestroyed) return
        externalPromptShowing = true
        val remember = android.widget.CheckBox(this).apply {
            text = tr("记住此网站的选择")
            visibility = if (host.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(remember)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(host.ifEmpty { appTitle })
            .setMessage(tr("打开其他应用"))
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(tr("允许")) { _, _ ->
                externalPromptShowing = false
                if (remember.isChecked && host.isNotEmpty()) GeckoSitePerms.remember(host, GeckoSitePerms.EXTERNAL_APP, true)
                launch()
            }
            .setNegativeButton(tr("拒绝")) { _, _ ->
                externalPromptShowing = false
                if (remember.isChecked && host.isNotEmpty()) GeckoSitePerms.remember(host, GeckoSitePerms.EXTERNAL_APP, false)
                blocked()
            }
            .show()
    }

    private fun launchIntentUri(raw: String) {
        runCatching {
            val parsed = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
            startActivity(parsed)
        }.onFailure {
            val fallback = runCatching {
                Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            }.getOrNull()
            if (!fallback.isNullOrBlank()) {
                fpInjector.prepare(fallback)
                webView.loadUrl(fallback)
            } else {
                Toast.makeText(this, tr("未安装对应应用"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showErrorPage(view: WebView, failingUrl: String, spec: ErrorSpec) {
        view.loadDataWithBaseURL(failingUrl, ErrorPages.render(spec, failingUrl, dark), "text/html", "UTF-8", failingUrl)
    }

    /** 状态栏 / 手势条区域的底色跟随网页（theme-color 或 body 背景），图标深浅随之切换 */
    private fun syncThemeColor() {
        val js = """
            (function(){
              function ok(s){return s&&s!=='transparent'&&!/rgba\(.*,\s*0\)${'$'}/.test(s)}
              var m=document.querySelector('meta[name="theme-color"]'),c=m&&m.content;
              if(c){var d=document.createElement('div');d.style.color=c;document.documentElement.appendChild(d);
                var r=getComputedStyle(d).color;d.remove();if(ok(r))return r}
              var b=document.body&&getComputedStyle(document.body).backgroundColor;if(ok(b))return b;
              var h=getComputedStyle(document.documentElement).backgroundColor;return ok(h)?h:''
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { value ->
            val color = parseCssColor(value?.trim('"').orEmpty()) ?: defaultBg
            root.setBackgroundColor(color)
            webView.setBackgroundColor(color)
            applySystemBarIcons(color)
        }
    }

    private fun parseCssColor(css: String): Int? {
        val m = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(css) ?: return null
        val (r, g, b) = m.destructured
        return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }

    private fun applySystemBarIcons(background: Int) {
        val light = ColorUtils.calculateLuminance(background) > 0.5
        insetsController.isAppearanceLightStatusBars = light
        insetsController.isAppearanceLightNavigationBars = light
    }

    @Suppress("DEPRECATION")
    private fun updateTaskDescription(title: String) {
        val icon = ShortcutHelper.readSavedIcon(this, intent?.getStringExtra(ShortcutHelper.EXTRA_ICON))
        setTaskDescription(ActivityManager.TaskDescription(title, icon))
    }
}

/** 下拉刷新指示：圆形底 + 随下拉进度旋转的环形箭头（与浏览器的 RefreshSpinner 同款观感） */
internal class RefreshSpinnerView(context: Context) : View(context) {

    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        color = 0xFF3B7DFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF3B7DFF.toInt()
    }
    private val arrow = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 6.5f * d
        canvas.save()
        canvas.rotate(progress * 360f, cx, cy)
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 280f, false, stroke)
        // 箭头：弧线末端沿顺时针切线方向
        val theta = Math.toRadians(190.0)
        val px = cx + r * Math.cos(theta).toFloat()
        val py = cy + r * Math.sin(theta).toFloat()
        val tx = -Math.sin(theta).toFloat()
        val ty = Math.cos(theta).toFloat()
        val nx = Math.cos(theta).toFloat()
        val ny = Math.sin(theta).toFloat()
        arrow.reset()
        arrow.moveTo(px + tx * 3.6f * d, py + ty * 3.6f * d)
        arrow.lineTo(px + nx * 3.2f * d, py + ny * 3.2f * d)
        arrow.lineTo(px - nx * 3.2f * d, py - ny * 3.2f * d)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.restore()
    }
}
