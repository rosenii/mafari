/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.gecko

import dev.dsh.miuixbrowser.sniff.MediaSniff
import dev.dsh.miuixbrowser.sniff.SniffList
import dev.dsh.miuixbrowser.sniff.SniffedMedia
import dev.dsh.miuixbrowser.sniff.XVideo
import org.json.JSONObject

/**
 * Gecko 内核的视频嗅探数据。内置扩展（mafari-gm-ext 后台页）用 webRequest.onHeadersReceived 看响应头，
 * 把疑似视频的请求批量上报：`{t:"sniff", items:[{top, url, ct, len, d?, pv?, br?}]}`（d / pv / br 来自 X 页面里的脚本），这里按页面顶层 URL（去 #片段）存成 [SniffList]。
 *
 * 和 [GeckoNetLog] 一样按页面地址而不是标签对应（扩展的 tabId 与应用里的标签没有现成映射）；
 * 代价是两个标签打开同一个地址时共用一份。只放内存，最近 [MAX_PAGES] 个页面。
 */
internal object GeckoSniff {

    private const val MAX_PAGES = 24

    private val pages = object : LinkedHashMap<String, SniffList>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SniffList>?): Boolean = size > MAX_PAGES
    }

    private fun norm(url: String): String = url.substringBefore('#')

    @Synchronized
    fun listFor(url: String): SniffList = pages.getOrPut(norm(url)) { SniffList() }

    /** 页面开始加载：本页嗅探结果重新统计 */
    @Synchronized
    fun reset(url: String) {
        pages[norm(url)]?.clear()
    }

    fun ingest(o: JSONObject) {
        if (!MediaSniff.enabled) return
        val arr = o.optJSONArray("items") ?: return
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val top = e.optString("top")
            val url = e.optString("url")
            if (!top.startsWith("http", true)) continue
            val type = MediaSniff.classify(url, e.optString("ct").ifEmpty { null }, e.optLong("len", -1)) ?: continue
            // d / pv / br 只有 X 的页面脚本报告带（时长秒、是否预览、mp4 码率），请求监听的条目没有
            val preview = if (e.has("pv")) e.optBoolean("pv") else null
            listFor(top).offer(
                SniffedMedia(
                    url, type, MediaSniff.hostOf(url), e.optLong("len", -1),
                    MediaSniff.durationMsOf(e.optDouble("d", Double.NaN)), preview,
                    label = XVideo.label(url, e.optLong("br", 0)),
                ),
            )
        }
    }
}
