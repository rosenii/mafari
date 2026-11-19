package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * `tabs.*` 的处理器，建在 [TabBridge] 之上。
 *
 * 已实现：query / get / getCurrent / create / update / remove / reload / goBack / goForward，
 * 以及 onCreated / onUpdated / onRemoved / onActivated 事件。
 * 未实现：`tabs.sendMessage`（需要把消息投递到某个标签里的 content script，要先有按标签的连接记录，单独一步做）、
 * move / duplicate / group / captureVisibleTab 等，调用会回 UNSUPPORTED。
 *
 * 安全约束：
 * - 只有拓展自己的页面能调（[PermissionChecker] 已限制 content script），所以不存在「网页借拓展读别的标签」；
 * - url / title / favIconUrl 只在有 `tabs` 权限、对该网址有 host 权限、或是本拓展自己的页面时才回给拓展，
 *   用 url / title 当查询条件时也只匹配这些「看得到」的标签，否则能靠过滤结果猜出看不到的网址；
 * - create / update 的网址过 [TabUrlPolicy]；
 * - [events] 必须是**只发往拓展页面**的 sink（传 ExtensionPageHost，不要传包含标签页 content script 的 CompositeEventSink），
 *   否则别的标签的网址会推到网页里的 content script 上。
 */
class TabsApi(
    private val events: EventSink,
    /** 当前启用、在 WebView 上运行的拓展：事件要按各自的权限分别序列化 */
    private val extensions: () -> List<InstalledExtension>,
) : TabEventListener {

    @Volatile private var bridge: TabBridge? = null

    /** 接上标签页桥接（浏览器界面建好后调用）；重复调用会先摘掉旧的 */
    fun attach(b: TabBridge) {
        detach()
        b.addListener(this)
        bridge = b
    }

    fun detach() {
        bridge?.removeListener(this)
        bridge = null
    }

    /** 仅当当前接着的是 [b] 才摘（界面重建时新旧桥接交替，旧的晚到的 detach 不能误伤新的） */
    fun detach(b: TabBridge) {
        if (bridge === b) detach()
    }

    /** 给同样要用标签桥接的模块（scripting 等）共用同一个连接，没接上时为 null */
    fun bridgeOrNull(): TabBridge? = bridge

    private fun bridge(): TabBridge =
        bridge ?: throw ApiException(ErrorCodes.UNSUPPORTED, "标签页暂不可用")

    fun register(router: ApiRouter) {
        router.register("tabs.query") { ctx, a ->
            val filter = TabQuery.parse(a.argOrNull(0) as? JSONObject ?: JSONObject())
            val out = JSONArray()
            for (t in bridge().list()) if (filter.matches(ctx.ext, t)) out.put(toJson(ctx.ext, t))
            out
        }
        router.register("tabs.get") { ctx, a ->
            val id = toInt(a.argOrNull(0), "tabId")
            toJson(ctx.ext, bridge().get(id) ?: throw noTab(id))
        }
        // 拓展页面不是标签页，Chrome 里同样返回 undefined
        router.register("tabs.getCurrent") { _, _ -> null }

        router.register("tabs.create") { ctx, a ->
            val p = a.argOrNull(0) as? JSONObject ?: JSONObject()
            checkWindow(p.intOrNull("windowId"))
            val url = p.stringOrNull("url")?.let { TabUrlPolicy.normalize(ctx.ext.spec.id, it) }
            val active = p.boolOrNull("active") ?: p.boolOrNull("selected") ?: true
            val opener = p.intOrNull("openerTabId")
            if (opener != null && bridge().get(opener) == null) throw noTab(opener)
            toJson(ctx.ext, bridge().create(TabCreateOptions(url, active, opener)))
        }
        router.register("tabs.update") { ctx, a ->
            val id = resolveTabId(a.argOrNull(0))
            val p = a.argOrNull(1) as? JSONObject ?: JSONObject()
            val url = p.stringOrNull("url")?.let { TabUrlPolicy.normalize(ctx.ext.spec.id, it) }
            // highlighted:true 在单窗口里等同于激活；pinned / muted / autoDiscardable 等没有对应概念，按空操作处理
            val active = p.boolOrNull("active") ?: p.boolOrNull("selected") ?: p.boolOrNull("highlighted")
            toJson(ctx.ext, bridge().update(id, TabUpdateOptions(url, active)) ?: throw noTab(id))
        }
        router.register("tabs.remove") { _, a ->
            val ids = when (val v = a.argOrNull(0)) {
                is JSONArray -> (0 until v.length()).map { toInt(v.opt(it), "tabIds") }
                else -> listOf(toInt(v, "tabIds"))
            }.distinct()
            val missing = bridge().remove(ids)
            if (missing.isNotEmpty()) throw noTab(missing.first())
            null
        }
        router.register("tabs.reload") { _, a ->
            val id = resolveTabId(a.argOrNull(0))
            val bypass = (a.argOrNull(1) as? JSONObject)?.boolOrNull("bypassCache") ?: false
            if (!bridge().reload(id, bypass)) throw noTab(id)
            null
        }
        router.register("tabs.goBack") { _, a ->
            val id = resolveTabId(a.argOrNull(0))
            if (!bridge().goBack(id)) throw noTab(id)
            null
        }
        router.register("tabs.goForward") { _, a ->
            val id = resolveTabId(a.argOrNull(0))
            if (!bridge().goForward(id)) throw noTab(id)
            null
        }
    }

    /** tabId 为 null = 当前窗口的前台标签（Chrome 的缺省语义） */
    private suspend fun resolveTabId(v: Any?): Int {
        if (v != null) return toInt(v, "tabId")
        return bridge().list().firstOrNull { it.active }?.id
            ?: throw ApiException(ErrorCodes.BAD_REQUEST, "没有前台标签页")
    }

    // ------------------------------------------------------------------ 事件

    override fun onCreated(tab: TabInfo) = fanOut("tabs.onCreated") { ext ->
        JSONObject().put("tab", toJson(ext, tab))
    }

    override fun onUpdated(tab: TabInfo, change: TabChange) = fanOut("tabs.onUpdated") { ext ->
        val see = canSeeDetails(ext, tab.url)
        val info = JSONObject()
        change.status?.let { info.put("status", it) }
        if (see) {
            change.url?.let { info.put("url", it) }
            change.title?.let { info.put("title", it) }
            change.favIconUrl?.let { info.put("favIconUrl", it) }
        }
        // 变化的全是看不到的字段：这个拓展不该收到事件（连「有变化」本身都不泄露）
        if (info.length() == 0) null
        else JSONObject().put("tabId", tab.id).put("changeInfo", info).put("tab", toJson(ext, tab))
    }

    override fun onRemoved(tabId: Int) = fanOut("tabs.onRemoved") { _ ->
        JSONObject().put("tabId", tabId)
            .put("removeInfo", JSONObject().put("windowId", TabBridge.WINDOW_ID).put("isWindowClosing", false))
    }

    override fun onActivated(tabId: Int, previousTabId: Int?) = fanOut("tabs.onActivated") { _ ->
        JSONObject().put("activeInfo", JSONObject().put("tabId", tabId).put("windowId", TabBridge.WINDOW_ID))
    }

    /** 对每个拓展分别生成载荷（各自权限不同）；返回 null 表示这个拓展不推送 */
    private fun fanOut(event: String, payload: (InstalledExtension) -> JSONObject?) {
        for (ext in extensions()) {
            try {
                val p = payload(ext) ?: continue
                events.emit(ext.spec.id, event, p)
            } catch (_: Exception) {
                // 一个拓展出错不影响其它拓展
            }
        }
    }

    // ------------------------------------------------------------------ 序列化 / 权限

    companion object {
        const val WINDOW_ID_CURRENT = -2
        const val WINDOW_ID_NONE = -1

        /** url / title / favIconUrl 能不能给这个拓展：tabs 权限、host 权限，或是它自己的页面 */
        internal fun canSeeDetails(ext: InstalledExtension, url: String): Boolean =
            PermissionChecker.canSeeTabDetails(ext, url) ||
                url.startsWith(ExtensionOrigins.originFor(ext.spec.id) + "/", ignoreCase = true)

        internal fun toJson(ext: InstalledExtension, t: TabInfo): JSONObject {
            val o = JSONObject()
                .put("id", t.id)
                .put("index", t.index)
                .put("windowId", TabBridge.WINDOW_ID)
                .put("active", t.active)
                .put("highlighted", t.active)
                .put("status", t.status)
                // 下面这些浏览器里没有对应概念：给 Chrome 里的「默认值」，不假装有能力
                .put("pinned", false)
                .put("incognito", false)
                .put("audible", false)
                .put("discarded", false)
                .put("autoDiscardable", true)
                .put("groupId", -1)
                .put("mutedInfo", JSONObject().put("muted", false))
            t.openerTabId?.let { o.put("openerTabId", it) }
            if (canSeeDetails(ext, t.url)) {
                o.put("url", t.url).put("title", t.title)
                t.favIconUrl?.let { o.put("favIconUrl", it) }
            }
            return o
        }

        internal fun noTab(id: Int) = ApiException(ErrorCodes.BAD_REQUEST, "No tab with id: $id.")

        private fun checkWindow(id: Int?) {
            if (id != null && id != TabBridge.WINDOW_ID && id != WINDOW_ID_CURRENT) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "No window with id: $id.")
            }
        }

        /** JSON 数字 → Int；小数、超范围、非数字都算参数错误 */
        internal fun toInt(v: Any?, what: String): Int {
            val n = v as? Number ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$what 必须是整数")
            val d = n.toDouble()
            if (d != Math.rint(d) || abs(d) > Int.MAX_VALUE) throw ApiException(ErrorCodes.BAD_REQUEST, "$what 必须是整数")
            return n.toInt()
        }

        internal fun JSONObject.boolOrNull(key: String): Boolean? {
            if (!has(key) || isNull(key)) return null
            return opt(key) as? Boolean ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是布尔值")
        }

        internal fun JSONObject.intOrNull(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            return toInt(opt(key), key)
        }

        internal fun JSONObject.stringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return opt(key) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是字符串")
        }
    }
}

