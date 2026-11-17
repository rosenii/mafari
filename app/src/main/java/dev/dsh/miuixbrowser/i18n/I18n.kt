package dev.dsh.miuixbrowser.i18n

import android.content.Context
import java.util.Locale

/**
 * 应用内多语言。
 *
 * 源码里的界面文案以简体中文为键：`tr("设置")`。
 * - 简体中文：原样返回
 * - English：查 assets/i18n/en.tsv（找不到则保留中文）
 * - 繁體中文：先查 assets/i18n/zh-TW-phrases.tsv 的台湾用语，再按 s2t.txt 逐字转换
 *
 * 带变量的文案用 `{0}` `{1}` 占位：`tr("共 {0} 项", n)`，译文里可以调换顺序。
 * 语言设置存在 mafari_settings 的 [KEY]；改语言后需重启应用（静态初始化的文案才会刷新）。
 */
object I18n {
    const val SYSTEM = 0
    const val ENGLISH = 1
    const val ZH_CN = 2
    const val ZH_TW = 3

    const val PREFS = "mafari_settings"
    const val KEY = "app_language"

    /** 设置页下拉选项的显示名：固定用各语言自己的名字，不随当前语言变化 */
    val OPTION_NATIVE_NAMES = listOf("", "English", "简体中文", "繁體中文")

    private enum class Lang { EN, ZH_CN, ZH_TW }

    @Volatile private var appContext: Context? = null
    @Volatile private var setting = SYSTEM
    @Volatile private var lang = Lang.ZH_CN
    @Volatile private var loaded = false

    private val lock = Any()
    private var enTable: HashMap<String, String> = HashMap()
    private var twPhrases: List<Pair<String, String>> = emptyList()
    private var s2t: HashMap<Char, Char> = HashMap()
    private val twCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2048
    }

    /** 进程启动时调用（Application.onCreate）；单元测试不调用，此时一律按简体中文原样返回 */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        setting = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, SYSTEM).coerceIn(SYSTEM, ZH_TW)
        lang = resolve(setting, Locale.getDefault())
        loaded = false
    }

    /** 当前选择（0 跟随系统 / 1 English / 2 简体中文 / 3 繁體中文） */
    val selected: Int get() = setting

    fun save(context: Context, option: Int) {
        val v = option.coerceIn(SYSTEM, ZH_TW)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, v).commit()
        setting = v
        lang = resolve(v, Locale.getDefault())
        loaded = false
    }

    /** 当前生效的 Locale（用于日期、数字格式等） */
    fun locale(): Locale = when (lang) {
        Lang.EN -> Locale.ENGLISH
        Lang.ZH_CN -> Locale.SIMPLIFIED_CHINESE
        Lang.ZH_TW -> Locale.TRADITIONAL_CHINESE
    }

    /** HTML lang 属性值（错误页等内置网页用） */
    fun htmlLang(): String = when (lang) { Lang.EN -> "en"; Lang.ZH_CN -> "zh-CN"; Lang.ZH_TW -> "zh-TW" }

    /** 当前是不是简体中文（源语言） */
    val isSourceLanguage: Boolean get() = lang == Lang.ZH_CN

    private fun resolve(option: Int, system: Locale): Lang = when (option) {
        ENGLISH -> Lang.EN
        ZH_CN -> Lang.ZH_CN
        ZH_TW -> Lang.ZH_TW
        else -> when {
            system.language == "zh" -> {
                val script = system.script
                val region = system.country
                if (script.equals("Hant", true) || region == "TW" || region == "HK" || region == "MO") Lang.ZH_TW else Lang.ZH_CN
            }
            // 其余系统语言：源码是中文，回退英文比回退中文更友好
            else -> Lang.EN
        }
    }

    fun translate(key: String): String {
        if (lang == Lang.ZH_CN || key.isEmpty()) return key
        ensureLoaded()
        return when (lang) {
            Lang.EN -> enTable[key] ?: key
            Lang.ZH_TW -> synchronized(twCache) { twCache[key] } ?: toTraditional(key).also { synchronized(twCache) { twCache[key] = it } }
            else -> key
        }
    }

    private fun toTraditional(s: String): String {
        if (!s.any { it in '\u4e00'..'\u9fff' }) return s
        var out = s
        for ((a, b) in twPhrases) if (out.contains(a)) out = out.replace(a, b)
        val sb = StringBuilder(out.length)
        for (c in out) sb.append(s2t[c] ?: c)
        return sb.toString()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val ctx = appContext
            if (ctx != null) runCatching {
                when (lang) {
                    Lang.EN -> enTable = readTsv(ctx, "i18n/en.tsv").toMap(HashMap())
                    Lang.ZH_TW -> {
                        twPhrases = readTsv(ctx, "i18n/zh-TW-phrases.tsv").sortedByDescending { it.first.length }
                        val raw = ctx.assets.open("i18n/s2t.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                        val m = HashMap<Char, Char>()
                        var i = 0
                        while (i + 1 < raw.length) { m[raw[i]] = raw[i + 1]; i += 2 }
                        s2t = m
                    }
                    else -> {}
                }
            }
            loaded = true
        }
    }

    private fun readTsv(ctx: Context, path: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(2048)
        ctx.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val t = line.indexOf('\t')
                if (t <= 0) continue
                out.add(unescape(line.substring(0, t)) to unescape(line.substring(t + 1)))
            }
        }
        return out
    }

    /** TSV 里用 \n \t \\ \s(前后空格保护：\s 表示一个空格) 转义 */
    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    's' -> sb.append(' ')
                    '\\' -> sb.append('\\')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}

/** 翻译一条界面文案。键是简体中文原文；`{0}` `{1}` 为占位符。 */
fun tr(key: String, vararg args: Any?): String {
    val t = I18n.translate(key)
    if (args.isEmpty()) return t
    var out = t
    for (i in args.indices) out = out.replace("{$i}", args[i]?.toString() ?: "null")
    return out
}
