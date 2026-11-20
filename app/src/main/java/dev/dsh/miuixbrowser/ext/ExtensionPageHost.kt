package dev.dsh.miuixbrowser.ext

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * 拓展自己的页面（后台页 / popup / options）的宿主：每个页面一个独立 WebView，用 WebViewAssetLoader 把拓展的虚拟 origin
 * 映射到解包目录，所以不同拓展同源策略天然隔离。页面里的 browser / chrome 由 [ExtensionInjection.buildPage] 注入。
 *
 * 信任模型与 [WebViewExtensionHost] 相同，区别在 Sender：这里是 EXTENSION_PAGE，且 origin 必须等于该拓展的虚拟 origin
 * （由 [PermissionChecker] 核对，取自 WebMessageListener 回调）。contextId 由本类分配，页面无法自称。
 *
 * 所有 WebView 操作都在主线程；调用方负责在主线程调用 [createPage] / [startBackground] 等。
 * 需要真机验证的点：WebViewAssetLoader 对自定义域名的接管；未挂到窗口的隐藏 WebView 里定时器是否被系统节流。
 */
class ExtensionPageHost(
    private val appContext: Context,
    private val repo: ExtensionRepository,
    private val runtime: ExtensionRuntime,
    private val broker: MessageBroker,
    private val shim: () -> String,
    private val log: (String) -> Unit = {},
) : EventSink {

    enum class PageKind { BACKGROUND, POPUP, OPTIONS }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loaders = HashMap<String, WebViewAssetLoader>()
    private val pages = HashMap<String, ExtensionPage>() // contextId → page（主线程访问）
    private val backgrounds = HashMap<String, ExtensionPage>() // extId → 后台页（主线程访问）
    private val pagesSnapshot = java.util.concurrent.CopyOnWriteArrayList<ExtensionPage>() // emit 用，任意线程读

    /** 后台页就绪 / 消失的通知（主线程回调）。就绪 = onPageFinished 且握手完成，缺一不可：只有握手后才有回复通道可投递 */
    @Volatile var backgroundListener: BackgroundListener? = null

    /**
     * 拓展页面里点了外链（http / https，且带用户手势）时回调 (extId, url)，由 UI 层用普通标签打开。
     * 页面自己永远不会被导航到别的站点；没设置回调时外链被直接丢弃。
     */
    @Volatile var externalLinkHandler: ((extId: String, url: String) -> Unit)? = null

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /** 一个拓展页面。同时是消息投递的目标：握手后有了回复通道才注册进 [MessageBroker] */
    inner class ExtensionPage internal constructor(
        override val contextId: String,
        override val extId: String,
        val pageKind: PageKind,
        val webView: WebView,
    ) : MessageEndpoint {
        override val kind: SenderKind = SenderKind.EXTENSION_PAGE
        override val portPriority: Int = when (pageKind) {
            PageKind.BACKGROUND -> 100
            PageKind.POPUP -> 50
            PageKind.OPTIONS -> 10
        }

        @Volatile internal var reply: JavaScriptReplyProxy? = null

        /** 页面调用 `window.close()`（popup 常用来关闭自己）时在主线程回调；后台页不设置，等于忽略 */
        @Volatile var onCloseRequested: (() -> Unit)? = null
        internal var registered = false
        internal var finished = false // 主框架 onPageFinished 过（主线程访问）
        internal var readyNotified = false // 已通知过 backgroundListener（主线程访问）

        override fun deliver(cid: String, message: Any?, sender: JSONObject): Boolean {
            val r = reply ?: return false
            val text = JSONObject()
                .put("type", "message")
                .put("cid", cid)
                .put("message", message ?: JSONObject.NULL)
                .put("sender", sender)
                .toString()
            post(r, text)
            return true
        }

        override fun deliverPort(type: String, portId: String, name: String, sender: JSONObject, message: Any?): Boolean {
            val r = reply ?: return false
            val payload = JSONObject().put("type", type).put("portId", portId).put("name", name).put("sender", sender)
            if (type == "portMessage") payload.put("message", message ?: JSONObject.NULL)
            post(r, payload.toString())
            return true
        }

        fun destroy() {
            main.post { destroyNow(this) }
        }
    }

    // ───────────────────────── 创建 / 销毁 ─────────────────────────

    /** 为拓展的某个页面建一个 WebView 并开始加载。[path] 相对拓展根。不支持或安装桥失败时返回 null */
    @SuppressLint("SetJavaScriptEnabled")
    fun createPage(ext: InstalledExtension, pageKind: PageKind, path: String): ExtensionPage? {
        if (!isSupported()) return null
        val id = ext.spec.id
        val plan = ExtensionInjection.buildPage(ext, shim(), background = pageKind == PageKind.BACKGROUND)
        plan.warnings.forEach { warning -> log("ext $id injection warning: $warning") }
        val webView = WebView(appContext)
        val page = ExtensionPage(UUID.randomUUID().toString(), id, pageKind, webView)
        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
            }
            val loader = loaderFor(ext)
            val origin = ExtensionOrigins.originFor(id)
            webView.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                // 拓展页面不允许自己导航到别的站点（外链交给 UI 层用标签打开）
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (target.startsWith("$origin/")) return false
                    val web = target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)
                    if (web && request.hasGesture()) externalLinkHandler?.invoke(id, target)
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    log("ext $id ${pageKind.name.lowercase()} load error ${error.errorCode} ${error.description}: ${request.url}")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    log("ext $id ${pageKind.name.lowercase()} http ${errorResponse.statusCode}: ${request.url}")
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    log("ext $id ${pageKind.name.lowercase()} render process gone (crashed=${detail.didCrash()})")
                    destroyNow(page)
                    return true
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    if (url != null && url.startsWith("$origin/")) {
                        log("ext $id ${pageKind.name.lowercase()} commit-visible url=$url viewport=${view.width}x${view.height}")
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != null && url.startsWith("$origin/")) {
                        page.finished = true
                        log("ext $id ${pageKind.name.lowercase()} page-finished url=$url viewport=${view.width}x${view.height}")
                        // 记录 DOM 与 Resource Timing 摘要，帮助区分资源加载失败和拓展自身初始化卡住。
                        // 不读取页面正文，不改变页面状态。
                        view.evaluateJavascript("(function(){try{var e=performance.getEntriesByType('resource')||[];var bad=e.filter(function(x){return x.responseStatus>=400});return JSON.stringify({state:document.readyState,body:!!document.body,bodyChildren:document.body?document.body.children.length:0,resources:e.length,failed:bad.slice(0,20).map(function(x){return {name:x.name,status:x.responseStatus||0}})});}catch(x){return JSON.stringify({probeError:String(x)})}})()") { result ->
                            log("ext $id ${pageKind.name.lowercase()} dom-probe=${result?.take(1800) ?: "null"}")
                        }
                        maybeNotifyReady(page)
                    }
                }
            })
            webView.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    page.onCloseRequested?.invoke()
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (newProgress == 100) {
                        log("ext $id ${page.pageKind.name.lowercase()} progress=100 url=${view.url}")
                    }
                }

                // 拓展页面的 console 输出对调试拓展至关重要；截断避免刷屏
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    log("ext $id ${page.pageKind.name.lowercase()} console.${m.messageLevel().name.lowercase()}: " +
                        "${m.message().take(500)} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                    return true
                }
            }
            WebViewCompat.addWebMessageListener(webView, plan.bridgeName, plan.originRules) { _, message, sourceOrigin, isMainFrame, reply ->
                onPageMessage(page, message, sourceOrigin.toString(), isMainFrame, reply)
            }
            WebViewCompat.addDocumentStartJavaScript(webView, plan.script, plan.originRules)
        } catch (e: Exception) {
            log("ext $id: page install failed ${e.message}")
            runCatching { webView.destroy() }
            return null
        }
        pages[page.contextId] = page
        pagesSnapshot.add(page)
        val url = ExtensionOrigins.urlFor(id, path)
        if (pageKind == PageKind.BACKGROUND) {
            webView.loadUrl(url)
        } else {
            // popup / options 是给人看的：必须等 WebView 挂上窗口再开始加载。
            // 还没挂窗口就加载，页面起步时视口是 0×0 且不可见，requestAnimationFrame 不会触发；
            // 而拓展（如 uBO）的 popup 要等若干帧之后才把 body 的 loading（opacity:0）去掉，结果就是一块空白。
            // 仅 attached 不够：Compose/BottomSheet 可能先 attach、后完成测量布局。
            // uBO 等 popup 会在若干 requestAnimationFrame 后解除 loading/opacity 隐藏；
            // 若首帧时 viewport 仍为 0×0，页面可能一直停留在白屏状态。
            var started = false
            var layoutListener: View.OnLayoutChangeListener? = null
            fun startWhenVisibleAndLaidOut() {
                if (started || pages[page.contextId] !== page || !webView.isAttachedToWindow) return
                if (webView.width <= 0 || webView.height <= 0) return
                started = true
                layoutListener?.let(webView::removeOnLayoutChangeListener)
                webView.onResume()
                webView.resumeTimers()
                log("ext $id ${pageKind.name.lowercase()} load $url viewport=${webView.width}x${webView.height}")
                webView.loadUrl(url)
            }
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                startWhenVisibleAndLaidOut()
            }
            layoutListener = listener
            webView.addOnLayoutChangeListener(listener)
            if (webView.isAttachedToWindow) {
                startWhenVisibleAndLaidOut()
            } else {
                webView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        startWhenVisibleAndLaidOut()
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
        }
        return page
    }

    /** 启动拓展的后台页（隐藏 WebView）。没有后台、已在运行或不支持时返回 null */
    fun startBackground(ext: InstalledExtension): ExtensionPage? {
        val id = ext.spec.id
        if (backgrounds.containsKey(id)) return null
        val entry = ExtensionPages.backgroundEntry(ext) ?: return null
        val page = createPage(ext, PageKind.BACKGROUND, entry.path) ?: return null
        backgrounds[id] = page
        broker.expectBackground(id)
        // 未挂窗口的 WebView 默认可能被暂停：显式恢复。是否仍被节流需要真机验证
        page.webView.onResume()
        page.webView.resumeTimers()
        return page
    }

    fun stopBackground(extId: String) {
        backgrounds.remove(extId)?.let { destroyNow(it) }
    }

    /** 启动所有已启用、引擎含 WEBVIEW、有后台的拓展 */
    fun startAllBackgrounds() {
        for (ext in repo.list()) {
            if (ext.enabled && EngineKind.WEBVIEW in ext.engines) startBackground(ext)
        }
    }

    /** 拓展停用 / 卸载 / 更新前：销毁它所有页面并清掉投递目标与连接 */
    fun shutdown(extId: String) {
        backgrounds.remove(extId)
        pages.values.filter { it.extId == extId }.forEach { destroyNow(it) }
        broker.unregisterExtension(extId)
        loaders.remove(ExtensionOrigins.hostFor(extId))
    }

    /** 后台页同时满足「加载完」和「已握手」才算就绪；通知只发一次（页面自己 reload 不重发） */
    private fun maybeNotifyReady(page: ExtensionPage) {
        if (page.pageKind != PageKind.BACKGROUND || page.readyNotified) return
        if (!page.finished || page.reply == null || pages[page.contextId] !== page) return
        page.readyNotified = true
        backgroundListener?.let { runCatching { it.onBackgroundReady(page.extId) } }
    }

    private fun destroyNow(page: ExtensionPage) {
        if (page.readyNotified) {
            page.readyNotified = false
            backgroundListener?.let { runCatching { it.onBackgroundGone(page.extId) } }
        }
        pages.remove(page.contextId)
        pagesSnapshot.remove(page)
        if (backgrounds[page.extId] === page) backgrounds.remove(page.extId)
        broker.unregister(page.contextId)
        page.reply = null
        page.onCloseRequested = null
        runCatching {
            page.webView.stopLoading()
            // popup 的 WebView 可能还挂在 Sheet 的视图树上：先摘下来再销毁，否则系统会报「destroy while still attached」
            (page.webView.parent as? ViewGroup)?.removeView(page.webView)
            page.webView.destroy()
        }
    }

    private fun loaderFor(ext: InstalledExtension): WebViewAssetLoader {
        val id = ext.spec.id
        return loaders.getOrPut(ExtensionOrigins.hostFor(id)) {
            WebViewAssetLoader.Builder()
                .setDomain(ExtensionOrigins.hostFor(id))
                .setHttpAllowed(false)
                .addPathHandler("/", PathHandler(repo, id))
                .build()
        }
    }

    private fun errorResponse(status: Int, reason: String, message: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", status, reason,
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)),
        )

    private inner class PathHandler(private val repo: ExtensionRepository, private val id: String) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            // 每次请求都重新取：拓展停用 / 更新后立刻生效。拓展 origin 下的未知资源必须明确 404，
            // 不能返回 null 让 WebView 再把虚拟域名当普通网络主机尝试请求。
            val ext = repo.get(id)?.takeIf { it.enabled }
            if (ext == null) {
                log("ext $id resource gone (disabled or uninstalled): /$path")
                return errorResponse(410, "Gone", "Extension is disabled or unavailable")
            }
            val res = ExtensionPages.open(ext, path)
            if (res == null) {
                log("ext $id resource MISS 404: /$path")
                return errorResponse(404, "Not Found", "Extension resource not found")
            }
            val stream = res.file?.inputStream() ?: ByteArrayInputStream(res.bytes ?: ByteArray(0))
            val textual = res.mime.startsWith("text/") || res.mime == "application/json" || res.mime == "application/xml" || res.mime.endsWith("+xml")
            val length = res.file?.length() ?: (res.bytes?.size?.toLong() ?: 0L)
            log("ext $id resource HIT 200: /$path mime=${res.mime} bytes=$length")
            return WebResourceResponse(
                res.mime,
                if (textual || res.mime == "application/javascript") "utf-8" else null,
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                stream,
            )
        }
    }

    // ───────────────────────── 消息 ─────────────────────────

    private fun onPageMessage(
        page: ExtensionPage,
        message: WebMessageCompat,
        origin: String,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val text = message.data ?: return

        // 回复通道只认主框架：投递目标是「页面」，不是页面里的某个 iframe
        if (isMainFrame) {
            page.reply = reply
            if (!page.registered) {
                log("ext ${page.extId} ${page.pageKind.name.lowercase()} bridge-ready origin=$origin")
                page.registered = true
                broker.register(page)
                if (page.pageKind == PageKind.BACKGROUND) broker.backgroundReady(page.extId)
            }
            maybeNotifyReady(page)
        }

        val req = try {
            ApiProtocol.parseRequest(text)
        } catch (e: ApiException) {
            post(reply, ApiProtocol.error(ApiProtocol.bestEffortId(text), e.code, e.message ?: ""))
            return
        }
        if (req.extId != page.extId) {
            post(reply, ApiProtocol.error(req.id, ErrorCodes.BAD_REQUEST, "extId 与连接不符"))
            return
        }
        // 回调在主线程：主框架直接取 WebView 当前网址（popup / options / 后台页都是 origin 下的真实路径）；子框架只能给 origin/
        val url = (if (isMainFrame) page.webView.url?.takeIf { it.startsWith("$origin/") } else null) ?: (origin.trimEnd('/') + "/")
        val sender = Sender(SenderKind.EXTENSION_PAGE, origin, isMainFrame, null, page.contextId, url)
        log("ext ${page.extId} ${page.pageKind.name.lowercase()} api-> ${req.api} id=${req.id}")
        scope.launch {
            val response = runtime.handle(text, sender)
            val result = runCatching { JSONObject(response) }.getOrNull()
            val error = result?.optJSONObject("error")
            if (error != null) {
                log("ext ${page.extId} ${page.pageKind.name.lowercase()} api<- ${req.api} error=${error.optString("code")}")
            } else {
                log("ext ${page.extId} ${page.pageKind.name.lowercase()} api<- ${req.api} ok=${result?.optBoolean("ok", false) == true}")
            }
            post(reply, response)
        }
    }

    private fun post(reply: JavaScriptReplyProxy, text: String) {
        main.post { runCatching { reply.postMessage(text) } }
    }

    /** 向某个拓展所有在线页面推送事件（storage.onChanged 等） */
    fun emitEvent(extId: String, event: String, payload: JSONObject) = emit(extId, event, payload)

    override fun emit(extId: String, event: String, payload: JSONObject) {
        val text = JSONObject().put("event", event).put("payload", payload).toString()
        for (p in pagesSnapshot) {
            if (p.extId == extId) p.reply?.let { post(it, text) }
        }
    }
}
