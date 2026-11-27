/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.data.Favorite
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.ui.AppIcons
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import dev.dsh.miuixbrowser.ui.icons.WorldClock
import top.yukonga.miuix.kmp.icon.glass.Translate
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.glass.Share
import dev.dsh.miuixbrowser.ui.icons.Settings
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.Hide
import top.yukonga.miuix.kmp.icon.glass.GridView
import dev.dsh.miuixbrowser.ui.icons.Favorites
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Copy
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Add

enum class MenuAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
) {
    NewTab("new_tab", tr("新建标签"), MiuixIcons.Glass.Add),
    Incognito("incognito", tr("无痕浏览"), MiuixIcons.Glass.Hide),
    Desktop("desktop", tr("桌面版"), MiuixIcons.Glass.GridView),
    Find("find", tr("页内查找"), MiuixIcons.Glass.Search),
    Translate("translate", tr("翻译"), MiuixIcons.Glass.Translate),
    Favorite("favorite", tr("收藏"), MiuixIcons.Glass.Favorites),
    Bookmarks("bookmarks", tr("书签"), AppIcons.Bookmark),
    Share("share", tr("分享"), MiuixIcons.Glass.Share),
    Refresh("refresh", tr("刷新"), MiuixIcons.Glass.Refresh),
    CopyLink("copy", tr("复制链接"), MiuixIcons.Glass.Copy),
    External("external", tr("外部打开"), MiuixIcons.Glass.Link),
    AddToDesktop("send_to_desktop", tr("添加到桌面"), AppIcons.AddToDesktop),
    Settings("settings", tr("设置"), MiuixIcons.Glass.Settings),
    Home("home", tr("主页"), MiuixIcons.Home),
    Downloads("downloads", tr("下载"), MiuixIcons.Glass.Download),
    Scan("scan", tr("扫一扫"), AppIcons.Scan),
    GeckoExtensions("gecko_extensions", tr("拓展"), AppIcons.Puzzle),
    ScriptMenu("script_menu", tr("脚本"), AppIcons.Script),
    History("history", tr("历史"), MiuixIcons.Glass.WorldClock),
    Bookshelf("bookshelf", tr("书架"), AppIcons.Bookshelf),

    /** 网络请求（两个内核）：以弹窗列出本页发出的请求域名，一键屏蔽 / 放行 */
    Requests("requests", tr("网络请求"), AppIcons.Globe),
    CloseTab("close", tr("关闭标签"), MiuixIcons.Glass.Close),

    /** 清理缓存：效果同「设置 → 清理缓存」 */
    CleanCache("clean_cache", tr("清理缓存"), AppIcons.CleanCache),

    /** 同步：一键把加密备份上传到所有 WebDAV 服务器 / Git 仓库 */
    Sync("sync", tr("同步"), AppIcons.SyncCloud),

    /** 去广告开关（仅 WebView 内核）：开启时图标变红，角标是本页拦截数 */
    AdBlock("adblock", tr("去广告"), AppIcons.AdShield),

    /** Firefox 账户（仅带 Firefox 同步的版本）：点开进入「Firefox账户同步」，登录后图标换成账号头像 */
    Account("account", tr("账户"), AppIcons.Account),
}

/** 菜单「同步」是否进行中：更多菜单和首页快捷菜单共用，两处的图标 / 文字一起变化 */
object MenuSyncState {
    var syncing by androidx.compose.runtime.mutableStateOf(false)
}

/** 更多面板的图标顺序与隐藏项，可拖动排序 / 拖到下方隐藏并持久化 */
class MenuOrderStore(context: Context) {

    private val prefs = context.getSharedPreferences("mafari_menu", Context.MODE_PRIVATE)

    /** 菜单里显示的功能（有序） */
    val order = mutableStateListOf<MenuAction>()

    /** 已隐藏的功能 */
    val hidden = mutableStateListOf<MenuAction>()

