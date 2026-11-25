/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sync.fxa

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.FavoritesStore
import dev.dsh.miuixbrowser.data.PasswordDb
import dev.dsh.miuixbrowser.engine.gecko.await
import dev.dsh.miuixbrowser.ui.ListPage
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Firefox 帐户同步页的 UI 状态。 */
sealed interface FxaUi {
    data object Loading : FxaUi
    data object SignedOut : FxaUi
    data object Authenticating : FxaUi
    /** [avatarUrl]：账号设置过自定义头像时的地址（https）；用默认头像 / 还没取到时为 null */
    data class SignedIn(val email: String, val displayName: String?, val avatarUrl: String? = null) : FxaUi
    data class NeedsReauth(val email: String?) : FxaUi
}

/**
 * Firefox 账号登录 + Sync 的状态与流程（不直接引用 Mozilla 库，见 [FxaApi]）。
 *
 * 借用 Fenix（正式版）的 OAuth client_id：redirect_uri 是写死的 https 地址，
 * 在登录页（内嵌 WebView）里截下这个地址的导航、取出 code / state 即可（[interceptRedirect]）。
 * ⚠ CLIENT_ID 需与 Fenix 源码逐字核对；Mozilla 随时可以吊销。
 */
internal object FxaSync {

    const val CLIENT_ID = "a2270f727f45f648"
    const val REDIRECT_URL = "https://accounts.firefox.com/oauth/success/$CLIENT_ID"

    var ui: FxaUi by mutableStateOf(FxaUi.Loading)
        private set
    var syncing: Boolean by mutableStateOf(false)
        private set
    var lastMessage: String? by mutableStateOf(null)
        private set

    /** 自动同步间隔（帐户页「自动同步」）。0 = 手动。应用在前台 / 进程存活期间按间隔检查，到点就同步。 */
    val AUTO_LABELS = listOf(tr("手动"), tr("1 小时"), tr("6 小时"), tr("12 小时"), tr("每天"), tr("每周"))
    private val AUTO_HOURS = longArrayOf(0, 1, 6, 12, 24, 168)
    private const val PREFS = "mafari_fxa_sync"
    private const val K_AUTO = "auto_mode"
    private const val K_LAST_OK = "last_success"
    /** 同步高级选项：绝对双向同步（书签的删除也互相传递）。随 mafari_fxa_sync 一起进备份。 */
    private const val K_ABSOLUTE = "absolute_sync"
    /** 上次已知是登录状态（账号管理器 onReady / 登录成功时写入，退出时清除）：冷启动后台预热据此判断要不要初始化，没登录过的用户零开销 */
    private const val K_SIGNED_IN = "signed_in"
    private const val AUTO_RETRY_GAP_MS = 10 * 60_000L

    var autoMode: Int by mutableIntStateOf(0)
        private set
    /** 上次成功同步的时间（毫秒）；从未成功 = 0。 */
    var lastSuccess: Long by mutableLongStateOf(0L)
        private set
    @Volatile private var lastAutoAttempt = 0L

    /** 「绝对双向同步」：开启后书签的删除（本机 ↔ 云端）也会互相传递；默认关闭。 */
    var absoluteSync: Boolean by mutableStateOf(false)
        private set

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun updateAutoMode(index: Int) {
        autoMode = index.coerceIn(0, AUTO_LABELS.lastIndex)
        prefs().edit().putInt(K_AUTO, autoMode).apply()
    }

    /** 开 / 关都清掉删除基线：下一轮同步只重新记录「两边都有的书签」，不会因为旧基线误删任何东西。 */
    fun updateAbsoluteSync(on: Boolean) {
        absoluteSync = on
        prefs().edit().putBoolean(K_ABSOLUTE, on).apply()
        FxaBookmarkBridge.resetBaseline(appContext)
        FxaHistoryBridge.resetBaseline(appContext)
    }

