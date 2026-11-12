package dev.dsh.miuixbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

/**
 * Floccus 书签同步桥。
 *
 * GeckoView 没有 browser.bookmarks，所以应用内置了一份 Floccus（assets/mafari-floccus），
 * 它的页面里先加载 mafari-bookmarks-shim.js，把 browser.bookmarks 的调用经 sendNativeMessage 转到这里，
 * 由这里读写应用自己的书签（[FavoritesStore]）。
 *
 * 书签树的映射（应用只有「分组 → 书签」两层，这里用 "/" 分隔的分组名表示嵌套文件夹）：
 *   根 root________
 *   ├ menu________   书签菜单   ↔ 分组「个人收藏」（直接放其中的书签；其下的文件夹 = 顶层分组）
 *   ├ toolbar_____   书签栏     ↔ 分组「书签栏」
 *   ├ unfiled_____   其他书签   ↔ 分组「其他书签」
 *   └ mobile______   移动书签   ↔ 分组「移动设备书签」
 * 每个容器的子项顺序：先文件夹（按注册表顺序），后书签（按列表顺序）。
 *
 * 注意：本文件依赖 GeckoView WebExtension 的消息 API，未在本环境编译。
 */
object BookmarksBridge {
    const val EXT_ID = "floccus@handmadeideas.org"
    private const val EXT_URI = "resource://android/assets/mafari-floccus/"
    private const val NATIVE_APP = "mafariBookmarks"

    private const val ROOT = "root________"
    private const val MENU = "menu________"
    private const val TOOLBAR = "toolbar_____"
    private const val UNFILED = "unfiled_____"
    private const val MOBILE = "mobile______"

    /** 根容器 id → (应用里的分组名, Firefox 里的标题) */
    private val roots = linkedMapOf(
        TOOLBAR to Pair("书签栏", "Bookmarks Toolbar"),
        MENU to Pair(Favorite.GROUP_PERSONAL, "Bookmarks Menu"),
        UNFILED to Pair("其他书签", "Other Bookmarks"),
        MOBILE to Pair("移动设备书签", "Mobile Bookmarks"),
    )
    private val rootGroups = roots.values.map { it.first }.toSet()

    @Volatile private var ext: WebExtension? = null
    @Volatile private var installing = false
    private val main = Handler(Looper.getMainLooper())

    /** 主线程。Gecko 运行时创建后调用一次（幂等）。 */
    fun ensureInstalled(context: Context) {
        if (ext != null || installing) return
        installing = true
        val app = context.applicationContext
        GeckoRuntimeHolder.get(app).webExtensionController.ensureBuiltIn(EXT_URI, EXT_ID).then({ e: WebExtension? ->
            installing = false
            if (e == null) return@then GeckoResult<Void>()
            ext = e
            e.setMessageDelegate(delegate(app), NATIVE_APP)
            Diag.log("bookmarks: floccus built-in ready ${e.id}")
            GeckoResult<Void>()
        }, { t: Throwable ->
            installing = false
            Diag.log("bookmarks: ensureBuiltIn failed: $t")
            GeckoResult<Void>()
        })
    }

