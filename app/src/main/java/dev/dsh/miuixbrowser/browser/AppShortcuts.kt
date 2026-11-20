package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.MainActivity
import dev.dsh.miuixbrowser.R
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.util.Diag
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * 长按应用图标弹出的快捷方式：搜索、设置、扫一扫、书签、历史。
 *
 * 用动态快捷方式而不是 res/xml 里的静态快捷方式：静态的 targetPackage 写死包名，而 Gecko / WebView 各变体的包名带后缀；
 * 动态的 Intent 用 [MainActivity] 的组件创建，包名自动正确，标签文字也能跟着应用内语言（[tr]）走。
 * 每次冷启动界面出来后重新发布一次（语言 / 变体变了也能刷新），点击后由 [MainActivity] 取出 [EXTRA_ACTION] 交给 BrowserScreen 执行。
 */
object AppShortcuts {

    const val ACTION = "dev.dsh.miuixbrowser.action.APP_SHORTCUT"
    const val EXTRA_ACTION = "mafari_shortcut_action"

    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"

    private class Entry(val id: String, val label: String, val icon: Int)

    /** 发布（覆盖）动态快捷方式。主线程调用即可，只有一次很轻的系统调用。 */
    fun publish(context: Context) {
        val app = context.applicationContext
        // 排序即 rank：靠前的离图标更近 / 排在更前面
        val entries = listOf(
            Entry(SEARCH, tr("搜索"), R.drawable.ic_shortcut_search),
            Entry(SETTINGS, tr("设置"), R.drawable.ic_shortcut_settings),
            Entry(SCAN, tr("扫一扫"), R.drawable.ic_shortcut_scan),
            Entry(BOOKMARKS, tr("书签"), R.drawable.ic_shortcut_bookmark),
            Entry(HISTORY, tr("历史"), R.drawable.ic_shortcut_history),
        )
        val list = entries.mapIndexed { index, e ->
            ShortcutInfoCompat.Builder(app, "app_${e.id}")
                .setShortLabel(e.label)
                .setLongLabel(e.label)
                .setIcon(IconCompat.createWithResource(app, e.icon))
                .setRank(index)
                .setIntent(
                    Intent(app, MainActivity::class.java)
                        .setAction(ACTION)
                        .putExtra(EXTRA_ACTION, e.id),
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, list) }
            .onFailure { Diag.log("app shortcuts: publish failed ${it.message}") }
    }
}
