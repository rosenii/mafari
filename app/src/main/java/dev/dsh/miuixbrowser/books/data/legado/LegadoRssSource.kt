/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data.legado

import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONArray
import org.json.JSONObject

/** 订阅源列表里的一篇文章。 */
class LegadoRssArticle(
    val title: String,
    val link: String,
    val author: String,
    val pubDate: String,
    val image: String?,
    val description: String?,
)

/** 一页订阅文章 + 下一页地址（没有下一页为 null）。 */
class LegadoRssPage(val articles: List<LegadoRssArticle>, val nextUrl: String?)

/**
 * Legado（开源阅读）的订阅源（RSS / JSON / 网页三种 `sourceType`），只保留浏览文章和取正文用得到的规则。
 * 和书源是两套模型：书源用 `bookSourceUrl`，订阅源用 `sourceUrl`。
 */
class LegadoRssSource(
    val name: String,
    val url: String,
    val group: String,
    val comment: String,
    /** 0 RSS / 1 JSON / 2 网页 */
    val type: Int,
    val header: String?,
    val sortUrl: String?,
    val concurrentRate: String?,
    val ruleArticles: String?,
    val ruleNextPage: String?,
    val ruleTitle: String?,
    val rulePubDate: String?,
    val ruleDescription: String?,
    val ruleLink: String?,
    val ruleImage: String?,
    val ruleContent: String?,
    val ruleAuthor: String?,
    val ruleCategory: String?,
    var enabled: Boolean = true,
    val raw: String = "",
) {
    /** 复用书源引擎的发请求 / Cookie / 频率限制 / 脚本能力；规则为空，实际规则由本类自己求值。 */
    private val engine: LegadoEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val empty = LegadoRules(emptyMap())
        LegadoEngine(
            LegadoSource(
                name = name,
                url = url,
                group = group,
                comment = comment,
                type = 0,
                header = header,
                searchUrl = null,
                exploreUrl = null,
                jsLib = null,
                concurrentRate = concurrentRate,
                search = empty,
                explore = empty,
                bookInfo = empty,
                toc = empty,
                content = empty,
                raw = raw,
                enabled = enabled,
            ),
        )
    }

    val canBrowse: Boolean get() = enabled && !ruleArticles.isNullOrBlank()

    /** 读一页文章；[url] 为空时读订阅源自己的地址（第二页起传上一页的 [LegadoRssPage.nextUrl]）。 */
    fun loadPage(url: String? = null): LegadoRssPage {
        if (!enabled) throw LegadoException(tr("这个订阅源已停用"))
        val rule = ruleArticles?.takeIf { it.isNotBlank() } ?: throw LegadoException(tr("这个订阅源没有文章列表规则"))
        val target = url?.takeIf { it.isNotBlank() } ?: this.url
        val res = engine.fetchForScript(target, null)
        val analyzer = LegadoAnalyzer(engine, res.finalUrl)
        val body = res.body()
        val vars = mapOf<String, Any?>("page" to 1)
        val titleRule = textRule(ruleTitle) ?: "title@text"
        val linkRule = textRule(ruleLink) ?: "link@text"
        val out = ArrayList<LegadoRssArticle>()
        for (item in analyzer.getElements(rule, body, vars)) {
            val title = analyzer.getString(titleRule, item, false, vars).trim()
            if (title.isEmpty()) continue
            val link = analyzer.getString(linkRule, item, true, vars).trim()
            if (link.isEmpty()) continue
            out.add(
                LegadoRssArticle(
                    title = title,
                    link = link,
                    author = analyzer.getString(textRule(ruleAuthor), item, false, vars).trim(),
                    pubDate = analyzer.getString(textRule(rulePubDate), item, false, vars).trim(),
                    image = analyzer.getString(textRule(ruleImage), item, true, vars).ifBlank { null },
                    description = analyzer.getString(textRule(ruleDescription), item, false, vars).ifBlank { null },
                ),
            )
        }
        // 有些「书源索引」订阅页没有文章节点，只有一堆书源 JSON 直链 / legado:// 导入链接：
        // 规则没命中时退回扫描这些链接，至少能列出可导入的书源。
        if (out.isEmpty()) {
            val seen = HashSet<String>()
            for (el in analyzer.getElements("a[href]", body)) {
                val href = analyzer.getString("@href", el, true).trim()
                val bookUrl = bookSourceUrl(href) ?: continue
                if (!seen.add(bookUrl)) continue
                val title = analyzer.getString("@text", el, false).trim()
                    .ifBlank { bookUrl.substringAfterLast('/').substringBefore('?').ifBlank { bookUrl } }
                out.add(
                    LegadoRssArticle(
                        title = title,
                        link = bookUrl,
                        author = "",
                        pubDate = "",
                        image = null,
                        description = null,
                    ),
                )
            }
        }
        val next = ruleNextPage?.let { analyzer.getString(textRule(it), body, true, vars) }
            ?.takeIf { it.isNotBlank() && it != target }
        return LegadoRssPage(out, next)
    }

    /** 书源索引页里的 JSON 直链 / legado://import/bookSource?src=…，转成可下载的 http(s) 地址。 */
    private fun bookSourceUrl(href: String): String? {
        if (href.startsWith("legado://import/bookSource", true)) {
            val query = href.substringAfter('?', "")
            val src = query.split('&').firstOrNull { it.startsWith("src=", true) }
                ?.substringAfter('=') ?: return null
            return runCatching { java.net.URLDecoder.decode(src, "UTF-8") }.getOrNull()
                ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }
        return href.takeIf {
            (it.startsWith("http://", true) || it.startsWith("https://", true)) &&
                (it.endsWith(".json", true) || it.contains("/sources/"))
        }
    }

    /** 简单规则（title / description / pubDate …）默认取文本；没写 @ / $ / 选择器变化时自动补 @text。 */
    private fun textRule(rule: String?): String? = rule?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith("@") || it.startsWith("$") || it.startsWith("/") || it.contains("@") || it.contains("{{")) it else "$it@text"
    }

    /** 正文规则默认取 innerHTML（description 之类），脚本 / JSON / XPath 规则原样。 */
    private fun htmlRule(rule: String): String =
        if (rule.startsWith("@") || rule.startsWith("$") || rule.startsWith("/") || rule.contains("@") || rule.contains("{{")) rule else "$rule@html"

    /** 读一篇文章正文（HTML / 纯文本），交给导入逻辑转段落。 */
    fun loadContent(article: LegadoRssArticle): String {
        val rule = ruleContent?.takeIf { it.isNotBlank() } ?: throw LegadoException(tr("这个订阅源没有正文规则"))
        val res = engine.fetchForScript(article.link, null)
        val analyzer = LegadoAnalyzer(engine, res.finalUrl)
        val body = res.body()
        val text = analyzer.getString(htmlRule(rule), body)
        if (text.isBlank()) throw LegadoException(tr("没有读到正文，订阅源规则可能已失效"))
        return text
    }
}

