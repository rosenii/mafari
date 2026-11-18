package dev.dsh.miuixbrowser.translate

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Microsoft（Bing）翻译（免 Key）：Edge 浏览器内置翻译用的接口。
 * 先到 edge.microsoft.com/translate/auth 领一个短期 JWT，再带着它调 api-edge.cognitive.microsofttranslator.com。
 * token 缓存到过期前一分钟；翻译请求遇到 401 会刷新 token 重试一次。
 */
class BingSource(
    private val authUrl: String = "https://edge.microsoft.com/translate/auth",
    private val apiUrl: String = "https://api-edge.cognitive.microsofttranslator.com",
    private val clock: () -> Long = System::currentTimeMillis,
) : TranslateSource {
    override val id = TranslateSourceId.BING

    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt = 0L

    override fun translate(texts: List<String>, to: String): List<String> {
        return try {
            call(texts, to, ensureToken(force = false))
        } catch (e: TranslateException) {
            if (e.kind != TranslateException.Kind.AUTH) throw e
            call(texts, to, ensureToken(force = true))
        }
    }

    private fun call(texts: List<String>, to: String, tk: String): List<String> {
        val body = JSONArray().also { arr -> texts.forEach { arr.put(JSONObject().put("Text", it)) } }.toString()
        val url = "$apiUrl/translate?api-version=3.0&to=${TranslateHttp.urlEncode(mapLang(to))}&textType=plain"
        val r = TranslateHttp.request(
            "POST", url,
            headers = mapOf("Authorization" to "Bearer $tk", "Content-Type" to "application/json;charset=UTF-8"),
            body = body,
        )
        TranslateHttp.check(r)
        return parse(r.body, texts.size)
    }

    @Synchronized
    private fun ensureToken(force: Boolean): String {
        val cur = token
        if (!force && cur != null && clock() < tokenExpiresAt) return cur
        val r = TranslateHttp.request("GET", authUrl)
        TranslateHttp.check(r)
        val t = r.body.trim()
        // 正常是 JWT（三段、点分隔）；拿到网页 / 错误文本说明被拦截了
        if (t.isEmpty() || t.length > 4096 || t.count { it == '.' } != 2 || t.any { it.isWhitespace() || it == '<' }) {
            throw TranslateException(TranslateException.Kind.AUTH, "bing: bad token")
        }
        token = t
        tokenExpiresAt = (jwtExpiryMillis(t) ?: (clock() + 8 * 60_000L)) - 60_000L
        return t
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

        /** JWT 的 exp（秒）→ 毫秒；解析不了返回 null */
        fun jwtExpiryMillis(jwt: String): Long? = runCatching {
            val payload = jwt.split('.').getOrNull(1) ?: return null
            val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            JSONObject(json).optLong("exp", 0L).takeIf { it > 0 }?.times(1000)
        }.getOrNull()
    }
}
