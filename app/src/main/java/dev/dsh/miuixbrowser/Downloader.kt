package dev.dsh.miuixbrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

enum class DownloadState { RUNNING, PAUSED, DONE, FAILED }

data class DownloadTask(
    val id: Long = Downloader.newId(),
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val mime: String? = null,
    val uri: Uri? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val state: DownloadState = DownloadState.RUNNING,
    val error: String? = null,
    /** 来源页地址，作为 Referer 发给下载站点（防盗链） */
    val referer: String? = null,
    /** 请求时从 WebView 的 CookieManager 取登录态（Cookie 不落盘，每次请求现取） */
    val withCookies: Boolean = false,
    /** Cookie 来自 Gecko（经内置扩展读取）而不是 WebView 的 CookieManager */
    val geckoCookies: Boolean = false,
    /** 远端文件指纹：强 ETag / Last-Modified / 总长度。续传时用来判断文件是否已变化 */
    val etag: String? = null,
    val lastModified: String? = null,
    /** >0 表示这是多线程分段任务，值为分段数（进度见 downloads 表的 seg_done） */
    val segCount: Int = 0,
    /** 数据已全部下载完，只差写入系统下载目录 */
    val fetched: Boolean = false,
)

/**
 * 内置下载器：断点续传、暂停/继续、失败重试，默认写入 Download/mafari。
 *
 * 持久化：任务元数据 + 分段进度存在 SQLCipher 加密库 history.db 的 downloads 表（见 [DownloadDb]），
 * 数据文件（data.part 单连接 / data.seg 分段）放在 filesDir/downloads/<id>/。目录不在 cacheDir，
 * 冷启动清缓存碰不到；暂停 / 失败只保留进度，只有手动删除任务才清记录和目录。远端指纹变化时整单重下。
 * 旧版每个任务目录里的明文 task.json 会在首次启动时导入数据库，导入后删除。
 */
object Downloader {

    val tasks = mutableStateListOf<DownloadTask>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val numberFormat = DecimalFormat("0.0")

    private lateinit var app: Context
    @Volatile
    private var inited = false
    private val persistLock = Any()

    /** 分段任务最近一次落盘的各分段已完成字节数（续传的依据） */
    private val segSaved = ConcurrentHashMap<Long, LongArray>()

    private val idSeq = AtomicLong(System.currentTimeMillis() * 1000)

    /** 任务 id 必须跨进程重启保持唯一（旧的 nanoTime 重启后会重复） */
    fun newId(): Long = idSeq.incrementAndGet()

    /** 远端文件变了 / 本地进度对不上：丢弃已下数据整单重下（不计入失败重试次数） */
    private class RestartDownload(message: String) : Exception(message)

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


    // ───────────────────────── 持久化 ─────────────────────────

    private fun rootDir(context: Context) = File(context.filesDir, "downloads")
    private fun taskDir(context: Context, id: Long) = File(rootDir(context), id.toString())

