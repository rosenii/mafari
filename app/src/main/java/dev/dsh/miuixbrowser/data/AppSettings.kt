/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.books.data.BookSort

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.download.ExternalDownloader
import dev.dsh.miuixbrowser.browser.HOME_QUICK_COLS
import dev.dsh.miuixbrowser.browser.HOME_QUICK_ROWS_MAX
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.browser.parseQuickMenu
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.net.WebViewProxy
import dev.dsh.miuixbrowser.translate.TranslateConfig
import dev.dsh.miuixbrowser.translate.TranslateLangs
import dev.dsh.miuixbrowser.translate.TranslateSourceId
import dev.dsh.miuixbrowser.ui.UpdateChannel
import dev.dsh.miuixbrowser.ui.WallpaperBlurCache
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 下载页可选的线程数 */
val DOWNLOAD_THREAD_OPTIONS = listOf(4, 8, 16, 32, 64)

/** 下载页「多线程阈值」档位（MB）：10 / 50 / 100 / 200 / 500 / 1G */
val DOWNLOAD_MIN_MB_OPTIONS = listOf(10, 50, 100, 200, 500, 1024)

/** 多线程阈值默认值（MB） */
const val DOWNLOAD_MIN_MB_DEFAULT = 200

/** 阈值档位的显示文字 */
fun downloadThresholdLabel(mb: Int): String = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

/** 浏览器内核 */
const val ENGINE_WEBVIEW = 0
const val ENGINE_GECKO = 1

/** 模糊效果模式 */
const val BLUR_MIUIX = 0
const val BLUR_LIQUID = 1
const val BLUR_OFF = 2
/** 磨砂：miuix-glass 不开折射（上游默认质感）；BLUR_MIUIX（柔光玻璃）则开折射。数值 3 只是为了不动已存的 0/1/2。 */
const val BLUR_FROST = 3

/** 首页个人收藏可选行数上限 / 默认行数 */
const val HOME_FAV_ROWS_MAX = 6
const val HOME_FAV_ROWS_DEFAULT = HOME_FAV_ROWS_MAX

/** 首页内容块 */
const val HOME_FAVORITES = 0
const val HOME_PRIVACY = 1
const val HOME_RECENT = 2
const val HOME_DOWNLOADS = 3
const val HOME_GROUPS = 4
const val HOME_QUICK = 5

/** 首页「快捷菜单」默认显示行数（0=不显示，1..2 行，每行 6 个；见 [HOME_QUICK_COLS]） */
const val HOME_QUICK_ROWS_DEFAULT = HOME_QUICK_ROWS_MAX

/** 首页「书签分组」卡片：每行格数 / 最多行数 */
const val HOME_GROUP_COLS = 5
const val HOME_GROUP_ROWS_MAX = 5
/** 首页「书签分组」默认显示行数（沿用旧版行为：最多 5 行） */
const val HOME_GROUP_ROWS_DEFAULT = HOME_GROUP_ROWS_MAX

/** 「定制主页 → 自定义起始页」：主页按钮 / 新建标签页 / 启动应用时打开什么 */
const val HOME_MODE_DEFAULT = 0
const val HOME_MODE_NEW_TAB = 1
const val HOME_MODE_CUSTOM = 2

/** 首次安装 / 升级后的「用户引导页」版本：引导内容有重大变化、需要让老用户再看一遍时 +1 */
const val ONBOARDING_VERSION = 1

/** 引导完成标记所在的 SharedPreferences（独立文件，不参与备份 / 恢复） */
const val ONBOARDING_PREFS = "mafari_onboarding"

/** 「布局 → 导航栏样式」：数值写进 SharedPreferences，不要改 */
const val BAR_POS_TOP = 0
const val BAR_POS_BOTTOM = 1
const val BAR_POS_MINIMIZED = 2
/** 固定在底部 / 顶部：位置固定，不随页面滚动自动隐藏，也不响应滑动收起 / 缩成圆钮 / 换边 */
const val BAR_POS_FIXED_BOTTOM = 3
const val BAR_POS_FIXED_TOP = 4
/** 悬浮三段式（底部）：左后退 / 中地址栏 / 右更多 三块分开的玻璃件，固定显示不随滚动隐藏 */
const val BAR_POS_SAFARI_BOTTOM = 5

private const val PREF_BAR_POSITION = "bar_position"
private const val PREF_HOME_MODE = "home_mode"
private const val PREF_HOME_CUSTOM_URL = "home_custom_url"

/** BrowserState 等没有 AppSettings 实例的地方直接读取（每次现读，设置改了立刻生效） */
fun readHomeMode(context: Context): Int =
    context.getSharedPreferences("mafari_settings", Context.MODE_PRIVATE)
        .getInt(PREF_HOME_MODE, HOME_MODE_DEFAULT).coerceIn(HOME_MODE_DEFAULT, HOME_MODE_CUSTOM)

fun readHomeCustomUrl(context: Context): String =
    context.getSharedPreferences("mafari_settings", Context.MODE_PRIVATE)
        .getString(PREF_HOME_CUSTOM_URL, "").orEmpty()

/** 解析首页内容块顺序；缺失 / 非法项补齐，默认 快捷菜单 → 收藏 → 书签分组 → 隐私报告 → 最近浏览 → 下载项（旧版升级上来的，新增的快捷菜单排在末尾）。 */
fun parseHomeOrder(raw: String?): List<Int> {
    val all = listOf(HOME_QUICK, HOME_FAVORITES, HOME_GROUPS, HOME_PRIVACY, HOME_RECENT, HOME_DOWNLOADS)
    val parsed = raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        .filter { it in all }.distinct()
    return parsed + all.filter { it !in parsed }
}

/** 解析首页「书签分组」里已添加的文件夹 id（按显示顺序，逗号分隔；id 是 UUID，不含逗号）。 */
fun parseHomeGroupIds(raw: String?): List<String> =
    raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** 标签页创建时直接读 SharedPreferences（不必构造整个 AppSettings） */
fun readBrowserEngine(context: Context): Int =
    context.getSharedPreferences("mafari_settings", Context.MODE_PRIVATE)
        .getInt("browser_engine", ENGINE_GECKO)

/** 网页增强总开关（脚本 / 去广告 / WebView 拓展）：冷启动早期、没有 AppSettings 实例的地方直接读。默认开 */
fun readPageEnhancements(context: Context): Boolean =
    context.getSharedPreferences("mafari_settings", Context.MODE_PRIVATE)
        .getBoolean("page_enhancements", true)

/** 阅读器排版（书架 → 阅读）：字号 sp、行距（占字号的百分比）、左右 / 上下页边距 dp。默认值与最初的固定排版一致 */
const val READER_FONT_MIN = 14
const val READER_FONT_MAX = 32
const val READER_FONT_DEFAULT = 18
const val READER_LINE_MIN = 120
const val READER_LINE_MAX = 260
const val READER_LINE_DEFAULT = 180
const val READER_PAD_H_MIN = 12
const val READER_PAD_H_MAX = 56
const val READER_PAD_H_DEFAULT = 26
/** 阅读器「文字背景」：-1 = 跟随应用深浅色（默认）；其余数值写进 SharedPreferences，不要改 */
const val READER_THEME_AUTO = -1
const val READER_THEME_PAPER = 0
const val READER_THEME_EYE = 1
const val READER_THEME_LIGHT = 2
const val READER_THEME_DARK = 3

/** 阅读器翻页方式：上下 / 左右平滑 / 仿真。 */
const val READER_PAGE_TURN_VERTICAL = 0
const val READER_PAGE_TURN_SMOOTH = 1
const val READER_PAGE_TURN_SIMULATED = 2
const val READER_PAD_V_MIN = 14
const val READER_PAD_V_MAX = 44
const val READER_PAD_V_DEFAULT = 26

