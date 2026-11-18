package dev.dsh.miuixbrowser.ext

import java.net.URI

/**
 * WebExtensions 标准 match pattern（MDN「Match patterns」）。
 * 形如 `<scheme>://<host><path>` 或 `<all_urls>`；path 对「路径 + 查询串」匹配，不含 #fragment。
 * 纯 JVM 实现，不依赖 Android，可直接单测。
 */
class MatchPattern private constructor(
    val raw: String,
    val schemes: Set<String>,
    /** null = 任意主机；否则为小写主机（不含前导 `*.`） */
    private val host: String?,
    /** host 来自 `*.example.com` 时为 true：同时匹配 example.com 与其所有子域 */
    private val subdomains: Boolean,
    /** null 或 "*" = 任意端口；否则为数字串 */
    private val port: String?,
    /** null = 任意路径 */
    private val pathRegex: Regex?,
) {
    val matchesAllUrls: Boolean get() = raw == ALL_URLS

    fun matches(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in schemes) return false
        if (host != null) {
            val h = uri.host?.lowercase() ?: return false
            val ok = if (subdomains) h == host || h.endsWith(".$host") else h == host
            if (!ok) return false
        }
        if (port != null && port != "*") {
            val actual = if (uri.port != -1) uri.port else defaultPort(scheme)
            if (actual != port.toInt()) return false
        }
        val regex = pathRegex ?: return true
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        return regex.matches(path)
    }

    /**
     * 转成 WebView `addDocumentStartJavaScript` / `addWebMessageListener` 的 origin 规则（只能表达 scheme://host[:port]，
     * 不含路径，也只支持 http/https）。主机为任意时退回 "*"，更精确的路径过滤由运行时脚本完成。
     * 未写端口时的匹配语义需在真机上验证。
     */
    fun toOriginRules(): Set<String> {
        val web = schemes.filter { it == "http" || it == "https" }
        if (web.isEmpty()) return emptySet()
        if (host == null) return setOf("*")
        val p = if (port != null && port != "*") ":$port" else ""
        return buildSet {
            for (s in web) {
                add("$s://$host$p")
                if (subdomains) add("$s://*.$host$p")
            }
        }
    }

    override fun toString() = raw

    companion object {
        const val ALL_URLS = "<all_urls>"
        private val ALL_SCHEMES = setOf("http", "https", "ws", "wss", "ftp", "file", "data")
        private val WILDCARD_SCHEMES = setOf("http", "https", "ws", "wss")

        private fun defaultPort(scheme: String) = when (scheme) {
            "http", "ws" -> 80
            "https", "wss" -> 443
            "ftp" -> 21
            else -> -1
        }

        /** 判断一个 permissions 条目是不是 host 权限（而不是 API 权限） */
        fun looksLikeHostPermission(s: String) = s == ALL_URLS || s.contains("://")

        fun parse(raw: String): MatchPattern? {
            val s = raw.trim()
            if (s == ALL_URLS) return MatchPattern(s, ALL_SCHEMES, null, false, null, null)
            val sep = s.indexOf("://")
            if (sep <= 0) return null
            val schemeText = s.substring(0, sep).lowercase()
            val schemes = when {
                schemeText == "*" -> WILDCARD_SCHEMES
                schemeText in ALL_SCHEMES -> setOf(schemeText)
                else -> return null
            }
            val rest = s.substring(sep + 3)
            val slash = rest.indexOf('/')
            if (slash < 0) return null // path 必须存在
            var hostPart = rest.substring(0, slash)
            val path = rest.substring(slash)

            var port: String? = null
            val colon = hostPart.lastIndexOf(':')
            if (colon >= 0) {
                port = hostPart.substring(colon + 1)
                hostPart = hostPart.substring(0, colon)
                if (port != "*" && (port.isEmpty() || !port.all { it.isDigit() })) return null
            }

            var host: String? = null
            var subdomains = false
            if (schemeText == "file") {
                if (hostPart.isNotEmpty()) return null
            } else {
                when {
                    hostPart.isEmpty() -> return null
                    hostPart == "*" -> host = null
                    hostPart.startsWith("*.") -> {
                        host = hostPart.substring(2).lowercase()
                        subdomains = true
                    }
                    else -> host = hostPart.lowercase()
                }
                if (host != null && (host.isEmpty() || host.contains('*'))) return null
            }

            val regex = if (path == "/*") null else {
                Regex(path.split('*').joinToString(".*") { Regex.escape(it) })
            }
            return MatchPattern(s, schemes, host, subdomains, port, regex)
        }

        fun anyMatches(patterns: List<MatchPattern>, url: String) = patterns.any { it.matches(url) }
    }
}
