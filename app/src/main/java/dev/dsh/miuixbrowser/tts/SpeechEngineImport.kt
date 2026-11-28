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
            var body = firstString(o, "body", "requestBody")
            var headers = headerString(o.opt("header") ?: o.opt("headers"))
            if (opts != null) {
                opts.optString("method").trim().takeIf { it.isNotEmpty() }?.let { method = it }
                bodyString(opts.opt("body"))?.let { body = it }
                headers = mergeHeaders(headers, headerString(opts.opt("headers") ?: opts.opt("header")))
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

    /** 拆出 Legado 写在地址后面的选项 JSON；拆不出（或后面不是合法 JSON）就整串当地址。 */
    private fun splitOptions(raw: String): Pair<String, JSONObject?> {
        val m = OPTION_SPLIT.find(raw) ?: return raw.trim() to null
        val tail = raw.substring(m.range.last + 1).trim()
        val obj: JSONObject? = try {
            JSONObject(tail)
        } catch (e: JSONException) {
            null
        }
        if (obj == null) return raw.trim() to null
        return raw.substring(0, m.range.first).trim() to obj
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

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return ""
    }
}
