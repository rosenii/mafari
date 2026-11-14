package dev.dsh.miuixbrowser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Firefox 账户同步页的 UI 状态。 */
sealed interface FxaUi {
    data object Loading : FxaUi
    data object SignedOut : FxaUi
    data object Authenticating : FxaUi
    data class SignedIn(val email: String, val displayName: String?) : FxaUi
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

    class Incoming(val from: String, val title: String, val url: String)
    var receivedTabs by mutableStateOf<List<Incoming>>(emptyList())
        private set
    var devices by mutableStateOf<List<PDevice>>(emptyList())
        private set
    var remoteDevices by mutableStateOf<List<RemoteDevice>>(emptyList())
        private set
    fun dismissReceived(t: Incoming) { receivedTabs = receivedTabs - t }

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
        FxaApi.start(appContext, listener)
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

    /** 命中重定向地址就取出 code/state 完成登录，返回 true 表示已消费。 */
    fun interceptRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(REDIRECT_URL)) return false
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code == null || state == null) return false
        scope.launch {
            runCatching { FxaApi.finishLogin(code, state) }
                .onFailure { lastMessage = "登录失败：${it.message}"; ui = FxaUi.SignedOut }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { onRedirectHandled?.invoke() }
        return true
    }

    fun syncNow() {
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
        override fun onProfile(email: String, name: String?) { ui = FxaUi.SignedIn(email, name) }
        override fun onLoggedOut() { ui = FxaUi.SignedOut }
        override fun onProblem() { ui = FxaUi.NeedsReauth((ui as? FxaUi.SignedIn)?.email) }
        override fun onSyncStarted() { syncing = true }
        override fun onSyncIdle() {
            syncing = false
            lastMessage = "同步完成"
            scope.launch {
                runCatching { FxaBookmarkBridge.reconcile(appContext) }
                runCatching { FxaHistoryBridge.reconcile(appContext) }
                refreshDevices()
            }
        }
        override fun onSyncError(message: String?) { syncing = false; lastMessage = "同步失败：$message" }
        override fun onTabReceived(from: String, title: String, url: String) {
            receivedTabs = receivedTabs + Incoming(from, title, url)
        }
    }
}
