package dev.dsh.miuixbrowser

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本应用的系统自动填充服务：在「设置 → 密码和账号 → 自动填充服务」里选中后，系统 WebView 的网页、
 * Gecko 的网页（GeckoView 把页面表单作为虚拟结构交给系统，见 [GeckoAutofill]）、以及受信任的其它浏览器，
 * 点登录框都会列出密码页（[PasswordDb]）里的账号——和密码页、Firefox 同步、CSV 导入是同一份加密数据。
 *
 * 复用的现有组件：[PasswordDb]（读 / 解密 / 合并保存）、[PasswordAutofill]（站点匹配）、[PasswordPrefs]（三个开关）、
 * [BiometricGate]（填充前验证）。新增的只有「系统结构 → [AfNode] → [AutofillParser]」这层翻译。
 *
 * 取舍：
 *  - 列表只读元数据（站点 / 用户名），不解密；「填充前验证身份」打开时，每条是一个带验证的数据集，
 *    选中后由 [AutofillAuthActivity] 验证并只解开这一条；关闭时才解开（至多 [MAX_DATASETS] 条）直接给系统。
 *  - 只信任自己和 [TRUSTED_BROWSERS]：网页域名是调用方自己声明的，放开给任意应用，恶意应用就能谎称自己是银行网站来骗密码。
 *  - 保存提示只在本应用的 WebView 非无痕标签、以及受信浏览器里出现：Gecko 标签有自己的保存提示（[GeckoLogins]），不能重复弹。
 *  - 本应用的 WebView 标签正常情况下已改走 [WebLoginChannel]（应用内弹窗，并关掉了该 WebView 的系统自动填充）；
 *    只有 WebView 太旧装不上它时，本服务才继续负责本应用 WebView 的填充 / 保存。
 *  - 不能引用 `Gecko*` 类：lite 版里它们不存在（见 ARCHITECTURE §2）。
 */
class MafariAutofillService : AutofillService() {

    /** 本应用当前标签的状态（主线程读取，交给工作线程用）：补网页域名、判断是否允许保存。 */
    private class OwnTab(val url: String, val gecko: Boolean, val incognito: Boolean)