    /** 冷启动 / 通知按钮 / 任何入口第一次用到下载器时调用，幂等 */
    @Synchronized
    fun init(context: Context) {
        if (inited) return
        app = context.applicationContext
        inited = true
        val db = HistoryDb.get(app)
        val loaded = ArrayList<DownloadTask>()
        val known = HashSet<Long>()
        fun adopt(parsed: Pair<DownloadTask, LongArray?>) {
            var task = parsed.first
            // 上次进程被杀时还在跑的任务：恢复成暂停，由用户点继续（避免一启动就拉起一堆连接）
            if (task.state == DownloadState.RUNNING) task = task.copy(state = DownloadState.PAUSED)
            parsed.second?.let { segSaved[task.id] = it }
            known += task.id
            loaded += task
        }
        if (db != null) {
            runCatching { DownloadDb.loadAll(db) }
                .onFailure { Diag.log("downloads: load failed: ${it.message}") }
                .getOrNull()
                ?.forEach { adopt(it) }
        }
        rootDir(app).listFiles()?.forEach { dir ->
            val id = dir.name.toLongOrNull() ?: return@forEach
            val json = File(dir, "task.json")
            if (id in known) {
                runCatching { json.delete() }
                return@forEach
            }
            if (json.exists()) {
                // 旧版明文 task.json：导入加密库，成功后删掉 json；库打不开就先只放在内存里，json 留着下次再导
                val parsed = runCatching { fromJson(JSONObject(json.readText())) }.getOrNull() ?: return@forEach
                val saved = db != null && runCatching { DownloadDb.upsert(db, parsed.first, parsed.second) }.isSuccess
                if (saved) runCatching { json.delete() }
                adopt(parsed)
            } else if (db != null) {
                // 库里没有记录、也没有旧元数据的目录无从续传（建目录后、写记录前就被杀）
                runCatching { dir.deleteRecursively() }
            }
        }
        // 已完成的任务不再需要数据文件目录
        loaded.filter { it.state == DownloadState.DONE }.forEach { t ->
            runCatching { taskDir(app, t.id).deleteRecursively() }
        }
        loaded.sortByDescending { it.id }
        tasks.addAll(loaded)
        // 已完成的任务如果文件已被用户在系统里删掉，从列表里清掉（后台做，不拖慢启动）
        val done = loaded.filter { it.state == DownloadState.DONE && it.uri != null }
        if (done.isNotEmpty()) {
            scope.launch {
                for (t in done) {
                    val exists = runCatching {
                        app.contentResolver.query(t.uri!!, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                            ?.use { it.moveToFirst() } == true
                    }.getOrDefault(true)
                    if (!exists) {
                        tasks.removeAll { it.id == t.id }
                        forget(t.id)
                    }
                }
            }
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotEmpty() } else null

    /** 只用于导入旧版 task.json */
    private fun fromJson(o: JSONObject): Pair<DownloadTask, LongArray?> {
        val task = DownloadTask(
            id = o.getLong("id"),
            name = o.getString("name"),
            url = o.optString("url"),
            userAgent = o.str("ua"),
            mime = o.str("mime"),
            uri = o.str("uri")?.let { Uri.parse(it) },
            bytes = o.optLong("bytes"),
            total = o.optLong("total"),
            state = runCatching { DownloadState.valueOf(o.optString("state")) }.getOrDefault(DownloadState.PAUSED),
            error = o.str("error"),
            referer = o.str("referer"),
            withCookies = o.optBoolean("withCookies"),
            geckoCookies = o.optBoolean("geckoCookies"),
            etag = o.str("etag"),
            lastModified = o.str("lastModified"),
            segCount = o.optInt("segCount"),
            fetched = o.optBoolean("fetched"),
        )
        val arr = o.optJSONArray("segDone")
        val seg = if (arr != null) LongArray(arr.length()) { arr.getLong(it) } else null
        return task to seg
    }

    /**
     * 写入加密库的 downloads 表（单语句事务，原子）。任务已被删除则什么都不写（避免把删掉的记录又写回去）。
     * 库用 WAL + synchronous=NORMAL：断电最多丢最后几笔，丢的只会让进度退回到更早的值，
     * 而数据在写进度前已 fsync，所以续传仍然正确。
     */
    private fun persist(id: Long) {
        if (!inited) return
        val task = tasks.firstOrNull { it.id == id } ?: return
        val seg = segSaved[id]
        synchronized(persistLock) {
            if (tasks.none { it.id == id }) return
            val db = HistoryDb.get(app) ?: return
            runCatching { DownloadDb.upsert(db, task, seg) }
                .onFailure { Diag.log("downloads: persist failed: ${it.message}") }
        }
    }

    /** 清掉某任务的数据库记录和数据文件目录（不碰系统下载目录里已保存的文件）。 */
    private fun forget(id: Long) {
        synchronized(persistLock) {
            HistoryDb.get(app)?.let { db ->
                runCatching { DownloadDb.delete(db, id) }
                    .onFailure { Diag.log("downloads: delete failed: ${it.message}") }
            }
            runCatching { taskDir(app, id).deleteRecursively() }
        }
    }

    private fun update(id: Long, transform: (DownloadTask) -> DownloadTask) {
        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0) tasks[index] = transform(tasks[index])
        }
    }

