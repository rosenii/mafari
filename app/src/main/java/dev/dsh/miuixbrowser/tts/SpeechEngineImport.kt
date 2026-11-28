/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.tts

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 导入 Legado 风格的在线朗读引擎订阅：
 * `[{"name":"…","url":"…","header":"…"}]`，也接受 `{"engines":[…]}` 或单个对象。
 *
 * Legado 的 URL 里常见 `{{java.encodeURI(java.encodeURI(speakText))}}`，这里转成
 * Mafari 的 `{{speakTextUrl2}}`（双重 URL 编码）；单层编码转成 `{{speakTextUrl}}`。
 */
object SpeechEngineImport {
    private val DOUBLE_ENCODE = Regex(
        """\{\{\s*java\.encodeURI\s*\(\s*java\.encodeURI\s*\(\s*speakText\s*\)\s*\)\s*\}\}""",
        RegexOption.IGNORE_CASE,
    )
    private val SINGLE_ENCODE = Regex(
        """\{\{\s*java\.encodeURI\s*\(\s*speakText\s*\)\s*\}\}""",
        RegexOption.IGNORE_CASE,
    )

    class Result(val engines: List<SpeechEngine>, val skipped: Int)

    fun parse(raw: String): Result {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw IllegalArgumentException("订阅内容为空")
        val root = JSONTokener(text).nextValue()
        val arr = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("engines")
                ?: root.optJSONArray("speechEngines")
                ?: root.optJSONArray("ttsEngines")
                ?: JSONArray().put(root)
            else -> throw IllegalArgumentException("不是有效的语音引擎订阅 JSON")
        }
        val out = ArrayList<SpeechEngine>()
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: run { skipped++; continue }
            if (o.has("enabled") && !o.optBoolean("enabled", true)) { skipped++; continue }
            val name = firstString(o, "name", "sourceName", "engineName", "title")
            val rawUrl = firstString(o, "url", "sourceUrl", "ttsUrl", "api")
            if (rawUrl.isBlank()) { skipped++; continue }
            val url = normalize(rawUrl)
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) { skipped++; continue }
            val header = when (val h = o.opt("header") ?: o.opt("headers")) {
                is String -> h
                is JSONObject -> h.toString()
                else -> ""
            }
            val method = firstString(o, "method", "requestMethod").ifBlank { "GET" }
            out.add(
                SpeechEngine(
                    id = "sub_" + Integer.toHexString(url.hashCode()),
                    name = name.ifBlank { "语音引擎 ${out.size + 1}" },
                    url = url,
                    method = if (method.equals("POST", true)) "POST" else "GET",
                    headers = header,
                    body = firstString(o, "body", "requestBody"),
                    voice = firstString(o, "voice"),
                    model = firstString(o, "model"),
                ),
            )
        }
        if (out.isEmpty()) throw IllegalArgumentException("没有读到有效的语音引擎")
        return Result(out, skipped)
    }

    private fun normalize(url: String): String {
        var u = url.trim()
        u = DOUBLE_ENCODE.replace(u, "{{speakTextUrl2}}")
        u = SINGLE_ENCODE.replace(u, "{{speakTextUrl}}")
        return u
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return ""
    }
}
