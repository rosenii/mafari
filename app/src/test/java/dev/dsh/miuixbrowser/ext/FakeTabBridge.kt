package dev.dsh.miuixbrowser.ext

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 测试用：内存里的标签页桥接。行为与 [TabBridge] 的约定一致（缺失标签不抛异常、事件同步触发），
 * 另外提供 [navigate] / [setTitle] 之类的「页面侧发生了变化」入口，以及 [evaluated] 记录收到的脚本。
 */
class FakeTabBridge(private val maxTabs: Int = 100) : TabBridge {

    private class Entry(
        val id: Int,
        var url: String,
        var title: String,
        var loading: Boolean = false,
        var favIconUrl: String? = null,
        var openerTabId: Int? = null,
    )

    private val entries = ArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<TabEventListener>()
    private var nextId = 1
    private var activeId: Int? = null

    /** [evaluate] 收到的 (tabId, js)，按调用顺序 */
    val evaluated = ArrayList<Pair<Int, String>>()

    /** [evaluate] 的返回值（按 tabId）；没设置过返回 "null" */
    val evalResults = HashMap<Int, String>()

    /** 设置后 [evaluate] 交给它处理（参数：tabId、脚本），返回 evaluateJavascript 的原始结果文本 */
    var evalHandler: ((Int, String) -> String?)? = null

    val reloads = ArrayList<Pair<Int, Boolean>>()

    private fun snapshot(e: Entry) = TabInfo(
        id = e.id,
        index = entries.indexOf(e),
        active = e.id == activeId,
        url = e.url,
        title = e.title,
        favIconUrl = e.favIconUrl,
        loading = e.loading,
        openerTabId = e.openerTabId?.takeIf { oid -> entries.any { it.id == oid } },
    )

    private fun find(id: Int) = entries.firstOrNull { it.id == id }

    /** 测试里直接放一个标签进来（会触发 onCreated），返回它的 id */
    fun add(url: String = "", title: String = "", active: Boolean = false): Int {
        val e = Entry(nextId++, url, title)
        entries.add(e)
        listeners.forEach { it.onCreated(snapshot(e)) }
        if (active || activeId == null) activate(e.id)
        return e.id
    }

    private fun activate(id: Int) {
        if (activeId == id) return
        val prev = activeId?.takeIf { p -> entries.any { it.id == p } }
        activeId = id
        listeners.forEach { it.onActivated(id, prev) }
    }

    /** 页面侧：开始导航并加载完成（依次触发 loading / complete 的 onUpdated） */
    fun navigate(tabId: Int, url: String) {
        val e = find(tabId) ?: return
        e.url = url
        e.loading = true
        listeners.forEach { it.onUpdated(snapshot(e), TabChange(status = TabChange.STATUS_LOADING, url = url)) }
        e.loading = false
        listeners.forEach { it.onUpdated(snapshot(e), TabChange(status = TabChange.STATUS_COMPLETE)) }
    }

    fun setTitle(tabId: Int, title: String) {
        val e = find(tabId) ?: return
        e.title = title
        listeners.forEach { it.onUpdated(snapshot(e), TabChange(title = title)) }
    }

    fun listenerCount(): Int = listeners.size

    // ---------------------------------------------------------------- TabBridge

    override suspend fun list(): List<TabInfo> = entries.map { snapshot(it) }

    override suspend fun get(tabId: Int): TabInfo? = find(tabId)?.let { snapshot(it) }

    override suspend fun create(options: TabCreateOptions): TabInfo {
        if (entries.size >= maxTabs) throw ApiException(ErrorCodes.QUOTA, "标签页数量已达上限")
        val e = Entry(nextId++, options.url.orEmpty(), "", loading = !options.url.isNullOrEmpty())
        e.openerTabId = options.openerTabId?.takeIf { find(it) != null }
        entries.add(e)
        listeners.forEach { it.onCreated(snapshot(e)) }
        if (options.active || activeId == null) activate(e.id)
        return snapshot(e)
    }

    override suspend fun move(tabIds: List<Int>, index: Int): List<TabInfo> {
        val moving = tabIds.distinct().map { find(it) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "No tab with id: $it") }
            .sortedBy { entries.indexOf(it) }
        val from = moving.map { entries.indexOf(it) }
        moving.forEach { entries.remove(it) }
        val target = if (index < 0) entries.size else index.coerceAtMost(entries.size)
        entries.addAll(target, moving)
        moving.forEachIndexed { i, entry ->
            val old = from[i]
            val now = entries.indexOf(entry)
            if (old != now) listeners.forEach { it.onMoved(entry.id, old, now) }
        }
        return moving.map(::snapshot)
    }

    override suspend fun update(tabId: Int, options: TabUpdateOptions): TabInfo? {
        val e = find(tabId) ?: return null
        options.url?.let { u ->
            e.url = u
            e.loading = true
            listeners.forEach { it.onUpdated(snapshot(e), TabChange(status = TabChange.STATUS_LOADING, url = u)) }
        }
        if (options.active == true) activate(e.id)
        return snapshot(e)
    }

    override suspend fun remove(tabIds: List<Int>): List<Int> {
        val missing = ArrayList<Int>()
        for (id in tabIds) {
            val e = find(id)
            if (e == null) {
                missing.add(id)
                continue
            }
            val wasActive = activeId == id
            entries.remove(e)
            if (wasActive) activeId = entries.lastOrNull()?.id
            listeners.forEach { it.onRemoved(id) }
            if (wasActive) activeId?.let { next -> listeners.forEach { it.onActivated(next, null) } }
        }
        return missing
    }

    override suspend fun reload(tabId: Int, bypassCache: Boolean): Boolean {
        find(tabId) ?: return false
        reloads.add(tabId to bypassCache)
        return true
    }

    override suspend fun goBack(tabId: Int): Boolean = find(tabId) != null

    override suspend fun goForward(tabId: Int): Boolean = find(tabId) != null

    override suspend fun evaluate(tabId: Int, js: String): String? {
        find(tabId) ?: return null
        evaluated.add(tabId to js)
        evalHandler?.let { return it(tabId, js) }
        return evalResults[tabId] ?: "null"
    }

    override fun addListener(listener: TabEventListener) {
        if (listener !in listeners) listeners.add(listener)
    }

    override fun removeListener(listener: TabEventListener) {
        listeners.remove(listener)
    }
}