    private fun updateAndPersist(id: Long, transform: (DownloadTask) -> DownloadTask) {
        update(id, transform)
        persist(id)
    }

    // ───────────────────────── 对外接口 ─────────────────────────

    fun start(
        context: Context,
        url: String,
        userAgent: String?,
        fileName: String,
        mimeType: String?,
        onComplete: ((ok: Boolean, error: String?) -> Unit)? = null,
        referer: String? = null,
        withCookies: Boolean = false,
        geckoCookies: Boolean = false,
    ) {
        init(context)
        val task = DownloadTask(
            name = fileName,
            url = url,
            userAgent = userAgent,
            mime = mimeType,
            referer = referer?.takeIf { it.startsWith("http", ignoreCase = true) },
            withCookies = withCookies,
            geckoCookies = geckoCookies,
        )
        if (onComplete != null) completions[task.id] = onComplete
        tasks.add(0, task)
        persist(task.id)
        DownloadService.start(context)
        DownloadService.instance?.showStart(fileName, task.id)
        launch(task.id)
    }

    fun pause(id: Long) {
        jobs.remove(id)?.cancel()
        updateAndPersist(id) { it.copy(state = DownloadState.PAUSED) }
        val task = tasks.firstOrNull { it.id == id }
        if (task != null) {
            val progress = progressOf(task)
            DownloadService.instance?.updatePaused(task.name, progress, id)
        }
    }

    fun resume(context: Context, id: Long) {
        init(context)
        if (tasks.none { it.id == id }) return
        updateAndPersist(id) { it.copy(state = DownloadState.RUNNING, error = null) }
        DownloadService.start(context)
        launch(id)
    }

    fun retry(context: Context, id: Long) = resume(context, id)

