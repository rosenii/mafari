package dev.dsh.miuixbrowser

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import mozilla.appservices.fxaclient.FxaServer
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.browser.storage.sync.Tab
import mozilla.components.browser.storage.sync.TabEntry
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.PageVisit
import mozilla.components.concept.storage.RedirectSource
import mozilla.components.concept.storage.VisitType
// 这些类在新版里换过包：用通配符让编译器自己找
import mozilla.components.concept.sync.*
import mozilla.components.service.fxa.*
import mozilla.components.service.fxa.manager.*
import mozilla.components.service.fxa.sync.*

/** 与 Mozilla 库无关的简单数据类：其余 Fxa* 文件只认这些。 */
internal class PNode(val guid: String, val isFolder: Boolean, val title: String, val url: String?, val children: List<PNode>)
internal class PDevice(val id: String, val name: String)
internal class PVisit(val url: String, val title: String, val time: Long)
internal class RemoteTab(val title: String, val url: String)
internal class RemoteDevice(val id: String, val tabs: List<RemoteTab>)

/**
 * 全工程【唯一】直接调用 Mozilla android-components 的文件。
 * 库升级 / 签名变了，只需要改这一个文件；其余 Fxa* 文件不引用任何 mozilla.* 类型。
 * 真实签名见 CI 的「Dump Firefox Sync API」步骤输出（tools/dump-fxa-api.sh）。
 */
internal object FxaApi {

    interface Listener {
        fun onReady(signedIn: Boolean)
        fun onAuthenticated()
        fun onProfile(email: String, name: String?)
        fun onLoggedOut()
        fun onProblem()
        fun onSyncStarted()
        fun onSyncIdle()
        fun onSyncError(message: String?)
        fun onTabReceived(from: String, title: String, url: String)
    }

    private lateinit var ctx: Context
    private lateinit var manager: FxaAccountManager
    private lateinit var bookmarks: PlacesBookmarksStorage
    private lateinit var history: PlacesHistoryStorage
    private lateinit var tabs: RemoteTabsStorage
    private var started = false

