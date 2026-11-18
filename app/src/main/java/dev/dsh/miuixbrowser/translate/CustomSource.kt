package dev.dsh.miuixbrowser.translate

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 自定义翻译源：兼容 LibreTranslate 的 `POST {地址}/translate`。
 * 请求 `{"q":[...],"source":"auto","target":"zh","format":"text","api_key":"..."}`，
 * 响应 `{"translatedText":[...]}`（q 是数组时为数组，单条时部分版本返回字符串，两种都认）。
 */
class CustomSource(private val baseUrl: String, private val apiKey: String) : TranslateSource {
    override val id = TranslateSourceId.CUSTOM

    override fun translate(texts: List<String>, to: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            throw TranslateException(TranslateException.Kind.CONFIG, "url")
        }
        val url = if (base.endsWith("/translate", true)) base else "$base/translate"
        val body = JSONObject()
            .put("q", JSONArray(texts))
            .put("source", "auto")
            .put("target", mapLang(to))
            .put("format", "text")
        if (apiKey.isNotBlank()) body.put("api_key", apiKey.trim())
        val r = TranslateHttp.request(
            "POST", url,
            headers = mapOf("Content-Type" to "application/json;charset=UTF-8"),
            body = body.toString(),
        )
        if (r.code !in 200..299) {
            val msg = runCatching { JSONObject(r.body).optString("error") }.getOrDefault("")
            if (msg.isNotEmpty()) {
                val kind = if (r.code == 400 && msg.contains("key", true) || r.code == 401 || r.code == 403) {
                    TranslateException.Kind.AUTH
                } else {
                    TranslateException.Kind.HTTP
                }
                throw TranslateException(kind, "HTTP ${r.code} $msg")
            }
        }
        TranslateHttp.check(r)
        return parse(r.body, texts.size)
    }

    internal companion object {
        /** 我们的代码 → LibreTranslate 的：它用 zh（简体）/ zt（繁体） */
        fun mapLang(code: String): String = when (code) {
            "zh-CN" -> "zh"
            "zh-TW" -> "zt"
            else -> code
        }

        fun parse(body: String, n: Int): List<String> {
            val root = try {
                JSONObject(body.trim())
            } catch (e: JSONException) {
                throw TranslateException(TranslateException.Kind.PARSE, "custom: not json", e)
            }
            val t = root.opt("translatedText")
            val list = when (t) {
                is JSONArray -> List(t.length()) { t.optString(it) }
                is String -> listOf(t)
                else -> throw TranslateException(TranslateException.Kind.PARSE, "custom: no translatedText")
            }
            if (list.size != n) throw TranslateException(TranslateException.Kind.PARSE, "custom: expected $n got ${list.size}")
            return list
        }
    }
}
