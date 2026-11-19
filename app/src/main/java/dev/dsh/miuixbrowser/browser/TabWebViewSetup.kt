package dev.dsh.miuixbrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.AdBlockBridge
import dev.dsh.miuixbrowser.adblock.RequestLog
import dev.dsh.miuixbrowser.crypto.TrustedChannel
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.DESKTOP_UA
import dev.dsh.miuixbrowser.data.PrivacyStats
import dev.dsh.miuixbrowser.data.TabDb
import dev.dsh.miuixbrowser.data.TabThumbs
import dev.dsh.miuixbrowser.download.Downloader
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
import dev.dsh.miuixbrowser.engine.webview.WebViewDebug
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.net.WebViewProxy
import dev.dsh.miuixbrowser.script.GmBridge
import dev.dsh.miuixbrowser.script.ScriptResources
import dev.dsh.miuixbrowser.script.UserScript
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.ErrorPages
import dev.dsh.miuixbrowser.util.ErrorSpec
import dev.dsh.miuixbrowser.util.isSameSite
import dev.dsh.miuixbrowser.util.isUserScriptUrl
import dev.dsh.miuixbrowser.util.toUrl

/**
 * WebView 内核标签的初始化（从 BrowserTab 拆出）：JS 桥、WebSettings、WebViewClient / WebChromeClient、
 * 下载 / 下拉刷新 / 长按监听。写成 [BrowserTab] 的扩展函数，所以函数体里直接访问标签的成员；
 * 它用到的状态（页面主机名、SSL 例外、权限模式等）仍存放在 [BrowserTab] 里，已放宽为 internal。
 * 由 BrowserTab 的 init 在所有属性初始化完毕后调用（仅 WebView 内核）。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun BrowserTab.setupWebView() {
    // 显式开启 WebView 的硬件层：WebView 内容进 GPU 离屏层后，被 layerBackdrop 录制并送入 RuntimeShader
    // 时不再整页闪烁 / 崩溃，底栏、弹窗才能在网页态下采样 WebView 使用高级材质（miuix-glass / 液态玻璃）。
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    // 原生滚动回调（API 23+）：底栏收起 / 展开由滚动事件唤醒，不再靠定时轮询
    webView.setOnScrollChangeListener { _, _, _, _, _ -> scrollSignal.trySend(Unit) }
    runCatching { webView.addJavascriptInterface(AdBlockBridge { !incognito || adblockInIncognito }, "MafariAdblock") }
    runCatching { webView.addJavascriptInterface(blob.bridge, "MafariBlob") }
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
            override val appContext: Context get() = this@setupWebView.appContext
            override val incognito: Boolean get() = this@setupWebView.incognito
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
    TabBlob.cleanStaleFiles(appContext)
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
            reader.reset()
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

            scripts.injectMissing(view)
            if (!adblockDocStart) view.evaluateJavascript(AdBlock.COSMETIC_JS, null)
            // 重定向 / 跳转后以最终地址为准更新页面主机名，去广告的第三方判定、白名单、$domain 都依赖它
            updatePageHost(pageUrl)
            inspector.install(view)
            // 阅读模式：页面加载完后检测能否进入
            if (!parked) reader.onWebPageSettled(pageUrl ?: url, 400L)
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
            if (!parked && !isReload && !loading) reader.onWebPageSettled(pageUrl, 800L)
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

    webView.addJavascriptInterface(inspector.bridge, "MafariElement")

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
            this@setupWebView.onShowFileChooser.invoke(fileChooserParams, filePathCallback)
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
            this@setupWebView.onShowCustomView?.invoke(view, callback)
        }

        override fun onHideCustomView() {
            val cb = customViewCallback
            customView = null
            customViewCallback = null
            restoreOrientation()
            this@setupWebView.onHideCustomView?.invoke()
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
                inspector.lastTouchX = event.x
                inspector.lastTouchY = event.y
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
