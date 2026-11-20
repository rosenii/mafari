package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.appContainer
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.ENGINE_GECKO
import dev.dsh.miuixbrowser.data.HOME_MODE_CUSTOM
import dev.dsh.miuixbrowser.data.HOME_MODE_NEW_TAB
import dev.dsh.miuixbrowser.data.RestoreMode
import dev.dsh.miuixbrowser.data.TabDb
import dev.dsh.miuixbrowser.data.TabRow
import dev.dsh.miuixbrowser.data.readBrowserEngine
import dev.dsh.miuixbrowser.data.readHomeCustomUrl
import dev.dsh.miuixbrowser.data.readHomeMode
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtTabHost
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtensionStore
import dev.dsh.miuixbrowser.engine.gecko.GeckoRuntimeHolder
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.looksLikeUrl
import dev.dsh.miuixbrowser.util.toUrl
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 多标签状态。 */
class BrowserState(private val context: Context, initialUserAgent: String = "") : GeckoExtTabHost {

    val tabs = mutableStateListOf<BrowserTab>()

    /** 全局 UA，新标签也用它 */
    var userAgent: String = initialUserAgent

    var activeIndex by mutableIntStateOf(0)
        private set

    /** 最近激活过的标签 id（越靠后越新）：关闭当前标签时，没有 opener 就回到「上一个用过的」 */
    private val recentIds = mutableListOf<Int>()

