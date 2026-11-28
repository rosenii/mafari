/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.dsh.miuixbrowser.books.data.legado.LegadoBook
import dev.dsh.miuixbrowser.books.data.legado.LegadoChapter
import dev.dsh.miuixbrowser.books.data.legado.LegadoSourceStore
import dev.dsh.miuixbrowser.i18n.tr
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** 章节里的一块内容：一段文字，或 EPUB 里的一张图（[path] 是 EPUB 压缩包内的条目名）。 */
sealed interface Block {
    class Text(val text: String) : Block
    class Image(val path: String) : Block
}

/**
 * 一章。[text] 是章内全部文字，已经整理好：段落用 \n 分隔，每段以两个全角空格开头（首行缩进）。
 * [blocks] 是按出现顺序排好的文字块 / 图片块；纯文字的章节只有一个文字块（TXT 就是这样）。
 */
class Chapter(
    val title: String,
    val text: String,
    val blocks: List<Block> = listOf(Block.Text(text)),
    /** 远端书里的章节下标；本地书为 -1 */
    val index: Int = -1,
    /** 远端章节还没下载（[text] 是占位提示）；读完缓存后由阅读器替换 */
    val pending: Boolean = false,
) {
    val hasImages: Boolean get() = blocks.any { it is Block.Image }
}

/**
 * 把书架里的 TXT / EPUB 读成章节列表（给阅读器用）。
 *
 * - TXT：自动识别 UTF-8 / UTF-16 / GB18030；按「第X章 / 序章 / Chapter N …」标题行分章，识别不出来就每约 4000 字切一节；
 *   单章超过 [LONG_CHAPTER] 字的再切成几段，避免一次排版过长的文本。
 * - EPUB：按 spine 顺序读每个 XHTML，章名取自目录（nav / ncx），没有就取正文里的标题；
 *   没有章名也没有标题的文件并入上一章（很多 EPUB 把一章拆成了几个文件）。
 *   页面里的图片（`<img>` / SVG `<image>`）按出现顺序保留为图片块：只有图片的页面（封面、插图页、漫画）整页显示；
 *   正文里夹的图只保留较大的（≥ [INLINE_IMAGE_MIN]），避免分隔线、小图标变成整页空白。
 */
object BookContent {
    private const val INDENT = "\u3000\u3000"
    private const val CHUNK = 4000
    private const val LONG_CHAPTER = 20000
    private const val LONG_PART = 10000

    /** 页面里有文字时，图片至少这么大（压缩包内字节数）才算插图；纯图片页不受此限制。 */
    internal const val INLINE_IMAGE_MIN = 16L * 1024
    private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024

    /** 在 IO 线程调用；读不出任何内容时返回空列表。远端书只读目录，正文按章取（见 [loadRemoteChapter]）。 */
    fun load(context: Context, book: Book): List<Chapter> {
        if (book.remote) return loadRemote(context, book)
        val file = BookShelf.bookFile(context, book)
        if (!file.isFile) return emptyList()
        val chapters = if (file.extension.equals("epub", ignoreCase = true)) parseEpub(file) else parseTxt(decodeText(file.readBytes()))
        return chapters.flatMap { splitLong(it) }
    }

    /** 远端书打开时预读的章节范围：当前章前后各这么多章（再远就翻到时再下）。 */
    private const val REMOTE_PREFETCH = 2

    /**
     * 远端书：目录立刻返回，当前章附近的已缓存章节带上正文，其余是占位章节。
     * 阅读器滑到附近时再调 [loadRemoteChapter] 下载并替换，不用等整本下完。
     */
    private fun loadRemote(context: Context, book: Book): List<Chapter> {
        val meta = RemoteBooks.read(context, book.id) ?: return emptyList()
        val current = book.chapter.coerceIn(0, meta.chapters.lastIndex)
        return meta.chapters.mapIndexed { i, c ->
            val cached = if (kotlin.math.abs(i - current) <= REMOTE_PREFETCH) RemoteBooks.cached(context, book.id, i) else null
            if (cached != null) {
                Chapter(c.title, cached, index = i)
            } else {
                Chapter(c.title, tr("正在下载本章…"), index = i, pending = true)
            }
        }
    }

