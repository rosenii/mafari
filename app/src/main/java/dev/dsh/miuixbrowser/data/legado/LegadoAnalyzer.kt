/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data.legado

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URL

/**
 * Legado 规则解析（只读一个页面 / 一个条目的内容，按规则取文本、链接、列表）。
 *
 * 一条规则由若干「段」顺着接力：`@js:` / `<js>…</js>` 段执行脚本，其余段按内容类型取值，
 * 上一段的结果是下一段的输入（`result`）。普通段支持：
 * - 默认语法（jsoup）：`class.x` / `tag.x` / `id.x` / `text.x` / `children` / 任意 CSS 选择器，
 *   用 `@` 逐级下钻，最后一级是 `text / textNodes / ownText / html / all` 或属性名；
 *   末尾可跟下标 `.0` / `!0` / `[0,2:4]`
 * - `@css:` 前缀的 CSS、`@xpath:` 或 `//` 开头的 XPath、`@json:` 或 `$.` 开头的 JSONPath（内容是 JSON 时默认走它）
 * - `&&`（合并）、`||`（取第一个非空）、`%%`（交错合并）
 * - `{{ … }}` 内嵌（JS 或 `@` / `$.` / `//` 开头的规则）、`##正则##替换` / `###`（只换第一处）
 * - `@put:{"k":"规则"}` / `@get:{k}`
 *
 * 非线程安全（带一个解析缓存）；每个页面新建一个实例。
 */
internal class LegadoAnalyzer(private val engine: LegadoEngine, private val baseUrl: String) {

    private class Seg(val js: Boolean, val text: String)

    // ------------------------------------------------------------------ 对外接口

    /** 取一段文本。[isUrl] 时把结果按页面地址补全成绝对地址。 */
    fun getString(rule: String?, content: Any?, isUrl: Boolean = false, vars: Map<String, Any?> = emptyMap()): String {
        if (rule.isNullOrBlank()) return ""
        var result: Any? = content
        for (seg in splitJs(rule)) {
            result = if (seg.js) js(seg.text, result, vars) else join(plain(seg.text, result, vars, regexOnJoined = true))
        }
        val s = stringify(result).trim()
        return if (isUrl && s.isNotEmpty()) absUrl(baseUrl, s) else s
    }

    /** 取一组文本（链接、标签…）；每项独立做正则替换。 */
    fun getStringList(rule: String?, content: Any?, isUrl: Boolean = false, vars: Map<String, Any?> = emptyMap()): List<String> {
        if (rule.isNullOrBlank()) return emptyList()
        var result: Any? = content
        for (seg in splitJs(rule)) {
            result = if (seg.js) js(seg.text, result, vars) else plain(seg.text, result, vars, regexOnJoined = false)
        }
        val list = when (result) {
            null -> emptyList()
            is List<*> -> result.map { stringify(it).trim() }
            else -> listOf(stringify(result).trim())
        }.filter { it.isNotEmpty() }
        return if (isUrl) list.map { absUrl(baseUrl, it) } else list
    }

    /** 取条目列表：元素是 jsoup [Element]、JSON 节点或脚本返回的字符串。规则以 `-` 开头表示倒序。 */
    fun getElements(rule: String?, content: Any?, vars: Map<String, Any?> = emptyMap()): List<Any?> {
        if (rule.isNullOrBlank()) return emptyList()
        var r = rule.trim()
        var reverse = false
        if (r.startsWith("-")) { reverse = true; r = r.substring(1) } else if (r.startsWith("+")) r = r.substring(1)
        var cur: Any? = content
        for (seg in splitJs(r)) {
            cur = if (seg.js) js(seg.text, cur, vars) else elementsOf(seg.text, cur, vars)
        }
        val list: List<Any?> = when (cur) {
            null -> emptyList()
            is List<*> -> cur
            else -> listOf(cur)
        }
        return if (reverse) list.asReversed() else list
    }

    /** 拼地址用的规则渲染：脚本段求值、`{{ }}` 替换，其余原样（地址里不会有选择器）。 */
    fun renderTemplate(rule: String, vars: Map<String, Any?>): String {
        var result: Any? = ""
        for (seg in splitJs(rule)) {
            result = if (seg.js) js(seg.text, result, vars) else template(seg.text, null, vars)
        }
        val s = stringify(result)
        return if (s.contains("{{")) template(s, null, vars) else s
    }

