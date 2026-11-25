/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.i18n.tr
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 自定义标签页（[dev.dsh.miuixbrowser.CustomTabActivity]）的底栏：直接复用浏览器的悬浮三段式 [SafariSplitBar]，
 * 左边圆钮 = 返回（没有历史就关闭）、中间 = 地址栏、右边圆钮 = 在 Mafari 中打开。
 *
 * 所有动画（弹簧宽度、按压形变、磁力、加载小点）都来自 [SafariSplitBar] 本身，这里只做三件事：
 * 1. 把 Compose 主题 / 玻璃参数配成和主界面一致（Activity 不是 setContent 起的，要自己包一层）；
 * 2. 同样按 [SafariSplitScale] 放大；
 * 3. 把栏的实际高度（含导航栏留白）报给 Activity，让网页在栏上面结束。
 *
 * 网页是 View 体系里的 WebView，不在 Compose 采集层里，没法给玻璃取样，
 * 所以走和主界面 Gecko 网页态一样的回退（渐变底板 + 描边）。
 */
@Composable
internal fun CustomTabBar(
    settings: AppSettings,
    address: String,
    loading: Boolean,
    onBack: () -> Unit,
    onOpenInMafari: () -> Unit,
    onRefresh: () -> Unit,
    onHeightChanged: (Int) -> Unit,
) {
    val mode = when (settings.themeMode) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    // isSystemInDarkTheme 只是让系统深浅色切换时重组
    isSystemInDarkTheme()
    val controller = remember(mode) { ThemeController(mode) }
    MiuixTheme(controller = controller) {
        GlassLook.update(settings.blurMode, settings.glassParams)
        CompositionLocalProvider(
            LocalBlurMode provides settings.blurMode,
            LocalGlassParams provides settings.glassParams,
            LocalLiquidParams provides settings.liquidParams,
        ) {
            val metrics = BarStyles.SafariSplit.metrics
            val baseDensity = LocalDensity.current
            val backdrop = rememberLayerBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 底部让开手势条；软键盘弹出时根布局已经按键盘高度让位，这里不重复留白
                    .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                    .onSizeChanged { onHeightChanged(it.height) },
            ) {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density * SafariSplitScale,
                        fontScale = baseDensity.fontScale,
                    ),
                ) {
                    SafariSplitBar(
                        metrics = metrics.scaledBy(1f / SafariSplitScale),
                        backdrop = backdrop,
                        liquid = null,
                        blurEnabled = settings.blurEnabled,
                        textureBlurEnabled = false,
                        address = address,
                        loading = loading,
                        atHome = false,
                        // 返回始终可点：没有历史时 = 关闭自定义标签页
                        canGoBack = true,
                        editing = false,
                        compact = false,
                        onExpand = {},
                        onChange = {},
                        onGo = {},
                        onFocusLost = {},
                        onAddressTap = {},
                        readerVisible = false,
                        readerActive = false,
                        onReader = {},
                        scriptsRunning = false,
                        onScripts = {},
                        download = null,
                        onDownload = {},
                        translateVisible = false,
                        translateOriginal = false,
                        onTranslateToggle = {},
                        onBack = onBack,
                        onMore = onOpenInMafari,
                        onRefresh = onRefresh,
                        onHome = {},
                        onSwitchTab = {},
                        onOpenTabs = {},
                        endIcon = AppIcons.OpenExternal,
                        endDescription = tr("在 Mafari 中打开"),
                        gesturesEnabled = false,
                    )
                }
            }
        }
    }
}
