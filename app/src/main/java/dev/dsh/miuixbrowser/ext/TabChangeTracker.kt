package dev.dsh.miuixbrowser.ext

/**
 * 把「可见标签快照列表」前后两次的差异翻译成 [TabEventListener] 事件。纯逻辑、不依赖 Android，可以在 JVM 里直接测。
 *
 * - 第一次 [update] 只建立基线、不发事件：接上桥接之前就存在的标签不算「新建」；
 * - 事件顺序：onRemoved → onCreated → onUpdated → onActivated；
 * - 只有 status（loading / complete）、url、title、favIconUrl 的变化才发 onUpdated；下标变化单独发 onMoved；
 * - favIconUrl 变成 null（无法用 [TabChange] 表示「清除」）不发；
 * - 前台标签换了才发 onActivated；之前的前台标签已不存在时 previousTabId 为 null；
 *   没有任何可见前台标签（用户正停在无痕 / Gecko 标签上）时不发，也不算「失去前台」，回到可见标签时 previousTabId 为 null。
 *
 * 只能在一个线程上调用（桥接实现里是主线程）；回调里不要再同步调回 [update]。
 */
class TabChangeTracker(private val sink: TabEventListener) {

    private var known: Map<Int, TabInfo>? = null
    private var activeId: Int? = null

    fun update(tabs: List<TabInfo>) {
        val now = LinkedHashMap<Int, TabInfo>(tabs.size * 2)
        for (t in tabs) now[t.id] = t
        val nowActive = tabs.firstOrNull { it.active }?.id

        val before = known
        val prevActive = activeId
        known = now
        activeId = nowActive
        if (before == null) return

        for (id in before.keys) if (id !in now) sink.onRemoved(id)
        for ((id, tab) in now) if (id !in before) sink.onCreated(tab)
        for ((id, tab) in now) {
            val old = before[id] ?: continue
            val change = changeOf(old, tab)
            if (!change.isEmpty()) sink.onUpdated(tab, change)
            if (old.index != tab.index) sink.onMoved(id, old.index, tab.index)
        }
        if (nowActive != null && nowActive != prevActive) {
            sink.onActivated(nowActive, prevActive?.takeIf { it in now })
        }
    }

    private fun changeOf(old: TabInfo, now: TabInfo) = TabChange(
        status = now.status.takeIf { it != old.status },
        url = now.url.takeIf { it != old.url },
        title = now.title.takeIf { it != old.title },
        favIconUrl = now.favIconUrl?.takeIf { it != old.favIconUrl },
    )
}
