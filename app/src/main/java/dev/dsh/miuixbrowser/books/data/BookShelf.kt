/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** 书架里的一本书。封面文件 / 正文文件都在 [BookShelf] 的目录里，按 [id] 分文件夹存放。 */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    /** 正文文件名（相对书的目录），如 book.txt / book.epub */
    val fileName: String,
    val addedAt: Long,
    /** 是否有封面（封面文件见 [BookShelf.coverFile]） */
    val hasCover: Boolean = false,
    /** 封面修订号：换封面时 +1，用来让界面缓存失效 */
    val coverRev: Int = 0,
    /** 阅读进度：章节下标、章内字符偏移（与排版无关，换字号 / 屏幕尺寸后仍能回到同一处）、全书百分比 0..1 */
    val chapter: Int = 0,
    val offset: Int = 0,
    val progress: Float = 0f,
    /** 所属分类的 id；空串 = 未分类（只出现在「全部」里） */
    val category: String = "",
    /** 远端书（Legado 书源）：正文不落盘，按章按需下载，见 [RemoteBooks] */
    val remote: Boolean = false,
    /** 远端书的目录章数（书架 / 阅读器用，不读元数据也能显示） */
    val remoteTotal: Int = 0,
)

/** 书架分类。书只记录分类 id，改名不用动书。 */
data class BookCategory(val id: String, val name: String)

/** 书架排序方式。[id] 写进 SharedPreferences，不要改。 */
enum class BookSort(val id: Int) {
    ADDED(0), TITLE(1), AUTHOR(2), PROGRESS(3);

    companion object {
        fun fromId(id: Int): BookSort = entries.firstOrNull { it.id == id } ?: ADDED
    }
}

/** 一次导入的结果。 */
class BookImportResult(val added: Int, val skipped: Int)

/** 从文件名 / EPUB 元数据里读出的书名、作者、封面。 */
class BookMeta(val title: String, val author: String, val cover: ByteArray? = null)

/**
 * 书架：本地导入的小说。
 *
 * 目录结构（应用私有目录）：`bookshelf/index.json` 是书目索引，`bookshelf/<id>/book.<ext>` 是正文副本，
 * `bookshelf/<id>/cover.img` 是封面（压到长边 ≤ 900px 的 JPEG）。导入时把文件复制进来，
 * 原文件被移动 / 删除也不影响阅读。来源有两种：本地文件（[importUris]）和 JSON 订阅源（见 BookFeed.kt，下载后走 [addBook]）。
 */
object BookShelf {
    private const val DIR = "bookshelf"
    private const val INDEX = "index.json"
    private const val CATEGORIES = "categories.json"
    private const val MAX_COVER_SIDE = 900
    private val SUPPORTED = setOf("txt", "epub")

    private val lock = Any()

    /** 书目（新导入的在前）。界面直接读这个状态；只在主线程之外改完后一次性赋值。 */
    var books by mutableStateOf<List<Book>>(emptyList())
        private set

    /** 分类（按创建顺序）。 */
    var categories by mutableStateOf<List<BookCategory>>(emptyList())
        private set

    private var loaded = false

    private fun root(context: Context) = File(context.filesDir, DIR).also { it.mkdirs() }
    internal fun bookDir(context: Context, id: String) = File(root(context), id)

    fun coverFile(context: Context, id: String) = File(bookDir(context, id), "cover.img")

    /** 书的正文副本。 */
    fun bookFile(context: Context, book: Book) = File(bookDir(context, book.id), book.fileName)

    /** 记录阅读进度（没变化就不写盘）。在 IO 线程调用。 */
    fun saveProgress(context: Context, id: String, chapter: Int, offset: Int, progress: Float) {
        load(context)
        synchronized(lock) {
            val old = books.firstOrNull { it.id == id } ?: return
            if (old.chapter == chapter && old.offset == offset && old.progress == progress) return
            val next = books.map { if (it.id == id) it.copy(chapter = chapter, offset = offset, progress = progress) else it }
            writeIndex(context, next)
            books = next
        }
    }

