package dev.dsh.miuixbrowser

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
}