    // ---------------------------------------------------------------- 标签页同步（只同步标签页引擎）
    private const val TABS_POLL_MS = 3 * 60_000L
    private const val TABS_PUSH_DEBOUNCE_MS = 3_000L
    private const val TABS_MIN_GAP_MS = 15_000L
    @Volatile private var lastTabsSync = 0L
    /** 本轮同步只含标签页引擎：[onSyncIdle] / [onSyncError] 据此跳过书签 / 历史 / 密码的合并与「上次同步」时间 */
    @Volatile private var tabsOnly = false
    /** 完整同步进行中又有标签变化：同步结束后补一次 */
    @Volatile private var tabsDirty = false
    private var tabsPushJob: Job? = null
    @Volatile private var lastTabsSig: Int? = null

    private suspend fun isForeground(): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }.getOrDefault(false)
    }

    /**
     * 本机标签列表变了（开 / 关 / 换页 / 清空）：稍等一下（合并连续操作）就把标签页同步到云端，
     * 这样本地关掉的标签页会从云端「此设备」的列表里消失。没登录时什么都不做，也不会触发初始化。
     */
    fun onLocalTabsChanged() {
        if (ui !is FxaUi.SignedIn) return
        val sig = runCatching { localTabs?.invoke()?.filter { it.second.startsWith("http", true) }.hashCode() }.getOrNull() ?: return
        if (sig == lastTabsSig) return
        lastTabsSig = sig
        tabsPushJob?.cancel()
        tabsPushJob = scope.launch {
            val gap = TABS_MIN_GAP_MS - (System.currentTimeMillis() - lastTabsSync)
            kotlinx.coroutines.delay(maxOf(TABS_PUSH_DEBOUNCE_MS, gap))
            syncTabs()
        }
    }

    /** 上传本机标签并拉取其他设备的标签（只同步标签页引擎）；完成后刷新「其他设备的标签页」。 */
    fun syncTabs() {
        scope.launch {
            if (ui !is FxaUi.SignedIn) return@launch
            if (syncing) { tabsDirty = true; return@launch } // 完整同步还在跑：结束后再补一次
            withTimeoutOrNull(8_000) { initJob?.join() }
            lastTabsSync = System.currentTimeMillis()
            runCatching { localTabs?.invoke()?.let { FxaApi.storeLocalTabs(it) } }
            tabsOnly = true
            runCatching { FxaApi.syncTabsOnly() }.onFailure { tabsOnly = false }
        }
    }

    /** 只重新读取本地同步库里其他设备的标签页（不联网、不触发同步）；标签列表展开「其他设备的标签页」时用。 */
    fun reloadRemoteTabs() {
        scope.launch {
            if (ui !is FxaUi.SignedIn) return@launch
            withTimeoutOrNull(8_000) { initJob?.join() }
            refreshRemoteTabs()
        }
    }

    private suspend fun refreshRemoteTabs() {
        runCatching { remoteDevices = FxaApi.remoteTabs() }
        runCatching { putCount(Category.Tabs, remoteDevices.sumOf { it.tabs.size }) }
    }

    private fun autoDue(): Boolean {
        val hours = AUTO_HOURS.getOrElse(autoMode) { 0 }
        if (hours <= 0 || ui !is FxaUi.SignedIn || syncing) return false
        val now = System.currentTimeMillis()
        return now - lastSuccess >= hours * 3_600_000L && now - lastAutoAttempt >= AUTO_RETRY_GAP_MS
    }

    class Incoming(val from: String, val title: String, val url: String)
    var receivedTabs by mutableStateOf<List<Incoming>>(emptyList())
        private set
    var devices by mutableStateOf<List<PDevice>>(emptyList())
        private set
    var remoteDevices by mutableStateOf<List<RemoteDevice>>(emptyList())
        private set
    fun dismissReceived(t: Incoming) { receivedTabs = receivedTabs - t }

    /** 「已同步的内容」页的分类。 */
    enum class Category(val label: String) { Bookmarks(tr("书签")), History(tr("历史记录")), Tabs(tr("其他设备的标签页")), Passwords(tr("密码")) }

    /** [title] 主文字；[summary] 次级文字（密码只给用户名，绝不含密码）；[url] 非 null 时可点开。 */
    class SyncedItem(val title: String, val summary: String?, val url: String?)

    /** 某一类已同步内容的总条数。条目本身不常驻内存：打开列表 sheet 时才通过 [loadItems] / [passwordsPage] 读取。 */
    class SyncedGroup(val count: Int)

    var synced by mutableStateOf<Map<Category, SyncedGroup>>(emptyMap())
        private set
    private val syncedLock = Any()

    /** 登录完成（回调被截获）后回调，主线程。 */
    @Volatile var onRedirectHandled: (() -> Unit)? = null

    /** 由 BrowserScreen 提供：本机非无痕标签 (标题, 网址)。 */
    @Volatile var localTabs: (() -> List<Pair<String, String>>)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private var inited = false
    private var initJob: Job? = null
    @Volatile private var initError: String? = null

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        appContext = context.applicationContext
        // 冷启动：主线程这里只做赋值。读偏好、Rust 组件 / NSS 初始化、各 Storage 与账号管理器的构造都放到下面的后台协程，
        // 此前它们同步跑在 MainActivity.onCreate 里，是 Firefox 同步拖慢冷启动的原因。
        // 自动同步：每分钟看一眼是否到点（登录状态、同步中、失败后 10 分钟内都不会重复触发）
        scope.launch {
            kotlinx.coroutines.delay(5_000)
            while (true) {
                if (autoDue()) {
                    lastAutoAttempt = System.currentTimeMillis()
                    syncNow()
                } else if (ui is FxaUi.SignedIn && System.currentTimeMillis() - lastTabsSync >= TABS_POLL_MS && isForeground()) {
                    // 其他设备关掉的标签页要在这里消失：前台时每隔几分钟只同步一次「标签页」（与自动同步间隔无关，手动模式也生效）
                    syncTabs()
                }
                kotlinx.coroutines.delay(60_000)
            }
        }
        initJob = scope.launch {
            // 偏好先读（很快），界面上的「自动同步」/「上次同步」随即就位
            runCatching {
                autoMode = prefs().getInt(K_AUTO, 0).coerceIn(0, AUTO_LABELS.lastIndex)
                lastSuccess = prefs().getLong(K_LAST_OK, 0L)
                absoluteSync = prefs().getBoolean(K_ABSOLUTE, false)
            }
            // start 失败（原来会直接让 onCreate 崩）现在落到 SignedOut 并记下原因；ui 在 onReady 前保持 Loading
            runCatching { FxaApi.start(appContext, listener); FxaApi.init() }
                .onFailure { initError = it.message ?: it.javaClass.simpleName; ui = FxaUi.SignedOut }
        }
    }

    /**
     * 冷启动后的后台预热：只在「上次已知登录」时才初始化，没登录过 Firefox 帐户的用户不付出任何代价。
     * 整个过程在 IO 线程：读偏好 → 需要时 [init]（Rust 组件 / NSS 加载也在后台）。可重复调用（init 幂等）。
     * 调用时机见 MainActivity（首帧之后再延迟一会儿），以及分享 / 备份 / 密码页等需要登录状态的界面。
     */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        scope.launch { initIfSignedIn(app) }
    }

    /** 同步版（调用方自己保证不在主线程）：上次是登录状态就 [init]。 */
    fun initIfSignedIn(context: Context) {
        if (inited) return
        val app = context.applicationContext
        // 老版本升级上来的用户还没有这个标记：有过成功同步记录（退出登录时会清掉）就当作已登录，初始化后 onReady 会把标记写准
        val wasSignedIn = runCatching {
            val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.getBoolean(K_SIGNED_IN, sp.contains(K_LAST_OK))
        }.getOrDefault(false)
        if (wasSignedIn) init(app)
    }

    private fun rememberSignedIn(value: Boolean) {
        runCatching { prefs().edit().putBoolean(K_SIGNED_IN, value).apply() }
    }

    /** 开始登录：返回登录页地址（在内嵌 WebView 里打开）。 */
    suspend fun beginLogin(): String? {
        ui = FxaUi.Authenticating
        // 账号管理器还没初始化完时调用会直接失败：先等它（最多 8 秒）
        withTimeoutOrNull(8_000) { initJob?.join() }
        var error: String? = null
        val url = try {
            FxaApi.beginLogin()
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            null
        }
        if (url == null) {
            ui = FxaUi.SignedOut
            val reason = initError ?: error ?: FxaApi.httpInitError?.let { tr("网络组件初始化失败：{0}", it) } ?: initError
            lastMessage = tr("无法获取登录地址") + (reason?.let { tr("：{0}", it) } ?: tr("，请检查网络后重试"))
        }
        return url
    }

    fun cancelLogin() { if (ui is FxaUi.Authenticating) ui = FxaUi.SignedOut }

    @Volatile private var handledState: String? = null

    /** 命中重定向地址就取出 code/state 完成登录，返回 true 表示已消费。 */
    fun interceptRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(REDIRECT_URL)) return false
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code == null || state == null) return false
        // 同一个回调可能被 shouldOverrideUrlLoading / onPageStarted 各截一次：只完成一次登录，否则第二次失败会把状态打回「未登录」造成界面来回闪
        if (state == handledState) return true
        handledState = state
        scope.launch {
            runCatching { FxaApi.finishLogin(code, state) }
                .onFailure { lastMessage = tr("登录失败：{0}", it.message); ui = FxaUi.SignedOut }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { onRedirectHandled?.invoke() }
        return true
    }

    /** 连续由「合并后还有东西要推」触发的续同步次数；用户 / 登录触发的 [syncNow] 会清零 */
    @Volatile private var followUps = 0
    private const val MAX_FOLLOW_UPS = 30

    /** 合并后还有数据没推完时再同步一轮；设上限，万一哪里又有推不完的数据也不会无限循环。 */
    fun syncFollowUp() {
        if (++followUps > MAX_FOLLOW_UPS) return
        syncOnce()
    }

    fun syncNow() {
        followUps = 0
        syncOnce()
    }

    /** 等待本轮同步结束的人（[syncAndReport]）；同步结束 / 失败时给它结果文案。 */
    @Volatile private var waiter: kotlinx.coroutines.CompletableDeferred<String>? = null

    /**
     * 立即同步，等结果出来再返回给界面显示（toast）。同步由 Firefox 引擎完成，随后对账密码（[FxaPasswordBridge]）。
     * 为了不依赖库里「只同步某个引擎」的接口签名，这里同步的是全部类别，文案只汇报密码。
     */
    suspend fun syncAndReport(): String {
        if (ui !is FxaUi.SignedIn) return tr("未登录")
        if (syncing) return tr("正在同步中，请稍候")
        val w = kotlinx.coroutines.CompletableDeferred<String>()
        waiter = w
        syncNow()
        val r = withTimeoutOrNull(90_000) { w.await() }
        if (r == null) waiter = null
        return r ?: tr("同步超时，请稍后重试")
    }

    private fun syncOnce() {
        tabsOnly = false // 完整同步优先：别让上一轮「只同步标签页」的标记把这轮的书签 / 历史 / 密码合并跳过
        scope.launch {
            runCatching { localTabs?.invoke()?.let { FxaApi.storeLocalTabs(it) } }
            runCatching { FxaApi.syncNow() }
        }
    }

    fun logout() { scope.launch { runCatching { FxaApi.logout() } } }

    /** 刷新设备列表、拉取收到的标签、读取其他设备的标签。 */
    fun refreshDevices() {
        scope.launch {
            // 刚由 warmUp / init 触发时账号管理器可能还没就绪：先等（最多 8 秒），免得 lateinit 未初始化被吞成空列表
            withTimeoutOrNull(8_000) { initJob?.join() }
            runCatching { devices = FxaApi.devices() }
            runCatching { remoteDevices = FxaApi.remoteTabs() }
        }
    }

    private fun putCount(cat: Category, n: Int) {
        synchronized(syncedLock) { synced = synced + (cat to SyncedGroup(n)) }
    }

    /** 本地「从 Firefox 拉下来」的书签（读的是书签库在内存里的当前视图，在主线程取）。 */
    private suspend fun pulledBookmarks() = withContext(Dispatchers.Main) {
        FavoritesStore.current?.items.orEmpty()
            .filter { it.group == FxaBookmarkBridge.PULL_ROOT || it.group.startsWith(FxaBookmarkBridge.PULL_ROOT + "/") }
    }

    /** 其他设备的访问，按网址去重、最近的在前。要读全量历史，开销最大，只在需要时调用。 */
    private suspend fun remoteHistory(): List<PVisit> =
        FxaApi.remoteVisits(0L)
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .groupBy { it.url }.map { (_, v) -> v.maxByOrNull { it.time }!! }
            .sortedByDescending { it.time }

    /**
     * 重新统计各类「已同步」内容的**条数**（同步结束、打开页面时调用）。只存条数，不保留条目；
     * 每类算完就立刻更新，便宜的先出来，历史记录（要读全量）最后。
     */
    fun refreshSynced() {
        scope.launch {
            runCatching { putCount(Category.Bookmarks, pulledBookmarks().size) }
            runCatching { putCount(Category.Tabs, FxaApi.remoteTabs().sumOf { it.tabs.size }) }
            runCatching {
                AppDatabase.get(appContext)?.let { putCount(Category.Passwords, PasswordDb.count(it, syncedOnly = true)) }
            }
            runCatching { putCount(Category.History, remoteHistory().size) }
        }
    }

    /** 打开列表 sheet 时读取某一类的全部条目（密码走 [passwordsPage]，在库里分页）。在 IO 线程执行。 */
    suspend fun loadItems(cat: Category): List<SyncedItem> = withContext(Dispatchers.IO) {
        when (cat) {
            Category.Bookmarks -> pulledBookmarks().map {
                SyncedItem(it.name.ifBlank { it.url }, it.group.removePrefix(FxaBookmarkBridge.PULL_ROOT).trim('/').ifEmpty { null }, it.url)
            }
            Category.History -> remoteHistory().map { SyncedItem(it.title.ifBlank { it.url }, it.url, it.url) }
            Category.Tabs -> FxaApi.remoteTabs().flatMap { it.tabs }.map { SyncedItem(it.title.ifBlank { it.url }, it.url, it.url) }
            Category.Passwords -> emptyList()
        }
    }

    /** 已同步的密码：只列站点和用户名，不解密任何密码；搜索和 LIMIT 在数据库里做。 */
    suspend fun passwordsPage(query: String, limit: Int): ListPage<SyncedItem> = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(appContext) ?: return@withContext ListPage(emptyList(), 0)
        ListPage(
            PasswordDb.search(db, query, limit, syncedOnly = true)
                .map { SyncedItem(it.origin.substringAfter("://"), it.username.ifBlank { tr("（无用户名）") }, null) },
            PasswordDb.count(db, query, syncedOnly = true),
        )
    }

    /** 发送标签页并返回是否成功（供分享对话框 toast）。 */
    suspend fun sendTabResult(device: PDevice, title: String, url: String): Boolean {
        val ok = runCatching { FxaApi.sendTab(device.id, title, url) }.getOrDefault(false)
        lastMessage = if (ok) tr("已发送到 {0}", device.name) else tr("发送失败")
        return ok
    }

    /** [onResult] 在后台线程回调，要弹 toast 请自己切主线程。 */
    fun sendTab(device: PDevice, title: String, url: String, onResult: ((Boolean) -> Unit)? = null) {
        scope.launch {
            val ok = runCatching { FxaApi.sendTab(device.id, title, url) }.getOrDefault(false)
            lastMessage = if (ok) tr("已发送到 {0}", device.name) else tr("发送失败")
            onResult?.invoke(ok)
        }
    }

    /** 等账号管理器就绪后读取可接收标签页的设备；未登录返回 null（给从系统分享面板冷启动的场景用）。 */
    suspend fun loadDevices(): List<PDevice>? {
        withTimeoutOrNull(8_000) { initJob?.join() }
        withTimeoutOrNull(6_000) { while (ui is FxaUi.Loading) kotlinx.coroutines.delay(100) }
        if (ui !is FxaUi.SignedIn) return null
        return withContext(Dispatchers.IO) { runCatching { FxaApi.devices() }.getOrDefault(emptyList()) }
    }

    private val listener = object : FxaApi.Listener {
        override fun onReady(signedIn: Boolean) {
            rememberSignedIn(signedIn)
            if (!signedIn) ui = FxaUi.SignedOut else if (ui is FxaUi.Loading) ui = FxaUi.SignedIn("", null)
        }
        override fun onAuthenticated() { rememberSignedIn(true); ui = FxaUi.SignedIn("", null); syncNow() }
        override fun onProfile(email: String, name: String?, avatarUrl: String?) { ui = FxaUi.SignedIn(email, name, avatarUrl) }
        override fun onLoggedOut() {
            rememberSignedIn(false)
            ui = FxaUi.SignedOut
            synced = emptyMap()
            lastSuccess = 0L
            runCatching { prefs().edit().remove(K_LAST_OK).apply() }
            FxaBookmarkBridge.resetBaseline(appContext)
            FxaHistoryBridge.resetBaseline(appContext)
            scope.launch { runCatching { FxaPasswordBridge.onLoggedOut(appContext) } }
        }
        override fun onProblem() { ui = FxaUi.NeedsReauth((ui as? FxaUi.SignedIn)?.email) }
        override fun onSyncStarted() { if (!tabsOnly) syncing = true }
        override fun onSyncIdle() {
            if (tabsOnly) {
                // 只同步了标签页：刷新列表即可，不动「上次同步」时间 / 提示语，也不跑书签 / 历史 / 密码的合并
                tabsOnly = false
                scope.launch { refreshRemoteTabs() }
                return
            }
            syncing = false
            lastMessage = tr("同步完成")
            lastSuccess = System.currentTimeMillis()
            runCatching { prefs().edit().putLong(K_LAST_OK, lastSuccess).apply() }
            scope.launch {
                // 合并失败不再静默：同步完成但本地合并出错时，如实告诉用户
                val pw = runCatching { FxaPasswordBridge.reconcile(appContext) }
                val failed = listOf(
                    tr("书签") to runCatching { FxaBookmarkBridge.reconcile(appContext) },
                    tr("历史记录") to runCatching { FxaHistoryBridge.reconcile(appContext) },
                    tr("密码") to pw,
                ).filter { it.second.isFailure }
                if (failed.isNotEmpty()) {
                    lastMessage = tr("同步完成，但") + failed.joinToString(tr("、")) { it.first } + tr("合并失败：") +
                        (failed.first().second.exceptionOrNull()?.message ?: tr("未知错误"))
                }
                val r = pw.getOrNull()
                waiter?.complete(
                    when {
                        pw.isFailure -> tr("密码同步失败：") + (pw.exceptionOrNull()?.message ?: tr("未知错误"))
                        r == null || (r.pulled == 0 && r.pushed == 0 && r.deletedRemote == 0) -> tr("密码已是最新")
                        else -> tr("密码同步完成：") + listOfNotNull(
                            r.pulled.takeIf { it > 0 }?.let { tr("收到 {0} 条", it) },
                            r.pushed.takeIf { it > 0 }?.let { tr("发出 {0} 条", it) },
                            r.deletedRemote.takeIf { it > 0 }?.let { tr("服务器已删除 {0} 条", it) },
                        ).joinToString("，")
                    },
                )
                waiter = null
                refreshDevices()
                refreshSynced()
                if (tabsDirty) { tabsDirty = false; syncTabs() }
            }
        }
        override fun onSyncError(message: String?) {
            if (tabsOnly) { tabsOnly = false; return } // 标签页的后台同步失败不打扰：下次变化 / 定时再试
            syncing = false
            lastMessage = tr("同步失败：{0}", message)
            waiter?.complete(tr("同步失败：{0}", message ?: tr("未知错误")))
            waiter = null
        }
        override fun onTabReceived(from: String, title: String, url: String) {
            receivedTabs = receivedTabs + Incoming(from, title, url)
        }
    }
}
