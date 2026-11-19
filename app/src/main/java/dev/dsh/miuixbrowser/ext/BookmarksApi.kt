package dev.dsh.miuixbrowser.ext

import android.content.Context
import dev.dsh.miuixbrowser.appContainer
import dev.dsh.miuixbrowser.data.Favorite
import dev.dsh.miuixbrowser.data.FavoritesStore
import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Firefox-compatible bookmarks API backed by Mafari's existing FavoritesStore. */
class BookmarksApi(context: Context, private val events: EventSink) {
    private val store by lazy { context.applicationContext.appContainer.favorites }

    fun register(router: ApiRouter) {
        fun handler(method: String): ApiHandler = ApiHandler { ctx, args ->
            if (ctx.sender.kind != SenderKind.EXTENSION_PAGE) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "bookmarks API 只能从拓展页面调用")
            }
            // FavoritesStore.items 是 Compose SnapshotStateList；沿用 Gecko bridge 的约束，在主线程串行读写。
            withContext(Dispatchers.Main.immediate) {
                val ops = Ops(store)
                val mutating = method == "create" || method == "update" || method == "move" || method == "remove" || method == "removeTree"
                val before = if (mutating) ops.snapshot() else emptyMap()
                val result = ops.run(method, args)
                if (mutating) emitChanges(ctx.ext.spec.id, method, args, before, ops.snapshot())
                result
            }
        }
        router.register("bookmarks.getTree", handler("getTree"))
        router.register("bookmarks.getSubTree", handler("getSubTree"))
        router.register("bookmarks.get", handler("get"))
        router.register("bookmarks.getChildren", handler("getChildren"))
        router.register("bookmarks.search", handler("search"))
        router.register("bookmarks.create", handler("create"))
        router.register("bookmarks.update", handler("update"))
        router.register("bookmarks.move", handler("move"))
        router.register("bookmarks.remove", handler("remove"))
        router.register("bookmarks.removeTree", handler("removeTree"))
    }

    private fun emitChanges(extId: String, method: String, args: JSONArray, before: Map<String, JSONObject>, after: Map<String, JSONObject>) {
        when (method) {
            "create" -> (after.keys - before.keys).forEach { id ->
                after[id]?.let { events.emit(extId, "bookmarks.onCreated", JSONObject().put("id", id).put("bookmark", it)) }
            }
            "update" -> listOfNotNull(args.optString(0).takeIf { it.isNotEmpty() }).forEach { id ->
                val old = before[id] ?: return@forEach
                val now = after[id] ?: return@forEach
                val info = JSONObject()
                if (old.optString("title") != now.optString("title")) info.put("title", now.optString("title"))
                if (old.optString("url") != now.optString("url")) info.put("url", now.optString("url"))
                if (info.length() > 0) events.emit(extId, "bookmarks.onChanged", JSONObject().put("id", id).put("changeInfo", info))
            }
            "move" -> listOfNotNull(args.optString(0).takeIf { it.isNotEmpty() }).forEach { id ->
                val old = before[id] ?: return@forEach
                val now = after[id] ?: return@forEach
                if (old.optString("parentId") != now.optString("parentId") || old.optInt("index") != now.optInt("index")) {
                    val info = JSONObject().put("parentId", now.optString("parentId"))
                        .put("index", now.optInt("index"))
                        .put("oldParentId", old.optString("parentId"))
                        .put("oldIndex", old.optInt("index"))
                    events.emit(extId, "bookmarks.onMoved", JSONObject().put("id", id).put("moveInfo", info))
                }
            }
            "remove", "removeTree" -> {
                val removed = before.keys - after.keys
                removed.filter { id -> before[id]?.optString("parentId") !in removed }.forEach { id ->
                    val old = before[id] ?: return@forEach
                    events.emit(extId, "bookmarks.onRemoved", JSONObject().put("id", id).put("removeInfo", JSONObject()
                        .put("parentId", old.optString("parentId")).put("index", old.optInt("index")).put("node", old)))
                }
            }
        }
    }

    private class Ops(val store: FavoritesStore) {
        private val SEPARATOR_URL = "https://separator.floccus.org/"
        private val SEPARATOR_TITLE = "⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯"
        private val ROOT = "root________"
        private val MENU = "menu________"
        private val TOOLBAR = "toolbar_____"
        private val UNFILED = "unfiled_____"
        private val MOBILE = "mobile______"
        private val roots = linkedMapOf(
            TOOLBAR to Pair("书签栏", "Bookmarks Toolbar"),
            MENU to Pair(Favorite.GROUP_PERSONAL, "Bookmarks Menu"),
            UNFILED to Pair("其他书签", "Other Bookmarks"),
            MOBILE to Pair("移动设备书签", "Mobile Bookmarks"),
        )
        private val rootGroups = roots.values.map { it.first }.toSet()
        fun snapshot(): Map<String, JSONObject> {
            val out = LinkedHashMap<String, JSONObject>()
            fun walk(n: JSONObject) {
                val id = n.optString("id")
                if (id.isNotEmpty()) out[id] = JSONObject(n.toString())
                n.optJSONArray("children")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::walk) }
            }
            val tree = rootNode(true)
            walk(tree)
            return out
        }

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

        private fun isSeparator(f: Favorite) = f.url.startsWith(SEPARATOR_URL)

        private fun bookmarkNode(f: Favorite, parentId: String, index: Int): JSONObject {
            val o = JSONObject().put("id", f.id).put("parentId", parentId).put("index", index).put("dateAdded", 0)
            // 与 Firefox 一致：分隔线是没有 url 的 separator 节点，标题为空
            return if (isSeparator(f)) o.put("title", "").put("type", "separator")
            else o.put("title", f.name).put("url", f.url).put("type", "bookmark")
        }

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
            val isSep = o.optString("type") == "separator"
            val url = if (isSep) SEPARATOR_URL
            else o.optString("url").takeIf { o.has("url") && !o.isNull("url") && it.isNotEmpty() }
            val title = if (isSep) SEPARATOR_TITLE else o.optString("title")
            return if (url != null) {
                val f = Favorite(name = title.ifBlank { url }, url = url, group = container)
                placeBookmark(f, container, index)
                store.commitSyncChanges()
                nodeOf(f.id, false)
            } else {
                val path = pathIn(container, title.ifBlank { tr("新文件夹") })
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
