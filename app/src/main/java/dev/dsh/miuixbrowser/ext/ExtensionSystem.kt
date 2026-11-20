package dev.dsh.miuixbrowser.ext

import android.content.Context
import android.webkit.WebResourceResponse
import dev.dsh.miuixbrowser.data.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * 拓展系统的组装入口（WebView 内核）：一个应用一个实例，建议放进 AppContainer。
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
    private val log: (String) -> Unit = {},
) {
    val repo = FileExtensionRepository(baseDir) { id, e -> log("ext $id: 读取失败 ${e.message}") }
    val installer = PackageInstaller(repo, File(baseDir, "work"))

    private val router = ApiRouter()
    private val historyStore by lazy { HistoryStore(context.applicationContext) }
    val broker = MessageBroker()
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
    private val tabs = TabsApi(pages, { webViewExtensions() }, broker, activeTab)
    private val webNavigation = WebNavigationApi(pages, { webViewExtensions() }) { tabs.bridgeOrNull() }
    val contextMenus = ContextMenusApi(pages)
    val actionApi = ActionApi()
    private val webRequest = WebRequestApi(pages) { webViewExtensions() }

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
        RuntimeApi(broker) { tabs.bridgeOrNull() }.register(router)
        broker.register(router)
        storage.register(router)
        alarms.register(router)
        tabs.register(router)
        webNavigation.register(router)
        webRequest.register(router)
        contextMenus.register(router)
        actionApi.register(router)
        scripting.register(router)
        permissions.register(router)
        ClipboardApi(context.applicationContext).register(router)
        NotificationsApi(context.applicationContext, pages).register(router)
        BookmarksApi(context.applicationContext, pages).register(router)
        HistoryApi(historyStore).register(router)
        DownloadsApi(context.applicationContext, pages).register(router)
        CookiesApi().register(router)
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

    fun onWebRequestBefore(tabId: Int, request: android.webkit.WebResourceRequest) = webRequest.beforeRequest(tabId, request)
    fun onWebRequestMainFrameCompleted(tabId: Int, url: String) = webRequest.mainFrameCompleted(tabId, url)
    fun onWebRequestMainFrameFailed(tabId: Int, url: String, error: String) = webRequest.mainFrameFailed(tabId, url, error)

    fun onWebNavigationBefore(tabId: Int, url: String, transitionType: String = "other") = webNavigation.beforeNavigate(tabId, url, transitionType)
    fun onWebNavigationStarted(tabId: Int, url: String) = webNavigation.ensureBeforeNavigate(tabId, url)
    fun onWebNavigationCommitted(tabId: Int, url: String) = webNavigation.committed(tabId, url)
    fun onWebNavigationError(tabId: Int, url: String, error: String) = webNavigation.errorOccurred(tabId, url, error)
    fun onWebNavigationForgetTab(tabId: Int) = webNavigation.forgetTab(tabId)
    fun onWebNavigationCompleted(tabId: Int, url: String) = webNavigation.completed(tabId, url)
    fun onWebNavigationHistoryState(tabId: Int, url: String) = webNavigation.historyStateUpdated(tabId, url)
    fun onWebNavigationFragment(tabId: Int, url: String) = webNavigation.referenceFragmentUpdated(tabId, url)

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
     * 应用启动后（主线程，幂等）：拉起所有已启用拓展的后台页。每个后台页就绪后先收到 runtime.onStartup，
     * 再收到补发的 alarms.onAlarm（见 [ExtensionLifecycle]）。
     */
    fun startAll() {
        for (ext in webViewExtensions()) {
            if (pages.startBackground(ext) != null) lifecycle.expectStartup(ext.spec.id)
        }
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
        if (ext.enabled && EngineKind.WEBVIEW in ext.engines) {
            if (pages.startBackground(ext) != null) lifecycle.expectInstalled(id, previous?.spec?.version)
        }
    }

    /** 重新启用（主线程）：拉起后台页，不触发 onInstalled / onStartup；闹钟保留，就绪后补发错过的 */
    fun onEnabled(ext: InstalledExtension) {
        val id = ext.spec.id
        lifecycle.cancel(id)
        pages.shutdown(id)
        tabHost.forget(id)
        if (ext.enabled && EngineKind.WEBVIEW in ext.engines) pages.startBackground(ext)
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
        repo.remove(extId)
    }
}
