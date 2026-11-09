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
import top.yukonga.miuix.kmp.icon.glass.File
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Copy
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Add

enum class MenuAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** 仅 Gecko 模式显示的菜单项（切回 WebView 时自动收起） */
    val geckoOnly: Boolean = false,
) {
    NewTab("new_tab", "新建标签", MiuixIcons.Glass.Add),
    Incognito("incognito", "无痕浏览", MiuixIcons.Glass.Hide),
    Desktop("desktop", "桌面版", MiuixIcons.Glass.GridView),
    Find("find", "页内查找", MiuixIcons.Glass.Search),
    Translate("translate", "翻译", MiuixIcons.Glass.Translate),
    Favorite("favorite", "收藏", MiuixIcons.Glass.Favorites),
    Share("share", "分享", MiuixIcons.Glass.Share),
    Refresh("refresh", "刷新", MiuixIcons.Glass.Refresh),
    CopyLink("copy", "复制链接", MiuixIcons.Glass.Copy),
    External("external", "外部打开", MiuixIcons.Glass.Link),
    AddToDesktop("send_to_desktop", "添加到桌面", AppIcons.AddToDesktop),
    Settings("settings", "设置", MiuixIcons.Glass.Settings),
    Home("home", "主页", MiuixIcons.Home),
    Downloads("downloads", "下载", MiuixIcons.Glass.Download),
    Extensions("extensions", "脚本", AppIcons.Script),
    GeckoExtensions("gecko_extensions", "拓展", AppIcons.Puzzle, geckoOnly = true),
    ScriptMenu("script_menu", "脚本菜单", MiuixIcons.Glass.File),
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
        order.addAll(read(KEY).filter { it !in hidden }.distinct())
        // geckoOnly 的项只在 Gecko 模式出现：初始化时先去掉，[syncGecko] 再按当前模式加回
        listOf(order, hidden).forEach { list -> list.removeAll { it.geckoOnly } }
        MenuAction.entries.forEach { if (!it.geckoOnly && it !in order && it !in hidden) order.add(it) }
    }

    /**
     * 切到 Gecko 时「拓展」出现在「脚本」后面（上次用户把它拖进隐藏区则保持隐藏），切回 WebView 时收起。
     * 只改内存里的列表、不落盘：这样用户上次的排列不会因为临时切回 WebView 而丢失。
     */
    fun syncGecko(geckoMode: Boolean) {
        MenuAction.entries.filter { it.geckoOnly }.forEach { action ->
            if (!geckoMode) {
                order.remove(action)
                hidden.remove(action)
            } else if (action !in order && action !in hidden) {
                if (prefs.getString(KEY_HIDDEN, null)?.split(",")?.contains(action.id) == true) {
                    hidden.add(action)
                } else {
                    val after = order.indexOf(MenuAction.Extensions)
                    order.add(if (after >= 0) after + 1 else order.size, action)
                }
            }
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

    private companion object {
        const val KEY = "order"
        const val KEY_HIDDEN = "hidden"
    }
}
