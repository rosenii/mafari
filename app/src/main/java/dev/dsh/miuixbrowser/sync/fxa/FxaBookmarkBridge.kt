package dev.dsh.miuixbrowser.sync.fxa

import dev.dsh.miuixbrowser.data.Favorite
import dev.dsh.miuixbrowser.data.FavoritesStore
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Places 镜像库 ↔ Mafari 书签库（FavoritesStore）。第一期「只增不删」的双向合并：
 *  - 拉：Places 里的书签 → 本地分组「火狐同步/…」（按 分组+网址 去重）；
 *  - 推：本地「非火狐同步」分组的书签 → Places「移动设备书签」下同名文件夹；已推过的记在 prefs。
 *    推上去的书签下一轮会从「移动设备书签」读回来：拉取时按「相对路径 + 网址」认出本机自己的 / 已推过的，不再拉成重复；
 *    旧版本已经拉出来的重复副本在下一次合并时一次性清掉。
 * 默认删除 / 改名 / 移动不传播。
 *
 * 「绝对双向同步」（FxaSync.absoluteSync，同步高级选项）开启后，书签的**删除**也互相传递：
 *  - 以「上一轮两边都有的书签」为基线：基线里有、本机现在没有、云端还有 → 删云端那条；
 *    基线里有、云端现在没有、本机还有 → 删本机那条。
 *  - 刚开启（或关闭再开启）时基线为空，第一轮只记录基线、不删任何东西；
 *  - 保险：云端→本机单轮要删的书签超过基线一半（且 ≥ 20 条）时视为异常（如换号 / 云端库被清空），本轮不删。
 *  只处理书签；文件夹、改名、移动仍不传播。
 */
internal object FxaBookmarkBridge {
    private const val PREFS = "mafari_fxa_bookmarks"
    private const val KEY_PUSHED = "pushed"
    /** 绝对双向同步的删除基线：上一轮两边都有的书签标识 */
    private const val KEY_BASE = "abs_baseline"
    private const val MASS_DELETE_MIN = 20
    const val PULL_ROOT = "火狐同步"
    private const val MOBILE_TITLE = "移动设备书签"

    // Places 固定的根节点 guid
    private const val ROOT = "root________"
    private const val MOBILE = "mobile______"
    private val rootNames = mapOf(
        "menu________" to "书签菜单",
        "toolbar_____" to "书签工具栏",
        "unfiled_____" to "其他书签",
        MOBILE to MOBILE_TITLE,
    )

    private fun key(group: String, url: String) = group + "\u0000" + url.trimEnd('/').lowercase()

    private fun norm(group: String) = group.ifBlank { Favorite.GROUP_PERSONAL }

    /** Places 里的一条书签：[mobileKey] 非空表示它位于「移动设备书签」下（相对路径 + 网址的键，和本地键同一格式）。 */
    private class Remote(val guid: String, val group: String, val name: String, val url: String, val mobileKey: String?)

