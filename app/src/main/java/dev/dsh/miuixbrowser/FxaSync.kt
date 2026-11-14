package dev.dsh.miuixbrowser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.appservices.fxaclient.FxaServer
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.concept.sync.AccountEvent
import mozilla.components.concept.sync.AccountEventsObserver
import mozilla.components.concept.sync.Device
import mozilla.components.concept.sync.DeviceCommandIncoming
import mozilla.components.concept.sync.DeviceCommandOutgoing
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.DeviceCapability
import mozilla.components.concept.sync.DeviceType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.concept.sync.Profile
import mozilla.components.service.fxa.DeviceConfig
import mozilla.components.service.fxa.ServerConfig
import mozilla.components.service.fxa.SyncConfig
import mozilla.components.service.fxa.SyncEngine
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.service.fxa.manager.GlobalSyncableStoreProvider
import mozilla.components.service.fxa.sync.SyncReason
import mozilla.components.service.fxa.sync.SyncStatusObserver

/** 登录页面板的 UI 状态。 */
sealed interface FxaUi {
    data object Loading : FxaUi
    data object SignedOut : FxaUi
    data object Authenticating : FxaUi
    data class SignedIn(val email: String, val displayName: String?) : FxaUi
    data class NeedsReauth(val email: String?) : FxaUi
}

