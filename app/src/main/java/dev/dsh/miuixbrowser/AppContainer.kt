package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.FavoritesStore
import dev.dsh.miuixbrowser.data.HistoryStore
import dev.dsh.miuixbrowser.script.ExtensionStore
import dev.dsh.miuixbrowser.ext.ExtensionSystem
import dev.dsh.miuixbrowser.util.Diag
import android.app.Application
import android.content.Context
import java.io.File

/**
 * 进程内唯一的一组共享对象，由 [MafariApp] 持有。
 *
 * 之前 AppSettings / FavoritesStore / HistoryStore / ExtensionStore 在 MainActivity、BrowserScreen、WebAppActivity、
 * Floccus 书签桥等处各自 new：每份都有自己的 Compose 状态、延后写库标志和缓存，互相看不到对方的改动。
 * 现在都从这里取，同一进程里只有一份；全部懒创建，第一次用到才构造（构造 Favorites / Extensions 会同步打开加密库，
 * 所以仍须在 [StartupCoordinator] 的库就绪闸门之后再取，和以前 BrowserScreen 首次组合时的时机一致）。
 *
 * 只放「应用级、随进程存活」的对象。和某个界面 / 标签绑定的状态（BrowserState、各 Screen 的 UI 状态）不要放这里。
 */
class AppContainer(app: Application) {
    private val appContext: Context = app.applicationContext

    val settings: AppSettings by lazy { AppSettings(appContext) }
    val favorites: FavoritesStore by lazy { FavoritesStore(appContext) }
    val history: HistoryStore by lazy { HistoryStore(appContext) }
    val extensions: ExtensionStore by lazy { ExtensionStore(appContext) }

    /**
     * WebView 内核的 WebExtension 运行时（拓展仓库 / 安装器 / 后台页 / 标签页注入）。
     * WebExtension 与原生 Userscript 使用独立运行时和权限；可信拓展可通过 mafariScripts 权限调用原生脚本管理 API。Gecko 内核的 WebExtension 由 GeckoView 自己管理，不走这里。
     * 懒创建：构造只建对象，不读盘；仓库在第一次 list() 时才读状态文件。
     */
    val webExtensions: ExtensionSystem by lazy {
        ExtensionSystem(appContext, File(appContext.filesDir, "webext"), nativeScripts = { extensions }) { Diag.log(it) }
    }
}

/** 取进程级容器。任何 Context（Activity / Service / Application）都可以调用。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as MafariApp).container
