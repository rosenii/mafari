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

    /**
     * 解析 Chrome / Firefox 导出的 Netscape Bookmark HTML。
     * 返回写入的条数；非法输入返回 0。
     */
    fun importNetscapeHtml(html: String): Int {
        if (html.isBlank() || !html.contains("HREF", ignoreCase = true)) return 0
        var added = 0
        val h3 = Regex("""<H3[^>]*>(.*?)</H3>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val anchor = Regex("""<A\s[^>]*HREF\s*=\s*"([^"]+)"[^>]*>(.*?)</A>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val tagStrip = Regex("""<[^>]+>""")
        // 按标签顺序线性扫描：H3 是文件夹名，紧随其后的 <DL> 开启该文件夹，</DL> 结束它；
        // 书签归入「最内层有名字的文件夹」，不在任何文件夹里的归入默认组
        val token = Regex(
            """(<H3[^>]*>.*?</H3>)|(<A\s[^>]*HREF\s*=\s*"[^"]+"[^>]*>.*?</A>)|(</DL\s*>)|(<DL[^>]*>)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val folders = ArrayList<String>()
        var pending = ""
        token.findAll(html).forEach { m ->
            val raw = m.value
            when {
                raw.startsWith("<H3", ignoreCase = true) ->
                    pending = unescapeHtml(h3.find(raw)?.groupValues?.get(1)?.replace(tagStrip, "")?.trim().orEmpty())
                raw.startsWith("</DL", ignoreCase = true) ->
                    if (folders.isNotEmpty()) folders.removeAt(folders.lastIndex)
                raw.startsWith("<DL", ignoreCase = true) -> {
                    folders.add(pending)
                    pending = ""
                }
                else -> {
                    val g = anchor.find(raw)
                    val href = unescapeHtml(g?.groupValues?.get(1)?.trim().orEmpty())
                    val title = unescapeHtml(g?.groupValues?.get(2)?.replace(tagStrip, "")?.trim().orEmpty())
                    if (href.isNotBlank()) {
                        val group = folders.lastOrNull { it.isNotBlank() } ?: Favorite.GROUP_PERSONAL
                        val fixed = toUrl(href)
                        // 去重：同 URL 同组跳过
                        val exists = items.any {
                            it.url == fixed && (it.group.ifBlank { Favorite.GROUP_PERSONAL }) == group
                        }
                        if (!exists) {
                            items.add(Favorite(name = title.ifBlank { hostOf(fixed) }, url = fixed, group = group))
                            added++
                        }
                    }
                }
            }
        }
        if (added > 0) persist()
        return added
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
        if ('&' !in s) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&")

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
