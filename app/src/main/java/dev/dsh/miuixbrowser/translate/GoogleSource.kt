package dev.dsh.miuixbrowser.translate

import org.json.JSONArray
import org.json.JSONException

/**
 * Google 翻译（免 Key）：translate.googleapis.com 的 gtx 客户端接口，网页版 / 各类浏览器插件都在用。
 * 批量走 translate_a/t（POST 表单，多个 q 一次译完）；这个接口拒绝或格式不对时，逐条退回 translate_a/single。
 * 注意：国内网络通常连不上，需要系统层代理 / VPN（应用自带的代理设置只作用于 WebView，不管这里的原生请求）。
 */
class GoogleSource(private val baseUrl: String = "https://translate.googleapis.com") : TranslateSource {
    override val id = TranslateSourceId.GOOGLE

    override fun translate(texts: List<String>, to: String): List<String> {
        try {
            return batch(texts, to)
        } catch (e: TranslateException) {
            // 网络不通 / 限流没必要再逐条试；只有「接口不认」才降级
            if (e.kind == TranslateException.Kind.NETWORK || e.kind == TranslateException.Kind.AUTH) throw e
            if (e.detail.startsWith("HTTP 429")) throw e
        }
        return texts.map { single(it, to) }
    }

    private fun batch(texts: List<String>, to: String): List<String> {
        val form = StringBuilder()
        for (t in texts) {
            if (form.isNotEmpty()) form.append('&')
            form.append("q=").append(TranslateHttp.urlEncode(t))
        }
        val url = "$baseUrl/translate_a/t?client=gtx&sl=auto&tl=${TranslateHttp.urlEncode(to)}&dt=t&ie=UTF-8&oe=UTF-8"
        val r = TranslateHttp.request(
            "POST", url,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"),
            body = form.toString(),
        )
        TranslateHttp.check(r)
        return parseBatch(r.body, texts.size)
    }

    private fun single(text: String, to: String): String {
        val url = "$baseUrl/translate_a/single?client=gtx&sl=auto&tl=${TranslateHttp.urlEncode(to)}&dt=t&ie=UTF-8&oe=UTF-8&q=" +
            TranslateHttp.urlEncode(text)
        val r = TranslateHttp.request("GET", url)
        TranslateHttp.check(r)
        val root = parseArray(r.body)
        return joinSegments(root.optJSONArray(0)) ?: throw TranslateException(TranslateException.Kind.PARSE, "google single")
    }

    internal companion object {
        private fun parseArray(body: String): JSONArray = try {
            JSONArray(body.trim())
        } catch (e: JSONException) {
            throw TranslateException(TranslateException.Kind.PARSE, "google: not json", e)
        }

        /**
         * 解析 translate_a/t。已知的几种形态：
         *  - 多条：`[["译1","en"],["译2","en"]]`
         *  - 单条：`["译1","en"]`（根上直接是字符串）或 `[["译1","en"]]`
         *  - 逐句：`[[["译","原","",""],...],null,"en"]`（single 接口的形态，偶尔也会出现在 t 上）
         */
        fun parseBatch(body: String, n: Int): List<String> {
            val root = parseArray(body)
            if (n == 1 && root.length() >= 1 && root.get(0) is String) return listOf(root.getString(0))
            if (root.length() != n) {
                // 单条的逐句形态：根长度是 3，第 0 项是句子数组
                if (n == 1) joinSegments(root.optJSONArray(0))?.let { return listOf(it) }
                throw TranslateException(TranslateException.Kind.PARSE, "google: expected $n got ${root.length()}")
            }
            val out = ArrayList<String>(n)
            for (i in 0 until n) {
                val e = root.get(i)
                out += when (e) {
                    is String -> e
                    is JSONArray -> when {
                        e.length() > 0 && e.get(0) is String -> e.getString(0)
                        else -> joinSegments(e) ?: throw TranslateException(TranslateException.Kind.PARSE, "google: item $i")
                    }
                    else -> throw TranslateException(TranslateException.Kind.PARSE, "google: item $i")
                }
            }
            return out
        }

        /** `[["译句1","原句1",...],["译句2","原句2",...]]` → 译句拼接 */
        fun joinSegments(segments: JSONArray?): String? {
            if (segments == null) return null
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i) ?: continue
                val part = seg.opt(0)
                if (part is String) sb.append(part)
            }
            return if (sb.isEmpty()) null else sb.toString()
        }
    }
}
