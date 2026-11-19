package dev.dsh.miuixbrowser.ext

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * WebView 适配器的传输层：把拓展的 content script 装到标签页 WebView 上，并把页面发来的请求交给 [ExtensionRuntime]。
 *
 * 信任模型：
 * - 来源（origin / isMainFrame）只取 WebMessageListener 回调给的值，绝不读消息体里的；
 * - 每个拓展一个 listener，消息里的 extId 必须等于该 listener 绑定的拓展，防止一个拓展冒充另一个；
 * - 标签页 WebView 上来的一律按 CONTENT_SCRIPT 处理；拓展自己的页面（popup / options）将来在独立 WebView 上用
 *   EXTENSION_PAGE，由原生决定，页面无法自称。
 *
 * 所有 WebView / 回复代理的操作都在主线程。注意：以下 androidx.webkit 调用依赖系统 WebView 版本，
 * 用 [isSupported] 检测并在不支持时降级（拓展不生效）。
 */
class WebViewExtensionHost(
    private val runtime: ExtensionRuntime,
    private val log: (String) -> Unit = {},
) : EventSink {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 每个拓展当前持有的页面端连接（回复代理），用来向页面推送事件。弱引用：页面销毁后自然失效 */
    private val connections = HashMap<String, MutableList<WeakReference<JavaScriptReplyProxy>>>()

    /** 一个 WebView 上的安装结果；拓展集合变化或标签销毁时 [close] */
    inner class Installation internal constructor(
        private val webView: WebView,
        private val bridgeNames: List<String>,
        private val handlers: List<ScriptHandler>,
    ) {
        fun close() {
            handlers.forEach { runCatching { it.remove() } }
            bridgeNames.forEach { runCatching { WebViewCompat.removeWebMessageListener(webView, it) } }
        }
    }

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /**
     * 在 WebView 首次加载网页之前调用。[exts] 里只传要在这个标签上生效的（已启用、引擎含 WEBVIEW、无痕范围已过滤）。
     * [shim] 是 assets/mafari_ext_shim.js 全文。
     */
    fun install(webView: WebView, exts: List<InstalledExtension>, shim: String, tabId: Int?): Installation? {
        if (!isSupported()) return null
        val names = mutableListOf<String>()
        val handlers = mutableListOf<ScriptHandler>()
        for (ext in exts) {
            val plan = ExtensionInjection.build(ext, shim, ExtensionInjection.fileReader(ext.rootDir)) ?: continue
            plan.warnings.forEach { log("ext ${ext.spec.id}: $it") }
            try {
                WebViewCompat.addWebMessageListener(webView, plan.bridgeName, plan.originRules) { _, message, sourceOrigin, isMainFrame, reply ->
                    onPageMessage(plan.extId, message, sourceOrigin.toString(), isMainFrame, tabId, reply)
                }
                names.add(plan.bridgeName)
                handlers.add(WebViewCompat.addDocumentStartJavaScript(webView, plan.script, plan.originRules))
            } catch (e: Exception) {
                log("ext ${ext.spec.id}: install failed ${e.message}")
            }
        }
        return Installation(webView, names, handlers)
    }

    private fun onPageMessage(
        boundExtId: String,
        message: WebMessageCompat,
        origin: String,
        isMainFrame: Boolean,
        tabId: Int?,
        reply: JavaScriptReplyProxy,
    ) {
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val text = message.data ?: return
        remember(boundExtId, reply)

        // 消息体里的 extId 必须与 listener 绑定的拓展一致
        val req = try {
            ApiProtocol.parseRequest(text)
        } catch (e: ApiException) {
            send(reply, ApiProtocol.error(ApiProtocol.bestEffortId(text), e.code, e.message ?: ""))
            return
        }
        if (req.extId != boundExtId) {
            send(reply, ApiProtocol.error(req.id, ErrorCodes.BAD_REQUEST, "extId 与连接不符"))
            return
        }

        val sender = Sender(SenderKind.CONTENT_SCRIPT, origin, isMainFrame, tabId)
        scope.launch {
            val response = runtime.handle(text, sender)
            send(reply, response)
        }
    }

    private fun send(reply: JavaScriptReplyProxy, text: String) {
        main.post {
            // 页面已经导航走或销毁时 postMessage 可能抛异常，忽略
            runCatching { reply.postMessage(text) }
        }
    }

    private fun remember(extId: String, reply: JavaScriptReplyProxy) {
        synchronized(connections) {
            val list = connections.getOrPut(extId) { mutableListOf() }
            list.removeAll { it.get() == null }
            if (list.none { it.get() === reply }) list.add(WeakReference(reply))
        }
    }

    /** 向某个拓展所有在线页面推送事件（storage.onChanged 等），格式 `{"event":…,"payload":…}` */
    override fun emit(extId: String, event: String, payload: JSONObject) {
        val text = JSONObject().put("event", event).put("payload", payload).toString()
        val targets = synchronized(connections) {
            connections[extId]?.mapNotNull { it.get() }.orEmpty()
        }
        for (t in targets) send(t, text)
    }

    /** 拓展被卸载 / 停用时丢掉它的连接记录 */
    fun forget(extId: String) {
        synchronized(connections) { connections.remove(extId) }
    }
}
