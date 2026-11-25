/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/**
 * 「用 Mafari 打开 HTML 文件」：其他应用（文件管理器、聊天软件、邮件……）通过系统「打开方式」交来的是
 * content:// 或 file:// 地址，其中 content:// 的读权限只在那次启动里有效，标签恢复、重新加载时就读不到了，
 * file:// 在新版 Android 上更是多半读不了。所以先把文件拷到应用私有的 filesDir/opened_html/<哈希>/<文件名>，
 * 再让浏览器加载这份副本（file://）。
 *
 * 安全：MainActivity 是导出的，WebView 也要开 allowFileAccess 才能加载 file://，
 * 所以所有「放行 file://」的地方都必须用 [isOpenedFile] 校验：只认本目录下的副本，其余 file:// 一律拒绝
 * （否则任何应用都能让浏览器去读本应用的私有数据库，或者网页能读手机存储）。
 */
object LocalHtml {
    private const val DIR = "opened_html"

    /** 单个 HTML 文件的体积上限 */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** 旧副本保留时间：超过就在下次打开时清掉 */
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /** 网页文件后缀（含 shtml / xht / mhtml 等）；MHTML 只有 WebView 内核能渲染，Gecko 不支持 */
    val HTML_EXT = setOf("html", "htm", "xhtml", "xht", "shtml", "shtm", "mhtml", "mht")

    /** 当纯文本打开的后缀（包成一页可读的 HTML 再加载） */
    val TEXT_EXT = setOf("txt", "text", "log", "md")

    /** 纯文本最多读多少：超出的部分不显示（整页 <pre> 太大会拖垮渲染） */
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /** [name] / [mime] 看起来是纯文本（后缀优先；后缀是网页就不算，没有后缀再看 MIME） */
    fun isText(name: String?, mime: String?): Boolean {
        val ext = name.orEmpty().substringAfterLast('.', "").lowercase()
        if (ext in HTML_EXT) return false
        if (ext in TEXT_EXT) return true
        return ext.isEmpty() && mime.orEmpty().lowercase().startsWith("text/plain")
    }