    init {
        fun read(key: String): List<MenuAction> =
            prefs.getString(key, null)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                .mapNotNull { id -> MenuAction.entries.firstOrNull { it.id == id } }
        hidden.addAll(read(KEY_HIDDEN).filter { it != MenuAction.Settings }.distinct())
        val saved = read(KEY).filter { it !in hidden }.distinct()
        // 从未自定义过：用默认排序（收藏 / 历史 / 桌面版 / 设置 … 按面板里的位置）
        order.addAll(if (prefs.contains(KEY)) saved else DEFAULT_ORDER.filter { it !in hidden })
        // 新增的项补到末尾；「拓展」默认排在「脚本」后面（两种内核都有，WebView 为自研运行时）
        MenuAction.entries.forEach {
            if (it != MenuAction.GeckoExtensions && it != MenuAction.Account && it !in order && it !in hidden) order.add(it)
        }
        if (MenuAction.GeckoExtensions !in order && MenuAction.GeckoExtensions !in hidden) {
            val after = order.indexOf(MenuAction.ScriptMenu)
            order.add(if (after >= 0) after + 1 else order.size, MenuAction.GeckoExtensions)
        }
        // 「账户」默认紧跟在「设置」后面；不带 Firefox 同步的版本没有它（含旧偏好里残留的）
        if (EngineSupport.SYNC_BUNDLED) {
            if (MenuAction.Account !in order && MenuAction.Account !in hidden) {
                val afterSettings = order.indexOf(MenuAction.Settings)
                order.add(if (afterSettings >= 0) afterSettings + 1 else order.size, MenuAction.Account)
            }
        } else {
            order.remove(MenuAction.Account)
            hidden.remove(MenuAction.Account)
        }
    }

    /** 把 [action] 放到显示区 / 隐藏区的第 [toIndex] 位；「设置」不允许隐藏，避免找不到入口 */
    fun place(action: MenuAction, toHidden: Boolean, toIndex: Int) {
        if (toHidden && action == MenuAction.Settings) return
        val target = if (toHidden) hidden else order
        order.remove(action)
        hidden.remove(action)
        target.add(toIndex.coerceIn(0, target.size), action)
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putString(KEY, order.joinToString(",") { it.id })
            .putString(KEY_HIDDEN, hidden.joinToString(",") { it.id })
            .apply()
    }

    companion object {
        /** 默认排序（四列宫格，自上而下）：其余功能依次排在后面。 */
        val DEFAULT_ORDER = listOf(
            MenuAction.Favorite, MenuAction.History, MenuAction.Desktop, MenuAction.Settings,
            MenuAction.Account, MenuAction.Translate, MenuAction.Incognito, MenuAction.AddToDesktop, MenuAction.Downloads,
            MenuAction.Scan, MenuAction.Home, MenuAction.ScriptMenu, MenuAction.GeckoExtensions, MenuAction.Refresh,
            MenuAction.Bookmarks, MenuAction.Bookshelf,
        )

        private const val KEY = "order"
        private const val KEY_HIDDEN = "hidden"
    }
}

/** 首页「快捷菜单」：每行格数 / 最多行数 */
const val HOME_QUICK_COLS = 6
const val HOME_QUICK_ROWS_MAX = 2

/** 依赖当前网页的功能：首页上没有网页，放进快捷菜单点了也没有效果，所以不提供 */
private val QUICK_MENU_NEEDS_PAGE = setOf(
    MenuAction.Find, MenuAction.Translate, MenuAction.Favorite, MenuAction.Share, MenuAction.Refresh,
    MenuAction.CopyLink, MenuAction.External, MenuAction.AddToDesktop, MenuAction.Home, MenuAction.Requests,
)

/** 快捷菜单里可以添加的功能（与底部「更多」菜单同一套，去掉依赖网页的和本安装包没有的） */
fun quickMenuCandidates(): List<MenuAction> = MenuAction.entries.filter {
    it !in QUICK_MENU_NEEDS_PAGE &&
        (it != MenuAction.Account || EngineSupport.SYNC_BUNDLED)
}

/** 快捷菜单的默认内容（一行 6 个） */
val QUICK_MENU_DEFAULT: List<MenuAction> = listOf(
    MenuAction.NewTab, MenuAction.Bookmarks, MenuAction.History,
    MenuAction.Downloads, MenuAction.Scan, MenuAction.Settings,
)

/** 解析存下来的快捷菜单（功能 id，逗号分隔）；从没存过用默认，存过空串就是空；去掉失效 / 重复项 */
fun parseQuickMenu(raw: String?): List<MenuAction> {
    if (raw == null) return QUICK_MENU_DEFAULT
    val allowed = quickMenuCandidates()
    return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { id -> allowed.firstOrNull { it.id == id } }
        .distinct()
        .take(HOME_QUICK_COLS * HOME_QUICK_ROWS_MAX)
}