/** 「重启恢复标签页」的三种方式（[AppSettings.restoreTabsMode]） */
object RestoreMode {
    const val ALL = 0
    const val KEEP_NEW = 1
    const val NONE = 2
}

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("mafari_settings", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    /** 每次设置壁纸 +1，用来让模糊缓存在同 uri 重选时也能重新生成。 */
    var wallpaperVersion by mutableStateOf(0)
        private set

    /** 模糊效果模式：0 = 柔光玻璃（miuix-glass + 折射），3 = 磨砂（miuix-glass 无折射），1 = liquid glass（Kyant0/AndroidLiquidGlass），2 = 关闭模糊。旧版布尔开关会迁移过来。 */
    var blurMode by mutableStateOf(
        if (prefs.contains(KEY_BLUR_MODE)) {
            prefs.getInt(KEY_BLUR_MODE, BLUR_MIUIX).coerceIn(BLUR_MIUIX, BLUR_FROST)
        } else if (prefs.contains(KEY_BLUR)) {
            if (prefs.getBoolean(KEY_BLUR, true)) BLUR_MIUIX else BLUR_OFF // 旧版布尔开关迁移
        } else {
            BLUR_OFF // 默认纯色
        },
    )
        private set

    /**
     * 模板下标迁移（版本号存在 glass_preset_ver）。现行顺序：0 miuix 默认、1 清透、2 水晶、3 晶边、4 雾霭、5 白亮。
     * - v1（无版本号）旧顺序：0 清透 1 水晶 2 晶边 3 雾玻璃 4 折射磨砂 5 miuix 默认 → 依次对应 雾玻璃=雾霭、折射磨砂=白亮；
     * - v2（中间版本）：0 miuix 默认 1 雾霭 2 晶边 3 白亮。
     * 套用过模板的把参数一并换成现行模板的数值，避免名字和数值对不上；手动自定义（-1）原样保留。
     */
    @Suppress("unused")
    private val glassPresetMigrated: Unit = run {
        val ver = prefs.getInt(KEY_GLASS_PRESET_VER, 1)
        if (!prefs.contains(KEY_GLASS_PARAMS) || ver >= 3) return@run
        val old = prefs.getInt(KEY_GLASS_PRESET, -1)
        val mapped = if (ver == 1) {
            when (old) {
                0 -> GlassPresets.INDEX_CLEAR
                1 -> GlassPresets.INDEX_CRYSTAL
                2 -> GlassPresets.INDEX_CRYSTAL_EDGE
                3 -> GlassPresets.INDEX_HAZE
                4 -> GlassPresets.INDEX_BRIGHT
                5 -> GlassPresets.INDEX_UPSTREAM
                else -> -1
            }
        } else {
            when (old) {
                0 -> GlassPresets.INDEX_UPSTREAM
                1 -> GlassPresets.INDEX_HAZE
                2 -> GlassPresets.INDEX_CRYSTAL_EDGE
                3 -> GlassPresets.INDEX_BRIGHT
                else -> -1
            }
        }
        val e = prefs.edit().putInt(KEY_GLASS_PRESET_VER, 3).putInt(KEY_GLASS_PRESET, mapped)
        GlassPresets.ALL.getOrNull(mapped)?.let { e.putString(KEY_GLASS_PARAMS, it.params.encode()) }
        e.apply()
    }

    /** 柔光玻璃的自定义材质参数（设置 →「高级材质」→「自定义材质参数」）。 */
    var glassParams by mutableStateOf(
        GlassParams.decode(prefs.getString(KEY_GLASS_PARAMS, null)) ?: GlassPresets.ALL[0].params,
    )
        private set

    /** 当前套用的柔光模板下标；-1 表示手动改过参数（自定义）。 */
    var glassPreset by mutableStateOf(
        if (prefs.contains(KEY_GLASS_PARAMS)) prefs.getInt(KEY_GLASS_PRESET, -1).takeIf { it in GlassPresets.ALL.indices } ?: -1 else 0,
    )
        private set

    /** 液态玻璃的自定义材质参数（设置 →「高级材质」→「自定义材质参数」，选「液态玻璃」时）。 */
    var liquidParams by mutableStateOf(
        LiquidParams.decode(prefs.getString(KEY_LIQUID_PARAMS, null)) ?: LiquidPresets.ALL[0].params,
    )
        private set

    /** 当前套用的液态模板下标；-1 表示手动改过参数（自定义）。 */
    var liquidPreset by mutableStateOf(
        if (prefs.contains(KEY_LIQUID_PARAMS)) prefs.getInt(KEY_LIQUID_PRESET, -1).takeIf { it in LiquidPresets.ALL.indices } ?: -1 else 0,
    )
        private set

    /** 是否有任何模糊（miuix-glass 或 liquid glass） */
    val blurEnabled: Boolean get() = blurMode != BLUR_OFF

    /** 是否使用 liquid glass 效果 */
    val liquidGlass: Boolean get() = blurMode == BLUR_LIQUID

    /** 首页内容显示：LOGO / 收藏 / 隐私报告 / 最近浏览 / 下载项默认全部显示。 */
    /** 个人收藏在首页显示的行数：0=不显示，1..6=显示对应行数（每行 3 个）。默认最多 6 行。 */
    var homeFavRows by mutableStateOf(
        if (prefs.contains(KEY_HOME_FAV_ROWS)) {
            prefs.getInt(KEY_HOME_FAV_ROWS, HOME_FAV_ROWS_DEFAULT).coerceIn(0, HOME_FAV_ROWS_MAX)
        } else if (!prefs.getBoolean(KEY_HOME_FAV, true)) {
            0 // 旧版「隐藏」迁移为「不显示」
        } else {
            HOME_FAV_ROWS_DEFAULT
        },
    )
        private set
    val homeShowFavorites: Boolean get() = homeFavRows > 0

    fun updateHomeFavRows(rows: Int) {
        homeFavRows = rows.coerceIn(0, HOME_FAV_ROWS_MAX)
        prefs.edit().putInt(KEY_HOME_FAV_ROWS, homeFavRows).apply()
    }
    /** 个人收藏的排列方式：false = 图标在左标题在右（每行 3 个，默认）；true = 图标在上标题在下（每行个数随宽度自适应）。 */
    var homeFavGrid by mutableStateOf(prefs.getBoolean(KEY_HOME_FAV_GRID, false))
        private set

    fun updateHomeFavGrid(grid: Boolean) {
        homeFavGrid = grid
        prefs.edit().putBoolean(KEY_HOME_FAV_GRID, grid).apply()
    }
    /** 首页顶部的 Mafari LOGO 标识：显示 / 隐藏（定制主页里设置） */
    var homeShowLogo by mutableStateOf(prefs.getBoolean(KEY_HOME_LOGO, true))
        private set

    fun updateHomeShowLogo(show: Boolean) {
        homeShowLogo = show
        prefs.edit().putBoolean(KEY_HOME_LOGO, show).apply()
    }

    var homeShowPrivacy by mutableStateOf(prefs.getBoolean(KEY_HOME_PRIVACY, true))
        private set
    var homeShowRecent by mutableStateOf(prefs.getBoolean(KEY_HOME_RECENT, true))
        private set
    var homeShowDownloads by mutableStateOf(prefs.getBoolean(KEY_HOME_DOWNLOADS, true))
        private set
    /** 书签分组在首页显示的行数：0=不显示，1..5=显示对应行数（每行 5 个）。旧版「隐藏」迁移为「不显示」。 */
    var homeGroupRows by mutableStateOf(
        if (prefs.contains(KEY_HOME_GROUP_ROWS)) {
            prefs.getInt(KEY_HOME_GROUP_ROWS, HOME_GROUP_ROWS_DEFAULT).coerceIn(0, HOME_GROUP_ROWS_MAX)
        } else if (!prefs.getBoolean(KEY_HOME_GROUPS, true)) {
            0
        } else {
            HOME_GROUP_ROWS_DEFAULT
        },
    )
        private set
    val homeShowGroups: Boolean get() = homeGroupRows > 0

    fun updateHomeGroupRows(rows: Int) {
        homeGroupRows = rows.coerceIn(0, HOME_GROUP_ROWS_MAX)
        prefs.edit().putInt(KEY_HOME_GROUP_ROWS, homeGroupRows).apply()
    }

    // 首页「书签分组」里已添加的文件夹 id、点开分组弹窗的排列方式：现在存在加密库 history.db 里（见 FavoritesStore / BookmarkDb），
    // 随「书签」类别备份；旧版放在这里的 home_group_ids 由 FavoritesStore 启动时一次性导入。

    /** 首页「快捷菜单」显示的行数：0=不显示，1..2=显示对应行数（每行 6 个）。 */
    var homeQuickRows by mutableStateOf(
        prefs.getInt(KEY_HOME_QUICK_ROWS, HOME_QUICK_ROWS_DEFAULT).coerceIn(0, HOME_QUICK_ROWS_MAX),
    )
        private set
    val homeShowQuick: Boolean get() = homeQuickRows > 0

    fun updateHomeQuickRows(rows: Int) {
        homeQuickRows = rows.coerceIn(0, HOME_QUICK_ROWS_MAX)
        prefs.edit().putInt(KEY_HOME_QUICK_ROWS, homeQuickRows).apply()
    }

    /** 快捷菜单里已添加的功能（有序）；超出所选行数的不显示但保留。 */
    var homeQuickItems by mutableStateOf(parseQuickMenu(prefs.getString(KEY_HOME_QUICK_ITEMS, null)))
        private set

    fun updateHomeQuickItems(items: List<MenuAction>) {
        val fixed = items.distinct().take(HOME_QUICK_COLS * HOME_QUICK_ROWS_MAX)
        homeQuickItems = fixed
        prefs.edit().putString(KEY_HOME_QUICK_ITEMS, fixed.joinToString(",") { it.id }).apply()
    }

    /** 首页内容块的显示顺序（含隐藏项的位置），长按拖动后持久化。 */
    var homeOrder by mutableStateOf(parseHomeOrder(prefs.getString(KEY_HOME_ORDER, null)))
        private set

    fun updateHomeOrder(order: List<Int>) {
        val fixed = parseHomeOrder(order.joinToString(","))
        homeOrder = fixed
        prefs.edit().putString(KEY_HOME_ORDER, fixed.joinToString(",")).apply()
    }

    /** 首页只剩一张卡片可见时，是否把它固定在底部（长按卡片标题行往下 / 往上拖动切换）。 */
    var homePinBottom by mutableStateOf(prefs.getBoolean(KEY_HOME_PIN_BOTTOM, false))
        private set

    fun updateHomePinBottom(pin: Boolean) {
        homePinBottom = pin
        prefs.edit().putBoolean(KEY_HOME_PIN_BOTTOM, pin).apply()
    }

    /** 阅读器排版：字号 sp。 */
    var readerFontSp by mutableIntStateOf(prefs.getInt(KEY_READER_FONT, READER_FONT_DEFAULT).coerceIn(READER_FONT_MIN, READER_FONT_MAX))
        private set

    /** 阅读器排版：行距，占字号的百分比（180 = 1.8 倍）。 */
    var readerLinePct by mutableIntStateOf(prefs.getInt(KEY_READER_LINE, READER_LINE_DEFAULT).coerceIn(READER_LINE_MIN, READER_LINE_MAX))
        private set

    /** 阅读器排版：左右页边距 dp。 */
    var readerPadH by mutableIntStateOf(prefs.getInt(KEY_READER_PAD_H, READER_PAD_H_DEFAULT).coerceIn(READER_PAD_H_MIN, READER_PAD_H_MAX))
        private set

    /** 阅读器排版：上下页边距 dp。 */
    var readerPadV by mutableIntStateOf(prefs.getInt(KEY_READER_PAD_V, READER_PAD_V_DEFAULT).coerceIn(READER_PAD_V_MIN, READER_PAD_V_MAX))
        private set

    /** 阅读器文字背景：[READER_THEME_AUTO] 跟随应用深浅色（浅色 = 纸质纹理、深色 = 深色），或 纸质纹理 / 护眼 / 浅色 / 深色。 */
    var readerTheme by mutableIntStateOf(prefs.getInt(KEY_READER_THEME, READER_THEME_AUTO).coerceIn(READER_THEME_AUTO, READER_THEME_DARK))
        private set

    /** 阅读器翻页方式，见 READER_PAGE_TURN_*。 */
    var readerPageTurn by mutableIntStateOf(
        prefs.getInt(KEY_READER_PAGE_TURN, READER_PAGE_TURN_SMOOTH)
            .coerceIn(READER_PAGE_TURN_VERTICAL, READER_PAGE_TURN_SIMULATED),
    )
        private set

    fun updateReaderPageTurn(v: Int) {
        readerPageTurn = v.coerceIn(READER_PAGE_TURN_VERTICAL, READER_PAGE_TURN_SIMULATED)
        prefs.edit().putInt(KEY_READER_PAGE_TURN, readerPageTurn).apply()
    }

    fun updateReaderTheme(v: Int) {
        readerTheme = v.coerceIn(READER_THEME_AUTO, READER_THEME_DARK)
        prefs.edit().putInt(KEY_READER_THEME, readerTheme).apply()
    }

    fun updateReaderFontSp(v: Int) {
        readerFontSp = v.coerceIn(READER_FONT_MIN, READER_FONT_MAX)
        prefs.edit().putInt(KEY_READER_FONT, readerFontSp).apply()
    }

    fun updateReaderLinePct(v: Int) {
        readerLinePct = v.coerceIn(READER_LINE_MIN, READER_LINE_MAX)
        prefs.edit().putInt(KEY_READER_LINE, readerLinePct).apply()
    }

    fun updateReaderPadH(v: Int) {
        readerPadH = v.coerceIn(READER_PAD_H_MIN, READER_PAD_H_MAX)
        prefs.edit().putInt(KEY_READER_PAD_H, readerPadH).apply()
    }

    fun updateReaderPadV(v: Int) {
        readerPadV = v.coerceIn(READER_PAD_V_MIN, READER_PAD_V_MAX)
        prefs.edit().putInt(KEY_READER_PAD_V, readerPadV).apply()
    }

    /** 阅读排版恢复默认。 */
    fun resetReaderLayout() {
        readerFontSp = READER_FONT_DEFAULT
        readerLinePct = READER_LINE_DEFAULT
        readerPadH = READER_PAD_H_DEFAULT
        readerPadV = READER_PAD_V_DEFAULT
        readerTheme = READER_THEME_AUTO
        readerPageTurn = READER_PAGE_TURN_SMOOTH
        prefs.edit().remove(KEY_READER_PAGE_TURN).remove(KEY_READER_THEME).remove(KEY_READER_FONT).remove(KEY_READER_LINE).remove(KEY_READER_PAD_H).remove(KEY_READER_PAD_V).apply()
    }

    /** 书架：true = 网格（默认），false = 列表。 */
    var bookshelfGrid by mutableStateOf(prefs.getBoolean(KEY_BOOKSHELF_GRID, true))
        private set

    /** 书架排序方式（[BookSort]，默认按添加时间）。 */
    var bookshelfSort by mutableStateOf(BookSort.fromId(prefs.getInt(KEY_BOOKSHELF_SORT, BookSort.ADDED.id)))
        private set

    /** 书架排序方向：true = 降序（默认；配合按添加时间 = 新书在前，与之前的顺序一致）。 */
    var bookshelfSortDesc by mutableStateOf(prefs.getBoolean(KEY_BOOKSHELF_SORT_DESC, true))
        private set

    fun updateBookshelfGrid(grid: Boolean) {
        bookshelfGrid = grid
        prefs.edit().putBoolean(KEY_BOOKSHELF_GRID, grid).apply()
    }

    fun updateBookshelfSort(sort: BookSort, desc: Boolean) {
        bookshelfSort = sort
        bookshelfSortDesc = desc
        prefs.edit().putInt(KEY_BOOKSHELF_SORT, sort.id).putBoolean(KEY_BOOKSHELF_SORT_DESC, desc).apply()
    }

    fun isHomeShown(kind: Int): Boolean = when (kind) {
        HOME_FAVORITES -> homeShowFavorites
        HOME_GROUPS -> homeShowGroups
        HOME_QUICK -> homeShowQuick
        HOME_PRIVACY -> homeShowPrivacy
        HOME_RECENT -> homeShowRecent
        else -> homeShowDownloads
    }

    var homeMode by mutableStateOf(readHomeMode(context))
        private set
    var homeCustomUrl by mutableStateOf(readHomeCustomUrl(context))
        private set

    /** 导航栏样式：顶部 / 底部（默认）/ 始终最小化 / 固定在底部 / 固定在顶部 / 悬浮三段式。快速上扫 / 下扫底栏也会改这里 */
    var barPosition by mutableStateOf(prefs.getInt(PREF_BAR_POSITION, BAR_POS_BOTTOM).coerceIn(BAR_POS_TOP, BAR_POS_SAFARI_BOTTOM))
        private set

    fun updateBarPosition(pos: Int) {
        barPosition = pos.coerceIn(BAR_POS_TOP, BAR_POS_SAFARI_BOTTOM)
        prefs.edit().putInt(PREF_BAR_POSITION, barPosition).apply()
    }

    fun updateHomeMode(mode: Int) {
        homeMode = mode.coerceIn(HOME_MODE_DEFAULT, HOME_MODE_CUSTOM)
        prefs.edit().putInt(PREF_HOME_MODE, homeMode).apply()
    }

    fun updateHomeCustomUrl(url: String) {
        homeCustomUrl = url.trim()
        prefs.edit().putString(PREF_HOME_CUSTOM_URL, homeCustomUrl).apply()
    }

    fun updateHomeShow(kind: Int, show: Boolean) {
        val e = prefs.edit()
        when (kind) {
            HOME_FAVORITES -> updateHomeFavRows(if (!show) 0 else if (homeFavRows == 0) HOME_FAV_ROWS_DEFAULT else homeFavRows)
            HOME_GROUPS -> updateHomeGroupRows(if (!show) 0 else if (homeGroupRows == 0) HOME_GROUP_ROWS_DEFAULT else homeGroupRows)
            HOME_QUICK -> updateHomeQuickRows(if (!show) 0 else if (homeQuickRows == 0) HOME_QUICK_ROWS_DEFAULT else homeQuickRows)
            HOME_PRIVACY -> { homeShowPrivacy = show; e.putBoolean(KEY_HOME_PRIVACY, show) }
            HOME_RECENT -> { homeShowRecent = show; e.putBoolean(KEY_HOME_RECENT, show) }
            HOME_DOWNLOADS -> { homeShowDownloads = show; e.putBoolean(KEY_HOME_DOWNLOADS, show) }
        }
        e.apply()
    }

    /** 用户点了“忽略此版本”的版本号；只有出现比它更新的版本才会再次提示 */
    var ignoredUpdate by mutableStateOf(prefs.getString(KEY_IGNORED_UPDATE, "") ?: "")
        private set

    fun updateIgnoredUpdate(version: String) {
        ignoredUpdate = version
        prefs.edit().putString(KEY_IGNORED_UPDATE, version).apply()
    }

    /**
     * 更新通道：0=lite 1=gecko 2=canary-lite 3=canary-gecko 4=sync_lite 5=canary-sync_lite（见 [UpdateChannel]）。
     * 用新 key 存储：旧版本的 update_channel（0=stable 1=dev 2=gecko）含义不同，不沿用。
     *
     * 通道要与用户实际安装的变体同步：同时记下「保存通道时安装的变体」，启动时发现已装变体变了
     * （用户换装了别的变体）或没有记录（旧版本升级上来，当时的默认值不看变体），就把通道改到已装变体上
     * （保留 canary / 正式的选择），避免 lite 用户收到 gecko 推送、sync_lite 用户收到 lite 推送。
     */
    var updateChannel by mutableStateOf(loadUpdateChannel())
        private set

    private fun loadUpdateChannel(): Int {
        val installed = UpdateChannel.installedFlavor()
        val stored = if (prefs.contains(KEY_UPDATE_CHANNEL)) {
            prefs.getInt(KEY_UPDATE_CHANNEL, UpdateChannel.default()).coerceIn(0, UpdateChannel.NAMES.lastIndex)
        } else null
        val recorded = prefs.getString(KEY_UPDATE_CHANNEL_FLAVOR, null)
        val channel = when {
            stored == null -> UpdateChannel.default()
            recorded == installed -> stored
            else -> UpdateChannel.forInstalled(UpdateChannel.isCanary(stored))
        }
        if (stored != channel || recorded != installed) {
            prefs.edit()
                .putInt(KEY_UPDATE_CHANNEL, channel)
                .putString(KEY_UPDATE_CHANNEL_FLAVOR, installed)
                .apply()
        }
        return channel
    }

    fun updateUpdateChannel(channel: Int) {
        updateChannel = channel.coerceIn(0, UpdateChannel.NAMES.lastIndex)
        prefs.edit()
            .putInt(KEY_UPDATE_CHANNEL, updateChannel)
            .putString(KEY_UPDATE_CHANNEL_FLAVOR, UpdateChannel.installedFlavor())
            .apply()
    }

    /**
     * 浏览器内核：0 = 系统 WebView，1 = GeckoView（Nightly）。
     * 只影响「之后新建」的标签页；已打开的标签保持创建时的内核，直到关闭。
     */
    var browserEngine by mutableStateOf(prefs.getInt(KEY_ENGINE, ENGINE_GECKO).coerceIn(ENGINE_WEBVIEW, ENGINE_GECKO))
        private set

    /** 当前是否处于 Gecko 模式：选了 Gecko 且本机架构支持（不支持时新标签会自动退回 WebView） */
    val geckoMode: Boolean
        get() = browserEngine == ENGINE_GECKO && EngineSupport.geckoAvailable()

    fun updateBrowserEngine(engine: Int) {
        browserEngine = engine.coerceIn(ENGINE_WEBVIEW, ENGINE_GECKO)
        prefs.edit().putInt(KEY_ENGINE, browserEngine).apply()
        syncMediaFeatures() // 网页增强总开关只在 WebView 内核下约束媒体功能
    }

    /** 切到 Gecko 前的状态快照：去广告 / DNT / 禁 WebRTC / 指纹保护（网页增强与脚本总开关两种内核通用，不动） */
    fun suspendForGecko() {
        if (!prefs.getBoolean("gk_saved", false)) {
            val fpOn = AdBlock.fpCanvas || AdBlock.fpWebgl || AdBlock.fpAudio || AdBlock.fpFonts
            prefs.edit().putBoolean("gk_saved", true)
                .putBoolean("gk_adblock", AdBlock.enabled).putBoolean("gk_dnt", sendDnt)
                .putBoolean("gk_webrtc", webrtcDisabled).putBoolean("gk_fp", fpOn).apply()
        }
        AdBlock.updateEnabled(false)
        updateSendDnt(false)
        updateWebrtcDisabled(false)
        AdBlock.setFingerprintMaster(false)
    }

    /** 切回 WebView：恢复为切换前的状态（没有快照则全部开启） */
    fun resumeFromGecko() {
        val has = prefs.getBoolean("gk_saved", false)
        fun b(k: String) = !has || prefs.getBoolean(k, true)
        AdBlock.updateEnabled(b("gk_adblock"))
        updateSendDnt(b("gk_dnt"))
        updateWebrtcDisabled(b("gk_webrtc"))
        AdBlock.setFingerprintMaster(b("gk_fp"))
        prefs.edit().remove("gk_saved").apply()
    }

    /** 下载线程数：4/8/16/32/64 */
    var downloadThreads by mutableStateOf(prefs.getInt(KEY_DL_THREADS, 4).let { if (it in DOWNLOAD_THREAD_OPTIONS) it else 4 })
        private set

    fun updateDownloadThreads(threads: Int) {
        downloadThreads = if (threads in DOWNLOAD_THREAD_OPTIONS) threads else 4
        Downloader.segments = downloadThreads
        prefs.edit().putInt(KEY_DL_THREADS, downloadThreads).apply()
    }

    /** 多线程下载总开关：默认开启 */
    var downloadMultiThread by mutableStateOf(prefs.getBoolean(KEY_DL_MULTI, true))
        private set

    fun updateDownloadMultiThread(enabled: Boolean) {
        downloadMultiThread = enabled
        Downloader.multiThreadEnabled = enabled
        prefs.edit().putBoolean(KEY_DL_MULTI, enabled).apply()
    }

    /** 多线程阈值：文件超过该大小（MB）才使用多线程下载，默认 200 MB */
    var downloadMultiThreadMinMb by mutableStateOf(
        prefs.getInt(KEY_DL_MIN_MB, DOWNLOAD_MIN_MB_DEFAULT).let { if (it in DOWNLOAD_MIN_MB_OPTIONS) it else DOWNLOAD_MIN_MB_DEFAULT },
    )
        private set

    fun updateDownloadMultiThreadMinMb(mb: Int) {
        downloadMultiThreadMinMb = if (mb in DOWNLOAD_MIN_MB_OPTIONS) mb else DOWNLOAD_MIN_MB_DEFAULT
        Downloader.segmentMinBytes = downloadMultiThreadMinMb * 1024L * 1024L
        prefs.edit().putInt(KEY_DL_MIN_MB, downloadMultiThreadMinMb).apply()
    }

    /** m3u8 下载完成后把 TS 转封装成 MP4：默认开启 */
    var hlsToMp4 by mutableStateOf(prefs.getBoolean(KEY_DL_HLS_MP4, true))
        private set

    fun updateHlsToMp4(enabled: Boolean) {
        hlsToMp4 = enabled
        Downloader.hlsToMp4 = enabled
        prefs.edit().putBoolean(KEY_DL_HLS_MP4, enabled).apply()
    }

    /** 外部下载器：空 = 内置；"chooser" = 每次询问；其余为包名 */
    var externalDownloader by mutableStateOf(prefs.getString(KEY_DL_EXTERNAL, "") ?: "")
        private set

    fun updateExternalDownloader(value: String) {
        externalDownloader = value
        ExternalDownloader.target = value
        prefs.edit().putString(KEY_DL_EXTERNAL, value).apply()
    }

    /** 删除下载记录时「同时删除本地文件」的勾选状态：勾选一次一直生效，直到下次取消勾选 */
    var downloadDeleteLocal by mutableStateOf(prefs.getBoolean(KEY_DL_DELETE_LOCAL, false))
        private set

    fun updateDownloadDeleteLocal(value: Boolean) {
        downloadDeleteLocal = value
        prefs.edit().putBoolean(KEY_DL_DELETE_LOCAL, value).apply()
    }

    init {
        ExternalDownloader.target = externalDownloader
        // 启动时把已保存的下载配置推给下载器
        Downloader.segments = downloadThreads
        Downloader.multiThreadEnabled = downloadMultiThread
        Downloader.hlsToMp4 = hlsToMp4
        Downloader.segmentMinBytes = downloadMultiThreadMinMb * 1024L * 1024L
    }

    /**
     * 用户选定的应用图标：0=默认 1=多彩 2=立体 3=深色 4=极简 5=极暗（对应 AppIconManager.ICONS）。
     * 桌面上实际显示的图标还要按深 / 浅色换算，见 [AppIconManager.effective]。
     */
    var appIcon by mutableStateOf(prefs.getInt(KEY_APP_ICON, 0).coerceIn(0, 5))
        private set

    fun updateAppIcon(index: Int) {
        appIcon = index.coerceIn(0, 5)
        prefs.edit().putInt(KEY_APP_ICON, appIcon).apply()
    }

    /** 外部链接打开方式：0=应用内打开 1=交给其他浏览器（询问） */
    var linkOpenMode by mutableStateOf(prefs.getInt(KEY_LINK_MODE, 0))
        private set

    fun updateLinkOpenMode(mode: Int) {
        linkOpenMode = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LINK_MODE, linkOpenMode).apply()
    }

    /** 地址栏显示：0=网站标题 1=主域名 2=完整域名 */
    var addressDisplay by mutableStateOf(prefs.getInt(KEY_ADDR, 1))
        private set

    /** 标签面板样式：0=文字列表 1=卡片 */
    var tabsStyle by mutableStateOf(prefs.getInt(KEY_TABS, 0))
        private set

    fun updateAddressDisplay(mode: Int) {
        addressDisplay = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_ADDR, addressDisplay).apply()
    }

    fun updateTabsStyle(style: Int) {
        tabsStyle = style.coerceIn(0, 1)
        prefs.edit().putInt(KEY_TABS, tabsStyle).apply()
    }

    /**
     * 搜索引擎：下标 0 until SEARCH_ENGINES.size 是内置预设，之后是用户添加并固化下来的自定义引擎（[customEngines]，按添加顺序）。
     * 自定义引擎的地址模板用 %s 代表关键词。
     */
    var searchIndex by mutableStateOf(prefs.getInt(KEY_SEARCH, 0))
        private set

    /** 用户添加的自定义引擎（持久化为 JSON） */
    val customEngines = androidx.compose.runtime.mutableStateListOf<SearchEngine>().apply {
        addAll(parseCustomEngines(prefs.getString(KEY_SEARCH_LIST, "").orEmpty()))
        // 旧版只有一个「自定义模板」：迁移成列表里的第一项（旧的 searchIndex == 预设数 正好指向它）
        val legacy = prefs.getString(KEY_SEARCH_CUSTOM, "").orEmpty().trim()
        if (isEmpty() && legacy.isNotBlank()) {
            add(SearchEngine(tr("自定义"), legacy, searchIconUrlFor(legacy)))
            prefs.edit().putString(KEY_SEARCH_LIST, serializeCustomEngines(this)).remove(KEY_SEARCH_CUSTOM).apply()
        }
    }

    /** 预设 + 自定义，选择弹窗和设置摘要都按这个列表的下标走 */
    val allSearchEngines: List<SearchEngine>
        get() = SEARCH_ENGINES + customEngines

    val searchTemplate: String
        get() = allSearchEngines.getOrNull(searchIndex)?.template ?: SEARCH_ENGINES[0].template

    fun updateSearch(index: Int) {
        searchIndex = index
        prefs.edit().putInt(KEY_SEARCH, index).apply()
    }

    /**
     * 添加自定义引擎并选中。[address] 必须含 %s；没有 %s 但以 = 结尾（如 https://x.com/s?q=）时自动补上。
     * 返回 false 表示名称为空或地址不合法。
     */
    fun addCustomEngine(name: String, address: String): Boolean {
        val n = name.trim()
        var a = address.trim()
        if (n.isEmpty() || a.isEmpty()) return false
        if (!a.contains("%s")) {
            if (a.endsWith("=")) a += "%s" else return false
        }
        if (!a.startsWith("http://", true) && !a.startsWith("https://", true)) a = "https://$a"
        customEngines.add(SearchEngine(n, a, searchIconUrlFor(a)))
        prefs.edit().putString(KEY_SEARCH_LIST, serializeCustomEngines(customEngines)).apply()
        updateSearch(SEARCH_ENGINES.size + customEngines.size - 1)
        return true
    }

    /** 删除一个自定义引擎（[index] 是 [allSearchEngines] 里的下标）；当前选中的被删时回到第一个预设 */
    fun removeCustomEngine(index: Int) {
        val k = index - SEARCH_ENGINES.size
        if (k !in customEngines.indices) return
        customEngines.removeAt(k)
        prefs.edit().putString(KEY_SEARCH_LIST, serializeCustomEngines(customEngines)).apply()
        when {
            searchIndex == index -> updateSearch(0)
            searchIndex > index -> updateSearch(searchIndex - 1)
        }
    }

    /** 0=跟随系统 1=浅色 2=深色 */
    var themeMode by mutableStateOf(prefs.getInt(KEY_THEME, 0))
        private set

    /** 主页壁纸（content:// 或空） */
    var wallpaper by mutableStateOf(prefs.getString(KEY_WALL, "").orEmpty())
        private set

    /** 壁纸模糊 */
    var wallpaperBlur by mutableStateOf(prefs.getBoolean(KEY_WALL_BLUR, false))
        private set

    /** 全局应用：壁纸同时作为设置 / 下载 / 书签历史等二级页面的背景（玻璃控件也从它取样） */
    var wallpaperGlobal by mutableStateOf(prefs.getBoolean(KEY_WALL_GLOBAL, false))
        private set

    /** 「设为默认浏览器」的首次询问是否已经处理过（选了设置 / 取消 / 点外面关掉都算）；之后不再自动弹，只能从 设置 → 高级 进入 */
    var defaultBrowserPrompted by mutableStateOf(prefs.getBoolean(KEY_DEFAULT_BROWSER_PROMPTED, false))
        private set

    fun markDefaultBrowserPrompted() {
        defaultBrowserPrompted = true
        prefs.edit().putBoolean(KEY_DEFAULT_BROWSER_PROMPTED, true).apply()
    }

    /**
     * 已看完（或跳过）的引导页版本。
     *
     * 标记存在独立的 SharedPreferences（[ONBOARDING_PREFS]）里，而不是 mafari_settings：备份恢复会按「除书签外全部覆盖」
     * 重写 mafari_settings，标记随之丢失，已经看过引导的人就会被再弹一次。独立文件不在备份范围内，恢复不会动它。
     * 旧版本写在 mafari_settings 里的标记（只要看过就 >= 1）仍然认，并迁移过来。
     */
    private val onboardingPrefs = context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)

    var onboardingVersion by mutableIntStateOf(
        maxOf(onboardingPrefs.getInt(KEY_ONBOARDING, 0), prefs.getInt(KEY_ONBOARDING, 0)),
    )
        private set

    val onboardingPending: Boolean get() = onboardingVersion < ONBOARDING_VERSION

    /** 完成或跳过引导。引导里已经带了「设为默认浏览器」这一步，旧的首次询问弹窗不必再弹。 */
    fun markOnboardingDone() {
        onboardingVersion = ONBOARDING_VERSION
        // commit：进程马上被杀时 apply 可能来不及落盘，下次启动又会弹
        onboardingPrefs.edit().putInt(KEY_ONBOARDING, ONBOARDING_VERSION).commit()
        markDefaultBrowserPrompted()
    }

    fun updateWallpaperBlur(enabled: Boolean) {
        wallpaperBlur = enabled
        prefs.edit().putBoolean(KEY_WALL_BLUR, enabled).apply()
    }

    fun updateWallpaperGlobal(enabled: Boolean) {
        wallpaperGlobal = enabled
        prefs.edit().putBoolean(KEY_WALL_GLOBAL, enabled).apply()
    }

    /** 空串表示跟随系统 WebView 默认 UA */
    var userAgent by mutableStateOf(prefs.getString(KEY_UA, "").orEmpty())
        private set

    fun updateThemeMode(mode: Int) {
        themeMode = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_THEME, themeMode).apply()
    }

    fun updateWallpaper(uri: String) {
        wallpaper = uri
        // 换壁纸后旧的模糊缓存作废（同一个 uri 内容也可能变）
        WallpaperBlurCache.clear(appContext)
        wallpaperVersion++
        prefs.edit().putString(KEY_WALL, uri).apply()
    }

    /** 套用一个柔光模板。 */
    fun applyGlassPreset(index: Int) {
        val preset = GlassPresets.ALL.getOrNull(index) ?: return
        glassParams = preset.params
        glassPreset = index
        saveGlass()
    }

    /** 拖滑条时实时改参数（只改内存，松手再 [commitGlassParams] 落盘），模板标记变为「自定义」；手动改参数即退出「miuix 默认」。 */
    fun updateGlassParams(params: GlassParams) {
        glassParams = params.copy(upstreamDefaults = false)
        glassPreset = -1
    }

    fun commitGlassParams() = saveGlass()

    private fun saveGlass() {
        prefs.edit().putString(KEY_GLASS_PARAMS, glassParams.encode()).putInt(KEY_GLASS_PRESET, glassPreset).apply()
    }

    /** 套用一个液态模板。 */
    fun applyLiquidPreset(index: Int) {
        val preset = LiquidPresets.ALL.getOrNull(index) ?: return
        liquidParams = preset.params
        liquidPreset = index
        saveLiquid()
    }

    /** 拖滑条时实时改参数（只改内存，松手再 [commitLiquidParams] 落盘），模板标记变为「自定义」。 */
    fun updateLiquidParams(params: LiquidParams) {
        liquidParams = params
        liquidPreset = -1
    }

    fun commitLiquidParams() = saveLiquid()

    private fun saveLiquid() {
        prefs.edit().putString(KEY_LIQUID_PARAMS, liquidParams.encode()).putInt(KEY_LIQUID_PRESET, liquidPreset).apply()
    }

    fun updateBlurMode(mode: Int) {
        blurMode = mode.coerceIn(BLUR_MIUIX, BLUR_FROST)
        prefs.edit().putInt(KEY_BLUR_MODE, blurMode).apply()
    }

    /** 网页 window.open / target=_blank 打开的同站链接是否新开标签页（默认关：在当前标签打开） */
    /**
     * 重启（冷启动）时标签页怎么处理：
     *  0 = 恢复为退出前的状态：标签列表原样回来，并回到当时正在看的那个标签页（默认）
     *  1 = 恢复标签并打开新标签页：标签列表回来，但不跳转，落在一个新的空白标签页上
     *  2 = 不恢复：丢掉之前的标签，直接打开一个新标签页
     * 老版本是一个开关（restore_tabs_on_launch）：开 → 0，关 → 1（关闭时就是「标签保留、落在空白页」）。
     */
    var restoreTabsMode by mutableIntStateOf(
        prefs.getInt(KEY_RESTORE_MODE, if (prefs.getBoolean(KEY_RESTORE_TABS, true)) RestoreMode.ALL else RestoreMode.KEEP_NEW),
    )
        private set

    fun updateRestoreTabsMode(mode: Int) {
        restoreTabsMode = mode.coerceIn(RestoreMode.ALL, RestoreMode.NONE)
        prefs.edit().putInt(KEY_RESTORE_MODE, restoreTabsMode).apply()
    }

    /** 网页 window.open / target=_blank 打开的同站链接是否新开标签页（默认关：在当前标签打开） */
    var sameSiteNewTab by mutableStateOf(prefs.getBoolean(KEY_SAME_SITE_TAB, false))
        private set

    fun updateSameSiteNewTab(enabled: Boolean) {
        sameSiteNewTab = enabled
        prefs.edit().putBoolean(KEY_SAME_SITE_TAB, enabled).apply()
    }

    /** 网页增强：油猴脚本、去广告规则、长按标记广告 */
    var pageEnhancements by mutableStateOf(prefs.getBoolean(KEY_ENHANCE, true))
        private set

    fun updatePageEnhancements(enabled: Boolean) {
        pageEnhancements = enabled
        prefs.edit().putBoolean(KEY_ENHANCE, enabled).apply()
        EngineSupport.notePageEnhancements(enabled)
        syncMediaFeatures()
    }

    /** 手动标记广告：长按网页元素时是否出现「标记为广告」（需要网页增强开启，WebView 内核） */
    var adMarkLongPress by mutableStateOf(prefs.getBoolean(KEY_AD_MARK_LONG_PRESS, true))
        private set

    fun updateAdMarkLongPress(enabled: Boolean) {
        adMarkLongPress = enabled
        prefs.edit().putBoolean(KEY_AD_MARK_LONG_PRESS, enabled).apply()
    }

    /** 退出时是否处于无痕模式：冷启动据此直接落在一个空白无痕标签上（无痕标签的页面本身不落盘） */
    var incognitoActive by mutableStateOf(prefs.getBoolean(KEY_INCOGNITO_ACTIVE, false))
        private set

    fun updateIncognitoActive(active: Boolean) {
        if (incognitoActive == active) return
        incognitoActive = active
        prefs.edit().putBoolean(KEY_INCOGNITO_ACTIVE, active).apply()
    }

    /** 启用范围：油猴脚本是否在无痕浏览 / 轻应用（桌面快捷方式）中运行 */
    var scriptsInIncognito by mutableStateOf(prefs.getBoolean(KEY_SCRIPTS_INCOGNITO, true))
        private set

    fun updateScriptsInIncognito(enabled: Boolean) {
        scriptsInIncognito = enabled
        prefs.edit().putBoolean(KEY_SCRIPTS_INCOGNITO, enabled).apply()
    }

    var scriptsInWebApp by mutableStateOf(prefs.getBoolean(KEY_SCRIPTS_WEBAPP, true))
        private set

    fun updateScriptsInWebApp(enabled: Boolean) {
        scriptsInWebApp = enabled
        prefs.edit().putBoolean(KEY_SCRIPTS_WEBAPP, enabled).apply()
    }

    /** 启用范围：去广告（请求拦截 + 元素隐藏）是否在无痕浏览 / 轻应用中生效 */
    var adblockInIncognito by mutableStateOf(prefs.getBoolean(KEY_ADBLOCK_INCOGNITO, true))
        private set

    fun updateAdblockInIncognito(enabled: Boolean) {
        adblockInIncognito = enabled
        prefs.edit().putBoolean(KEY_ADBLOCK_INCOGNITO, enabled).apply()
    }

    var adblockInWebApp by mutableStateOf(prefs.getBoolean(KEY_ADBLOCK_WEBAPP, true))
        private set

    fun updateAdblockInWebApp(enabled: Boolean) {
        adblockInWebApp = enabled
        prefs.edit().putBoolean(KEY_ADBLOCK_WEBAPP, enabled).apply()
    }

    /** 脚本一键启停：只影响油猴脚本，不影响去广告 / 长按标记 */
    var scriptsEnabled by mutableStateOf(prefs.getBoolean(KEY_SCRIPTS, true))
        private set

    // ---- 页内翻译 ----

    /** 翻译源（[TranslateSourceId]）；不认识的值按默认源处理 */
    var translateSource by mutableStateOf(
        prefs.getString(KEY_TR_SOURCE, null)?.takeIf { it in TranslateSourceId.ALL } ?: TranslateSourceId.DEFAULT,
    )
        private set

    /** 目标语言；第一次使用时按系统语言挑 */
    var translateTarget by mutableStateOf(
        prefs.getString(KEY_TR_TARGET, null)?.let { TranslateLangs.normalize(it) }
            ?: java.util.Locale.getDefault().let { TranslateLangs.fromLocale(it.language, it.country) },
    )
        private set

    /** 自定义翻译源（LibreTranslate 兼容）的服务地址 / Key */
    var translateCustomUrl by mutableStateOf(prefs.getString(KEY_TR_URL, "").orEmpty())
        private set
    var translateCustomKey by mutableStateOf(prefs.getString(KEY_TR_KEY, "").orEmpty())
        private set

    fun updateTranslate(
        source: String = translateSource,
        target: String = translateTarget,
        customUrl: String = translateCustomUrl,
        customKey: String = translateCustomKey,
    ) {
        translateSource = source.takeIf { it in TranslateSourceId.ALL } ?: TranslateSourceId.DEFAULT
        translateTarget = TranslateLangs.normalize(target)
        translateCustomUrl = customUrl.trim()
        translateCustomKey = customKey.trim()
        prefs.edit()
            .putString(KEY_TR_SOURCE, translateSource)
            .putString(KEY_TR_TARGET, translateTarget)
            .putString(KEY_TR_URL, translateCustomUrl)
            .putString(KEY_TR_KEY, translateCustomKey)
            .apply()
    }

    /** 「始终翻译此页面」：勾选过的页面键（见 [translatePageKey]），在这些页面加载完成后自动翻译 */
    var alwaysTranslatePages by mutableStateOf(prefs.getStringSet(KEY_TR_ALWAYS, null)?.toSet().orEmpty())
        private set

    /**
     * 「始终翻译此页面」的页面键：http(s) 地址去掉 #片段 和 ?查询，主机名小写，末尾斜杠忽略。
     * 想改成按整个网站（主机名）生效，只需把这里改成返回 host。
     */
    fun translatePageKey(url: String): String? {
        val u = runCatching { android.net.Uri.parse(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (u.port > 0) ":${u.port}" else ""
        val path = (u.encodedPath ?: "").trimEnd('/')
        return "$scheme://$host$port$path"
    }

    fun isAlwaysTranslate(url: String): Boolean {
        val key = translatePageKey(url) ?: return false
        return key in alwaysTranslatePages
    }

    fun setAlwaysTranslate(url: String, on: Boolean) {
        val key = translatePageKey(url) ?: return
        val next = if (on) alwaysTranslatePages + key else alwaysTranslatePages - key
        if (next == alwaysTranslatePages) return
        alwaysTranslatePages = next
        prefs.edit().putStringSet(KEY_TR_ALWAYS, next).apply()
    }

    /** 当前设置的快照：一次翻译进行中改设置不影响它 */
    fun translateConfig() = TranslateConfig(translateSource, translateTarget, translateCustomUrl, translateCustomKey)

    fun updateScriptsEnabled(enabled: Boolean) {
        scriptsEnabled = enabled
        prefs.edit().putBoolean(KEY_SCRIPTS, enabled).apply()
    }

    fun updateUserAgent(value: String) {
        userAgent = value.trim()
        prefs.edit().putString(KEY_UA, userAgent).apply()
    }

    /** 发送 Do Not Track 请求头 */
    var sendDnt by mutableStateOf(prefs.getBoolean(KEY_DNT, false))
        private set

    fun updateSendDnt(enabled: Boolean) {
        sendDnt = enabled
        prefs.edit().putBoolean(KEY_DNT, enabled).apply()
    }

    /** 嗅探媒体播放：发现 m3u8 / mp4 等地址时弹出提示，用原生播放器打开（见 sniff/MediaSniff）。设置 → 媒体嗅探与播放 */
    var mediaSniff by mutableStateOf(prefs.getBoolean(KEY_SNIFF, true))
        private set

    fun updateMediaSniff(enabled: Boolean) {
        mediaSniff = enabled
        prefs.edit().putBoolean(KEY_SNIFF, enabled).apply()
        syncMediaFeatures()
    }

    /** 媒体嗅探与播放总开关：一键停用嗅探、悬浮播放脚本、自动画中画、脚本宿主能力 */
    var mediaMaster by mutableStateOf(prefs.getBoolean(KEY_MEDIA_MASTER, true))
        private set

    fun updateMediaMaster(enabled: Boolean) {
        mediaMaster = enabled
        prefs.edit().putBoolean(KEY_MEDIA_MASTER, enabled).apply()
        syncMediaFeatures()
    }

    /**
     * 「媒体嗅探与播放」免责声明是否已确认（从此版本起，打开该功能前必须先在弹窗里确认一次）。
     * 未确认时：打开嗅探列表 / 打开总开关都会先弹出声明；自动悬浮播放不会自动拉起播放器。
     */
    var mediaDisclaimerAccepted by mutableStateOf(prefs.getBoolean(KEY_MEDIA_DISCLAIMER, false))
        private set

    fun acceptMediaDisclaimer() {
        mediaDisclaimerAccepted = true
        prefs.edit().putBoolean(KEY_MEDIA_DISCLAIMER, true).apply()
    }

    /** 退出（按 Home / 手势返回桌面）时，全屏播放中的视频自动进入画中画 */
    var mediaAutoPip by mutableStateOf(prefs.getBoolean(KEY_MEDIA_AUTO_PIP, true))
        private set

    fun updateMediaAutoPip(enabled: Boolean) {
        mediaAutoPip = enabled
        prefs.edit().putBoolean(KEY_MEDIA_AUTO_PIP, enabled).apply()
        syncMediaFeatures()
    }

    /** 自动悬浮播放：嗅探到正片后自动用原生悬浮播放器打开（默认关）；无论开关，都不会主动弹「发现视频」提示 */
    var mediaAutoFloat by mutableStateOf(prefs.getBoolean(KEY_MEDIA_AUTO_FLOAT, false))
        private set

    fun updateMediaAutoFloat(enabled: Boolean) {
        mediaAutoFloat = enabled
        prefs.edit().putBoolean(KEY_MEDIA_AUTO_FLOAT, enabled).apply()
        syncMediaFeatures()
    }

    /** 本页整体是否在工作：总开关开，且（WebView 内核下）网页增强开 */
    val mediaActive: Boolean
        get() = mediaMaster && (geckoMode || pageEnhancements)

    /** 嗅探是否真正生效（弹提示的地方读这个，而不是 [mediaSniff]） */
    val mediaSniffActive: Boolean get() = mediaActive && mediaSniff

    /** 自动悬浮播放是否真正生效（需要嗅探本身生效） */
    val mediaAutoFloatActive: Boolean get() = mediaSniffActive && mediaAutoFloat && mediaDisclaimerAccepted

    // ---- 原生播放器（读写同一份 prefs，播放页不构造 AppSettings 直接读 PlayerPrefs）----

    /** 视频渲染方式：0 = TextureView（支持玻璃模糊），1 = SurfaceView（更省电、低延迟，无玻璃模糊） */
    var playerRenderMode by mutableStateOf(dev.dsh.miuixbrowser.player.PlayerPrefs.clampRender(prefs.getInt(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_RENDER, 0)))
        private set

    fun updatePlayerRenderMode(mode: Int) {
        playerRenderMode = dev.dsh.miuixbrowser.player.PlayerPrefs.clampRender(mode)
        prefs.edit().putInt(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_RENDER, playerRenderMode).apply()
    }

    /** 默认倍速：每次打开播放器按这个速度起播 */
    var playerDefaultSpeed by mutableStateOf(dev.dsh.miuixbrowser.player.PlayerPrefs.snap(prefs.getFloat(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_SPEED, 1f), dev.dsh.miuixbrowser.player.PlayerPrefs.SPEEDS, 1f))
        private set

    fun updatePlayerDefaultSpeed(v: Float) {
        playerDefaultSpeed = dev.dsh.miuixbrowser.player.PlayerPrefs.snap(v, dev.dsh.miuixbrowser.player.PlayerPrefs.SPEEDS, 1f)
        prefs.edit().putFloat(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_SPEED, playerDefaultSpeed).apply()
    }

    /** 长按画面时的临时倍速（松手恢复） */
    var playerLongPressSpeed by mutableStateOf(dev.dsh.miuixbrowser.player.PlayerPrefs.snap(prefs.getFloat(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_LONG_PRESS, 2f), dev.dsh.miuixbrowser.player.PlayerPrefs.LONG_PRESS_SPEEDS, 2f))
        private set

    fun updatePlayerLongPressSpeed(v: Float) {
        playerLongPressSpeed = dev.dsh.miuixbrowser.player.PlayerPrefs.snap(v, dev.dsh.miuixbrowser.player.PlayerPrefs.LONG_PRESS_SPEEDS, 2f)
        prefs.edit().putFloat(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_LONG_PRESS, playerLongPressSpeed).apply()
    }

    /** 快捷菜单：悬浮播放器卡片下方的一排按钮（投屏 / 外部播放 / 下载 / 截屏 / 倍速 / 清晰度 / 视频源）总开关 */
    var playerQuickMenu by mutableStateOf(prefs.getBoolean(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_QUICK_MENU, true))
        private set

    fun updatePlayerQuickMenu(on: Boolean) {
        playerQuickMenu = on
        prefs.edit().putBoolean(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_QUICK_MENU, on).apply()
    }

    /** 快捷菜单里每个按钮各自的开关（id 见 PlayerPrefs.QUICK_IDS） */
    val playerQuickItems = androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
        dev.dsh.miuixbrowser.player.PlayerPrefs.QUICK_IDS.forEach { put(it, prefs.getBoolean(dev.dsh.miuixbrowser.player.PlayerPrefs.quickKey(it), true)) }
    }

    fun updatePlayerQuickItem(id: String, on: Boolean) {
        playerQuickItems[id] = on
        prefs.edit().putBoolean(dev.dsh.miuixbrowser.player.PlayerPrefs.quickKey(id), on).apply()
    }

    /** 后台播放：切到后台 / 锁屏继续播放（前台服务 + 媒体通知）；关闭后离开界面就暂停 */
    var playerBgPlay by mutableStateOf(dev.dsh.miuixbrowser.player.PlayerPrefs.bgPlay(context))
        private set

    fun updatePlayerBgPlay(on: Boolean) {
        playerBgPlay = on
        prefs.edit().putBoolean(dev.dsh.miuixbrowser.player.PlayerPrefs.KEY_BG_PLAY, on).apply()
    }

    // ---- Media3 高级选项（取值见 PlayerTuning；下次打开播放器生效）----

    /** 下拉类选项的当前下标（键见 PlayerTuning.CHOICE_SIZES） */
    val playerAdvChoices = androidx.compose.runtime.mutableStateMapOf<String, Int>().apply {
        val t = dev.dsh.miuixbrowser.player.PlayerTuning
        t.CHOICE_SIZES.forEach { (k, size) -> put(k, t.clamp(prefs.getInt(k, t.choiceDefault(k)), size)) }
    }

    /** 开关类选项的当前值（键见 PlayerTuning.SWITCH_DEFAULTS） */
    val playerAdvFlags = androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
        dev.dsh.miuixbrowser.player.PlayerTuning.SWITCH_DEFAULTS.forEach { (k, def) -> put(k, prefs.getBoolean(k, def)) }
    }

    fun updatePlayerAdvChoice(key: String, index: Int) {
        val t = dev.dsh.miuixbrowser.player.PlayerTuning
        val size = t.CHOICE_SIZES[key] ?: return
        val v = t.clamp(index, size)
        playerAdvChoices[key] = v
        prefs.edit().putInt(key, v).apply()
    }

    fun updatePlayerAdvFlag(key: String, on: Boolean) {
        if (key !in dev.dsh.miuixbrowser.player.PlayerTuning.SWITCH_DEFAULTS) return
        playerAdvFlags[key] = on
        prefs.edit().putBoolean(key, on).apply()
    }

    /** 全部 Media3 高级选项恢复默认（删掉键，读取时自然回到默认值） */
    fun resetPlayerAdvanced() {
        val t = dev.dsh.miuixbrowser.player.PlayerTuning
        prefs.edit().apply { t.ALL_KEYS.forEach { remove(it) } }.apply()
        t.CHOICE_SIZES.keys.forEach { playerAdvChoices[it] = t.choiceDefault(it) }
        t.SWITCH_DEFAULTS.forEach { (k, def) -> playerAdvFlags[k] = def }
    }

    /** 把设置值和两层总开关合成「生效状态」推给热路径（请求拦截 / MediaHost），并通知 Gecko 后台页 */
    fun syncMediaFeatures() {
        val f = dev.dsh.miuixbrowser.sniff.MediaFeatures
        f.master = mediaMaster
        f.pageEnhancements = pageEnhancements
        f.enhancementsApply = !geckoMode
        f.sniffSwitch = mediaSniff
        f.autoPipSwitch = mediaAutoPip
        f.autoFloatSwitch = mediaAutoFloat
        dev.dsh.miuixbrowser.sniff.MediaSniff.enabled = f.sniffActive
        // Gecko：后台页按开关装 / 卸 onHeadersReceived 监听
        runCatching { dev.dsh.miuixbrowser.engine.gecko.GeckoGm.sendSniffRules() }
    }

    init { syncMediaFeatures() }

    /** 禁用 WebRTC（防 IP 泄露） */
    var webrtcDisabled by mutableStateOf(prefs.getBoolean(KEY_WEBRTC, false))
        private set

    fun updateWebrtcDisabled(enabled: Boolean) {
        webrtcDisabled = enabled
        prefs.edit().putBoolean(KEY_WEBRTC, enabled).apply()
    }

    /**
     * Cookie 接受策略（两个内核共用）：0 允许全部 / 1 拒绝跟踪者 / 2 拒绝第三方 / 3 拒绝全部。
     * 旧版「第三方 Cookie」开关（0 允许 / 1 拒绝）迁移为 0 / 2。
     */
    var cookieMode by mutableStateOf(
        if (prefs.contains(KEY_COOKIE_MODE)) prefs.getInt(KEY_COOKIE_MODE, 0).coerceIn(0, 3)
        else if (prefs.getInt(KEY_3P_COOKIE, 0) == 1) 2 else 0,
    )
        private set

    fun updateCookieMode(mode: Int) {
        cookieMode = mode.coerceIn(0, 3)
        prefs.edit().putInt(KEY_COOKIE_MODE, cookieMode).apply()
    }


    /** 启动时清理：0 不清理 / 1 清除 Cookie / 2 清除 Cookie 与网站存储 */
    var cookieClearOnLaunch by mutableStateOf(prefs.getInt(KEY_COOKIE_CLEAR, 0).coerceIn(0, 2))
        private set

    fun updateCookieClearOnLaunch(mode: Int) {
        cookieClearOnLaunch = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_COOKIE_CLEAR, cookieClearOnLaunch).apply()
    }

    /** 网页权限：摄像头 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var cameraPermission by mutableStateOf(prefs.getInt(KEY_CAM, 0))
        private set

    fun updateCameraPermission(mode: Int) {
        cameraPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_CAM, cameraPermission).apply()
    }

    /** 网页权限：麦克风 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var micPermission by mutableStateOf(prefs.getInt(KEY_MIC, 0))
        private set

    fun updateMicPermission(mode: Int) {
        micPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_MIC, micPermission).apply()
    }

    /** 网页权限：定位 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var locationPermission by mutableStateOf(prefs.getInt(KEY_LOC, 0))
        private set

    fun updateLocationPermission(mode: Int) {
        locationPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LOC, locationPermission).apply()
    }

    /** 网页权限：剪贴板读写 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var clipboardPermission by mutableStateOf(prefs.getInt(KEY_CLIP, 0))
        private set

    fun updateClipboardPermission(mode: Int) {
        clipboardPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_CLIP, clipboardPermission).apply()
    }

    /** 网页权限：网站媒体发声/自动播放 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var mediaAutoplay by mutableStateOf(prefs.getInt(KEY_MEDIA, 0))
        private set

    fun updateMediaAutoplay(mode: Int) {
        mediaAutoplay = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_MEDIA, mediaAutoplay).apply()
    }

    /** 网页权限：网站通知 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var notificationPermission by mutableStateOf(prefs.getInt(KEY_NOTIFY, 0))
        private set

    fun updateNotificationPermission(mode: Int) {
        notificationPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_NOTIFY, notificationPermission).apply()
    }

    /**
     * 网页权限：网页重定向 0=每次使用时询问 1=总是允许 2=总是拒绝
     * 指网页在没有用户操作的情况下自动跳转到其他站点（JS / meta refresh），以及自动唤起其他应用。
     */
    var redirectPermission by mutableStateOf(prefs.getInt(KEY_REDIRECT, 0))
        private set

    fun updateRedirectPermission(mode: Int) {
        redirectPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_REDIRECT, redirectPermission).apply()
    }

    /**
     * 网页权限：打开其他应用 0=每次使用时询问 1=总是允许 2=总是拒绝
     * 指网页唤起其他应用（intent:// weixin:// alipays:// 等外部协议），无论点击还是自动唤起都按此权限处理。
     * 原先与「网页重定向」共用一个开关，现在独立；旧版本升级上来时沿用当时的「网页重定向」取值，行为不变。
     */
    var externalAppPermission by mutableStateOf(
        if (prefs.contains(KEY_EXTERNAL_APP)) prefs.getInt(KEY_EXTERNAL_APP, 0) else prefs.getInt(KEY_REDIRECT, 0),
    )
        private set

    fun updateExternalAppPermission(mode: Int) {
        externalAppPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_EXTERNAL_APP, externalAppPermission).apply()
    }

    /** 启用「请勿追踪」类隐私增强：合并 DNT + 禁第三方跟踪脚本提示 */
    var blockTrackingScripts by mutableStateOf(prefs.getBoolean(KEY_TRACK, true))
        private set

    fun updateBlockTrackingScripts(enabled: Boolean) {
        blockTrackingScripts = enabled
        prefs.edit().putBoolean(KEY_TRACK, blockTrackingScripts).apply()
    }

    /** 代理开关（WebView ProxyController，进程内全部标签） */
    var proxyEnabled by mutableStateOf(prefs.getBoolean(KEY_PROXY_ON, false))
        private set

    /** 0 = HTTP，1 = SOCKS5 */
    var proxyType by mutableStateOf(prefs.getInt(KEY_PROXY_TYPE, 0).coerceIn(0, 1))
        private set

    var proxyHost by mutableStateOf(prefs.getString(KEY_PROXY_HOST, "").orEmpty())
        private set

    var proxyPort by mutableStateOf(prefs.getInt(KEY_PROXY_PORT, 0))
        private set

    /** 代理认证（HTTP 挑战时提交；SOCKS5 官方 ProxyConfig 不支持，由本机 Socks5Relay 代为向上游认证） */
    var proxyAuth by mutableStateOf(prefs.getBoolean(KEY_PROXY_AUTH, false))
        private set

    var proxyUser by mutableStateOf(prefs.getString(KEY_PROXY_USER, "").orEmpty())
        private set

    var proxyPass by mutableStateOf(prefs.getString(KEY_PROXY_PASS, "").orEmpty())
        private set

    fun updateProxy(
        enabled: Boolean,
        type: Int,
        host: String,
        port: Int,
        auth: Boolean = proxyAuth,
        user: String = proxyUser,
        pass: String = proxyPass,
    ) {
        proxyEnabled = enabled
        proxyType = type.coerceIn(0, 1)
        proxyHost = host.trim()
        proxyPort = port.coerceIn(0, 65535)
        proxyAuth = auth
        proxyUser = user
        proxyPass = pass
        prefs.edit()
            .putBoolean(KEY_PROXY_ON, proxyEnabled)
            .putInt(KEY_PROXY_TYPE, proxyType)
            .putString(KEY_PROXY_HOST, proxyHost)
            .putInt(KEY_PROXY_PORT, proxyPort)
            .putBoolean(KEY_PROXY_AUTH, proxyAuth)
            .putString(KEY_PROXY_USER, proxyUser)
            .putString(KEY_PROXY_PASS, proxyPass)
            .apply()
        WebViewProxy.applyFrom(this, webView = !geckoMode || EngineSupport.webViewTouched)
    }

    private companion object {
        const val KEY_BLUR = "blur"
        const val KEY_BLUR_MODE = "blur_mode"
        const val KEY_GLASS_PARAMS = "glass_params"
        const val KEY_GLASS_PRESET = "glass_preset"
        const val KEY_GLASS_PRESET_VER = "glass_preset_ver"
        const val KEY_LIQUID_PARAMS = "liquid_params"
        const val KEY_LIQUID_PRESET = "liquid_preset"
        const val KEY_HOME_FAV = "home_show_favorites"
        const val KEY_HOME_FAV_ROWS = "home_fav_rows"
        const val KEY_HOME_FAV_GRID = "home_fav_grid"
        const val KEY_HOME_LOGO = "home_show_logo"
        const val KEY_HOME_PRIVACY = "home_show_privacy"
        const val KEY_HOME_RECENT = "home_show_recent"
        const val KEY_HOME_DOWNLOADS = "home_show_downloads"
        const val KEY_HOME_GROUPS = "home_show_groups"
        const val KEY_HOME_GROUP_ROWS = "home_group_rows"
        const val KEY_HOME_QUICK_ROWS = "home_quick_rows"
        const val KEY_HOME_QUICK_ITEMS = "home_quick_items"
        const val KEY_HOME_ORDER = "home_order"
        const val KEY_HOME_PIN_BOTTOM = "home_pin_bottom"
        const val KEY_READER_FONT = "reader_font_sp"
        const val KEY_READER_LINE = "reader_line_pct"
        const val KEY_READER_PAD_H = "reader_pad_h"
        const val KEY_READER_PAD_V = "reader_pad_v"
        const val KEY_READER_THEME = "reader_theme"
        const val KEY_READER_PAGE_TURN = "reader_page_turn"
        const val KEY_BOOKSHELF_GRID = "bookshelf_grid"
        const val KEY_BOOKSHELF_SORT = "bookshelf_sort"
        const val KEY_BOOKSHELF_SORT_DESC = "bookshelf_sort_desc"
        const val KEY_SAME_SITE_TAB = "same_site_new_tab"
        const val KEY_RESTORE_TABS = "restore_tabs_on_launch"
        const val KEY_RESTORE_MODE = "restore_tabs_mode"
        const val KEY_APP_ICON = "app_icon"
        const val KEY_IGNORED_UPDATE = "ignored_update"
        const val KEY_UPDATE_CHANNEL = "update_channel_v2" // 旧 "update_channel" 含义不同（stable/dev/gecko），已弃用
        const val KEY_UPDATE_CHANNEL_FLAVOR = "update_channel_installed_flavor" // 保存通道时安装的变体，用于发现换装
        const val KEY_ENGINE = "browser_engine"
        const val KEY_DL_THREADS = "download_threads"
        const val KEY_DL_MIN_MB = "download_multithread_min_mb"
        const val KEY_DL_MULTI = "download_multithread_enabled"
        const val KEY_DL_EXTERNAL = "download_external_app"
        const val KEY_DL_HLS_MP4 = "download_hls_to_mp4"
        const val KEY_DL_DELETE_LOCAL = "download_delete_local_file"
        const val KEY_UA = "ua"
        const val KEY_THEME = "theme_mode"
        const val KEY_SEARCH = "search_index"
        const val KEY_ADDR = "address_display"
        const val KEY_LINK_MODE = "link_open_mode"
        const val KEY_TABS = "tabs_style"
        const val KEY_SEARCH_CUSTOM = "search_custom"
        const val KEY_SEARCH_LIST = "search_custom_list"
        const val KEY_WALL = "wallpaper"
        const val KEY_WALL_BLUR = "wallpaper_blur"
        const val KEY_WALL_GLOBAL = "wallpaper_global"
        const val KEY_DEFAULT_BROWSER_PROMPTED = "default_browser_prompted"
        const val KEY_ONBOARDING = "onboarding_version"
        const val KEY_ENHANCE = "page_enhancements"
        const val KEY_AD_MARK_LONG_PRESS = "ad_mark_long_press"
        const val KEY_SCRIPTS = "scripts_enabled"
        const val KEY_SCRIPTS_INCOGNITO = "scripts_in_incognito"
        const val KEY_INCOGNITO_ACTIVE = "incognito_active"
        const val KEY_SCRIPTS_WEBAPP = "scripts_in_webapp"
        const val KEY_ADBLOCK_INCOGNITO = "adblock_in_incognito"
        const val KEY_ADBLOCK_WEBAPP = "adblock_in_webapp"
        const val KEY_DNT = "send_dnt"
        const val KEY_SNIFF = "media_sniff"
        const val KEY_MEDIA_MASTER = "media_master"
        const val KEY_MEDIA_DISCLAIMER = "media_disclaimer_accepted"
        const val KEY_MEDIA_AUTO_PIP = "media_auto_pip"
        const val KEY_MEDIA_AUTO_FLOAT = "media_auto_float"
        const val KEY_WEBRTC = "webrtc_disabled"
        const val KEY_3P_COOKIE = "third_party_cookie"
        const val KEY_COOKIE_MODE = "cookie_mode"
        const val KEY_COOKIE_CLEAR = "cookie_clear_on_launch"
        const val KEY_CAM = "perm_camera"
        const val KEY_MIC = "perm_mic"
        const val KEY_LOC = "perm_location"
        const val KEY_CLIP = "perm_clipboard"
        const val KEY_MEDIA = "perm_media_autoplay"
        const val KEY_NOTIFY = "perm_notification"
        const val KEY_REDIRECT = "perm_redirect"
        const val KEY_EXTERNAL_APP = "perm_external_app"
        const val KEY_TRACK = "block_tracking"
        const val KEY_PROXY_ON = "proxy_enabled"
        const val KEY_PROXY_TYPE = "proxy_type"
        const val KEY_PROXY_HOST = "proxy_host"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_PROXY_AUTH = "proxy_auth"
        const val KEY_PROXY_USER = "proxy_user"
        const val KEY_PROXY_PASS = "proxy_pass"
        const val KEY_TR_SOURCE = "translate_source"
        const val KEY_TR_TARGET = "translate_target"
        const val KEY_TR_URL = "translate_custom_url"
        const val KEY_TR_KEY = "translate_custom_key"
        const val KEY_TR_ALWAYS = "translate_always_pages"
    }
}