    /** 读取索引（只第一次真正读盘）。在 IO 线程调用。 */
    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            books = readIndex(context)
            categories = readCategories(context)
            loaded = true
        }
    }

    // ---- 导入 ----

    /** 把用户选的文件复制进书架。不支持的格式计入 skipped。在 IO 线程调用。 */
    fun importUris(context: Context, uris: List<Uri>): BookImportResult {
        load(context)
        var added = 0
        var skipped = 0
        for (uri in uris) {
            val ok = runCatching { importOne(context, uri) }.getOrDefault(false)
            if (ok) added++ else skipped++
        }
        return BookImportResult(added, skipped)
    }

    private fun importOne(context: Context, uri: Uri): Boolean {
        val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: return false
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return addBook(context, displayName, ext, null, null, null) { target ->
            val input = context.contentResolver.openInputStream(uri) ?: error("open failed")
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        }
    }

    /**
     * 新建一本书并入架：[write] 把正文写进给定的文件；书名 / 作者 / 封面默认取自文件名或 EPUB 元数据，
     * [title] / [author] / [cover] 非空时优先（订阅源给的信息）。不支持的格式返回 false；写盘失败抛异常（已清理半成品）。在 IO 线程调用。
     */
    fun addBook(
        context: Context,
        displayName: String,
        ext: String,
        title: String?,
        author: String?,
        cover: ByteArray?,
        write: (File) -> Unit,
    ): Boolean {
        load(context)
        if (ext !in SUPPORTED) return false

        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val dir = bookDir(context, id).also { it.mkdirs() }
        val target = File(dir, "book.$ext")
        try {
            write(target)
            if (target.length() == 0L) error("empty file")

            val meta = if (ext == "epub") readEpubMeta(target, displayName) else metaFromTxtName(displayName)
            var hasCover = false
            val coverBytes = cover ?: meta.cover
            if (coverBytes != null) {
                hasCover = runCatching { writeCover(coverFile(context, id), decodeScaled(coverBytes)) }.getOrDefault(false)
            }
            val book = Book(
                id = id,
                title = title?.takeIf { it.isNotBlank() } ?: meta.title,
                author = author?.takeIf { it.isNotBlank() } ?: meta.author,
                fileName = target.name,
                addedAt = System.currentTimeMillis(),
                hasCover = hasCover,
            )
            synchronized(lock) {
                val next = listOf(book) + books
                writeIndex(context, next)
                books = next
            }
            return true
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    /**
     * 新建一本远端书（Legado 书源）：只写目录元数据和封面，正文等阅读器按章下载。
     * [meta] 里的章节就是目录；在 IO 线程调用。
     */
    fun addRemoteBook(
        context: Context,
        title: String,
        author: String,
        cover: ByteArray?,
        meta: RemoteBookMeta,
    ): Boolean {
        load(context)
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val dir = bookDir(context, id).also { it.mkdirs() }
        try {
            RemoteBooks.write(context, id, meta)
            var hasCover = false
            if (cover != null) {
                hasCover = runCatching { writeCover(coverFile(context, id), decodeScaled(cover)) }.getOrDefault(false)
            }
            val book = Book(
                id = id,
                title = title.ifBlank { meta.name },
                author = author.ifBlank { meta.author },
                fileName = "",
                addedAt = System.currentTimeMillis(),
                hasCover = hasCover,
                remote = true,
                remoteTotal = meta.chapters.size,
            )
            synchronized(lock) {
                val next = listOf(book) + books
                writeIndex(context, next)
                books = next
            }
            return true
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    // ---- 编辑 / 删除 ----

    /**
     * 保存修改：[title] 为空时保持原书名；[newCover] 非 null 时替换封面。在 IO 线程调用。
     * @return 保存是否成功（书已被删掉时返回 false）
     */
    fun update(context: Context, id: String, title: String, author: String? = null, newCover: Bitmap? = null): Boolean {
        load(context)
        synchronized(lock) {
            val old = books.firstOrNull { it.id == id } ?: return false
            var hasCover = old.hasCover
            var rev = old.coverRev
            if (newCover != null) {
                if (writeCover(coverFile(context, id), newCover)) {
                    hasCover = true
                    rev += 1
                }
            }
            val updated = old.copy(
                title = title.trim().ifEmpty { old.title },
                author = author?.trim() ?: old.author,
                hasCover = hasCover,
                coverRev = rev,
            )
            val next = books.map { if (it.id == id) updated else it }
            writeIndex(context, next)
            books = next
            return true
        }
    }

    /** 删除一本书（索引 + 正文 + 封面）。在 IO 线程调用。 */
    fun delete(context: Context, id: String) {
        load(context)
        synchronized(lock) {
            val next = books.filterNot { it.id == id }
            writeIndex(context, next)
            books = next
        }
        bookDir(context, id).deleteRecursively()
    }

    /** 一次删除多本书（索引 + 正文 + 封面）。在 IO 线程调用。 */
    fun deleteMany(context: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        load(context)
        synchronized(lock) {
            val next = books.filterNot { it.id in ids }
            writeIndex(context, next)
            books = next
        }
        ids.forEach { bookDir(context, it).deleteRecursively() }
    }

    // ---- 分类 ----

    /** 分类名是否可用：去掉首尾空白后非空，且不与别的分类重名（忽略大小写）。[exceptId] 是正在改名的那个分类。 */
    internal fun isCategoryNameFree(existing: List<BookCategory>, name: String, exceptId: String? = null): Boolean {
        val n = name.trim()
        return n.isNotEmpty() && existing.none { it.id != exceptId && it.name.equals(n, ignoreCase = true) }
    }

    /** 新建分类；名字为空或重名返回 null。在 IO 线程调用。 */
    fun createCategory(context: Context, name: String): BookCategory? {
        load(context)
        synchronized(lock) {
            if (!isCategoryNameFree(categories, name)) return null
            val c = BookCategory(UUID.randomUUID().toString().replace("-", "").take(12), name.trim())
            val next = categories + c
            writeCategories(context, next)
            categories = next
            return c
        }
    }

    /** 分类改名；名字为空或重名返回 false。在 IO 线程调用。 */
    fun renameCategory(context: Context, id: String, name: String): Boolean {
        load(context)
        synchronized(lock) {
            if (categories.none { it.id == id } || !isCategoryNameFree(categories, name, exceptId = id)) return false
            val next = categories.map { if (it.id == id) it.copy(name = name.trim()) else it }
            writeCategories(context, next)
            categories = next
            return true
        }
    }

    /** 删除分类：里面的书不删，回到「未分类」。在 IO 线程调用。 */
    fun deleteCategory(context: Context, id: String) {
        load(context)
        synchronized(lock) {
            val nextCats = categories.filterNot { it.id == id }
            writeCategories(context, nextCats)
            categories = nextCats
            if (books.any { it.category == id }) {
                val nextBooks = books.map { if (it.category == id) it.copy(category = "") else it }
                writeIndex(context, nextBooks)
                books = nextBooks
            }
        }
    }

    /** 把一批书移进分类 [categoryId]；传空串 = 移出分类。分类不存在时按「移出」处理。在 IO 线程调用。 */
    fun setCategory(context: Context, ids: Set<String>, categoryId: String) {
        if (ids.isEmpty()) return
        load(context)
        synchronized(lock) {
            val target = if (categories.any { it.id == categoryId }) categoryId else ""
            val next = books.map { if (it.id in ids && it.category != target) it.copy(category = target) else it }
            writeIndex(context, next)
            books = next
        }
    }

    // ---- 搜索 / 排序（纯函数，单测覆盖） ----

    /**
     * 书架实际显示的列表：先按分类过滤（[categoryId] 为 null = 全部），再按书名 / 作者搜索（忽略大小写，空白查询不过滤），最后排序。
     * 书名 / 作者排序按中文拼音序；相同的再按添加时间，保证顺序稳定。
     */
    fun visible(all: List<Book>, query: String, categoryId: String?, sort: BookSort, desc: Boolean): List<Book> {
        val q = query.trim()
        val filtered = all.filter { b ->
            (categoryId == null || b.category == categoryId) &&
                (q.isEmpty() || b.title.contains(q, ignoreCase = true) || b.author.contains(q, ignoreCase = true))
        }
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        val primary: Comparator<Book> = when (sort) {
            BookSort.ADDED -> compareBy { it.addedAt }
            BookSort.TITLE -> Comparator { a, b -> collator.compare(a.title, b.title) }
            BookSort.AUTHOR -> Comparator { a, b -> collator.compare(a.author, b.author) }
            BookSort.PROGRESS -> compareBy { it.progress }
        }
        val cmp = if (desc) primary.reversed() else primary
        return filtered.sortedWith(cmp.thenByDescending { it.addedAt })
    }

    // ---- 封面 ----

    /** 解码用户选的图片（只解到长边 ≤ [MAX_COVER_SIDE]）。在 IO 线程调用。 */
    fun decodeCoverFromUri(context: Context, uri: Uri): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        decodeScaled(bytes)
    }.getOrNull()

    /** 读书架里存的封面，解到宽度约 [targetWidth]px。在 IO 线程调用。 */
    fun loadCover(context: Context, id: String, targetWidth: Int): Bitmap? = runCatching {
        val f = coverFile(context, id)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, targetWidth) })
    }.getOrNull()

    private fun sampleFor(width: Int, target: Int): Int {
        var s = 1
        while (width / (s * 2) >= target) s *= 2
        return s
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleFor(maxOf(bounds.outWidth, bounds.outHeight), MAX_COVER_SIDE)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun writeCover(file: File, bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val ok = tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (!ok) { tmp.delete(); return false }
        if (file.exists()) file.delete()
        return tmp.renameTo(file)
    }

    // ---- 索引读写 ----

    private fun readIndex(context: Context): List<Book> {
        val f = File(root(context), INDEX)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isEmpty() || !bookDir(context, id).isDirectory) return@mapNotNull null
                Book(
                    id = id,
                    title = o.optString("title"),
                    author = o.optString("author"),
                    fileName = o.optString("file"),
                    addedAt = o.optLong("addedAt"),
                    hasCover = o.optBoolean("hasCover"),
                    coverRev = o.optInt("coverRev"),
                    chapter = o.optInt("chapter"),
                    offset = o.optInt("offset"),
                    progress = o.optDouble("progress", 0.0).toFloat(),
                    category = o.optString("category"),
                    remote = o.optBoolean("remote"),
                    remoteTotal = o.optInt("remoteTotal"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, list: List<Book>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("title", it.title).put("author", it.author)
                    .put("file", it.fileName).put("addedAt", it.addedAt)
                    .put("hasCover", it.hasCover).put("coverRev", it.coverRev)
                    .put("chapter", it.chapter).put("offset", it.offset).put("progress", it.progress.toDouble())
                    .put("category", it.category)
                    .put("remote", it.remote).put("remoteTotal", it.remoteTotal),
            )
        }
        val f = File(root(context), INDEX)
        val tmp = File(f.parentFile, "$INDEX.tmp")
        tmp.writeText(arr.toString())
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }

    private fun readCategories(context: Context): List<BookCategory> {
        val f = File(root(context), CATEGORIES)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isEmpty() || name.isBlank()) null else BookCategory(id, name)
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCategories(context: Context, list: List<BookCategory>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val f = File(root(context), CATEGORIES)
        val tmp = File(f.parentFile, "$CATEGORIES.tmp")
        tmp.writeText(arr.toString())
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }

    // ---- 元数据解析（纯函数，单测覆盖） ----

    /** TXT：书名取文件名；支持「《书名》作者：xxx」「书名 作者：xxx」两种常见命名。 */
    internal fun metaFromTxtName(fileName: String): BookMeta {
        val (title, author) = parseTxtName(fileName)
        return BookMeta(title, author)
    }

    internal fun parseTxtName(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').trim()
        val bracket = Regex("^《(.+?)》\\s*(?:作者)?[:：_\\-\\s]*(.*)$").find(base)
        if (bracket != null) {
            val t = bracket.groupValues[1].trim()
            if (t.isNotEmpty()) return t to bracket.groupValues[2].trim()
        }
        val labeled = Regex("^(.+?)[\\s_\\-]*作者[:：]\\s*(.+)$").find(base)
        if (labeled != null) {
            val t = labeled.groupValues[1].trim()
            val a = labeled.groupValues[2].trim()
            if (t.isNotEmpty()) return t to a
        }
        return base.ifEmpty { fileName } to ""
    }

    /** EPUB：读 container.xml → OPF，取 dc:title / dc:creator / 封面图。读不出来的字段回退到文件名。 */
    private fun readEpubMeta(file: File, displayName: String): BookMeta {
        val fallback = parseTxtName(displayName)
        return runCatching {
            ZipFile(file).use { zip ->
                fun text(name: String): String? =
                    zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
                val container = text("META-INF/container.xml") ?: error("no container")
                val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1) ?: error("no opf")
                val opf = text(opfPath) ?: error("no opf file")
                val parsed = parseOpf(opf)
                var cover: ByteArray? = null
                val href = parsed.coverHref
                if (href != null) {
                    val path = resolveEpubPath(opfPath, href)
                    val entry = zip.getEntry(path) ?: zip.getEntry(Uri.decode(path))
                    if (entry != null && entry.size in 1..(8L * 1024 * 1024)) {
                        cover = zip.getInputStream(entry).use { it.readBytes() }
                    }
                }
                BookMeta(
                    title = parsed.title.ifBlank { fallback.first },
                    author = parsed.author.ifBlank { fallback.second },
                    cover = cover,
                )
            }
        }.getOrElse { BookMeta(fallback.first, fallback.second) }
    }

    internal class OpfInfo(val title: String, val author: String, val coverHref: String?)

    internal fun parseOpf(opf: String): OpfInfo {
        fun tag(name: String): String =
            Regex("<(?:dc:)?$name\\b[^>]*>([\\s\\S]*?)</(?:dc:)?$name>", RegexOption.IGNORE_CASE)
                .find(opf)?.groupValues?.get(1)?.let { decodeEntities(it.replace(Regex("<[^>]+>"), "")).trim() }.orEmpty()

        val items = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).map { it.value }.toList()
        fun attr(tagText: String, name: String): String? =
            Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tagText)?.groupValues?.get(1)

        // EPUB3：properties 含 cover-image；EPUB2：<meta name="cover" content="<item id>">
        var href = items.firstOrNull { attr(it, "properties")?.split(' ')?.contains("cover-image") == true }
            ?.let { attr(it, "href") }
        if (href == null) {
            val coverId = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
                .map { it.value }
                .firstOrNull { attr(it, "name").equals("cover", ignoreCase = true) }
                ?.let { attr(it, "content") }
            if (coverId != null) href = items.firstOrNull { attr(it, "id") == coverId }?.let { attr(it, "href") }
        }
        return OpfInfo(tag("title"), tag("creator"), href?.let { decodeEntities(it) })
    }

    internal fun resolveEpubPath(opfPath: String, href: String): String {
        val base = opfPath.substringBeforeLast('/', "")
        val raw = if (href.startsWith("/")) href.removePrefix("/") else if (base.isEmpty()) href else "$base/$href"
        val parts = ArrayList<String>()
        for (p in raw.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    internal fun decodeEntities(s: String): String = TextCleaner.decode(s)
}
