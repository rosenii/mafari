/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.util

import dev.dsh.miuixbrowser.i18n.tr

import android.net.Uri
import java.net.URLEncoder

/**
 * 地址栏输入与网址的纯函数：判断「像不像网址」、补协议、取主机、判断同站。
 * 原来放在 BrowserState.kt 顶部，历史 / 收藏 / 图标 / 脚本都在用，单独成文件后可以脱离标签页状态做单元测试
 * （looksLikeUrl / toUrl / isSameSite 不依赖 Android 类；hostOf / isUserScriptUrl 用了 android.net.Uri，需要仪器测试）。
 */

private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

private val DOMAIN_REGEX = Regex("^[A-Za-z0-9\\-._~%]+(\\.[A-Za-z0-9\\-._~%]+)+$")
private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
private val SINGLE_LABEL_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9\\-_]*$")

/** 拆出「主机」「端口」；不合法返回 null。支持 [IPv6]:端口。 */
private fun splitHostPort(authority: String): Pair<String, Int?>? {
    var host = authority.substringAfterLast('@') // 去掉 user:pass@
    var port: Int? = null
    if (host.startsWith("[")) {
        val end = host.indexOf(']')
        if (end < 0) return null
        val rest = host.substring(end + 1)
        if (rest.isNotEmpty()) {
            if (!rest.startsWith(":")) return null
            port = rest.drop(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        host = host.substring(0, end + 1)
        return host to port
    }
    val idx = host.lastIndexOf(':')
    if (idx >= 0) {
        val p = host.substring(idx + 1)
        if (p.isNotEmpty()) port = p.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        host = host.substring(0, idx)
    }
    return host to port
}

private fun isIpv4(host: String): Boolean {
    val m = IPV4_REGEX.matchEntire(host) ?: return false
    return m.groupValues.drop(1).all { it.toInt() in 0..255 }
}

/** 没写协议时该补 http 还是 https：IP / localhost / .local / 单段主机名（局域网服务）走 http，其余走 https */
private fun defaultSchemeFor(host: String, port: Int?): String {
    val h = host.lowercase()
    val local = h == "localhost" || h.startsWith("[") || isIpv4(h) || h.endsWith(".local") || !h.contains('.')
    return if (local && port != 443) "http" else "https"
}

/** 判断输入是网址还是搜索词。 */
fun looksLikeUrl(input: String): Boolean {
    val s = input.trim()
    if (s.isEmpty() || s.any { it.isWhitespace() }) return false
    if (s.startsWith("about:", ignoreCase = true)) return true
    if (SCHEME_REGEX.containsMatchIn(s)) return true
    // 无协议：主机[:端口][/路径?查询#片段]
    val authority = s.substringBefore('/').substringBefore('?').substringBefore('#')
    val (host, port) = splitHostPort(authority) ?: return false
    if (host.isEmpty()) return false
    return when {
        host.equals("localhost", ignoreCase = true) -> true
        host.startsWith("[") -> true                       // IPv6 字面量
        isIpv4(host) -> true                               // 192.168.1.1 / 192.168.1.1:8080
        DOMAIN_REGEX.matches(host) -> true                 // example.com / example.com:8443/path
        port != null && SINGLE_LABEL_REGEX.matches(host) -> true // nas:5000 这类局域网主机名
        else -> false
    }
}

/** 把地址栏输入变成可加载的 URL，不像网址就走搜索。 */
fun toUrl(input: String, searchTemplate: String = "https://www.baidu.com/s?wd=%s"): String {
    val s = input.trim()
    if (!looksLikeUrl(s)) return searchTemplate.replace("%s", URLEncoder.encode(s, "UTF-8"))
    if (s.startsWith("about:", ignoreCase = true) || SCHEME_REGEX.containsMatchIn(s)) return s
    val authority = s.substringBefore('/').substringBefore('?').substringBefore('#')
    val (host, port) = splitHostPort(authority) ?: return "https://$s"
    return "${defaultSchemeFor(host, port)}://$s"
}

fun hostOf(url: String): String {
    val trimmed = url.trim()
    if (trimmed.startsWith("data:", ignoreCase = true)) return tr("内嵌图片")
    val host = runCatching { Uri.parse(trimmed).host }.getOrNull()
    return if (!host.isNullOrBlank()) host else trimmed.take(48)
}

private val SECOND_LEVEL_LABELS = setOf("com", "net", "org", "gov", "edu", "co", "ac")
private val PRIVATE_SUFFIXES = setOf("github.io", "vercel.app", "pages.dev", "netlify.app", "blogspot.com", "workers.dev")

/** 取站点标识（近似可注册域名）：m.example.com / www.example.com → example.com，a.co.uk → a.co.uk */
private fun siteOf(host: String): String {
    val h = host.lowercase().trim('.')
    if (h.isEmpty() || h.contains(':') || h.all { it.isDigit() || it == '.' }) return h
    val parts = h.split('.')
    if (parts.size <= 2) return h
    val tail2 = parts.takeLast(2).joinToString(".")
    val n = if ((parts.last().length == 2 && parts[parts.size - 2] in SECOND_LEVEL_LABELS) || tail2 in PRIVATE_SUFFIXES) 3 else 2
    return parts.takeLast(n).joinToString(".")
}

/** 两个 host 是否同站 */
fun isSameSite(a: String, b: String): Boolean = siteOf(a) == siteOf(b)

/** 链接是否指向 .user.js（忽略查询串与片段） */
fun isUserScriptUrl(url: String): Boolean {
    val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
    return path.endsWith(".user.js", ignoreCase = true)
}