    /** 下载远端书的一章并缓存；目录变了 / 书源被删 / 网络失败时抛异常（消息可直接给用户看）。 */
    fun loadRemoteChapter(context: Context, book: Book, index: Int): Chapter {
        val meta = RemoteBooks.read(context, book.id) ?: error(tr("这本书的目录丢了，请重新从书源导入"))
        val c = meta.chapters.getOrNull(index) ?: error(tr("章节不存在"))
        RemoteBooks.cached(context, book.id, index)?.let { return Chapter(c.title, it, index = index) }
        val source = LegadoSourceStore.find(meta.sourceUrl)
            ?: error(tr("书源已被删除，无法继续下载；可以重新导入书源后再试"))
        val legadoBook = LegadoBook(
            name = meta.name,
            author = meta.author,
            bookUrl = meta.bookUrl,
            coverUrl = meta.coverUrl,
            intro = meta.intro,
            kind = meta.kind,
        ).also { it.tocUrl = meta.tocUrl }
        val tocUrls = meta.chapters.mapTo(HashSet()) { it.url }
        val raw = source.engine.loadContent(
            legadoBook,
            LegadoChapter(c.title, c.url),
            meta.chapters.getOrNull(index + 1)?.url,
            tocUrls,
        )
        val text = remoteReaderText(raw)
        RemoteBooks.save(context, book.id, index, text)
        return Chapter(c.title, text, index = index)
    }

    private val REMOTE_HTML = Regex("(<(?:p|br|div|span|img|h[1-6]|li|tr|blockquote|section)\b|&(?:amp;)?lt;(?:p|br|div|span|img|h[1-6]|li|tr|blockquote|section)\b)", RegexOption.IGNORE_CASE)

    /** 书源正文（HTML 或纯文本）→ 阅读器用的段落文本（首行缩进）。 */
    private fun remoteReaderText(raw: String): String {
        val paras = if (REMOTE_HTML.containsMatchIn(raw)) {
            htmlToParagraphs(raw)
        } else {
            raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
                .map { it.trim() }.filter { it.isNotEmpty() }
        }
        val clean = TextCleaner.paragraphs(paras)
        return clean.joinToString("\n") { INDENT + it }.ifBlank { tr("（本章没有内容）") }
    }

    // ---------------------------------------------------------------- TXT