    private class Snapshot(val nodes: List<AfNode>, val ids: List<AutofillId>)

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val ctx = applicationContext
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null || !(PasswordPrefs.fillEnabled(ctx) || PasswordPrefs.saveEnabled(ctx)) || !trusted(structure)) {
            callback.onSuccess(null)
            return
        }
        val own = structure.activityComponent?.packageName == packageName
        val tab = if (own) ownTab() else null
        val cancelled = AtomicBoolean(false)
        cancel.setOnCancelListener { cancelled.set(true) }
        io.execute {
            val response = runCatching { buildResponse(ctx, structure, own, tab) }
                .onFailure { Diag.log("autofill: fill failed: $it") }
                .getOrNull()
            if (!cancelled.get()) callback.onSuccess(response)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val ctx = applicationContext
        val contexts = request.fillContexts
        val last = contexts.lastOrNull()?.structure
        if (last == null || !PasswordPrefs.saveEnabled(ctx) || !trusted(last)) {
            callback.onSuccess()
            return
        }
        val clientState = request.clientState
        io.execute {
            runCatching {
                // 提交后表单可能已不可见，所以不要求可见；从最近的上下文往前找第一个带密码值的
                for (fc in contexts.asReversed()) {
                    val snap = snapshot(fc.structure)
                    val form = AutofillParser.parse(snap.nodes, visibleOnly = false) ?: continue
                    val password = form.password?.text.orEmpty()
                    if (password.isEmpty()) continue
                    // 站点用填充请求时记下的（提交后页面可能已经跳转到别的站点）
                    val host = clientState?.getString(STATE_HOST) ?: form.domain ?: continue
                    val scheme = clientState?.getString(STATE_SCHEME) ?: form.scheme ?: "https"
                    val origin = PasswordCsv.originOf("$scheme://$host") ?: continue
                    val now = System.currentTimeMillis()
                    val entry = PasswordEntry(
                        guid = "", origin = origin, username = form.username?.text.orEmpty(), password = password,
                        timeCreated = now, timeLastUsed = now, timePasswordChanged = now, timesUsed = 1,
                    )
                    // 和 Gecko 保存、CSV 导入同一条合并路径：同站点同用户名，密码不同则更新，相同则不动
                    HistoryDb.get(ctx)?.let { PasswordDb.importEntries(ctx, it, listOf(entry)) }
                    break
                }
            }.onFailure { Diag.log("autofill: save failed: $it") }
            callback.onSuccess()
        }
    }

    // ------------------------------------------------------------------ 构造填充响应（工作线程）

    private fun buildResponse(ctx: Context, structure: AssistStructure, own: Boolean, tab: OwnTab?): FillResponse? {
        val snap = snapshot(structure)
        val form = AutofillParser.parse(snap.nodes)
        if (form == null) {
            Diag.log("autofill: no login form (nodes=${snap.nodes.size}, own=$own)")
            return null
        }
        // 网页域名：优先用结构里带的；本应用的 WebView / GeckoView 没带时用当前标签的地址
        var host = form.domain
        var scheme = form.scheme
        if (host.isNullOrEmpty() && own && tab != null) {
            host = PasswordAutofill.hostOf(tab.url)
            scheme = tab.url.substringBefore("://", "").takeIf { it.isNotEmpty() }
        }
        if (host.isNullOrEmpty()) {
            Diag.log("autofill: login form without a site (own=$own)")
            return null
        }
        val pageHost: String = host
        val pageScheme: String? = scheme
        val db = HistoryDb.get(ctx) ?: return null
        val userId = form.username?.let { snap.ids[it.index] }
        val passId = form.password?.let { snap.ids[it.index] }
        val response = FillResponse.Builder()
        var any = false
        // 只有第一个数据集的那一行带顶部文案
        fun rowView(username: String, site: String): RemoteViews =
            presentation(ctx, username, site, withHeader = !any)

        if (PasswordPrefs.fillEnabled(ctx) && !form.newPassword) {
            val items = PasswordDb.list(db)
                .filter { PasswordAutofill.usable(it.origin, it.httpRealm, pageHost, pageScheme) }
                .sortedWith(compareByDescending<PasswordDb.Item> { it.timeLastUsed }.thenBy { it.username.lowercase() })
                .take(MAX_DATASETS)
            if (passId == null) {
                // 分步登录的第一步：用户名不是机密，直接填
                if (userId != null) {
                    for (item in items.filter { it.username.isNotEmpty() }.distinctBy { it.username }) {
                        response.addDataset(
                            Dataset.Builder(rowView(item.username, PasswordCsv.displayHost(item.origin)))
                                .setValue(userId, AutofillValue.forText(item.username)).build(),
                        )
                        any = true
                    }
                }
            } else if (PasswordPrefs.verifyBeforeFill(ctx)) {
                for (item in items) {
                    response.addDataset(lockedDataset(ctx, item, userId, passId, withHeader = !any))
                    any = true
                }
            } else {
                for (item in items) {
                    val e = PasswordDb.reveal(ctx, db, item.guid) ?: continue
                    val b = Dataset.Builder(rowView(e.username, PasswordCsv.displayHost(e.origin)))
                    if (userId != null) b.setValue(userId, AutofillValue.forText(e.username))
                    b.setValue(passId, AutofillValue.forText(e.password))
                    response.addDataset(b.build())
                    any = true
                }
            }
        }

        // 顶部「钥匙 + 密码」已放进第一行（rowView），不再用 setHeader：不少厂商 ROM 的自定义下拉会忽略它，支持的机型则会重复显示

        // 保存提示：Gecko 标签有自己的（GeckoLogins），无痕标签不保存，取不到标签状态时不冒险
        val saveAllowed = passId != null && PasswordPrefs.saveEnabled(ctx) && (!own || (tab != null && !tab.gecko && !tab.incognito))
        if (saveAllowed && passId != null) {
            val save = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(passId))
            if (userId != null) save.setOptionalIds(arrayOf(userId))
            save.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            response.setSaveInfo(save.build())
            any = true
        }
        if (!any) return null
        response.setClientState(Bundle().apply { putString(STATE_HOST, pageHost); putString(STATE_SCHEME, pageScheme) })
        return response.build()
    }

    /** 需要验证身份的数据集：值留空，选中后系统启动 [AutofillAuthActivity]，验证通过才解密这一条。 */
    private fun lockedDataset(ctx: Context, item: PasswordDb.Item, userId: AutofillId?, passId: AutofillId, withHeader: Boolean = false): Dataset {
        val intent = Intent(ctx, AutofillAuthActivity::class.java)
            .putExtra(AutofillAuthActivity.EXTRA_GUID, item.guid)
            .putExtra(AutofillAuthActivity.EXTRA_PASS_ID, passId)
        if (userId != null) intent.putExtra(AutofillAuthActivity.EXTRA_USER_ID, userId)
        // 必须可变：系统启动认证界面时要往 Intent 里塞结构 / 客户端状态；
        // requestCode 必须每条不同——PendingIntent 的比较不看 extras，相同会互相覆盖
        val sender = PendingIntent.getActivity(
            ctx, requestCodes.incrementAndGet(), intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender
        val b = Dataset.Builder(presentation(ctx, item.username, PasswordCsv.displayHost(item.origin), withHeader))
        if (userId != null) b.setValue(userId, null)
        b.setValue(passId, null)
        return b.setAuthentication(sender).build()
    }

    // ------------------------------------------------------------------ 系统结构 → AfNode

    private fun trusted(structure: AssistStructure): Boolean {
        val pkg = structure.activityComponent?.packageName ?: return false
        return pkg == packageName || pkg in TRUSTED_BROWSERS
    }

    /** 本应用当前标签。读 Compose 状态，只能在主线程（[onFillRequest] 就在主线程）。 */
    private fun ownTab(): OwnTab? = runCatching {
        val t = BrowserState.current?.activeTab
        if (t == null) null else OwnTab(t.url, t.isGecko, t.incognito)
    }.getOrNull()

    private fun snapshot(structure: AssistStructure): Snapshot {
        val nodes = ArrayList<AfNode>()
        val ids = ArrayList<AutofillId>()
        for (w in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(w).rootViewNode, null, null, false, 0, nodes, ids)
        }
        return Snapshot(nodes, ids)
    }

    /**
     * 深度优先收集文本输入框。网页域名可能只在祖先节点（WebView 本身）上，所以往下传；
     * Gecko 与系统 WebView 的差异（有无 autofillHints、有无 HTML 属性）在这里抹平成 [AfNode]。
     */
    private fun walk(
        v: AssistStructure.ViewNode, parentDomain: String?, parentScheme: String?, parentWeb: Boolean,
        depth: Int, nodes: MutableList<AfNode>, ids: MutableList<AutofillId>,
    ) {
        if (depth > MAX_DEPTH || nodes.size >= MAX_NODES) return
        val ownDomain = v.webDomain?.takeIf { it.isNotEmpty() }
        val domain = ownDomain ?: parentDomain
        val scheme = if (ownDomain != null) v.webScheme?.takeIf { it.isNotEmpty() } else parentScheme
        val cls = v.className.orEmpty()
        val web = parentWeb || cls.endsWith("WebView") || cls.contains("GeckoView")
        val id = v.autofillId
        if (id != null && v.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val html = v.htmlInfo
            val attrs = HashMap<String, String>()
            html?.attributes?.forEach { attrs[it.first.lowercase()] = it.second.orEmpty() }
            val hints = ArrayList<String>()
            v.autofillHints?.let { hints.addAll(it) }
            attrs["autocomplete"]?.split(' ', '\t')?.filterTo(hints) { it.isNotBlank() }
            nodes.add(
                AfNode(
                    index = nodes.size,
                    hints = hints,
                    inputType = v.inputType,
                    htmlType = attrs["type"],
                    labels = listOfNotNull(attrs["name"], attrs["id"], attrs["placeholder"], attrs["aria-label"], v.idEntry, v.hint),
                    editable = v.isEnabled,
                    visible = v.visibility == View.VISIBLE,
                    focused = v.isFocused,
                    inWeb = web || html != null,
                    domain = domain,
                    scheme = scheme,
                    text = v.autofillValue?.takeIf { it.isText }?.textValue?.toString().orEmpty(),
                ),
            )
            ids.add(id)
        }
        for (i in 0 until v.childCount) walk(v.getChildAt(i), domain, scheme, web, depth + 1, nodes, ids)
    }

    companion object {
        private const val MAX_DATASETS = 6
        private const val MAX_DEPTH = 60
        private const val MAX_NODES = 500
        private const val STATE_HOST = "host"
        private const val STATE_SCHEME = "scheme"

        private val requestCodes = AtomicInteger(1)

        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mafari-autofill").apply { isDaemon = true } }

        /**
         * 除本应用外，信任其网页域名声明的浏览器。只列真实页面域名由浏览器自己给出的主流浏览器；
         * 任何不在名单里的应用（包括声称是浏览器的）一律不填，否则恶意应用可以谎称自己是任意网站。
         */
        private val TRUSTED_BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
            "com.microsoft.emmx", "com.brave.browser", "com.vivaldi.browser", "com.sec.android.app.sbrowser",
            "com.opera.browser", "com.duckduckgo.mobile.android",
        )

        /** 下拉里一行：主标题用户名，副标题站点。 */
        internal fun presentation(ctx: Context, username: String, site: String, withHeader: Boolean = false): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.autofill_item).apply {
                setTextViewText(R.id.af_title, username.ifEmpty { "（无用户名）" })
                setTextViewText(R.id.af_subtitle, site)
                // 第一行顶部带「钥匙 + 密码」
                if (withHeader) setViewVisibility(R.id.af_header, android.view.View.VISIBLE)
            }
    }
}
