/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.util.cancelCompat
import dev.dsh.miuixbrowser.crypto.digest
import dev.dsh.miuixbrowser.engine.gecko.await
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 所有图标仓库共用的应用上下文（磁盘缓存放在 filesDir 下各自的子目录）。在 Application.onCreate 里初始化，幂等。 */
object IconStores {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    internal fun dir(subdir: String): File? =
        appContext?.let { File(it.filesDir, subdir).also { d -> d.mkdirs() } }
}

/**
 * 图标仓库的公共实现：内存 → 磁盘 → 网络三级缓存 + 同键请求合并 + 并发上限 + 失败退避。
 * [FaviconLoader]、[RemoteIconLoader]、[SearchIconStore] 只负责「怎么从网络取一张图」，其余都在这里。
 *
 *  - 合并：同一个键正在加载时，后来的调用共享同一个任务；调用方被取消（滚出屏幕）不会打断下载，图标仍会进缓存。
 *  - 磁盘：放 filesDir 而不是 cacheDir（冷启动清缓存会清掉 cacheDir）。[ttlMs] 为 null 表示永不过期；过期后联网刷新，
 *    刷新失败仍用旧图。文件超过 [maxFiles] 时在后台删最旧的。
 *  - 失败：按次数退避 1 分钟 → 5 分钟 → 30 分钟 → 2 小时封顶，成功后清零。
 *  - [fileName]：键 → 磁盘文件名。默认是 SHA-256 前缀（文件名不泄露域名）；旧存储要兼容时可自定义。
 */
internal class IconStore(
    private val subdir: String,
    private val ttlMs: Long?,
    private val maxFiles: Int,
    concurrency: Int,
    private val normalizeKey: (String) -> String = { it },
    private val fileName: (String) -> String = { key -> hashName(key) },
) {
    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    private class Failure(val count: Int, val at: Long)
    private val failures = ConcurrentHashMap<String, Failure>()

    private val inflight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(concurrency)

    /** 只查内存，不做任何 IO：首帧就有图标可画，不闪默认图标。 */
    fun peek(rawKey: String): ImageBitmap? = memory[normalizeKey(rawKey)]

    /** 在后台修剪磁盘上过多的旧文件。 */
    fun trimAsync() {
        scope.launch {
            runCatching {
                val files = IconStores.dir(subdir)?.listFiles() ?: return@runCatching
                if (files.size > maxFiles) {
                    files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
                }
            }
        }
    }

    fun clear() {
        memory.clear()
        failures.clear()
        runCatching { IconStores.dir(subdir)?.listFiles()?.forEach { it.delete() } }
    }

    /** [fetch] 是阻塞的取图函数（在后台线程、并发上限内执行）；返回 null 表示这次失败。 */
    suspend fun get(rawKey: String, fetch: () -> Bitmap?): ImageBitmap? {
        val key = normalizeKey(rawKey)
        if (key.isEmpty()) return null
        memory[key]?.let { return it }
        failures[key]?.let { f ->
            if (System.currentTimeMillis() - f.at < BACKOFF_MS[minOf(f.count - 1, BACKOFF_MS.size - 1)]) return null
        }
        val fresh = scope.async(start = CoroutineStart.LAZY) { produce(key, fetch) }
        val existing = inflight.putIfAbsent(key, fresh)
        val task = if (existing != null) {
            fresh.cancelCompat()
            existing
        } else {
            fresh.invokeOnCompletion { inflight.remove(key, fresh) }
            fresh.also { it.start() }
        }
        return task.await()
    }

    private fun fileOf(key: String): File? = IconStores.dir(subdir)?.let { File(it, fileName(key)) }

    private fun readDisk(file: File?, allowStale: Boolean): Bitmap? {
        if (file == null || !file.exists()) return null
        if (!allowStale && ttlMs != null && System.currentTimeMillis() - file.lastModified() > ttlMs) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    private suspend fun produce(key: String, fetch: () -> Bitmap?): ImageBitmap? {
        val file = fileOf(key)
        readDisk(file, allowStale = false)?.let { return keep(key, it) }
        val bitmap = gate.withPermit { runCatching(fetch).getOrNull() }
        if (bitmap != null) {
            runCatching { file?.outputStream()?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            return keep(key, bitmap)
        }
        // 联网失败：磁盘上有过期的旧图就继续用它，不要退回默认图标
        readDisk(file, allowStale = true)?.let { return keep(key, it, clearFailure = false) }
        val prev = failures[key]?.count ?: 0
        failures[key] = Failure(prev + 1, System.currentTimeMillis())
        return null
    }

    private fun keep(key: String, bitmap: Bitmap, clearFailure: Boolean = true): ImageBitmap {
        val image = bitmap.asImageBitmap()
        memory[key] = image
        if (clearFailure) failures.remove(key)
        return image
    }

    companion object {
        private val BACKOFF_MS = longArrayOf(60_000L, 5 * 60_000L, 30 * 60_000L, 2 * 60 * 60_000L)

        fun hashName(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12)
                .joinToString("") { "%02x".format(it) } + ".png"
    }
}
