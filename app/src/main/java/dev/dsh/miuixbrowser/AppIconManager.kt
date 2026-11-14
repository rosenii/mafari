package dev.dsh.miuixbrowser

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes

/** 可选的桌面图标。下标与 [AppSettings.appIcon] 对应，alias 名与 AndroidManifest 里的 activity-alias 一致。 */
data class AppIconOption(
    val label: String,
    val alias: String,
    @DrawableRes val preview: Int,
)

object AppIconManager {

    val ICONS = listOf(
        AppIconOption("默认", "IconDefaultV2", R.drawable.icon_preview_default),
        AppIconOption("多彩", "IconColorfulV2", R.drawable.icon_preview_colorful),
        AppIconOption("立体", "IconSolidV2", R.drawable.icon_preview_solid3d),
        AppIconOption("深色", "IconDarkV2", R.drawable.icon_preview_dark),
    )

    /** 启用选中的 alias、停用其余的。DONT_KILL_APP 避免系统直接杀进程。 */
    fun apply(context: Context, index: Int) {
        val pm = context.packageManager
        val target = index.coerceIn(0, ICONS.lastIndex)
        // 先启用目标再停用其他，保证任何时刻桌面上至少有一个入口
        ICONS.forEachIndexed { i, icon ->
            if (i == target) setState(pm, context, icon.alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        }
        ICONS.forEachIndexed { i, icon ->
            if (i != target) setState(pm, context, icon.alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }
    }

    /** 实际生效的是哪一个（以系统组件状态为准）；没有任何显式启用时为默认。 */
    fun current(context: Context): Int {
        val pm = context.packageManager
        val idx = ICONS.indexOfFirst { icon ->
            pm.getComponentEnabledSetting(component(context, icon.alias)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        return if (idx >= 0) idx else 0
    }

    // alias 在清单里以 ".Xxx" 声明，相对 namespace（与 applicationId 相同）
    private fun component(context: Context, alias: String) =
        ComponentName(context.packageName, "dev.dsh.miuixbrowser.$alias")

    private fun setState(pm: PackageManager, context: Context, alias: String, state: Int) {
        runCatching {
            pm.setComponentEnabledSetting(component(context, alias), state, PackageManager.DONT_KILL_APP)
        }
    }
}