/**
 * Firefox 账号（Mozilla 账号）登录 + Sync。
 *
 * 借用 Fenix（Firefox for Android 正式版）的 OAuth client_id：redirect_uri 是 client_id 注册时写死的
 * `https://accounts.firefox.com/oauth/success/<client_id>`，这是个普通 https 地址，所以不需要自定义 scheme，
 * 只要在标签页里把这个地址的导航截下来、取出 code / state 即可（见 [interceptRedirect]）。
 *
 * ⚠ CLIENT_ID 必须和 Fenix 源码里的 FxaConfig / Config.kt 逐字核对后再用；Mozilla 随时可以吊销。
 * ⚠ 下面对 android-components 的调用按近期版本（≥130）写成，升级或锁版本后如有签名差异，
 *    以编译错误为准微调（已在 TODO(API) 处标出最可能有出入的几个调用）。
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

    /** 重定向被截获并完成登录后回调（主线程），用于关掉登录标签页。 */
    @Volatile var onRedirectHandled: (() -> Unit)? = null

    /** 由 BrowserScreen 提供：本机非无痕标签 (标题, 网址)。 */
    @Volatile var localTabs: (() -> List<Pair<String, String>>)? = null

    class Incoming(val from: String, val title: String, val url: String)
    var receivedTabs by mutableStateOf<List<Incoming>>(emptyList())
        private set
    var devices by mutableStateOf<List<Device>>(emptyList())
        private set
    var remoteDevices by mutableStateOf<List<FxaTabsBridge.RemoteDevice>>(emptyList())
        private set
    fun dismissReceived(t: Incoming) { receivedTabs = receivedTabs - t }

    private lateinit var tabsStorage: RemoteTabsStorage
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var manager: FxaAccountManager? = null
    private lateinit var appContext: Context

    private val syncEngines = setOf(SyncEngine.Bookmarks, SyncEngine.History, SyncEngine.Tabs)

    @Synchronized
    fun init(context: Context) {
        if (manager != null) return
        appContext = context.applicationContext

        // 书签引擎用 Places 的镜像库（与 SQLCipher 的 history.db 分开）
        val bookmarks = lazy { PlacesBookmarksStorage(appContext) }
        val history = lazy { PlacesHistoryStorage(appContext) }
        tabsStorage = RemoteTabsStorage(appContext)
        GlobalSyncableStoreProvider.configureStore(SyncEngine.Bookmarks to bookmarks)
        GlobalSyncableStoreProvider.configureStore(SyncEngine.History to history)
        GlobalSyncableStoreProvider.configureStore(SyncEngine.Tabs to lazy { tabsStorage })

        val m = FxaAccountManager(
            context = appContext,
            serverConfig = ServerConfig(FxaServer.Release, CLIENT_ID, REDIRECT_URL),
            deviceConfig = DeviceConfig(
                name = "Mafari (${android.os.Build.MODEL})",
                type = DeviceType.MOBILE,
                capabilities = setOf(DeviceCapability.SEND_TAB),
            ),
            syncConfig = SyncConfig(syncEngines), // 不设周期同步：由用户手动 / 登录后触发
            applicationScopes = emptySet(),
        )
        manager = m
        m.register(accountObserver)
        m.registerForSyncEvents(syncObserver, ProcessLifecycleOwner.get(), false)
        m.registerForAccountEvents(eventsObserver, ProcessLifecycleOwner.get(), false)
        scope.launch { runCatching { m.start() } /* TODO(API): 旧版叫 initAsync().await() */ }
    }

    /** 开始登录：返回要在标签页里打开的 Mozilla 账号登录页地址。 */
    suspend fun beginLogin(): String? {
        val m = manager ?: return null
        ui = FxaUi.Authenticating
        val url = runCatching { m.beginAuthentication(MafariEntryPoint) }.getOrNull() // TODO(API): 旧版无 entryPoint 参数
        if (url == null) { ui = FxaUi.SignedOut; lastMessage = "无法获取登录地址" }
        return url
    }

    fun cancelLogin() { if (ui is FxaUi.Authenticating) ui = FxaUi.SignedOut }

    /**
     * 在每个标签的导航回调里调用。命中重定向地址就取出 code/state 交给账号管理器，返回 true 表示「已消费，别再加载」。
     */
    fun interceptRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(REDIRECT_URL)) return false
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val m = manager
        if (code == null || state == null || m == null) return false
        scope.launch {
            runCatching {
                m.finishAuthentication(FxaAuthData(AuthType.Signin, code, state, null)) // TODO(API): 旧版 finishAuthenticationAsync
            }.onFailure { lastMessage = "登录失败：${it.message}"; ui = FxaUi.SignedOut }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { onRedirectHandled?.invoke() }
        return true
    }

    fun syncNow() {
        manager?.let { m ->
            scope.launch {
                runCatching { localTabs?.invoke()?.let { FxaTabsBridge.storeLocal(tabsStorage, it) } }
                runCatching { m.syncNow(SyncReason.User) }
            }
        }
    }

    /** 刷新设备列表 + 拉取别的设备发来的标签 + 读取远端标签（进入页面时调用）。 */
    fun refreshDevices() {
        val m = manager ?: return
        scope.launch {
            runCatching {
                val c = m.authenticatedAccount()?.deviceConstellation() ?: return@runCatching
                c.refreshDevices()
                devices = c.state()?.otherDevices.orEmpty().filter { DeviceCapability.SEND_TAB in it.capabilities }
                c.pollForCommands()
                remoteDevices = FxaTabsBridge.remote(tabsStorage)
            }
        }
    }

    fun sendTab(device: Device, title: String, url: String) {
        val m = manager ?: return
        scope.launch {
            val ok = runCatching {
                m.authenticatedAccount()?.deviceConstellation()
                    ?.sendCommandToDevice(device.id, DeviceCommandOutgoing.SendTab(title, url)) == true
            }.getOrDefault(false)
            lastMessage = if (ok) "已发送到 ${device.displayName}" else "发送失败"
        }
    }

    private val eventsObserver = object : AccountEventsObserver {
        override fun onEvents(events: List<AccountEvent>) {
            val got = events.filterIsInstance<AccountEvent.DeviceCommandIncoming>()
                .map { it.command }.filterIsInstance<DeviceCommandIncoming.TabReceived>()
                .flatMap { cmd -> cmd.entries.map { Incoming(cmd.from?.displayName ?: "其他设备", it.title, it.url) } }
            if (got.isNotEmpty()) receivedTabs = receivedTabs + got
        }
    }

    fun logout() {
        manager?.let { m -> scope.launch { runCatching { m.logout() } } }
    }

    private val accountObserver = object : AccountObserver {
        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
            ui = FxaUi.SignedIn(email = "", displayName = null)
            syncNow()
        }
        override fun onProfileUpdated(profile: Profile) {
            ui = FxaUi.SignedIn(profile.email.orEmpty(), profile.displayName)
        }
        override fun onLoggedOut() { ui = FxaUi.SignedOut }
        override fun onAuthenticationProblems() {
            ui = FxaUi.NeedsReauth((ui as? FxaUi.SignedIn)?.email)
        }
        override fun onReady(authenticatedAccount: OAuthAccount?) {
            if (authenticatedAccount == null) ui = FxaUi.SignedOut
            else if (ui is FxaUi.Loading) ui = FxaUi.SignedIn("", null)
        }
    }

    private val syncObserver = object : SyncStatusObserver {
        override fun onStarted() { syncing = true }
        override fun onIdle() {
            syncing = false
            lastMessage = "同步完成"
            scope.launch {
                runCatching { FxaBookmarkBridge.reconcile(appContext) }
                runCatching { FxaHistoryBridge.reconcile(appContext) }
                refreshDevices()
            }
        }
        override fun onError(error: Exception?) { syncing = false; lastMessage = "同步失败：${error?.message}" }
    }
}

/** FxA 要求的入口标记（只用于统计归因，不发遥测）。 */
private object MafariEntryPoint : mozilla.components.concept.sync.FxAEntryPoint {
    override val entryName: String = "mafari"
}
