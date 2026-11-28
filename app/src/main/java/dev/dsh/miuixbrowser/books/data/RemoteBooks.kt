/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data

import android.content.Context
import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 远端目录里的一章：标题 + 正文地址（Legado 章节页）。 */
class RemoteChapterMeta(val title: String, val url: String)

/**
 * 远端书（Legado 书源）的目录元数据，存在 `bookshelf/<id>/remote.json`。
 * 正文按章下载后放在 `bookshelf/<id>/chapters/<下标>.txt`，不再整本拼成一个巨大的 TXT。
 */
class RemoteBookMeta(
    val sourceUrl: String,
    val bookUrl: String,
    val name: String,
    val author: String,
    val coverUrl: String?,
    val intro: String?,
    val kind: String?,
    val tocUrl: String?,
    val chapters: List<RemoteChapterMeta>,
)

/** 远端书的元数据 / 章节缓存读写。只在 IO 线程调用（很小的文件，但避免占主线程）。 */
object RemoteBooks {
    private const val META = "remote.json"
    private const val CACHE_DIR = "chapters"

    private fun dir(context: Context, bookId: String) = BookShelf.bookDir(context, bookId)

    fun metaFile(context: Context, bookId: String) = File(dir(context, bookId), META)

    fun chapterFile(context: Context, bookId: String, index: Int): File =
        File(File(dir(context, bookId), CACHE_DIR).also { it.mkdirs() }, "$index.txt")

    fun write(context: Context, bookId: String, meta: RemoteBookMeta) {
        val chapters = JSONArray()
        meta.chapters.forEach {
            chapters.put(JSONObject().put("title", it.title).put("url", it.url))
        }
        val o = JSONObject()
            .put("sourceUrl", meta.sourceUrl)
            .put("bookUrl", meta.bookUrl)
            .put("name", meta.name)
            .put("author", meta.author)
            .put("coverUrl", meta.coverUrl ?: "")
            .put("intro", meta.intro ?: "")
            .put("kind", meta.kind ?: "")
            .put("tocUrl", meta.tocUrl ?: "")
            .put("chapters", chapters)
        val f = metaFile(context, bookId)
        val tmp = File(f.parentFile, "$META.tmp")
        tmp.writeText(o.toString())
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            f.writeText(o.toString())
            tmp.delete()
        }
    }

    /** 读目录元数据；文件缺失或损坏返回 null。 */
    fun read(context: Context, bookId: String): RemoteBookMeta? = runCatching {
        val f = metaFile(context, bookId)
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        val arr = o.optJSONArray("chapters") ?: return null
        val chapters = ArrayList<RemoteChapterMeta>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val title = c.optString("title").ifBlank { tr("第 {0} 章", i + 1) }
            val url = c.optString("url")
            if (url.isNotBlank()) chapters.add(RemoteChapterMeta(title, url))
        }
        if (chapters.isEmpty()) return null
        RemoteBookMeta(
            sourceUrl = o.optString("sourceUrl"),
            bookUrl = o.optString("bookUrl"),
            name = o.optString("name"),
            author = o.optString("author"),
            coverUrl = o.optString("coverUrl").ifBlank { null },
            intro = o.optString("intro").ifBlank { null },
            kind = o.optString("kind").ifBlank { null },
            tocUrl = o.optString("tocUrl").ifBlank { null },
            chapters = chapters,
        )
    }.getOrNull()

    /** 已下载的章节正文；没下过返回 null。 */
    fun cached(context: Context, bookId: String, index: Int): String? = runCatching {
        val f = chapterFile(context, bookId, index)
        if (f.isFile) f.readText() else null
    }.getOrNull()

    fun save(context: Context, bookId: String, index: Int, text: String) {
        val f = chapterFile(context, bookId, index)
        val tmp = File(f.parentFile, "$index.txt.tmp")
        tmp.writeText(text)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    /** 已缓存的章数（书架上显示「已缓存 n / total」用）。 */
    fun cachedCount(context: Context, bookId: String): Int =
        runCatching { File(dir(context, bookId), CACHE_DIR).listFiles()?.count { it.extension == "txt" } ?: 0 }.getOrDefault(0)
}