    // ------------------------------------------------------------------ 分段

    private fun splitJs(rule: String): List<Seg> {
        val out = ArrayList<Seg>()
        var start = 0
        while (true) {
            val block = JS_BLOCK.find(rule, start)
            val at = rule.indexOf("@js:", start, ignoreCase = true)
            if (block == null && at < 0) break
            if (block != null && (at < 0 || block.range.first < at)) {
                if (block.range.first > start) out.add(Seg(false, rule.substring(start, block.range.first)))
                out.add(Seg(true, block.groupValues[1]))
                start = block.range.last + 1
            } else {
                if (at > start) out.add(Seg(false, rule.substring(start, at)))
                out.add(Seg(true, rule.substring(at + 4)))
                start = rule.length
                break
            }
        }
        if (start < rule.length) out.add(Seg(false, rule.substring(start)))
        return out.filter { it.text.isNotBlank() }
    }

    private fun js(script: String, result: Any?, vars: Map<String, Any?>): Any? {
        val all = HashMap<String, Any?>(vars)
        all["result"] = result
        all["baseUrl"] = baseUrl
        return engine.js.eval(script, all)
    }

    // ------------------------------------------------------------------ 普通段（非脚本）

    /** 一个普通段 → 文本列表。 */
    private fun plain(text: String, content: Any?, vars: Map<String, Any?>, regexOnJoined: Boolean): List<String> {
        var rule = text.trim()
        if (rule.isEmpty()) return emptyList()
        rule = applyPutGet(rule, content, vars)
        if (rule.contains("{{")) return listOf(template(rule, content, vars))

        val (body, repl) = splitRegex(rule)
        val values = ops(body, content) { r -> stringsOf(r, content) }
        if (repl == null) return values
        return if (regexOnJoined) listOf(replaceRegex(values.joinToString("\n"), repl)) else values.map { replaceRegex(it, repl) }
    }

    private fun elementsOf(text: String, content: Any?, vars: Map<String, Any?>): List<Any?> {
        val rule = applyPutGet(text.trim(), content, vars)
        if (rule.isEmpty()) return emptyList()
        val (body, _) = splitRegex(rule)
        return ops(body, content) { r -> nodesOf(r, content) }
    }

    /** `&&` / `||` / `%%`：按最先出现的那一种切开（不混用）。 */
    private fun <T> ops(rule: String, content: Any?, one: (String) -> List<T>): List<T> {
        for (op in OPS) {
            val parts = splitTop(rule, op)
            if (parts.size < 2) continue
            return when (op) {
                "||" -> {
                    for (p in parts) {
                        val r = one(p)
                        if (r.isNotEmpty()) return r
                    }
                    emptyList()
                }
                "&&" -> parts.flatMap(one)
                else -> {
                    val lists = parts.map(one)
                    val out = ArrayList<T>()
                    val n = lists.maxOfOrNull { it.size } ?: 0
                    for (i in 0 until n) for (l in lists) if (i < l.size) out.add(l[i])
                    out
                }
            }
        }
        return one(rule)
    }

    private enum class Mode { DEFAULT, CSS, JSON, XPATH }

    private fun modeOf(rule: String, content: Any?): Pair<Mode, String> {
        val r = rule.trim()
        return when {
            r.startsWith("@css:", true) -> Mode.CSS to r.substring(5).trim()
            r.startsWith("@json:", true) -> Mode.JSON to r.substring(6).trim()
            r.startsWith("@xpath:", true) -> Mode.XPATH to r.substring(7).trim()
            r.startsWith("$.") || r.startsWith("$[") -> Mode.JSON to r
            r.startsWith("//") -> Mode.XPATH to r
            r.startsWith("/") && !r.startsWith("/*") -> Mode.XPATH to r
            LegadoJsonPath.looksLikeJson(content) -> Mode.JSON to (if (r.startsWith("$")) r else "$.$r")
            else -> Mode.DEFAULT to r
        }
    }

    // ------------------------------------------------------------------ 取文本

