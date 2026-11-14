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
        AppIconOption("极简", "IconMinimalV2", R.drawable.icon_preview_minimal),
        AppIconOption("极暗", "IconUltraDarkV2", R.drawable.icon_preview_ultradark),
    )

    // 下标常量（与 ICONS、AppSettings.appIcon 对应）
    const val DEFAULT = 0
    const val COLORFUL = 1
    const val SOLID = 2
    const val DARK = 3
    const val MINIMAL = 4
    const val ULTRA_DARK = 5

    /**
     * 当前是否按深色显示：显示模式 1=浅色 2=深色，0=跟随系统（读系统 uiMode）。
     */
    fun isDark(context: Context, themeMode: Int): Boolean = when (themeMode) {
        1 -> false
        2 -> true
        else ->
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 用户选定的图标 [choice] 在深 / 浅色下实际应启用的图标（选定值本身不变，只换桌面上显示的那个）：
     * - 极简 / 极暗：浅色 → 极简，深色 → 极暗
     * - 多彩 / 立体：深色 → 深色图标，浅色 → 保持所选
     * - 深色图标：深色 → 深色图标，浅色 → 默认图标
     * - 默认：不跟随
     */
    fun effective(choice: Int, dark: Boolean): Int = when (choice) {
        COLORFUL, SOLID -> if (dark) DARK else choice
        DARK -> if (dark) DARK else DEFAULT
        MINIMAL, ULTRA_DARK -> if (dark) ULTRA_DARK else MINIMAL
        else -> DEFAULT
    }

    /** 按设置里选定的图标 + 当前深浅色，把桌面图标同步到应该显示的那个（已一致则不动）。 */
    fun sync(context: Context, settings: AppSettings = AppSettings(context)) {
        val wanted = effective(settings.appIcon, isDark(context, settings.themeMode))
        if (current(context) != wanted) apply(context, wanted)
    }

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
