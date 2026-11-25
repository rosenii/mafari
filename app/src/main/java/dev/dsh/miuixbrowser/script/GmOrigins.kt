/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

import java.net.URI

/**
 * 脚本的「origin 级匹配范围」：把 @match / @include（或用户手动填的规则）折成一组 scheme://host[:port] 规则，
 * 供 [GmChannel] 做两件事：
 *  - [originRules]：交给 `addWebMessageListener` / `addJavaScriptOnEvent` 的 origin 白名单（只能表达 origin，不含路径）；
 *  - [allows]：每条通道消息都拿浏览器给出的 `sourceOrigin` 再校验一次，令牌被偷到别的 origin 也用不了。
 *
 * 原则与 Gecko 侧 matchSpec 一致：**只放宽、不收紧**。表达不了的规则（正则、`http*`、主机中间通配、`<all_urls>`、`*`……）
 * 整个范围放宽成「任意 origin」；精确的路径 / 排除判断仍由页面里的运行时（shouldRun）完成。
 * 非 http(s) 规则（file / ftp…）WebView 的 origin 规则表达不了，也不会连上通道：不贡献任何 origin。
 * 纯 JVM，不依赖 Android，可直接单测。
 */
internal class GmOriginScope private constructor(
    /** true = 放宽成任意 origin（没写规则、或有规则表达不了） */
    val matchesAny: Boolean,
    val rules: List<Rule>,
) {
    /** [host] null = 任意主机；[subdomains] 时同时匹配其所有子域；[port] null = 任意端口 */
    data class Rule(val schemes: Set<String>, val host: String?, val subdomains: Boolean, val port: Int?)

    /** [origin] 形如 `https://example.com` / `https://example.com:8443`（浏览器给出的 sourceOrigin） */
    fun allows(origin: String): Boolean {
        if (matchesAny) return true
        val uri = try { URI(origin) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val port = if (uri.port != -1) uri.port else defaultPort(scheme)
        return rules.any { r ->
            scheme in r.schemes &&
                (r.host == null || host == r.host || (r.subdomains && host.endsWith("." + r.host))) &&
                (r.port == null || r.port == port)
        }
    }

    /**
     * addWebMessageListener / addJavaScriptOnEvent 的 origin 规则。放宽或没有可表达规则时返回 `*`
     * （此时是否放行完全由 [allows] 决定，所以没有可表达规则时 [allows] 恒为 false）。
     * 未写端口 = 按「该 scheme 的默认端口」还是「任意端口」解释，需在真机上验证（见 docs/WEBVIEW_GM_CHANNEL.md 清单）。
     */
    fun originRules(): Set<String> {
        if (matchesAny || rules.isEmpty() || rules.any { it.host == null }) return setOf("*")
        return buildSet {
            for (r in rules) for (s in r.schemes) {
                val p = r.port?.let { ":$it" }.orEmpty()
                add("$s://${r.host}$p")
                if (r.subdomains) add("$s://*.${r.host}$p")
            }
        }
    }

    companion object {
        private val ANY = GmOriginScope(true, emptyList())

        private fun defaultPort(scheme: String) = when (scheme) { "http" -> 80; "https" -> 443; else -> -1 }

        // scheme://host[:port][/path…]：@match 与 @include（glob）共用。host 不含 `*`（除了整个是 `*` 或前缀 `*.`），
        // 所以 `http*://…`、`*example.com*`、`www.*.com` 都匹配不上 → 放宽
        private val ENTRY = Regex("^(\\*|[a-zA-Z][a-zA-Z0-9+.\\-]*)://(\\*|\\*\\.[^/*:?#]+|[^/*:?#]+)(?::(\\d+|\\*))?(/.*)?$")

        private sealed interface Parsed {
            data object Anything : Parsed
            data object Nowhere : Parsed
            data class Got(val rule: Rule) : Parsed
        }

        private fun parse(raw: String): Parsed {
            val t = raw.trim()
            if (t.isEmpty() || t == "*" || t == "<all_urls>") return Parsed.Anything
            if (t.length >= 2 && t.startsWith("/") && t.lastIndexOf('/') > 0) return Parsed.Anything // /正则/flags
            val m = ENTRY.find(t) ?: return Parsed.Anything
            val schemes = when (val s = m.groupValues[1].lowercase()) {
                "*" -> setOf("http", "https")
                "http", "https" -> setOf(s)
                else -> return Parsed.Nowhere // file / ftp / 其它：origin 规则表达不了，也不会连上通道
            }
            val hostText = m.groupValues[2].lowercase()
            val (host, sub) = when {
                hostText == "*" -> null to false
                hostText.startsWith("*.") -> hostText.substring(2) to true
                else -> hostText to false
            }
            val portText = m.groupValues[3]
            val port = if (portText.isEmpty() || portText == "*") null else portText.toIntOrNull() ?: return Parsed.Anything
            return Parsed.Got(Rule(schemes, host, sub, port))
        }

        /** 由一组规则文本构造；[globs] 只在 [userOverride] 为空时参与（与运行时 shouldRun 的取舍一致：用户手填规则整体覆盖脚本头） */
        fun of(userOverride: List<String>, matches: List<String>, includes: List<String>): GmOriginScope {
            val sources = if (userOverride.isNotEmpty()) userOverride else matches + includes
            if (sources.isEmpty()) return ANY // 没写规则：所有站点
            val rules = ArrayList<Rule>()
            for (s in sources) {
                when (val p = parse(s)) {
                    Parsed.Anything -> return ANY
                    Parsed.Nowhere -> {}
                    is Parsed.Got -> rules.add(p.rule)
                }
            }
            return GmOriginScope(false, rules.distinct())
        }

        fun of(userOverride: List<String>, meta: UserscriptMeta): GmOriginScope =
            of(userOverride, meta.matches, meta.includes)

        /** 多个脚本的 origin 规则并集（addWebMessageListener 只能装一次，要覆盖所有脚本） */
        fun unionRules(scopes: Collection<GmOriginScope>): Set<String> {
            if (scopes.isEmpty()) return emptySet()
            val out = LinkedHashSet<String>()
            for (s in scopes) {
                val r = s.originRules()
                if ("*" in r) return setOf("*")
                out.addAll(r)
            }
            return out
        }
    }
}
