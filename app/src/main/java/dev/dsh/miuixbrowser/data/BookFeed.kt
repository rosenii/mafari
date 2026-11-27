/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

import android.content.Context
import android.net.Uri
import dev.dsh.miuixbrowser.data.legado.LegadoBook
import dev.dsh.miuixbrowser.data.legado.LegadoSource
import dev.dsh.miuixbrowser.data.legado.LegadoSourceParser
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.net.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** 订阅源里的一章：[url] 指向正文，或直接内嵌 [text]。 */
class FeedChapter(val title: String, val url: String?, val text: String?)

/**
 * 订阅源里的一本书。正文来源三选一（按此优先级）：
 * [url]（整本 txt / epub 文件）→ [chapters]（内嵌章节列表）→ [chaptersUrl]（章节列表的 JSON 链接）。
 */
class FeedBook(
    val title: String,
    val author: String,
    val cover: String?,
    val url: String?,
    /** "txt" / "epub"；源里没写时为 null，下载后按文件内容判断 */
    val format: String?,
    val chapters: List<FeedChapter>,
    val chaptersUrl: String?,
    /** 来自 Legado 书源的搜索 / 发现结果：下载时由书源引擎读详情、目录、正文（此时 url / chapters 都是空的）。 */
    val legado: LegadoBook? = null,
    val legadoSource: LegadoSource? = null,
)

/**
 * 一份订阅源。通用 JSON 订阅源直接带 [books]；Legado（开源阅读）书源文件带 [sources]、[books] 为空——
 * 书要用户在书源里搜索 / 发现之后才有，见 [BookFeedImport.importBook] 里的 Legado 分支。
 */
class BookFeed(val name: String, val books: List<FeedBook>, val sources: List<LegadoSource> = emptyList())

/**
 * 通用 JSON 小说订阅源的解析。不依赖具体站点的规则，只认「书目列表 + 每本书的下载 / 章节链接」，字段名兼容常见写法：
 *
 * ```
 * { "name": "我的书源",
 *   "books": [
 *     { "title": "三体", "author": "刘慈欣", "cover": "https://…/c.jpg", "url": "https://…/santi.epub", "format": "epub" },
 *     { "title": "某某传", "author": "佚名",
 *       "chapters": [ { "title": "第一章", "url": "https://…/1.txt" }, { "title": "第二章", "text": "正文…" } ] }
 *   ] }
 * ```
 *
 * 顶层可以直接是数组；书目列表也可以放在 books / novels / items / list / entries / data … 下；
 * 同时兼容 OPDS 2.0（publications + links 里的 acquisition）。链接可以是相对地址，相对订阅源自己的地址解析。
 */
object BookFeedParser {
    private val LIST_KEYS = listOf("books", "novels", "items", "list", "entries", "results", "result", "publications", "library", "shelf", "data")
    private val CHAPTER_LIST_KEYS = listOf("chapters", "chapterList", "chapter_list", "list", "items", "toc", "catalog", "contents", "data")
    private val NAME_KEYS = arrayOf("name", "title", "feedName", "sourceName")

