package dev.dsh.miuixbrowser.ext

import android.content.Context
import java.io.File

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
 * 应用启动后（主线程）：`system.pages.startAllBackgrounds()`。
 * 安装 / 停用 / 卸载：见 [onInstalled] / [onDisabled] / [onUninstalled]，它们负责重启后台页并清理状态。
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

    @Volatile private var shimText: String? = null

    init {
        RuntimeApi(broker).register(router)
        broker.register(router)
        storage.register(router)
        sinks.add(tabHost)
        sinks.add(pages)
    }

    /** 已启用、能在 WebView 上运行的拓展：标签页注入、后台页、菜单角标都用它（无痕范围由调用方过滤） */
    fun webViewExtensions(): List<InstalledExtension> =
        repo.list().filter { it.enabled && EngineKind.WEBVIEW in it.engines }

    /** assets/mafari_ext_shim.js 全文（读一次缓存） */
    fun shim(): String = shimText ?: context.assets.open("mafari_ext_shim.js").bufferedReader(Charsets.UTF_8)
        .use { it.readText() }.also { shimText = it }

    /** 安装 / 更新完成后（主线程）：重启后台页 */
    fun onInstalled(ext: InstalledExtension) {
        pages.shutdown(ext.spec.id)
        tabHost.forget(ext.spec.id)
        storage.forget(ext.spec.id)
        if (ext.enabled && EngineKind.WEBVIEW in ext.engines) pages.startBackground(ext)
    }

    /** 停用（主线程）：关掉页面与连接；已打开标签里的 content script 要等页面重新加载才会消失 */
    fun onDisabled(extId: String) {
        pages.shutdown(extId)
        tabHost.forget(extId)
    }

    /** 卸载（主线程）：先关页面再删仓库，避免页面还在读已删除的文件 */
    fun onUninstalled(extId: String) {
        onDisabled(extId)
        storage.forget(extId)
        repo.remove(extId)
    }
}
