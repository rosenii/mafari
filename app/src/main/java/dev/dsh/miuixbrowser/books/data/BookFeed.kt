/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data

import android.content.Context
import android.net.Uri
import dev.dsh.miuixbrowser.books.data.legado.LegadoBook
import dev.dsh.miuixbrowser.books.data.legado.LegadoRssArticle
import dev.dsh.miuixbrowser.books.data.legado.LegadoRssSource
import dev.dsh.miuixbrowser.books.data.legado.LegadoRssSourceParser
import dev.dsh.miuixbrowser.books.data.legado.LegadoSource
import dev.dsh.miuixbrowser.books.data.legado.LegadoSourceParser
import dev.dsh.miuixbrowser.books.data.legado.LegadoSourceStore
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
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** 订阅源里的一章：[url] 指向正文，或直接内嵌 [text]。 */
class FeedChapter(val title: String, val url: String?, val text: String?)

/** 从链接或本地文件读到的一份订阅源：[feed] 是解析结果，[raw] 是原文（用于保存订阅），[baseUrl] 是相对链接的基准地址。 */
class LoadedFeed(val feed: BookFeed, val raw: String, val baseUrl: String?)

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
class BookFeed(
    val name: String,
    val books: List<FeedBook>,
    val sources: List<LegadoSource> = emptyList(),
    /** Legado 的订阅源（RSS / JSON / 网页），和书源分开 */
    val rssSources: List<LegadoRssSource> = emptyList(),
)

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
        val source = text.trim().trimStart('\uFEFF')
        // OPDS 1.0（Atom XML）：book 目录里带 acquisition 链接
        if (source.startsWith("<")) return parseOpds(source, baseUrl)
        val root = runCatching { JSONTokener(source).nextValue() }.getOrNull()
        if (LegadoSourceParser.looksLikeLegado(root)) {
            val sources = LegadoSourceParser.parse(root)
            return BookFeed(sources.singleOrNull()?.name.orEmpty(), emptyList(), sources)
        }
        if (LegadoRssSourceParser.looksLikeLegado(root)) {
            val rss = LegadoRssSourceParser.parse(root)
            return BookFeed(rss.singleOrNull()?.name.orEmpty(), emptyList(), emptyList(), rss)
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

    /**
     * OPDS 1.0（Atom）目录：`<feed><entry>` 里的 `title` / `author` / 带 `acquisition` 的 `link`。
     * 只认能下载的条目（没有 acquisition 链接的导航页 / 详情页跳过）；封面取 image / thumbnail 链接。
     */
    private fun parseOpds(text: String, baseUrl: String?): BookFeed {
        val doc = runCatching { Jsoup.parse(text, baseUrl.orEmpty(), Parser.xmlParser()) }
            .getOrElse { throw IllegalArgumentException(tr("不是有效的订阅源（OPDS 文件解析失败）")) }
        val name = doc.selectFirst("feed > title")?.text()?.trim().orEmpty()
        val out = ArrayList<FeedBook>()
        for (entry in doc.select("entry")) {
            val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) continue
            val author = entry.selectFirst("author > name")?.text()?.trim()
                ?: entry.selectFirst("author")?.text()?.trim().orEmpty()
            val cover = entry.select("link").firstNotNullOfOrNull { l ->
                val rel = l.attr("rel").lowercase()
                if (rel.contains("image") || rel.contains("thumbnail") || rel.contains("cover")) resolve(l.attr("href"), baseUrl) else null
            }
            val acquisition = entry.select("link").firstOrNull { l ->
                l.attr("rel").lowercase().contains("acquisition")
            } ?: continue
            val href = resolve(acquisition.attr("href"), baseUrl) ?: continue
            val format = normFormat(acquisition.attr("type")).let { it ?: formatFromPath(href) }
            out.add(FeedBook(title, author, cover, href, format, emptyList(), null))
        }
        require(out.isNotEmpty()) { tr("订阅源里没有可导入的书（OPDS 目录里没有带下载链接的条目）") }
        return BookFeed(name, out)
    }

    private fun formatFromPath(url: String): String? {
        val path = runCatching { URL(url).path }.getOrDefault(url)
        return normFormat(path.substringAfterLast('.', ""))
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
    /** 订阅源 / 书源文件的上限：Legado 书源合集动辄十几 MB（几千个书源），8 MiB 会误报「文件过大」 */
    private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    private const val MAX_BOOK_BYTES = 48 * 1024 * 1024
    private const val MAX_CHAPTERS = 5000
    private const val PARALLEL = 4
    /** Legado 书源通常有访问频率限制（引擎里按间隔排队），多开并发没有意义，还容易被站点风控 */
    private const val LEGADO_PARALLEL = 2

    private fun options(maxBytes: Int, readTimeoutMs: Int = 20_000) =
        HttpFetcher.Options(connectTimeoutMs = 10_000, readTimeoutMs = readTimeoutMs, maxBytes = maxBytes, userAgent = UA)

    /** 下载订阅源并解析。阻塞调用，放 IO 线程。 */
    fun loadFromUrl(url: String): LoadedFeed {
        val u = url.trim()
        require(u.startsWith("http://", true) || u.startsWith("https://", true)) { tr("请输入 http / https 链接") }
        val res = HttpFetcher.get(u, options(MAX_SOURCE_BYTES, readTimeoutMs = 60_000))
        val text = BookContent.decodeText(res.bytes)
        // 重定向之后以最终地址为准，相对链接才解析得对
        return LoadedFeed(BookFeedParser.parse(text, res.url), text, res.url)
    }

    /** 读本地订阅源文件并解析。阻塞调用，放 IO 线程。 */
    fun loadFromUri(context: Context, uri: Uri): LoadedFeed {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_SOURCE_BYTES) throw HttpFetcher.TooLargeException()
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: error(tr("读取文件失败"))
        val text = BookContent.decodeText(bytes)
        return LoadedFeed(BookFeedParser.parse(text, null), text, null)
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

    /** 把 Legado 订阅源里的一篇文章导入成一本单章 TXT（文章通常很短，直接取正文即可）。 */
    suspend fun importRssArticle(
        context: Context,
        source: LegadoRssSource,
        article: LegadoRssArticle,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            BookShelf.load(context)
            val cover = article.image?.let { runCatching { HttpFetcher.get(it, options(2 * 1024 * 1024)).bytes }.getOrNull() }
            val raw = source.loadContent(article)
            val txt = buildTxt(listOf(article.title), listOf(raw))
            val ok = BookShelf.addBook(
                context,
                "${article.title}.txt",
                "txt",
                article.title,
                article.author.ifBlank { source.name },
                cover,
            ) { it.writeText(txt) }
            if (!ok) error(tr("仅支持 TXT / EPUB"))
            onProgress(1, 1)
        }.recoverCatching {
            if (it is kotlin.coroutines.cancellation.CancellationException) throw it
            throw IllegalStateException(it.message ?: tr("下载失败，检查网络或订阅源是否失效"))
        }
    }

    /** 书源索引型订阅：文章链接本身是 Legado 书源 JSON，解析后加入书源库。 */
    suspend fun importRssBookSource(context: Context, url: String): Int = withContext(Dispatchers.IO) {
        val res = HttpFetcher.get(url, options(MAX_SOURCE_BYTES, readTimeoutMs = 60_000))
        val root = JSONTokener(BookContent.decodeText(res.bytes)).nextValue()
        if (!LegadoSourceParser.looksLikeLegado(root)) {
            error(tr("这个链接不是书源文件"))
        }
        val list = LegadoSourceParser.parse(root)
        withContext(Dispatchers.Main) { LegadoSourceStore.add(context, list) }
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
     * Legado 书源的一本书：读详情页 → 读目录 → 直接把目录存成「远端书」入架。
     * 正文不在这里下载，阅读器翻到哪章再取哪章（见 BookContent.loadRemoteChapter），
     * 所以大部头也是几秒入架，不用等整本下完。
     */
    private fun importLegado(
        context: Context,
        book: FeedBook,
        ref: LegadoBook,
        source: LegadoSource,
        cover: ByteArray?,
        onProgress: (Int, Int) -> Unit,
    ) {
        val engine = source.engine
        val info = engine.loadInfo(ref)
        val chapters = engine.loadChapters(info).take(MAX_CHAPTERS)
        val coverBytes = cover ?: info.coverUrl?.let { runCatching { HttpFetcher.get(it, options(2 * 1024 * 1024)).bytes }.getOrNull() }

        val meta = RemoteBookMeta(
            sourceUrl = source.url,
            bookUrl = info.bookUrl,
            name = info.name.ifBlank { book.title },
            author = info.author.ifBlank { book.author },
            coverUrl = info.coverUrl,
            intro = info.intro,
            kind = info.kind,
            tocUrl = info.tocUrl,
            chapters = chapters.map { RemoteChapterMeta(it.title, it.url) },
        )
        val ok = BookShelf.addRemoteBook(
            context,
            title = meta.name,
            author = meta.author,
            cover = coverBytes,
            meta = meta,
        )
        if (!ok) error(tr("加入书架失败"))
        onProgress(1, 1)
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

    private val HTML_HINT = Regex("(<(?:p|br|div|span|h[1-6]|li|tr|blockquote|section)\\b|&(?:amp;)?lt;(?:p|br|div|span|h[1-6]|li|tr|blockquote|section)\\b)", RegexOption.IGNORE_CASE)

    internal fun chapterText(raw: String): String =
        if (HTML_HINT.containsMatchIn(raw)) {
            BookContent.htmlToParagraphs(raw).joinToString("\n")
        } else {
            TextCleaner.paragraphs(raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')).joinToString("\n")
        }

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
