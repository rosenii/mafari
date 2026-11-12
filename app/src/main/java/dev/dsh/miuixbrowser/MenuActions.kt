package dev.dsh.miuixbrowser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.WorldClock
import top.yukonga.miuix.kmp.icon.glass.Translate
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.glass.Share
import top.yukonga.miuix.kmp.icon.glass.Settings
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.Hide
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.Favorites
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Copy
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Add

enum class MenuAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
) {
    NewTab("new_tab", "新建标签", MiuixIcons.Glass.Add),
    Incognito("incognito", "无痕浏览", MiuixIcons.Glass.Hide),
    Desktop("desktop", "桌面版", MiuixIcons.Glass.GridView),
    Find("find", "页内查找", MiuixIcons.Glass.Search),
    Translate("translate", "翻译", MiuixIcons.Glass.Translate),
    Favorite("favorite", "收藏", MiuixIcons.Glass.Favorites),
    Bookmarks("bookmarks", "书签", AppIcons.Bookmark),
    Share("share", "分享", MiuixIcons.Glass.Share),
    Refresh("refresh", "刷新", MiuixIcons.Glass.Refresh),
    CopyLink("copy", "复制链接", MiuixIcons.Glass.Copy),
    External("external", "外部打开", MiuixIcons.Glass.Link),
    AddToDesktop("send_to_desktop", "添加到桌面", AppIcons.AddToDesktop),
    Settings("settings", "设置", MiuixIcons.Glass.Settings),
    Home("home", "主页", MiuixIcons.Home),
    Downloads("downloads", "下载", MiuixIcons.Glass.Download),
    GeckoExtensions("gecko_extensions", "拓展", AppIcons.Puzzle),
    ScriptMenu("script_menu", "脚本", AppIcons.Script),
    History("history", "历史", MiuixIcons.Glass.WorldClock),
    CloseTab("close", "关闭标签", MiuixIcons.Glass.Close),
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
        // 新增的项补到末尾；「拓展」默认排在「脚本」后面（WebView 下同样显示，只是置灰，见 BrowserScreen 的 lockedFor）
        MenuAction.entries.forEach {
            if (it != MenuAction.GeckoExtensions && it !in order && it !in hidden) order.add(it)
        }
        if (MenuAction.GeckoExtensions !in order && MenuAction.GeckoExtensions !in hidden) {
            val after = order.indexOf(MenuAction.ScriptMenu)
            order.add(if (after >= 0) after + 1 else order.size, MenuAction.GeckoExtensions)
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
            MenuAction.Translate, MenuAction.Incognito, MenuAction.AddToDesktop, MenuAction.Downloads,
            MenuAction.Home, MenuAction.ScriptMenu, MenuAction.GeckoExtensions, MenuAction.Refresh,
            MenuAction.Bookmarks,
        )

        private const val KEY = "order"
        private const val KEY_HIDDEN = "hidden"
    }
}