    /** 关 / 开「绝对双向同步」时清空删除基线：下一轮只重新记录，不删东西。 */
    fun resetBaseline(context: Context) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_BASE).apply() }
    }

    /** 本机书签的统一标识：本机自己的 = 分组+网址；从 Firefox 拉下来的（「火狐同步」分组）加 P: 前缀。 */
    private fun localId(f: Favorite) =
        if (isPulled(f.group)) "P:" + key(f.group, f.url) else key(norm(f.group), f.url)

    suspend fun reconcile(context: Context) {
        val store = FavoritesStore.current ?: return // 界面没起来就不动书签库
        val tree = FxaApi.bookmarkTree() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pushed = prefs.getStringSet(KEY_PUSHED, emptySet()).orEmpty().toHashSet()

        // ---- 拉 ----
        val remote = ArrayList<Remote>()
        // rel：位于「移动设备书签」下时，相对它的分组路径（本地分组推上去时就是这个路径）；否则 null
        fun walk(node: PNode, path: String, rel: String?) {
            if (node.isFolder) {
                val title = rootNames[node.guid] ?: node.title
                val next = when {
                    node.guid == ROOT -> path
                    path.isEmpty() -> "$PULL_ROOT/$title"
                    else -> "$path/$title"
                }
                val nextRel = when {
                    node.guid == MOBILE -> ""
                    rel == null -> null
                    rel.isEmpty() -> title
                    else -> "$rel/$title"
                }
                node.children.forEach { walk(it, next, nextRel) }
            } else node.url?.let {
                remote += Remote(node.guid, path.ifEmpty { PULL_ROOT }, node.title, it, rel?.let { r -> key(norm(r), it) })
            }
        }
        walk(tree, "", null)

        // ---- 绝对双向同步：先把删除传过去 ----
        val absolute = FxaSync.absoluteSync
        val baseline: Set<String>? = if (absolute) prefs.getStringSet(KEY_BASE, null)?.toHashSet() else null
        var pushedChanged = false
        // 云端条目的统一标识：「移动设备书签」下属于本机的（own / 已推过）用本机键，其余按拉取副本算
        fun remoteId(r: Remote, own: Set<String>) =
            if (r.mobileKey != null && (r.mobileKey in own || r.mobileKey in pushed)) r.mobileKey else "P:" + key(r.group, r.url)
        if (absolute && baseline != null) {
            val (localItems, ownKeys) = withContext(Dispatchers.Main) {
                val items = store.items.toList()
                items to items.filter { !isPulled(it.group) }.map { key(norm(it.group), it.url) }.toHashSet()
            }
            val localIds = localItems.map(::localId).toHashSet()
            val remoteIds = remote.map { remoteId(it, ownKeys) }.toHashSet()
            // 本机删了、云端还在 → 删云端
            val delRemote = remote.filter { val id = remoteId(it, ownKeys); id in baseline && id !in localIds }
            // 云端删了、本机还在 → 删本机
            val delLocalIds = localIds.filter { it in baseline && it !in remoteIds }.toHashSet()
            fun mass(n: Int) = n >= MASS_DELETE_MIN && n * 2 > baseline.size
            run {
                for (r in delRemote) {
                    if (runCatching { FxaApi.deleteBookmark(r.guid) }.isSuccess) {
                        r.mobileKey?.let { pushed.remove(it) }
                        remote.remove(r)
                        pushedChanged = true
                    }
                }
            }
            if (delLocalIds.isNotEmpty() && !mass(delLocalIds.size)) {
                withContext(Dispatchers.Main) {
                    val gone = store.items.filter { localId(it) in delLocalIds }
                    if (gone.isNotEmpty()) {
                        for (f in gone) if (!isPulled(f.group)) pushed.remove(key(norm(f.group), f.url))
                        store.items.removeAll(gone.toSet())
                        store.commitSyncChanges()
                        pushedChanged = true
                    }
                }
            }
        }

        withContext(Dispatchers.Main) {
            // 本机自己的书签（不含拉下来的「火狐同步」分组）：推到「移动设备书签」后又会从 Places 读回来，要认出来别当成新书签
            val own = store.items.filter { !isPulled(it.group) }.map { key(norm(it.group), it.url) }.toHashSet()
            var changed = false

            // 一次性清理旧版本留下的重复：「火狐同步/移动设备书签/…」下与本机书签同组同址的拉取副本
            val dupGroups = HashSet<String>()
            val before = store.items.size
            val mobilePrefix = "$PULL_ROOT/$MOBILE_TITLE"
            store.items.removeAll { f ->
                if (f.group != mobilePrefix && !f.group.startsWith("$mobilePrefix/")) return@removeAll false
                val rel = f.group.removePrefix(mobilePrefix).removePrefix("/")
                val dup = key(norm(rel), f.url) in own
                if (dup) dupGroups += f.group
                dup
            }
            if (store.items.size != before) {
                // 清空后的空文件夹一并删掉
                for (g in dupGroups.sortedByDescending { it.length }) {
                    if (store.itemsInTree(g).isEmpty()) store.removeFolderTree(g)
                }
                changed = true
            }

            val have = store.items.map { key(norm(it.group), it.url) }.toHashSet()
            var added = 0
            for (r in remote) {
                if (key(r.group, r.url) in have) continue
                // 本机推上去的（或本机已有同组同址的）：不再拉回来
                if (r.mobileKey != null && (r.mobileKey in own || r.mobileKey in pushed)) continue
                store.registerFolder(r.group)
                store.add(r.name, r.url, r.group)
                have += key(r.group, r.url)
                added++
            }
            if (added > 0 || changed) store.commitSyncChanges()
        }

        // ---- 推 ----
        val remoteKeys = remote.flatMap { listOfNotNull(key(it.group, it.url), it.mobileKey) }.toHashSet()
        val local = withContext(Dispatchers.Main) { store.items.toList() }
        var pushedAny = false
        for (f in local) {
            if (isPulled(f.group)) continue
            val group = norm(f.group)
            val k = key(group, f.url)
            if (k in pushed || k in remoteKeys) continue
            var parent = MOBILE
            for (seg in group.split('/').filter { it.isNotBlank() }) {
                parent = FxaApi.childFolders(parent).firstOrNull { it.title == seg }?.guid ?: FxaApi.addFolder(parent, seg)
            }
            FxaApi.addBookmark(parent, f.name, f.url)
            pushed += k
            pushedAny = true
        }
        if (pushedAny || pushedChanged) prefs.edit().putStringSet(KEY_PUSHED, pushed).apply()

        // ---- 记录新基线：此刻两边都有的书签 ----
        if (absolute) {
            val (localAfter, ownAfter) = withContext(Dispatchers.Main) {
                val items = store.items.toList()
                items.map(::localId).toHashSet() to items.filter { !isPulled(it.group) }.map { key(norm(it.group), it.url) }.toHashSet()
            }
            val remoteAfter = remote.map { remoteId(it, ownAfter) }.toHashSet()
            for (f in local) if (!isPulled(f.group) && key(norm(f.group), f.url) in pushed) remoteAfter += key(norm(f.group), f.url)
            prefs.edit().putStringSet(KEY_BASE, localAfter.filter { it in remoteAfter }.toHashSet()).apply()
        }
        if (pushedAny) FxaSync.syncFollowUp()
    }

    private fun isPulled(group: String) = group == PULL_ROOT || group.startsWith("$PULL_ROOT/")
}