object LegadoRssSourceParser {
    /** 顶层是订阅源数组、单个订阅源（`sourceUrl` + `ruleArticles`），或 `{"rssSources":[…]}`。 */
    fun looksLikeLegado(root: Any?): Boolean = when (root) {
        is JSONArray -> (0 until root.length()).any { isRss(root.optJSONObject(it)) }
        is JSONObject -> isRss(root) || root.optJSONArray("rssSources")?.let { a ->
            (0 until a.length()).any { isRss(a.optJSONObject(it)) }
        } == true
        else -> false
    }

    private fun isRss(o: JSONObject?): Boolean =
        o != null && !o.optString("sourceUrl").isBlank() &&
            (!o.optString("ruleArticles").isBlank() || !o.optString("ruleTitle").isBlank())

    /** 解析失败的条目跳过；整份文件里没有可用的订阅源时抛 [IllegalArgumentException]。 */
    fun parse(root: Any?): List<LegadoRssSource> {
        val objs: List<JSONObject> = when (root) {
            is JSONArray -> (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            is JSONObject -> root.optJSONArray("rssSources")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            } ?: listOf(root)
            else -> emptyList()
        }
        val out = objs.mapNotNull { runCatching { parseOne(it) }.getOrNull() }
        require(out.isNotEmpty()) { tr("没有读到有效的订阅源（需要 sourceUrl 和 ruleArticles）") }
        return out
    }

    internal fun parseOne(o: JSONObject): LegadoRssSource? {
        val url = str(o, "sourceUrl")?.substringBefore('#')?.trimEnd('/') ?: return null
        val articles = str(o, "ruleArticles") ?: return null
        return LegadoRssSource(
            name = str(o, "sourceName") ?: url,
            url = url,
            group = str(o, "sourceGroup").orEmpty(),
            comment = str(o, "sourceComment").orEmpty(),
            type = o.optInt("sourceType", 0),
            header = when (val h = o.opt("header")) {
                is String -> h.trim().ifEmpty { null }
                is JSONObject -> h.toString()
                else -> null
            },
            sortUrl = str(o, "sortUrl"),
            concurrentRate = when (val r = o.opt("concurrentRate")) {
                is String -> r.trim().ifEmpty { null }
                is Number -> r.toString()
                else -> null
            },
            ruleArticles = articles,
            ruleNextPage = str(o, "ruleNextPage"),
            ruleTitle = str(o, "ruleTitle"),
            rulePubDate = str(o, "rulePubDate"),
            ruleDescription = str(o, "ruleDescription"),
            ruleLink = str(o, "ruleLink"),
            ruleImage = str(o, "ruleImage"),
            ruleContent = str(o, "ruleContent"),
            ruleAuthor = str(o, "ruleAuthor"),
            ruleCategory = str(o, "ruleCategory"),
            enabled = o.optBoolean("enabled", true),
            raw = o.toString(),
        )
    }

    private fun str(o: JSONObject, key: String): String? {
        val v = o.opt(key)
        return if (v is String && v.isNotBlank()) v.trim() else null
    }
}
