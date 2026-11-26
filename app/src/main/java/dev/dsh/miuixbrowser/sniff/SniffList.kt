/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一个页面嗅探到的视频：去重、有上限，线程安全（WebView 在 IO 线程、Gecko 在主线程写，界面在主线程读）。
 * [items] 是 Compose 状态，界面直接读；[promptedCount] 记录已经处理过的个数（自动悬浮播放只对新出现的地址判断一次）；
 * 不会主动弹提示，列表只在用户点地址栏的媒体图标时才打开。
 */
class SniffList {
    var items by mutableStateOf<List<SniffedMedia>>(emptyList())
        private set

    var promptedCount by mutableStateOf(0)

    // 时长探测的进度（只在内存里）：进行中 / 已结束（成功或失败都算，不重复探）
    private val probing = HashSet<String>()
    private val probed = HashSet<String>()

    // 自动悬浮播放已经拉起过的地址：关掉播放器后别因为后来又出现的别的源把同一条再开一次
    private val autoPlayed = HashSet<String>()

    private fun keyOf(url: String) = url.substringBefore('#')

    /**
     * 新增返回 true；重复（同一地址，不含 #片段）返回 false，但会把新带来的信息并进已有那条
     * （页面后来才报出的时长 / 预览标记 / 大小）；超过上限返回 false。
     */
    @Synchronized
    fun offer(m: SniffedMedia): Boolean {
        val key = keyOf(m.url)
        var cur = items
        val idx = cur.indexOfFirst { keyOf(it.url) == key }
        if (idx >= 0) {
            val old = cur[idx]
            val merged = old.copy(
                size = if (m.size > 0) m.size else old.size,
                durationMs = if (m.durationMs != -1L) m.durationMs else old.durationMs,
                preview = m.preview ?: old.preview,
                userPlayed = old.userPlayed || m.userPlayed,
                referer = old.referer.ifEmpty { m.referer },
                origin = old.origin.ifEmpty { m.origin },
                label = old.label.ifEmpty { m.label },
            )
            if (merged != old) items = cur.toMutableList().also { it[idx] = merged }
            return false
        }
        // X：子清单只是主清单里的一档，主清单已经在列表里就不单独列；主清单后到时把已列的子清单收掉
        val xKey = if (XVideo.isMasterPlaylist(m.url) || XVideo.isVariantPlaylist(m.url)) XVideo.mediaKey(m.url) else null
        if (xKey != null) {
            if (XVideo.isVariantPlaylist(m.url) &&
                cur.any { XVideo.isMasterPlaylist(it.url) && XVideo.mediaKey(it.url) == xKey }
            ) return false
            if (XVideo.isMasterPlaylist(m.url)) {
                cur = cur.filterNot { XVideo.isVariantPlaylist(it.url) && XVideo.mediaKey(it.url) == xKey }
            }
        }
        if (cur.size >= MediaSniff.MAX_ITEMS) {
            // X 的时间线会自动播放一串视频把列表占满：新来的 X 视频挤掉最早的一条 X 视频，别的站点照旧不收
            if (!XVideo.isXMedia(m.url)) return false
            val victim = cur.indexOfFirst { XVideo.isXMedia(it.url) }
            if (victim < 0) return false
            cur = cur.toMutableList().also { it.removeAt(victim) }
        }
        items = cur + m
        return true
    }

    /** 去掉所有「解析来源」条目（[SniffedMedia.source] 以 [prefix] 开头）里来源不是 [keep] 的：SPA 换视频时清掉上一个视频的流 */
    @Synchronized
    fun dropSourcesExcept(prefix: String, keep: String) {
        val cur = items
        val next = cur.filterNot { it.source.startsWith(prefix) && it.source != keep }
        if (next.size != cur.size) items = next
    }

    /** 后台探测到的时长；页面已经报过的（>= 0 或直播）不覆盖 */
    @Synchronized
    fun setProbedDuration(url: String, ms: Long) {
        val key = keyOf(url)
        val cur = items
        val idx = cur.indexOfFirst { keyOf(it.url) == key }
        if (idx < 0 || cur[idx].durationMs != -1L) return
        items = cur.toMutableList().also { it[idx] = it[idx].copy(durationMs = ms) }
    }

    /** 开始探测这一条：已经探过 / 正在探返回 false */
    @Synchronized
    fun beginProbe(url: String): Boolean = keyOf(url).let { k -> k !in probed && probing.add(k) }

    @Synchronized
    fun endProbe(url: String) {
        val k = keyOf(url)
        probing.remove(k)
        probed.add(k)
    }

    /** 这一条还在等探测结果（进行中，或还没开始探） */
    @Synchronized
    fun probePending(url: String): Boolean = keyOf(url).let { it !in probed }

    @Synchronized
    fun markAutoPlayed(url: String) { autoPlayed.add(keyOf(url)) }

    /** 上一次拉起播放器时列表里已有的个数：之后新增的才是「播放器开着时页面又换了视频」的候选 */
    @Volatile
    var launchedSize = 0
        private set

    @Synchronized
    fun markLaunched() { launchedSize = items.size }

    @Synchronized
    fun autoPlayedUrls(): Set<String> = autoPlayed.toSet()

    @Synchronized
    fun clear() {
        items = emptyList()
        promptedCount = 0
        probing.clear()
        probed.clear()
        autoPlayed.clear()
        launchedSize = 0
    }
}