    val activeTab: BrowserTab
        get() = tabs[activeIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))]

    // 下面几个必须声明在 init 之前：init 里就要用

    /**
     * 标签列表正在分批恢复：此期间内存里的标签列表还不完整，所以不能落盘（[saveTabs] 的整表快照会把还没恢复出来的标签从库里删掉），
     * 给拓展的标签事件也先不发（见 BrowserStateTabBridge）。是 Compose 状态，结束时观察者会收到变化。
     */
    private var restoring by mutableStateOf(false)

    internal val isRestoring: Boolean get() = restoring

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 周期性回收闲置的后台 Gecko 引擎；旧实例（界面重建后被新 BrowserState 取代）自己停掉 */
    private val reclaimTick = object : Runnable {
        override fun run() {
            if (current !== this@BrowserState) return
            reclaimEngines()
            mainHandler.postDelayed(this, RECLAIM_INTERVAL_MS)
        }
    }

    init {
        // 拓展（油猴等）通过 tabs.create / tabs.update / tabs.remove 操作浏览器标签时的回调入口
        if (EngineSupport.GECKO_BUNDLED) GeckoExtensionStore.tabHost = this
        current = this
        // 冷启动按「重启恢复标签页」的三种方式处理（见 AppSettings.restoreTabsMode）：
        //  0 恢复为退出前的状态：标签列表原样回来，回到最近用的那个标签（页面只加载它，其余切到前台才加载）
        //  1 恢复标签并打开新标签页：标签列表回来但不跳转，落在一个空白标签上（已有空白标签就直接用它，不重复新建）
        //  2 不恢复：不读之前的标签，直接一个新标签页（tabs 表随后被新的标签列表覆盖）
        // 界面重建（同一进程里再建实例）不受影响，仍回到最近用的标签。
        val coldStart = !instanceCreated
        instanceCreated = true
        val mode = if (coldStart) context.appContainer.settings.restoreTabsMode else RestoreMode.ALL
        // 冷启动时标签列表已在库就绪闸门里于后台线程读好（见 StartupCoordinator.awaitContentReady），这里不再在主线程读库
        val preloaded = if (coldStart) TabDb.takePreloaded() else null
        when (mode) {
            RestoreMode.NONE -> newTab()
            RestoreMode.KEEP_NEW -> if (!restoreSavedTabs(activateLast = false, preloaded)) newTab()
            else -> if (!restoreSavedTabs(activateLast = true, preloaded)) newTab()
        }
        if (EngineSupport.GECKO_BUNDLED) mainHandler.postDelayed(reclaimTick, RECLAIM_INTERVAL_MS)
    }

    /**
     * 回收后台 Gecko 引擎（主线程）。当前标签永不回收；回收条件（见 [BrowserTab.canDiscardEngine]）之外还要满足之一：
     *  - 闲置超过 [IDLE_RECLAIM_MS]；
     *  - 活着的引擎超过上限（[MAX_LIVE_ENGINES]，[aggressive] 时收紧到 [TRIM_LIVE_ENGINES]），
     *    按「最久没看」的先收，且至少闲置过一小段时间，刚切走的不会被立刻收掉。
     * 系统正在放音乐时整轮跳过：后台标签可能就是声源，目前没有按标签判断的办法。
     */
    internal fun reclaimEngines(aggressive: Boolean = false) {
        if (!EngineSupport.GECKO_BUNDLED) return
        val live = tabs.filter { it.hasEngine }
        if (live.size <= 1) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audio?.isMusicActive == true) return
        val now = android.os.SystemClock.uptimeMillis()
        val activeNow = tabs.getOrNull(activeIndex)
        val maxLive = if (aggressive) TRIM_LIVE_ENGINES else MAX_LIVE_ENGINES
        val minIdleOverCap = if (aggressive) 15_000L else 60_000L
        var liveCount = live.size
        val candidates = live
            .filter { it !== activeNow && it.canDiscardEngine() }
            .sortedBy { it.lastActiveUptime }
        for (t in candidates) {
            val idle = now - t.lastActiveUptime
            val overCap = liveCount > maxLive && idle >= minIdleOverCap
            if ((overCap || idle >= IDLE_RECLAIM_MS) && t.discardEngine()) liveCount--
        }
    }

    /** 把有变化的 Gecko 会话状态写进库（前进后退栈 + 滚动位置，重启 / 回收后恢复用）。主线程。 */
    internal fun persistSessionStates() {
        tabs.forEach { it.persistSessionState() }
    }

    /**
     * 从 tabs 表恢复上次的普通标签，分两步，避免标签多时把冷启动拖成长时间白屏：
     *  1. 同步只建「马上要显示」的那一个标签（[activateLast] 时是最近用的，否则是已有的空白标签）并切过去，界面立刻出来；
     *  2. 其余标签按库里的顺序插回列表，每次主线程只花几毫秒（[RESTORE_SLICE_MS]），片与片之间让出主线程给绘制 / 触摸。
     * 恢复出来的标签都不加载页面：成为当前标签时才加载（见 [BrowserTab.wakeIfPending]）。没有可恢复的返回 false。
     *
     * [rowsOrNull] 是闸门期间已在后台读好的标签行；没有（库在首帧前就好了）就在这里读。
     */
    private fun restoreSavedTabs(activateLast: Boolean = true, rowsOrNull: List<TabRow>? = null): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val rows = (rowsOrNull ?: run {
            val db = AppDatabase.get(context) ?: return false
            runCatching { TabDb.load(db) }
                .onFailure { Diag.log("tabs: load failed: ${it.message}") }
                .getOrDefault(emptyList())
        }).take(MAX_TABS)
        if (rows.isEmpty()) return false

        val byKey = HashMap<Long, BrowserTab>()
        val rowOfTab = HashMap<Int, Int>() // 标签 id → 它在 rows 里的下标，插回列表时据此还原顺序

        // 恢复出来的标签不建 Gecko 引擎：只有成为当前标签 / 真正要加载页面时才创建（见 BrowserTab.ensureGecko）
        fun build(i: Int): BrowserTab? {
            val row = rows[i]
            val tab = runCatching { createTab(incognito = false, opener = null, lazyEngine = true) }
                .onFailure { Diag.log("tabs: restore tab failed: ${it.message}") }
                .getOrNull() ?: return null
            tab.dbKey = row.key
            tab.lastUsedAt = row.lastUsed
            tab.prepareRestore(row.url, row.title, row.onHome, row.desktop, row.fromLink, row.hasState)
            byKey[row.key] = tab
            rowOfTab[tab.id] = i
            return tab
        }

        // 第一步：只建要先显示的那一个。
        // activateLast：最近用过的；否则（重启后落在空白标签）：第一个空白标签，没有就稍后新建一个。
        var firstIdx = if (activateLast) {
            rows.indices.maxByOrNull { rows[it].lastUsed } ?: 0
        } else {
            rows.indexOfFirst { it.url.isBlank() || it.url.startsWith("about:") }
        }
        var firstTab: BrowserTab? = if (firstIdx >= 0) build(firstIdx) else null
        if (firstTab == null && activateLast) {
            // 最近的那个没建出来：换第一个能建出来的，实在没有就当没有可恢复的
            for (i in rows.indices) {
                if (i == firstIdx) continue
                firstTab = build(i)
                if (firstTab != null) { firstIdx = i; break }
            }
            if (firstTab == null) return false
        }
        val first: BrowserTab? = firstTab
        val firstIndex = firstIdx
        restoring = true
        if (first != null) {
            tabs.add(first)
            activate(0)
        } else {
            newTab(opener = null)
        }
        Diag.log("tabs: first tab ready in ${android.os.SystemClock.elapsedRealtime() - t0}ms, ${rows.size - (if (first != null) 1 else 0)} more to restore")

        // 第二步：其余标签分批插回
        val pending = rows.indices.filter { it != firstIndex || first == null }
        var cursor = 0
        fun insertRestored(tab: BrowserTab, rowIdx: Int) {
            // 插在「已恢复且顺序更靠前」的最后一个标签后面；用户在恢复期间新开的标签（不在 rowOfTab 里）留在原处
            var at = 0
            for (k in tabs.indices.reversed()) {
                val r = rowOfTab[tabs[k].id]
                if (r != null && r < rowIdx) { at = k + 1; break }
            }
            tabs.add(at, tab)
            if (at <= activeIndex) activeIndex++ // 当前标签被挤后一位，下标跟着走
        }
        fun finish() {
            for (row in rows) {
                val tab = byKey[row.key] ?: continue
                if (tab.closed) continue
                tab.openerId = byKey[row.openerKey]?.takeIf { !it.closed }?.id ?: -1
            }
            // 最近使用顺序：恢复出来的旧标签排在恢复期间用户已经切过的标签前面
            val older = byKey.values.filter { !it.closed && it.id !in recentIds }.sortedBy { it.lastUsedAt }.map { it.id }
            recentIds.addAll(0, older)
            restoring = false
            Diag.log("tabs: restored ${tabs.size} tabs in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            saveTabs() // 恢复期间挡掉的保存，补一次
        }
        if (pending.isEmpty()) {
            finish()
            return true
        }
        val step = object : Runnable {
            override fun run() {
                if (current !== this@BrowserState) return // 界面已重建，这个实例作废
                val start = android.os.SystemClock.uptimeMillis()
                while (cursor < pending.size) {
                    if (tabs.size >= MAX_TABS) { cursor = pending.size; break }
                    val i = pending[cursor++]
                    build(i)?.let { insertRestored(it, i) }
                    // 至少建一个；超出这一片的时间预算就让出主线程（WebView 内核建一个就可能超）
                    if (android.os.SystemClock.uptimeMillis() - start >= RESTORE_SLICE_MS) break
                }
                if (cursor < pending.size) mainHandler.postDelayed(this, RESTORE_GAP_MS) else finish()
            }
        }
        // 先让首帧画出来，再开始恢复其余标签
        mainHandler.postDelayed(step, RESTORE_FIRST_DELAY_MS)
        return true
    }

    /** 要落盘的标签列表（只含普通标签）。读取的都是 Compose 状态，可放进 snapshotFlow 里，变化时才触发保存。 */
    internal fun tabRows(): List<TabRow> {
        val byId = HashMap<Int, BrowserTab>(tabs.size * 2)
        tabs.forEach { byId[it.id] = it }
        val out = ArrayList<TabRow>(tabs.size)
        for (t in tabs) {
            if (t.incognito || t.closed) continue
            val page = t.persistedPageUrl
            val opener = byId[t.openerId]?.takeIf { !it.incognito }
            out.add(
                TabRow(
                    key = t.dbKey,
                    pos = out.size,
                    url = page,
                    title = t.title,
                    onHome = t.url.isEmpty() && page.isNotBlank() && !t.showsSavedPage,
                    desktop = t.wantsDesktop,
                    openerKey = opener?.dbKey ?: 0L,
                    fromLink = t.openedFromLink,
                    lastUsed = t.lastUsedAt,
                ),
            )
        }
        return out
    }

    /** 把标签列表写进 tabs 表（快照在主线程取，写入在 [AppDatabase.io] 上单事务执行）。 */
    internal fun saveTabs(rows: List<TabRow> = tabRows()) {
        // 分批恢复期间列表不完整，整表快照会把还没恢复出来的标签从库里删掉：等恢复完再存（见 [restoreSavedTabs]）
        if (restoring) return
        val db = AppDatabase.get(context) ?: return
        AppDatabase.io.execute {
            runCatching { TabDb.sync(db, rows) }.onFailure { Diag.log("tabs: save failed: ${it.message}") }
        }
    }

    /** 唯一的切换入口：同时维护最近使用顺序 */
    private fun activate(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        val id = tabs[index].id
        tabs[index].lastUsedAt = System.currentTimeMillis()
        recentIds.remove(id)
        recentIds.add(id)
        // 当前标签一定有引擎（界面马上要挂它的 view、拓展要用它的 session）；延迟创建 / 被回收的后台标签保持没有
        tabs[index].ensureGecko()
        // Gecko 会话只有「当前标签」保持活动，后台标签停止渲染
        tabs.forEachIndexed { i, t -> t.setEngineActive(i == index) }
        // 从 tabs 表恢复出来的标签，成为当前标签时才真正加载页面
        tabs[index].wakeIfPending()
        // 告诉拓展哪个是当前标签：tabs.query({active:true}) / 安装页等都依赖它，不能只在打开拓展菜单时才登记
        if (EngineSupport.GECKO_BUNDLED) {
            GeckoExtensionStore.activeSession = tabs[index].geckoSession
            GeckoExtensionStore.markActiveTab()
        }
    }

    // ------------------------------------------------ GeckoExtTabHost：拓展对浏览器标签的操作

    private fun indexOfGeckoSession(session: org.mozilla.geckoview.GeckoSession): Int =
        tabs.indexOfFirst { it.geckoSession === session }

    /** tabs.create：用 Gecko 给拓展的 session 开一个真正的浏览器标签（而不是返回 null 让拓展以为失败） */
    override fun openExtensionTab(url: String, active: Boolean, session: org.mozilla.geckoview.GeckoSession): Boolean {
        if (tabs.size >= MAX_TABS) return false
        if (readBrowserEngine(context) != ENGINE_GECKO || !EngineSupport.geckoAvailable()) return false
        val opener = tabs.getOrNull(activeIndex)
        val tab = runCatching { createTab(false, opener, session) }
            .onFailure { Diag.log("ext: create extension tab failed: $it") }
            .getOrNull() ?: return false
        if (!tab.isGecko) {
            tab.close()
            return false
        }
        // 拓展页没有「主页」可回：第一页返回直接关闭并回到来源标签
        tab.openedFromLink = opener != null
        if (active) tabs.getOrNull(activeIndex)?.captureThumbnailNow()
        tabs.add(tab)
        if (active) activate(tabs.lastIndex) else tab.setEngineActive(false)
        tab.adoptExtensionUrl(url)
        Diag.log("ext: extension tab opened url=$url active=$active tabs=${tabs.size}")
        return true
    }

    /** tabs.remove / 安装完成后拓展自己关闭安装页 */
    override fun closeExtensionTab(session: org.mozilla.geckoview.GeckoSession): Boolean {
        val i = indexOfGeckoSession(session)
        if (i < 0) return false
        closeTab(i)
        return true
    }

    /** tabs.update({active:true}) */
    override fun activateExtensionTab(session: org.mozilla.geckoview.GeckoSession): Boolean {
        val i = indexOfGeckoSession(session)
        if (i < 0) return false
        tabs.getOrNull(activeIndex)?.captureThumbnailNow()
        activate(i)
        return true
    }

    /** tabs.update({url}) */
    override fun loadInExtensionTab(session: org.mozilla.geckoview.GeckoSession, url: String): Boolean {
        val i = indexOfGeckoSession(session)
        if (i < 0) return false
        tabs[i].load(url)
        return true
    }

    private fun createTab(
        incognito: Boolean,
        opener: BrowserTab?,
        adoptedGeckoSession: org.mozilla.geckoview.GeckoSession? = null,
        lazyEngine: Boolean = false,
    ): BrowserTab {
        val wantGecko = readBrowserEngine(context) == ENGINE_GECKO && EngineSupport.geckoAvailable()
        val tab = (if (wantGecko) {
            runCatching {
                // 延迟创建的标签构造时不碰 Gecko，所以先在这里把运行时拉起来：启动失败才能照旧退回 WebView
                if (lazyEngine) GeckoRuntimeHolder.get(context)
                BrowserTab(context, EngineKind.GECKO, incognito, adoptedGeckoSession, lazyEngine)
            }
                .onFailure {
                    Diag.log("gecko tab create failed, fallback to WebView: ${it.message}")
                    if (adoptedGeckoSession == null) {
                        android.widget.Toast.makeText(context, tr("Gecko 内核启动失败，已改用 WebView"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .getOrNull()
        } else {
            null
        }) ?: run {
            // 拓展 session 只能由 Gecko 承载：Gecko 标签没建成就不能退回 WebView
            if (adoptedGeckoSession != null) error("gecko tab for extension session unavailable")
            BrowserTab(context, EngineKind.WEBVIEW, incognito)
        }
        tab.customUserAgent = userAgent
        tab.applyUserAgent()
        tab.applyIncognito(incognito)
        tab.openerId = opener?.id ?: -1
        // 页面自己要开的新标签（window.open / 允许的重定向 / GM_openInTab）以它为 opener
        tab.onOpenInNewTab = { u -> newTab(u, tab.incognito, tab, fromLink = true) }
        tab.onRequestClose = { closeTab(tabs.indexOf(tab)) }
        tab.onGmOpenTab = { id, u, active ->
            val opened = openTabInternal(u, tab.incognito, tab, fromLink = active, makeActive = active)
            if (opened != null) gmTabs[id] = opened
        }
        tab.onGmCloseTab = { id ->
            gmTabs.remove(id)?.let { t ->
                val i = tabs.indexOf(t)
                if (i >= 0) closeTab(i)
            }
        }
        return tab
    }

    /** 「自定义起始页」选了「指定页面」且填了有效网址时返回该网址，否则 null */
    private fun customHomeUrl(): String? {
        if (readHomeMode(context) != HOME_MODE_CUSTOM) return null
        val raw = readHomeCustomUrl(context).trim()
        return if (raw.isEmpty()) null else toUrl(raw)
    }

    /**
     * 点击主页按钮（底栏 / 更多菜单）：按「定制主页 → 自定义起始页」处理。
     * 默认主页：回到内置主页；新建标签页：新建并切到一个新标签页；指定页面：当前标签打开该页面。
     */
    fun goHome(tab: BrowserTab = activeTab) {
        when (readHomeMode(context)) {
            HOME_MODE_NEW_TAB -> newTab(incognito = tab.incognito, opener = tab)
            HOME_MODE_CUSTOM -> {
                val target = customHomeUrl()
                if (target == null) tab.goHome()
                else if (tab.url.trimEnd('/') != target.trimEnd('/')) tab.load(target)
            }
            else -> tab.goHome()
        }
    }

    /**
     * 新建标签页。[opener] 是打开它的标签（默认当前标签）：关闭新标签时会优先回到 opener。
     * 当前已经是同模式的空白标签时，「新建空白标签」直接沿用它，不再叠出一张空卡片。
     */
    fun newTab(
        target: String = "",
        incognito: Boolean = false,
        opener: BrowserTab? = tabs.getOrNull(activeIndex),
        /** 由页面里的链接打开：第一页返回直接关闭并回到 opener，不经过本标签的主页 */
        fromLink: Boolean = false,
    ) {
        openTabInternal(target, incognito, opener, fromLink, makeActive = true)
    }

    /** 下一个新标签是不是 WebView 内核：拓展（WebView 适配器）只看得到 WebView 标签，Gecko 默认时不能替它新建 */
    internal fun newTabUsesWebView(): Boolean =
        !(readBrowserEngine(context) == ENGINE_GECKO && EngineSupport.geckoAvailable())

    /**
     * 拓展 tabs.create：新建一个普通（非无痕）标签，[active] 为 false 时后台打开。
     * 与 [newTab] 的区别：不复用当前空白标签、返回新标签；到上限时返回 null。调用方先确认 [newTabUsesWebView]。
     */
    internal fun openTabForExtension(target: String, active: Boolean, opener: BrowserTab?): BrowserTab? =
        openTabInternal(target, incognito = false, opener = opener, fromLink = false, makeActive = active, reuseBlank = false)

    /** [newTab] 的实现；[makeActive] = false 为后台打开（GM_openInTab({ active: false })），返回新标签（没建成返回 null） */
    private fun openTabInternal(
        target: String,
        incognito: Boolean,
        opener: BrowserTab?,
        fromLink: Boolean,
        makeActive: Boolean,
        /** false = 即使当前标签是同模式的空白标签也照样新建（拓展 tabs.create 要拿到一个确定的新标签） */
        reuseBlank: Boolean = true,
    ): BrowserTab? {
        // 新建空白标签时，若设置了「指定页面」就打开它（无痕标签保持空白）
        val loadTarget = if (target.isEmpty() && !incognito) customHomeUrl().orEmpty() else target
        if (loadTarget.isEmpty() && reuseBlank) {
            val cur = tabs.getOrNull(activeIndex)
            if (cur != null && cur.isBlank && cur.incognito == incognito) return null
        }
        if (tabs.size >= MAX_TABS) {
            android.widget.Toast.makeText(
                context,
                tr("最多同时打开 {0} 个标签页", MAX_TABS),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return null
        }
        // 切走前给当前标签拍一张缩略图
        if (makeActive) tabs.getOrNull(activeIndex)?.captureThumbnailNow()
        val tab = createTab(incognito, opener)
        // 翻译 / 拓展选项页 / 外部链接等没有标 fromLink 的入口也一样：有来源标签、有目标地址，返回就该直接回来源
        tab.openedFromLink = (fromLink || makeActive) && opener != null && target.isNotEmpty()
        tabs.add(tab)
        if (makeActive) activate(tabs.lastIndex)
        if (loadTarget.isNotEmpty()) tab.load(loadTarget)
        return tab
    }

    /** GM_openInTab 返回的 id → 标签；仅主线程访问，标签被移除时一并清掉 */
    private val gmTabs = HashMap<String, BrowserTab>()

    /** 站点标识：小写 host，忽略开头的 www. */
    private fun siteKey(url: String): String? {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
        if (host.isNullOrBlank()) return null
        return host.removePrefix("www.")
    }

    /** 是否站点根地址（无路径/查询/片段） */
    private fun isSiteRoot(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return true
        val path = u.path.orEmpty()
        return (path.isEmpty() || path == "/") && u.query.isNullOrEmpty() && u.fragment.isNullOrEmpty()
    }

    /** 找同站点、同无痕状态的已有标签（含回过主页、页面被盖住的标签） */
    private fun findSiteTab(target: String, incognito: Boolean): Int {
        if (!looksLikeUrl(target)) return -1
        val key = siteKey(toUrl(target)) ?: return -1
        return tabs.indexOfFirst { tab ->
            tab.incognito == incognito && siteKey(tab.effectiveUrl) == key
        }
    }

    /**
     * 从主页 / 外部 / 应用内打开网址：
     * 1. 已有同站标签 → 直接切过去显示它原来的页面，不新建、不重新加载；
     *    仅当 [navigateIfDeepLink] 为 true 且目标是带具体路径的深链、且与该标签当前页不同，才在该标签里跳转。
     * 2. 没有同站标签 → 当前是空白/主页就地加载，否则新开标签。
     * 切走后遗留的空白标签会被清掉，避免多出一张空卡片。
     */
    fun openOrReuse(
        target: String,
        incognito: Boolean = activeTab.incognito,
        navigateIfDeepLink: Boolean = false,
        fromStartPage: Boolean = false,
    ) {
        if (target.isBlank()) return
        val idx = findSiteTab(target, incognito)
        if (idx >= 0) {
            val existing = tabs[idx]
            val blank = activeTab.takeIf { it.isBlank && it !== existing && tabs.size > 1 }
            existing.restoreFromHome()
            val url = toUrl(target)
            if (navigateIfDeepLink && !isSiteRoot(url) &&
                !existing.effectiveUrl.equals(url, ignoreCase = true)
            ) {
                existing.load(url)
            }
            if (blank != null) detach(blank)
            activate(tabs.indexOf(existing).coerceAtLeast(0))
            return
        }
        val cur = activeTab
        if (cur.incognito == incognito && cur.url.isEmpty() && (fromStartPage || cur.isBlank)) {
            cur.load(target)
        } else {
            newTab(target, incognito)
        }
    }

    /** 切换当前标签无痕状态（无痕时新开标签，已有无痕则关闭回普通） */
    fun toggleIncognito() {
        if (activeTab.incognito) {
            // 回到普通：关闭当前无痕标签，回到最近用过的普通标签；没有就新建一个
            closeTab(activeIndex)
            val normal = lastUsed(incognito = false)
            if (normal == null) {
                newTab(incognito = false)
            } else {
                activate(tabs.indexOf(normal))
            }
        } else {
            newTab(incognito = true)
        }
    }

    /** 指定模式里最近用过的标签 */
    private fun lastUsed(incognito: Boolean): BrowserTab? {
        for (id in recentIds.asReversed()) {
            val t = tabs.firstOrNull { it.id == id }
            if (t != null && t.incognito == incognito) return t
        }
        return tabs.lastOrNull { it.incognito == incognito }
    }

    fun applyUserAgent(ua: String) {
        userAgent = ua
        tabs.forEach {
            it.customUserAgent = ua
            it.applyUserAgent()
        }
    }

    /** 标签被重排（如 tabs.move）后，把活动索引对齐到 [tab] 的新位置；只改索引，不触发切换副作用 */
    fun realignActive(tab: BrowserTab) {
        val i = tabs.indexOf(tab)
        if (i >= 0) activeIndex = i
    }

    fun select(index: Int) {
        // 切走前给当前标签拍一张缩略图
        if (index != activeIndex) {
            tabs.getOrNull(activeIndex)?.let {
                it.captureThumbnailNow()
                // 手动切走后，关闭它就按最近使用顺序回退，不再强行跳回当初的来源标签
                it.openerId = -1
            }
        }
        activate(index)
    }

    /** 从列表里摘掉一个标签（不负责挑选下一个当前标签） */
    private fun detach(tab: BrowserTab) {
        tabs.remove(tab)
        gmTabs.values.removeAll { it === tab }
        recentIds.remove(tab.id)
        // 以它为 opener 的标签改挂到它的 opener 上，层级链不断
        tabs.forEach { if (it.openerId == tab.id) it.openerId = tab.openerId }
        tab.close()
    }

    /**
     * 关闭当前标签后回到哪一个：
     * 1. opener 链（打开它的标签，其次是打开 opener 的标签……）；
     * 2. 最近用过的标签；
     * 3. 列表里相邻的标签。
     * 候选里优先同模式（无痕 / 普通）的，避免关掉无痕页莫名落到普通页。
     */
    private fun pickSuccessor(closing: BrowserTab): BrowserTab? {
        val others = tabs.filter { it !== closing }
        if (others.isEmpty()) return null
        val candidates = mutableListOf<BrowserTab>()
        val seen = HashSet<Int>()
        var oid = closing.openerId
        while (oid != -1 && seen.add(oid)) {
            val parent = others.firstOrNull { it.id == oid } ?: break
            candidates.add(parent)
            oid = parent.openerId
        }
        for (id in recentIds.asReversed()) {
            others.firstOrNull { it.id == id }?.let { candidates.add(it) }
        }
        val i = tabs.indexOf(closing)
        tabs.getOrNull(i + 1)?.let { candidates.add(it) }
        tabs.getOrNull(i - 1)?.let { candidates.add(it) }
        candidates.addAll(others)
        return candidates.firstOrNull { it.incognito == closing.incognito } ?: candidates.first()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val closing = tabs[index]
        val activeBefore = tabs[activeIndex.coerceIn(0, tabs.lastIndex)]
        val wasActive = activeBefore === closing
        val successor = if (wasActive) pickSuccessor(closing) else activeBefore
        detach(closing)
        if (tabs.isEmpty()) {
            // 关掉的是最后一个标签：新建一个（保持无痕 / 普通模式不变）；普通标签会按「自定义起始页」打开指定页面
            newTab(incognito = closing.incognito)
            return
        }
        // 关掉的是后台标签时当前标签不能变；关掉当前标签才按层级回退
        val target = successor?.let { tabs.indexOf(it) } ?: -1
        if (wasActive) {
            activate(if (target >= 0) target else index.coerceAtMost(tabs.lastIndex))
        } else if (target >= 0) {
            activeIndex = target
        }
    }


    /**
     * 清空某一栏的标签：[incognito] 为 true 清无痕栏，false 清普通栏，另一栏不受影响。
     * 当前标签被清掉时：清无痕栏且还有普通标签 → 回到最近用过的普通标签；
     * 否则在被清空的那一栏新建一个空白标签。
     */
    fun closeAllOf(incognito: Boolean) {
        val removed = tabs.filter { it.incognito == incognito }
        if (removed.isEmpty()) return
        val keep = activeTab.takeIf { it.incognito != incognito }
        val fallback = if (incognito) lastUsed(incognito = false) else null
        removed.forEach { it.close() }
        tabs.removeAll(removed)
        recentIds.removeAll { id -> tabs.none { it.id == id } }
        when {
            keep != null -> activeIndex = tabs.indexOf(keep).coerceAtLeast(0)
            fallback != null && tabs.contains(fallback) -> activate(tabs.indexOf(fallback))
            else -> newTab(incognito = incognito)
        }
    }


    companion object {
        const val MAX_TABS = 99

        /** 后台引擎回收：检查周期 / 闲置多久必收 / 活着的引擎上限 / 内存吃紧时的上限 */
        private const val RECLAIM_INTERVAL_MS = 60_000L
        private const val IDLE_RECLAIM_MS = 20 * 60_000L
        private const val MAX_LIVE_ENGINES = 6
        private const val TRIM_LIVE_ENGINES = 2

        /** 分批恢复标签：首帧后多久开始 / 每一片最多占主线程多久 / 片与片的间隔（让绘制和触摸插进来） */
        private const val RESTORE_FIRST_DELAY_MS = 60L
        private const val RESTORE_SLICE_MS = 8L
        private const val RESTORE_GAP_MS = 16L

        /** 本进程是否已经建过 BrowserState（用来区分冷启动和界面重建） */
        @Volatile
        private var instanceCreated = false

        /** 最近创建的实例：退到后台时由 MainActivity 通过它把标签列表立刻落盘。 */
        @Volatile
        var current: BrowserState? = null
            private set

        /** 退到后台时调用：不等延迟，立刻保存标签列表。主线程。 */
        fun flushCurrent() {
            val state = current ?: return
            state.saveTabs()
            state.persistSessionStates()
            state.reclaimEngines()
        }

        /** 应用退到后台：暂停所有标签里正在播放的媒体。主线程。 */
        fun pauseMediaCurrent() {
            current?.tabs?.forEach { it.pauseMedia() }
        }

        /** 系统内存吃紧：收紧后台引擎数量。主线程。 */
        fun trimCurrent() {
            current?.reclaimEngines(aggressive = true)
        }
    }
}
