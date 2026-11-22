package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把 `CookieManagerCompat.getCookieInfo`（1.6，RFC 6265 Set-Cookie 格式的字符串，如
 * `name=value; domain=.example.com; path=/`）解析成 WebExtensions 的 `cookies.Cookie`。
 * 纯 JVM 逻辑，不依赖 Android，方便单测。
 *
 * 取舍：getCookieInfo 返回的是「该 URL 可见的 cookie 及其设置属性」，各属性是否齐全取决于系统 WebView，
 * 所以每个属性都按「缺省 = 未设置」处理，不凭空补值（和旧版用调用参数拼 domain / path 的做法相反）。
 */
object CookieInfo {
    /** [requestHost] 用来给没有 Domain 属性的 host-only cookie 填 domain。 */
    fun parse(setCookie: String, requestHost: String): JSONObject? {
        val parts = setCookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val first = parts.firstOrNull() ?: return null
        val eq = first.indexOf('=')
        // 没有 '=' 的 cookie（name 为空）按 RFC 6265bis 视为 name="" value=整段
        val name = if (eq >= 0) first.substring(0, eq).trim() else ""
        val value = if (eq >= 0) first.substring(eq + 1) else first

        var domain: String? = null
        var path = "/"
        var secure = false
        var httpOnly = false
        var sameSite = "unspecified"
        var expires: Double? = null
        var maxAge: Double? = null
        for (attr in parts.drop(1)) {
            val k = attr.substringBefore('=').trim().lowercase(Locale.ROOT)
            val v = attr.substringAfter('=', "").trim()
            when (k) {
                "domain" -> domain = v.takeIf { it.isNotEmpty() }
                "path" -> if (v.startsWith("/")) path = v
                "secure" -> secure = true
                "httponly" -> httpOnly = true
                "samesite" -> sameSite = when (v.lowercase(Locale.ROOT)) {
                    "none" -> "no_restriction"
                    "lax" -> "lax"
                    "strict" -> "strict"
                    else -> "unspecified"
                }
                "expires" -> expires = parseHttpDate(v)
                "max-age" -> maxAge = v.toDoubleOrNull()
            }
        }
        val out = JSONObject()
            .put("name", name)
            .put("value", value)
            .put("domain", (domain ?: requestHost).lowercase(Locale.ROOT))
            .put("hostOnly", domain == null)
            .put("path", path)
            .put("secure", secure)
            .put("httpOnly", httpOnly)
            .put("sameSite", sameSite)
            .put("storeId", "0")
        // Max-Age 优先于 Expires（RFC 6265 §5.3）；都没有就是会话 cookie
        val exp = maxAge?.let { System.currentTimeMillis() / 1000.0 + it } ?: expires
        if (exp != null) out.put("session", false).put("expirationDate", exp) else out.put("session", true)
        return out
    }

    /** `cookies.getAll` 的过滤：name / domain / path / secure / session。domain 按后缀匹配（Chrome / Firefox 一致）。 */
    fun matches(cookie: JSONObject, name: String?, domain: String?, path: String?, secure: Boolean?, session: Boolean?): Boolean {
        if (name != null && cookie.optString("name") != name) return false
        if (domain != null) {
            val want = domain.removePrefix(".").lowercase(Locale.ROOT)
            val have = cookie.optString("domain").removePrefix(".").lowercase(Locale.ROOT)
            if (have != want && !have.endsWith(".$want")) return false
        }
        if (path != null && cookie.optString("path") != path) return false
        if (secure != null && cookie.optBoolean("secure") != secure) return false
        if (session != null && cookie.optBoolean("session") != session) return false
        return true
    }

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd-MMM-yyyy HH:mm:ss zzz",
        "EEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM dd HH:mm:ss yyyy",
    )

    internal fun parseHttpDate(raw: String): Double? {
        for (pattern in dateFormats) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }
            val date: Date? = runCatching { fmt.parse(raw) }.getOrNull()
            if (date != null) return date.time / 1000.0
        }
        return null
    }
}