    /** 唯一会清掉分段 / 临时数据的入口；[deleteFile] 为 true 时连同系统里已保存的文件一起删 */
    fun delete(context: Context, id: Long, deleteFile: Boolean = false) {
        init(context)
        jobs.remove(id)?.cancel()
        val task = tasks.firstOrNull { it.id == id }
        // 已完成的文件默认保留，只有勾选「同时删除本地文件」才删；未完成的任务留下的是残缺文件，一律清掉
        if (deleteFile || task?.state != DownloadState.DONE) {
            task?.uri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
        synchronized(tasks) { tasks.removeAll { it.id == id } }
        segSaved.remove(id)
        forget(id)
        settle(id, false, "cancelled")
    }

    /** 下载在 IO 线程里完成，Toast 必须切回主线程 */
    private fun toast(context: Context, message: String) {
        val appCtx = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(appCtx, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(id: Long) {
        jobs.remove(id)?.cancel()
        jobs[id] = scope.launch {
            var attempt = 0
            var restarts = 0
            while (attempt < MAX_ATTEMPTS) {
                try {
                    downloadOnce(id)
                    val finished = tasks.firstOrNull { it.id == id }
                    updateAndPersist(id) { it.copy(state = DownloadState.DONE, error = null) }
                    settle(id, true)
                    DownloadService.instance?.finish(finished?.name ?: "文件")
                    toast(app, "下载完成：${finished?.name ?: "文件"}")
                    return@launch
                } catch (e: CancellationException) {
                    return@launch
                } catch (e: RestartDownload) {
                    if (!isActive) return@launch
                    restarts++
                    wipeData(id)
                    if (restarts > MAX_RESTARTS) {
                        failTask(id, e.message ?: "文件反复变化")
                        return@launch
                    }
                    update(id) { it.copy(error = e.message) }
                } catch (e: Exception) {
                    // 连接被 pause / delete 主动断开时也会抛 IO 异常，此时不是失败
                    if (!isActive) return@launch
                    attempt++
                    val message = e.message ?: e.javaClass.simpleName
                    if (attempt >= MAX_ATTEMPTS) {
                        failTask(id, message)
                        return@launch
                    }
                    update(id) { it.copy(error = message) }
                    delay(1200L * attempt)
                }
            }
        }
    }

    private fun failTask(id: Long, message: String) {
        updateAndPersist(id) { it.copy(state = DownloadState.FAILED, error = message) }
        settle(id, false, message)
        DownloadService.instance?.fail("下载失败：$message")
    }

    /** 远端已变化：删掉数据与指纹，下一轮从头开始 */
    private fun wipeData(id: Long) {
        val dir = taskDir(app, id)
        runCatching { File(dir, DATA_PART).delete() }
        runCatching { File(dir, DATA_SEG).delete() }
        segSaved.remove(id)
        updateAndPersist(id) {
            it.copy(bytes = 0, total = 0, etag = null, lastModified = null, segCount = 0, fetched = false)
        }
    }

    // ───────────────────────── 网络 ─────────────────────────

    private fun cookieFor(task: DownloadTask, url: String): String? =
        if (task.geckoCookies) {
            runCatching { GeckoGm.cookieHeader(url) }.getOrNull()
        } else {
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    /**
     * 手动跟随重定向：Cookie 按每一跳的地址重新取（和浏览器一致，不会把 A 站的 Cookie 带给 CDN），
     * Referer 全程保持为来源页。返回的连接已拿到响应码。
     */
    private fun connect(task: DownloadTask, range: String?, ifRange: String?): HttpURLConnection {
        var url = URL(task.url)
        repeat(MAX_REDIRECTS + 1) {
            // 先取 Cookie：Gecko 的 UA 是取 Cookie 时顺带从后台页拿到的
            val cookie = if (task.withCookies) cookieFor(task, url.toString()) else null
            val ua = task.userAgent ?: (if (task.geckoCookies) GeckoGm.userAgent else null) ?: DEFAULT_UA
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                task.referer?.let { setRequestProperty("Referer", it) }
                if (cookie != null) setRequestProperty("Cookie", cookie)
                if (range != null) {
                    setRequestProperty("Range", range)
                    if (ifRange != null) setRequestProperty("If-Range", ifRange)
                }
            }
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("HTTP $code")
                url = URL(url, location)
                if (!url.protocol.equals("http", true) && !url.protocol.equals("https", true)) {
                    throw IllegalStateException("不支持的重定向：${url.protocol}")
                }
                return@repeat
            }
            return c
        }
        throw IllegalStateException("重定向次数过多")
    }

    /** 协程取消（暂停 / 删除）时主动断开连接，否则阻塞中的 read 会一直占着线程继续写文件 */
    private suspend fun <T> withConn(conn: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Unit> { cont ->
                // 可能在主线程触发（pause），关 socket 要换线程
                cont.invokeOnCancellation { Thread { runCatching { conn.disconnect() } }.start() }
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    private fun strongEtag(c: HttpURLConnection): String? =
        c.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }

    private fun lastModifiedOf(c: HttpURLConnection): String? =
        c.getHeaderField("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }

    private fun validatorOf(task: DownloadTask): String? = task.etag ?: task.lastModified

    private class ContentRange(val start: Long, val end: Long, val total: Long)

    private fun parseContentRange(c: HttpURLConnection): ContentRange? {
        val header = c.getHeaderField("Content-Range") ?: return null
        val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").find(header) ?: return null
        return ContentRange(
            m.groupValues[1].toLong(),
            m.groupValues[2].toLong(),
            m.groupValues[3].toLongOrNull() ?: -1L,
        )
    }

    /** 服务器没按 If-Range 重发整文件、但响应头里的指纹和记录的不一致：文件变了 */
    private fun checkUnchanged(task: DownloadTask, c: HttpURLConnection) {
        val etag = strongEtag(c)
        if (task.etag != null && etag != null && etag != task.etag) throw RestartDownload("远端文件已更新（ETag 变化），重新下载")
        val modified = lastModifiedOf(c)
        if (task.etag == null && task.lastModified != null && modified != null && modified != task.lastModified) {
            throw RestartDownload("远端文件已更新（修改时间变化），重新下载")
        }
    }

    /** 带 Range 探测一下是否真支持，并确认长度和首次响应一致 */
    private fun supportsRange(task: DownloadTask, expectedTotal: Long): Boolean = runCatching {
        val c = connect(task, "bytes=0-0", validatorOf(task))
        try {
            val range = parseContentRange(c)
            c.responseCode == 206 && (range == null || range.total <= 0 || range.total == expectedTotal)
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    // ───────────────────────── 下载流程 ─────────────────────────

    private suspend fun downloadOnce(id: Long) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        val dir = taskDir(app, id).apply { mkdirs() }
        val part = File(dir, DATA_PART)
        val seg = File(dir, DATA_SEG)

        // 数据已下完，只差入库（上次入库失败 / 入库途中被杀）：不重下
        if (task.fetched) {
            val data = if (task.segCount > 0) seg else part
            if (data.exists() && task.total > 0 && data.length() == task.total) {
                export(id, data)
                return
            }
            throw RestartDownload("临时文件已丢失，重新下载")
        }

        // 分段任务：按落盘的分段进度续传
        if (task.segCount > 0) {
            val saved = segSaved[id]
            if (!seg.exists() || seg.length() != task.total || saved == null || saved.size != task.segCount) {
                throw RestartDownload("分段进度丢失，重新下载")
            }
            downloadSegmented(id, seg)
            markFetched(id)
            export(id, seg)
            return
        }

        var existing = if (part.exists()) part.length() else 0L
        if (existing > 0 && task.total > 0 && existing > task.total) {
            runCatching { part.delete() }
            existing = 0
        }
        // 长度恰好等于总长度：说明已经写完（只是没来得及标记），直接入库
        if (existing > 0 && task.total > 0 && existing == task.total) {
            markFetched(id)
            export(id, part)
            return
        }

        var conn = connect(task, if (existing > 0) "bytes=$existing-" else null, if (existing > 0) validatorOf(task) else null)
        try {
            val code = conn.responseCode
            if (code == 416 && existing > 0) throw RestartDownload("续传位置越界，重新下载")
            if (code !in 200..299) throw IllegalStateException("HTTP $code")

            var append = false
            if (existing > 0) {
                if (code == 206) {
                    val range = parseContentRange(conn)
                    if (range == null || range.start != existing) throw RestartDownload("续传位置不匹配，重新下载")
                    if (task.total > 0 && range.total > 0 && range.total != task.total) {
                        throw RestartDownload("远端文件长度变化，重新下载")
                    }
                    checkUnchanged(task, conn)
                    append = true
                    if (task.total <= 0 && range.total > 0) update(id) { it.copy(total = range.total) }
                } else {
                    // 200：服务器不支持 Range，或 If-Range 校验失败（文件已变）——整单重下，用这条响应从头写
                    runCatching { part.delete() }
                    existing = 0
                }
            }
            if (!append) {
                val length = conn.contentLengthLong.coerceAtLeast(0)
                updateAndPersist(id) {
                    it.copy(
                        etag = strongEtag(conn),
                        lastModified = lastModifiedOf(conn),
                        total = length,
                        bytes = 0,
                        state = DownloadState.RUNNING,
                    )
                }
                // 多线程分段下载（服务器支持 Range 且文件够大时）
                if (multiThreadEnabled && length > segmentMinBytes) {
                    // 先放掉这条整文件连接，再探测 Range，避免对镜像同时开 N+2 条连接被限流
                    conn.disconnect()
                    if (supportsRange(tasks.first { it.id == id }, length)) {
                        val threads = segments.coerceAtMost(((length / (256 * 1024)) + 1).toInt()).coerceAtLeast(1)
                        runCatching { seg.delete() }
                        java.io.RandomAccessFile(seg, "rw").use { it.setLength(length) }
                        segSaved[id] = LongArray(threads)
                        updateAndPersist(id) { it.copy(segCount = threads) }
                        downloadSegmented(id, seg)
                        markFetched(id)
                        export(id, seg)
                        return
                    }
                    // 不支持 Range：重新建立单连接顺序下载
                    conn = connect(tasks.first { it.id == id }, null, null)
                    val retryCode = conn.responseCode
                    if (retryCode !in 200..299) throw IllegalStateException("HTTP $retryCode")
                }
            } else {
                update(id) { it.copy(bytes = existing, state = DownloadState.RUNNING) }
            }

            val streamConn = conn
            val written = try {
                withConn(streamConn) { stream(id, streamConn, part, append, existing) }
            } finally {
                // 暂停 / 失败 / 完成都按磁盘上的实际长度记一次进度，不删文件
                val len = if (part.exists()) part.length() else 0L
                updateAndPersist(id) { it.copy(bytes = len) }
            }
            val total = tasks.firstOrNull { it.id == id }?.total ?: 0L
            if (total > 0 && written < total) {
                throw IllegalStateException("连接中断（$written / $total），可继续下载")
            }
            update(id) { it.copy(total = written, bytes = written) }
            markFetched(id)
            export(id, part)
        } finally {
            conn.disconnect()
        }
    }

    private fun markFetched(id: Long) = updateAndPersist(id) { it.copy(fetched = true, bytes = it.total) }

    private suspend fun stream(id: Long, conn: HttpURLConnection, file: File, append: Boolean, existing: Long): Long {
        val name = tasks.firstOrNull { it.id == id }?.name ?: ""
        var written = existing
        conn.inputStream.use { input ->
            java.io.FileOutputStream(file, append).use { out ->
                val buffer = ByteArray(256 * 1024)
                var lastUi = 0L
                var lastSave = System.currentTimeMillis()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 250) {
                        lastUi = now
                        update(id) { it.copy(bytes = written) }
                        val total = tasks.firstOrNull { it.id == id }?.total ?: 0L
                        val progress = if (total > 0) written.toFloat() / total else 0f
                        DownloadService.instance?.update(
                            name,
                            progress,
                            "${(progress * 100).toInt()}% · ${sizeText(written)}",
                            id,
                        )
                    }
                    if (now - lastSave > 1000) {
                        lastSave = now
                        out.flush()
                        out.fd.sync()
                        updateAndPersist(id) { it.copy(bytes = written) }
                    }
                }
                out.flush()
                out.fd.sync()
            }
        }
        return written
    }

    /**
     * 多线程分段下载。每段的已完成字节数定期（先 fsync 数据再写 downloads 表）落盘；
     * 暂停 / 失败 / 被杀后按各段进度继续，不动数据文件。
     */
    private suspend fun downloadSegmented(id: Long, file: File) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        val total = task.total
        val threads = task.segCount
        val saved = segSaved[id] ?: throw RestartDownload("分段进度丢失，重新下载")
        val done = AtomicLongArray(threads).also { arr -> saved.forEachIndexed { i, v -> arr.set(i, v) } }
        val chunk = total / threads
        fun segStart(i: Int) = i * chunk
        fun segEnd(i: Int) = if (i == threads - 1) total - 1 else segStart(i) + chunk - 1
        // 每段已完成字节数不可能超过段长：对不上说明进度文件有问题
        for (i in 0 until threads) {
            if (done.get(i) < 0 || done.get(i) > segEnd(i) - segStart(i) + 1) throw RestartDownload("分段进度异常，重新下载")
        }
        fun sum(): Long = (0 until threads).sumOf { done.get(it) }

        java.io.RandomAccessFile(file, "rw").use { raf ->
            fun checkpoint() {
                val snapshot = LongArray(threads) { done.get(it) }
                // 先取快照、再 fsync：落盘的进度一定不超过已落盘的数据
                runCatching { raf.fd.sync() }
                segSaved[id] = snapshot
                updateAndPersist(id) { it.copy(bytes = snapshot.sum()) }
            }
            try {
                coroutineScope {
                    val ticker = launch {
                        var tick = 0
                        while (true) {
                            delay(300)
                            val written = sum()
                            update(id) { it.copy(bytes = written, state = DownloadState.RUNNING) }
                            DownloadService.instance?.update(
                                task.name,
                                (written.toFloat() / total).coerceIn(0f, 1f),
                                "${(written * 100 / total)}% · ${sizeText(written)}",
                                id,
                            )
                            if (++tick % 3 == 0) checkpoint()
                        }
                    }
                    val workers = (0 until threads).map { index ->
                        launch(Dispatchers.IO) {
                            downloadSegment(id, index, segStart(index), segEnd(index), done, raf)
                        }
                    }
                    workers.joinAll()
                    ticker.cancel()
                }
            } finally {
                checkpoint()
            }
            if (sum() != total) throw IllegalStateException("分段数据不完整（${sum()} / $total）")
            update(id) { it.copy(bytes = total) }
        }
    }

    private suspend fun downloadSegment(
        id: Long,
        index: Int,
        start: Long,
        end: Long,
        done: AtomicLongArray,
        raf: java.io.RandomAccessFile,
    ) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        val size = end - start + 1
        if (done.get(index) >= size) return
        val from = start + done.get(index)
        val connection = connect(task, "bytes=$from-$end", validatorOf(task))
        try {
            withConn(connection) {
                val code = connection.responseCode
                if (code != 206) {
                    // 带着指纹却返回 200：If-Range 没通过，文件变了；否则就是服务器忽略了 Range
                    if (code == 200 && validatorOf(task) != null) throw RestartDownload("远端文件已更新，重新下载")
                    throw IllegalStateException("HTTP $code")
                }
                val range = parseContentRange(connection)
                if (range == null || range.start != from || (range.total > 0 && range.total != task.total)) {
                    throw RestartDownload("分段响应与记录不一致，重新下载")
                }
                checkUnchanged(task, connection)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var position = from
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val n = minOf(read.toLong(), end + 1 - position).toInt()
                        if (n <= 0) break
                        synchronized(raf) {
                            raf.seek(position)
                            raf.write(buffer, 0, n)
                        }
                        position += n
                        done.addAndGet(index, n.toLong())
                    }
                    if (position != end + 1) throw IllegalStateException("分段 ${index + 1} 数据不完整")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 数据下完后一次性写入 /sdcard/Download/mafari。失败时删掉未完成的条目，数据文件保留，重试只重做这一步 */
    private suspend fun export(id: Long, file: File) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        val uri = createEntry(app, task) ?: throw IllegalStateException("无法创建文件")
        try {
            val out = app.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入文件")
            out.use { o ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        o.write(buffer, 0, read)
                    }
                }
            }
            app.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (t: Throwable) {
            runCatching { app.contentResolver.delete(uri, null, null) }
            throw t
        }
        // 本地已有同名文件时系统会自动改名（如 xxx (1).zip），读回实际文件名
        update(id) { it.copy(uri = uri, name = actualName(app, uri, it.name)) }
        runCatching { file.delete() }
        persist(id)
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
        init(context)
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
            val saved = DownloadTask(
                name = actualName(context, target, fileName),
                url = "",
                mime = mime,
                uri = target,
                bytes = size,
                total = size,
                state = DownloadState.DONE,
            )
            tasks.add(0, saved)
            persist(saved.id)
            true
        }.getOrElse {
            // 写到一半失败：删掉未完成的条目，避免留下残缺文件
            uri?.let { u -> runCatching { context.contentResolver.delete(u, null, null) } }
            false
        }
    }

    /** 直接把字节写入 Download/mafari（blob / data URL 共用） */
    fun saveBytes(context: Context, bytes: ByteArray, fileName: String, mime: String?): Boolean = runCatching {
        init(context)
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
        val saved = DownloadTask(
            name = actualName(context, uri, fileName),
            url = "",
            mime = mime,
            uri = uri,
            bytes = bytes.size.toLong(),
            total = bytes.size.toLong(),
            state = DownloadState.DONE,
        )
        tasks.add(0, saved)
        persist(saved.id)
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
    private const val MAX_RESTARTS = 3
    private const val MAX_REDIRECTS = 8
    private const val DATA_PART = "data.part"
    private const val DATA_SEG = "data.seg"
    private const val DEFAULT_UA = "Mozilla/5.0 mafari"
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