    /** [baseUrl]：订阅源自身的地址（本地文件为 null，此时相对链接无法解析、会被丢弃）。解析失败抛 [IllegalArgumentException]（消息可直接给用户看）。 */
    fun parse(text: String, baseUrl: String? = null): BookFeed {
        val root = runCatching { JSONTokener(text.trim().trimStart('\uFEFF')).nextValue() }.getOrNull()
        if (LegadoSourceParser.looksLikeLegado(root)) {
            val sources = LegadoSourceParser.parse(root)
            return BookFeed(sources.singleOrNull()?.name.orEmpty(), emptyList(), sources)
        }
        var name = ""
        val arr: JSONArray? = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                name = root.str(*NAME_KEYS) ?: root.optJSONObject("metadata")?.str("title").orEmpty()
                findList(root, LIST_KEYS, 0)
            }
            else -> null
        }
        require(arr != null) { tr("不是有效的订阅源（需要 JSON 书目列表）") }
        val out = ArrayList<FeedBook>()
        for (i in 0 until arr.length()) parseBook(arr.optJSONObject(i), baseUrl)?.let(out::add)
        require(out.isNotEmpty()) { tr("订阅源里没有可导入的书（每本书需要书名，以及下载链接或章节列表；本地文件里的链接需写完整的 http / https 地址）") }
        return BookFeed(name, out)
    }

    /** 章节列表的 JSON（数组，或放在 chapters / list / data … 下的数组），元素可以是链接字符串或 {title,url|text}。 */
    fun parseChapterList(text: String, baseUrl: String?): List<FeedChapter> {
        val root = runCatching { JSONTokener(text.trim().trimStart('\uFEFF')).nextValue() }.getOrNull()
        val arr = when (root) {
            is JSONArray -> root
            is JSONObject -> findList(root, CHAPTER_LIST_KEYS, 0)
            else -> null
        } ?: return emptyList()
        return chapters(arr, baseUrl)
    }

    private fun findList(o: JSONObject, keys: List<String>, depth: Int): JSONArray? {
        for (k in keys) {
            when (val v = o.opt(k)) {
                is JSONArray -> if (v.length() > 0) return v
                is JSONObject -> if (depth < 2) findList(v, keys, depth + 1)?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun parseBook(o: JSONObject?, base: String?): FeedBook? {
        o ?: return null
        val meta = o.optJSONObject("metadata")
        val title = o.str("title", "name", "bookName", "book_name", "novelName") ?: meta?.str("title") ?: return null
        val author = authorOf(o.opt("author") ?: o.opt("authors") ?: o.opt("writer") ?: o.opt("bookAuthor") ?: meta?.opt("author") ?: meta?.opt("authors"))

        val links = o.optJSONArray("links")
        val acquisition = findLink(links) { rel -> rel.contains("acquisition") }
        val imageLink = findLink(links) { rel -> rel.contains("image") || rel.contains("cover") || rel.contains("thumbnail") }

        val url = resolve(
            o.str("download", "downloadUrl", "download_url", "fileUrl", "file_url", "file", "epub", "txt", "url", "link", "href")
                ?: acquisition?.str("href"),
            base,
        )
        val format = normFormat(o.str("format", "fileType", "file_type", "ext", "extension", "type")) ?: normFormat(acquisition?.str("type"))
        val cover = resolve(
            o.str("cover", "coverUrl", "cover_url", "img", "image", "thumbnail", "pic", "poster")
                ?: o.optJSONArray("images")?.optJSONObject(0)?.str("href")
                ?: imageLink?.str("href"),
            base,
        )

        var chapterList: List<FeedChapter> = emptyList()
        var chaptersUrl: String? = null
        for (key in arrayOf("chapters", "chapterList", "chapter_list", "toc", "catalog", "contents")) {
            when (val v = o.opt(key)) {
                is JSONArray -> if (chapterList.isEmpty()) chapterList = chapters(v, base)
                is String -> if (chaptersUrl == null) chaptersUrl = resolve(v, base)
                else -> Unit
            }
        }
        if (url == null && chapterList.isEmpty() && chaptersUrl == null) return null
        return FeedBook(title, author, cover, url, format, chapterList, chaptersUrl)
    }

    private fun chapters(arr: JSONArray, base: String?): List<FeedChapter> {
        val out = ArrayList<FeedChapter>(arr.length())
        for (i in 0 until arr.length()) {
            when (val e = arr.opt(i)) {
                is String -> resolve(e, base)?.let { out.add(FeedChapter("", it, null)) }
                is JSONObject -> {
                    val url = resolve(e.str("url", "link", "href", "chapterUrl", "chapter_url"), base)
                    val text = e.str("content", "text")
                    if (url != null || text != null) {
                        out.add(FeedChapter(e.str("title", "name", "chapterName", "chapter_title").orEmpty(), url, text))
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun findLink(links: JSONArray?, match: (String) -> Boolean): JSONObject? {
        links ?: return null
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            val rel = when (val r = l.opt("rel")) {
                is String -> r
                is JSONArray -> (0 until r.length()).joinToString(" ") { r.optString(it) }
                else -> ""
            }.lowercase()
            if (match(rel)) return l
        }
        return null
    }

    private fun authorOf(v: Any?): String = when (v) {
        is String -> v.trim()
        is JSONObject -> v.str("name").orEmpty()
        is JSONArray -> (0 until v.length()).map { authorOf(v.opt(it)) }.filter { it.isNotEmpty() }.joinToString("、")
        else -> ""
    }

    /** 只认 txt / epub；"text/plain"、"application/epub+zip"、".epub" 之类的写法也算。 */
    internal fun normFormat(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.removePrefix(".") ?: return null
        return when {
            s == "txt" || s == "text" || s.startsWith("text/plain") -> "txt"
            s == "epub" || s.contains("epub") -> "epub"
            else -> null
        }
    }

    /** 绝对 http(s) 地址原样返回；相对地址按 [base] 解析；其他协议（file: / javascript: / content: …）一律丢弃。 */
    internal fun resolve(raw: String?, base: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val abs = when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            base != null -> runCatching { URL(URL(base), s).toString() }.getOrNull()
            else -> null
        }
        return abs?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return null
    }
}

/** 订阅源的下载与入架：取源文本、下载整本文件或逐章拼成 TXT，再交给 [BookShelf]。 */
object BookFeedImport {
    private const val UA = "Mozilla/5.0 mafari"
    private const val MAX_FEED_BYTES = 8 * 1024 * 1024
    private const val MAX_BOOK_BYTES = 48 * 1024 * 1024
    private const val MAX_CHAPTERS = 5000
    private const val PARALLEL = 4
    /** Legado 书源通常有访问频率限制（引擎里按间隔排队），多开并发没有意义，还容易被站点风控 */
    private const val LEGADO_PARALLEL = 2

    private fun options(maxBytes: Int, readTimeoutMs: Int = 20_000) =
        HttpFetcher.Options(connectTimeoutMs = 10_000, readTimeoutMs = readTimeoutMs, maxBytes = maxBytes, userAgent = UA)

    /** 下载订阅源并解析。阻塞调用，放 IO 线程。 */
    fun loadFromUrl(url: String): BookFeed {
        val u = url.trim()
        require(u.startsWith("http://", true) || u.startsWith("https://", true)) { tr("请输入 http / https 链接") }
        val res = HttpFetcher.get(u, options(MAX_FEED_BYTES))
        // 重定向之后以最终地址为准，相对链接才解析得对
        return BookFeedParser.parse(BookContent.decodeText(res.bytes), res.url)
    }

    /** 读本地订阅源文件并解析。阻塞调用，放 IO 线程。 */
    fun loadFromUri(context: Context, uri: Uri): BookFeed {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FEED_BYTES) throw HttpFetcher.TooLargeException()
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: error(tr("读取文件失败"))
        return BookFeedParser.parse(BookContent.decodeText(bytes), null)
    }

    /**
     * 把订阅源里的一本书下载进书架。[onProgress] 只在按章节拼书时回调（已完成章数, 总章数）。
     * 失败返回带用户可读消息的 [Result.failure]。协程被取消时失败里装的是 CancellationException，调用方要判断并原样抛出。
     */
    suspend fun importBook(context: Context, book: FeedBook, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                BookShelf.load(context)
                // 封面拿不到不影响入架；EPUB 自带封面时源里没写也行
                val cover = book.cover?.let { runCatching { HttpFetcher.get(it, options(2 * 1024 * 1024)).bytes }.getOrNull() }
                when {
                    book.legado != null && book.legadoSource != null -> importLegado(context, book, book.legado, book.legadoSource, cover, onProgress)
                    book.url != null -> importFile(context, book, book.url, cover)
                    else -> importChapters(context, book, cover, onProgress)
                }
            }.recoverCatching {
                if (it is kotlin.coroutines.cancellation.CancellationException) throw it
                throw IllegalStateException(it.message ?: tr("下载失败，检查网络或链接"))
            }
        }

    private fun importFile(context: Context, book: FeedBook, url: String, cover: ByteArray?) {
        val res = HttpFetcher.get(url, options(MAX_BOOK_BYTES, readTimeoutMs = 60_000))
        val ext = pickExt(book.format, res.url, res.contentType, res.bytes)
        if (ext == "txt" && looksLikeHtml(res.bytes)) error(tr("下载到的不是书籍文件"))
        val ok = BookShelf.addBook(context, "${book.title}.$ext", ext, book.title, book.author, cover) { it.writeBytes(res.bytes) }
        if (!ok) error(tr("仅支持 TXT / EPUB"))
    }

    private suspend fun importChapters(context: Context, book: FeedBook, cover: ByteArray?, onProgress: (Int, Int) -> Unit) {
        val list = (
            book.chapters.ifEmpty {
                val u = book.chaptersUrl ?: error(tr("这本书没有可下载的内容"))
                BookFeedParser.parseChapterList(BookContent.decodeText(HttpFetcher.get(u, options(MAX_FEED_BYTES)).bytes), u)
            }
            ).take(MAX_CHAPTERS)
        if (list.isEmpty()) error(tr("这本书没有可下载的内容"))

        val done = AtomicInteger(0)
        val gate = Semaphore(PARALLEL)
        val bodies: List<String?> = coroutineScope {
            list.map { ch ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val body = ch.text ?: ch.url?.let { fetchChapter(it) }
                        onProgress(done.incrementAndGet(), list.size)
                        body
                    }
                }
            }.awaitAll()
        }
        if (bodies.all { it == null }) error(tr("章节下载失败，检查网络或链接"))

        val txt = buildTxt(list.map { it.title }, bodies)
        val ok = BookShelf.addBook(context, "${book.title}.txt", "txt", book.title, book.author, cover) { it.writeText(txt) }
        if (!ok) error(tr("仅支持 TXT / EPUB"))
    }

    /**
     * Legado 书源的一本书：读详情页（补全书名 / 封面 / 目录地址）→ 读目录 → 逐章取正文 → 拼成 TXT 入架。
     * 书源带访问频率限制（concurrentRate）时由引擎按间隔排队，所以大部头会比较慢；取消后已读的章节不入架。
     */
    private suspend fun importLegado(
        context: Context,
        book: FeedBook,
        ref: LegadoBook,
        source: LegadoSource,
        cover: ByteArray?,
        onProgress: (Int, Int) -> Unit,
    ) {
        val engine = source.engine
        val chapters = engine.loadInfo(ref).let { engine.loadChapters(it) }.take(MAX_CHAPTERS)
        // 详情页补全后的封面（列表里没有封面时才用）
        val coverBytes = cover ?: ref.coverUrl?.let { runCatching { HttpFetcher.get(it, options(2 * 1024 * 1024)).bytes }.getOrNull() }

        val tocUrls = chapters.mapTo(HashSet()) { it.url }
        val done = AtomicInteger(0)
        val gate = Semaphore(LEGADO_PARALLEL)
        val bodies: List<String?> = coroutineScope {
            chapters.mapIndexed { i, ch ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        ensureActive()
                        val next = chapters.getOrNull(i + 1)?.url
                        var body: String? = null
                        repeat(2) { attempt ->
                            if (body == null) {
                                body = runCatching { engine.loadContent(ref, ch, next, tocUrls) }.getOrNull()
                                if (body == null && attempt == 0) ensureActive()
                            }
                        }
                        onProgress(done.incrementAndGet(), chapters.size)
                        body
                    }
                }
            }.awaitAll()
        }
        if (bodies.all { it == null }) error(tr("章节下载失败，检查网络或书源是否失效"))

        val title = ref.name.ifBlank { book.title }
        val author = ref.author.ifBlank { book.author }
        val txt = buildTxt(chapters.map { it.title }, bodies)
        val ok = BookShelf.addBook(context, "$title.txt", "txt", title, author, coverBytes) { it.writeText(txt) }
        if (!ok) error(tr("仅支持 TXT / EPUB"))
    }

    /** 一章正文；失败重试一次，仍失败返回 null（由 [buildTxt] 填占位文字）。 */
    private fun fetchChapter(url: String): String? {
        repeat(2) {
            val t = runCatching { BookContent.decodeText(HttpFetcher.get(url, options(2 * 1024 * 1024, readTimeoutMs = 15_000)).bytes) }.getOrNull()
            if (t != null) return t
        }
        return null
    }

    /**
     * 把各章拼成一个 TXT。章节标题行以 [BookContent.TITLE_MARK] 开头，阅读器据此切章，
     * 不依赖标题长得像「第 N 章」；标题为空的章按序号补。正文是 HTML 的先转成段落。
     */
    internal fun buildTxt(titles: List<String>, bodies: List<String?>): String {
        val sb = StringBuilder()
        for (i in bodies.indices) {
            val title = titles.getOrNull(i).orEmpty().ifBlank { tr("第 {0} 章", i + 1) }
            sb.append(BookContent.TITLE_MARK).append(title).append('\n')
            val raw = bodies[i]
            sb.append(if (raw == null) tr("（本章下载失败）") else chapterText(raw)).append("\n\n")
        }
        return sb.toString()
    }

    private val HTML_HINT = Regex("<(?:p|br|div)\\b", RegexOption.IGNORE_CASE)

    internal fun chapterText(raw: String): String =
        if (HTML_HINT.containsMatchIn(raw)) BookContent.htmlToParagraphs(raw).joinToString("\n") else raw.trim()

    /** 先看内容（zip 魔数 = EPUB），再看源里写的格式、URL 后缀、Content-Type，都没有就当 TXT。 */
    internal fun pickExt(format: String?, url: String, contentType: String, bytes: ByteArray): String {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) return "epub"
        BookFeedParser.normFormat(format)?.let { return it }
        val path = runCatching { URL(url).path }.getOrDefault("")
        BookFeedParser.normFormat(path.substringAfterLast('.', ""))?.let { return it }
        BookFeedParser.normFormat(contentType)?.let { return it }
        return "txt"
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }
}
