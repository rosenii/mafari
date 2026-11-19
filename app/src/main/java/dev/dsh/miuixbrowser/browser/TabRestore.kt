package dev.dsh.miuixbrowser.browser

import android.view.ViewGroup
import android.webkit.WebSettings
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.TabDb
import dev.dsh.miuixbrowser.util.Diag

/**
 * 标签页的「待恢复」状态与 Gecko 引擎回收（从 BrowserTab 拆出）：
 * 重启后恢复出来的标签、被回收引擎的后台标签，都先是「页面被盖住、还没加载」，
 * 成为当前标签或用户点回页面时才真正加载；Gecko 的会话状态（前进后退栈 + 滚动位置）落在 tabs 表里。
 * 页面地址 / 标题 / 加载状态等仍是 [BrowserTab] 上的属性，这里只持有恢复流程自己的标记。
 */
internal class TabRestore(private val tab: BrowserTab) {

    // ---------------------------------------------------------------- 从 tabs 表恢复（见 TabDb / BrowserState.restoreSavedTabs）
    //
    // 恢复出来的标签先是「被主页盖住、页面还没加载」的状态：地址记在 savedUrl，不碰网络。
    // 它成为当前标签时（[wakeIfPending]）、或用户从主页点回它的页面时（[restoreFromHome]）才真正加载，
    // 所以重启后不会同时把所有标签的页面都拉起来。

    var pendingRestore = false
        private set
    private var pendingOnHome = false
    private var pendingDesktop = false

    /** 库里有这个页面的 Gecko 会话状态（前进后退栈 + 滚动位置）：唤醒时用 restoreState 而不是只加载地址 */
    private var pendingHasState = false

    /**
     * 正在用库里的状态恢复页面：从 [restoreWithState] 开始，到引擎报出第一个非空白地址为止。
     * 这段时间引擎里的会话状态还是空白的，不能把它存回库里（见 [persistSessionState]）。
     */
    var stateRestorePending = false

    /** 每次用户 / 系统发起新导航（[load] / [loadUrl]）加一：异步恢复回来时据此判断自己是否已过期 */
    private var navEpoch = 0

    /** 标签的页面还没加载、只是用保存的地址 / 标题占位（卡片上要显示这个页面的域名，而不是「主页」） */
    val showsSavedPage: Boolean get() = pendingRestore && !pendingOnHome

    /** 要保存的桌面模式状态：还没加载的恢复标签也保留这个标志 */
    val wantsDesktop: Boolean get() = tab.desktopMode || pendingDesktop

    fun prepare(
        target: String,
        savedTitle: String,
        onHome: Boolean,
        desktop: Boolean,
        fromLink: Boolean,
        hasState: Boolean = false,
    ) {
        tab.openedFromLink = fromLink
        if (target.isBlank()) return // 空白标签：就是一个主页标签，没有页面可恢复
        tab.savedUrl = target
        if (savedTitle.isNotBlank()) tab.title = savedTitle
        tab.parked = true
        tab.url = ""
        pendingRestore = true
        pendingOnHome = onHome
        pendingDesktop = desktop
        pendingHasState = hasState && tab.isGecko
    }

    /** 页面被新的导航替掉：旧的「待恢复」作废（否则之后从主页点回来会把旧地址又加载一遍） */
    fun dropPending() {
        navEpoch++
        pendingRestore = false
        pendingHasState = false
    }

    /** 成为当前标签时调用：该显示页面的恢复标签此刻才加载 */
    fun wakeIfPending() {
        if (pendingRestore && !pendingOnHome) loadPending()
    }

    fun loadPending() {
        val target = tab.savedUrl
        val withState = pendingHasState
        pendingRestore = false
        pendingHasState = false
        if (pendingDesktop) {
            pendingDesktop = false
            tab.desktopMode = true
            if (!tab.isGecko) {
                runCatching {
                    tab.webView.settings.apply {
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                    }
                }
                tab.installDesktopScript(true)
            }
            tab.applyUserAgent()
        }
        if (target.isBlank()) return
        if (withState && tab.isGecko) restoreWithState(target) else tab.load(target)
    }

