/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import android.net.Uri
import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * WebView CookieManager adapter. WebView exposes URL-scoped reads, not a global cookie enumeration API.
 * 系统 WebView 支持 GET_COOKIE_INFO（CookieManagerCompat，1.6）时读到真实的 domain / path / secure / httpOnly /
 * sameSite / 过期时间；不支持时退回 getCookie 的 name=value，且不再凭空补属性。
 */
class CookiesApi {
    fun register(router: ApiRouter) {
        router.register("cookies.get") { ctx, args ->
            val details = args.argOrNull(0) as? JSONObject ?: bad("cookies.get 需要 details 对象")
            val url = validatedUrl(details.optString("url"))
            requireHostAccess(ctx, url)
            val name = details.optString("name")
            if (name.isBlank()) bad("cookies.get 需要 name")
            // 同名取 path 最长的（和浏览器发送 Cookie 头时的排序一致）
            readCookies(url).filter { it.optString("name") == name }.maxByOrNull { it.optString("path").length }
        }
        router.register("cookies.getAll") { ctx, args ->
            val d = args.argOrNull(0) as? JSONObject ?: bad("cookies.getAll 需要 details 对象")
            val allowed = setOf("url", "name", "domain", "path", "secure", "session", "storeId")
            val keys = d.keys(); while (keys.hasNext()) { val k = keys.next(); if (k !in allowed) throw ApiException(ErrorCodes.UNSUPPORTED, "cookies.getAll 暂不支持字段：$k") }
            // WebView 只能按 URL 读，没有全局枚举：不给 url 就明确报错，而不是悄悄返回空数组让扩展以为没有 cookie
            if (!d.has("url")) throw ApiException(ErrorCodes.UNSUPPORTED, "cookies.getAll 在 WebView 上必须提供 url（没有全局枚举）")
            val url = validatedUrl(d.optString("url"))
            requireHostAccess(ctx, url)
            if (d.has("storeId") && d.optString("storeId") != "0") return@register JSONArray()
            val name = d.optString("name").takeIf { d.has("name") }
            val domain = d.optString("domain").takeIf { it.isNotBlank() }
            val path = d.optString("path").takeIf { it.isNotBlank() }
            val secure = if (d.has("secure")) d.optBoolean("secure") else null
            val session = if (d.has("session")) d.optBoolean("session") else null
            val out = JSONArray()
            readCookies(url).filter { CookieInfo.matches(it, name, domain, path, secure, session) }.forEach { out.put(it) }
            out
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

    /** 该 URL 可见的全部 cookie。有 GET_COOKIE_INFO 就带真实属性，否则只有 name / value。 */
    private suspend fun readCookies(url: String): List<JSONObject> = withContext(Dispatchers.Main.immediate) {
        val cm = CookieManager.getInstance()
        val host = Uri.parse(url).host.orEmpty()
        val withInfo = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO) }.getOrDefault(false)
        if (withInfo) {
            val parsed = runCatching { CookieManagerCompat.getCookieInfo(cm, url) }.getOrNull()
            if (parsed != null) return@withContext parsed.mapNotNull { CookieInfo.parse(it, host) }
        }
        cm.getCookie(url).orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { cookieFromPair(it) }
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
