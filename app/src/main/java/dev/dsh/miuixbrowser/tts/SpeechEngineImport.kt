/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.tts

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 导入 Legado 风格的在线朗读引擎订阅：
 * `[{"name":"…","url":"…","header":"…"}]`，也接受 `{"engines":[…]}`（外层的 name / title 作订阅文件夹名）或单个对象。
 *
 * - Legado 的 URL 里常见 `{{java.encodeURI(java.encodeURI(speakText))}}`，转成 Mafari 的 `{{speakTextUrl2}}`
 *   （双重 URL 编码）；单层编码（含 `encodeURIComponent`、带 `"UTF-8"` 参数的写法）转成 `{{speakTextUrl}}`。
 *   转换同时作用于地址、请求体和请求头。
 * - Legado 把请求选项写在地址后面：`https://x/tts?t={{speakText}},{"method":"POST","body":"…","headers":{…}}`，
 *   这里拆开，method / body / headers 填进对应字段。
 * - Mafari 不能执行 JS：`@js:` / `<js>`、选项里的 `js` / `webView`、以及 `{{…}}` 里不认识的表达式，
 *   这类引擎导入了也播不出来，直接跳过并记入 [Result.unsupported]。
 */
object SpeechEngineImport {
    private const val ENC_FN = """(?:java\.encodeURIComponent|java\.encodeURI|encodeURIComponent|encodeURI)"""
    private const val SPEAK_ARG = """speakText(?:\s*,\s*["'][^"']*["'])?"""

    private val DOUBLE_ENCODE = Regex(
        """\{\{\s*$ENC_FN\s*\(\s*$ENC_FN\s*\(\s*$SPEAK_ARG\s*\)\s*\)\s*\}\}""",
        RegexOption.IGNORE_CASE,
    )
    private val SINGLE_ENCODE = Regex(
        """\{\{\s*$ENC_FN\s*\(\s*$SPEAK_ARG\s*\)\s*\}\}""",
        RegexOption.IGNORE_CASE,
    )

    /** 地址与选项 JSON 之间的逗号：后面紧跟 `{` 但不是 `{{`（那是占位符）。 */
    private val OPTION_SPLIT = Regex("""\s*,\s*(?=\{(?!\{))""")

    private val TEMPLATE = Regex("""\{\{\s*([^{}]*?)\s*\}\}""")
    private val KNOWN_PLACEHOLDERS = setOf(
        "speakText", "speakTextUrl", "speakTextUrl2", "speakTextJson", "speakSpeed", "text", "speed", "voice", "model",
    )

    /**
     * @param skipped 没导入的条数（格式不对、被停用、重复、不支持 JS 等）
     * @param title 订阅外层写的名字，没有则为空
     * @param unsupported 因为使用了 JS / 不认识的表达式而被跳过的引擎名
     */
    class Result(
        val engines: List<SpeechEngine>,
        val skipped: Int,
        val title: String = "",
        val unsupported: List<String> = emptyList(),
    )

