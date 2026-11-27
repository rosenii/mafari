/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 已保存的一份通用订阅源（JSON / OPDS）。原文不放在这里：它单独放在 `bookshelf/feeds/<id>.feed`，
 * 列表只读很小的清单，避免启动时把几 MB 的订阅源全读进内存。
 */
class SavedFeed(
    val id: String,
    val name: String,
    /** 订阅地址；本地文件导入的为 null（不能在线刷新）。 */
    val url: String?,
    /** 重定向后的地址，解析相对链接用。 */
    val baseUrl: String?,
    /** 导入时间 */
    val addedAt: Long,
    /** 最近一次刷新 / 导入时间 */
    val updatedAt: Long,
    val bookCount: Int,
    /** "json" / "opds"，只在列表里做标签 */
    val kind: String,
) {
    val canRefresh: Boolean get() = !url.isNullOrBlank()
}

/**
 * 通用订阅源的持久化：导入后保存原文，下次打开 App 还能继续从同一份订阅源挑书、刷新。
 * 同一个地址重复导入会覆盖原来那份（保留导入时间、更新刷新时间）。
 *
 * 只保存通用 JSON / OPDS 订阅源；Legado 书源由 [dev.dsh.miuixbrowser.data.legado.LegadoSourceStore] 管理。
 */
object FeedStore {
    private const val DIR = "bookshelf"
    private const val FEED_DIR = "feeds"
    private const val INDEX = "feeds.json"
    private const val MAX_FEEDS = 200
    private val lock = Any()

    /** 已保存的订阅源（最近导入 / 刷新的在前）。界面直接读这个状态。 */
    var feeds by mutableStateOf<List<SavedFeed>>(emptyList())
        private set

    private var loaded = false

    private fun root(context: Context) = File(context.filesDir, DIR).also { it.mkdirs() }
    private fun feedDir(context: Context) = File(root(context), FEED_DIR).also { it.mkdirs() }
    private fun rawFile(context: Context, id: String) = File(feedDir(context), "$id.feed")

