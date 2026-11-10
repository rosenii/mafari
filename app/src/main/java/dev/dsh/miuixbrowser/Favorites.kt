package dev.dsh.miuixbrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** 首页收藏最多显示的个数；其余只出现在书签页里。 */
const val HOME_FAV_MAX = 12

data class Favorite(
    val name: String,
    val url: String,
    /** 书签分组；「个人收藏」为默认组。 */
    val group: String = GROUP_PERSONAL,
) {
    companion object {
        const val GROUP_PERSONAL = "个人收藏"
    }
}

/** 主页收藏（书签）的增删改、分组 + 本地持久化。 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("mafari", Context.MODE_PRIVATE)

    val items = mutableStateListOf<Favorite>().apply {
        addAll(load())
    }

    private fun load(): List<Favorite> {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        Favorite(
                            name = obj.optString("name"),
                            url = obj.optString("url"),
                            group = obj.optString("group").ifBlank { Favorite.GROUP_PERSONAL },
                        ),
                    )
                }
            }
        }.getOrDefault(defaults())
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
        if (Favorite.GROUP_PERSONAL !in seen) seen.add(Favorite.GROUP_PERSONAL)
        return seen.toList()
    }

    fun itemsIn(group: String): List<Favorite> =
        items.filter { (it.group.ifBlank { Favorite.GROUP_PERSONAL }) == group }

    /**
     * 首页收藏：只取「个人收藏」分组的书签，最多 [HOME_FAV_MAX] 个。
     * 返回 (在总列表 [items] 里的下标, 书签)，首页的删除 / 排序 / 编辑都靠这个下标回到总列表。
     */
    fun homeEntries(): List<Pair<Int, Favorite>> =
        items.withIndex()
            .filter { (_, f) -> f.group.ifBlank { Favorite.GROUP_PERSONAL } == Favorite.GROUP_PERSONAL }
            .take(HOME_FAV_MAX)
            .map { it.index to it.value }

    /** 「个人收藏」是系统保留分组：不能删除。 */
    fun isProtectedGroup(group: String): Boolean =
        group.ifBlank { Favorite.GROUP_PERSONAL } == Favorite.GROUP_PERSONAL

    /** 删除一个分组及其中的全部书签；「个人收藏」拒绝删除，返回 false。 */
    fun removeGroup(group: String): Boolean {
        if (isProtectedGroup(group)) return false
        val removed = items.removeAll { it.group.ifBlank { Favorite.GROUP_PERSONAL } == group }
        if (removed) persist()
        return removed
    }

    /** 重命名分组：「个人收藏」不可改名；新名字为空返回 false；与已有分组同名则合并。 */
    fun renameGroup(old: String, newName: String): Boolean {
        val target = newName.trim()
        if (target.isEmpty() || isProtectedGroup(old)) return false
        if (target == old) return true
        var changed = false
        for (i in items.indices) {
            val f = items[i]
            if (f.group.ifBlank { Favorite.GROUP_PERSONAL } == old) {
                items[i] = f.copy(group = target)
                changed = true
            }
        }
        if (changed) persist()
        return changed
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
        )
        persist()
    }

    /** 拖动排序：把 from 位置的项移到 to 位置（首页只显示前 [HOME_FAV_MAX] 项，下标与总列表一致）。 */
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

    fun removeAtUrl(url: String) {
        val i = items.indexOfFirst { it.url == url }
        if (i >= 0) remove(i)
    }

    fun reset() {
        items.clear()
        items.addAll(defaults())
        persist()
    }

    /** 导入结果：[found] 为文件里识别到的书签数，[added] 为实际新增数（其余是已存在的重复项）。 */
    data class ImportResult(val found: Int, val added: Int)

    /** 兼容旧调用：返回新增条数。 */
    fun importNetscapeHtml(html: String): Int = importBookmarks(html).added

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

    /** 导出为 Chrome / Firefox 可导入的 Netscape Bookmark HTML。 */
    fun exportNetscapeHtml(): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        sb.appendLine("<!-- This is an automatically generated file. -->")
        sb.appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        sb.appendLine("<TITLE>Bookmarks</TITLE>")
        sb.appendLine("<H1>Bookmarks</H1>")
        sb.appendLine("<DL><p>")
        groups().forEach { g ->
            sb.appendLine("    <DT><H3>${escapeHtml(g)}</H3>")
            sb.appendLine("    <DL><p>")
            itemsIn(g).forEach { f ->
                sb.appendLine("        <DT><A HREF=\"${escapeHtml(f.url)}\">${escapeHtml(f.name)}</A>")
            }
            sb.appendLine("    </DL><p>")
        }
        sb.appendLine("</DL><p>")
        return sb.toString()
    }

    private fun persist() {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("group", it.group),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

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

    private companion object {
        const val KEY = "favorites"
    }
}
