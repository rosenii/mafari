package dev.dsh.miuixbrowser.ext

import android.content.Context
import android.net.Uri
import dev.dsh.miuixbrowser.download.DownloadState
import dev.dsh.miuixbrowser.download.DownloadTask
import dev.dsh.miuixbrowser.download.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Partial WebExtension downloads API backed by Mafari's existing persistent Downloader. */
class DownloadsApi(private val context: Context, private val pages: ExtensionPageHost) {
    fun register(router: ApiRouter) {
        router.register("downloads.download") { ctx, args ->
            val options = args.argOrNull(0) as? JSONObject
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "downloads.download 需要 options 对象")
            val url = options.optString("url").trim()
            if (url.length > MAX_URL_LENGTH || !isHttpUrl(url)) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "目前仅支持有效的 HTTP/HTTPS 下载地址")
            }
            val allowed = setOf("url", "filename")
            val keys = options.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in allowed) throw ApiException(ErrorCodes.UNSUPPORTED, "downloads.download 暂不支持字段：$key")
            }
            val requestedName = options.optString("filename").takeIf { it.isNotBlank() }
            val name = requestedName?.let { raw ->
                if (raw.contains('/') || raw.contains('\\')) throw ApiException(ErrorCodes.BAD_REQUEST, "filename 只能是文件名，不能包含目录")
                Downloader.originalName(raw)
            } ?: Downloader.fileNameFrom(url, null)
            withContext(Dispatchers.Main.immediate) {
                Downloader.init(context)
                val id = Downloader.startForExtension(context, url, name)
                val task = Downloader.tasks.firstOrNull { it.id == id }
                if (task != null) pages.emitEvent(ctx.ext.spec.id, EVENT_CREATED, task.toJson())
                id
            }
        }
        router.register("downloads.search") { _, args ->
            val query = args.argOrNull(0) as? JSONObject ?: JSONObject()
            val limit = query.optInt("limit", 100).coerceIn(1, MAX_RESULTS)
            val order = query.optString("orderBy", "-startTime")
            if (order !in setOf("startTime", "-startTime", "-endTime", "endTime")) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "暂只支持按 startTime/endTime 排序")
            }
            withContext(Dispatchers.Main.immediate) {
                Downloader.init(context)
                val filtered = Downloader.tasks.toList().filter { it.matches(query) }
                val sorted = when (order) {
                    "startTime", "endTime" -> filtered.reversed()
                    else -> filtered
                }
                JSONArray().apply { sorted.take(limit).forEach { put(it.toJson()) } }
            }
        }
        router.register("downloads.pause") { _, args -> withContext(Dispatchers.Main.immediate) {
            val id = args.downloadId(); Downloader.init(context)
            val task = Downloader.tasks.firstOrNull { it.id == id } ?: throw ApiException(ErrorCodes.BAD_REQUEST, "下载任务不存在")
            if (task.state != DownloadState.RUNNING) throw ApiException(ErrorCodes.BAD_REQUEST, "只有正在下载的任务可以暂停")
            Downloader.pause(id); null
        } }
        router.register("downloads.resume") { _, args -> withContext(Dispatchers.Main.immediate) {
            val id = args.downloadId(); Downloader.init(context)
            val task = Downloader.tasks.firstOrNull { it.id == id } ?: throw ApiException(ErrorCodes.BAD_REQUEST, "下载任务不存在")
            if (task.state != DownloadState.PAUSED && task.state != DownloadState.FAILED) throw ApiException(ErrorCodes.BAD_REQUEST, "任务当前不能继续")
            Downloader.resume(context, id); null
        } }
        router.register("downloads.cancel") { ctx, args -> withContext(Dispatchers.Main.immediate) {
            val id = args.downloadId(); Downloader.init(context)
            if (Downloader.tasks.none { it.id == id }) throw ApiException(ErrorCodes.BAD_REQUEST, "下载任务不存在")
            Downloader.delete(context, id, false)
            pages.emitEvent(ctx.ext.spec.id, EVENT_ERASED, JSONObject().put("id", id))
            null
        } }
        router.register("downloads.erase") { ctx, args -> withContext(Dispatchers.Main.immediate) {
            val ids = JSONArray(); val query = args.argOrNull(0) as? JSONObject ?: JSONObject()
            Downloader.init(context)
            Downloader.tasks.toList().filter { it.matches(query) }.forEach { task ->
                ids.put(task.id)
                Downloader.delete(context, task.id, false)
                pages.emitEvent(ctx.ext.spec.id, EVENT_ERASED, JSONObject().put("id", task.id))
            }
            ids
        } }
    }

    private fun JSONArray.downloadId(): Long {
        val raw = argOrNull(0)
        val id = when (raw) { is Number -> raw.toLong(); is String -> raw.toLongOrNull(); else -> null }
        return id?.takeIf { it > 0 } ?: throw ApiException(ErrorCodes.BAD_REQUEST, "downloadId 无效")
    }

    private fun DownloadTask.matches(q: JSONObject): Boolean {
        fun strings(key: String): List<String> = when (val v = q.opt(key)) {
            is String -> listOf(v)
            is Number -> listOf(v.toLong().toString())
            is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).takeIf(String::isNotBlank) }
            else -> emptyList()
        }
        val urls = strings("url"); if (urls.isNotEmpty() && urls.none { url.contains(it, true) }) return false
        val names = strings("filename"); if (names.isNotEmpty() && names.none { name.contains(it, true) }) return false
        val ids = strings("id"); if (ids.isNotEmpty() && id.toString() !in ids) return false
        val states = strings("state")
        val apiState = when (state) { DownloadState.RUNNING -> "in_progress"; DownloadState.PAUSED, DownloadState.FAILED -> "interrupted"; DownloadState.DONE -> "complete" }
        if (states.isNotEmpty() && apiState !in states) return false
        val mimeTypes = strings("mimeType")
        if (mimeTypes.isNotEmpty() && mimeTypes.none { mime.orEmpty().contains(it, true) }) return false
        return true
    }

    private fun DownloadTask.toJson(): JSONObject {
        val totalBytes = total.takeIf { it > 0 } ?: -1L
        val stateName = when (state) { DownloadState.RUNNING -> "in_progress"; DownloadState.PAUSED -> "interrupted"; DownloadState.DONE -> "complete"; DownloadState.FAILED -> "interrupted" }
        return JSONObject()
            .put("id", id).put("url", url).put("finalUrl", url)
            .put("filename", name).put("mime", mime ?: JSONObject.NULL)
            .put("startTime", java.util.Date(id / 1000L).toInstant().toString())
            .put("endTime", if (state == DownloadState.DONE || state == DownloadState.FAILED) java.util.Date(id / 1000L).toInstant().toString() else JSONObject.NULL)
            .put("state", stateName).put("paused", state == DownloadState.PAUSED)
            .put("canResume", state == DownloadState.PAUSED || state == DownloadState.FAILED)
            .put("bytesReceived", bytes).put("totalBytes", totalBytes)
            .put("fileSize", if (state == DownloadState.DONE) bytes else -1)
            .put("exists", state == DownloadState.DONE && uri != null)
            .put("error", error ?: JSONObject.NULL)
            .put("urlChain", JSONArray().put(url))
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value); uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    companion object {
        const val EVENT_CREATED = "downloads.onCreated"
        const val EVENT_ERASED = "downloads.onErased"
        private const val MAX_URL_LENGTH = 16_384
        private const val MAX_RESULTS = 1_000
    }
}