    /** 读清单（只第一次真正读盘）。在 IO 线程调用。 */
    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            feeds = readIndex(context)
            loaded = true
        }
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        load(context)
    }

    /**
     * 保存一份刚导入的订阅源；同一个地址已存在时更新它。返回保存后的条目。
     * 在 IO 线程调用（会写盘）。
     */
    fun add(context: Context, loadedFeed: LoadedFeed, url: String?): SavedFeed {
        require(loadedFeed.feed.sources.isEmpty() && loadedFeed.feed.rssSources.isEmpty()) { "Legado 书源不能保存为通用订阅源" }
        synchronized(lock) {
            ensureLoadedLocked(context)
            val now = System.currentTimeMillis()
            val old = url?.let { u -> feeds.firstOrNull { it.url == u } }
            val id = old?.id ?: UUID.randomUUID().toString().replace("-", "").take(12)
            val saved = SavedFeed(
                id = id,
                name = loadedFeed.feed.name.ifBlank { old?.name.orEmpty() }.ifBlank { defaultName(url) },
                url = url,
                baseUrl = loadedFeed.baseUrl,
                addedAt = old?.addedAt ?: now,
                updatedAt = now,
                bookCount = loadedFeed.feed.books.size,
                kind = feedKind(loadedFeed.raw),
            )
            writeRaw(context, id, loadedFeed.raw)
            val next = (feeds.filterNot { it.id == id } + saved)
                .sortedByDescending { it.updatedAt }
                .take(MAX_FEEDS)
            feeds = next
            // 超出上限被裁掉的订阅源，原文也一起删掉
            val alive = next.mapTo(HashSet()) { it.id }
            runCatching { feedDir(context).listFiles()?.forEach { f -> if (f.name.substringBefore('.') !in alive) f.delete() } }
            writeIndex(context, next)
            return saved
        }
    }

    /** 用新下载到的内容刷新一份订阅源。返回更新后的条目；[id] 不存在时返回 null。在 IO 线程调用。 */
    fun refresh(context: Context, id: String, loadedFeed: LoadedFeed): SavedFeed? {
        require(loadedFeed.feed.sources.isEmpty() && loadedFeed.feed.rssSources.isEmpty()) { "Legado 书源不能保存为通用订阅源" }
        synchronized(lock) {
            ensureLoadedLocked(context)
            val old = feeds.firstOrNull { it.id == id } ?: return null
            val saved = SavedFeed(
                id = old.id,
                name = loadedFeed.feed.name.ifBlank { old.name },
                url = old.url,
                baseUrl = loadedFeed.baseUrl ?: old.baseUrl,
                addedAt = old.addedAt,
                updatedAt = System.currentTimeMillis(),
                bookCount = loadedFeed.feed.books.size,
                kind = feedKind(loadedFeed.raw),
            )
            writeRaw(context, id, loadedFeed.raw)
            val next = (feeds.filterNot { it.id == id } + saved).sortedByDescending { it.updatedAt }
            feeds = next
            writeIndex(context, next)
            return saved
        }
    }

    /** 删除一份订阅源（清单 + 原文）。 */
    fun remove(context: Context, id: String) {
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (feeds.none { it.id == id }) return
            feeds = feeds.filterNot { it.id == id }
            runCatching { rawFile(context, id).delete() }
            writeIndex(context, feeds)
        }
    }

    /** 读回订阅源原文；文件不存在返回 null。在 IO 线程调用。 */
    fun readRaw(context: Context, id: String): String? = runCatching {
        rawFile(context, id).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** 重新解析保存的订阅源。在 IO 线程调用；文件丢了或内容损坏时抛异常。 */
    fun read(context: Context, id: String): BookFeed {
        val saved = feeds.firstOrNull { it.id == id } ?: error(tr("订阅源不存在"))
        val raw = readRaw(context, id) ?: error(tr("订阅源文件缺失，请重新导入"))
        return BookFeedParser.parse(raw, saved.baseUrl)
    }

    private fun ensureLoadedLocked(context: Context) {
        if (loaded) return
        feeds = readIndex(context)
        loaded = true
    }

    private fun feedKind(raw: String): String = if (raw.trimStart().startsWith("<")) "opds" else "json"

    private fun defaultName(url: String?): String =
        url?.let { runCatching { java.net.URL(it).host }.getOrNull() }?.takeIf { it.isNotBlank() } ?: tr("订阅源")

    private fun readIndex(context: Context): List<SavedFeed> {
        val f = File(root(context), INDEX)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SavedFeed(
                    id = id,
                    name = o.optString("name"),
                    url = o.optString("url").ifBlank { null },
                    baseUrl = o.optString("baseUrl").ifBlank { null },
                    addedAt = o.optLong("addedAt"),
                    updatedAt = o.optLong("updatedAt"),
                    bookCount = o.optInt("bookCount"),
                    kind = o.optString("kind", "json").ifBlank { "json" },
                )
            }.filter { rawFile(context, it.id).isFile }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, list: List<SavedFeed>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("url", it.url ?: "")
                    .put("baseUrl", it.baseUrl ?: "").put("addedAt", it.addedAt)
                    .put("updatedAt", it.updatedAt).put("bookCount", it.bookCount).put("kind", it.kind),
            )
        }
        val f = File(root(context), INDEX)
        val tmp = File(f.parentFile, "$INDEX.tmp")
        tmp.writeText(arr.toString())
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            f.writeText(arr.toString())
            tmp.delete()
        }
    }

    private fun writeRaw(context: Context, id: String, raw: String) {
        val f = rawFile(context, id)
        val tmp = File(f.parentFile, "$id.feed.tmp")
        tmp.writeText(raw)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            f.writeText(raw)
            tmp.delete()
        }
    }
}
