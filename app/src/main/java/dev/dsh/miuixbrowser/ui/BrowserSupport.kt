/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.miuixbrowser.RefreshSpinnerView
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.net.HttpFetcher
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* BrowserScreen 的零散支撑：远程脚本抓取、BrowserChunk、IME insets、下拉刷新、菜单动作转发 */

/** 抓取远程脚本 */
internal fun fetchRemoteScript(url: String): String? = runCatching {
    HttpFetcher.getText(
        url,
        HttpFetcher.Options(connectTimeoutMs = 8000, readTimeoutMs = 8000, maxBytes = 8 * 1024 * 1024, userAgent = "Mozilla/5.0 mafari"),
    )
}.getOrNull()

/** 下拉刷新指示器的宿主：pullProgress 每个触摸事件都在变，把读取隔离在这个小组合里，不让整个 BrowserScreen 跟着重组 */
/**
 * 把一段 composable 内容放进独立的 lambda 方法里（不产生任何布局节点）。
 * 用来拆分 BrowserScreen 里过大的内容 lambda，避免 Kotlin 编译器生成超大方法而 OOM。
 */
@Composable
internal fun BrowserChunk(content: @Composable () -> Unit) {
    content()
}

/** 键盘动画的目标 insets：动画一开始就是最终高度，用于让网页区域只重排一次 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberImeTargetInsets(): WindowInsets = WindowInsets.imeAnimationTarget

@Composable
internal fun BoxScope.PullSpinnerHost(
    tab: BrowserTab,
    /** 玻璃采样源（页面内容采集层）：本控件必须放在采集层之外，否则会采样到自己 */
    backdrop: LayerBackdrop?,
    liquid: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    textureBlurEnabled: Boolean,
) {
    val p = tab.pullProgress
    if (p > 0.05f) {
        // 宿主铺满全屏（盖住状态栏）：从状态栏 / 居中摄像头挖孔的下沿再往下留 18dp，避免与摄像头重叠
        val topInset = with(LocalDensity.current) {
            maxOf(WindowInsets.statusBars.getTop(this), WindowInsets.displayCutout.getTop(this)).toDp()
        }
        RefreshSpinner(
            progress = p,
            backdrop = backdrop,
            liquid = liquid,
            blurEnabled = blurEnabled,
            textureBlurEnabled = textureBlurEnabled,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInset + 18.dp),
        )
    }
}

/**
 * 顶部下拉刷新指示器：旋转箭头直接复用轻应用（WebAppActivity）的 RefreshSpinnerView，
 * 圆形底板改用和导航栏同款的「高级材质」（[glassBar]：柔光 / 磨砂 / 液态玻璃，关闭模糊时退回纯色）。
 * 底板与箭头分层，避免采样层递归包含自身。
 */
@Composable
internal fun RefreshSpinner(
    progress: Float,
    backdrop: LayerBackdrop?,
    liquid: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    textureBlurEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .graphicsLayer {
                alpha = 0.4f + 0.6f * progress
                val scale = 0.7f + 0.3f * progress
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .glassBar(
                    backdrop = backdrop,
                    corner = 17.dp,
                    blurEnabled = blurEnabled,
                    textureBlurEnabled = textureBlurEnabled,
                    liquid = liquid,
                ),
        )
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx -> RefreshSpinnerView(ctx) },
            update = { v -> v.progress = progress },
        )
    }
}

/** 把「执行菜单功能」转发给稍后才定义的函数（首页快捷菜单要在它声明之前就引用到） */
internal class MenuActionRunner {
    var run: (MenuAction) -> Unit = {}
}
