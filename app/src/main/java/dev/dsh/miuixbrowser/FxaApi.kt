package dev.dsh.miuixbrowser

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import mozilla.appservices.fxaclient.FxaServer
import mozilla.appservices.init_rust_components.initialize as initRustComponents
import mozilla.appservices.viaduct.Backend
import mozilla.appservices.viaduct.ClientSettings
import mozilla.appservices.viaduct.initBackend
import mozilla.appservices.viaduct.Request as ViaductRequest
import mozilla.appservices.viaduct.Response as ViaductResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.browser.storage.sync.Tab
import mozilla.components.browser.storage.sync.TabEntry
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.Login as MozLogin
import mozilla.components.concept.storage.LoginEntry as MozLoginEntry
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import mozilla.components.service.sync.logins.SyncableLoginsStorage
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
 * Rust logins 库里的一条登录（Firefox 同步的 `passwords` 集合）。与 [PasswordEntry] 的差别：
 * [formActionOrigin] 为 ""（空串）表示「任意表单」，null 表示这是 HTTP 认证（此时 [httpRealm] 非 null）。时间均为毫秒。
 */
internal class PLogin(
    val guid: String,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
    val usernameField: String,
    val passwordField: String,
    val timesUsed: Long,
    val timeCreated: Long,
    val timeLastUsed: Long,
    val timePasswordChanged: Long,
)

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
    private lateinit var logins: SyncableLoginsStorage
    private var started = false

    /** Places 的方法在新版里返回 Result<T>，旧版直接返回 T：统一拆开。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> unwrap(v: Any?): T = (if (v is Result<*>) v.getOrThrow() else v) as T

    /**
     * RemoteTabsStorage 要求非空的 CrashReporting。我们不上报任何崩溃：用动态代理实现一个「什么都不做」的，
     * 不依赖接口里具体有哪些方法（Job 返回值给一个已完成的 Job，其余返回 null / 0 / false）。
     */
    private val noCrashReporter: CrashReporting by lazy {
        java.lang.reflect.Proxy.newProxyInstance(
            CrashReporting::class.java.classLoader,
            arrayOf(CrashReporting::class.java),
        ) { _, method, _ ->
            val rt = method.returnType
            when {
                rt == Void.TYPE -> null
                kotlinx.coroutines.Job::class.java.isAssignableFrom(rt) -> kotlinx.coroutines.Job().also { it.complete() }
                rt == java.lang.Boolean.TYPE -> false
                rt.isPrimitive -> 0
                else -> null
            }
        } as CrashReporting
    }

    /**
     * Rust 层（viaduct）自己没有网络栈，必须由应用注册 HTTP 后端（Fenix 在 Application 里做同样的事）。
     * 新版 application-services 已移除 RustHttpConfig.setClient，改为 mozilla.appservices.viaduct.initBackend(Backend)。
     * 没注册时，取账号服务配置 / 登录地址会失败，表现就是「无法获取登录地址」。失败原因记下来，由界面显示。
     */
    @Volatile var httpInitError: String? = null
        private set

    /** 用 OkHttp 实现 viaduct 的 Backend：Rust 发来的请求在这里真正走网络。 */
    private class OkHttpBackend : Backend {
        private val base = OkHttpClient.Builder().build()

        override suspend fun sendRequest(request: ViaductRequest, settings: ClientSettings): ViaductResponse =
            withContext(Dispatchers.IO) {
                val timeoutMs = settings.timeout.toLong()
                val client = base.newBuilder().apply {
                    if (timeoutMs > 0) {
                        connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    }
                    followRedirects(settings.redirectLimit.toInt() > 0)
                }.build()

                val method = request.method.name.uppercase()
                val body = request.body?.toRequestBody(null)
                    ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(null) else null
                val builder = OkRequest.Builder().url(request.url).method(method, body)
                request.headers.forEach { (k, v) -> builder.header(k, v) }
                settings.userAgent?.let { ua ->
                    if (request.headers.keys.none { it.equals("user-agent", ignoreCase = true) }) builder.header("User-Agent", ua)
                }

                client.newCall(builder.build()).execute().use { r ->
                    val headers = linkedMapOf<String, String>()
                    r.headers.forEach { (k, v) ->
                        val key = k.lowercase()
                        headers[key] = headers[key]?.let { "$it, $v" } ?: v
                    }
                    ViaductResponse(
                        requestMethod = request.method,
                        url = r.request.url.toString(),
                        status = r.code.toUShort(),
                        headers = headers,
                        body = r.body?.bytes() ?: ByteArray(0),
                    )
                }
            }
    }

    /**
     * application-services 要求在任何 Rust 组件调用之前先做全局初始化（内部初始化 NSS 加密库等），
     * 否则登录 / 同步会报「NSS has not initialized」。Fenix 在 Application 里最先做这一步。
     * 必须在 installHttpClient、各 Storage 构造、FxaAccountManager 之前调用。
     */
    private fun initRustComponentsEarly() {
        runCatching { initRustComponents() }
            .onFailure { httpInitError = "Rust 组件初始化失败：" + (it.cause?.message ?: it.message ?: it.javaClass.simpleName) }
    }

    private fun installHttpClient() {
        runCatching { initBackend(OkHttpBackend()) }
            .onFailure { httpInitError = it.cause?.message ?: it.message ?: it.javaClass.simpleName }
    }

    @Synchronized
    fun start(context: Context, l: Listener) {
        if (started) return
        started = true
        ctx = context.applicationContext
        initRustComponentsEarly()
        installHttpClient()
        bookmarks = PlacesBookmarksStorage(ctx)
        history = PlacesHistoryStorage(ctx)
        tabs = RemoteTabsStorage(ctx, noCrashReporter)
        // 登录库（Rust）的加密密钥放在 Keystore 保护的偏好里；这里只是 Firefox 同步的中转镜像，
        // 本机真正的密码仍在 PasswordDb（SQLCipher + 逐条加密），见 FxaPasswordBridge
        logins = SyncableLoginsStorage(ctx, lazy { SecureAbove22Preferences(ctx, "mafari_fxa_logins", false) })

        GlobalSyncableStoreProvider.configureStore(SyncEngine.Bookmarks to lazy { bookmarks })
        GlobalSyncableStoreProvider.configureStore(SyncEngine.History to lazy { history })
        GlobalSyncableStoreProvider.configureStore(SyncEngine.Tabs to lazy { tabs })
        GlobalSyncableStoreProvider.configureStore(SyncEngine.Passwords to lazy { logins })

        manager = FxaAccountManager(
            context = ctx,
            serverConfig = ServerConfig(FxaServer.Release, FxaSync.CLIENT_ID, FxaSync.REDIRECT_URL),
            deviceConfig = DeviceConfig(
                name = "Mafari (${android.os.Build.MODEL})",
                type = DeviceType.MOBILE,
                capabilities = setOf(DeviceCapability.SEND_TAB),
            ),
            syncConfig = SyncConfig(setOf(SyncEngine.Bookmarks, SyncEngine.History, SyncEngine.Tabs, SyncEngine.Passwords), periodicSyncConfig = null),
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
    /** 不再吞异常：失败原因由 [FxaSync.beginLogin] 显示出来。 */
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

    suspend fun recordVisit(url: String, title: String) {
        unwrap<Any?>(history.recordVisit(url, PageVisit(VisitType.LINK, null)))
        if (title.isNotBlank()) unwrap<Any?>(history.recordObservation(url, PageObservation(title)))
    }

    // ---------------------------------------------------------------- 密码（Rust logins）
    /** 镜像库里的全部登录（已解密，只在内存里过一下，不落盘、不进日志）。 */
    suspend fun loginList(): List<PLogin> =
        unwrap<List<MozLogin>>(logins.list()).map {
            PLogin(
                guid = it.guid, origin = it.origin, formActionOrigin = it.formActionOrigin, httpRealm = it.httpRealm,
                username = it.username, password = it.password,
                usernameField = it.usernameField, passwordField = it.passwordField,
                timesUsed = it.timesUsed.toLong(), timeCreated = it.timeCreated.toLong(),
                timeLastUsed = it.timeLastUsed.toLong(), timePasswordChanged = it.timePasswordChanged.toLong(),
            )
        }

    private fun loginEntry(l: PLogin) = MozLoginEntry(
        origin = l.origin,
        formActionOrigin = l.formActionOrigin,
        httpRealm = l.httpRealm,
        usernameField = l.usernameField,
        passwordField = l.passwordField,
        username = l.username,
        password = l.password,
    )

    /** 新增（库里已有同站点同用户名的会合并成一条）；返回库里这条的 id——可能与 [l] 的 guid 不同，调用方要换成它。 */
    suspend fun loginAddOrUpdate(l: PLogin): String = unwrap<MozLogin>(logins.addOrUpdate(loginEntry(l))).guid

    /** 按 id 更新；库里没有这个 id（已被服务器删除）返回 false。 */
    suspend fun loginUpdate(guid: String, l: PLogin): Boolean = runCatching { unwrap<Any?>(logins.update(guid, loginEntry(l))) }.isSuccess

    suspend fun loginDelete(guid: String) { runCatching { unwrap<Any?>(logins.delete(guid)) } }

    /** 退出账号时清空本地镜像，免得下一个登录的账号把上一个账号的密码同步出去。 */
    suspend fun loginWipeLocal() { unwrap<Any?>(logins.wipeLocal()) }

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
                t.isEnum -> t.enumConstants.let { all -> all.firstOrNull { e -> val n = e.toString().uppercase(); n.contains("NORMAL") || n.contains("PUBLIC") || n.contains("NONE") } ?: all.first() }
                else -> null
            }
        }
        return ctor.newInstance(*args.toTypedArray()) as DeviceCommandOutgoing
    }

    private object FxaEntry : FxAEntryPoint {
        override val entryName: String = "mafari"
    }
}
