/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.webview

import androidx.compose.runtime.mutableStateListOf
import dev.dsh.miuixbrowser.util.Diag

/**
 * 页面性能记录（WebView 内核，设置 → 高级 → WebView 调试 → 页面性能）。
 *
 * 数据来自 androidx.webkit NavigationListener 的 onFirstContentfulPaintMillis / onLargestContentfulPaintMillis
 * （见 `WebViewNavigationBridge`）：主文档每提交一次就 [begin] 一条记录，之后到达的 FCP / LCP 写进同一标签最新的那条。
 *
 * 调试页里有「记录页面性能」开关（[WebViewDebug.perfRecord]）：关着时 [begin] / FCP / LCP 都直接丢弃。
 * 只放内存、只留最近 [MAX] 条，不落盘；无痕标签不挂 NavigationListener，因此不会出现在这里。
 * 所有方法都在主线程调用（NavigationListener 的回调执行器就是主线程）。
 */
object WebViewPerf {

    const val MAX = 30

    /** 一次主文档加载。[fcpMs] / [lcpMs] 为 null 表示还没收到（或页面没有可绘制内容）。 */
    data class Entry(
        val tabId: Int,
        val url: String,
        val startedAt: Long,
        val fcpMs: Long? = null,
        val lcpMs: Long? = null,
    )

    enum class Rating { GOOD, NEEDS_IMPROVEMENT, POOR }

    enum class Metric { FCP, LCP }

    /** 最新的在最前。Compose 直接读。 */
    val entries = mutableStateListOf<Entry>()

    /** 主文档提交：新开一条记录。 */
    fun begin(tabId: Int, url: String, now: Long = System.currentTimeMillis()) {
        if (!WebViewDebug.perfRecord) return
        entries.add(0, Entry(tabId, url, now))
        while (entries.size > MAX) entries.removeAt(entries.lastIndex)
    }

    fun onFcp(tabId: Int, ms: Long) = update(tabId, Metric.FCP, ms)

    fun onLcp(tabId: Int, ms: Long) = update(tabId, Metric.LCP, ms)

    private fun update(tabId: Int, metric: Metric, ms: Long) {
        if (ms < 0 || !WebViewDebug.perfRecord) return
        val i = entries.indexOfFirst { it.tabId == tabId }
        if (i < 0) return // 还没有提交记录（事件早于提交）：丢弃，别把数据挂到别的页面上
        val old = entries[i]
        entries[i] = when (metric) {
            Metric.FCP -> old.copy(fcpMs = ms)
            Metric.LCP -> old.copy(lcpMs = ms) // LCP 会随更大的元素出现而多次更新，取最新一次
        }
        if (WebViewDebug.pageLog && Diag.enabled) {
            Diag.log("perf ${metric.name.lowercase()} ${ms}ms ${old.url.take(200)}")
        }
    }

    fun clear() = entries.clear()

    /** 某个标签最近一次记录（菜单 / 调试页要显示「当前页」时用）。 */
    fun latestFor(tabId: Int): Entry? = entries.firstOrNull { it.tabId == tabId }

    /** 阈值取自 web.dev：FCP 1.8s / 3.0s，LCP 2.5s / 4.0s。 */
    fun rate(metric: Metric, ms: Long): Rating {
        val (good, poor) = when (metric) {
            Metric.FCP -> 1800L to 3000L
            Metric.LCP -> 2500L to 4000L
        }
        return when {
            ms <= good -> Rating.GOOD
            ms <= poor -> Rating.NEEDS_IMPROVEMENT
            else -> Rating.POOR
        }
    }

    /** 毫秒 → 「820 ms」/「2.35 s」。 */
    fun format(ms: Long): String = if (ms < 1000) "$ms ms" else String.format(java.util.Locale.US, "%.2f s", ms / 1000.0)
}
