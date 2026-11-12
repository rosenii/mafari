package dev.dsh.miuixbrowser

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.system.Os
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.RandomAccessFile

/**
 * 让 Gecko 的网页默认字体跟随 Android 系统字体。
 *
 * ## Gecko 和 WebView 的区别
 * WebView（Chromium）通过 Skia 直接问 Android 的字体管理器，系统换了字体它天然跟着变。
 * Gecko 不是：minSdk 33 下它用 NDK 的 `AndroidSystemFontIterator` 枚举系统字体文件，
 * 族名取自「字体文件 name 表」（不读 fonts.xml）；`sans-serif` 这类通用族则走 `font.name.sans-serif.<语言>`
 * 偏好里写的「族名」，没写就是 Gecko 自己的默认（Roboto / Noto Sans CJK）。
 * 所以要跟随系统字体，必须同时满足两件事：
 *  1. 知道系统「现在」用的是哪个字体、它的族名是什么（[resolve]）；
 *  2. 这个字体 Gecko 真的枚举得到（[syncProfileFonts]）。
 *
 * ## 找系统字体的两个来源（按优先级）
 *  1. **本进程实际映射的字体文件**（读 `/proc/self/maps`，先用 `Typeface.DEFAULT` 等强制加载一遍）：
 *     厂商「主题字体」（小米 / OPPO / vivo 等）是框架层替换的，不会写进 fonts.xml，
 *     但本应用进程里的 Typeface 用的就是它，文件一定被映射进来。只认「不在标准系统字体目录」的那部分。
 *  2. **系统 fonts.xml**：`sans-serif` 家族的常规字重 + 各语言的 CJK 回退家族。
 *
 * ## 让 Gecko 枚举得到
 * Gecko 无条件扫描 `<profile>/fonts` 目录（不受系统字体 API 影响）。
 * 这里在该目录里放**指向系统字体文件的软链接**（不复制字体文件，系统换字体后下次启动自动重建）。
 *
 * 限制：主题字体的识别靠进程映射 + 目录/文件名启发式，个别机型可能识别不到，此时退回 fonts.xml 的结果。
 */
internal object GeckoSystemFont {

    class Result(
        /** sans-serif 主字体族名（西文 + 多数系统的汉字） */
        val main: String,
        /** lang 前缀 → 回退家族名，如 "zh-Hans" → "Noto Sans CJK SC"（主题字体来源时为空：主字体自带汉字） */
        val cjk: Map<String, String>,
        /** 诊断用：来源说明 */
        val source: String,
        /** 主字体族的字体文件（用来在 profile 里建软链接） */
        val files: List<File>,
    ) {
        /** 内容签名：族名 + 文件路径 / 大小 / 修改时间；变了说明系统字体换过 */
        val signature: String = buildString {
            append(main).append('|').append(cjk.entries.joinToString(",") { it.key + "=" + it.value })
            files.forEach { append('|').append(it.path).append(':').append(it.length()).append(':').append(it.lastModified()) }
        }
    }

    private class Family(val name: String?, val lang: String?, val files: List<FontRef>)
    private class FontRef(val file: String, val index: Int, val weight: Int, val italic: Boolean)
    private class Info(val family: String, val weight: Int, val italic: Boolean)

    private val CONFIGS = listOf("/system/etc/fonts.xml", "/system/etc/font_fallback.xml")
    private val FONT_DIRS = listOf("/system/fonts", "/system_ext/fonts", "/product/fonts", "/vendor/fonts")

    /** Gecko 自己枚举得到、无需处理的系统字体目录（含 Android 12+ 可更新字体目录） */
    private val STANDARD_PREFIXES = listOf(
        "/system/fonts/", "/system_ext/fonts/", "/product/fonts/", "/vendor/fonts/", "/system/product/fonts/",
        "/system/system_ext/fonts/", "/data/fonts/",
    )

    /** 不可能是系统主题字体的路径（本应用 / 其它应用 / 存储卡 / apex） */
    private val IGNORED_PREFIXES = listOf(
        "/data/data/", "/data/user/", "/data/app/", "/data/local/", "/storage/", "/sdcard/", "/mnt/", "/apex/", "/proc/",
    )

