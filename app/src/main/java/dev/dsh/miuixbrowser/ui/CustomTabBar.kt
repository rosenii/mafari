/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.os.Build
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.i18n.tr
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 自定义标签页（[dev.dsh.miuixbrowser.CustomTabActivity]）的整页：网页 + 浏览器同款悬浮三段式 [SafariSplitBar] + 状态栏 / 底部材质。
 * 与主界面浏览页保持同一套体验：
 * 1. WebView 放在整页采集层里，底栏 / 状态栏的玻璃（miuix 材质、液态玻璃、渐进模糊）能真正取样到网页；
 * 2. 沉浸式：网页铺到手势条下面；状态栏留白 [topPadPx] 随滚动收起 / 回落（由 Activity 的滚动监听驱动），
 *    收起时底栏缩成小胶囊（[compact]），点胶囊展开；展开时网页在栏上面结束；
 * 3. 主题 / 玻璃参数配成和主界面一致（Activity 不是 setContent 起的，要自己包一层）。
 */
@Composable
internal fun CustomTabScreen(
    settings: AppSettings,
    webView: WebView,
    pageBg: Int,
    topPadPx: Int,
    compact: Boolean,
    address: String,
    loading: Boolean,
    onBack: () -> Unit,
    onOpenInMafari: () -> Unit,
    onRefresh: () -> Unit,
    onExpand: () -> Unit,
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
        val bg = Color(pageBg)
        // 给采集层补一层不透明底色，避免网页透明区域出现模糊采样伪影（与主界面一致）
        val browserBackdrop = rememberLayerBackdrop {
            drawRect(bg)
            drawContent()
        }
        val liquidBackdrop = rememberKyantBackdrop {
            drawRect(bg)
            drawContent()
        }
        SideEffect { browserBackdrop.graphicsLayer.compositingStrategy = LayerCompositingStrategy.Offscreen }
        CompositionLocalProvider(
            LocalBlurMode provides settings.blurMode,
            LocalGlassParams provides settings.glassParams,
            LocalLiquidParams provides settings.liquidParams,
            LocalLiquidBackdrop provides if (settings.blurEnabled) liquidBackdrop else null,
        ) {
            val metrics = BarStyles.SafariSplit.metrics
            val baseDensity = LocalDensity.current
            var barHeightPx by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxSize()) {
                // ---- 整页采集层：只有网页，底栏不能放进来 ----
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(browserBackdrop)
                        .then(if (settings.liquidGlass) Modifier.kyantLayerBackdrop(liquidBackdrop) else Modifier),
                ) {
                    val topDp = with(baseDensity) { topPadPx.toDp() }
                    // 栏展开时网页在栏上面结束；收起成小胶囊时网页铺到手势条下面
                    val bottomDp = with(baseDensity) { (if (compact) 0 else barHeightPx).toDp() }
                    AndroidView(
                        factory = { _ ->
                            (webView.parent as? ViewGroup)?.removeView(webView)
                            webView.apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(top = topDp, bottom = bottomDp),
                    )
                }

                // ---- 状态栏材质（渐进模糊）：留白时拉伸取样网页顶边，网页铺到屏幕顶时直接取样 ----
                if (settings.blurEnabled) {
                    StatusBarMaterialBand(
                        backdrop = browserBackdrop,
                        stretched = StatusInsetPolicy.stretched(
                            pageAtTop = topPadPx > 0, keepStatusInset = false, collapsed = topPadPx == 0,
                        ),
                    )
                }

                // ---- 底栏所在那一行（网页在它上面结束时）：渐进模糊 ----
                if (settings.blurEnabled && !compact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    BottomBarProgressiveBand(
                        backdrop = browserBackdrop,
                        heightPx = { barHeightPx },
                        alpha = { 1f },
                    )
                }

                // ---- 悬浮三段式栏 ----
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // 底部让开手势条；软键盘弹出时根布局已经按键盘高度让位，这里不重复留白
                        .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                        .onSizeChanged { barHeightPx = it.height },
                ) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = baseDensity.density * SafariSplitScale,
                            fontScale = baseDensity.fontScale,
                        ),
                    ) {
                        SafariSplitBar(
                            metrics = metrics.scaledBy(1f / SafariSplitScale),
                            backdrop = browserBackdrop,
                            liquid = if (settings.liquidGlass) liquidBackdrop else null,
                            blurEnabled = settings.blurEnabled,
                            textureBlurEnabled = true,
                            address = address,
                            loading = loading,
                            atHome = false,
                            // 返回始终可点：没有历史时 = 关闭自定义标签页
                            canGoBack = true,
                            editing = false,
                            compact = compact,
                            onExpand = onExpand,
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
}
