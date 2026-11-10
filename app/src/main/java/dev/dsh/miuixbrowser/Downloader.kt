package dev.dsh.miuixbrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class DownloadState { RUNNING, PAUSED, DONE, FAILED }

data class DownloadTask(
    val id: Long = System.nanoTime(),
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val mime: String? = null,
    val uri: Uri? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val state: DownloadState = DownloadState.RUNNING,
    val error: String? = null,
)

/** 内置下载器：断点续传、暂停/继续、失败重试，默认写入 Download/mafari */
object Downloader {

    val tasks = mutableStateListOf<DownloadTask>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val numberFormat = DecimalFormat("0.0")

    fun fileNameFrom(url: String, disposition: String?): String {
        val raw = disposition?.let { d ->
            Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)").find(d)?.groupValues?.get(1)?.trim()
        } ?: runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        val name = runCatching { URLDecoder.decode(raw ?: "", "UTF-8") }
            .getOrDefault(raw ?: "")
            .ifBlank { "download_${System.currentTimeMillis()}" }
        return truncateName(name.replace(Regex("[\\\\/:*?\"<>|]"), "_"), 80)
    }

    /**
     * 页面给定的原始文件名（<a download> / File.name）：原样保留，
     * 只去掉路径分隔符和控制字符（系统不允许），不补扩展名、不替换其它字符。
     */
    fun originalName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/\\u0000-\\u001F]"), "_").trim()
        return truncateName(cleaned.ifBlank { "download_${System.currentTimeMillis()}" }, 255)
    }

    /** 超长文件名截断时保留扩展名 */
    private fun truncateName(name: String, max: Int): String {
        if (name.length <= max) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
        return name.substring(0, (max - ext.length).coerceAtLeast(1)) + ext
    }

    /** 文件名没有扩展名时，按 MIME 补一个（blob 地址的文件名常常只是一串 UUID） */
    fun withExtension(name: String, mime: String?): String {
        if (Regex("\\.[A-Za-z0-9]{1,8}$").containsMatchIn(name)) return name
        val type = mime?.substringBefore(';')?.trim()?.lowercase()
        if (type.isNullOrBlank() || type == "application/octet-stream") return name
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        return if (ext.isNullOrBlank()) name else truncateName("$name.$ext", 80)
    }

    /** 终态回调（GM_download 的 onload / onerror）：完成、最终失败、被删除各触发一次；暂停不算终态 */
    private val completions = ConcurrentHashMap<Long, (Boolean, String?) -> Unit>()

    private fun settle(id: Long, ok: Boolean, error: String? = null) {
        val cb = completions.remove(id) ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { cb(ok, error) } }
    }

    fun start(
        context: Context,
        url: String,
        userAgent: String?,
        fileName: String,
        mimeType: String?,
        onComplete: ((ok: Boolean, error: String?) -> Unit)? = null,
    ) {
        val task = DownloadTask(name = fileName, url = url, userAgent = userAgent, mime = mimeType)
        if (onComplete != null) completions[task.id] = onComplete
        tasks.add(0, task)
        DownloadService.start(context)
        DownloadService.instance?.showStart(fileName, task.id)
        launch(context, task.id)
    }

    fun pause(id: Long) {
        jobs.remove(id)?.cancel()
        update(id) { it.copy(state = DownloadState.PAUSED) }
        val task = tasks.firstOrNull { it.id == id }
        if (task != null) {
            val progress = progressOf(task)
            DownloadService.instance?.updatePaused(task.name, progress, id)
        }
    }

    fun resume(context: Context, id: Long) {
        update(id) { it.copy(state = DownloadState.RUNNING, error = null) }
        launch(context, id)
    }

    fun retry(context: Context, id: Long) {
        update(id) { it.copy(state = DownloadState.RUNNING, error = null) }
        launch(context, id)
    }

    fun delete(context: Context, id: Long) {
        jobs.remove(id)?.cancel()
        val task = tasks.firstOrNull { it.id == id }
        task?.uri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        runCatching { tempFile(context, id).delete() }
        runCatching { segFile(context, id).delete() }
        tasks.removeAll { it.id == id }
        settle(id, false, "cancelled")
    }

    private fun tempFile(context: Context, id: Long) =
        java.io.File(context.cacheDir, "mafari_dl_$id.part")

    /** 多线程分段下载专用临时文件：会预先撑满到总长度，不能和断点续传共用 .part，否则重试时会误当成“已下完”而发出越界 Range → HTTP 416 */
    private fun segFile(context: Context, id: Long) =
        java.io.File(context.cacheDir, "mafari_dl_$id.seg")

    /** 下载在 IO 线程里完成，Toast 必须切回主线程 */
    private fun toast(context: Context, message: String) {
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(context: Context, id: Long) {
        jobs.remove(id)?.cancel()
        jobs[id] = scope.launch {
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                try {
                    downloadOnce(context, id)
                    val finished = tasks.firstOrNull { it.id == id }
                    update(id) { it.copy(state = DownloadState.DONE) }
                    settle(id, true)
                    DownloadService.instance?.finish(finished?.name ?: "文件")
                    toast(context, "下载完成：${finished?.name ?: "文件"}")
                    return@launch
                } catch (e: CancellationException) {
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    val message = e.message ?: e.javaClass.simpleName
                    if (attempt >= MAX_ATTEMPTS) {
                        update(id) { it.copy(state = DownloadState.FAILED, error = message) }
                        settle(id, false, message)
                        DownloadService.instance?.fail("下载失败：$message")
                        return@launch
                    }
                    update(id) { it.copy(error = message) }
                    delay(1200L * attempt)
                }
            }
        }
    }

    private suspend fun downloadOnce(context: Context, id: Long) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        val file = tempFile(context, id)
        var existing = if (file.exists()) file.length() else 0L

        // 旧版本遗留的 / 已经完整的临时文件：长度已达总长度，再发 Range 必然 416，直接重来
        if (existing > 0 && task.total > 0 && existing >= task.total) {
            runCatching { file.delete() }
            existing = 0
        }

        fun open(from: Long): HttpURLConnection = (URL(task.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", task.userAgent ?: "Mozilla/5.0 mafari")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }

        var connection = open(existing)
        try {
            var code = connection.responseCode
            if (code == 416 && existing > 0) {
                // 服务器认为续传位置越界（文件已变 / 临时文件已满）：丢弃临时文件，从头下载
                connection.disconnect()
                runCatching { file.delete() }
                existing = 0
                connection = open(0)
                code = connection.responseCode
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val length = connection.contentLengthLong.coerceAtLeast(0)
            val append = existing > 0 && code == 206
            if (!append) {
                existing = 0
                runCatching { file.delete() }
            }
            update(id) {
                it.copy(
                    bytes = existing,
                    total = if (append) existing + length else length,
                    state = DownloadState.RUNNING,
                )
            }
            // 多线程分段下载（服务器支持 Range 且文件 > 1MB 时）
            if (multiThreadEnabled && existing == 0L && append.not() && length > segmentMinBytes) {
                // 先放掉这条整文件连接，再探测 Range，避免对镜像同时开 N+2 条连接被限流
                connection.disconnect()
                if (supportsRange(task)) {
                    val seg = segFile(context, id)
                    runCatching { seg.delete() }
                    try {
                        downloadSegmented(context, id, task, seg, length)
                    } catch (t: Throwable) {
                        runCatching { seg.delete() }
                        throw t
                    }
                    return finishToMediaStore(context, id, task, seg)
                }
                // 不支持 Range：重新建立单连接顺序下载
                connection = open(0)
                val retryCode = connection.responseCode
                if (retryCode !in 200..299) throw IllegalStateException("HTTP $retryCode")
            }

            connection.inputStream.use { input ->
                java.io.FileOutputStream(file, append).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var written = existing
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250) {
                            lastUpdate = now
                            update(id) { it.copy(bytes = written) }
                            val total = tasks.firstOrNull { it.id == id }?.total ?: 0L
                            val progress = if (total > 0) written.toFloat() / total else 0f
                            DownloadService.instance?.update(
                                task.name,
                                progress,
                                "${(progress * 100).toInt()}% · ${sizeText(written)}",
                                id,
                            )
                        }
                    }
                    out.flush()
                    out.fd.sync()
                    update(id) { it.copy(bytes = written) }
                }
            }
            // 下载完成后一次性入库到 /sdcard/Download/mafari
            val uri = createEntry(context, task) ?: throw IllegalStateException("无法创建文件")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            update(id) { it.copy(uri = uri) }
            runCatching { file.delete() }
        } finally {
            connection.disconnect()
        }
    }

    /** HEAD 一下看是否支持 Range，顺便拿长度 */
    private fun supportsRange(task: DownloadTask): Boolean = runCatching {
        val head = (URL(task.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", task.userAgent ?: "Mozilla/5.0 mafari")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val code = head.responseCode
        head.disconnect()
        code == 206
    }.getOrDefault(false)

    private suspend fun downloadSegmented(
        context: Context,
        id: Long,
        task: DownloadTask,
        file: java.io.File,
        total: Long,
    ) {
        val threads = segments.coerceAtMost(((total / (256 * 1024)) + 1).toInt()).coerceAtLeast(1)
        val chunk = total / threads
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(total)
            val done = AtomicLong(0)
            var lastUpdate = 0L
            coroutineScope {
                repeat(threads) { index ->
                    launch(Dispatchers.IO) {
                        val start = index * chunk
                        val end = if (index == threads - 1) total - 1 else start + chunk - 1
                        val connection = (URL(task.url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15000
                            readTimeout = 30000
                            instanceFollowRedirects = true
                            setRequestProperty("Range", "bytes=$start-$end")
                            setRequestProperty("User-Agent", task.userAgent ?: "Mozilla/5.0 mafari")
                            setRequestProperty("Accept-Encoding", "identity")
                        }
                        try {
                            val code = connection.responseCode
                            // 必须是 206：返回 200 说明服务器忽略了 Range，整文件会被写到错误的偏移上
                            if (code != 206) throw IllegalStateException("HTTP $code")
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(128 * 1024)
                                var position = start
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    synchronized(raf) {
                                        raf.seek(position)
                                        raf.write(buffer, 0, read)
                                    }
                                    position += read
                                    val written = done.addAndGet(read.toLong())
                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate > 300) {
                                        lastUpdate = now
                                        update(id) {
                                            it.copy(
                                                bytes = written,
                                                total = total,
                                                state = DownloadState.RUNNING,
                                            )
                                        }
                                        DownloadService.instance?.update(
                                            task.name,
                                            (written.toFloat() / total).coerceIn(0f, 1f),
                                            "${(written * 100 / total)}% · ${sizeText(written)}",
                                            id,
                                        )
                                    }
                                }
                                if (position != end + 1) {
                                    throw IllegalStateException("分段 ${index + 1} 数据不完整")
                                }
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            }
            update(id) { it.copy(bytes = total, total = total) }
        }
    }

    private fun finishToMediaStore(context: Context, id: Long, task: DownloadTask, file: java.io.File) {
        val uri = createEntry(context, task) ?: throw IllegalStateException("无法创建文件")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        // 本地已有同名文件时系统会自动改名（如 xxx (1).zip），读回实际文件名
        update(id) { it.copy(uri = uri, name = actualName(context, uri, it.name)) }
        runCatching { file.delete() }
    }

    private fun createEntry(context: Context, task: DownloadTask): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, task.name)
            put(MediaStore.Downloads.MIME_TYPE, task.mime ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/mafari")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    /** 读取 MediaStore 实际落盘的显示名；同名文件已存在时系统会自动追加 (1) 等后缀 */
    private fun actualName(context: Context, uri: Uri, fallback: String): String = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull() ?: fallback

    private fun update(id: Long, transform: (DownloadTask) -> DownloadTask) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = transform(tasks[index])
    }

    /** 保存 data:...;base64 的内嵌 / blob 抽取结果到系统下载目录 */
    fun saveDataUri(context: Context, dataUri: String, fileName: String): Boolean = runCatching {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return@runCatching false
        val meta = dataUri.substringAfter("data:").substringBefore(',')
        if (!meta.contains("base64", ignoreCase = true)) return@runCatching false
        val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
        saveBytes(context, bytes, fileName, meta.substringBefore(';').ifBlank { null })
        true
    }.getOrDefault(false)

    /** 把缓存文件流式复制到 Download/mafari（大 blob 用，不把整个文件读进内存） */
    fun saveFile(context: Context, file: java.io.File, fileName: String, mime: String?): Boolean {
        var uri: Uri? = null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/mafari")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            uri = target
            val out = context.contentResolver.openOutputStream(target) ?: error("no output stream")
            out.use { o -> file.inputStream().use { it.copyTo(o, 64 * 1024) } }
            context.contentResolver.update(
                target,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            val size = file.length()
            tasks.add(
                0,
                DownloadTask(
                    name = actualName(context, target, fileName),
                    url = "",
                    mime = mime,
                    uri = target,
                    bytes = size,
                    total = size,
                    state = DownloadState.DONE,
                ),
            )
            true
        }.getOrElse {
            // 写到一半失败：删掉未完成的条目，避免留下残缺文件
            uri?.let { u -> runCatching { context.contentResolver.delete(u, null, null) } }
            false
        }
    }

    /** 直接把字节写入 Download/mafari（blob / data URL 共用） */
    fun saveBytes(context: Context, bytes: ByteArray, fileName: String, mime: String?): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/mafari")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        tasks.add(
            0,
            DownloadTask(
                name = actualName(context, uri, fileName),
                url = "",
                mime = mime,
                uri = uri,
                bytes = bytes.size.toLong(),
                total = bytes.size.toLong(),
                state = DownloadState.DONE,
            ),
        )
        true
    }.getOrDefault(false)

    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${numberFormat.format(bytes / 1024f)} KB"
        else -> "${numberFormat.format(bytes / 1024f / 1024f)} MB"
    }

    fun progressOf(task: DownloadTask): Float {
        if (task.state == DownloadState.DONE) return 1f
        if (task.total <= 0) return 0f
        return (task.bytes.toFloat() / task.total).coerceIn(0f, 1f)
    }

    private const val MAX_ATTEMPTS = 3
    /** 分段下载线程数（设置里可选 4/8/16/32/64） */
    @Volatile
    var segments = 4

    /** 多线程下载总开关（设置里可关闭，关闭后一律单线程） */
    @Volatile
    var multiThreadEnabled = true

    /** 文件超过该字节数才使用多线程分段下载 */
    @Volatile
    var segmentMinBytes = 200L * 1024 * 1024
}
