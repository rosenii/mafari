package dev.dsh.miuixbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject

/** 首页收藏最多显示的个数；其余只出现在书签页里。 */

data class Favorite(
    val name: String,
    val url: String,
    /** 书签分组；「个人收藏」为默认组。 */
    val group: String = GROUP_PERSONAL,
    /** 稳定 id：供 Floccus 书签同步识别同一条书签（旧数据首次读取时补上并落盘）。 */
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    companion object {
        const val GROUP_PERSONAL = "个人收藏"
    }
}

/**
 * 主页收藏（书签）的增删改、分组 + 本地持久化。
 *
 * 存储在 SQLCipher 整库加密的 history.db 里（见 [BookmarkDb]）；内存模型（[items]、文件夹注册表）和对外接口不变，
 * Floccus 书签桥（[BookmarksBridge]）照旧直接改 [items] 再 [commitSyncChanges]。
 * 加密库打不开时退回旧的 SharedPreferences 密文存储，书签不丢。
 */
class FavoritesStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("mafari", Context.MODE_PRIVATE)

    /** 加密库；打不开为 null。下面这些属性必须声明在 items 之前（属性按声明顺序初始化，load() 会用到）。 */
    private val db: SQLiteDatabase? = HistoryDb.get(appContext)

    /** 本次运行是否用加密库存书签；读库出错会置 false，退回旧存储。 */
    private var useDb = db != null

    /** load() 里发现旧数据缺 id / id 重复时置位。 */
    private var needsPersist = false

    // 写库是「整表快照」，Floccus 同步时一连串操作会反复 persist：延后一小会儿合并成一次
    private val main = Handler(Looper.getMainLooper())
    private var dirtyBookmarks = false
    private var dirtyFolders = false
    private var firstDirtyAt = 0L
    private val flushTask = Runnable { flush() }

    val items = mutableStateListOf<Favorite>().apply {
        addAll(load())
    }

    /** 文件夹注册表（有序）：路径 → 稳定 id。空文件夹、文件夹顺序、重命名后 id 不变都靠它。 */
    private val folderOrder = mutableListOf<String>()
    private val folderIdByPath = HashMap<String, String>()

    init {
        current = this
        loadFolders()
        if (needsPersist) persist()
        if (!useDb) {
            // 旧版本的明文书签 / 旧算法套件的密文：读得出来就立即以当前套件重新加密一次（读不出来绝不覆盖）
            val raw = prefs.getString(KEY, null)
            if (raw != null && SecureStore.needsUpgrade(raw) && SecureStore.read(appContext, raw, PURPOSE) != null) persist()
        }
    }

    private fun load(): List<Favorite> {
        val database = db
        if (database != null) {
            try {
                // 旧存储里还有书签（首次升级 / 备份恢复 / 加密库不可用期间写过）：它优先，整体导入后清掉
                importLegacyBookmarks(database)?.let { return it }
                if (BookmarkDb.isInitialized(database)) {
                    val loaded = BookmarkDb.loadBookmarks(database)
                    if (loaded.changed) needsPersist = true
                    return loaded.items
                }
                return defaults()
            } catch (t: Throwable) {
                Diag.log("bookmarks: encrypted db unavailable, using prefs: ${t.message}")
                useDb = false
            }
        }
        return loadLegacy()
    }

    /**
     * 旧版把书签整份加密存在 SharedPreferences「mafari」的 favorites 里。读得出来就在一个事务里导入加密库，
     * 成功后删掉旧值；读不出来（密钥丢了）不动它，也不覆盖库里已有的。没有旧值返回 null。
     */
    private fun importLegacyBookmarks(database: SQLiteDatabase): List<Favorite>? {
        val stored = prefs.getString(KEY, null) ?: return null
        val raw = SecureStore.read(appContext, stored, PURPOSE) ?: return null
        val items = BookmarkDb.dedupeIds(BookmarkDb.parseItems(raw)).items
        BookmarkDb.saveBookmarks(database, items)
        prefs.edit().remove(KEY).commit()
        return items
    }

    /** 同上，文件夹注册表（favorites_folders）。 */
    private fun importLegacyFolders(database: SQLiteDatabase) {
        val stored = prefs.getString(KEY_FOLDERS, null) ?: return
        val raw = SecureStore.read(appContext, stored, PURPOSE) ?: return
        BookmarkDb.saveFolders(database, BookmarkDb.parseFolders(raw))
        prefs.edit().remove(KEY_FOLDERS).commit()
    }

    private fun loadLegacy(): List<Favorite> {
        val stored = prefs.getString(KEY, null) ?: return defaults()
        val raw = SecureStore.read(appContext, stored, PURPOSE) ?: return defaults()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val storedId = obj.optString("id")
                    if (storedId.isBlank()) needsPersist = true
                    add(
                        Favorite(
                            name = obj.optString("name"),
                            url = obj.optString("url"),
                            group = obj.optString("group").ifBlank { Favorite.GROUP_PERSONAL },
                            id = storedId.ifBlank { java.util.UUID.randomUUID().toString() },
                        ),
                    )
                }
            }
        }.getOrDefault(defaults())
    }

    private fun loadFolders() {
        val list: List<Pair<String, String>> = if (useDb) {
            try {
                val database = db!!
                importLegacyFolders(database)
                BookmarkDb.loadFolders(database)
            } catch (t: Throwable) {
                Diag.log("bookmarks: folders from encrypted db failed, using prefs: ${t.message}")
                useDb = false
                loadFoldersLegacy()
            }
        } else {
            loadFoldersLegacy()
        }
        for ((path, id) in list) {
            if (path.isNotBlank() && id.isNotBlank() && path !in folderIdByPath) {
                folderOrder.add(path)
                folderIdByPath[path] = id
            }
        }
    }

    private fun loadFoldersLegacy(): List<Pair<String, String>> {
        val stored = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        val raw = SecureStore.read(appContext, stored, PURPOSE) ?: return emptyList()
        return runCatching { BookmarkDb.parseFolders(raw) }.getOrDefault(emptyList())
    }

    private fun persistFolders() {
        if (useDb) {
            scheduleSave(bookmarks = false)
            return
        }
        val arr = JSONArray()
        folderOrder.forEach { arr.put(JSONObject().put("path", it).put("id", folderIdByPath[it])) }
        val sealed = SecureStore.write(appContext, arr.toString(), PURPOSE) ?: return
        prefs.edit().putString(KEY_FOLDERS, sealed).apply()
    }

    private fun defaults(): List<Favorite> = listOf(
        Favorite("百度", "https://www.baidu.com"),
        Favorite("知乎", "https://www.zhihu.com"),
        Favorite("B站", "https://www.bilibili.com"),
        Favorite("GitHub", "https://github.com"),
        Favorite("微博", "https://weibo.com"),
        Favorite("淘宝", "https://www.taobao.com"),
    )

    /** 按出现顺序返回全部分组名。 */
    fun groups(): List<String> {
        val seen = linkedSetOf<String>()
        items.forEach { seen.add(it.group.ifBlank { Favorite.GROUP_PERSONAL }) }
        folderOrder.forEach { seen.add(it) }
        if (Favorite.GROUP_PERSONAL !in seen) seen.add(Favorite.GROUP_PERSONAL)
        return seen.toList()
    }

    fun itemsIn(group: String): List<Favorite> =
        items.filter { (it.group.ifBlank { Favorite.GROUP_PERSONAL }) == group }

    /**
     * 首页收藏：只取「个人收藏」分组的书签（全部返回，首页按「定制主页」里选的行数截取）。
     * 返回 (在总列表 [items] 里的下标, 书签)，首页的删除 / 排序 / 编辑都靠这个下标回到总列表。
     */
    fun homeEntries(): List<Pair<Int, Favorite>> =
        items.withIndex()
            .filter { (_, f) -> f.group.ifBlank { Favorite.GROUP_PERSONAL } == Favorite.GROUP_PERSONAL }
            .map { it.index to it.value }

    /** 「个人收藏」是系统保留分组：不能删除。 */
    fun isProtectedGroup(group: String): Boolean =
        group.ifBlank { Favorite.GROUP_PERSONAL } == Favorite.GROUP_PERSONAL

    /** [path] 本身或位于其下（以 / 分层）的分组。 */
    private fun inFolderTree(p: String, root: String) = p == root || p.startsWith("$root/")

    /** 某个分组及其全部子分组里的书签（按总列表顺序）。 */
    fun itemsInTree(group: String): List<Favorite> =
        items.filter { inFolderTree(it.group.ifBlank { Favorite.GROUP_PERSONAL }, group) }

    /** 删除一个分组（含全部子分组）及其中的全部书签；「个人收藏」拒绝删除，返回 false。 */
    fun removeGroup(group: String): Boolean {
        if (isProtectedGroup(group)) return false
        val removed = items.removeAll { inFolderTree(it.group.ifBlank { Favorite.GROUP_PERSONAL }, group) }
        val hadFolder = folderOrder.removeAll { inFolderTree(it, group) }
        folderIdByPath.keys.removeAll { inFolderTree(it, group) }
        if (hadFolder) persistFolders()
        if (removed) persist()
        return removed || hadFolder
    }

    /** 重命名 / 移动分组：连同全部子分组一起改前缀；「个人收藏」不可改名；新名字为空返回 false；与已有分组同名则合并。 */
    fun renameGroup(old: String, newName: String): Boolean {
        val target = newName.trim()
        if (target.isEmpty() || isProtectedGroup(old)) return false
        if (target == old) return true
        val touched = items.any { inFolderTree(it.group.ifBlank { Favorite.GROUP_PERSONAL }, old) } ||
            folderOrder.any { inFolderTree(it, old) }
        if (!touched) return false
        renameFolderPath(old, target)
        return true
    }

    // ------------------------------------------------------------------ 书签同步（Floccus）用的结构接口

    /** 所有已知文件夹路径（含祖先、含注册表里的空文件夹），按注册表顺序；顺带把新发现的登记进注册表。 */
    fun knownFolders(): List<String> {
        var dirty = false
        fun reg(path: String) {
            if (path.isBlank() || path in folderIdByPath) return
            folderOrder.add(path)
            folderIdByPath[path] = java.util.UUID.randomUUID().toString()
            dirty = true
        }
        fun regWithAncestors(path: String) {
            var cur = path
            val chain = ArrayList<String>()
            while (cur.isNotEmpty()) {
                chain.add(cur)
                cur = if (cur.contains('/')) cur.substringBeforeLast('/') else ""
            }
            chain.asReversed().forEach { reg(it) }
        }
        items.map { it.group.ifBlank { Favorite.GROUP_PERSONAL } }.distinct().forEach { regWithAncestors(it) }
        folderOrder.toList().forEach { regWithAncestors(it) }
        if (dirty) persistFolders()
        return folderOrder.toList()
    }

    fun folderId(path: String): String? = folderIdByPath[path]

    fun folderPath(id: String): String? = folderIdByPath.entries.firstOrNull { it.value == id }?.key

    /** 登记一个文件夹（已存在则返回原 id），[index] 为它在 [folderOrder] 里的位置（null = 末尾）。 */
    fun registerFolder(path: String, index: Int? = null): String {
        val existing = folderIdByPath[path]
        if (existing != null) return existing
        val id = java.util.UUID.randomUUID().toString()
        folderIdByPath[path] = id
        if (index != null && index in 0..folderOrder.size) folderOrder.add(index, path) else folderOrder.add(path)
        persistFolders()
        return id
    }

    /** 把 [path] 在注册表里挪到第 [index] 位（下标按注册表整体计）。 */
    fun reorderFolder(path: String, index: Int) {
        if (!folderOrder.remove(path)) return
        folderOrder.add(index.coerceIn(0, folderOrder.size), path)
        persistFolders()
    }

    /** 文件夹改名 / 搬家：[old] 及其全部子路径换成 [new] 前缀，id 不变，其中的书签跟着走。 */
    fun renameFolderPath(old: String, new: String) {
        if (old == new) return
        fun map(p: String) = when {
            p == old -> new
            p.startsWith("$old/") -> new + p.substring(old.length)
            else -> p
        }
        val newOrder = folderOrder.map { map(it) }.distinct()
        val newIds = HashMap<String, String>()
        folderOrder.forEach { newIds[map(it)] = folderIdByPath[it]!! }
        folderOrder.clear(); folderOrder.addAll(newOrder)
        folderIdByPath.clear(); folderIdByPath.putAll(newIds)
        for (i in items.indices) {
            val g = items[i].group.ifBlank { Favorite.GROUP_PERSONAL }
            val m = map(g)
            if (m != g) items[i] = items[i].copy(group = m)
        }
        persistFolders()
        persist()
    }

    /** 删除文件夹 [path] 及其下全部子文件夹和书签。 */
    fun removeFolderTree(path: String) {
        fun inTree(p: String) = p == path || p.startsWith("$path/")
        folderOrder.removeAll { inTree(it) }
        folderIdByPath.keys.removeAll { inTree(it) }
        items.removeAll { inTree(it.group.ifBlank { Favorite.GROUP_PERSONAL }) }
        persistFolders()
        persist()
    }

    /** 同步接口改完 [items] 后调用落盘。 */
    fun commitSyncChanges() = persist()

    /** 是否已有同名或同地址（忽略末尾斜杠）的书签 */
    fun exists(name: String, url: String): Boolean {
        fun norm(u: String) = toUrl(u).trimEnd('/').lowercase()
        val n = name.trim()
        val u = norm(url)
        return items.any { (n.isNotEmpty() && it.name.trim() == n) || norm(it.url) == u }
    }

    fun add(name: String, url: String, group: String = Favorite.GROUP_PERSONAL) {
        if (url.isBlank()) return
        val fixed = toUrl(url)
        items.add(
            Favorite(
                name = name.ifBlank { hostOf(fixed) },
                url = fixed,
                group = group.ifBlank { Favorite.GROUP_PERSONAL },
            ),
        )
        persist()
    }

    fun update(index: Int, name: String, url: String, group: String = items.getOrNull(index)?.group ?: Favorite.GROUP_PERSONAL) {
        if (index !in items.indices || url.isBlank()) return
        val fixed = toUrl(url)
        items[index] = Favorite(
            name = name.ifBlank { hostOf(fixed) },
            url = fixed,
            group = group.ifBlank { Favorite.GROUP_PERSONAL },
            id = items[index].id,
        )
        persist()
    }

    /** 拖动排序：把 from 位置的项移到 to 位置（首页只显示靠前的若干项，下标与总列表一致）。 */
    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        items.add(to, items.removeAt(from))
        persist()
    }

    fun remove(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            persist()
        }
    }


    fun reset() {
        items.clear()
        items.addAll(defaults())
        persist()
    }

    /** 导入结果：[found] 为文件里识别到的书签数，[added] 为实际新增数（其余是已存在的重复项）。 */
    data class ImportResult(val found: Int, val added: Int)


    /**
     * 导入书签文本。支持：
     * - Chrome / Edge / Firefox / Safari 导出的 Netscape Bookmark HTML；
     * - Firefox 备份 JSON、Chromium 的 Bookmarks JSON；
     * - 纯文本里的 http(s) 链接（兜底）。
     */
    fun importBookmarks(text: String): ImportResult {
        val body = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (body.isEmpty()) return ImportResult(0, 0)
        var parsed: List<Triple<String, String, String>> = emptyList() // 名称、网址、分组
        if (body.startsWith("{") || body.startsWith("[")) {
            parsed = runCatching { parseJsonBookmarks(body) }.getOrDefault(emptyList())
        }
        if (parsed.isEmpty()) parsed = parseHtmlBookmarks(body)
        if (parsed.isEmpty()) {
            parsed = Regex("""https?://[^\s"'<>]+""").findAll(body).map {
                val u = it.value
                Triple(hostOf(u), u, Favorite.GROUP_PERSONAL)
            }.toList()
        }
        var added = 0
        parsed.forEach { (title, href, folder) ->
            val group = folder.ifBlank { Favorite.GROUP_PERSONAL }
            val fixed = toUrl(href)
            // 去重：同 URL 同组跳过
            val exists = items.any { it.url == fixed && (it.group.ifBlank { Favorite.GROUP_PERSONAL }) == group }
            if (!exists) {
                items.add(Favorite(name = title.ifBlank { hostOf(fixed) }, url = fixed, group = group))
                added++
            }
        }
        if (added > 0) persist()
        return ImportResult(parsed.size, added)
    }

    private fun skipUrl(href: String): Boolean {
        val h = href.trim()
        return h.isEmpty() || h.startsWith("javascript:", true) || h.startsWith("place:", true) || h == "#"
    }

    /**
     * 手写的 Netscape HTML 扫描（不用大正则，避免大文件栈溢出 / 回溯卡死）：
     * H3 是文件夹名，紧随其后的 DL 开启该文件夹，/DL 结束；书签归入最内层有名字的文件夹。
     * href 兼容双引号 / 单引号 / 无引号，标签与属性名不区分大小写。
     */
    private fun parseHtmlBookmarks(html: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val folders = ArrayList<String>()
        var pending = ""
        val n = html.length
        var i = 0
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) break
            if (html.startsWith("<!--", lt)) {
                val e = html.indexOf("-->", lt + 4)
                i = if (e < 0) n else e + 3
                continue
            }
            val gt = html.indexOf('>', lt)
            if (gt < 0) break
            val tag = html.substring(lt + 1, gt)
            i = gt + 1
            val closing = tag.startsWith("/")
            val name = tag.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase()
            when (name) {
                "h3" -> if (!closing) {
                    val end = html.indexOf("</h3", i, ignoreCase = true).let { if (it < 0) n else it }
                    pending = cleanText(html.substring(i, end))
                    i = end
                }
                "dl" -> if (closing) {
                    if (folders.isNotEmpty()) folders.removeAt(folders.lastIndex)
                } else {
                    folders.add(pending)
                    pending = ""
                }
                "a" -> if (!closing) {
                    val href = unescapeHtml(attrValue(tag, "href")).trim()
                    val end = html.indexOf("</a", i, ignoreCase = true)
                    val title = if (end < 0) "" else cleanText(html.substring(i, end))
                    if (end >= 0) i = end
                    if (!skipUrl(href)) {
                        out.add(Triple(title, href, folders.lastOrNull { it.isNotBlank() }.orEmpty()))
                    }
                }
            }
        }
        return out
    }

    private fun attrValue(tag: String, attr: String): String {
        var from = 0
        while (true) {
            val p = tag.indexOf(attr, from, ignoreCase = true)
            if (p < 0) return ""
            from = p + attr.length
            if (p > 0 && !tag[p - 1].isWhitespace()) continue
            var q = from
            while (q < tag.length && tag[q].isWhitespace()) q++
            if (q >= tag.length || tag[q] != '=') continue
            q++
            while (q < tag.length && tag[q].isWhitespace()) q++
            if (q >= tag.length) return ""
            val c = tag[q]
            return if (c == '"' || c == '\'') {
                val e = tag.indexOf(c, q + 1)
                tag.substring(q + 1, if (e < 0) tag.length else e)
            } else {
                tag.substring(q).takeWhile { !it.isWhitespace() }
            }
        }
    }

    private fun cleanText(raw: String): String =
        unescapeHtml(raw.replace(Regex("<[^>]*>"), "")).trim()

    /** Firefox 备份（title / uri / children）与 Chromium Bookmarks（name / url / children）。 */
    private fun parseJsonBookmarks(text: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        fun folderTitle(o: JSONObject): String {
            val t = o.optString("title").ifBlank { o.optString("name") }
            return when (t) {
                "bookmarksMenu" -> "书签菜单"
                "bookmarksToolbar" -> "书签工具栏"
                "unfiledBookmarks" -> "其他书签"
                "mobileBookmarks" -> "移动设备书签"
                else -> t
            }
        }
        fun walk(node: Any?, folder: String) {
            when (node) {
                is JSONArray -> for (k in 0 until node.length()) walk(node.opt(k), folder)
                is JSONObject -> {
                    val url = node.optString("uri").ifBlank { node.optString("url") }
                    if (url.isNotBlank() && node.optJSONArray("children") == null) {
                        if (!skipUrl(url)) {
                            out.add(Triple(node.optString("title").ifBlank { node.optString("name") }, url, folder))
                        }
                    } else {
                        val kids = node.optJSONArray("children")
                        if (kids != null) {
                            walk(kids, folderTitle(node).ifBlank { folder })
                        } else {
                            // Chromium 根：{"roots":{"bookmark_bar":{...},"other":{...}}}
                            val roots = node.optJSONObject("roots") ?: return
                            roots.keys().forEach { key -> walk(roots.opt(key), folder) }
                        }
                    }
                }
            }
        }
        walk(if (text.startsWith("[")) JSONArray(text) else JSONObject(text), "")
        return out
    }

    /**
     * 导出为 Chrome / Firefox 可导入的 Netscape Bookmark HTML。
     * 分组路径里的 / 还原成嵌套的 H3 + DL 层级（缺失的祖先自动补齐），子文件夹在前、本层书签在后。
     */
    fun exportNetscapeHtml(): String {
        // 父路径（顶层用 ""）→ 直接子文件夹的完整路径，顺序沿用 groups()
        val childrenOf = LinkedHashMap<String, MutableList<String>>()
        val seen = HashSet<String>()
        fun ensure(path: String) {
            if (path.isEmpty() || !seen.add(path)) return
            val parent = if (path.contains('/')) path.substringBeforeLast('/') else ""
            ensure(parent)
            childrenOf.getOrPut(parent) { mutableListOf() }.add(path)
        }
        groups().forEach { ensure(it) }

        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        sb.appendLine("<!-- This is an automatically generated file. -->")
        sb.appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        sb.appendLine("<TITLE>Bookmarks</TITLE>")
        sb.appendLine("<H1>Bookmarks</H1>")
        sb.appendLine("<DL><p>")
        fun writeFolder(path: String, depth: Int) {
            val pad = "    ".repeat(depth)
            val title = path.substringAfterLast('/').ifBlank { "未命名" }
            sb.appendLine("$pad<DT><H3>${escapeHtml(title)}</H3>")
            sb.appendLine("$pad<DL><p>")
            childrenOf[path]?.forEach { writeFolder(it, depth + 1) }
            itemsIn(path).forEach { f ->
                sb.appendLine("$pad    <DT><A HREF=\"${escapeHtml(f.url)}\">${escapeHtml(f.name)}</A>")
            }
            sb.appendLine("$pad</DL><p>")
        }
        childrenOf[""]?.forEach { writeFolder(it, 1) }
        sb.appendLine("</DL><p>")
        return sb.toString()
    }

    private fun persist() {
        if (useDb) {
            scheduleSave(bookmarks = true)
            return
        }
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("group", it.group)
                    .put("id", it.id),
            )
        }
        val sealed = SecureStore.write(appContext, array.toString(), PURPOSE) ?: return
        prefs.edit().putString(KEY, sealed).apply()
    }

    /** 标记有改动待写库：安静 [SAVE_DEBOUNCE_MS] 后写；一直有改动则最迟 [SAVE_MAX_WAIT_MS] 写一次。主线程。 */
    private fun scheduleSave(bookmarks: Boolean) {
        if (bookmarks) dirtyBookmarks = true else dirtyFolders = true
        val now = SystemClock.uptimeMillis()
        if (firstDirtyAt == 0L) firstDirtyAt = now
        main.removeCallbacks(flushTask)
        if (now - firstDirtyAt >= SAVE_MAX_WAIT_MS) flush() else main.postDelayed(flushTask, SAVE_DEBOUNCE_MS)
    }

    /**
     * 立即把待写的改动交给写库线程（在主线程取快照，写入在 [HistoryDb.io] 上按顺序执行、单事务）。
     * 应用退到后台时调用，免得最后一小段改动还在等延迟。主线程。
     */
    fun flush() {
        main.removeCallbacks(flushTask)
        firstDirtyAt = 0L
        val database = db
        if (!useDb || database == null) return
        val bookmarkSnapshot = if (dirtyBookmarks) items.toList() else null
        val folderSnapshot = if (dirtyFolders) folderSnapshot() else null
        dirtyBookmarks = false
        dirtyFolders = false
        if (bookmarkSnapshot == null && folderSnapshot == null) return
        HistoryDb.io.execute {
            runCatching {
                bookmarkSnapshot?.let { BookmarkDb.saveBookmarks(database, it) }
                folderSnapshot?.let { BookmarkDb.saveFolders(database, it) }
            }.onFailure { Diag.log("bookmarks: save failed: ${it.message}") }
        }
    }

    private fun folderSnapshot(): List<Pair<String, String>> =
        folderOrder.mapNotNull { path -> folderIdByPath[path]?.let { path to it } }

    /** 备份用：当前内存里的 (书签 JSON, 文件夹 JSON)，旧版明文格式。 */
    fun snapshotJson(): Pair<String, String> =
        BookmarkDb.itemsToJson(items.toList()) to BookmarkDb.foldersToJson(folderSnapshot())

    private fun unescapeHtml(s: String): String =
        if ('&' !in s) s else s
            .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m ->
                val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")

    private fun escapeHtml(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    companion object {
        /** 最近创建的实例：Floccus 书签桥靠它读写界面正在用的那份书签。 */
        @Volatile var current: FavoritesStore? = null
            private set

        /** 退到后台时调用：把还在等延迟的书签改动立刻交给写库线程。主线程。 */
        fun flushCurrent() {
            current?.flush()
        }

        private const val SAVE_DEBOUNCE_MS = 300L
        private const val SAVE_MAX_WAIT_MS = 2_000L

        private const val KEY_FOLDERS = "favorites_folders"
        private const val KEY = "favorites"
        /** 加密上下文（进 AAD）：密文只能在这个用途下解开 */
        private const val PURPOSE = SecureStore.PURPOSE_FAVORITES
    }
}