    /** 不是正文字体的文件名（emoji / 符号 / 图标 / 等宽 / 衬线 / 回退等），不能当「默认无衬线字体」 */
    private val NOT_BODY_FONT = Regex(
        "emoji|symbol|icon|mono|(?<!sans)(?<!sans-)serif|cursive|fallback|dingbat|math|music|barcode",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_LINKS = 12
    private const val LINK_PREFIX = "mafari_"

    @Volatile private var cached: Result? = null
    @Volatile private var done = false
    @Volatile private var lastNote = "未解析"
    @Volatile private var reason = ""

    private val infoCache = HashMap<String, Info?>()

    /** 取系统字体信息。[force] = true 重新读取（系统换字体后用）；失败返回 null，由调用方退回旧写法 */
    @Synchronized
    fun resolve(force: Boolean = false): Result? {
        if (done && !force) return cached
        reason = ""
        cached = runCatching { fromProcess() ?: fromFontsXml() }
            .onFailure { reason = "异常 ${it.javaClass.simpleName}: ${it.message}"; Diag.log("system font: resolve failed $reason") }
            .getOrNull()
        lastNote = cached?.let { "${it.main}（${it.source}，${it.files.size} 个字体文件）" }
            ?: "未识别（退回 sans-serif）：$reason"
        if (cached == null) Diag.log("system font: $lastNote")
        done = true
        return cached
    }

    /** 调试页展示用 */
    fun describe(): String = lastNote

    // ───────────────────────── 来源 1：本进程实际映射的字体 ─────────────────────────

    private fun fromProcess(): Result? {
        val themed = mappedFontFiles()
            .filter { isThemedPath(it.path) && !NOT_BODY_FONT.containsMatchIn(it.name) && it.isFile && it.canRead() }
        if (themed.isEmpty()) return null
        val infos = themed.mapNotNull { f -> info(f, 0)?.let { f to it } }
        if (infos.isEmpty()) return null
        val best = infos.filter { !it.second.italic }.minByOrNull { kotlin.math.abs(it.second.weight - 400) } ?: infos.first()
        val family = best.second.family
        val files = infos.filter { it.second.family == family }.map { it.first }.take(MAX_LINKS)
        Diag.log("system font: themed ${best.first.path} -> $family (${files.size} files)")
        return Result(family, emptyMap(), "系统主题字体", files)
    }

    private fun isThemedPath(path: String): Boolean =
        STANDARD_PREFIXES.none { path.startsWith(it) } && IGNORED_PREFIXES.none { path.startsWith(it) }

    /** 先让 Typeface 把默认字体加载进进程，再从 /proc/self/maps 里取出被映射的字体文件 */
    private fun mappedFontFiles(): List<File> {
        runCatching {
            val paint = Paint()
            val faces = listOf(
                Typeface.DEFAULT, Typeface.SANS_SERIF,
                Typeface.create("sans-serif", Typeface.NORMAL),
                Typeface.create("sans-serif", Typeface.BOLD),
            )
            faces.forEach { paint.typeface = it; paint.measureText("Ag中文あ한") }
        }
        val paths = LinkedHashSet<String>()
        runCatching {
            File("/proc/self/maps").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val i = line.indexOf('/')
                    if (i < 0) return@forEach
                    val path = line.substring(i).trim().removeSuffix(" (deleted)")
                    when (path.substringAfterLast('.', "").lowercase()) {
                        "ttf", "otf", "ttc", "otc" -> paths.add(path)
                    }
                }
            }
        }
        return paths.map(::File)
    }

    // ───────────────────────── 来源 2：系统 fonts.xml ─────────────────────────

    private fun fromFontsXml(): Result? {
        val cfg = CONFIGS.map(::File).firstOrNull { it.isFile && it.canRead() }
        if (cfg == null) { reason = "读不到 fonts.xml"; return null }
        val families = cfg.let(::parse)
        val sans = families.firstOrNull { it.name == "sans-serif" }
        if (sans == null) { reason = "fonts.xml 里没有 sans-serif"; return null }
        val regular = pickRegular(sans)
        if (regular == null) { reason = "sans-serif 没有字体文件"; return null }
        val main = nameOf(regular)
        if (main == null) { reason = "读不到 ${regular.file} 的族名"; return null }
        val cjk = LinkedHashMap<String, String>()
        for (lang in listOf("zh-Hans", "zh-Hant", "ja", "ko")) {
            val fam = families.firstOrNull { it.name == null && it.lang?.split(',', ' ')?.any { l -> l.trim().startsWith(lang) } == true }
                ?: continue
            nameOf(pickRegular(fam))?.let { cjk[lang] = it }
        }
        // 主字体族的全部字重文件（只收族名和主字体一致的），建软链接时粗体 / 细体不会被合成
        val files = sans.files.mapNotNull { ref -> locate(ref)?.takeIf { f -> info(f, ref.index)?.family == main } }
            .distinct().take(MAX_LINKS)
        return Result(main, cjk, "fonts.xml", files)
    }

    private fun pickRegular(f: Family): FontRef? =
        f.files.filter { !it.italic }.minByOrNull { kotlin.math.abs(it.weight - 400) } ?: f.files.firstOrNull()

    private fun parse(file: File): List<Family> {
        val out = ArrayList<Family>()
        file.inputStream().use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            var name: String? = null
            var lang: String? = null
            var files = ArrayList<FontRef>()
            var inFamily = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "family" -> {
                            inFamily = true
                            name = p.getAttributeValue(null, "name")
                            lang = p.getAttributeValue(null, "lang")
                            files = ArrayList()
                        }
                        "font" -> if (inFamily) {
                            val weight = p.getAttributeValue(null, "weight")?.toIntOrNull() ?: 400
                            val italic = p.getAttributeValue(null, "style") == "italic"
                            val index = p.getAttributeValue(null, "index")?.toIntOrNull() ?: 0
                            // Android 12+ 的 <font> 里带 <axis> 子标签，nextText() 会抛异常，必须手动收集文本
                            val sb = StringBuilder()
                            var depth = 1
                            while (depth > 0) {
                                when (p.next()) {
                                    XmlPullParser.TEXT -> if (depth == 1) sb.append(p.text)
                                    XmlPullParser.START_TAG -> depth++
                                    XmlPullParser.END_TAG -> depth--
                                    XmlPullParser.END_DOCUMENT -> depth = 0
                                }
                            }
                            val fileName = sb.toString().trim()
                            if (fileName.isNotEmpty()) files.add(FontRef(fileName, index, weight, italic))
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "family" && inFamily) {
                    out.add(Family(name, lang, files))
                    inFamily = false
                }
                ev = p.next()
            }
        }
        return out
    }

    /** 系统字体 API（API 29+）列出的字体文件：覆盖 /data/fonts 等 fonts.xml 里没写全路径的位置 */
    private val apiFonts: List<File> by lazy {
        runCatching { android.graphics.fonts.SystemFonts.getAvailableFonts().mapNotNull { it.file } }
            .getOrDefault(emptyList())
    }

    private fun locate(ref: FontRef): File? =
        FONT_DIRS.map { File(it, ref.file) }.firstOrNull { it.isFile && it.canRead() }
            ?: apiFonts.firstOrNull { it.name == ref.file && it.canRead() }

    private fun nameOf(ref: FontRef?): String? {
        ref ?: return null
        val f = locate(ref) ?: return null
        return info(f, ref.index)?.family
    }

    // ───────────────────────── 让 Gecko 枚举得到：profile/fonts 里的软链接 ─────────────────────────

    /**
     * 把 [r] 的字体文件以软链接放进 Gecko profile 的 `fonts` 目录；[r] = null 时清掉本应用建过的软链接。
     * 返回新建的软链接数。Gecko 只在启动时枚举字体，所以新建软链接后要重启应用才会被识别。
     */
    fun syncProfileFonts(context: Context, r: Result?): Int {
        var created = 0
        val roots = File(context.filesDir, "mozilla").listFiles { f -> f.isDirectory }
            ?.filter { it.name.endsWith(".default") || File(it, "prefs.js").exists() }
            .orEmpty()
        if (roots.isEmpty()) {
            if (r != null) Diag.log("system font: gecko profile dir not found yet, will link next launch")
            return 0
        }
        val wanted = r?.files.orEmpty().associateBy(::linkName)
        for (root in roots) {
            val dir = File(root, "fonts")
            runCatching {
                if (wanted.isNotEmpty()) dir.mkdirs()
                dir.listFiles { f -> f.name.startsWith(LINK_PREFIX) }?.forEach { old ->
                    if (old.name !in wanted) old.delete()
                }
                wanted.forEach { (name, target) ->
                    val link = File(dir, name)
                    if (link.exists()) return@forEach
                    Os.symlink(target.path, link.path)
                    created++
                }
            }.onFailure { Diag.log("system font: link failed ${it.message}") }
        }
        if (created > 0) Diag.log("system font: linked $created font file(s) into gecko profile")
        return created
    }

    /** 软链接名带「路径 + 大小 + 修改时间」的哈希：字体文件变了名字也变，Gecko 的字体缓存不会误用旧数据 */
    private fun linkName(f: File): String {
        val magic = runCatching { RandomAccessFile(f, "r").use { it.readInt() } }.getOrNull()
        val ext = when (magic) {
            0x74746366 -> "ttc"
            0x4F54544F -> "otf"
            else -> "ttf"
        }
        val hash = Integer.toHexString((f.path + "|" + f.length() + "|" + f.lastModified()).hashCode())
        val base = f.name.substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
        return "$LINK_PREFIX${hash}_$base.$ext"
    }

    // ───────────────────────── 读字体文件 ─────────────────────────

    private fun info(file: File, index: Int): Info? {
        val key = "${file.path}|$index|${file.length()}|${file.lastModified()}"
        synchronized(infoCache) {
            if (infoCache.containsKey(key)) return infoCache[key]
        }
        val v = readInfo(file, index)
        synchronized(infoCache) { infoCache[key] = v }
        return v
    }

    /** 读 TTF / OTF / TTC：族名（nameID 16 优先，其次 1，和 Gecko 取名顺序一致）+ OS/2 字重 / 斜体 */
    private fun readInfo(file: File, index: Int): Info? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            var base = 0L
            if (f.readInt() == 0x74746366) { // 'ttcf'
                f.readInt()
                val n = f.readInt()
                f.seek(12L + 4L * index.coerceIn(0, (n - 1).coerceAtLeast(0)))
                base = f.readInt().toLong() and 0xFFFFFFFFL
            }
            f.seek(base + 4)
            val numTables = f.readUnsignedShort()
            f.skipBytes(6)
            var nameOff = -1L
            var os2Off = -1L
            repeat(numTables) {
                val tag = ByteArray(4).also { f.readFully(it) }
                f.skipBytes(4)
                val off = f.readInt().toLong() and 0xFFFFFFFFL
                f.skipBytes(4)
                val t = String(tag, Charsets.ISO_8859_1)
                if (t == "OS/2") os2Off = off
                if (t == "name") nameOff = off
            }
            val family = readFamily(f, nameOff) ?: return@use null
            var weight = 400
            var italic = false
            if (os2Off >= 0) {
                runCatching {
                    f.seek(os2Off + 4)
                    weight = f.readUnsignedShort()
                    f.seek(os2Off + 62)
                    italic = (f.readUnsignedShort() and 1) != 0
                }
            }
            Info(family, weight, italic)
        }
    }.getOrNull()

    private fun readFamily(f: RandomAccessFile, nameOff: Long): String? {
        if (nameOff < 0) return null
        f.seek(nameOff + 2)
        val count = f.readUnsignedShort()
        val strOff = f.readUnsignedShort()
        var best: String? = null
        var bestScore = -1
        repeat(count) {
            val platform = f.readUnsignedShort()
            f.skipBytes(2)
            val lang = f.readUnsignedShort()
            val id = f.readUnsignedShort()
            val len = f.readUnsignedShort()
            val off = f.readUnsignedShort()
            if (id != 1 && id != 16) return@repeat
            val score = (if (id == 16) 2 else 0) * 10 +
                (if (platform == 3 && lang == 0x409) 3 else if (platform == 1) 2 else if (platform == 3) 1 else 0)
            if (score <= bestScore || (platform != 3 && platform != 1)) return@repeat
            val here = f.filePointer
            val bytes = ByteArray(len)
            f.seek(nameOff + strOff + off)
            f.readFully(bytes)
            f.seek(here)
            val s = if (platform == 3) String(bytes, Charsets.UTF_16BE) else String(bytes, Charsets.ISO_8859_1)
            if (s.isNotBlank()) { best = s.trim(); bestScore = score }
        }
        return best
    }
}