/**
 * tabs.query 的过滤条件。没有对应概念的属性（pinned / audible / muted / discarded / incognito）
 * 按「全部为 false」处理：查 `pinned:true` 得到空，查 `pinned:false` 得到全部。
 */
internal class TabQuery private constructor(
    private val active: Boolean?,
    private val highlighted: Boolean?,
    private val pinned: Boolean?,
    private val audible: Boolean?,
    private val muted: Boolean?,
    private val discarded: Boolean?,
    private val autoDiscardable: Boolean?,
    private val incognito: Boolean?,
    private val currentWindow: Boolean?,
    private val lastFocusedWindow: Boolean?,
    private val windowId: Int?,
    private val windowType: String?,
    private val groupId: Int?,
    private val index: Int?,
    private val status: String?,
    private val urls: List<MatchPattern>?,
    private val title: Regex?,
) {
    fun matches(ext: InstalledExtension, t: TabInfo): Boolean {
        if (active != null && t.active != active) return false
        if (highlighted != null && t.active != highlighted) return false
        if (pinned == true || audible == true || muted == true || discarded == true || incognito == true) return false
        if (autoDiscardable == false) return false
        // 只有一个窗口且它就是当前窗口：false 表示「不在当前窗口」，必然为空
        if (currentWindow == false || lastFocusedWindow == false) return false
        if (windowId != null && windowId != TabBridge.WINDOW_ID && windowId != TabsApi.WINDOW_ID_CURRENT) return false
        if (windowType != null && windowType != "normal") return false
        if (groupId != null && groupId != -1) return false
        if (index != null && t.index != index) return false
        if (status != null && t.status != status) return false
        if (urls != null || title != null) {
            // 看不到网址的标签不参与 url / title 过滤，否则能靠过滤结果探测它
            if (!TabsApi.canSeeDetails(ext, t.url)) return false
            if (urls != null && !MatchPattern.anyMatches(urls, t.url)) return false
            if (title != null && !title.matches(t.title)) return false
        }
        return true
    }

    companion object {
        fun parse(q: JSONObject): TabQuery {
            fun bool(k: String) = with(TabsApi) { q.boolOrNull(k) }
            fun int(k: String) = with(TabsApi) { q.intOrNull(k) }
            fun str(k: String) = with(TabsApi) { q.stringOrNull(k) }
            return TabQuery(
                active = bool("active"),
                highlighted = bool("highlighted"),
                pinned = bool("pinned"),
                audible = bool("audible"),
                muted = bool("muted"),
                discarded = bool("discarded"),
                autoDiscardable = bool("autoDiscardable"),
                incognito = bool("incognito"),
                currentWindow = bool("currentWindow"),
                lastFocusedWindow = bool("lastFocusedWindow"),
                windowId = int("windowId"),
                windowType = str("windowType"),
                groupId = int("groupId"),
                index = int("index"),
                status = str("status"),
                urls = parseUrls(q),
                title = str("title")?.let { glob(it) },
            )
        }

        private fun parseUrls(q: JSONObject): List<MatchPattern>? {
            if (!q.has("url") || q.isNull("url")) return null
            return when (val u = q.get("url")) {
                is String -> listOf(pattern(u))
                is JSONArray -> (0 until u.length()).map {
                    pattern(u.opt(it) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "url 必须是字符串或字符串数组"))
                }
                else -> throw ApiException(ErrorCodes.BAD_REQUEST, "url 必须是字符串或字符串数组")
            }
        }

        private fun pattern(s: String): MatchPattern =
            MatchPattern.parse(s) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "Invalid url pattern '$s'")

        /** Chrome 的 title 过滤是只认 `*` 的通配符，其余字符按字面匹配 */
        private fun glob(p: String): Regex =
            Regex(p.split("*").joinToString(".*") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL)
    }
}
