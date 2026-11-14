package dev.dsh.miuixbrowser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.BookmarkRoot

/**
 * Places 镜像库 ↔ Mafari 书签库（BookmarkDb / FavoritesStore）的适配层。第一期只做「只增不删」的双向合并：
 *  - 拉：Places 里的书签 → 本地分组「火狐同步/…」（按 分组+网址 去重，只新增）；
 *  - 推：本地「非火狐同步」分组的书签 → Places 的「移动设备书签」下，同名文件夹结构；已推过的记在 prefs，不重复推。
 * 删除 / 改名 / 移动暂不传播（避免两套结构对账出错把书签误删）；之后再引入 guid↔id 映射表做完整对账。
 */
internal object FxaBookmarkBridge {

    private const val PREFS = "mafari_fxa_bookmarks"
    private const val KEY_PUSHED = "pushed"
    const val PULL_ROOT = "火狐同步"

    private val rootNames = mapOf(
        BookmarkRoot.Menu.id to "书签菜单",
        BookmarkRoot.Toolbar.id to "书签工具栏",
        BookmarkRoot.Unfiled.id to "其他书签",
        BookmarkRoot.Mobile.id to "移动设备书签",
    )

    private fun key(group: String, url: String) = group + "\u0000" + url.trimEnd('/').lowercase()

    suspend fun reconcile(context: Context) {
        val store = FavoritesStore.current ?: return // 界面没起来就不动书签库，下次同步再来
        val places = PlacesBookmarksStorage(context)
        val tree = places.getTree(BookmarkRoot.Root.id, recursive = true) ?: return

        // ---- 拉 ----
        val remote = ArrayList<Triple<String, String, String>>() // group, name, url
        fun walk(node: BookmarkNode, path: String) {
            when (node.type) {
                BookmarkNodeType.FOLDER -> {
                    val title = rootNames[node.guid] ?: node.title.orEmpty()
                    val next = if (node.guid == BookmarkRoot.Root.id) path
                    else if (path.isEmpty()) "$PULL_ROOT/$title" else "$path/$title"
                    node.children?.forEach { walk(it, next) }
                }
                BookmarkNodeType.ITEM -> node.url?.let { u ->
                    remote += Triple(path.ifEmpty { PULL_ROOT }, node.title.orEmpty(), u)
                }
                else -> Unit
            }
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
            val parent = ensureFolders(places, BookmarkRoot.Mobile.id, f.group.split('/').filter { it.isNotBlank() })
            places.addItem(parent, f.url, f.name, null)
            pushed += k
            changed = true
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_PUSHED, pushed).apply()
            FxaSync.syncNow() // 把新推的书签上传
        }
    }

    private suspend fun ensureFolders(places: PlacesBookmarksStorage, rootGuid: String, segments: List<String>): String {
        var parent = rootGuid
        for (seg in segments) {
            val node = places.getTree(parent, recursive = false)
            val existing = node?.children?.firstOrNull { it.type == BookmarkNodeType.FOLDER && it.title == seg }
            parent = existing?.guid ?: places.addFolder(parent, seg, null)
        }
        return parent
    }
}
