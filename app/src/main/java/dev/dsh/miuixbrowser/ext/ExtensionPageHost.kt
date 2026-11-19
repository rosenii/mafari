package dev.dsh.miuixbrowser.ext

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
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

        @Volatile internal var reply: JavaScriptReplyProxy? = null
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
        val plan = ExtensionInjection.buildPage(ext, shim())
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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !request.url.toString().startsWith("$origin/")

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != null && url.startsWith("$origin/")) {
                        page.finished = true
                        maybeNotifyReady(page)
                    }
                }
            })
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
        webView.loadUrl(ExtensionOrigins.urlFor(id, path))
        return page
    }

    /** 启动拓展的后台页（隐藏 WebView）。没有后台、已在运行或不支持时返回 null */
    fun startBackground(ext: InstalledExtension): ExtensionPage? {
        val id = ext.spec.id
        if (backgrounds.containsKey(id)) return null
        val entry = ExtensionPages.backgroundEntry(ext) ?: return null
        val page = createPage(ext, PageKind.BACKGROUND, entry.path) ?: return null
        backgrounds[id] = page
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
        runCatching {
            page.webView.stopLoading()
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

    private class PathHandler(private val repo: ExtensionRepository, private val id: String) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            // 每次请求都重新取：拓展停用 / 更新后立刻生效
            val ext = repo.get(id)?.takeIf { it.enabled } ?: return null
            val res = ExtensionPages.open(ext, path) ?: return null
            val stream = res.file?.inputStream() ?: ByteArrayInputStream(res.bytes ?: return null)
            val textual = res.mime.startsWith("text/") || res.mime == "application/json" || res.mime == "application/xml"
            return WebResourceResponse(res.mime, if (textual) "utf-8" else null, stream)
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
                page.registered = true
                broker.register(page)
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
        val sender = Sender(SenderKind.EXTENSION_PAGE, origin, isMainFrame, null, page.contextId)
        scope.launch { post(reply, runtime.handle(text, sender)) }
    }

    private fun post(reply: JavaScriptReplyProxy, text: String) {
        main.post { runCatching { reply.postMessage(text) } }
    }

    /** 向某个拓展所有在线页面推送事件（storage.onChanged 等） */
    override fun emit(extId: String, event: String, payload: JSONObject) {
        val text = JSONObject().put("event", event).put("payload", payload).toString()
        for (p in pagesSnapshot) {
            if (p.extId == extId) p.reply?.let { post(it, text) }
        }
    }
}
