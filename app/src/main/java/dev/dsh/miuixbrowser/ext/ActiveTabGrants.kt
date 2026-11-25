/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import java.util.concurrent.ConcurrentHashMap

/** `scheme://host[:port]`：小写、去掉用户信息与默认端口，与页面里的 `location.origin` 同形。非 http(s) 返回 null */
internal object UrlOrigin {
    fun of(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
        var hostPort = authority.substringAfterLast('@').lowercase()
        if (hostPort.isEmpty()) return null
        hostPort = hostPort.removeSuffix(if (scheme == "http") ":80" else ":443")
        return "$scheme://$hostPort"
    }
}

/**
 * `activeTab`：用户主动点了某个拓展（快捷菜单里点它）时，临时授予它对「当前标签、当前 origin」的访问，
 * 可用于 scripting.executeScript / insertCSS / removeCSS，以及 tabs 里该标签的 url / title。
 *
 * 语义与 Chrome 一致：
 * - 授权只针对一个标签、一个 origin，只存内存；
 * - 标签跳到别的 origin 或被关闭就作废（同源内跳转保留）；
 * - 即使事件漏了，[isGranted] 也会拿「标签此刻的网址」再比一次 origin，所以不会授权到别的站点。
 *
 * 只在拓展声明并获授 `activeTab` 时才会产生授权，这一步由调用方（[ExtensionSystem.grantActiveTab]）检查。
 */
class ActiveTabGrants : TabEventListener {
    private data class Key(val extId: String, val tabId: Int)

    private val grants = ConcurrentHashMap<Key, String>() // → 授权时的 origin

    /** 对 [tab] 当前的 origin 授权；不是 http(s) 页面（主页、错误页、拓展页）返回 false */
    fun grant(extId: String, tab: TabInfo): Boolean {
        val origin = UrlOrigin.of(tab.url) ?: return false
        grants[Key(extId, tab.id)] = origin
        return true
    }

    /** [url] 必须是标签此刻的网址 */
    fun isGranted(extId: String, tabId: Int, url: String): Boolean {
        val granted = grants[Key(extId, tabId)] ?: return false
        return granted == UrlOrigin.of(url)
    }

    /** 拓展停用 / 卸载 / 更新时 */
    fun revokeExtension(extId: String) {
        grants.keys.removeAll { it.extId == extId }
    }

    fun count(): Int = grants.size

    override fun onUpdated(tab: TabInfo, change: TabChange) {
        if (change.url == null) return
        val now = UrlOrigin.of(tab.url)
        for ((key, origin) in grants) {
            if (key.tabId == tab.id && origin != now) grants.remove(key, origin)
        }
    }

    override fun onRemoved(tabId: Int) {
        grants.keys.removeAll { it.tabId == tabId }
    }
}
