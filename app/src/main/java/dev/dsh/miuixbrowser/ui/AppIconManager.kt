/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.R
import dev.dsh.miuixbrowser.appContainer
import dev.dsh.miuixbrowser.data.AppSettings
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes

/** 桌面图标组件。下标与 [AppIconManager.ICONS] 对应，alias 名与 AndroidManifest 里的 activity-alias 一致。 */
data class AppIconOption(
    val label: String,
    val alias: String,
    @DrawableRes val preview: Int,
)

object AppIconManager {

    /**
     * 全部桌面图标组件。前 [CHOICE_COUNT] 个是设置里可选的（浅色 / 主图标），
     * 后面三个是它们在深色下的配对图标，只在「跟随显示模式」时自动启用，不单独出现在选择列表里。
     */
    val ICONS = listOf(
        AppIconOption(tr("默认"), "IconDefaultV3", R.drawable.icon_preview_default),
        AppIconOption(tr("多彩"), "IconColorfulV2", R.drawable.icon_preview_colorful),
        AppIconOption(tr("极简"), "IconMinimalV2", R.drawable.icon_preview_minimal),
        AppIconOption(tr("经典"), "IconClassicV3", R.drawable.icon_preview_classic),
        AppIconOption(tr("深蓝"), "IconDeepBlueV3", R.drawable.icon_preview_deepblue),
        AppIconOption(tr("深色"), "IconDarkV2", R.drawable.icon_preview_dark),
        AppIconOption(tr("极暗"), "IconUltraDarkV2", R.drawable.icon_preview_ultradark),
    )

    // 下标常量（与 ICONS 对应；0..3 同时是 AppSettings.appIcon 的取值）
    const val DEFAULT = 0
    const val COLORFUL = 1
    const val MINIMAL = 2
    const val CLASSIC = 3
    const val DEEP_BLUE = 4
    const val DARK = 5
    const val ULTRA_DARK = 6

    /** 设置里可选的图标个数（ICONS 的前几个） */
    const val CHOICE_COUNT = 4

    /** 设置里展示的可选图标 */
    val CHOICES: List<AppIconOption> get() = ICONS.take(CHOICE_COUNT)

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

    /** 选定图标在深色下的配对图标；经典没有深色版，保持不变。 */
    fun darkPartner(choice: Int): Int = when (choice) {
        DEFAULT -> DEEP_BLUE
        COLORFUL -> DARK
        MINIMAL -> ULTRA_DARK
        else -> choice
    }

    /**
     * 用户选定的图标 [choice] 在当前深 / 浅色下实际应启用的图标（选定值本身不变，只换桌面上显示的那个）。
     * [follow] 关闭时始终显示所选图标；开启时深色下换成配对图标：默认→深蓝、多彩→深色、极简→极暗、经典→经典。
     */
    fun effective(choice: Int, dark: Boolean, follow: Boolean = true): Int {
        val c = choice.coerceIn(0, CHOICE_COUNT - 1)
        return if (follow && dark) darkPartner(c) else c
    }

    /** 按设置里选定的图标 + 当前深浅色，把桌面图标同步到应该显示的那个（已一致则不动）。 */
    fun sync(context: Context, settings: AppSettings = context.appContainer.settings) {
        val wanted = effective(settings.appIcon, isDark(context, settings.themeMode), settings.appIconFollowTheme)
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
        return if (idx >= 0) idx else DEFAULT
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
