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
 * GeckoView 没有 browser.bookmarks。用户自己安装 Floccus 时，[FloccusPatcher] 会在安装前给 xpi 加上
 * mafari-bookmarks-shim.js，由它补上 browser.bookmarks，调用最终落到这里，读写应用自己的书签（[FavoritesStore]）。应用本身不内置 Floccus。
 *
 * 通道（重要）：Floccus 是普通安装的拓展，GeckoView 在 Android 上只给内置特权拓展开放
 * runtime.sendNativeMessage / connectNative，Floccus 自己拿不到这个 API，所以不能直连这里。实际路径：
 *   Floccus（后台页 / 设置页 / 弹窗） --runtime.sendMessage("gm@mafari.dev")-->
 *   内置拓展的后台页 onMessageExternal（只认 sender.id == Floccus，且只放行书签操作） --sendNativeMessage("mafariBookmarks")-->
 *   这里（扩展级委托，挂在内置拓展 [GeckoGm.EXT_ID] 上）
 *
 * 书签树的映射（FavoritesStore 用带稳定 ID 的路径注册表表示任意层级目录；书签归属完整目录路径）：
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
    private const val NATIVE_APP = "mafariBookmarks"

    /**
     * Floccus 的分隔线：Firefox 里是 type=separator 的节点（没有 url / 标题），Floccus 把它们翻译成
     * https://separator.floccus.org/ 的书签再同步。应用没有「分隔线」这种项，所以存成这个网址的占位书签，
     * 对 Floccus 仍以 type=separator 的节点呈现，位置能原样往返，不会被误当成文件夹或书签。
     */
    private const val SEPARATOR_URL = "https://separator.floccus.org/"
    private const val SEPARATOR_TITLE = "⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯"

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

    private val main = Handler(Looper.getMainLooper())

    /**
     * 给内置拓展 [GeckoGm.EXT_ID] 挂上书签消息的扩展级委托（它的后台页会把 Floccus 的书签调用转到这里）。
     * 内置拓展就绪时由 [GeckoGm] 调用；幂等，每次启动都要重新挂。主线程。
     */
    fun attach(context: Context, ext: WebExtension) {
        if (ext.id != GeckoGm.EXT_ID) return
        ext.setMessageDelegate(delegate(context.applicationContext), NATIVE_APP)
        Diag.log("bookmarks: bridge attached to ${ext.id}")
    }

    private fun delegate(app: Context) = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val result = GeckoResult<Any>()
            // 只认内置拓展自己的后台页（Floccus 的身份由它在转发前校验）
            val senderId = sender.webExtension?.id
            if (senderId != null && senderId != GeckoGm.EXT_ID) {
                result.complete(JSONObject().put("error", "Unauthorized sender").toString())
                return result
            }
            val msg = message as? JSONObject
            val op = msg?.optString("op").orEmpty()
            // 整树 / 子树读取是选文件夹、每轮同步的入口，留一条日志方便确认消息有没有送到
            if (op == "getTree" || op == "getSubTree") Diag.log("bookmarks: $op")
            // 内置拓展后台页回报的 JS 侧诊断（拿不到 JS 控制台，靠这条进 diag.txt）
            if (op == "log") {
                Diag.log("bookmarks(js): " + (msg?.optJSONArray("args")?.optString(0).orEmpty()))
                result.complete(JSONObject().toString())
                return result
            }
            // FavoritesStore 里是 Compose 的状态列表：统一切到主线程读写，也顺带串行化了所有调用
            main.post {
                val out = JSONObject()
                try {
                    val store = FavoritesStore.current ?: FavoritesStore(app)
                    val args = msg?.optJSONArray("args") ?: JSONArray()
                    out.put("result", Ops(store).run(op, args))
                } catch (t: Throwable) {
                    Diag.log("bookmarks: $op failed: $t")
                    out.put("error", t.message ?: t.toString())
                }
                // 响应用 JSON 字符串：GeckoView 对 GeckoResult<Any> 里直接放 JSONObject 的序列化不可靠，字符串一定能过
                val txt = out.toString()
                Diag.log("bookmarks: $op replied ${txt.length} chars" + (if (out.has("error")) " (error)" else ""))
                result.complete(txt)
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
            // browser.bookmarks.search 同时可命中文件夹和书签；Floccus 会用标题搜索目录，
            // 只返回书签会让多级目录在增量同步时被误判为不存在。
            store.knownFolders().filter { it !in rootGroups && it != Favorite.GROUP_PERSONAL }.forEach { path ->
                val folderTitle = path.substringAfterLast('/')
                val matches = url == null && (title == null || folderTitle == title) &&
                    (query == null || folderTitle.contains(query, true))
                if (matches) store.folderId(path)?.let { out.put(nodeOf(it, false)) }
            }
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
                val path = pathIn(container, title.ifBlank { "新文件夹" })
                if (store.folderId(path) != null || store.items.any { group(it) == path }) {
                    throw IllegalArgumentException("Folder already exists: $path")
                }
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
