/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data.legado

import android.util.Log
import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/** 一次请求的完整描述（地址规则求值、拆出选项之后）。 */
internal class LegadoUrl(
    val url: String,
    val method: String,
    val body: String?,
    val headers: Map<String, String>,
    val charset: String?,
    val retry: Int,
)

/**
 * 一份 Legado 书源的执行引擎：搜索、发现、详情、目录、正文。全部是阻塞调用，放 IO 线程。
 * 同一份书源共用一个实例（见 [LegadoSource.engine]）；Cookie、`source.put` 变量、访问频率限制都在这里，可多线程并发调用。
 */
class LegadoEngine(val source: LegadoSource) {
    internal val jar = LegadoCookieJar()
    internal val store = ConcurrentHashMap<String, String>()
    internal val js = LegadoJs(this)
    private val http = LegadoHttp(jar, parseHeaders(source.header), source.concurrentRate)

    @Volatile private var kindsCache: List<LegadoExploreKind>? = null

    internal fun tmpDir(): File = (cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")).also { it.mkdirs() }

    internal fun log(msg: String) {
        runCatching { Log.d("LegadoEngine", "[${source.name}] $msg") }
    }

    /** 书源脚本里 `java.ajax / get / post / downloadFile` 走的请求：带书源请求头和 Cookie。 */
    internal fun fetchForScript(url: String, body: String?, headers: Map<String, String> = emptyMap(), method: String = "GET"): LegadoResponse {
        val u = analyzeUrl(url, null, null, source.url)
        return http.fetch(
            LegadoRequest(
                url = u.url,
                method = if (body != null) "POST" else if (method != "GET") method else u.method,
                body = body ?: u.body,
                headers = u.headers + headers,
                charset = u.charset,
                retry = u.retry,
            ),
        )
    }

    // ------------------------------------------------------------------ 搜索 / 发现

    /** 搜索。[key] 是用户输入的关键字，[page] 从 1 开始。 */
    fun search(key: String, page: Int = 1): List<LegadoBook> {
        val tpl = source.searchUrl ?: throw LegadoException(tr("这个书源没有搜索地址"))
        if (source.search["bookList"] == null) throw LegadoException(tr("这个书源没有搜索规则"))
        val u = analyzeUrl(tpl, key, page, source.url, mapOf("key" to key))
        if (u.url.isBlank()) throw LegadoException(tr("书源没有返回搜索地址（脚本可能出错，例如验证码识别失败），请重试"))
        val res = request(u)
        return parseBooks(res, source.search, mapOf("key" to key, "page" to page))
    }

    /** 「发现」入口列表（分类 / 榜单）。 */
    fun exploreKinds(): List<LegadoExploreKind> {
        kindsCache?.let { return it }
        val raw = source.exploreUrl?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val text = if (raw.startsWith("@js:", true) || raw.contains("<js>", true)) {
            LegadoAnalyzer(this, source.url).renderTemplate(raw, emptyMap())
        } else raw
        val out = ArrayList<LegadoExploreKind>()
        val t = text.trim()
        if (t.startsWith("[")) {
            runCatching {
                val arr = JSONTokener(t).nextValue() as JSONArray
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    val url = o.optString("url").trim()
                    if (title.isNotEmpty() && url.isNotEmpty()) out.add(LegadoExploreKind(title, url))
                }
            }
        } else {
            for (line in t.split("&&", "\n")) {
                val sep = line.indexOf("::")
                if (sep <= 0) continue
                val title = line.substring(0, sep).trim()
                val url = line.substring(sep + 2).trim()
                if (title.isNotEmpty() && url.isNotEmpty()) out.add(LegadoExploreKind(title, url))
            }
        }
        kindsCache = out
        return out
    }

    fun explore(kind: LegadoExploreKind, page: Int = 1): List<LegadoBook> {
        val rules = if (source.explore["bookList"] != null) source.explore else source.search
        if (rules["bookList"] == null) throw LegadoException(tr("这个书源没有发现规则"))
        val u = analyzeUrl(kind.url, null, page, source.url)
        val res = request(u)
        return parseBooks(res, rules, mapOf("page" to page))
    }