    internal fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, start, bytes.size - start, charset("GB18030"))
        }
    }

    private val TXT_CHAPTER = Regex(
        "^(?:第[0-9零一二三四五六七八九十百千万两〇]{1,9}[章节回卷集部篇话].{0,30}" +
            "|(?:序章|序言|序|楔子|引子|前言|后记|尾声|终章|番外.{0,20}|完本感言|大结局)" +
            "|(?:Chapter|CHAPTER)\\s*[0-9IVXLivxl]+.{0,30})$",
    )

    /** 订阅源按章拼出的 TXT 里，章节标题行以它开头（不可见字符）：阅读器据此切章，不依赖标题像不像「第 N 章」。 */
    const val TITLE_MARK = '\u2063'

    private fun isTitleLine(line: String): Boolean =
        line.length <= 40 && TXT_CHAPTER.matches(line) && !line.endsWith("。") && !line.endsWith("，")

    internal fun parseTxt(raw: String): List<Chapter> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val chapters = ArrayList<Chapter>()
        var title: String? = null
        val body = ArrayList<String>()
        val all = ArrayList<String>()

        fun flush() {
            if (body.isEmpty()) return
            chapters.add(Chapter(title ?: tr("开篇"), body.joinToString("\n")))
            body.clear()
        }

        for (rawLine in lines) {
            val line = normalizeTxtLine(rawLine.trim { it.isWhitespace() || it == '\uFEFF' })
            if (line.isEmpty()) continue
            if (line[0] == TITLE_MARK) {
                flush()
                title = line.substring(1).trim().ifEmpty { null }
            } else if (isTitleLine(line)) {
                flush()
                title = line
            } else {
                val clean = TextCleaner.line(line) ?: continue
                val p = INDENT + clean
                body.add(p)
                all.add(p)
            }
        }
        flush()
        // 识别不出章节（或只有一章）：按字数切成「第 N 节」
        if (chapters.size < 2) return chunk(all)
        return chapters
    }

    private val TXT_HTML = Regex("(<(?:br|p|div|span|h[1-6]|li|blockquote)\\b|&(?:amp;)?lt;(?:br|p|div|span|h[1-6]|li|blockquote)\\b)", RegexOption.IGNORE_CASE)

    /** 老书 / 手工 TXT 里可能直接带着 `<br>` / `&lt;br&gt;`：按段落拆开再用空格接回同一行。 */
    private fun normalizeTxtLine(line: String): String =
        if (!TXT_HTML.containsMatchIn(line)) line else htmlToParagraphs(line).joinToString(" ")

    private fun chunk(paragraphs: List<String>): List<Chapter> {
        val out = ArrayList<Chapter>()
        val cur = ArrayList<String>()
        var len = 0
        fun flush() {
            if (cur.isEmpty()) return
            out.add(Chapter(tr("第 {0} 节", out.size + 1), cur.joinToString("\n")))
            cur.clear()
            len = 0
        }
        for (p in paragraphs) {
            cur.add(p)
            len += p.length
            if (len >= CHUNK) flush()
        }
        flush()
        return out
    }

    /** 超长章节按段落切成几段：第一段保留原章名，后面的加「（2）」「（3）」。 */
    internal fun splitLong(chapter: Chapter): List<Chapter> {
        if (chapter.text.length <= LONG_CHAPTER || chapter.hasImages) return listOf(chapter)
        val out = ArrayList<Chapter>()
        val cur = ArrayList<String>()
        var len = 0
        fun flush() {
            if (cur.isEmpty()) return
            val n = out.size + 1
            out.add(Chapter(if (n == 1) chapter.title else chapter.title + "（$n）", cur.joinToString("\n")))
            cur.clear()
            len = 0
        }
        for (p in chapter.text.split('\n')) {
            cur.add(p)
            len += p.length
            if (len >= LONG_PART) flush()
        }
        flush()
        return out
    }

    // ---------------------------------------------------------------- EPUB

    private fun parseEpub(file: File): List<Chapter> = runCatching {
        ZipFile(file).use { zip ->
            fun text(name: String): String? {
                val e = zip.getEntry(name) ?: zip.getEntry(urlDecode(name)) ?: return null
                return zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
            }

            val container = text("META-INF/container.xml") ?: return emptyList()
            val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1) ?: return emptyList()
            val opf = text(opfPath) ?: return emptyList()

            val manifest = parseManifest(opf)
            val spine = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
                .mapNotNull { attr(it.value, "idref") }
                .mapNotNull { manifest[it] }
                .map { BookShelf.resolveEpubPath(opfPath, it.href) }
                .toList()

            // 目录：EPUB3 的 nav，或 EPUB2 的 ncx
            val titles = HashMap<String, String>()
            manifest.values.firstOrNull { it.properties.contains("nav") }?.let { nav ->
                val p = BookShelf.resolveEpubPath(opfPath, nav.href)
                text(p)?.let { titles.putAll(parseNavTitles(it, p)) }
            }
            if (titles.isEmpty()) {
                manifest.values.firstOrNull { it.mediaType.contains("dtbncx") }?.let { ncx ->
                    val p = BookShelf.resolveEpubPath(opfPath, ncx.href)
                    text(p)?.let { titles.putAll(parseNcxTitles(it, p)) }
                }
            }

            fun lookupImage(name: String): Pair<String, Long>? =
                (zip.getEntry(name) ?: zip.getEntry(urlDecode(name)))?.let { e ->
                    e.name to (if (e.size >= 0) e.size else e.compressedSize)
                }

            val out = ArrayList<ChapterBuilder>()
            for (path in spine) {
                val html = text(path) ?: continue
                val heading = firstHeading(html)
                val title = titles[urlDecode(path)] ?: heading
                var pieces = htmlPieces(html, path, ::lookupImage)
                // 正文第一段就是章名：去掉，免得阅读器里重复
                val first = pieces.firstOrNull()
                if (title != null && first is Piece.Paras && first.lines.firstOrNull()?.trim { it.isWhitespace() } == title) {
                    val rest = first.lines.drop(1)
                    pieces = (if (rest.isEmpty()) emptyList() else listOf(Piece.Paras(rest))) + pieces.drop(1)
                }
                if (pieces.isEmpty()) continue
                val blocks = pieces.map { piece ->
                    when (piece) {
                        is Piece.Paras -> Block.Text(piece.lines.joinToString("\n") { line -> INDENT + line })
                        is Piece.Img -> Block.Image(piece.path)
                    }
                }
                if (title == null && out.isNotEmpty()) {
                    // 既没有目录项也没有标题：并入上一章
                    out.last().append(blocks)
                } else {
                    out.add(ChapterBuilder(title ?: tr("第 {0} 节", out.size + 1), blocks.toMutableList()))
                }
            }
            out.map { it.build() }
        }
    }.getOrDefault(emptyList())

    private class ChapterBuilder(val title: String, val blocks: MutableList<Block>) {
        fun append(more: List<Block>) {
            for (b in more) {
                val last = blocks.lastOrNull()
                if (b is Block.Text && last is Block.Text) blocks[blocks.lastIndex] = Block.Text(last.text + "\n" + b.text)
                else blocks.add(b)
            }
        }

        fun build() = Chapter(
            title,
            blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text },
            blocks.toList(),
        )
    }

    /** XHTML 拆出来的一段：若干段文字（未缩进），或一张图（[Img.path] 是压缩包内真实存在的条目名）。 */
    internal sealed interface Piece {
        class Paras(val lines: List<String>) : Piece
        class Img(val path: String) : Piece
    }

    private val IMG_TAG = Regex("<(?:img|image)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("(?<![\\w-])src\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val IMG_HREF = Regex("(?<![\\w-])(?:xlink:)?href\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    /**
     * XHTML → 按顺序排好的文字段 / 图片。[docPath] 是这个 XHTML 在压缩包里的路径（用来解析图片的相对路径）；
     * [lookup] 按路径查压缩包条目，返回（真实条目名，字节数），不存在返回 null。
     * 没有文字的页面保留所有图；有文字的页面只保留 ≥ [INLINE_IMAGE_MIN] 的图。
     */
    internal fun htmlPieces(html: String, docPath: String, lookup: (String) -> Pair<String, Long>?): List<Piece> {
        val cleaned = Regex("<(script|style|head)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE).replace(html, "")
        val hasText = htmlToParagraphs(cleaned).isNotEmpty()

        val kept = ArrayList<Pair<IntRange, String>>()
        for (m in IMG_TAG.findAll(cleaned)) {
            val raw = IMG_SRC.find(m.value)?.groupValues?.get(1) ?: IMG_HREF.find(m.value)?.groupValues?.get(1) ?: continue
            val ref = BookShelf.decodeEntities(raw).substringBefore('#').substringBefore('?').trim()
            if (ref.isEmpty() || ref.startsWith("data:", ignoreCase = true) || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(ref)) continue
            val (entry, size) = lookup(BookShelf.resolveEpubPath(docPath, ref)) ?: continue
            if (size <= 0 || size > MAX_IMAGE_BYTES) continue
            if (hasText && size < INLINE_IMAGE_MIN) continue
            kept.add(m.range to entry)
        }

        val out = ArrayList<Piece>()
        var last = 0
        for ((range, entry) in kept) {
            val lines = htmlToParagraphs(cleaned.substring(last, range.first))
            if (lines.isNotEmpty()) out.add(Piece.Paras(lines))
            out.add(Piece.Img(entry))
            last = range.last + 1
        }
        val tail = htmlToParagraphs(cleaned.substring(last))
        if (tail.isNotEmpty()) out.add(Piece.Paras(tail))
        return out
    }

    /**
     * 读 EPUB 里的一张图，解码到刚好够 [targetW] × [targetH] 的清晰度（不会整张大图解满）。在 IO 线程调用；
     * 条目不存在 / 格式不支持（如 SVG）/ 解码失败返回 null。
     */
    fun loadImage(file: File, entry: String, targetW: Int, targetH: Int): Bitmap? = runCatching {
        val bytes = ZipFile(file).use { zip ->
            val e = zip.getEntry(entry) ?: return null
            if (e.size > MAX_IMAGE_BYTES) return null
            zip.getInputStream(e).use { it.readBytes() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    internal class ManifestItem(val href: String, val mediaType: String, val properties: List<String>)

    internal fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    internal fun parseManifest(opf: String): Map<String, ManifestItem> {
        val map = LinkedHashMap<String, ManifestItem>()
        Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            map[id] = ManifestItem(
                href = BookShelf.decodeEntities(href),
                mediaType = attr(tag, "media-type").orEmpty(),
                properties = attr(tag, "properties").orEmpty().split(' ').filter { it.isNotEmpty() },
            )
        }
        return map
    }

    private fun stripFragment(href: String) = href.substringBefore('#')

    internal fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun plain(html: String): String =
        BookShelf.decodeEntities(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    /** EPUB3 nav 文档：href（相对 nav 文件，去掉 # 片段，解码后）→ 章名；同一文件多个锚点取第一个。 */
    internal fun parseNavTitles(nav: String, navPath: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).findAll(nav).forEach { m ->
            val key = urlDecode(BookShelf.resolveEpubPath(navPath, stripFragment(BookShelf.decodeEntities(m.groupValues[1]))))
            val t = plain(m.groupValues[2])
            if (t.isNotEmpty() && key !in out) out[key] = t
        }
        return out
    }

    /** EPUB2 ncx：每个 navPoint 的第一个 text / content。 */
    internal fun parseNcxTitles(ncx: String, ncxPath: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex(
            "<navPoint\\b[\\s\\S]*?<text>([\\s\\S]*?)</text>[\\s\\S]*?<content\\b[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE,
        ).findAll(ncx).forEach { m ->
            val key = urlDecode(BookShelf.resolveEpubPath(ncxPath, stripFragment(BookShelf.decodeEntities(m.groupValues[2]))))
            val t = plain(m.groupValues[1])
            if (t.isNotEmpty() && key !in out) out[key] = t
        }
        return out
    }

    internal fun firstHeading(html: String): String? {
        val body = html.substringAfter("<body", html)
        return Regex("<h[1-3]\\b[^>]*>([\\s\\S]*?)</h[1-3]>", RegexOption.IGNORE_CASE).find(body)
            ?.let { plain(it.groupValues[1]) }?.takeIf { it.isNotEmpty() }
    }

    /**
     * XHTML → 段落（已去空行，未加缩进）。
     * 书源正文经常把 `<br>` / `<p>` 转义成 `&lt;br&gt;` / `&lt;p&gt;`，或再套一层 `&amp;lt;`；
     * 所以先把转义标签还原成换行 / 去掉，再处理真正的标签，最后才解码普通实体。
     */
    internal fun htmlToParagraphs(html: String): List<String> {
        var s = html
        // 双重转义（&amp;lt;br&amp;gt;）先还原一层
        if (s.contains("&amp;lt;", true) || s.contains("&amp;gt;", true)) s = BookShelf.decodeEntities(s)
        // 转义的换行 / 块级标签
        s = Regex("&lt;br\\s*/?&gt;", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("&lt;/?(?:p|div|h[1-6]|li|tr|blockquote|section)\\b[^&]*?&gt;", RegexOption.IGNORE_CASE).replace(s, "\n")
        // 其余转义标签（如 &lt;span ...&gt;）直接去掉
        s = Regex("&lt;/?[a-zA-Z][^&]*?&gt;", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("<(script|style|head)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("<br\\s*/?>|</(?:p|div|h[1-6]|li|tr|blockquote|section)>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = BookShelf.decodeEntities(s.replace("&nbsp;", " "))
        // 解码后又出现的 <br>（例如 &amp;lt;br&amp;gt; 只解了一层）再兜底
        s = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        return TextCleaner.paragraphs(
            s.split('\n').map { it.trim { c -> c.isWhitespace() || c == '\uFEFF' } }.filter { it.isNotEmpty() },
        )
    }
}
