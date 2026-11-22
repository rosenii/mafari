package dev.dsh.miuixbrowser.ext

import android.content.Context
import android.webkit.WebResourceResponse
import dev.dsh.miuixbrowser.data.HistoryStore
import dev.dsh.miuixbrowser.script.ExtensionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * 拓展系统的组装入口（WebView 内核）：一个应用一个实例，建议放进 AppContainer。
 * 可选接入 Mafari 原生 UserscriptStore，为声明 mafariScripts 的可信拓展开放管理 API。
 *
 * 目录布局（[baseDir] 下）：packages/ state/ data/ 由 [FileExtensionRepository] 管；work/ 是安装暂存。
 *
 * 接入标签页（首次加载网页之前，主线程）：
 * ```
 * val exts = system.repo.list().filter { it.enabled && EngineKind.WEBVIEW in it.engines }  // 无痕范围自行过滤
 * installation = system.tabHost.install(webView, exts, system.shim(), tabId)
 * // 标签销毁或拓展集合变化时 installation?.close()
 * ```
 * 应用启动后（主线程）：`system.startAll()`（拉起后台页；每个后台页就绪后依次收到 runtime.onStartup、补发的 alarms.onAlarm）。
 * 安装 / 启用 / 停用 / 卸载：见 [onInstalled] / [onEnabled] / [onDisabled] / [onUninstalled]，它们负责重启后台页并清理状态。
 */