    private fun delegate(app: Context) = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val result = GeckoResult<Any>()
            val msg = message as? JSONObject
            // FavoritesStore 里是 Compose 的状态列表：统一切到主线程读写，也顺带串行化了所有调用
            main.post {
                val out = JSONObject()
                try {
                    val store = FavoritesStore.current ?: FavoritesStore(app)
                    val args = msg?.optJSONArray("args") ?: JSONArray()
                    out.put("result", Ops(store).run(msg?.optString("op").orEmpty(), args))
                } catch (t: Throwable) {
                    Diag.log("bookmarks: ${msg?.optString("op")} failed: $t")
                    out.put("error", t.message ?: t.toString())
                }
                result.complete(out)
            }
            return result
        }
    }

    // ------------------------------------------------------------------ 书签树 ⇄ FavoritesStore

    private class Ops(val store: FavoritesStore) {
        private fun group(f: Favorite) = f.group.ifBlank { Favorite.GROUP_PERSONAL }

        /** 文件夹 / 根的 id → 容器分组名（根用其对应分组；普通文件夹用路径）。 */
        private fun containerOf(id: String): String? =
            roots[id]?.first ?: store.knownFolders().let { store.folderPath(id) }

        private fun isFolderId(id: String) = id == ROOT || roots.containsKey(id) || store.folderPath(id) != null

        private fun containerId(g: String): String =
            roots.entries.firstOrNull { it.value.first == g }?.key ?: store.folderId(g) ?: store.registerFolder(g)

        /** 文件夹路径的父容器（顶层文件夹的父容器是菜单 = 「个人收藏」）。 */
        private fun parentContainer(path: String) =
            if (path.contains('/')) path.substringBeforeLast('/') else Favorite.GROUP_PERSONAL

        private fun childFolders(container: String): List<String> =
            store.knownFolders().filter { it !in rootGroups && it != Favorite.GROUP_PERSONAL && parentContainer(it) == container }

        private fun childBookmarks(container: String): List<Favorite> = store.items.filter { group(it) == container }

        private fun bookmarkNode(f: Favorite, parentId: String, index: Int) = JSONObject()
            .put("id", f.id).put("parentId", parentId).put("index", index)
            .put("title", f.name).put("url", f.url).put("type", "bookmark").put("dateAdded", 0)

        private fun folderNode(id: String, parentId: String?, index: Int, title: String, container: String, deep: Boolean): JSONObject {
            val o = JSONObject().put("id", id).put("index", index).put("title", title)
                .put("type", "folder").put("dateAdded", 0)
            if (parentId != null) o.put("parentId", parentId)
            if (deep) o.put("children", childrenOf(container, id, true))
            return o
        }

        private fun childrenOf(container: String, containerId: String, deep: Boolean): JSONArray {
            val arr = JSONArray()
            var idx = 0
            childFolders(container).forEach { path ->
                arr.put(folderNode(store.folderId(path)!!, containerId, idx++, path.substringAfterLast('/'), path, deep))
            }
            childBookmarks(container).forEach { arr.put(bookmarkNode(it, containerId, idx++)) }
            return arr
        }

        private fun rootNode(deep: Boolean): JSONObject {
            val o = JSONObject().put("id", ROOT).put("title", "").put("type", "folder").put("index", 0).put("dateAdded", 0)
            if (deep) {
                val kids = JSONArray()
                roots.entries.forEachIndexed { i, (id, v) ->
                    kids.put(folderNode(id, ROOT, i, v.second, v.first, true))
                }
                o.put("children", kids)
            }
            return o
        }

        /** 任意 id → 节点（[deep] 时带完整子树）。 */
        private fun nodeOf(id: String, deep: Boolean): JSONObject {
            if (id == ROOT) return rootNode(deep)
            roots[id]?.let { (g, title) ->
                return folderNode(id, ROOT, roots.keys.indexOf(id), title, g, deep)
            }
            store.knownFolders()
            store.folderPath(id)?.let { path ->
                val parent = parentContainer(path)
                val pid = containerId(parent)
                val idx = childFolders(parent).indexOf(path)
                return folderNode(id, pid, idx, path.substringAfterLast('/'), path, deep)
            }
            val f = store.items.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Bookmark not found: $id")
            val g = group(f)
            val idx = childFolders(g).size + childBookmarks(g).indexOfFirst { it.id == id }
            return bookmarkNode(f, containerId(g), idx)
        }

        fun run(op: String, args: JSONArray): Any = when (op) {
            "getTree" -> JSONArray().put(rootNode(true))
            "getSubTree" -> JSONArray().put(nodeOf(args.getString(0), true))
            "get" -> {
                val a = args.opt(0)
                val ids = if (a is JSONArray) (0 until a.length()).map { a.getString(it) } else listOf(a.toString())
                JSONArray().also { arr -> ids.forEach { arr.put(nodeOf(it, false)) } }
            }
            "getChildren" -> {
                val id = args.getString(0)
                if (id == ROOT) JSONArray().also { arr -> roots.keys.forEach { arr.put(nodeOf(it, false)) } }
                else {
                    val c = containerOf(id) ?: throw IllegalArgumentException("Folder not found: $id")
                    childrenOf(c, id, false)
                }
            }
            "search" -> search(args.opt(0))
            "create" -> create(args.getJSONObject(0))
            "update" -> update(args.getString(0), args.getJSONObject(1))
            "move" -> move(args.getString(0), args.getJSONObject(1))
            "remove" -> remove(args.getString(0), tree = false)
            "removeTree" -> remove(args.getString(0), tree = true)
            else -> throw IllegalArgumentException("Unsupported bookmarks op: $op")
        }

        private fun search(q: Any?): JSONArray {
            val url: String?; val title: String?; val query: String?
            if (q is JSONObject) {
                url = q.optString("url").takeIf { it.isNotEmpty() }
                title = q.optString("title").takeIf { it.isNotEmpty() }
                query = q.optString("query").takeIf { it.isNotEmpty() }
            } else {
                url = null; title = null; query = q?.toString()?.takeIf { it.isNotEmpty() }
            }
            fun norm(u: String) = u.trimEnd('/')
            val out = JSONArray()
            store.items.filter { f ->
                (url == null || norm(f.url) == norm(url)) &&
                    (title == null || f.name == title) &&
                    (query == null || f.name.contains(query, true) || f.url.contains(query, true))
            }.forEach { out.put(nodeOf(it.id, false)) }
            return out
        }

        /** 把书签 [f] 放到容器 [target] 的第 [index] 位（index 按「文件夹在前、书签在后」的整体下标）。 */
        private fun placeBookmark(f: Favorite, target: String, index: Int?) {
            val cur = store.items.indexOfFirst { it.id == f.id }
            if (cur >= 0) store.items.removeAt(cur)
            val moved = f.copy(group = target)
            val siblings = store.items.withIndex().filter { group(it.value) == target }.map { it.index }
            val bIdx = if (index == null) siblings.size else (index - childFolders(target).size).coerceIn(0, siblings.size)
            val at = when {
                bIdx < siblings.size -> siblings[bIdx]
                siblings.isNotEmpty() -> siblings.last() + 1
                else -> store.items.size
            }
            store.items.add(at, moved)
        }

        private fun pathIn(container: String, title: String) =
            (if (container == Favorite.GROUP_PERSONAL) "" else "$container/") + title.replace('/', '／')

        private fun create(o: JSONObject): JSONObject {
            val parentId = o.optString("parentId").ifEmpty { MENU }
            if (parentId == ROOT) throw IllegalArgumentException("Cannot create under the root")
            val container = containerOf(parentId) ?: throw IllegalArgumentException("Folder not found: $parentId")
            val index = if (o.has("index") && !o.isNull("index")) o.getInt("index") else null
            val url = o.optString("url").takeIf { o.has("url") && !o.isNull("url") && it.isNotEmpty() }
            val title = o.optString("title")
            return if (url != null) {
                val f = Favorite(name = title.ifBlank { url }, url = url, group = container)
                placeBookmark(f, container, index)
                store.commitSyncChanges()
                nodeOf(f.id, false)
            } else {
                val path = pathIn(container, title.ifBlank { "新文件夹" })
                val id = store.registerFolder(path)
                if (index != null) {
                    val sibs = childFolders(container)
                    val at = index.coerceIn(0, sibs.size)
                    if (at < sibs.size && sibs[at] != path) store.reorderFolder(path, store.knownFolders().indexOf(sibs[at]))
                }
                nodeOf(id, false)
            }
        }

        private fun update(id: String, ch: JSONObject): JSONObject {
            if (id == ROOT || roots.containsKey(id)) throw IllegalArgumentException("Bookmark root cannot be modified")
            store.knownFolders()
            val path = store.folderPath(id)
            if (path != null) {
                if (ch.has("title")) {
                    val np = pathIn(parentContainer(path), ch.getString("title"))
                    if (np != path) {
                        if (store.folderId(np) != null) throw IllegalArgumentException("Folder already exists: $np")
                        store.renameFolderPath(path, np)
                    }
                }
                return nodeOf(id, false)
            }
            val i = store.items.indexOfFirst { it.id == id }
            if (i < 0) throw IllegalArgumentException("Bookmark not found: $id")
            var f = store.items[i]
            if (ch.has("title")) f = f.copy(name = ch.getString("title"))
            if (ch.has("url") && !ch.isNull("url")) f = f.copy(url = ch.getString("url"))
            store.items[i] = f
            store.commitSyncChanges()
            return nodeOf(id, false)
        }

        private fun move(id: String, dest: JSONObject): JSONObject {
            if (id == ROOT || roots.containsKey(id)) throw IllegalArgumentException("Bookmark root cannot be moved")
            store.knownFolders()
            val index = if (dest.has("index") && !dest.isNull("index")) dest.getInt("index") else null
            val path = store.folderPath(id)
            if (path != null) {
                val newParent = if (dest.has("parentId")) (containerOf(dest.getString("parentId")) ?: throw IllegalArgumentException("Folder not found")) else parentContainer(path)
                if (newParent == path || newParent.startsWith("$path/")) throw IllegalArgumentException("Cannot move a folder into itself")
                val np = pathIn(newParent, path.substringAfterLast('/'))
                if (np != path) {
                    if (store.folderId(np) != null) throw IllegalArgumentException("Folder already exists: $np")
                    store.renameFolderPath(path, np)
                }
                if (index != null) {
                    val sibs = childFolders(newParent)
                    val at = index.coerceIn(0, sibs.size)
                    if (at < sibs.size && sibs[at] != np) store.reorderFolder(np, store.knownFolders().indexOf(sibs[at]))
                }
                return nodeOf(id, false)
            }
            val f = store.items.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Bookmark not found: $id")
            val target = if (dest.has("parentId")) (containerOf(dest.getString("parentId")) ?: throw IllegalArgumentException("Folder not found")) else group(f)
            placeBookmark(f, target, index)
            store.commitSyncChanges()
            return nodeOf(id, false)
        }

        private fun remove(id: String, tree: Boolean): Any {
            if (id == ROOT || roots.containsKey(id)) throw IllegalArgumentException("Bookmark root cannot be removed")
            store.knownFolders()
            val path = store.folderPath(id)
            if (path != null) {
                if (!tree && (childFolders(path).isNotEmpty() || childBookmarks(path).isNotEmpty())) {
                    throw IllegalArgumentException("Cannot remove a non-empty folder")
                }
                store.removeFolderTree(path)
            } else {
                val i = store.items.indexOfFirst { it.id == id }
                if (i < 0) throw IllegalArgumentException("Bookmark not found: $id")
                store.items.removeAt(i)
                store.commitSyncChanges()
            }
            return JSONObject()
        }
    }
}
