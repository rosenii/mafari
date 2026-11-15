package dev.dsh.miuixbrowser.adblock

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单个标签当前页面的请求汇总（按请求域名聚合，WebView 内核）。
 *
 * - 只放内存：不落盘、不记完整 URL，只有域名和次数；换页（onPageStarted）、关标签即清空；无痕标签不记录
 * - 在 shouldInterceptRequest 的热路径上调用，所以只做一次 map 查找 + 两个原子自增；域名数设上限，超出的新域名丢弃
 * - 请求线程并发写、界面线程定时读 [snapshot]，不需要加锁
 */
class RequestLog {

    class Entry(val host: String) {
        val total = AtomicInteger(0)
        val blocked = AtomicInteger(0)

        /** 最近一次被拦截的原因：[AdBlock.R_RULE] / [AdBlock.R_TRACKER] / [AdBlock.R_USER]；从没被拦截过是 0 */
        @Volatile var reason = 0
    }

    data class Row(val host: String, val total: Int, val blocked: Int, val reason: Int)

    private val map = ConcurrentHashMap<String, Entry>()

    fun record(host: String, reason: Int) {
        var e = map[host]
        if (e == null) {
            if (map.size >= MAX_HOSTS) return
            e = map.computeIfAbsent(host) { Entry(it) }
        }
        e.total.incrementAndGet()
        if (reason != 0) {
            e.blocked.incrementAndGet()
            e.reason = reason
        }
    }

    /** 被拦截的排前面，其次按请求数降序 */
    fun snapshot(): List<Row> =
        map.values.map { Row(it.host, it.total.get(), it.blocked.get(), it.reason) }
            .sortedWith(compareByDescending<Row> { it.blocked > 0 }.thenByDescending { it.total }.thenBy { it.host })

    fun clear() = map.clear()

    private companion object {
        const val MAX_HOSTS = 300
    }
}