    /** 放在 filesDir 而不是 cacheDir：冷启动会清空 cacheDir（见 CacheCleaner），标签恢复时文件就没了 */
    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR)

    /** [uri] 是本应用拷出来的副本（file://，规范路径落在 [dir] 之内）。 */
    fun isOpenedFile(context: Context, uri: Uri): Boolean {
        if (!"file".equals(uri.scheme, ignoreCase = true)) return false
        val path = uri.path ?: return false
        return runCatching {
            val root = dir(context).canonicalPath + File.separator
            File(path).canonicalPath.startsWith(root)
        }.getOrDefault(false)
    }

    fun isOpenedFileUrl(context: Context, url: String): Boolean =
        runCatching { isOpenedFile(context, Uri.parse(url)) }.getOrDefault(false)

    /** 可以由「打开方式」交进来的地址：content:// 或 file://。 */
    fun isImportable(uri: Uri?): Boolean {
        val s = uri?.scheme?.lowercase()
        return s == "content" || s == "file"
    }

    /** 把 [src] 拷成本地副本。失败（读不到 / 太大 / 磁盘满）返回 failure，并清掉没写完的文件。 */
    fun import(context: Context, src: Uri, mime: String? = null): Result<File> = runCatching {
        val app = context.applicationContext
        val folder = File(dir(app), digest(src.toString())).apply { mkdirs() }
        val rawName = rawNameOf(app, src)
        val target = File(folder, cleanName(rawName))
        try {
            if (isText(rawName, mime)) {
                writeTextPage(app, src, rawName, target)
                prune(app, keep = folder)
                return@runCatching target
            }
            val input = app.contentResolver.openInputStream(src) ?: error("cannot open $src")
            input.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) error("file too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        prune(app, keep = folder)
        target
    }

    /** 清掉超过 [KEEP_MS] 没动过的旧副本；[keep] 是这次刚写的目录。 */
    private fun prune(context: Context, keep: File) {
        val now = System.currentTimeMillis()
        runCatching {
            dir(context).listFiles()?.forEach { d ->
                if (d != keep && now - d.lastModified() > KEEP_MS) d.deleteRecursively()
            }
        }
    }

    /** 纯文本 → 一页可读的 HTML：自动判断编码、保留换行、自动折行、跟随深浅色；文本内容全部转义，不会执行里面的脚本。 */
    private fun writeTextPage(context: Context, src: Uri, rawName: String?, target: File) {
        val input = context.contentResolver.openInputStream(src) ?: error("cannot open $src")
        val buf = java.io.ByteArrayOutputStream()
        var truncated = false
        input.use { ins ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                val room = MAX_TEXT_BYTES - buf.size()
                if (n > room) { buf.write(chunk, 0, room); truncated = true; break }
                buf.write(chunk, 0, n)
            }
        }
        val text = decodeText(buf.toByteArray())
        val title = (rawName ?: "text").substringAfterLast('/').substringAfterLast('\\')
        val mono = if (title.substringAfterLast('.', "").lowercase() == "log") MONO else SANS
        target.writeText(textPageHtml(title, text, truncated, mono), Charsets.UTF_8)
    }

    private const val MONO = "ui-monospace,Menlo,Consolas,monospace"
    private const val SANS = "system-ui,-apple-system,'Segoe UI',sans-serif"

    /** 纯文本页的 HTML：[text] 全部转义；[truncated] 时末尾加一行提示。 */
    internal fun textPageHtml(title: String, text: String, truncated: Boolean, font: String = SANS): String {
        val note = if (truncated) "\n\n—— 文件较大，仅显示前 8 MB ——" else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light dark\"><title>${escape(title)}</title>" +
            "<style>body{margin:0;padding:16px}pre{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;" +
            "font:15px/1.65 $font;tab-size:4}</style></head><body><pre>${escape(text + note)}</pre></body></html>"
    }

    /**
     * 分享菜单交来的一段文字（EXTRA_TEXT）整个就是一条 http(s) 链接时返回它，否则 null。
     * 文字里夹着别的内容（标题 + 链接）不当链接处理，按文本显示，避免误开。
     */
    fun sharedLink(text: String): String? {
        val s = text.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val lower = s.lowercase()
        return if ((lower.startsWith("http://") || lower.startsWith("https://")) && s.length > 8) s else null
    }

    /** 把分享来的一段文字做成本地文本页（和纯文本文件同一套样式）。 */
    fun importText(context: Context, text: String): Result<File> = runCatching {
        val app = context.applicationContext
        val folder = File(dir(app), digest("text:$text")).apply { mkdirs() }
        val target = File(folder, "shared-text.html")
        try {
            val title = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(40).ifEmpty { "text" }
            target.writeText(textPageHtml(title, text, truncated = false), Charsets.UTF_8)
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        prune(app, keep = folder)
        target
    }

    /** 编码判断：BOM → 严格 UTF-8 → GB18030（兼容 GBK）→ 容错 UTF-8。 */
    internal fun decodeText(bytes: ByteArray): String {
        fun strict(cs: java.nio.charset.Charset, off: Int = 0): String? = runCatching {
            cs.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, off, bytes.size - off)).toString()
        }.getOrNull()
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return strict(Charsets.UTF_8) ?: strict(java.nio.charset.Charset.forName("GB18030")) ?: String(bytes, Charsets.UTF_8)
    }

    internal fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 取显示名：先问提供方的 DISPLAY_NAME，再用路径最后一段（未清洗）。 */
    private fun rawNameOf(context: Context, uri: Uri): String? {
        var name: String? = null
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        return name
    }

    /** 文件名清洗：去掉路径分隔符 / 控制字符 / 非法字符，限长 80，缺 html 扩展名就补 .html。 */
    internal fun cleanName(raw: String?): String {
        var clean = (raw ?: "page").substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '"' && it != '<' && it != '>' && it != ':' && it != '|' && it != '?' && it != '*' }
            .trim().trim('.')
        if (clean.isEmpty()) clean = "page"
        if (clean.length > 80) clean = clean.takeLast(80)
        val ext = clean.substringAfterLast('.', "").lowercase()
        return if (ext in HTML_EXT) clean else "$clean.html"
    }

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
