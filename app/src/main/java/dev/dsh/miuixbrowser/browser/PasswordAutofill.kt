package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.util.hostOf

/**
 * 自动填充的站点匹配（纯函数，不碰 Android / Gecko，见 PasswordAutofillTest）。
 *
 * Gecko 的 `Autocomplete.StorageDelegate.onLoginFetch(domain)` 只要「候选」：真正的精确匹配
 * （来源 / 表单提交目标 / 认证域是否一致）由 Gecko 自己在拿到候选后再筛一遍，所以这里宁可多给不要漏给，
 * 但有一条红线——不能把别的站点的凭据当候选交出去：`evilexample.com` 不是 `example.com` 的子域。
 */
internal object PasswordAutofill {

    /** 来源（scheme://host[:port]）里的主机，小写、去端口；IPv6 保留方括号。取不到返回 null。 */
    fun hostOf(origin: String): String? {
        val rest = origin.substringAfter("://", origin).substringBefore('/').substringAfterLast('@')
        val host = if (rest.startsWith("[")) rest.substringBefore(']') + "]" else rest.substringBefore(':')
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }

    /**
     * 已存记录的来源 [origin] 能否作为页面域名 [domain]（可以是主机名，也可以是带协议的网址）的填充候选：
     * 主机相同，或其中一方是另一方的子域（`www.example.com` ↔ `example.com`）。端口、协议不参与（交给 Gecko 精筛）。
     */
    fun hostMatches(origin: String, domain: String): Boolean {
        val have = hostOf(origin) ?: return false
        val want = hostOf(domain) ?: return false
        return have == want || have.endsWith(".$want") || want.endsWith(".$have")
    }

    /**
     * 页面协议是否允许使用这条记录：https 站点保存的凭据不填进 http 页面（明文传输会泄露）。
     * 页面协议未知（[pageScheme] 为 null）时不拦——系统自动填充只在受信浏览器里生效，由它保证。
     */
    fun schemeAllows(origin: String, pageScheme: String?): Boolean {
        val saved = origin.substringBefore("://", "https").lowercase()
        return !(saved == "https" && pageScheme.equals("http", ignoreCase = true))
    }

    /**
     * 系统自动填充用的站点判断：主机必须相同（只忽略开头的 `www.`），**不**像 [hostMatches] 那样放过子域。
     * Gecko 是「候选宽松、Gecko 再精筛」；系统自动填充没有下一层，放过子域会让 `sites.example.com` 这类
     * 用户可自建内容的子站拿到 `example.com` 的密码。
     */
    fun sameSite(origin: String, pageHost: String): Boolean {
        val have = hostOf(origin)?.removePrefix("www.") ?: return false
        val want = hostOf(pageHost)?.removePrefix("www.") ?: return false
        return have == want
    }

    /**
     * 系统自动填充（[MafariAutofillService]）用的完整判断：只给网页表单凭据（HTTP 认证凭据不进网页表单），
     * 站点要严格对得上（[sameSite]）、协议不能降级（[schemeAllows]）。
     */
    fun usable(origin: String, httpRealm: String?, pageHost: String, pageScheme: String?): Boolean =
        httpRealm == null && sameSite(origin, pageHost) && schemeAllows(origin, pageScheme)
}
