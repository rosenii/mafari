/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

import java.net.URI

/**
 * GM_xmlhttpRequest / GM_cookie 的目标主机策略（`@connect`），与 Gecko 侧 background.js 的 connectAllows / cookieAllows 同一口径：
 *  - 同站（目标主机 = 页面主机或其子域）始终放行；
 *  - `@connect` 列出的主机（含子域）放行；
 *  - 本机 / 局域网 / 链路本地 / 单标签内网名必须显式 `@connect`（`*` 不算）；
 *  - 没声明任何 `@connect`：放行公网（兼容老脚本）；声明了就按声明，`*` 表示任意公网主机；
 *  - GM_cookie 更严：只给同站或 `@connect` 明确列出的主机（`*` 不算，没声明也不算）。
 *
 * 每一跳重定向都要重新过 [xhrAllows]。纯 JVM，不依赖 Android，可直接单测。
 * 解析到内网的公网域名（DNS rebinding）这里看不出来，属已知限制（Gecko 侧相同）。
 */
internal class GmConnectPolicy(rawConnect: List<String>) {

    private val connect: List<String> = normalize(rawConnect)

    /** 脚本是否写了 @connect（只用于诊断 / 提示） */
    val declared: Boolean get() = connect.isNotEmpty()

    /** [host] 目标主机；[pageHost] 发起请求的页面主机（空串 = 未知，此时没有「同站」） */
    fun xhrAllows(host: String, pageHost: String): Boolean {
        val h = host.lowercase()
        if (sameSite(h, pageHost) || listed(h)) return true
        if (isPrivateHost(h)) return false // 内网 / 本机必须显式 @connect
        return connect.isEmpty() || connect.contains("*")
    }

    fun cookieAllows(host: String, pageHost: String): Boolean {
        val h = host.lowercase()
        return sameSite(h, pageHost) || listed(h)
    }

    private fun listed(host: String) =
        connect.any { c -> c != "*" && c != "self" && (host == c || host.endsWith(".$c")) }

    private fun sameSite(host: String, page: String): Boolean {
        val p = page.lowercase()
        return p.isNotEmpty() && (host == p || host.endsWith(".$p"))
    }

    companion object {
        /** 只留主机：去 scheme / 路径 / 端口 / 前导 `*.`，小写；`*` 与 `self` 原样保留 */
        fun normalize(list: List<String>): List<String> {
            val out = ArrayList<String>(list.size)
            for (raw in list) {
                var c = raw.trim().lowercase()
                if (c.isEmpty()) continue
                if (c != "*" && c != "self") {
                    c = c.replace(Regex("^[a-z][a-z0-9+.\\-]*://"), "")
                        .replace(Regex("[/?#].*$"), "")
                        .removePrefix("*.")
                        .replace(Regex(":\\d+$"), "")
                }
                if (c.isNotEmpty()) out.add(c)
            }
            return out
        }

        /** `https://a.com:8443` / `https://a.com/x` → `a.com`；解析不出（含 `null` origin）返回空串 */
        fun hostOf(urlOrOrigin: String?): String {
            if (urlOrOrigin.isNullOrBlank()) return ""
            return try { URI(urlOrOrigin.trim()).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
        }

        /** 本机 / 局域网 / 链路本地 / 单标签内网名；空主机按内网处理（宁可拒绝） */
        fun isPrivateHost(raw: String): Boolean {
            val h = raw.removePrefix("[").removeSuffix("]").lowercase().removeSuffix(".")
            if (h.isEmpty()) return true
            if (h == "localhost" || Regex("\\.(localhost|local|internal|lan|home\\.arpa)$").containsMatchIn(h)) return true
            val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(h)
            if (v4 != null) {
                val a = v4.groupValues[1].toInt()
                val b = v4.groupValues[2].toInt()
                return a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) || (a == 100 && b in 64..127)
            }
            if (h.contains(':')) {
                if (h == "::" || h == "::1") return true
                if (Regex("^f[cd]").containsMatchIn(h) || Regex("^fe[89ab]").containsMatchIn(h)) return true
                Regex("^::ffff:(\\d+\\.\\d+\\.\\d+\\.\\d+)$").find(h)?.let { return isPrivateHost(it.groupValues[1]) }
                Regex("^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$").find(h)?.let {
                    val x = it.groupValues[1].toInt(16)
                    val y = it.groupValues[2].toInt(16)
                    return isPrivateHost("${x shr 8}.${x and 255}.${y shr 8}.${y and 255}")
                }
                return false
            }
            return !h.contains('.') // 单标签名（router、nas…）只会在内网解析
        }
    }
}