    /**
     * 用库里保存的 Gecko 会话状态恢复页面：前进后退栈、滚动位置都回来。
     * 引擎此刻同步建好（拓展 / 界面马上要用它的 session），状态从库里异步读，读不到或不匹配就按地址正常加载。
     * 等待期间用户又自己导航了（[navEpoch] 变了）就放弃恢复，不拿旧状态覆盖新页面。
     */
    private fun restoreWithState(target: String) {
        // 与 load() 从主页进入页面的约定一致：这一页是从主页进来的，回退栈到头再返回就回主页
        tab.fromHome = !tab.openedFromLink
        tab.homeAnchor = -1
        stateRestorePending = true
        tab.savedUrl = ""
        tab.parked = false
        tab.url = target
        tab.errorText = null
        tab.loading = true
        tab.syncNav()
        val g = tab.ensureGecko()
        val db = AppDatabase.get(tab.appContext)
        if (g == null || db == null) {
            tab.engineLoad(target)
            return
        }
        val epoch = navEpoch
        val key = tab.dbKey
        AppDatabase.io.execute {
            val saved = runCatching { TabDb.loadState(db, key) }
                .onFailure { Diag.log("tabs: load state failed: ${it.message}") }
                .getOrNull()
            tab.mainHandler.post {
                if (tab.closed || tab.destroyed || epoch != navEpoch) return@post
                val engine = tab.geckoEngine ?: return@post
                val restored = saved != null && engine.restoreState(saved)
                if (!restored) {
                    Diag.log("tabs: state restore unavailable, loading url")
                    tab.engineLoad(target)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 后台回收 / Gecko 会话状态持久化
    //
    // 后台标签闲置太久（或活着的引擎太多）时，[discardEngine] 把它的 GeckoSession 连同 GeckoView 一起销毁，
    // 标签变回和「重启后恢复」一样的「待恢复」状态（[pendingRestore]）：卡片、标题、缩略图都还在，
    // 再次切回去时由 [loadPending] 用保存的 [GeckoSession.SessionState] 恢复（前进后退栈、滚动位置）。
    // 会话状态存在 tabs 表的 state 列（见 [TabDb]），重启后恢复走的是同一条路径。

    /**
     * 能不能回收引擎：只回收「普通、Gecko 自建 session、已有页面、没在加载、没在全屏」的后台标签。
     * 无痕标签的状态不能落盘，回收就丢页面，所以永不回收；拓展 tabs.create 的 session 由拓展持有，也不回收。
     * （是不是当前标签由调用方判断。）
     */
    fun canDiscard(): Boolean {
        val g = tab.geckoEngine ?: return false
        if (tab.closed || tab.destroyed || tab.incognito || tab.startIncognito || tab.adoptedGeckoSession != null) return false
        if (tab.loading || g.inFullScreen) return false
        return tab.persistedPageUrl.isNotBlank()
    }

    /**
     * 回收引擎（主线程）：先把会话状态写进库，再销毁 session / view，标签变回「待恢复」。
     * 写库失败（库不可用）就不回收，宁可多占内存也不丢页面。
     */
    fun discardEngine(): Boolean {
        if (!canDiscard()) return false
        val g = tab.geckoEngine ?: return false
        val db = AppDatabase.get(tab.appContext) ?: return false
        val page = tab.persistedPageUrl
        // 页面还没加载进引擎（待恢复 / 正在恢复）时，引擎里的状态是空白的，库里那份才是真的：不能用它覆盖
        val dbStateAuthoritative = pendingRestore || stateRestorePending
        val state = if (dbStateAuthoritative) null else g.serializedState()
        if (state != null) {
            val key = tab.dbKey
            AppDatabase.io.execute {
                runCatching { TabDb.saveState(db, key, state, page) }
                    .onFailure { Diag.log("tabs: save state failed: ${it.message}") }
            }
        }
        runCatching { (g.view.parent as? ViewGroup)?.removeView(g.view) }
        runCatching { g.destroy() }
        tab.geckoEngine = null
        // 变回「待恢复」：回过主页的（页面被盖住）仍是被盖住；正显示页面的退到「卡片显示域名」的状态
        val covered = tab.url.isEmpty()
        if (!covered) {
            tab.savedUrl = tab.url
            tab.url = ""
            tab.parked = true
        }
        pendingHasState = if (pendingRestore) pendingHasState else (stateRestorePending || state != null)
        pendingRestore = true
        pendingOnHome = covered
        tab.loading = false
        tab.errorText = null
        tab.syncNav()
        Diag.log("tabs: engine discarded (${if (state != null) "with" else "without"} state) $page")
        return true
    }

    /** 把有变化的会话状态写进库（退到后台时由 [BrowserState.persistSessionStates] 统一调用）；无痕标签不落盘 */
    fun persistSessionState() {
        if (tab.incognito || tab.startIncognito || tab.closed || tab.destroyed) return
        val g = tab.geckoEngine ?: return
        val page = tab.persistedPageUrl
        if (page.isBlank()) return
        // 待恢复 / 正在恢复：引擎里还是空白状态，库里那份才是真的，不能覆盖（见 discardEngine）
        if (pendingRestore || stateRestorePending) return
        val state = g.takeDirtyState() ?: return
        val db = AppDatabase.get(tab.appContext) ?: return
        val key = tab.dbKey
        AppDatabase.io.execute {
            runCatching { TabDb.saveState(db, key, state, page) }
                .onFailure { Diag.log("tabs: save state failed: ${it.message}") }
        }
    }
}
