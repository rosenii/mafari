/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

/**
 * tabs.create / tabs.update 能让浏览器打开什么网址。拓展不可信，所以用白名单：
 * - `http` / `https`（必须有主机名）；
 * - `about:blank`；
 * - 本拓展自己的页面（相对路径会按拓展的虚拟 origin 展开，如 `options.html`）。
 * 其余一律拒绝：`javascript:` / `data:` / `file:` / `content:` / `intent:` / `blob:` 以及各种自定义 scheme，
 * 还有其它拓展的页面（防止一个拓展去打开另一个拓展的内部页面）。
 *
 * 注意：本拓展页面的网址能否在普通标签里真正显示，取决于浏览器侧是否拦截了该 origin（见 [ExtensionOrigins]），
 * 这里只负责放行，不保证能显示。
 */
object TabUrlPolicy {
    private const val MAX_URL_CHARS = 16 * 1024
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** 返回可以交给浏览器的完整网址；不允许时抛 [ApiException]（BAD_REQUEST） */
    fun normalize(extId: String, raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) throw bad("网址为空")
        if (url.length > MAX_URL_CHARS) throw bad("网址过长")
        if (url.any { it.isISOControl() }) throw bad("网址含有非法字符")

        val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase()
        if (scheme == null) return relativeToExtension(extId, url)

        return when (scheme) {
            "http", "https" -> {
                val host = hostOf(url) ?: throw bad("网址缺少主机名")
                if (host.endsWith("." + ExtensionOrigins.DOMAIN) && host != ExtensionOrigins.hostFor(extId)) {
                    throw bad("不能打开其它拓展的页面")
                }
                url
            }
            "about" -> if (url.equals("about:blank", ignoreCase = true)) "about:blank" else throw bad("不允许的网址：about")
            // 只回 scheme，不回整条网址
            else -> throw bad("不允许的网址：$scheme")
        }
    }

    private fun relativeToExtension(extId: String, path: String): String {
        // 以 // 开头是「同协议网络路径」，不是拓展内的路径
        if (path.startsWith("//")) throw bad("网址缺少协议")
        val pathOnly = path.substringBefore('?').substringBefore('#')
        if (pathOnly.split('/', '\\').any { it == ".." }) throw bad("路径不合法")
        return ExtensionOrigins.urlFor(extId, path)
    }

    /** 取小写主机名：去掉用户信息（`user@`）与端口。取不到或为空返回 null */
    internal fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringBefore(']', "") + "]"
        } else {
            hostPort.substringBefore(':')
        }
        return host.lowercase().takeIf { it.isNotEmpty() && it != "]" && !it.any { c -> c.isWhitespace() } }
    }

    private fun bad(msg: String) = ApiException(ErrorCodes.BAD_REQUEST, msg)
}
