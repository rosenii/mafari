/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data.legado

import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 书源规则失败 / 站点异常时抛出，消息可直接给用户看。 */
class LegadoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 一组规则（ruleSearch / ruleBookInfo …）：键是字段名，值是规则字符串；空串视为没写。 */
class LegadoRules(private val map: Map<String, String>) {
    operator fun get(key: String): String? = map[key]?.takeIf { it.isNotBlank() }
}

/**
 * Legado（开源阅读）书源，只保留「文字小说」用得到的字段。
 * 规则的执行都在 [LegadoEngine]；同一份书源共用一个引擎（Cookie、`source.put` 的变量、搜索节流都跟着书源走）。
 */
class LegadoSource(
    val name: String,
    val url: String,
    val group: String,
    val comment: String,
    /** 0 文字 / 1 音频 / 2 图片 / 3 文件；目前只支持 0 */
    val type: Int,
    /** 请求头：JSON 对象文本 */
    val header: String?,
    val searchUrl: String?,
    val exploreUrl: String?,
    val jsLib: String?,
    /** 访问频率：`1/1500`（每 1500 ms 一次）或 `1500` */
    val concurrentRate: String?,
    val search: LegadoRules,
    val explore: LegadoRules,
    val bookInfo: LegadoRules,
    val toc: LegadoRules,
    val content: LegadoRules,
    /** 书源原始的 JSON 文本，用于持久化；解析失败的旧数据可能为空。 */
    val raw: String = "",
    /** 书源总开关（Legado 的 `enabled`）；关掉后不参与搜索 / 发现，但仍留在列表里。 */
    var enabled: Boolean = true,
    /** 发现开关（Legado 的 `enabledExplore`）。 */
    var exploreEnabled: Boolean = true,
) {
    val engine: LegadoEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { LegadoEngine(this) }

    val canSearch: Boolean get() = enabled && !searchUrl.isNullOrBlank() && search["bookList"] != null
    val canExplore: Boolean get() = enabled && exploreEnabled && !exploreUrl.isNullOrBlank()

    /** 复制一份并改开关（书源列表是不可变的 state，改开关时用新对象替换）。 */
    fun withSwitches(enabled: Boolean = this.enabled, explore: Boolean = this.exploreEnabled): LegadoSource =
        LegadoSource(
            name, url, group, comment, type, header, searchUrl, exploreUrl, jsLib, concurrentRate,
            this.search, this.explore, bookInfo, toc, content, raw, enabled,
            explore,
        )
}

object LegadoSourceParser {
    /** 顶层是书源数组、单个书源对象（有 bookSourceUrl），或 `{"bookSources":[…]}` 形式的合集。 */
    fun looksLikeLegado(root: Any?): Boolean = when (root) {
        is JSONArray -> (0 until root.length()).any { root.optJSONObject(it)?.has("bookSourceUrl") == true }
        is JSONObject -> root.has("bookSourceUrl") || root.optJSONArray("bookSources")?.let { a ->
            (0 until a.length()).any { a.optJSONObject(it)?.has("bookSourceUrl") == true }
        } == true
        else -> false
    }

    /** 解析失败的条目直接跳过；整份文件里没有可用的文字书源时抛 [IllegalArgumentException]。 */
    fun parse(root: Any?): List<LegadoSource> {
        val objs: List<JSONObject> = when (root) {
            is JSONArray -> (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            // Legado 的备份 / 合集文件会把书源放在 bookSources 下
            is JSONObject -> root.optJSONArray("bookSources")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            } ?: listOf(root)
            else -> emptyList()
        }
        val all = objs.mapNotNull { runCatching { parseOne(it) }.getOrNull() }
        require(all.isNotEmpty()) { tr("没有读到有效的书源（需要 bookSourceUrl 和 bookSourceName）") }
        val text = all.filter { it.type == 0 }
        require(text.isNotEmpty()) { tr("暂不支持音频 / 图片 / 文件类书源，只能导入文字小说书源") }
        return text
    }

    internal fun parseOne(o: JSONObject): LegadoSource? {
        val url = str(o, "bookSourceUrl")?.substringBefore('#')?.trimEnd('/') ?: return null
        val name = str(o, "bookSourceName") ?: url
        val header = when (val h = o.opt("header")) {
            is String -> h.trim().ifEmpty { null }
            is JSONObject -> h.toString()
            else -> null
        }
        return LegadoSource(
            name = name,
            url = url,
            group = str(o, "bookSourceGroup").orEmpty(),
            comment = str(o, "bookSourceComment").orEmpty(),
            type = o.optInt("bookSourceType", 0),
            header = header,
            searchUrl = str(o, "searchUrl"),
            exploreUrl = str(o, "exploreUrl"),
            jsLib = str(o, "jsLib"),
            concurrentRate = when (val r = o.opt("concurrentRate")) {
                is String -> r.trim().ifEmpty { null }
                is Number -> r.toString()
                else -> null
            },
            search = rules(o.opt("ruleSearch")),
            explore = rules(o.opt("ruleExplore")),
            bookInfo = rules(o.opt("ruleBookInfo")),
            toc = rules(o.opt("ruleToc")),
            content = rules(o.opt("ruleContent")),
            raw = o.toString(),
            enabled = o.optBoolean("enabled", true),
            exploreEnabled = o.optBoolean("enabledExplore", true),
        )
    }

    /** 规则对象；有些导出文件把它存成 JSON 字符串，也认。 */
    private fun rules(v: Any?): LegadoRules {
        val obj = when (v) {
            is JSONObject -> v
            is String -> runCatching { JSONTokener(v.trim()).nextValue() as? JSONObject }.getOrNull()
            else -> null
        } ?: return LegadoRules(emptyMap())
        val map = LinkedHashMap<String, String>()
        for (k in obj.keys()) {
            when (val x = obj.opt(k)) {
                is String -> map[k] = x
                is Number, is Boolean -> map[k] = x.toString()
                else -> Unit
            }
        }
        return LegadoRules(map)
    }

    private fun str(o: JSONObject, key: String): String? {
        val v = o.opt(key)
        return if (v is String && v.isNotBlank()) v.trim() else null
    }
}

/** 搜索 / 发现列表里的一本书，详情页读到更多字段后会补全。 */
class LegadoBook(
    var name: String,
    var author: String,
    val bookUrl: String,
    var coverUrl: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var tocUrl: String? = null,
) {
    /** 详情页的原文（目录和详情在同一个页面时省一次请求） */
    internal var infoBody: String? = null
    internal var infoUrl: String? = null
}

class LegadoChapter(val title: String, val url: String)

/** 「发现」页的一个入口（分类 / 榜单）；[url] 里可以带 `{{page}}`。 */
class LegadoExploreKind(val title: String, val url: String)
