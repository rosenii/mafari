package dev.dsh.miuixbrowser.ext

import android.content.Context
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
    val broker = MessageBroker()
    private val sinks = CompositeEventSink()
    private val storage = StorageApi({ repo.dataDir(it) }, sinks)
    val runtime = ExtensionRuntime(repo, router)

    val tabHost = WebViewExtensionHost(runtime, log)
    val pages = ExtensionPageHost(context.applicationContext, repo, runtime, broker, { shim() }, log)

    /**
     * tabs.*：事件只发往拓展自己的页面（[pages]），不走 [sinks]——后者包含标签页里的 content script，
     * 标签的网址不能推到网页里去。浏览器界面建好后调用 [attachTabs] 接上真实的标签桥接（BrowserScreen 里接），之前调用回 UNSUPPORTED。
     */
    private val tabs = TabsApi(pages) { webViewExtensions() }

    /** scripting.*：与 tabs 共用同一个标签桥接（[TabsApi.bridgeOrNull]），attach 之前同样回 UNSUPPORTED */
    private val scripting = ScriptingApi({ tabs.bridgeOrNull() })

    /**
     * 后台页启动流程与闹钟共用一个单线程 Executor：就绪 / 消失 / 唤醒按顺序处理，文件读写与事件投递都不占主线程。
     * alarms / runtime 启动事件只投给拓展自己的页面（[pages]），content script 里没有这些 API。
     */
    private val bgExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ext-background").apply { isDaemon = true } }
    private val alarms = AlarmService({ repo.dataDir(it) }, pages, HandlerWakeTimer(bgExecutor), log = log)
    private val lifecycle = ExtensionLifecycle(pages, alarms) { bgExecutor.execute(it) }

    @Volatile private var shimText: String? = null

    init {
        RuntimeApi(broker).register(router)
        broker.register(router)
        storage.register(router)
        alarms.register(router)
        tabs.register(router)
        scripting.register(router)
        pages.backgroundListener = lifecycle
        sinks.add(tabHost)
        sinks.add(pages)
    }

    /** 浏览器界面建好后（主线程）接上标签页桥接；重复调用会替换旧的 */
    fun attachTabs(bridge: TabBridge) = tabs.attach(bridge)

    /** 浏览器界面销毁时摘掉桥接，避免持有已销毁的标签状态 */
    fun detachTabs() = tabs.detach()

    /** 只在当前接着的就是 [bridge] 时才摘：界面重建后旧界面的销毁回调晚到，不会把新界面刚接上的桥接摘掉 */
    fun detachTabs(bridge: TabBridge) = tabs.detach(bridge)

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
        lifecycle.cancel(id)
        pages.shutdown(id)
        tabHost.forget(id)
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
        lifecycle.cancel(extId)
        pages.shutdown(extId)
        tabHost.forget(extId)
        bgExecutor.execute { alarms.forget(extId) }
    }

    /** 卸载（主线程）：先关页面再删仓库，避免页面还在读已删除的文件 */
    fun onUninstalled(extId: String) {
        onDisabled(extId)
        storage.forget(extId)
        repo.remove(extId)
    }
}
