package dev.dsh.miuixbrowser.ext

import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/** WebView CookieManager adapter. WebView exposes URL-scoped reads, not a global cookie enumeration API. */
class CookiesApi {
    fun register(router: ApiRouter) {
        router.register("cookies.get") { ctx, args ->
            val details = args.argOrNull(0) as? JSONObject ?: bad("cookies.get 需要 details 对象")
            val url = validatedUrl(details.optString("url"))
            requireHostAccess(ctx, url)
            val name = details.optString("name")
            if (name.isBlank()) bad("cookies.get 需要 name")
            withContext(Dispatchers.Main.immediate) {
                val raw = CookieManager.getInstance().getCookie(url).orEmpty()
                val pair = raw.split(';').asSequence().map { it.trim() }.firstOrNull { it.substringBefore('=') == name }
                pair?.let { cookieFromPair(it) }
            }
        }
        router.register("cookies.set") { ctx, args ->
            val d = args.argOrNull(0) as? JSONObject ?: bad("cookies.set 需要 details 对象")
            val url = validatedUrl(d.optString("url")); requireHostAccess(ctx, url)
            val name = d.optString("name")
            if (name.isBlank() || name.any { it == ';' || it == '=' || it.isWhitespace() }) bad("Cookie name 无效")
            val value = d.optString("value")
            if (value.contains(';') || value.contains('\n') || value.contains('\r')) bad("Cookie value 含有非法字符")
            val allowed = setOf("url", "name", "value", "domain", "path", "secure", "httpOnly", "sameSite", "expirationDate")
            val keys = d.keys(); while (keys.hasNext()) { val k = keys.next(); if (k !in allowed) throw ApiException(ErrorCodes.UNSUPPORTED, "cookies.set 暂不支持字段：$k") }
            val host = Uri.parse(url).host.orEmpty().lowercase()
            val domain = d.optString("domain").takeIf { it.isNotBlank() }
            if (domain != null) {
                val normalized = domain.removePrefix(".").lowercase()
                if (host != normalized && !host.endsWith(".$normalized")) bad("Cookie domain 与 URL 主机不匹配")
            }
            val path = d.optString("path").ifBlank { "/" }
            if (!path.startsWith("/") || path.contains(';') || path.contains('\r') || path.contains('\n')) bad("Cookie path 无效")
            val secure = d.optBoolean("secure")
            if (secure && Uri.parse(url).scheme != "https") bad("Secure Cookie 必须使用 HTTPS URL")
            val sameSite = d.optString("sameSite", "unspecified")
            if (sameSite !in setOf("no_restriction", "lax", "strict", "unspecified")) bad("sameSite 无效")
            if (sameSite == "no_restriction" && !secure) bad("SameSite=None 必须同时设置 secure=true")
            val cookie = buildString {
                append(name).append('=').append(value)
                if (domain != null) append("; Domain=").append(domain)
                append("; Path=").append(path)
                if (secure) append("; Secure")
                if (d.optBoolean("httpOnly")) append("; HttpOnly")
                when (sameSite) { "no_restriction" -> append("; SameSite=None"); "lax" -> append("; SameSite=Lax"); "strict" -> append("; SameSite=Strict") }
                if (d.has("expirationDate")) {
                    val exp = d.optDouble("expirationDate", Double.NaN)
                    if (!exp.isFinite()) bad("expirationDate 无效")
                    append("; Expires=").append(java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(java.util.Date((exp * 1000.0).toLong())))
                }
            }
            val success = setCookie(url, cookie)
            if (!success) null else cookieFromPair("$name=$value").apply {
                put("domain", domain ?: host); put("path", path); put("secure", secure); put("httpOnly", d.optBoolean("httpOnly")); put("sameSite", sameSite)
                if (d.has("expirationDate")) put("expirationDate", d.optDouble("expirationDate")) else put("session", true)
            }
        }
        router.register("cookies.remove") { ctx, args ->
            val d = args.argOrNull(0) as? JSONObject ?: bad("cookies.remove 需要 details 对象")
            val url = validatedUrl(d.optString("url")); requireHostAccess(ctx, url)
            val name = d.optString("name")
            if (name.isBlank() || name.any { it == ';' || it == '=' }) bad("Cookie name 无效")
            val path = d.optString("path").ifBlank { "/" }
            if (!path.startsWith("/") || path.contains(';') || path.contains('\r') || path.contains('\n')) bad("Cookie path 无效")
            val domain = d.optString("domain").takeIf { it.isNotBlank() }
            val host = Uri.parse(url).host.orEmpty().lowercase()
            if (domain != null) {
                val normalized = domain.removePrefix(".").lowercase()
                if (host != normalized && !host.endsWith(".$normalized")) bad("Cookie domain 与 URL 主机不匹配")
                if (domain.contains(';') || domain.contains('\r') || domain.contains('\n')) bad("Cookie domain 无效")
            }
            val cookie = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path" + (domain?.let { "; Domain=$it" } ?: "")
            if (!setCookie(url, cookie)) null else JSONObject().put("url", url).put("name", name).put("storeId", "0")
        }
    }

    private suspend fun setCookie(url: String, value: String): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { c ->
            try { CookieManager.getInstance().setCookie(url, value) { ok -> if (c.isActive) c.resume(ok) } }
            catch (_: Throwable) { if (c.isActive) c.resume(false) }
        }.also { if (it) CookieManager.getInstance().flush() }
    }

    private fun requireHostAccess(ctx: CallContext, url: String) {
        if (!PermissionChecker.hasHostAccess(ctx.ext, url)) throw ApiException(ErrorCodes.DENIED_PERMISSION, "访问该 Cookie 需要对应站点的 host permission")
    }

    private fun validatedUrl(raw: String): String {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) bad("Cookie URL 必须是有效的 HTTP/HTTPS 地址")
        return uri.toString()
    }

    private fun cookieFromPair(pair: String): JSONObject =
        JSONObject().put("name", pair.substringBefore('=')).put("value", pair.substringAfter('=', "")).put("storeId", "0")

    private fun bad(message: String): Nothing = throw ApiException(ErrorCodes.BAD_REQUEST, message)
}
