package dev.dsh.miuixbrowser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Places 镜像库 ↔ Mafari 书签库（FavoritesStore）。第一期「只增不删」的双向合并：
 *  - 拉：Places 里的书签 → 本地分组「火狐同步/…」（按 分组+网址 去重）；
 *  - 推：本地「非火狐同步」分组的书签 → Places「移动设备书签」下同名文件夹；已推过的记在 prefs。
 *    推上去的书签下一轮会从「移动设备书签」读回来：拉取时按「相对路径 + 网址」认出本机自己的 / 已推过的，不再拉成重复；
 *    旧版本已经拉出来的重复副本在下一次合并时一次性清掉。
 * 删除 / 改名 / 移动暂不传播。
 */
internal object FxaBookmarkBridge {
    private const val PREFS = "mafari_fxa_bookmarks"
    private const val KEY_PUSHED = "pushed"
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
    private class Remote(val group: String, val name: String, val url: String, val mobileKey: String?)

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
                remote += Remote(path.ifEmpty { PULL_ROOT }, node.title, it, rel?.let { r -> key(norm(r), it) })
            }
        }
        walk(tree, "", null)

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
        if (pushedAny) {
            prefs.edit().putStringSet(KEY_PUSHED, pushed).apply()
            FxaSync.syncFollowUp()
        }
    }

    private fun isPulled(group: String) = group == PULL_ROOT || group.startsWith("$PULL_ROOT/")
}
