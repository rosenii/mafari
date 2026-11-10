package dev.dsh.miuixbrowser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.RandomAccessFile

/**
 * 解析系统字体的真实「字体族名」，供 Gecko 的 font.name.* 偏好使用。
 *
 * 为什么需要：Gecko 的 font.name.sans-serif.* 取的是「字体族名」而不是通用族关键字。
 * 直接写 "sans-serif" 时 Gecko 在 Android 上不一定认得，会退回它自己默认的 Roboto / Noto Sans CJK，
 * 表现就是「没有用系统默认字体」。这里改成读系统 fonts.xml：
 *  - 西文：取 name="sans-serif" 家族里常规字重那一项，再从字体文件的 name 表读出真实族名（如 MiSans、OPPO Sans）；
 *  - 中日韩：取 fonts.xml 里对应 lang 的回退家族，作为 font.name-list 的后备，保证汉字也走系统指定的字体。
 *
 * 限制：只能识别写进 fonts.xml 的系统字体；个别厂商「主题字体」走框架层替换、不在 fonts.xml 里的，读不到，
 * 此时返回 null，由调用方退回旧写法。
 */
internal object GeckoSystemFont {

    class Result(
        /** sans-serif 主字体族名（西文 + 多数系统的汉字） */
        val main: String,
        /** lang 前缀 → 回退家族名，如 "zh-Hans" → "Noto Sans CJK SC" */
        val cjk: Map<String, String>,
    )

    private class Family(val name: String?, val lang: String?, val files: List<FontRef>)
    private class FontRef(val file: String, val index: Int, val weight: Int, val italic: Boolean)

    private val CONFIGS = listOf("/system/etc/fonts.xml", "/system/etc/font_fallback.xml")
    private val FONT_DIRS = listOf("/system/fonts", "/system_ext/fonts", "/product/fonts", "/vendor/fonts")

    @Volatile private var cached: Result? = null
    @Volatile private var done = false

    @Synchronized
    fun resolve(): Result? {
        if (done) return cached
        cached = runCatching { load() }.getOrNull()
        done = true
        return cached
    }

    private fun load(): Result? {
        val families = CONFIGS.map(::File).firstOrNull { it.isFile && it.canRead() }?.let(::parse) ?: return null
        val sans = families.firstOrNull { it.name == "sans-serif" } ?: return null
        val main = nameOf(pickRegular(sans)) ?: return null
        val cjk = LinkedHashMap<String, String>()
        for (lang in listOf("zh-Hans", "zh-Hant", "ja", "ko")) {
            val fam = families.firstOrNull { it.name == null && it.lang?.split(',', ' ')?.any { l -> l.trim().startsWith(lang) } == true }
                ?: continue
            nameOf(pickRegular(fam))?.let { cjk[lang] = it }
        }
        return Result(main, cjk)
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
                            val fileName = p.nextText().trim()
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

    private fun nameOf(ref: FontRef?): String? {
        ref ?: return null
        val f = FONT_DIRS.map { File(it, ref.file) }.firstOrNull { it.isFile && it.canRead() } ?: return null
        return familyName(f, ref.index)
    }

    /** 读 TTF / OTF / TTC 的 name 表：优先 nameID 16（排版族名），其次 nameID 1；取不到返回 null */
    private fun familyName(file: File, index: Int): String? = runCatching {
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
            repeat(numTables) {
                val tag = ByteArray(4).also { f.readFully(it) }
                f.skipBytes(4)
                val off = f.readInt().toLong() and 0xFFFFFFFFL
                f.skipBytes(4)
                if (String(tag, Charsets.ISO_8859_1) == "name") nameOff = off
            }
            if (nameOff < 0) return@use null
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
            best
        }
    }.getOrNull()
}
