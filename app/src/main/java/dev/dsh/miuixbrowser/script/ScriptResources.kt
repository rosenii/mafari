package dev.dsh.miuixbrowser.script

import dev.dsh.miuixbrowser.crypto.digest
import dev.dsh.miuixbrowser.net.HttpFetcher
import android.content.Context
import android.util.Base64
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 油猴脚本的 @require / @resource 磁盘缓存。
 * 网络请求只在后台线程做；注入时（主线程）只读缓存。
 */
object ScriptResources {

    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val RETRY_AFTER_MS = 60_000L
    private val failedAt = ConcurrentHashMap<String, Long>()

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "userscript_cache").apply { mkdirs() }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dataFile(context: Context, url: String) = File(dir(context), key(url) + ".bin")
    private fun mimeFile(context: Context, url: String) = File(dir(context), key(url) + ".mime")

    fun bytes(context: Context, url: String): ByteArray? =
        runCatching { dataFile(context, url).takeIf { it.exists() }?.readBytes() }.getOrNull()

    fun text(context: Context, url: String): String? =
        bytes(context, url)?.toString(Charsets.UTF_8)?.removePrefix("﻿")

    fun mime(context: Context, url: String): String =
        runCatching { mimeFile(context, url).takeIf { it.exists() }?.readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: guessMime(url)

    private fun guessMime(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".js") -> "text/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            path.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** 该脚本声明的、尚未缓存的 URL */
    fun missing(context: Context, code: String): List<String> {
        val meta = UserscriptEngine.parse(code)
        return (meta.requires + meta.resources.values).distinct()
            .filter { it.isNotBlank() && !dataFile(context, it).exists() }
    }

    /** 已缓存数量，用来判断「有新资源到了，需要重新生成注入脚本」 */
    fun cachedCount(context: Context, code: String): Int {
        val meta = UserscriptEngine.parse(code)
        return (meta.requires + meta.resources.values).distinct().count { dataFile(context, it).exists() }
    }

    /** 后台线程调用：补齐缺失资源。返回是否有新增。 */
    fun prefetch(context: Context, code: String): Boolean {
        var changed = false
        missing(context, code).forEach { url ->
            val last = failedAt[url]
            if (last != null && System.currentTimeMillis() - last < RETRY_AFTER_MS) return@forEach
            if (fetch(context, url)) {
                failedAt.remove(url)
                changed = true
            } else {
                failedAt[url] = System.currentTimeMillis()
            }
        }
        return changed
    }

    private fun fetch(context: Context, url: String): Boolean = runCatching {
        val (data, mime) = download(url) ?: return false
        dataFile(context, url).writeBytes(data)
        mimeFile(context, url).writeText(mime)
        true
    }.getOrDefault(false)

    private val FETCH_OPTIONS = HttpFetcher.Options(
        connectTimeoutMs = 10_000,
        readTimeoutMs = 15_000,
        maxBytes = MAX_BYTES,
        userAgent = "Mozilla/5.0 (Linux; Android 14) Mafari",
        accept = "*/*",
    )

    private fun download(startUrl: String): Pair<ByteArray, String>? {
        if (startUrl.startsWith("data:", ignoreCase = true)) return decodeDataUrl(startUrl)
        return runCatching {
            val r = HttpFetcher.get(startUrl, FETCH_OPTIONS)
            r.bytes to r.contentType.ifBlank { guessMime(r.url) }
        }.getOrNull()
    }

    private fun decodeDataUrl(url: String): Pair<ByteArray, String>? {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val mime = header.substringBefore(';').ifBlank { "text/plain" }
        val data = if (header.contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            URLDecoder.decode(payload, "UTF-8").toByteArray()
        }
        return data to mime
    }
}
