package dev.dsh.miuixbrowser.translate

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Microsoft（Bing）翻译（免 Key）：Edge 浏览器内置翻译用的接口。
 * 直接 POST https://edge.microsoft.com/translate/translatetext，不再需要先领 JWT（旧的 /translate/auth + api-edge 已失效）。
 * 请求体是字符串数组，响应与以前一致：[{ translations: [{ text, to }] }, ...]。
 */
class BingSource(
    private val apiUrl: String = "https://edge.microsoft.com/translate/translatetext",
) : TranslateSource {
    override val id = TranslateSourceId.BING

    override fun translate(texts: List<String>, to: String): List<String> {
        val body = JSONArray().also { arr -> texts.forEach { arr.put(it) } }.toString()
        val url = "$apiUrl?from=&to=${TranslateHttp.urlEncode(mapLang(to))}&isEnterpriseClient=false"
        val r = TranslateHttp.request(
            "POST", url,
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
        )
        TranslateHttp.check(r)
        return parse(r.body, texts.size)
    }

    internal companion object {
        /** 我们的语言代码 → Bing 的 */
        fun mapLang(code: String): String = when (code) {
            "zh-CN" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> code
        }

        fun parse(body: String, n: Int): List<String> {
            val root = try {
                JSONArray(body.trim())
            } catch (e: JSONException) {
                throw TranslateException(TranslateException.Kind.PARSE, "bing: not json", e)
            }
            if (root.length() != n) throw TranslateException(TranslateException.Kind.PARSE, "bing: expected $n got ${root.length()}")
            return List(n) { i ->
                root.optJSONObject(i)?.optJSONArray("translations")?.optJSONObject(0)?.optString("text", null)
                    ?: throw TranslateException(TranslateException.Kind.PARSE, "bing: item $i")
            }
        }
    }
}
