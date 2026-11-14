package dev.dsh.miuixbrowser

import android.content.Context
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import java.util.concurrent.Executors

/**
 * Gecko 网页登录的存储端：GeckoView 自己不存任何密码，要填什么、存什么都问 [Autocomplete.StorageDelegate]。
 * 这里把它接到加密的密码库（[PasswordDb]）——和密码页、Firefox 同步用的是同一份数据，
 * 所以网页里保存的密码会出现在密码页里，并随 Firefox 同步推出去。
 *
 * 界面端（保存提示、选择要填的账号）在 [GeckoPromptHandler.onLoginSave] / [GeckoPromptHandler.onLoginSelect]。
 *
 * 数据库 / 解密都不在主线程做：委托回调在 UI 线程，统一丢给 [io]，取候选用 [GeckoResult] 异步交还。
 * 只在 gecko 变体里会被碰到（入口是 [GeckoRuntimeHolder.get]）；lite 没有 Gecko 类，共用代码不要直接引用它。
 */
internal object GeckoLogins : Autocomplete.StorageDelegate {

    enum class SaveKind { NEW, UPDATE, SAME }

    /** [decide] 的结果：[entry] 是准备写库的记录（还没有 guid，由密码库分配）。 */
    class SaveDecision(val kind: SaveKind, val entry: PasswordEntry)

    @Volatile private var appContext: Context? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mafari-logins").apply { isDaemon = true } }

    fun runIo(block: () -> Unit) {
        io.execute {
            runCatching(block).onFailure { Diag.log("logins: io task failed: $it") }
        }
    }

    /** Gecko 运行时刚创建后调用一次：挂上存储委托，并把开关同步给 Gecko。 */
    fun install(runtime: GeckoRuntime, context: Context) {
        appContext = context.applicationContext
        runCatching { runtime.setAutocompleteStorageDelegate(this) }
            .onFailure { Diag.log("logins: install storage delegate failed: $it") }
        PasswordPrefs.pushToGecko(context)
    }

    // ---------------------------------------------------------------- StorageDelegate

    /** 页面上有登录框时 Gecko 来要候选。「自动填充」关着就如实给空：不填，保存提示那边自己判断是不是已存在。 */
    override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>>? {
        val ctx = appContext ?: return null
        val result = GeckoResult<Array<Autocomplete.LoginEntry>>()
        io.execute {
            val found: List<Autocomplete.LoginEntry> = runCatching {
                if (!PasswordPrefs.fillEnabled(ctx)) {
                    emptyList()
                } else {
                    HistoryDb.get(ctx)?.let { PasswordDb.candidatesForHost(ctx, it, domain) }.orEmpty().map(::toGecko)
                }
            }.getOrElse {
                Diag.log("logins: fetch failed: $it")
                emptyList()
            }
            result.complete(found.toTypedArray())
        }
        return result
    }

    /** 用户在保存提示里点了「保存 / 更新」之后，Gecko 把最终的登录交过来。写库是幂等的（同站点同用户名合并）。 */
    override fun onLoginSave(login: Autocomplete.LoginEntry) {
        val ctx = appContext ?: return
        if (!PasswordPrefs.saveEnabled(ctx)) return
        val entry = toEntry(login) ?: return
        runIo { save(ctx, entry) }
    }

    /** 某条登录被填进页面了：记一次使用（不触发同步）。 */
    override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
        val ctx = appContext ?: return
        val guid = login.guid?.takeIf { it.isNotEmpty() } ?: return
        runIo { HistoryDb.get(ctx)?.let { PasswordDb.markUsed(it, guid) } }
    }

    // ---------------------------------------------------------------- 保存提示用（都要在 [io] 线程调用）

    /** 判断这次登录该「新增 / 更新 / 不用提示（已存且密码没变）」。不能保存的（来源不是网页、密码为空）返回 null。 */
    fun decide(context: Context, login: Autocomplete.LoginEntry): SaveDecision? {
        val entry = toEntry(login) ?: return null
        val db = HistoryDb.get(context) ?: return null
        val old = PasswordDb.findSaved(context, db, entry.origin, entry.username, entry.httpRealm)
        val kind = when {
            old == null -> SaveKind.NEW
            old.password == entry.password -> SaveKind.SAME
            else -> SaveKind.UPDATE
        }
        return SaveDecision(kind, entry)
    }

    fun save(context: Context, entry: PasswordEntry) {
        val db = HistoryDb.get(context) ?: return
        // 走和导入同一条合并路径：同站点同用户名 → 密码不同则更新（标为待上传），相同则不动；没有则新增
        PasswordDb.importEntries(context, db, listOf(entry.copy(timePasswordChanged = System.currentTimeMillis())))
    }

    // ---------------------------------------------------------------- 与 Gecko 的记录互转

    private fun toGecko(e: PasswordEntry): Autocomplete.LoginEntry =
        Autocomplete.LoginEntry.Builder()
            .guid(e.guid)
            .origin(e.origin)
            .formActionOrigin(e.formOrigin)
            .httpRealm(e.httpRealm)
            .username(e.username)
            .password(e.password)
            .build()

    /** 来源规整成 scheme://host[:port]；表单提交目标与认证域互斥（有认证域就不要表单目标）。 */
    fun toEntry(l: Autocomplete.LoginEntry, now: Long = System.currentTimeMillis()): PasswordEntry? {
        val origin = PasswordCsv.originOf(l.origin ?: return null) ?: return null
        val password = l.password
        if (password.isNullOrEmpty()) return null
        val realm = l.httpRealm?.takeIf { it.isNotEmpty() }
        val formOrigin = if (realm != null) null else l.formActionOrigin?.let { PasswordCsv.originOf(it) }
        return PasswordEntry(
            guid = "", origin = origin, formOrigin = formOrigin, httpRealm = realm,
            username = l.username.orEmpty(), password = password,
            timeCreated = now, timeLastUsed = now, timePasswordChanged = now, timesUsed = 1,
        )
    }
}
