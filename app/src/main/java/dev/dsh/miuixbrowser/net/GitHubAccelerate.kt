/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.net

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * GitHub 公共加速线路：直连 + 多个公益镜像前缀，双线（多线）并发探测，先通者优先。
 * 镜像规则：`前缀 + 完整 https URL`（与 gh-proxy / ghfast 等一致）。
 */
object GitHubAccelerate {

    /** 加速前缀（空 = 直连）。按常见公益站列出，探测时并行。 */
    val MirrorPrefixes = listOf(
        "", // 直连 GitHub / api.github.com
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://github.moeyy.xyz/",
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/",
    )

    private val MIRRORS get() = MirrorPrefixes

    /** 把绝对 URL 映射到某条线；prefix 为空则原样返回 */
    fun map(url: String, prefix: String): String =
        if (prefix.isEmpty()) url else prefix + url

    /**
     * 多线并发请求，最先返回非 null 的结果胜出（连通者优先）。
     * [timeoutMs] 为整体等待上限。
     */
    fun <T> raceFirst(timeoutMs: Long, fetchers: List<() -> T?>): T? {
        if (fetchers.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(fetchers.size.coerceAtMost(8))
        val completion = ExecutorCompletionService<T?>(pool)
        try {
            fetchers.forEach { f ->
                completion.submit(java.util.concurrent.Callable {
                    runCatching { f() }.getOrNull()
                })
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (leftMs <= 0) break
                val future = completion.poll(leftMs, TimeUnit.MILLISECONDS) ?: break
                val value = runCatching { future.get() }.getOrNull()
                if (value != null) return value
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /** 对候选 URL 做连通探测（GET + Range，兼容不支持 HEAD 的镜像） */
    fun probe(url: String, timeoutMs: Int = 4000): Boolean = runCatching {
        HttpFetcher.requireHttps(url) // 镜像与直连一律 https
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", "mafari")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = connection.responseCode
            code in 200..299 || code == 206
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /**
     * 并发探测直连与全部加速前缀，返回第一条可连通的映射 URL；全失败返回 null。
     * [rawUrl] 为完整 https 地址（api 或 release 资源）。
     */
    fun resolveReachable(rawUrl: String, timeoutMs: Long = 6000): String? {
        val candidates = MIRRORS.map { prefix -> map(rawUrl, prefix) }
        val fetchers: List<() -> String?> = candidates.map { mapped ->
            { if (probe(mapped)) mapped else null }
        }
        return raceFirst(timeoutMs, fetchers)
    }

    /** 映射后的下载 URL：探测胜出者优先，全失败退回直连 */
    fun pickDownloadUrl(rawUrl: String): String =
        resolveReachable(rawUrl) ?: rawUrl
}
