package dev.dsh.miuixbrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.noiseDither
import top.yukonga.miuix.kmp.blur.progressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.blur.textureBlurEffect
import top.yukonga.miuix.kmp.blur.highlight.Highlight


/**
 * 页面顶部的 miuix 渐进模糊带（自上而下由强到弱），官方 glass demo 的同款写法。
 *
 * - 必须放在 [matchParentSize][androidx.compose.foundation.layout.BoxScope.matchParentSize] 的位置：
 *   本身只画顶部 `(状态栏 + [barHeight] + 20dp) * 1.2` 的高度，不撑大父布局，也不拦截触摸。
 * - [backdrop] 必须是录制「页面内容」的层，并且本组件要放在录制层之外，否则会采样到自己。
 * - [tint] 叠在模糊之上的主题色雾化（默认跟随当前主题的 surface，深色模式下是深色而不是白色）；
 *   传 [Color.Unspecified] 表示不叠色，只做纯模糊（例如首页壁纸之上）。
 * - [alpha] 在绘制阶段读取，用来跟随滚动淡入淡出。
 */
@Composable
fun TopProgressiveBlurBand(
    backdrop: Backdrop,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
    barHeight: Dp = TopAppBarDefaults.CollapsedHeight,
    tint: Color = MiuixTheme.colorScheme.surface.copy(alpha = 0.3f),
    blurRadius: Float = 80f,
) {
    val statusInsets = WindowInsets.statusBars
    val colors = BlurDefaults.blurColors(
        blendColors = if (tint == Color.Unspecified) emptyList() else listOf(BlendColorEntry(color = tint)),
    )
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val barPx = statusInsets.getTop(this) + barHeight.toPx()
                val blurHeight = ((barPx + 20.dp.toPx()) * 1.2f).roundToInt()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = blurHeight, maxHeight = blurHeight),
                )
                // 向下溢出，不改变父布局（Scaffold topBar）测得的高度。
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeRelative(0, 0)
                }
            }
            .graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) }
            .progressiveTextureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                gradient = ProgressiveBlur(90f, 0f, 1f, 0.5f),
                blurRadius = blurRadius,
                colors = colors,
            ),
    )
}