class ExtensionSystem(
    private val context: Context,
    baseDir: File,
    private val nativeScripts: () -> ExtensionStore? = { null },
    private val log: (String) -> Unit = {},
) {
    val repo = FileExtensionRepository(baseDir) { id, e -> log("ext $id: 读取失败 ${e.message}") }
    val installer = PackageInstaller(repo, File(baseDir, "work"))

    private val router = ApiRouter()

    /** 原生已注册的 API 全名（如 tabs.query）：拓展管理页的说明文案据此生成，不手写维护 */
    fun registeredApis(): Set<String> = router.registered()
    private val historyStore by lazy { HistoryStore(context.applicationContext) }
    val broker = MessageBroker().also { it.log = log }
    private val dynamicScripts = DynamicContentScriptStore()
    private val sinks = CompositeEventSink()
    /** 用户点击拓展后临时授予的「当前标签」访问（activeTab），只存内存 */
    val activeTab = ActiveTabGrants()
    private var attachedBridge: TabBridge? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runtime = ExtensionRuntime(repo, router)

    val tabHost = WebViewExtensionHost(runtime, broker, { ext -> dynamicScripts.all(ext).map { it.spec } }, log)
    val pages = ExtensionPageHost(context.applicationContext, repo, runtime, broker, { shim() }, log)

    // session 区的变化事件只发给拓展自己的页面（pages），不能经 sinks 推进标签页里的 content script
    private val storage = StorageApi({ repo.dataDir(it) }, sinks, pages)

    /**
     * tabs.*：事件只发往拓展自己的页面（[pages]），不走 [sinks]——后者包含标签页里的 content script，
     * 标签的网址不能推到网页里去。浏览器界面建好后调用 [attachTabs] 接上真实的标签桥接（BrowserScreen 里接），之前调用回 UNSUPPORTED。
     */
    private val tabs = TabsApi(pages, { webViewExtensions() }, broker, activeTab, ::openOwnPageInSheet)

    /**
     * 拓展用 tabs.create / tabs.update 打开自己的页面（如 popup 里的「设置」）：普通标签里没有拓展消息桥，
     * 且只放行 web_accessible_resources（其余 404），所以改在拓展页面 Sheet 里打开。返回 false = 不是自己的页面或没有 UI。
     */
    private fun openOwnPageInSheet(ext: InstalledExtension, url: String): Boolean {
        val prefix = ExtensionOrigins.originFor(ext.spec.id) + "/"
        if (!url.startsWith(prefix, ignoreCase = true)) return false
        val path = url.substring(prefix.length)
        val handler = pages.openOptionsHandler
        log("ext ${ext.spec.id}: 打开自己的页面 path=$path -> " + if (handler != null) "拓展页面 Sheet" else "无 UI 处理器，退回标签")
        if (handler == null) return false
        android.os.Handler(android.os.Looper.getMainLooper()).post { handler(ext.spec.id, path) }
        return true
    }
    private val webNavigation = WebNavigationApi(pages, { webViewExtensions() }) { tabs.bridgeOrNull() }
    val contextMenus = ContextMenusApi(pages)
    val actionApi = ActionApi()
    private val webRequest = WebRequestApi(pages, { webViewExtensions() })
    private val declarativeNetRequest = DeclarativeNetRequestApi({ id -> repo.dataDir(id) }) { webViewExtensions() }

    /** scripting.*：与 tabs 共用同一个标签桥接（[TabsApi.bridgeOrNull]），attach 之前同样回 UNSUPPORTED */
    private val scripting = ScriptingApi(
        { tabs.bridgeOrNull() }, dynamicScripts, { id -> tabHost.refreshDynamicScripts(id) }, activeTab = activeTab,
    )
    @Volatile var permissionPrompt: ExtensionPermissionPrompt? = null
    private val permissions = PermissionsApi(
        repo = repo,
        events = sinks,
        prompt = { ext, api, origins -> permissionPrompt?.confirm(ext, api, origins) ?: false },
        onPermissionsChanged = { id -> tabHost.refreshDynamicScripts(id, repo.get(id)) },
    )

    /**
     * 后台页启动流程与闹钟共用一个单线程 Executor：就绪 / 消失 / 唤醒按顺序处理，文件读写与事件投递都不占主线程。
     * alarms / runtime 启动事件只投给拓展自己的页面（[pages]），content script 里没有这些 API。
     */
    private val bgExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ext-background").apply { isDaemon = true } }
    private val alarms = AlarmService({ repo.dataDir(it) }, pages, HandlerWakeTimer(bgExecutor), log = log)
    private val lifecycle = ExtensionLifecycle(pages, alarms) { bgExecutor.execute(it) }

    @Volatile private var shimText: String? = null

    init {
        pages.externalLinkHandler = { extId, url -> openExternalLink(extId, url) }
        pages.onPageDestroyed = { _, contextId -> webRequest.forgetContext(contextId) }
        RuntimeApi(
            broker,
            { tabs.bridgeOrNull() },
            openOptions = { extId ->
                val handler = pages.openOptionsHandler
                log("ext $extId: runtime.openOptionsPage -> " + if (handler != null) "拓展页面 Sheet" else "无 UI 处理器，退回标签")
                if (handler == null) false else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { handler(extId, null) }
                    true
                }
            },
        ).register(router)
        broker.register(router)
        storage.register(router)
        alarms.register(router)
        tabs.register(router)
        webNavigation.register(router)
        webRequest.register(router)
        declarativeNetRequest.register(router)
        contextMenus.register(router)
        actionApi.register(router)
        scripting.register(router)
        UserScriptsApi(dynamicScripts) { id -> tabHost.refreshDynamicScripts(id) }.register(router)
        OffscreenApi(pages).register(router)
        permissions.register(router)
        ClipboardApi(context.applicationContext).register(router)
        NetApi().register(router)
        NotificationsApi(context.applicationContext, pages).register(router)
        BookmarksApi(context.applicationContext, pages).register(router)
        HistoryApi(historyStore).register(router)
        DownloadsApi(context.applicationContext, pages).register(router)
        CookiesApi().register(router)
        NativeScriptsApi(nativeScripts).register(router)
        pages.backgroundListener = lifecycle
        sinks.add(tabHost)
        sinks.add(pages)
    }

    /** 浏览器界面建好后（主线程）接上标签页桥接；重复调用会替换旧的 */
    fun attachTabs(bridge: TabBridge) {
        attachedBridge?.removeListener(activeTab)
        tabs.attach(bridge)
        bridge.addListener(activeTab)
        attachedBridge = bridge
    }

    /** 浏览器界面销毁时摘掉桥接，避免持有已销毁的标签状态 */
    fun detachTabs() {
        attachedBridge?.removeListener(activeTab)
        attachedBridge = null
        tabs.detach()
    }

    /** 只在当前接着的就是 [bridge] 时才摘：界面重建后旧界面的销毁回调晚到，不会把新界面刚接上的桥接摘掉 */
    fun detachTabs(bridge: TabBridge) {
        if (attachedBridge === bridge) {
            bridge.removeListener(activeTab)
            attachedBridge = null
        }
        tabs.detach(bridge)
    }

    /** WebViewClient 主框架导航回调；调用方不得传递网页脚本自报的 URL。 */
    fun onContextMenuClicked(extId: String, itemId: String, info: org.json.JSONObject, tab: org.json.JSONObject? = null) =
        contextMenus.click(extId, itemId, info, tab)

    fun contextMenuItems(context: String, documentUrl: String, targetUrl: String? = null): List<Pair<InstalledExtension, List<ContextMenusApi.Item>>> =
        webViewExtensions().mapNotNull { ext -> contextMenus.matching(ext, context, documentUrl, targetUrl).takeIf { it.isNotEmpty() }?.let { ext to it } }

    fun onWebRequestBefore(tabId: Int, request: android.webkit.WebResourceRequest): WebResourceResponse? =
        declarativeNetRequest.intercept(tabId, request) ?: webRequest.beforeRequest(tabId, request)
    // 以下两个 WebViewClient 近似事件，在由 NavigationListener 驱动的标签上让位给 [navigationSink]（真实状态码 / 响应头）
    fun onWebRequestMainFrameResponseStarted(tabId: Int, url: String) {
        if (!webNavigation.isListenerDriven(tabId)) webRequest.mainFrameResponseStarted(tabId, url)
    }
    fun onWebRequestMainFrameCompleted(tabId: Int, url: String) {
        if (!webNavigation.isListenerDriven(tabId)) webRequest.mainFrameCompleted(tabId, url)
    }
    fun onWebRequestMainFrameFailed(tabId: Int, url: String, error: String) = webRequest.mainFrameFailed(tabId, url, error)

    fun onWebNavigationBefore(tabId: Int, url: String, transitionType: String = "other") = webNavigation.beforeNavigate(tabId, url, transitionType)
    fun onWebNavigationStarted(tabId: Int, url: String) = webNavigation.ensureBeforeNavigate(tabId, url)
    fun onWebNavigationCommitted(tabId: Int, url: String) {
        if (!webNavigation.isListenerDriven(tabId)) webNavigation.committed(tabId, url)
    }
    fun onWebNavigationError(tabId: Int, url: String, error: String) = webNavigation.errorOccurred(tabId, url, error)
    fun onWebNavigationForgetTab(tabId: Int) = webNavigation.forgetTab(tabId)
    fun onWebNavigationCompleted(tabId: Int, url: String) {
        if (!webNavigation.isListenerDriven(tabId)) webNavigation.completed(tabId, url)
    }
    fun onWebNavigationHistoryState(tabId: Int, url: String) = webNavigation.historyStateUpdated(tabId, url)
    fun onWebNavigationFragment(tabId: Int, url: String) = webNavigation.referenceFragmentUpdated(tabId, url)

    private val navigationSink = object : NavigationSink {
        override fun redirected(tabId: Int, fromUrl: String, toUrl: String, statusCode: Int?, headers: org.json.JSONArray?) =
            webRequest.mainFrameRedirected(tabId, fromUrl, toUrl, statusCode, headers)

        override fun committed(
            tabId: Int, url: String, transitionType: String, qualifiers: List<String>,
            statusCode: Int?, headers: org.json.JSONArray?,
        ) {
            webNavigation.committed(tabId, url, transitionType, qualifiers)
            webRequest.mainFrameResponse(tabId, url, statusCode, headers)
        }

        override fun domContentLoaded(tabId: Int, url: String) = webNavigation.domContentLoaded(tabId, url)

        override fun loaded(tabId: Int, url: String, statusCode: Int?, headers: org.json.JSONArray?) {
            webNavigation.completed(tabId, url)
            webRequest.mainFrameCompleted(tabId, url, statusCode, headers)
        }
    }

    /**
     * 给非无痕的 WebView 标签接上 NavigationListener。成功后该标签的 committed / completed / webRequest 主文档事件
     * 改由真实导航回调驱动；系统 WebView 不支持时返回 false，保持 WebViewClient 近似路径。主线程调用。
     */
    fun attachNavigationListener(webView: android.webkit.WebView, tabId: Int): Boolean {
        if (!WebViewNavigationBridge.attach(webView, tabId, navigationSink)) return false
        webNavigation.setListenerDriven(tabId, true)
        return true
    }

    /**
     * 用户在快捷菜单里点了某个拓展：对当前前台标签的当前 origin 授予 activeTab。
     * 拓展没声明 / 没获授 `activeTab`、没有前台标签、前台不是 http(s) 页面时返回 false（什么也不授予）。
     * 必须由用户手势触发（点击菜单项），不要从拓展自己的请求里调用。
     */
    suspend fun grantActiveTab(extId: String): Boolean {
        val ext = repo.get(extId)?.takeIf { it.enabled && EngineKind.WEBVIEW in it.engines } ?: return false
        if ("activeTab" !in PermissionChecker.effectivePermissions(ext)) return false
        val tab = tabs.bridgeOrNull()?.list()?.firstOrNull { it.active } ?: return false
        return activeTab.grant(extId, tab)
    }

    /** 拓展页面里的外链：交给普通标签打开。网址过 [TabUrlPolicy]（只放行 http / https，且不能是别的拓展的页面） */
    private fun openExternalLink(extId: String, url: String) {
        val bridge = tabs.bridgeOrNull() ?: return
        val safe = try { TabUrlPolicy.normalize(extId, url) } catch (_: ApiException) { return }
        uiScope.launch { runCatching { bridge.create(TabCreateOptions(url = safe, active = true)) } }
    }

    /**
     * 标签页里的网页请求了拓展的虚拟 origin（`runtime.getURL()` 给的地址）：只放行 web_accessible_resources 声明的资源，
     * 并带上 CORS / CORP 头，让 content script 注入的 `<img>`、`fetch` 能用。不是拓展 origin 的请求返回 null（交给后续拦截器）。
     * 在 WebView 的 IO 线程上调用；[pageUrl] 必须是浏览器自己记录的标签网址，不能取页面自报的。
     */
    fun webAccessibleResponse(url: String, pageUrl: String?): WebResourceResponse? {
        val uri = try { java.net.URI(url) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !ExtensionOrigins.isExtensionHost(host)) return null
        val ext = webViewExtensions().firstOrNull { ExtensionOrigins.hostFor(it.spec.id) == host }
        val res = ext?.let { ExtensionPages.openWebAccessible(it, uri.path.orEmpty(), pageUrl) }
        if (res == null) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val stream = res.file?.inputStream() ?: ByteArrayInputStream(res.bytes ?: ByteArray(0))
        val textual = res.mime.startsWith("text/") || res.mime == "application/json" || res.mime == "application/xml"
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cross-Origin-Resource-Policy" to "cross-origin",
            "Cache-Control" to "no-cache",
        )
        return WebResourceResponse(res.mime, if (textual) "utf-8" else null, 200, "OK", headers, stream)
    }

    /** 已启用、能在 WebView 上运行的拓展：标签页注入、后台页、菜单角标都用它（无痕范围由调用方过滤） */
    fun webViewExtensions(): List<InstalledExtension> =
        repo.list().filter { it.enabled && EngineKind.WEBVIEW in it.engines }

    /** assets/mafari_ext_shim.js 全文（读一次缓存） */
    fun shim(): String = shimText ?: context.assets.open("mafari_ext_shim.js").bufferedReader(Charsets.UTF_8)
        .use { it.readText() }.also { shimText = it }

    /**
     * 冷启动预热（**后台线程**调用）：把首个标签装配拓展时主线程要做的事提前做完——
     * 读仓库（state 目录下的 json + 解析每个 manifest）、读 shim、预处理各拓展的 content script（读文件 / 打包 / 本地化）。
     * 都做完后，主线程上 [WebViewExtensionHost.install] 只剩拼接字符串；没有拓展时只是把系统对象建好。
     * [injection] = false：只读仓库、不预处理 content script（默认内核不是 WebView 时，标签装配用不上）。
     * 失败无妨：主线程用到时照旧同步做一遍。
     */
    fun prewarm(injection: Boolean = true) {
        if (!active) return
        val exts = webViewExtensions()
        if (!injection || exts.isEmpty()) return
        shim()
        tabHost.prewarm(exts)
    }

    private val startHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 应用启动后（主线程，幂等）：拉起所有已启用拓展的后台页。每个后台页就绪后先收到 runtime.onStartup，
     * 再收到补发的 alarms.onAlarm（见 [ExtensionLifecycle]）。
     *
     * 每个后台页都是一个隐藏 WebView，创建有开销。[staggerMs] > 0 时第一个立即拉起，其余每个依次再晚 [staggerMs]，
     * 避免多个拓展在主线程上挤成一次长卡顿；默认 0 = 全部立即拉起（与旧行为一致）。
     */
    fun startAll(staggerMs: Long = 0L) {
        if (!active) return
        var delay = 0L
        for (ext in webViewExtensions()) {
            val id = ext.spec.id
            if (delay == 0L) startOne(id) else startHandler.postDelayed({ startOne(id) }, delay)
            delay += staggerMs
        }
    }

    /**
     * 总闸（网页增强开关）：false 时不预热、不拉后台页、安装 / 启用也不启动，并立刻关掉所有已在运行的后台页与标签注入；
     * 不改任何拓展自己的「启用」状态，所以再打开时原来启用的那些照常回来。
     */
    @Volatile var active: Boolean = true
        private set

    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        if (!on) stopAll()
    }

    /** 关掉所有 WebView 拓展的后台页（主线程）：默认内核切到 Gecko 时用，释放隐藏 WebView。不改拓展的启用状态，下次启动照常拉起。 */
    fun stopAll() {
        for (ext in webViewExtensions()) onDisabled(ext.spec.id) // 页面 / 连接 / 标签注入 / webRequest / 闹钟计时一并收掉（不改仓库状态）
    }

    /**
     * 拉起一个拓展的后台页。延后执行的启动要重新确认：期间拓展可能已被停用 / 卸载，
     * 或已被 [onInstalled] / [onEnabled] 拉起（[ExtensionPageHost.startBackground] 对已在运行的返回 null，不会重复拉）。
     */
    private fun startOne(id: String) {
        if (!active) return
        val ext = repo.get(id)?.takeIf { it.enabled && EngineKind.WEBVIEW in it.engines } ?: return
        if (pages.startBackground(ext) != null) lifecycle.expectStartup(id)
    }

    /**
     * 安装 / 更新完成后（主线程）：重启后台页。[previous] 为更新前的版本（全新安装传 null）：
     * 后台页就绪后收到 runtime.onInstalled（reason = install / update）；更新会清掉旧闹钟，与 Chrome 一致。
     */
    fun onInstalled(ext: InstalledExtension, previous: InstalledExtension? = null) {
        val id = ext.spec.id
        activeTab.revokeExtension(id)
        lifecycle.cancel(id)
        pages.shutdown(id)
        tabHost.forget(id)
        actionApi.states.remove(id)
        webRequest.forget(id)
        storage.forget(id)
        bgExecutor.execute { alarms.reset(id) } // 与就绪 / 消失事件同一个线程，保证顺序
        if (active && ext.enabled && EngineKind.WEBVIEW in ext.engines) {
            if (pages.startBackground(ext) != null) lifecycle.expectInstalled(id, previous?.spec?.version)
        }
    }

    /** 重新启用（主线程）：拉起后台页，不触发 onInstalled / onStartup；闹钟保留，就绪后补发错过的 */
    fun onEnabled(ext: InstalledExtension) {
        val id = ext.spec.id
        lifecycle.cancel(id)
        pages.shutdown(id)
        tabHost.forget(id)
        if (active && ext.enabled && EngineKind.WEBVIEW in ext.engines) pages.startBackground(ext)
    }

    /** 停用（主线程）：关掉页面与连接；已打开标签里的 content script 要等页面重新加载才会消失。闹钟留在磁盘上 */
    fun onDisabled(extId: String) {
        activeTab.revokeExtension(extId)
        lifecycle.cancel(extId)
        pages.shutdown(extId)
        tabHost.forget(extId)
        webRequest.forget(extId)
        bgExecutor.execute { alarms.forget(extId) }
    }

    /** 卸载（主线程）：先关页面再删仓库，避免页面还在读已删除的文件 */
    fun onUninstalled(extId: String) {
        val installed = repo.get(extId)
        onDisabled(extId)
        if (installed != null) dynamicScripts.clearAll(installed) else dynamicScripts.clearSession(extId)
        storage.forget(extId)
        contextMenus.forget(extId)
        actionApi.states.remove(extId)
        declarativeNetRequest.forget(extId)
        ExtensionProfiles.delete(extId)
        repo.remove(extId)
    }
}