    private fun parseBooks(res: LegadoResponse, rules: LegadoRules, vars: Map<String, Any?>): List<LegadoBook> {
        val a = LegadoAnalyzer(this, res.finalUrl)
        val body = res.body()
        val items = a.getElements(rules["bookList"], body, vars)
        val seen = HashSet<String>()
        val out = ArrayList<LegadoBook>()
        for (item in items) {
            val name = clean(a.getString(rules["name"], item, false, vars))
            if (name.isEmpty()) continue
            var bookUrl = a.getString(rules["bookUrl"], item, true, vars)
            if (bookUrl.isEmpty()) bookUrl = res.finalUrl
            if (!seen.add(bookUrl)) continue
            out.add(
                LegadoBook(
                    name = name,
                    author = clean(a.getString(rules["author"], item, false, vars)),
                    bookUrl = bookUrl,
                    coverUrl = a.getString(rules["coverUrl"], item, true, vars).ifEmpty { null },
                    intro = a.getString(rules["intro"], item, false, vars).ifEmpty { null },
                    kind = a.getStringList(rules["kind"], item, false, vars).joinToString(",").ifEmpty { null },
                    lastChapter = clean(a.getString(rules["lastChapter"], item, false, vars)).ifEmpty { null },
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------------ 详情 / 目录 / 正文

    /** 读详情页，补全书名、作者、封面、简介、目录地址。详情页读不到规则时保持列表里的值。 */
    fun loadInfo(book: LegadoBook): LegadoBook {
        val rules = source.bookInfo
        val res = request(analyzeUrl(book.bookUrl, null, null, source.url))
        val body = res.body()
        book.infoBody = body
        book.infoUrl = res.finalUrl
        val a = LegadoAnalyzer(this, res.finalUrl)
        val vars = mapOf<String, Any?>("book" to book)
        var content: Any? = body
        rules["init"]?.let { r -> a.getElements(r, body, vars).firstOrNull()?.let { content = it } }

        a.getString(rules["name"], content, false, vars).let { clean(it) }.takeIf { it.isNotEmpty() }?.let { book.name = it }
        a.getString(rules["author"], content, false, vars).let { clean(it) }.takeIf { it.isNotEmpty() }?.let { book.author = it }
        a.getString(rules["coverUrl"], content, true, vars).takeIf { it.isNotEmpty() }?.let { book.coverUrl = it }
        a.getString(rules["intro"], content, false, vars).takeIf { it.isNotEmpty() }?.let { book.intro = it }
        a.getStringList(rules["kind"], content, false, vars).joinToString(",").takeIf { it.isNotEmpty() }?.let { book.kind = it }
        a.getString(rules["lastChapter"], content, false, vars).let { clean(it) }.takeIf { it.isNotEmpty() }?.let { book.lastChapter = it }
        book.tocUrl = a.getString(rules["tocUrl"], content, true, vars).ifEmpty { res.finalUrl }
        return book
    }

    /** 目录（会跟随 nextTocUrl 翻页）。需要先 [loadInfo]；没有读过详情页时自动读一次。 */
    fun loadChapters(book: LegadoBook): List<LegadoChapter> {
        val rules = source.toc
        val listRule = rules["chapterList"] ?: throw LegadoException(tr("这个书源没有目录规则"))
        if (book.tocUrl == null) loadInfo(book)
        var url = book.tocUrl ?: book.bookUrl
        var prefetched: String? = if (url == book.infoUrl) book.infoBody else null
        val visited = HashSet<String>()
        val all = ArrayList<LegadoChapter>()
        val vars = mapOf<String, Any?>("book" to book)

        var pages = 0
        while (pages++ < MAX_TOC_PAGES) {
            if (!visited.add(url)) break
            val text: String
            val finalUrl: String
            if (prefetched != null) {
                text = prefetched
                finalUrl = url
                prefetched = null
            } else {
                val res = request(analyzeUrl(url, null, null, source.url))
                text = res.body()
                finalUrl = res.finalUrl
            }
            val a = LegadoAnalyzer(this, finalUrl)
            for (item in a.getElements(listRule, text, vars)) {
                val title = clean(a.getString(rules["chapterName"], item, false, vars))
                if (title.isEmpty()) continue
                val cu = a.getString(rules["chapterUrl"], item, true, vars).ifEmpty { finalUrl }
                all.add(LegadoChapter(title, cu))
            }
            val next = rules["nextTocUrl"]?.let { r -> a.getStringList(r, text, true, vars).firstOrNull { it !in visited } }
            if (next == null) break
            url = next
        }
        if (all.isEmpty()) throw LegadoException(tr("没有读到目录，书源规则可能已失效"))
        val seen = HashSet<String>()
        return all.filter { seen.add(it.url + "\u0000" + it.title) }
    }

    /**
     * 一章正文（HTML 或纯文本，交给 BookFeedImport 转段落）。会跟随 nextContentUrl 拼接章内分页，
     * 但遇到 [nextChapterUrl]、目录里的其他章节或已读过的地址就停，避免串章。
     */
    fun loadContent(book: LegadoBook, chapter: LegadoChapter, nextChapterUrl: String?, tocUrls: Set<String>): String {
        val rules = source.content
        val contentRule = rules["content"] ?: throw LegadoException(tr("这个书源没有正文规则"))
        val vars = mapOf<String, Any?>(
            "book" to book,
            "title" to chapter.title,
            "nextChapterUrl" to (nextChapterUrl ?: ""),
        )
        val sb = StringBuilder()
        val visited = HashSet<String>()
        var url = chapter.url
        var lastAnalyzer: LegadoAnalyzer? = null
        var pages = 0
        while (pages++ < MAX_CONTENT_PAGES) {
            if (!visited.add(url)) break
            val res = request(analyzeUrl(url, null, null, source.url))
            val text = res.body()
            val a = LegadoAnalyzer(this, res.finalUrl)
            lastAnalyzer = a
            val part = a.getString(contentRule, text, false, vars)
            if (part.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(part)
            }
            val nextRule = rules["nextContentUrl"] ?: break
            val next = a.getStringList(nextRule, text, true, vars).firstOrNull() ?: break
            if (next == nextChapterUrl || next in tocUrls || next in visited) break
            url = next
        }
        var out = sb.toString()
        val rep = rules["replaceRegex"]
        if (rep != null && lastAnalyzer != null) {
            out = lastAnalyzer.replaceRegex(out, if (rep.startsWith("##")) rep else "##$rep")
        }
        if (out.isBlank()) throw LegadoException(tr("这一章没有读到正文"))
        return out
    }

    // ------------------------------------------------------------------ 地址

    private fun request(u: LegadoUrl): LegadoResponse {
        val res = http.fetch(LegadoRequest(u.url, u.method, u.body, u.headers, u.charset, u.retry))
        if (res.status >= 400 && res.bytes.isEmpty()) throw IOException(tr("服务器返回 HTTP {0}", res.status))
        return res
    }

    /**
     * 书源里的地址规则 → 请求。顺序：脚本段 / `{{ }}` 求值 → `<第1页,第2页>` 页码选择 → 以 `,{选项}` 切出
     * method / body / headers / charset / retry → 相对地址补全并转义。
     */
    internal fun analyzeUrl(raw: String, key: String?, page: Int?, base: String, extra: Map<String, Any?> = emptyMap()): LegadoUrl {
        val vars = HashMap<String, Any?>(extra)
        if (key != null) vars["key"] = key
        if (page != null) vars["page"] = page
        var s = LegadoAnalyzer(this, base).renderTemplate(raw.trim(), vars).trim()

        if (page != null) {
            s = PAGE_LIST.replace(s) { m ->
                val pages = m.groupValues[1].split(',')
                (pages.getOrNull(page - 1) ?: pages.last()).trim()
            }
        }

        var method = "GET"
        var body: String? = null
        var charset: String? = null
        var retry = 0
        val headers = LinkedHashMap<String, String>()
        val cut = OPTION_SPLIT.find(s)
        if (cut != null) {
            val opt = s.substring(cut.range.last + 1)
            s = s.substring(0, cut.range.first)
            runCatching {
                val o = JSONObject(opt)
                method = o.optString("method", "GET").ifBlank { "GET" }.uppercase()
                charset = o.optString("charset").ifBlank { null }
                retry = o.optInt("retry", 0).coerceIn(0, 3)
                when (val b = o.opt("body")) {
                    is String -> body = b
                    is JSONObject, is JSONArray -> body = b.toString()
                    else -> Unit
                }
                when (val h = o.opt("headers")) {
                    is JSONObject -> for (k in h.keys()) headers[k] = h.optString(k)
                    is String -> headers.putAll(parseHeaders(h))
                    else -> Unit
                }
            }
        }

        val cs = charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val abs = LegadoAnalyzer.absUrl(base, s)
        val bodyOut = body?.let { b -> if (b.trimStart().startsWith("{") || b.trimStart().startsWith("[")) b else encodeForm(b, cs) }
        return LegadoUrl(escapeUrl(abs, cs), method, bodyOut, headers, charset, retry)
    }

    private fun parseHeaders(text: String?): Map<String, String> {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || !t.startsWith("{")) return emptyMap()
        return runCatching {
            val o = JSONObject(t)
            val m = LinkedHashMap<String, String>()
            for (k in o.keys()) m[k] = o.optString(k)
            m
        }.getOrDefault(emptyMap())
    }

    /** `k=v&k2=v2` 表单：值里已经有 `%XX` 的原样保留，其余按 [cs] 编码。 */
    private fun encodeForm(body: String, cs: Charset): String =
        body.split('&').filter { it.isNotEmpty() }.joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) pair else {
                val v = pair.substring(eq + 1)
                pair.substring(0, eq + 1) + if (PERCENT.containsMatchIn(v)) v else URLEncoder.encode(v, cs.name())
            }
        }

    /** 把地址里的空格和非 ASCII 字符转成 %XX（路径用 UTF-8，查询串用 [cs]）；已有的 %XX 不动。 */
    private fun escapeUrl(url: String, cs: Charset): String {
        if (url.none { it.code > 127 || it == ' ' }) return url
        val q = url.indexOf('?')
        val path = if (q < 0) url else url.substring(0, q)
        val query = if (q < 0) "" else url.substring(q)
        return percent(path, Charsets.UTF_8) + percent(query, cs)
    }

    private fun percent(s: String, cs: Charset): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp < 128 && cp != ' '.code) {
                sb.append(cp.toChar())
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(cs)) sb.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
            i += n
        }
        return sb.toString()
    }

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val MAX_TOC_PAGES = 100
        private const val MAX_CONTENT_PAGES = 30
        private val PAGE_LIST = Regex("<([^<>]*,[^<>]*)>")
        private val OPTION_SPLIT = Regex("\\s*,\\s*(?=\\{)")
        private val PERCENT = Regex("%[0-9a-fA-F]{2}")

        /** 脚本下载文件的临时目录（应用里设成 cacheDir 下的子目录）；未设置时用系统临时目录。 */
        @Volatile var cacheDir: File? = null
    }
}
