package dev.dsh.miuixbrowser.ui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

/**
 * 把采样层整体上移 [shiftUpPx] 像素的包装：网页内容从状态栏下面才开始，状态栏那一条在整页采集层里是空的；
 * 上移一个状态栏高度后，状态栏这一条采到的就是「网页最顶部那一条」（原来紧贴在状态栏下面的区域）。
 * [shiftUpPx] 只在绘制阶段读取。
 */
internal class ShiftedBackdrop(
    private val base: Backdrop,
    private val shiftUpPx: () -> Float,
) : Backdrop {
    override val isCoordinatesDependent: Boolean get() = base.isCoordinatesDependent
    override val offsetResidualX: Float get() = base.offsetResidualX
    override val offsetResidualY: Float get() = base.offsetResidualY
    override val backdropCoordinates: LayoutCoordinates? get() = base.backdropCoordinates

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Float,
    ) {
        // 降采样录制时，绘制坐标系已按 1/downscaleFactor 缩小，位移同比例缩小
        val dy = shiftUpPx() / downscaleFactor.coerceAtLeast(1f)
        withTransform({ translate(0f, -dy) }) {
            with(base) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        }
    }
}

/**
 * 网页态的状态栏背景：高级材质（与停靠栏同款玻璃），以网页最顶部一条区域为采样层。
 * 只在 [visible]（页面处于初始 / 未收起状态）时显示；页面下滑、状态栏留白收起后淡出，网页铺到屏幕顶。
 * 需要 RuntimeShader（Android 13+）；不支持时不画，保持原来的纯色底。
 * 放在整页采集层（[backdrop]）之外，状态栏高度的一条，只画不接触摸。
 */
@Composable
internal fun StatusBarMaterialBand(
    backdrop: Backdrop,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isRuntimeShaderSupported()) return
    val density = LocalDensity.current
    val statusInsets = WindowInsets.statusBars
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "statusBarMaterial",
    )
    // 完全淡出后不再占用采样 / 着色器
    if (!visible && alpha < 0.01f) return
    val dark = isAppDarkTheme()
    val shifted = remember(backdrop) { ShiftedBackdrop(backdrop) { statusInsets.getTop(density).toFloat() } }
    val barHeight = with(density) { statusInsets.getTop(this).toDp() }
    if (barHeight <= 0.dp) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .graphicsLayer { this.alpha = alpha }
            .glassPanel(
                backdrop = shifted,
                shape = GlassShape(0.dp),
                style = GlassLook.style(dark),
                material = GlassLook.material(dark),
                stroke = null,
                shadow = null,
                shading = GlassLook.shading(),
            ),
    )
}
