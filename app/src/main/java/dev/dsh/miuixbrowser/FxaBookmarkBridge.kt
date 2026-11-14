package dev.dsh.miuixbrowser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Places 镜像库 ↔ Mafari 书签库（FavoritesStore）。第一期「只增不删」的双向合并：
 *  - 拉：Places 里的书签 → 本地分组「火狐同步/…」（按 分组+网址 去重）；
 *  - 推：本地「非火狐同步」分组的书签 → Places「移动设备书签」下同名文件夹；已推过的记在 prefs。
 * 删除 / 改名 / 移动暂不传播。
 */
internal object FxaBookmarkBridge {
    private const val PREFS = "mafari_fxa_bookmarks"
    private const val KEY_PUSHED = "pushed"
    const val PULL_ROOT = "火狐同步"

    // Places 固定的根节点 guid
    private const val ROOT = "root________"
    private const val MOBILE = "mobile______"
    private val rootNames = mapOf(
        "menu________" to "书签菜单",
        "toolbar_____" to "书签工具栏",
        "unfiled_____" to "其他书签",
        MOBILE to "移动设备书签",
    )

    private fun key(group: String, url: String) = group + "\u0000" + url.trimEnd('/').lowercase()

    suspend fun reconcile(context: Context) {
        val store = FavoritesStore.current ?: return // 界面没起来就不动书签库
        val tree = FxaApi.bookmarkTree() ?: return

        // ---- 拉 ----
        val remote = ArrayList<Triple<String, String, String>>() // group, name, url
        fun walk(node: PNode, path: String) {
            if (node.isFolder) {
                val title = rootNames[node.guid] ?: node.title
                val next = when {
                    node.guid == ROOT -> path
                    path.isEmpty() -> "$PULL_ROOT/$title"
                    else -> "$path/$title"
                }
                node.children.forEach { walk(it, next) }
            } else node.url?.let { remote += Triple(path.ifEmpty { PULL_ROOT }, node.title, it) }
        }
        walk(tree, "")

        withContext(Dispatchers.Main) {
            val have = store.items.map { key(it.group, it.url) }.toHashSet()
            var added = 0
            for ((group, name, url) in remote) {
                if (key(group, url) in have) continue
                store.registerFolder(group)
                store.add(name, url, group)
                added++
            }
            if (added > 0) store.commitSyncChanges()
        }

        // ---- 推 ----
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pushed = prefs.getStringSet(KEY_PUSHED, emptySet()).orEmpty().toHashSet()
        val remoteKeys = remote.map { key(it.first, it.third) }.toHashSet()
        val local = withContext(Dispatchers.Main) { store.items.toList() }
        var changed = false
        for (f in local) {
            if (f.group == PULL_ROOT || f.group.startsWith("$PULL_ROOT/")) continue
            val k = key(f.group, f.url)
            if (k in pushed || k in remoteKeys) continue
            var parent = MOBILE
            for (seg in f.group.split('/').filter { it.isNotBlank() }) {
                parent = FxaApi.childFolders(parent).firstOrNull { it.title == seg }?.guid ?: FxaApi.addFolder(parent, seg)
            }
            FxaApi.addBookmark(parent, f.name, f.url)
            pushed += k
            changed = true
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_PUSHED, pushed).apply()
            FxaSync.syncNow()
        }
    }
}