    private fun stringsOf(rule: String, content: Any?): List<String> {
        val (mode, r) = modeOf(rule, content)
        return when (mode) {
            Mode.JSON -> {
                val root = LegadoJsonPath.toRoot(content) ?: return emptyList()
                LegadoJsonPath.select(root, r).mapNotNull { LegadoJsonPath.text(it) }
            }
            Mode.XPATH -> xpathStrings(r, content)
            else -> {
                val el = asElement(content) ?: return emptyList()
                val steps = splitAt(r)
                val last: String
                val chain: List<String>
                if (mode == Mode.CSS) {
                    val i = r.lastIndexOf('@')
                    if (i < 0) { last = "text"; chain = listOf(r) } else { last = r.substring(i + 1).trim(); chain = listOf(r.substring(0, i).trim()) }
                } else {
                    last = steps.last().trim()
                    chain = steps.dropLast(1)
                }
                val elements = if (mode == Mode.CSS) cssSelect(el, chain[0]) else select(el, chain)
                lastValues(elements, last)
            }
        }
    }

    private fun lastValues(elements: List<Element>, last: String): List<String> {
        val out = ArrayList<String>()
        when (last) {
            "text" -> elements.forEach { out.add(it.text()) }
            "textNodes" -> elements.forEach { e ->
                val t = e.textNodes().map { n: TextNode -> n.text().trim() }.filter { it.isNotEmpty() }
                if (t.isNotEmpty()) out.add(t.joinToString("\n"))
            }
            "ownText" -> elements.forEach { out.add(it.ownText()) }
            "html" -> elements.forEach { e ->
                val c = e.clone()
                c.select("script").remove()
                c.select("style").remove()
                out.add(c.outerHtml())
            }
            "all" -> elements.forEach { out.add(it.outerHtml()) }
            else -> elements.forEach { e ->
                val v = e.attr(last)
                if (v.isNotBlank() && v !in out) out.add(v)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 取条目

    private fun nodesOf(rule: String, content: Any?): List<Any?> {
        val (mode, r) = modeOf(rule, content)
        return when (mode) {
            Mode.JSON -> {
                val root = LegadoJsonPath.toRoot(content) ?: return emptyList()
                val hits = LegadoJsonPath.select(root, r)
                // 命中单个数组（$.data.list）时展开成条目
                if (hits.size == 1 && hits[0] is JSONArray) {
                    val a = hits[0] as JSONArray
                    (0 until a.length()).map { a.opt(it).takeIf { v -> v !== JSONObject.NULL } }
                } else hits
            }
            Mode.XPATH -> asElement(content)?.let { xpathElements(it, r) } ?: emptyList()
            Mode.CSS -> asElement(content)?.let { cssSelect(it, r) } ?: emptyList()
            Mode.DEFAULT -> asElement(content)?.let { select(it, splitAt(r)) } ?: emptyList()
        }
    }

    /** 默认语法：每一级在上一级的结果里选，最后合并。 */
    private fun select(start: Element, steps: List<String>): List<Element> {
        var cur: List<Element> = listOf(start)
        for (st in steps) {
            if (st.isBlank()) continue
            val next = ArrayList<Element>()
            for (e in cur) next.addAll(selectOne(e, st.trim()))
            cur = next
            if (cur.isEmpty()) break
        }
        return cur
    }

    private fun cssSelect(el: Element, css: String): List<Element> =
        runCatching { el.select(css).toList() }.getOrDefault(emptyList())

    private fun selectOne(el: Element, rule: String): List<Element> {
        var before = rule
        var includeIdx: List<Int>? = null
        var excludeIdx: List<Int>? = null
        var bracket: String? = null

        val b = BRACKET_INDEX.find(rule)
        if (b != null && b.groupValues[2].any { it.isDigit() || it == ':' }) {
            before = b.groupValues[1].trim()
            bracket = b.groupValues[2]
        } else {
            val d = DOT_INDEX.find(rule)
            if (d != null) {
                before = d.groupValues[1]
                val idx = d.groupValues[3].split(':').mapNotNull { it.toIntOrNull() }
                if (d.groupValues[2] == "!") excludeIdx = idx else includeIdx = idx
            }
        }

        val found: List<Element> = if (before.isEmpty()) {
            el.children().toList()
        } else {
            val parts = before.split('.')
            when (parts[0]) {
                "children" -> el.children().toList()
                "class" -> if (parts.size > 1) el.getElementsByClass(parts[1]).toList() else emptyList()
                "tag" -> if (parts.size > 1) el.getElementsByTag(parts[1]).toList() else emptyList()
                "id" -> if (parts.size > 1) el.getElementsByAttributeValue("id", parts[1]).toList() else emptyList()
                "text" -> if (parts.size > 1) el.getElementsContainingOwnText(parts[1]).toList() else emptyList()
                else -> cssSelect(el, before)
            }
        }
        if (found.isEmpty()) return found
        val size = found.size
        fun norm(i: Int) = if (i < 0) size + i else i
        return when {
            includeIdx != null -> includeIdx.map(::norm).filter { it in 0 until size }.distinct().map { found[it] }
            excludeIdx != null -> {
                val ex = excludeIdx.map(::norm).toSet()
                found.filterIndexed { i, _ -> i !in ex }
            }
            bracket != null -> applyBracket(found, bracket)
            else -> found
        }
    }

    /** `[0,2,-1]` / `[1:4]` / `[!0,3]`（! 表示排除这些下标）。 */
    private fun applyBracket(list: List<Element>, spec: String): List<Element> {
        val size = list.size
        var s = spec.trim()
        val exclude = s.startsWith("!")
        if (exclude) s = s.substring(1)
        val picked = LinkedHashSet<Int>()
        for (part in s.split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if (p.contains(':')) {
                val f = p.split(':')
                var from = f[0].trim().toIntOrNull() ?: 0
                var to = f.getOrNull(1)?.trim()?.toIntOrNull() ?: (size - 1)
                val step = f.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1
                if (from < 0) from += size
                if (to < 0) to += size
                var i = from.coerceAtLeast(0)
                while (i <= to.coerceAtMost(size - 1)) { picked.add(i); i += step }
            } else {
                val n = p.toIntOrNull() ?: continue
                picked.add(if (n < 0) size + n else n)
            }
        }
        return if (exclude) list.filterIndexed { i, _ -> i !in picked } else picked.filter { it in 0 until size }.map { list[it] }
    }

    // ------------------------------------------------------------------ XPath（jsoup selectXpath；属性 / text() 结尾自己处理）

    private fun xpathElements(el: Element, path: String): List<Element> =
        runCatching { el.selectXpath(path).toList() }.getOrDefault(emptyList())

    private fun xpathStrings(path: String, content: Any?): List<String> {
        val el = asElement(content) ?: return emptyList()
        val m = XPATH_TAIL.find(path)
        if (m != null) {
            val elements = xpathElements(el, m.groupValues[1])
            val tail = m.groupValues[2]
            return if (tail == "text()") {
                elements.map { it.ownText().ifEmpty { it.text() } }.filter { it.isNotEmpty() }
            } else {
                val attr = tail.substring(1)
                elements.map { it.attr(attr) }.filter { it.isNotBlank() }
            }
        }
        return xpathElements(el, path).map { it.outerHtml() }
    }

    // ------------------------------------------------------------------ 模板、正则、@put/@get

    private fun template(text: String, content: Any?, vars: Map<String, Any?>): String =
        TEMPLATE.replace(text) { m ->
            val inner = m.groupValues[1].trim()
            when {
                inner.startsWith("@js:", true) -> stringify(js(inner.substring(4), content, vars))
                inner.startsWith("@") || inner.startsWith("$.") || inner.startsWith("$[") || inner.startsWith("//") ->
                    getString(inner, content, false, vars)
                else -> stringify(js(inner, content, vars))
            }
        }

    private fun splitRegex(rule: String): Pair<String, String?> {
        val i = rule.indexOf("##")
        return if (i < 0) rule to null else rule.substring(0, i) to rule.substring(i)
    }

    /** `##正则##替换###`：换全部；结尾 `###` 只换第一处；只有 `##正则` 就删掉匹配。替换里 `$1` 引用分组。 */
    fun replaceRegex(text: String, spec: String): String {
        val firstOnly = spec.endsWith("###")
        val body = spec.removePrefix("##").let { if (firstOnly) it.removeSuffix("###") else it }
        val parts = body.split("##", limit = 2)
        val pattern = parts[0]
        if (pattern.isEmpty()) return text
        val replacement = parts.getOrElse(1) { "" }
        val re = runCatching { Regex(pattern) }.getOrNull() ?: return text
        return if (firstOnly) {
            val m = re.find(text) ?: return text
            text.replaceRange(m.range, expand(m, replacement))
        } else {
            re.replace(text) { expand(it, replacement) }
        }
    }

    private fun expand(m: MatchResult, replacement: String): String =
        GROUP_REF.replace(replacement) { g -> m.groupValues.getOrNull(g.groupValues[1].toInt()).orEmpty() }

    private fun applyPutGet(rule: String, content: Any?, vars: Map<String, Any?>): String {
        var r = rule
        PUT.find(r)?.let { m ->
            runCatching {
                val o = JSONObject(m.groupValues[1])
                for (k in o.keys()) engine.store[k] = getString(o.optString(k), content, false, vars)
            }
            r = r.replace(m.value, "")
        }
        if (r.contains("@get:")) r = GET.replace(r) { engine.store[it.groupValues[1].trim()].orEmpty() }
        return r
    }

    // ------------------------------------------------------------------ 小工具

    private var cachedKey: String? = null
    private var cachedDoc: Document? = null

    /** 内容 → jsoup 元素：Element 原样；字符串解析成 HTML（同一个字符串只解析一次）。 */
    private fun asElement(content: Any?): Element? = when (content) {
        is Element -> content
        is CharSequence -> {
            val s = content.toString()
            if (s === cachedKey && cachedDoc != null) cachedDoc else Jsoup.parse(s, baseUrl).also { cachedKey = s; cachedDoc = it }
        }
        is List<*> -> content.firstOrNull()?.let { asElement(it) }
        else -> null
    }

    private fun stringify(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Element -> v.outerHtml()
        is List<*> -> v.joinToString("\n") { stringify(it) }
        is Double -> LegadoJsonPath.text(v).orEmpty()
        else -> v.toString()
    }

    private fun join(l: List<String>): String = l.joinToString("\n")

    /** 按 `@` 切（不切方括号、圆括号、引号里的 `@`）。 */
    private fun splitAt(rule: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var q = '\u0000'
        var last = 0
        for (i in rule.indices) {
            val c = rule[i]
            if (q != '\u0000') { if (c == q) q = '\u0000'; continue }
            when (c) {
                '\'', '"' -> q = c
                '[', '(' -> depth++
                ']', ')' -> depth--
                '@' -> if (depth == 0) { parts.add(rule.substring(last, i)); last = i + 1 }
            }
        }
        parts.add(rule.substring(last))
        return parts
    }

    /** 在括号 / 引号之外按 [op] 切开。 */
    private fun splitTop(rule: String, op: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var q = '\u0000'
        var last = 0
        var i = 0
        while (i < rule.length) {
            val c = rule[i]
            if (q != '\u0000') {
                if (c == q) q = '\u0000'
            } else when {
                c == '\'' || c == '"' -> q = c
                c == '[' || c == '(' -> depth++
                c == ']' || c == ')' -> depth--
                depth == 0 && rule.startsWith(op, i) -> {
                    parts.add(rule.substring(last, i))
                    i += op.length
                    last = i
                    continue
                }
            }
            i++
        }
        parts.add(rule.substring(last))
        return parts
    }

    companion object {
        private val OPS = listOf("&&", "||", "%%")
        private val JS_BLOCK = Regex("<js>([\\s\\S]*?)</js>", RegexOption.IGNORE_CASE)
        private val TEMPLATE = Regex("\\{\\{([\\s\\S]*?)\\}\\}")
        private val PUT = Regex("@put:(\\{[^}]*\\})", RegexOption.IGNORE_CASE)
        private val GET = Regex("@get:\\{([^}]+)\\}", RegexOption.IGNORE_CASE)
        private val GROUP_REF = Regex("\\$(\\d+)")
        private val BRACKET_INDEX = Regex("^(.*?)\\[([\\s!\\d,:\\-]+)\\]$")
        private val DOT_INDEX = Regex("^(.*?)([.!])(-?\\d+(?::-?\\d+)*)$")
        private val XPATH_TAIL = Regex("^(.*?)/+(@[\\w:.-]+|text\\(\\))$")

        /** 把相对地址补全成绝对地址；`地址,{选项}` 只处理逗号前的部分。 */
        fun absUrl(base: String, url: String): String {
            val u = url.trim()
            if (u.isEmpty()) return u
            val cut = u.indexOf(",{")
            val main = if (cut >= 0) u.substring(0, cut) else u
            val tail = if (cut >= 0) u.substring(cut) else ""
            if (main.startsWith("data:", true) || main.startsWith("javascript:", true)) return u
            val abs = if (main.startsWith("http://", true) || main.startsWith("https://", true)) main
            else runCatching { URL(URL(base), main).toString() }.getOrDefault(main)
            return abs + tail
        }
    }
}
