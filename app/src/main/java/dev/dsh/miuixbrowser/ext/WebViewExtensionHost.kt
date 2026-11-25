/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionException
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * WebView 适配器的传输层：把拓展的 content script 装到标签页 WebView 上，并把页面发来的请求交给 [ExtensionRuntime]。
 *
 * 两条注入路径（见 [install]）：
 * - **隔离世界**（`JS_INJECTION_IN_FRAME_AND_WORLD`，androidx.webkit ≥ 1.16）：每个拓展一个 `JavaScriptExecutionWorld`，
 *   桥（WebMessageListener）与 ISOLATED content script 都只存在于该世界，页面脚本既看不到桥也发不出消息；
 *   `world: "MAIN"` 的 content script 注入页面世界且没有桥。
 * - **旧路径**（系统 WebView 太旧时的降级）：桥与脚本都在页面世界，靠脚本捕获后删除全局来降低暴露面。
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
    private val broker: MessageBroker,
    private val dynamicContentScripts: (InstalledExtension) -> List<ContentScriptSpec> = { emptyList() },
    private val log: (String) -> Unit = {},
) : EventSink {

    private companion object {
        /** `WebViewCompat.addJavaScriptOnEvent` 的「文档刚创建、页面脚本之前」事件，与 addDocumentStartJavaScript 同时机 */
        const val EVENT_DOCUMENT_START = WebViewCompat.INJECTION_EVENT_DOCUMENT_START
        /** shim 向原生要内联模块的内部 api（不走 PermissionChecker，由 [handleModuleLoad] 自己校验）；符合 ApiProtocol 的 api 名格式。 */
        const val MODULE_LOAD_API = "mafari.module.load"
        /** shim 在隔离世界里代 content script 读拓展自己的 web_accessible 资源（页面 CSP 的 connect-src 会拦直接 fetch） */
        const val RESOURCE_READ_API = "mafari.resource.read"
        const val MAX_RESOURCE_BYTES = 4 * 1024 * 1024

        /** `addJavaScriptOnEvent` 的 document-end 事件（1.16 起；官方文档写明对应 DOMContentLoaded），用来驱动 run_at: document_end / document_idle */
        const val EVENT_DOCUMENT_END = WebViewCompat.INJECTION_EVENT_DOCUMENT_END
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 每个拓展当前持有的页面端连接（回复代理），用来向页面推送事件。弱引用：页面销毁后自然失效 */
    private val connections = HashMap<String, MutableList<WeakReference<JavaScriptReplyProxy>>>()
    private val contentEndpoints = HashMap<String, ContentEndpoint>()
    private data class Binding(
        val webView: WebView,
        val exts: List<InstalledExtension>,
        val shim: String,
        val tabId: Int,
        var installation: Installation,
    )
    private val bindings = HashMap<Int, Binding>()

    /**
     * 按需送进隔离世界的 content script 模块：extId → (路径 → 模块函数源码)，见 [ExtensionInjection.build] 的 lazyModules。
     * 只有该拓展自己世界里的桥请求得到（[onPageMessage] 里按 viaWorld 校验），路径必须在这张表里，所以页面拿不到别的文件。
     */
    private val lazyModules = HashMap<String, Map<String, String>>()

    private inner class ContentEndpoint(
        override val extId: String,
        override val tabId: Int,
        initialReply: JavaScriptReplyProxy,
    ) : MessageEndpoint {
        override val contextId: String = "content:$extId:$tabId"
        override val kind: SenderKind = SenderKind.CONTENT_SCRIPT
        override val frameId: Int = 0
        override val contextType: String = "TAB"
        @Volatile private var currentDocumentUrl: String? = null
        @Volatile private var currentDocumentId: String = java.util.UUID.randomUUID().toString()
        override var documentUrl: String?
            get() = currentDocumentUrl
            set(value) {
                if (currentDocumentUrl != value) {
                    currentDocumentUrl = value
                    currentDocumentId = java.util.UUID.randomUUID().toString()
                }
            }
        override val documentId: String get() = currentDocumentId
        @Volatile override var documentOrigin: String? = null
        @Volatile var reply: JavaScriptReplyProxy = initialReply
        /** [reply] 来自隔离世界里的桥（可用 [JavaScriptReplyProxy.executeJavaScript] 在该世界执行脚本） */
        @Volatile var isolated: Boolean = false

        override fun deliver(cid: String, message: Any?, sender: JSONObject): Boolean {
            val payload = JSONObject()
                .put("type", "message")
                .put("cid", cid)
                .put("message", message ?: JSONObject.NULL)
                .put("sender", sender)
            main.post { runCatching { reply.postMessage(payload.toString()) } }
            return true
        }

        override fun deliverPort(type: String, portId: String, name: String, sender: JSONObject, message: Any?): Boolean {
            val payload = JSONObject().put("type", type).put("portId", portId).put("name", name).put("sender", sender)
            if (type == "portMessage") payload.put("message", message ?: JSONObject.NULL)
            main.post { runCatching { reply.postMessage(payload.toString()) } }
            return true
        }
    }

    /** 一个 WebView 上的安装结果；拓展集合变化或标签销毁时 [close] */
    /** 注册在某个隔离世界里的桥；释放时要连世界一起指定才能摘掉 */
    internal class WorldListener(val name: String, val world: JavaScriptExecutionWorld)

    inner class Installation internal constructor(
        private val webView: WebView,
        bridgeNames: List<String>,
        handlers: List<ScriptHandler>,
        extIds: List<String>,
        private val tabId: Int?,
        worldListeners: List<WorldListener> = emptyList(),
    ) {
        private var worldListeners = worldListeners.toMutableList()
        private var bridgeNames = bridgeNames.toMutableList()
        private var handlers = handlers.toMutableList()
        private var extIds = extIds.toMutableList()
        private var closed = false

        fun close() {
            if (closed) return
            releaseResources()
            closed = true
            if (tabId != null) {
                extIds.forEach { forgetTab(it, tabId) }
                synchronized(bindings) {
                    if (bindings[tabId]?.installation === this) bindings.remove(tabId)
                }
            }
        }

        internal fun closeForRefresh() {
            if (closed) return
            releaseResources()
            closed = true
        }

        internal fun adopt(other: Installation) {
            bridgeNames = other.bridgeNames
            handlers = other.handlers
            extIds = other.extIds
            worldListeners = other.worldListeners
            other.bridgeNames = mutableListOf()
            other.handlers = mutableListOf()
            other.extIds = mutableListOf()
            other.worldListeners = mutableListOf()
            other.closed = true
            closed = false
        }

        private fun releaseResources() {
            handlers.forEach { runCatching { it.remove() } }
            bridgeNames.forEach { runCatching { WebViewCompat.removeWebMessageListener(webView, it) } }
            worldListeners.forEach { runCatching { WebViewCompat.removeWebMessageListener(webView, it.world, it.name) } }
            handlers.clear()
            bridgeNames.clear()
            worldListeners.clear()
        }
    }

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /**
     * 系统 WebView 是否支持隔离世界（addJavaScriptOnEvent / 带 world 的 addWebMessageListener / executeJavaScript，
     * 同一个特性位）。不支持时 [install] 自动走旧的页面世界路径。
     */
    fun isolatedWorldsSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
    }.getOrDefault(false)

    /**
     * 在 WebView 首次加载网页之前调用。[exts] 里只传要在这个标签上生效的（已启用、引擎含 WEBVIEW、无痕范围已过滤）。
     * [shim] 是 assets/mafari_ext_shim.js 全文。
     */
    /** [ExtensionInjection.prepare] 的缓存项：键是它依赖的全部输入，任何一个变了（授权、动态脚本、世界支持）就重算 */
    private class PreparedEntry(
        val ext: InstalledExtension,
        val dynamic: List<ContentScriptSpec>,
        val lazyModules: Boolean,
        val prepared: PreparedInjection?,
    )

    private val preparedLock = Any()
    private val preparedCache = HashMap<String, PreparedEntry>()

    /**
     * 取（必要时计算）拓展的注入预处理结果。整段在锁里：冷启动时后台线程正在算的话，主线程会等它算完而不是重复算一遍。
     * 命中条件是 [ext]、动态脚本、是否走隔离世界都和上次一致；拓展被装 / 启 / 停 / 卸（[forget]）时整项丢弃。
     */
    private fun preparedFor(ext: InstalledExtension, dynamic: List<ContentScriptSpec>, useWorlds: Boolean): PreparedInjection? =
        synchronized(preparedLock) {
            val hit = preparedCache[ext.spec.id]
            if (hit != null && hit.lazyModules == useWorlds && hit.ext == ext && hit.dynamic == dynamic) {
                return@synchronized hit.prepared
            }
            val fresh = ExtensionInjection.prepare(ext, ExtensionInjection.fileReader(ext.rootDir), dynamic, useWorlds)
            preparedCache[ext.spec.id] = PreparedEntry(ext, dynamic, useWorlds, fresh)
            fresh
        }

    /**
     * 冷启动预热：在**后台线程**调用，把每个拓展的 content script 先读好、打包好，
     * 这样第一个标签在主线程装配时（[install]）只剩字符串拼接，不再同步读盘。不支持时什么都不做。
     */
    fun prewarm(exts: List<InstalledExtension>) {
        if (!isSupported()) return
        val useWorlds = isolatedWorldsSupported()
        for (ext in exts) {
            runCatching { preparedFor(ext, dynamicContentScripts(ext), useWorlds) }
                .onFailure { log("ext ${ext.spec.id}: prewarm failed ${it.message}") }
        }
    }

    fun install(webView: WebView, exts: List<InstalledExtension>, shim: String, tabId: Int?): Installation? {
        if (!isSupported()) return null
        val useWorlds = isolatedWorldsSupported()
        val names = mutableListOf<String>()
        val handlers = mutableListOf<ScriptHandler>()
        val worldListeners = mutableListOf<WorldListener>()
        val installedExtIds = mutableListOf<String>()
        for (ext in exts) {
            installedExtIds.add(ext.spec.id)
            // 读文件 / 打包 / 本地化这部分与标签无关，走缓存（冷启动时已在后台线程算好，见 [prewarm]）；每个标签只做拼接
            val plan = preparedFor(ext, dynamicContentScripts(ext), useWorlds)
                ?.let { ExtensionInjection.finish(it, shim, tabId) }
            if (plan == null) {
                log("ext ${ext.spec.id}: tab $tabId 没有可注入的 content script（manifest 无 content_scripts，或脚本文件读不到）")
                continue
            }
            plan.warnings.forEach { log("ext ${ext.spec.id}: $it") }
            synchronized(lazyModules) {
                if (plan.lazyModuleSources.isEmpty()) lazyModules.remove(ext.spec.id)
                else lazyModules[ext.spec.id] = plan.lazyModuleSources
            }
            try {
                if (useWorlds) {
                    installInWorlds(webView, plan, tabId, handlers, worldListeners)
                } else {
                    WebViewCompat.addWebMessageListener(webView, plan.bridgeName, plan.originRules) { _, message, sourceOrigin, isMainFrame, reply ->
                        onPageMessage(plan.extId, message, sourceOrigin.toString(), isMainFrame, tabId, reply, viaWorld = false)
                    }
                    names.add(plan.bridgeName)
                    handlers.add(WebViewCompat.addDocumentStartJavaScript(webView, plan.script, plan.originRules))
                    log("ext ${ext.spec.id}: tab $tabId content scripts installed (page world) rules=${plan.originRules.take(4)} script=${plan.script.length}B")
                }
            } catch (e: Exception) {
                log("ext ${ext.spec.id}: install failed ${e.message}")
            }
        }
        val installation = Installation(webView, names, handlers, installedExtIds, tabId, worldListeners)
        if (tabId != null) synchronized(bindings) {
            bindings[tabId] = Binding(webView, exts, shim, tabId, installation)
        }
        return installation
    }

    /**
     * 隔离世界路径：ISOLATED 条目 + 桥装进该拓展自己的世界，MAIN 条目装进页面世界（不带桥）。
     * 已经装进去的部分在后面抛异常时由 [Installation.close] 统一摘除（handlers / worldListeners 是调用方持有的列表）。
     */
    private fun installInWorlds(
        webView: WebView,
        plan: InjectionPlan,
        tabId: Int?,
        handlers: MutableList<ScriptHandler>,
        worldListeners: MutableList<WorldListener>,
    ) {
        plan.isolatedScript?.let { script ->
            val world = WebViewCompat.getExecutionWorld(webView, plan.worldName)
            WebViewCompat.addWebMessageListener(webView, plan.bridgeName, plan.isolatedOriginRules, world) { _, message, sourceOrigin, isMainFrame, reply ->
                onPageMessage(plan.extId, message, sourceOrigin.toString(), isMainFrame, tabId, reply, viaWorld = true)
            }
            worldListeners.add(WorldListener(plan.bridgeName, world))
            handlers.add(WebViewCompat.addJavaScriptOnEvent(webView, script, EVENT_DOCUMENT_START, plan.isolatedOriginRules, world))
            // document_end / document_idle 条目：shim 在 document-start 时只登记，等这个 document-end 事件来触发（window.load 兜底）
            plan.isolatedEndScript?.let { end ->
                handlers.add(WebViewCompat.addJavaScriptOnEvent(webView, end, EVENT_DOCUMENT_END, plan.isolatedOriginRules, world))
            }
            log("ext ${plan.extId}: tab $tabId isolated world '${plan.worldName}' rules=${plan.isolatedOriginRules.take(4)} script=${script.length}B")
        }
        plan.mainScript?.let { script ->
            val pageWorld = WebViewCompat.getExecutionWorld(webView, JavaScriptExecutionWorld.PAGE_WORLD_NAME)
            handlers.add(WebViewCompat.addJavaScriptOnEvent(webView, script, EVENT_DOCUMENT_START, plan.mainOriginRules, pageWorld))
            log("ext ${plan.extId}: tab $tabId MAIN-world content scripts rules=${plan.mainOriginRules.take(4)} script=${script.length}B")
        }
    }

    /**
     * 在拓展自己的隔离世界里执行脚本（`scripting.executeScript` 的 ISOLATED 世界），借用该世界里桥的回复代理，
     * 所以只有「该标签主框架里已有这个拓展的 ISOLATED content script 连上来、且标签没有跳去别的 origin」时才可能。
     *
     * @return false = 此刻没有可用的隔离世界入口，**什么也没执行**，调用方自己决定是否降级；
     *   true = 已发出，[done] 稍后恰好回调一次：结果文本（与 `evaluateJavascript` 同样的 JSON 文本），出错 / 帧已销毁时为 null。
     * 必须在主线程调用。
     */
    fun executeInIsolatedWorld(extId: String, tabId: Int, js: String, done: (String?) -> Unit): Boolean {
        if (!isolatedWorldsSupported()) return false
        val endpoint = synchronized(contentEndpoints) { contentEndpoints["$extId:$tabId"] } ?: return false
        if (!endpoint.isolated) return false
        val origin = endpoint.documentOrigin ?: return false
        val current = synchronized(bindings) { bindings[tabId]?.webView?.url } ?: return false
        if (!current.startsWith(origin.trimEnd('/') + "/")) return false
        val receiver = object : WebViewOutcomeReceiver<String, JavaScriptExecutionException> {
            override fun onResult(result: String) = done(result)
            override fun onError(error: JavaScriptExecutionException) = done(null)
        }
        return try {
            endpoint.reply.executeJavaScript(js, receiver)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 动态脚本注册发生变化时，重装受影响标签的 document-start handler；现有页面不回溯执行。 */
    fun refreshDynamicScripts(extId: String, updatedExtension: InstalledExtension? = null) {
        main.post {
            val affected = synchronized(bindings) { bindings.values.filter { b -> b.exts.any { it.spec.id == extId } } }
            for (binding in affected) {
                // Permission changes update the repository object; never rebuild with the stale snapshot
                // captured when this tab was first installed.
                val refreshedExts = if (updatedExtension == null) binding.exts else binding.exts.map { current ->
                    if (current.spec.id == extId) updatedExtension else current
                }
                val old = binding.installation
                old.closeForRefresh()
                val fresh = install(binding.webView, refreshedExts, binding.shim, binding.tabId)
                if (fresh == null) {
                    synchronized(bindings) { bindings.remove(binding.tabId) }
                } else {
                    old.adopt(fresh)
                    synchronized(bindings) {
                        bindings[binding.tabId] = binding.copy(exts = refreshedExts, installation = old)
                    }
                }
            }
        }
    }

    private fun onPageMessage(
        boundExtId: String,
        message: WebMessageCompat,
        origin: String,
        isMainFrame: Boolean,
        tabId: Int?,
        reply: JavaScriptReplyProxy,
        viaWorld: Boolean,
    ) {
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val text = message.data ?: return
        remember(boundExtId, reply)
        // tabs.sendMessage 默认目标是主框架；子框架目前没有独立 frameId 路由，不能覆盖主框架 endpoint。
        if (tabId != null && isMainFrame) rememberContentEndpoint(boundExtId, tabId, reply, viaWorld)

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

        if (req.api == MODULE_LOAD_API) {
            handleModuleLoad(req, reply, viaWorld)
            return
        }

        // 回调在主线程：主框架网址取自这个标签的 WebView（浏览器自己记录的，不是页面自报的）；子框架只有 origin/
        val frameUrl = (if (isMainFrame && tabId != null) synchronized(bindings) { bindings[tabId]?.webView?.url } else null)
            ?.takeIf { it.startsWith(origin.trimEnd('/') + "/") }
            ?: (origin.trimEnd('/') + "/")
        if (isMainFrame && tabId != null) {
            synchronized(contentEndpoints) {
                contentEndpoints["$boundExtId:$tabId"]?.let { endpoint ->
                    endpoint.documentUrl = frameUrl
                    endpoint.documentOrigin = origin
                }
            }
        }
        if (req.api == RESOURCE_READ_API) {
            handleResourceRead(req, reply, viaWorld, tabId, frameUrl)
            return
        }
        val sender = Sender(
            SenderKind.CONTENT_SCRIPT, origin, isMainFrame, tabId,
            if (isMainFrame && tabId != null) "content:$boundExtId:$tabId" else null,
            frameUrl,
        )
        scope.launch {
            val response = runtime.handle(text, sender)
            send(reply, response)
        }
    }

    /**
     * shim 的 `__mafariImport` 首次用到某个拓展内模块时请求它：用隔离世界的 executeJavaScript 调
     * `__mafariModDefine(path, fn)` 把模块函数送进去（页面 CSP 管不到 executeJavaScript），执行完才回复 ok，shim 随后即可取用。
     * 必须在主线程（[onPageMessage] 的回调线程）。
     */
    private fun handleModuleLoad(req: ApiRequest, reply: JavaScriptReplyProxy, viaWorld: Boolean) {
        val path = req.args.optString(0)
        val src = synchronized(lazyModules) { lazyModules[req.extId]?.get(path) }
        if (!viaWorld || path.isEmpty() || src == null) {
            send(reply, ApiProtocol.error(req.id, ErrorCodes.BAD_REQUEST, "没有这个模块：$path"))
            return
        }
        val js = "globalThis.__mafariModDefine(" + JSONObject.quote(path) + "," + src + ")"
        val receiver = object : WebViewOutcomeReceiver<String, JavaScriptExecutionException> {
            override fun onResult(result: String) = send(reply, ApiProtocol.ok(req.id, true))
            override fun onError(error: JavaScriptExecutionException) =
                send(reply, ApiProtocol.error(req.id, ErrorCodes.SCRIPT_ERROR, "模块送入隔离世界失败：$path"))
        }
        try {
            reply.executeJavaScript(js, receiver)
        } catch (e: Exception) {
            send(reply, ApiProtocol.error(req.id, ErrorCodes.SCRIPT_ERROR, "模块送入隔离世界失败：${e.message}"))
        }
    }

    /**
     * 读拓展包里的资源给 content script（`fetch(runtime.getURL(...))`）。规则与网页直接请求拓展地址一致：
     * 只放行 web_accessible_resources，页面网址取自浏览器记录的标签网址（[frameUrl]），不信任页面自报。
     * 回复 `{status, mime, b64}`；不存在 / 不可访问回 404，让页面侧拿到与网络请求一样的 404 响应。
     */
    private fun handleResourceRead(req: ApiRequest, reply: JavaScriptReplyProxy, viaWorld: Boolean, tabId: Int?, frameUrl: String) {
        val path = req.args.optString(0)
        // 旧的页面世界路径同样允许：连接已绑定到这个拓展，且只放行 web_accessible_resources，与网页直接请求拓展地址的规则一致
        val ext = if (tabId != null) {
            synchronized(bindings) { bindings[tabId]?.exts?.firstOrNull { it.spec.id == req.extId } }
        } else {
            null
        }
        if (ext == null || path.isEmpty()) {
            send(reply, ApiProtocol.error(req.id, ErrorCodes.BAD_REQUEST, "不能读取资源：$path"))
            return
        }
        scope.launch {
            val out = try {
                val res = ExtensionPages.openWebAccessible(ext, "/" + path.removePrefix("/"), frameUrl)
                val bytes = res?.let { it.file?.readBytes() ?: it.bytes }
                when {
                    res == null || bytes == null -> JSONObject().put("status", 404)
                    bytes.size > MAX_RESOURCE_BYTES -> null
                    else -> JSONObject().put("status", 200).put("mime", res.mime)
                        .put("b64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                }
            } catch (_: Exception) {
                null
            }
            send(
                reply,
                if (out != null) ApiProtocol.ok(req.id, out)
                else ApiProtocol.error(req.id, ErrorCodes.BAD_REQUEST, "资源读取失败或超过 ${MAX_RESOURCE_BYTES / 1024 / 1024}MB：$path"),
            )
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


    private fun rememberContentEndpoint(extId: String, tabId: Int, reply: JavaScriptReplyProxy, viaWorld: Boolean) {
        val key = "$extId:$tabId"
        synchronized(contentEndpoints) {
            val endpoint = contentEndpoints[key]
            if (endpoint != null) {
                endpoint.reply = reply
                endpoint.isolated = viaWorld
            } else {
                ContentEndpoint(extId, tabId, reply).also {
                    it.isolated = viaWorld
                    contentEndpoints[key] = it
                    broker.register(it)
                    log("ext $extId: content script connected tab=$tabId")
                }
            }
        }
    }

    private fun forgetTab(extId: String, tabId: Int) {
        val key = "$extId:$tabId"
        synchronized(contentEndpoints) {
            contentEndpoints.remove(key)?.let { broker.unregister(it.contextId) }
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
        synchronized(preparedLock) { preparedCache.remove(extId) } // 包可能换了（更新 / 重装）：预处理结果作废
        synchronized(connections) { connections.remove(extId) }
        synchronized(lazyModules) { lazyModules.remove(extId) }
        synchronized(contentEndpoints) {
            contentEndpoints.values.filter { it.extId == extId }.forEach { broker.unregister(it.contextId) }
            contentEndpoints.entries.removeAll { it.value.extId == extId }
        }
    }
}