    /** Places 的方法在新版里返回 Result<T>，旧版直接返回 T：统一拆开。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> unwrap(v: Any?): T = (if (v is Result<*>) v.getOrThrow() else v) as T

    @Synchronized
    fun start(context: Context, l: Listener) {
        if (started) return
        started = true
        ctx = context.applicationContext
        bookmarks = PlacesBookmarksStorage(ctx)
        history = PlacesHistoryStorage(ctx)
        tabs = RemoteTabsStorage(ctx)

        GlobalSyncableStoreProvider.configureStore(SyncEngine.Bookmarks to lazy { bookmarks })
        GlobalSyncableStoreProvider.configureStore(SyncEngine.History to lazy { history })
        GlobalSyncableStoreProvider.configureStore(SyncEngine.Tabs to lazy { tabs })

        manager = FxaAccountManager(
            context = ctx,
            serverConfig = ServerConfig(FxaServer.Release, FxaSync.CLIENT_ID, FxaSync.REDIRECT_URL),
            deviceConfig = DeviceConfig(
                name = "Mafari (${android.os.Build.MODEL})",
                type = DeviceType.MOBILE,
                capabilities = setOf(DeviceCapability.SEND_TAB),
            ),
            syncConfig = SyncConfig(setOf(SyncEngine.Bookmarks, SyncEngine.History, SyncEngine.Tabs)),
            applicationScopes = emptySet(),
            crashReporter = null,
        )
        manager.register(object : AccountObserver {
            override fun onAuthenticated(account: OAuthAccount, authType: AuthType) = l.onAuthenticated()
            override fun onProfileUpdated(profile: Profile) = l.onProfile(profile.email.orEmpty(), profile.displayName)
            override fun onLoggedOut() = l.onLoggedOut()
            override fun onAuthenticationProblems() = l.onProblem()
            override fun onReady(authenticatedAccount: OAuthAccount?) = l.onReady(authenticatedAccount != null)
        })
        val owner = ProcessLifecycleOwner.get()
        manager.registerForSyncEvents(object : SyncStatusObserver {
            override fun onStarted() = l.onSyncStarted()
            override fun onIdle() = l.onSyncIdle()
            override fun onError(error: Exception?) = l.onSyncError(error?.message)
        }, owner, false)
        manager.registerForAccountEvents(object : AccountEventsObserver {
            override fun onEvents(events: List<AccountEvent>) {
                events.filterIsInstance<AccountEvent.DeviceCommandIncoming>()
                    .map { it.command }.filterIsInstance<DeviceCommandIncoming.TabReceived>()
                    .forEach { cmd -> cmd.entries.forEach { l.onTabReceived(cmd.from?.displayName ?: "其他设备", it.title, it.url) } }
            }
        }, owner, false)
    }

    suspend fun init() { manager.start() }

    // ---------------------------------------------------------------- 账号
    suspend fun beginLogin(): String? = manager.beginAuthentication(entrypoint = FxaEntry, service = "sync")

    suspend fun finishLogin(code: String, state: String) {
        manager.finishAuthentication(FxaAuthData(AuthType.Signin, code, state, null))
    }

    suspend fun syncNow() { manager.syncNow(SyncReason.User) }
    suspend fun logout() { manager.logout() }

    // ---------------------------------------------------------------- 书签（Places）
    suspend fun bookmarkTree(): PNode? {
        val root = unwrap<BookmarkNode?>(bookmarks.getTree("root________", true)) ?: return null
        fun conv(n: BookmarkNode): PNode = PNode(
            guid = n.guid,
            isFolder = n.type == BookmarkNodeType.FOLDER,
            title = n.title.orEmpty(),
            url = n.url,
            children = n.children.orEmpty().map(::conv),
        )
        return conv(root)
    }

    suspend fun childFolders(parentGuid: String): List<PNode> =
        (unwrap<BookmarkNode?>(bookmarks.getTree(parentGuid, false))?.children.orEmpty()).filter { it.type == BookmarkNodeType.FOLDER }
            .map { PNode(it.guid, true, it.title.orEmpty(), null, emptyList()) }

    suspend fun addFolder(parentGuid: String, title: String): String =
        unwrap(bookmarks.addFolder(parentGuid, title, null))

    suspend fun addBookmark(parentGuid: String, title: String, url: String) {
        unwrap<Any?>(bookmarks.addItem(parentGuid, url, title, null))
    }

    // ---------------------------------------------------------------- 历史（Places）
    suspend fun remoteVisits(since: Long): List<PVisit> =
        unwrap<List<mozilla.components.concept.storage.VisitInfo>>(history.getDetailedVisits(since, Long.MAX_VALUE))
            .filter { it.isRemote }
            .map { PVisit(it.url, it.title.orEmpty(), it.visitTime) }

    private val notSource: RedirectSource by lazy {
        val all = enumValues<RedirectSource>()
        all.firstOrNull { it.name.contains("NOT", true) || it.name.contains("NONE", true) } ?: all.first()
    }

    suspend fun recordVisit(url: String, title: String) {
        unwrap<Any?>(history.recordVisit(url, PageVisit(VisitType.LINK, notSource)))
        if (title.isNotBlank()) unwrap<Any?>(history.recordObservation(url, PageObservation(title)))
    }

    // ---------------------------------------------------------------- 标签页 / 发送标签
    suspend fun storeLocalTabs(list: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        tabs.store(list.map { (title, url) -> Tab(listOf(TabEntry(title, url, null)), 0, now, false) })
    }

    suspend fun remoteTabs(): List<RemoteDevice> =
        tabs.getAll().map { (client, ts) ->
            RemoteDevice(client.id, ts.mapNotNull { t ->
                val e = t.history.getOrNull(t.active) ?: t.history.lastOrNull() ?: return@mapNotNull null
                RemoteTab(e.title.ifBlank { e.url }, e.url)
            })
        }

    suspend fun devices(): List<PDevice> {
        val c = manager.authenticatedAccount()?.deviceConstellation() ?: return emptyList()
        c.refreshDevices()
        c.pollForCommands()
        return c.state()?.otherDevices.orEmpty()
            .filter { DeviceCapability.SEND_TAB in it.capabilities }
            .map { PDevice(it.id, it.displayName) }
    }

    suspend fun sendTab(deviceId: String, title: String, url: String): Boolean {
        val c = manager.authenticatedAccount()?.deviceConstellation() ?: return false
        return c.sendCommandToDevice(deviceId, newSendTab(title, url))
    }

    /** SendTab 新版多了 privacy 参数：按构造函数的参数类型补默认值，不写死签名。 */
    private fun newSendTab(title: String, url: String): DeviceCommandOutgoing {
        val ctor = DeviceCommandOutgoing.SendTab::class.java.constructors
            .filter { c -> c.parameterTypes.none { it.name.contains("DefaultConstructorMarker") } }
            .maxByOrNull { it.parameterTypes.size } ?: error("SendTab 构造函数不可用")
        val args = ctor.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 -> title
                i == 1 -> url
                t == java.lang.Boolean.TYPE -> false
                t.isEnum -> t.enumConstants.first()
                else -> null
            }
        }
        return ctor.newInstance(*args.toTypedArray()) as DeviceCommandOutgoing
    }

    private object FxaEntry : FxAEntryPoint {
        override val entryName: String = "mafari"
    }
}