data class SearchEngine(val name: String, val template: String, val iconUrl: String = "")

/** 自定义引擎的图标：取模板所在站点的 favicon（拉取失败时界面用占位色块） */
fun searchIconUrlFor(template: String): String = runCatching {
    val u = java.net.URI(template.replace("%s", "x"))
    if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) "" else "${u.scheme}://${u.host}/favicon.ico"
}.getOrDefault("")

private fun serializeCustomEngines(list: List<SearchEngine>): String {
    val arr = org.json.JSONArray()
    list.forEach { arr.put(org.json.JSONObject().put("name", it.name).put("template", it.template).put("icon", it.iconUrl)) }
    return arr.toString()
}

private fun parseCustomEngines(json: String): List<SearchEngine> = runCatching {
    if (json.isBlank()) return emptyList()
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).mapNotNull {
        val o = arr.optJSONObject(it) ?: return@mapNotNull null
        val name = o.optString("name"); val tpl = o.optString("template")
        if (name.isBlank() || tpl.isBlank()) null else SearchEngine(name, tpl, o.optString("icon"))
    }
}.getOrDefault(emptyList())

val SEARCH_ENGINES = listOf(
    SearchEngine(tr("百度"), "https://www.baidu.com/s?wd=%s", "https://www.baidu.com/favicon.ico"),
    SearchEngine(tr("必应"), "https://www.bing.com/search?q=%s", "https://www.bing.com/favicon.ico"),
    SearchEngine("Google", "https://www.google.com/search?q=%s", "https://www.google.com/favicon.ico"),
    SearchEngine("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/favicon.ico"),
)

data class UaPreset(val name: String, val value: String, val iconUrls: List<String> = emptyList())

/** 常用 UA 预设 */
val UA_PRESETS = listOf(
    UaPreset(tr("跟随系统"), ""),
    UaPreset(
        "Android Chrome",
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        listOf(
            "https://www.android.com/favicon.ico",
            "https://www.google.com/s2/favicons?sz=128&domain_url=https://www.android.com/",
            "https://icons.duckduckgo.com/ip3/www.android.com.ico",
        ),
    ),
    UaPreset(
        tr("桌面 Chrome"),
        DESKTOP_UA,
        listOf(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e1/Google_Chrome_icon_%28February_2022%29.svg/128px-Google_Chrome_icon_%28February_2022%29.svg.png",
            "https://www.google.com/chrome/static/images/favicons/favicon-96x96.png",
        ),
    ),
    UaPreset(
        "iPhone Safari",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
        listOf(
            "https://www.apple.com/favicon.ico",
            "https://www.google.com/s2/favicons?sz=128&domain_url=https://www.apple.com/",
            "https://icons.duckduckgo.com/ip3/www.apple.com.ico",
        ),
    ),
)

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
