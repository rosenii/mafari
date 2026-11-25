/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.gecko

import dev.dsh.miuixbrowser.adblock.RequestLog
import org.json.JSONObject

/**
 * Gecko 内核的「本页请求」数据。内置扩展（mafari-gm-ext 后台页）用 webRequest 观察请求，
 * 每 600ms 批量上报「页面顶层 URL → 域名 → 次数 / 拦截数」，这里按页面 URL（去掉 #片段）存成 [RequestLog]。
 *
 * 为什么按 URL 而不是按标签：扩展看到的是扩展自己的 tabId，和应用里的 BrowserTab 没有现成的对应关系；
 * 页面顶层 URL 两边都有。代价：两个标签打开同一个地址时共用一份统计。
 * 只放内存，最近 [MAX_PAGES] 个页面；页面开始加载时 [reset]；无痕窗口的请求扩展那边就不上报。
 */
internal object GeckoNetLog {

    private const val MAX_PAGES = 24

    private val pages = object : LinkedHashMap<String, RequestLog>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RequestLog>?): Boolean = size > MAX_PAGES
    }

    private fun norm(url: String): String = url.substringBefore('#')

    @Synchronized
    fun logFor(url: String): RequestLog = pages.getOrPut(norm(url)) { RequestLog() }

    /** 页面开始加载：本页请求重新统计 */
    @Synchronized
    fun reset(url: String) {
        pages[norm(url)]?.clear()
    }

    /** 后台页上报：{t:"net", pages:[{top, hosts:[{h, n, b, r}]}]} */
    fun ingest(o: JSONObject) {
        val arr = o.optJSONArray("pages") ?: return
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val top = p.optString("top")
            if (top.isEmpty()) continue
            val log = logFor(top)
            val hosts = p.optJSONArray("hosts") ?: continue
            for (j in 0 until hosts.length()) {
                val h = hosts.optJSONObject(j) ?: continue
                val host = h.optString("h")
                if (host.isEmpty()) continue
                log.recordN(host, h.optInt("n"), h.optInt("b"), h.optInt("r"))
            }
        }
    }
}