    fun parse(raw: String): Result {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw IllegalArgumentException("订阅内容为空")
        val root = try {
            JSONTokener(text).nextValue()
        } catch (e: JSONException) {
            throw IllegalArgumentException("不是有效的 JSON：" + (e.message ?: "").take(80))
        }
        var title = ""
        val arr: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                val wrapped = root.optJSONArray("engines")
                    ?: root.optJSONArray("speechEngines")
                    ?: root.optJSONArray("ttsEngines")
                if (wrapped != null) {
                    title = firstString(root, "name", "title", "sourceName")
                    wrapped
                } else {
                    JSONArray().put(root)
                }
            }
            else -> throw IllegalArgumentException("不是有效的语音引擎订阅 JSON")
        }
        val out = ArrayList<SpeechEngine>()
        val unsupported = ArrayList<String>()
        val seen = HashSet<String>()
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { skipped++; continue }
            if (o.has("enabled") && !o.optBoolean("enabled", true)) { skipped++; continue }
            val name = firstString(o, "name", "sourceName", "engineName", "title")
            val rawUrl = firstString(o, "url", "sourceUrl", "ttsUrl", "api")
            if (rawUrl.isBlank()) { skipped++; continue }
            val label = name.ifBlank { rawUrl.take(30) }

            val (head, opts) = splitOptions(rawUrl)
            if (usesJs(head, opts)) { skipped++; unsupported.add(label); continue }
            val url = normalize(head)
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) { skipped++; continue }

            var method = firstString(o, "method", "requestMethod")
            var body = firstBody(o, "body", "requestBody", "postData", "data")
            var headers = firstHeaders(o, "header", "headers", "requestHeaders", "requestHeader")
            if (opts != null) {
                opts.optString("method").trim().takeIf { it.isNotEmpty() }?.let { method = it }
                firstBody(opts, "body", "requestBody", "postData", "data").takeIf { it.isNotEmpty() }?.let { body = it }
                headers = mergeHeaders(headers, firstHeaders(opts, "headers", "header", "requestHeaders"))
            }
            body = normalize(body)
            headers = normalize(headers)
            // 有请求体却没写方式：GET 带请求体没有意义，按 POST
            val post = method.equals("POST", true) || (method.isBlank() && body.isNotBlank())

            if (hasUnknownTemplate(url, body, headers)) { skipped++; unsupported.add(label); continue }

            // 同一订阅里地址 + 方式 + 请求体都一样的，只留第一条
            val dedupe = url + "\n" + (if (post) "POST" else "GET") + "\n" + body
            if (!seen.add(dedupe)) { skipped++; continue }

            out.add(
                SpeechEngine(
                    id = "sub_" + Integer.toHexString(url.hashCode()),
                    name = name.ifBlank { "语音引擎 ${out.size + 1}" },
                    url = url,
                    method = if (post) "POST" else "GET",
                    headers = headers,
                    body = body,
                    voice = firstString(o, "voice"),
                    model = firstString(o, "model"),
                ),
            )
        }
        if (out.isEmpty()) {
            throw IllegalArgumentException(
                if (unsupported.isNotEmpty()) "这些语音引擎使用了暂不支持的 JS 规则，没有可导入的引擎" else "没有读到有效的语音引擎",
            )
        }
        return Result(out, skipped, title, unsupported)
    }

    /**
     * 拆出 Legado 写在地址后面的选项 JSON。选项 JSON 经常不严格（末尾多了字符、body 里有没转义的引号等），
     * 所以依次试：整段严格解析 → 配平花括号后解析 → 按 method / body / headers 逐个宽松提取。
     * 都不行才整串当地址（此时请求头 / 请求体会是空的）。
     */
    private fun splitOptions(raw: String): Pair<String, JSONObject?> {
        var from = 0
        while (true) {
            val m = OPTION_SPLIT.find(raw, from) ?: return raw.trim() to null
            val tail = raw.substring(m.range.last + 1).trim()
            val obj = parseOptions(tail)
            if (obj != null) return raw.substring(0, m.range.first).trim() to obj
            from = m.range.last + 1
        }
    }

    private fun parseOptions(tail: String): JSONObject? {
        if (!tail.startsWith("{")) return null
        try {
            return JSONObject(tail)
        } catch (_: JSONException) {
        }
        val block = balanced(tail, 0)
        if (block != null) {
            try {
                return JSONObject(block)
            } catch (_: JSONException) {
            }
        }
        // 宽松提取：至少 method / body / headers 之一读得出来才算
        val o = JSONObject()
        val m = extractValue(tail, "method")
        if (m is String) o.put("method", m)
        val b = extractValue(tail, "body")
        if (b != null) o.put("body", b)
        val h = extractValue(tail, "headers") ?: extractValue(tail, "header")
        if (h != null) o.put("headers", h)
        return if (o.length() > 0) o else null
    }

    /** 从 [start]（必须是 `{` 或 `[`）起配平括号，引号里的括号不算；配不平返回 null。 */
    private fun balanced(s: String, start: Int): String? {
        val open = s[start]
        val close = if (open == '{') '}' else if (open == '[') ']' else return null
        var depth = 0
        var quote = '\u0000'
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (quote != '\u0000') {
                if (c == '\\') i++ else if (c == quote) quote = '\u0000'
            } else when (c) {
                '"', '\'' -> quote = c
                open -> depth++
                close -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
            i++
        }
        return null
    }

    /** 在选项文本里找 `"key": value`，value 可以是字符串、对象或数组；字符串按 JSON 规则解转义。 */
    private fun extractValue(s: String, key: String): Any? {
        val m = Regex("""["']?\b$key["']?\s*[:=]\s*""").find(s) ?: return null
        val i = m.range.last + 1
        if (i >= s.length) return null
        val c = s[i]
        if (c == '{' || c == '[') {
            val block = balanced(s, i) ?: return null
            return try {
                if (c == '{') JSONObject(block) else JSONArray(block)
            } catch (_: JSONException) {
                block
            }
        }
        if (c == '"' || c == '\'') {
            var j = i + 1
            while (j < s.length) {
                if (s[j] == '\\') j += 2 else if (s[j] == c) break else j++
            }
            if (j >= s.length) return null
            val lit = s.substring(i, j + 1)
            return try {
                JSONTokener(lit).nextValue()
            } catch (_: JSONException) {
                lit.substring(1, lit.length - 1)
            }
        }
        return null
    }

    private fun usesJs(head: String, opts: JSONObject?): Boolean {
        if (head.contains("@js:", true) || head.contains("<js>", true)) return true
        if (opts == null) return false
        return opts.optString("js").isNotBlank() || opts.optBoolean("webView", false)
    }

    private fun normalize(text: String): String {
        if (text.isEmpty()) return text
        return SINGLE_ENCODE.replace(DOUBLE_ENCODE.replace(text, "{{speakTextUrl2}}"), "{{speakTextUrl}}")
    }

    private fun hasUnknownTemplate(vararg texts: String): Boolean =
        texts.any { t -> TEMPLATE.findAll(t).any { it.groupValues[1] !in KNOWN_PLACEHOLDERS } }

    private fun headerString(h: Any?): String = when (h) {
        is String -> h.trim()
        is JSONObject -> h.toString()
        else -> ""
    }

    /** 选项里的 body 可能是字符串，也可能直接写成对象 / 数组。 */
    private fun bodyString(b: Any?): String? = when (b) {
        is String -> b
        is JSONObject, is JSONArray -> b.toString()
        else -> null
    }

    /** 订阅里的 header 与地址选项里的 headers 合并（后者优先）；不是 JSON 就取后者。 */
    private fun mergeHeaders(base: String, extra: String): String {
        if (extra.isBlank()) return base
        if (base.isBlank()) return extra
        return try {
            val a = JSONObject(base)
            val b = JSONObject(extra)
            for (k in b.keys()) a.put(k, b.get(k))
            a.toString()
        } catch (e: JSONException) {
            extra
        }
    }

    /** 依次取这些键里第一个「非空」的 header（字符串或对象），空字符串不会挡住后面的键。 */
    private fun firstHeaders(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val s = headerString(o.opt(k))
            if (s.isNotBlank()) return s
        }
        return ""
    }

    private fun firstBody(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val s = bodyString(o.opt(k))
            if (!s.isNullOrEmpty()) return s
        }
        return ""
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return ""
    }
}
