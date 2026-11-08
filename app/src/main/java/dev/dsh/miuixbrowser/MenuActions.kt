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

enum class MenuAction(val id: String, val label: String, val icon: ImageVector) {
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
    ScriptMenu("script_menu", "脚本菜单", MiuixIcons.Glass.File),
    History("history", "历史", MiuixIcons.Glass.WorldClock),
    CloseTab("close", "关闭标签", MiuixIcons.Glass.Close),

    /** 只在 Gecko 标签下出现（见 [geckoOnly]）；放在最后，旧用户保存的顺序里没有它时会自动补在末尾 */
    GeckoExtensions("gecko_extensions", "拓展", AppIcons.Puzzle),
    ;

    /** 仅 Gecko 内核可用：WebView 标签下整个入口隐藏，切到 Gecko 标签时出现 */
    val geckoOnly: Boolean get() = this == GeckoExtensions

    /**
     * Gecko 下不支持的功能：返回 toast 里的替代方案提示（以逗号开头，可为空串）；null = Gecko 下可用。
     * 置灰与否由这里统一决定，对照 docs/GECKO.md 的适配矩阵。
     */
    val geckoUnsupportedHint: String?
        get() = when (this) {
            Extensions -> "，可在「拓展」安装 Tampermonkey / Violentmonkey 替代"
            ScriptMenu -> "，请使用拓展自带的菜单"
            else -> null
        }
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
        MenuAction.entries.forEach { if (it !in order && it !in hidden) order.add(it) }
    }

    /**
     * 把 [action] 放到显示区 / 隐藏区的第 [toIndex] 位；「设置」不允许隐藏，避免找不到入口。
     * [toIndex] 是界面上看到的序号：[skipped]（当前内核下不显示的入口，如 WebView 下的「拓展」）不计入，
     * 这里换算回含隐藏入口的真实下标。
     */
    fun place(action: MenuAction, toHidden: Boolean, toIndex: Int, skipped: Set<MenuAction> = emptySet()) {
        if (toHidden && action == MenuAction.Settings) return
        val target = if (toHidden) hidden else order
        order.remove(action)
        hidden.remove(action)
        var real = 0
        var seen = 0
        while (real < target.size && seen < toIndex) {
            if (target[real] !in skipped) seen++
            real++
        }
        target.add(real, action)
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
