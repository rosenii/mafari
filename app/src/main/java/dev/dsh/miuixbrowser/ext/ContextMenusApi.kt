package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Context-menu registry shared by every tab and the extension background page. */
class ContextMenusApi(private val pages: ExtensionPageHost) {
    data class Item(val id: String, val extId: String, val parentId: String?, val title: String,
                    val contexts: Set<String>, val documentPatterns: List<MatchPattern>,
                    val targetPatterns: List<MatchPattern>, val enabled: Boolean, val order: Long)
    private val ids = AtomicLong(1)
    private val items = ConcurrentHashMap<String, MutableList<Item>>()

    fun register(router: ApiRouter) {
        router.register("contextMenus.create") { c, a ->
            val o = a.optJSONObject(0) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "缺少菜单项")
            val id = if (o.has("id")) o.opt("id").toString() else "mafari-${ids.getAndIncrement()}"
            val item = parse(c.ext.spec.id, id, o, null)
            val list = items.getOrPut(c.ext.spec.id) { mutableListOf() }
            synchronized(list) {
                if (list.any { it.id == id }) throw ApiException(ErrorCodes.BAD_REQUEST, "菜单 id 已存在")
                list.add(item)
            }
            id
        }
        router.register("contextMenus.update") { c, a ->
            val id = a.opt(0).toString(); val patch = a.optJSONObject(1) ?: JSONObject()
            val list = items[c.ext.spec.id] ?: throw ApiException(ErrorCodes.BAD_REQUEST, "菜单项不存在")
            synchronized(list) {
                val i = list.indexOfFirst { it.id == id }
                if (i < 0) throw ApiException(ErrorCodes.BAD_REQUEST, "菜单项不存在")
                list[i] = parse(c.ext.spec.id, id, patch, list[i])
            }
            null
        }
        router.register("contextMenus.remove") { c, a -> remove(c.ext.spec.id, a.opt(0).toString()); null }
        router.register("contextMenus.removeAll") { c, _ -> items.remove(c.ext.spec.id); null }
    }

    fun forget(extId: String) { items.remove(extId) }

    private fun remove(extId: String, id: String) {
        val list = items[extId] ?: return
        synchronized(list) { list.removeAll { it.id == id || it.parentId == id } }
    }

    private fun parse(extId: String, id: String, o: JSONObject, old: Item?): Item {
        fun strings(key: String, fallback: Set<String>): Set<String> {
            val v = o.optJSONArray(key) ?: return fallback
            return (0 until v.length()).map { v.optString(it) }.filter { it.isNotBlank() }.toSet()
        }
        fun patterns(key: String, fallback: List<MatchPattern>): List<MatchPattern> {
            val v = o.optJSONArray(key) ?: return fallback
            return (0 until v.length()).map { i -> MatchPattern.parse(v.optString(i)) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "无效的 $key 匹配规则") }
        }
        val title = if (o.has("title")) o.optString("title") else old?.title.orEmpty()
        return Item(id, extId, if (o.has("parentId")) o.opt("parentId").toString() else old?.parentId,
            title, strings("contexts", old?.contexts ?: setOf("page")),
            patterns("documentUrlPatterns", old?.documentPatterns ?: emptyList()),
            patterns("targetUrlPatterns", old?.targetPatterns ?: emptyList()),
            if (o.has("enabled")) o.optBoolean("enabled", true) else old?.enabled ?: true,
            old?.order ?: ids.getAndIncrement())
    }

    /** Called by the browser's native long-press/context-menu callback; URLs must come from WebView. */
    fun matching(ext: InstalledExtension, context: String, documentUrl: String, targetUrl: String?): List<Item> =
        items[ext.spec.id].orEmpty().filter { item -> item.enabled &&
            ("all" in item.contexts || context in item.contexts) &&
            (item.documentPatterns.isEmpty() || item.documentPatterns.any { it.matches(documentUrl) }) &&
            (item.targetPatterns.isEmpty() || (targetUrl != null && item.targetPatterns.any { it.matches(targetUrl) }))
        }.sortedBy { it.order }

    fun click(extId: String, id: String, info: JSONObject, tab: JSONObject?) {
        val clickInfo = JSONObject(info.toString()).put("menuItemId", id)
        val payload = JSONObject().put("info", clickInfo)
        if (tab != null) payload.put("tab", tab)
        pages.emitEvent(extId, "contextMenus.onClicked", payload)
    }
}
