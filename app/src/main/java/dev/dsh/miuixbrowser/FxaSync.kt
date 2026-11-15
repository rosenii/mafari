package dev.dsh.miuixbrowser

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
    val AUTO_LABELS = listOf("手动", "1 小时", "6 小时", "12 小时", "每天", "每周")
    private val AUTO_HOURS = longArrayOf(0, 1, 6, 12, 24, 168)
    private const val PREFS = "mafari_fxa_sync"
    private const val K_AUTO = "auto_mode"
    private const val K_LAST_OK = "last_success"
    private const val AUTO_RETRY_GAP_MS = 10 * 60_000L

    var autoMode: Int by mutableIntStateOf(0)
        private set
    /** 上次成功同步的时间（毫秒）；从未成功 = 0。 */
    var lastSuccess: Long by mutableLongStateOf(0L)
        private set
    @Volatile private var lastAutoAttempt = 0L

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun updateAutoMode(index: Int) {
        autoMode = index.coerceIn(0, AUTO_LABELS.lastIndex)
        prefs().edit().putInt(K_AUTO, autoMode).apply()
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
    enum class Category(val label: String) { Bookmarks("书签"), History("历史记录"), Tabs("其他设备的标签页"), Passwords("密码") }

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
        autoMode = prefs().getInt(K_AUTO, 0).coerceIn(0, AUTO_LABELS.lastIndex)
        lastSuccess = prefs().getLong(K_LAST_OK, 0L)
        FxaApi.start(appContext, listener)
        // 自动同步：每分钟看一眼是否到点（登录状态、同步中、失败后 10 分钟内都不会重复触发）
        scope.launch {
            kotlinx.coroutines.delay(5_000)
            while (true) {
                if (autoDue()) {
                    lastAutoAttempt = System.currentTimeMillis()
                    syncNow()
                }
                kotlinx.coroutines.delay(60_000)
            }
        }
        initJob = scope.launch {
            runCatching { FxaApi.init() }.onFailure { initError = it.message ?: it.javaClass.simpleName; ui = FxaUi.SignedOut }
        }
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
            val reason = error ?: FxaApi.httpInitError?.let { "网络组件初始化失败：$it" } ?: initError
            lastMessage = "无法获取登录地址" + (reason?.let { "：$it" } ?: "，请检查网络后重试")
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
                .onFailure { lastMessage = "登录失败：${it.message}"; ui = FxaUi.SignedOut }
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
        if (ui !is FxaUi.SignedIn) return "未登录"
        if (syncing) return "正在同步中，请稍候"
        val w = kotlinx.coroutines.CompletableDeferred<String>()
        waiter = w
        syncNow()
        val r = withTimeoutOrNull(90_000) { w.await() }
        if (r == null) waiter = null
        return r ?: "同步超时，请稍后重试"
    }

    private fun syncOnce() {
        scope.launch {
            runCatching { localTabs?.invoke()?.let { FxaApi.storeLocalTabs(it) } }
            runCatching { FxaApi.syncNow() }
        }
    }

    fun logout() { scope.launch { runCatching { FxaApi.logout() } } }

    /** 刷新设备列表、拉取收到的标签、读取其他设备的标签。 */
    fun refreshDevices() {
        scope.launch {
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
                HistoryDb.get(appContext)?.let { putCount(Category.Passwords, PasswordDb.count(it, syncedOnly = true)) }
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
        val db = HistoryDb.get(appContext) ?: return@withContext ListPage(emptyList(), 0)
        ListPage(
            PasswordDb.search(db, query, limit, syncedOnly = true)
                .map { SyncedItem(it.origin.substringAfter("://"), it.username.ifBlank { "（无用户名）" }, null) },
            PasswordDb.count(db, query, syncedOnly = true),
        )
    }

    /** 发送标签页并返回是否成功（供分享对话框 toast）。 */
    suspend fun sendTabResult(device: PDevice, title: String, url: String): Boolean {
        val ok = runCatching { FxaApi.sendTab(device.id, title, url) }.getOrDefault(false)
        lastMessage = if (ok) "已发送到 ${device.name}" else "发送失败"
        return ok
    }

    fun sendTab(device: PDevice, title: String, url: String) {
        scope.launch {
            val ok = runCatching { FxaApi.sendTab(device.id, title, url) }.getOrDefault(false)
            lastMessage = if (ok) "已发送到 ${device.name}" else "发送失败"
        }
    }

    private val listener = object : FxaApi.Listener {
        override fun onReady(signedIn: Boolean) {
            if (!signedIn) ui = FxaUi.SignedOut else if (ui is FxaUi.Loading) ui = FxaUi.SignedIn("", null)
        }
        override fun onAuthenticated() { ui = FxaUi.SignedIn("", null); syncNow() }
        override fun onProfile(email: String, name: String?, avatarUrl: String?) { ui = FxaUi.SignedIn(email, name, avatarUrl) }
        override fun onLoggedOut() {
            ui = FxaUi.SignedOut
            synced = emptyMap()
            lastSuccess = 0L
            runCatching { prefs().edit().remove(K_LAST_OK).apply() }
            scope.launch { runCatching { FxaPasswordBridge.onLoggedOut(appContext) } }
        }
        override fun onProblem() { ui = FxaUi.NeedsReauth((ui as? FxaUi.SignedIn)?.email) }
        override fun onSyncStarted() { syncing = true }
        override fun onSyncIdle() {
            syncing = false
            lastMessage = "同步完成"
            lastSuccess = System.currentTimeMillis()
            runCatching { prefs().edit().putLong(K_LAST_OK, lastSuccess).apply() }
            scope.launch {
                // 合并失败不再静默：同步完成但本地合并出错时，如实告诉用户
                val pw = runCatching { FxaPasswordBridge.reconcile(appContext) }
                val failed = listOf(
                    "书签" to runCatching { FxaBookmarkBridge.reconcile(appContext) },
                    "历史记录" to runCatching { FxaHistoryBridge.reconcile(appContext) },
                    "密码" to pw,
                ).filter { it.second.isFailure }
                if (failed.isNotEmpty()) {
                    lastMessage = "同步完成，但" + failed.joinToString("、") { it.first } + "合并失败：" +
                        (failed.first().second.exceptionOrNull()?.message ?: "未知错误")
                }
                val r = pw.getOrNull()
                waiter?.complete(
                    when {
                        pw.isFailure -> "密码同步失败：" + (pw.exceptionOrNull()?.message ?: "未知错误")
                        r == null || (r.pulled == 0 && r.pushed == 0 && r.deletedRemote == 0) -> "密码已是最新"
                        else -> "密码同步完成：" + listOfNotNull(
                            r.pulled.takeIf { it > 0 }?.let { "收到 $it 条" },
                            r.pushed.takeIf { it > 0 }?.let { "发出 $it 条" },
                            r.deletedRemote.takeIf { it > 0 }?.let { "服务器已删除 $it 条" },
                        ).joinToString("，")
                    },
                )
                waiter = null
                refreshDevices()
                refreshSynced()
            }
        }
        override fun onSyncError(message: String?) {
            syncing = false
            lastMessage = "同步失败：$message"
            waiter?.complete("同步失败：${message ?: "未知错误"}")
            waiter = null
        }
        override fun onTabReceived(from: String, title: String, url: String) {
            receivedTabs = receivedTabs + Incoming(from, title, url)
        }
    }
}
