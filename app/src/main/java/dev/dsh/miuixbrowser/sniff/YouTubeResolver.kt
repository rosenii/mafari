/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import dev.dsh.miuixbrowser.util.Diag
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 YouTube 其他客户端的 innertube `player` 接口解析视频流（见 [YouTube]）。阻塞网络，必须在 IO 线程调用。
 * 按 [YouTube.CLIENTS] 的顺序逐个试，第一个拿到可播流的为准；全都不行返回 null（页面里照常播放，只是列表里没有这条）。
 */
object YouTubeResolver {

    /** 解析成功的一组条目 + 实际用到的客户端 UA（googlevideo 的流地址按它校验，播放时要带同一个 UA） */
    class Result(val items: List<SniffedMedia>, val title: String, val clientName: String)

    private val cache = object : LinkedHashMap<String, Pair<Long, Result>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, Result>>?) = size > 8
    }
    private const val TTL_MS = 10 * 60 * 1000L

    @Synchronized private fun cached(id: String): Result? =
        cache[id]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second

    @Synchronized private fun remember(id: String, r: Result) { cache[id] = System.currentTimeMillis() to r }

    fun resolve(videoId: String): Result? {
        cached(videoId)?.let { return it }
        for (client in YouTube.CLIENTS) {
            val parsed = runCatching { fetch(videoId, client) }
                .onFailure { Diag.log("youtube ${client.name}: ${it.message}") }
                .getOrNull() ?: continue
            if (parsed.streams.isEmpty()) {
                Diag.log("youtube ${client.name}: no streams (${parsed.reason})")
                continue
            }
            val items = parsed.streams.map { s ->
                SniffedMedia(
                    url = s.url, type = s.type, host = MediaSniff.hostOf(s.url),
                    size = s.size, durationMs = s.durationMs,
                    referer = YouTube.ORIGIN + "/", origin = YouTube.ORIGIN,
                    label = "YouTube · " + s.label,
                    ua = client.userAgent,
                    source = YouTube.SOURCE_PREFIX + videoId,
                    manualOnly = true,
                )
            }
            return Result(items, parsed.title, client.name).also { remember(videoId, it) }
        }
        return null
    }

    private fun fetch(videoId: String, client: YouTube.Client): YouTube.Parsed {
        val c = URL(YouTube.PLAYER_ENDPOINT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", client.userAgent)
            c.setRequestProperty("Origin", YouTube.ORIGIN)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("X-YouTube-Client-Name", client.clientId.toString())
            c.setRequestProperty("X-YouTube-Client-Version", client.version)
            c.outputStream.use { it.write(YouTube.playerBody(videoId, client).toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val text = c.inputStream.use { it.readNBytes(4 * 1024 * 1024).toString(Charsets.UTF_8) }
            return YouTube.parse(text)
        } finally {
            c.disconnect()
        }
    }
}
