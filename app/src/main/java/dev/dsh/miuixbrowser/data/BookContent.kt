/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

import android.content.Context
import dev.dsh.miuixbrowser.i18n.tr
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** 一章。[text] 已经整理好：段落用 \n 分隔，每段以两个全角空格开头（首行缩进）。 */
class Chapter(val title: String, val text: String)

/**
 * 把书架里的 TXT / EPUB 读成章节列表（给阅读器用）。
 *
 * - TXT：自动识别 UTF-8 / UTF-16 / GB18030；按「第X章 / 序章 / Chapter N …」标题行分章，识别不出来就每约 4000 字切一节；
 *   单章超过 [LONG_CHAPTER] 字的再切成几段，避免一次排版过长的文本。
 * - EPUB：按 spine 顺序读每个 XHTML，章名取自目录（nav / ncx），没有就取正文里的标题；
 *   没有章名也没有标题的文件并入上一章（很多 EPUB 把一章拆成了几个文件）。
 */
object BookContent {
    private const val INDENT = "\u3000\u3000"
    private const val CHUNK = 4000
    private const val LONG_CHAPTER = 20000
    private const val LONG_PART = 10000

    /** 在 IO 线程调用；读不出任何内容时返回空列表。 */
    fun load(context: Context, book: Book): List<Chapter> {
        val file = BookShelf.bookFile(context, book)
        if (!file.isFile) return emptyList()
        val chapters = if (file.extension.equals("epub", ignoreCase = true)) parseEpub(file) else parseTxt(decodeText(file.readBytes()))
        return chapters.flatMap { splitLong(it) }
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
            val line = rawLine.trim { it.isWhitespace() || it == '\uFEFF' }
            if (line.isEmpty()) continue
            if (isTitleLine(line)) {
                flush()
                title = line
            } else {
                val p = INDENT + line
                body.add(p)
                all.add(p)
            }
        }
        flush()
        // 识别不出章节（或只有一章）：按字数切成「第 N 节」
        if (chapters.size < 2) return chunk(all)
        return chapters
    }

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
        if (chapter.text.length <= LONG_CHAPTER) return listOf(chapter)
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

            val out = ArrayList<Chapter>()
            val bodies = ArrayList<StringBuilder>()
            for (path in spine) {
                val html = text(path) ?: continue
                val heading = firstHeading(html)
                val tocTitle = titles[urlDecode(path)]
                val title = tocTitle ?: heading
                var paragraphs = htmlToParagraphs(html)
                if (title != null && paragraphs.firstOrNull()?.trim { it.isWhitespace() } == title) paragraphs = paragraphs.drop(1)
                if (paragraphs.isEmpty()) continue
                val bodyText = paragraphs.joinToString("\n") { INDENT + it }
                if (title == null && out.isNotEmpty()) {
                    // 既没有目录项也没有标题：并入上一章
                    bodies[bodies.size - 1].append('\n').append(bodyText)
                } else {
                    out.add(Chapter(title ?: tr("第 {0} 节", out.size + 1), ""))
                    bodies.add(StringBuilder(bodyText))
                }
            }
            out.indices.map { Chapter(out[it].title, bodies[it].toString()) }
        }
    }.getOrDefault(emptyList())

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

    /** XHTML → 段落（已去空行，未加缩进）。 */
    internal fun htmlToParagraphs(html: String): List<String> {
        var s = Regex("<(script|style|head)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE).replace(html, "")
        s = Regex("<br\\s*/?>|</(?:p|div|h[1-6]|li|tr|blockquote|section)>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = BookShelf.decodeEntities(s.replace("&nbsp;", " "))
        return s.split('\n').map { it.trim { c -> c.isWhitespace() || c == '\uFEFF' } }.filter { it.isNotEmpty() }
    }
}
