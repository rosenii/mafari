package dev.dsh.miuixbrowser.ui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

/**
 * 网页态的状态栏背景：与子页面顶栏同款的 miuix 渐进模糊（自上而下由强到弱），取代原来的高级材质玻璃。
 * 只在 [visible]（页面处于初始 / 未收起状态）时显示；页面下滑、状态栏留白收起后淡出，网页铺到屏幕顶。
 * 适用于所有「网页不压到状态栏下」的导航栏样式（悬浮默认栏、悬浮三段式、固定在底部的栏）。
 * 需要 RuntimeShader（Android 13+）；不支持时不画，保持原来的纯色底。
 * 放在整页采集层（[backdrop]）之外，只画不接触摸。
 */
@Composable
internal fun StatusBarMaterialBand(
    backdrop: Backdrop,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isRuntimeShaderSupported()) return
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "statusBarMaterial",
    )
    // 完全淡出后不再占用采样 / 着色器
    if (!visible && alpha < 0.01f) return
    val density = LocalDensity.current
    val statusInsets = WindowInsets.statusBars
    // 状态栏那一条在整页采集层里是空的：以状态栏下沿为轴做边缘拉伸，这一条采到的是网页最顶部的边缘色；轴以下保持原位
    val mirrored = remember(backdrop) { MirroredBackdrop(backdrop) { statusInsets.getTop(density).toFloat() } }
    Box(modifier = modifier.fillMaxSize()) {
        TopProgressiveBlurBand(
            backdrop = mirrored,
            alpha = { alpha },
            modifier = Modifier.matchParentSize(),
            // 不叠雾化色（拉伸取到的是不透明网页边缘色，叠色会在与网页的接缝处露出一条线）
            // 渐隐段比状态栏再向下延伸 StatusBarRampExtra：状态栏下沿与网页之间原本会露出一条没被模糊盖住的浅色分割块，
            // 延伸出的这一段采样的是真实网页（轴以下保持原位采样），渐隐到清晰时与网页无缝衔接
            barHeight = 0.dp,
            rampExtra = StatusBarRampExtra,
            heightFactor = 1f,
            tint = Color.Unspecified,
        )
    }
}

/** 状态栏渐进模糊在状态栏下沿以下继续向下渐隐的高度，用来盖住状态栏与网页之间的分割白块 */
private val StatusBarRampExtra = 17.7.dp

/** 底部下沉背景比让位高度略向上多出的高度：顶边压在网页底边上，避免两者接缝处露出细分割线 */
private val BottomBandOverlap = 2.dp

/** 边缘拉伸倍数：停靠行只取紧贴它的网页边缘一小条（行高 / 倍数），纵向拉满整行 */
private const val EdgeStretch = 24f

/**
 * 采样层「边缘拉伸」（翻转后纵向放大 [EdgeStretch] 倍）：停靠栏占的那一行里网页是空的，镜像后这一行采到的是紧贴在它旁边的网页内容，
 * 并且在接缝处与真实网页无缝衔接。只取紧贴接缝的一小条拉满整行，看不出倒映的图标 / 文字（单纯镜像会露出倒影）；
 * 取到的是不透明的网页边缘色，不需要再叠雾化色（叠了会在接缝处形成一条分割线）。
 *
 * - [splitPx] == null：以绘制区域顶边（局部 y=0）为轴，整块镜像（底部那一行：采样紧贴在它上面的网页）。
 * - [splitPx] != null：以局部 y=[splitPx] 为轴，只镜像它上方那一条（顶部状态栏：采样网页顶部），
 *   轴以下保持原位采样（渐隐段本来就盖在网页上）。
 */
internal class MirroredBackdrop(
    private val base: Backdrop,
    private val splitPx: (() -> Float)? = null,
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
        val split = splitPx
        if (split == null) {
            withTransform({ scale(1f, -EdgeStretch, pivot = Offset.Zero) }) {
                with(base) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
            }
            return
        }
        // 降采样录制时，绘制坐标系已按 1/downscaleFactor 缩小，轴位置同比例缩小
        val p = split() / downscaleFactor.coerceAtLeast(1f)
        withTransform({ clipRect(top = 0f, bottom = p) }) {
            withTransform({ scale(1f, -EdgeStretch, pivot = Offset(0f, p)) }) {
                with(base) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
            }
        }
        withTransform({ clipRect(top = p, bottom = size.height) }) {
            with(base) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        }
    }
}

/**
 * 悬浮三段式识别到网页自带底栏 / 输入框时，会沉到屏幕底部占一行固定高度（网页在它上面结束）。
 * 这一行原来只有纯色底；改为 miuix 渐进模糊（direction = Bottom：贴屏幕底最强、向上渐隐到清晰），
 * 采样的是网页底部边缘拉伸后的颜色。
 *
 * - [heightPx]：这一行的高度（栏高 + 导航栏，随让位比例 / 收起动画变化），布局阶段读取。
 * - [alpha]：绘制阶段读取，跟随让位比例淡入淡出。
 * - 放在整页采集层之外、三段式玻璃件之下，只画不接触摸。
 */
@Composable
internal fun BottomBarProgressiveBand(
    backdrop: Backdrop,
    heightPx: Density.() -> Int,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isRuntimeShaderSupported()) return
    val active by remember { derivedStateOf { alpha() > 0.01f } }
    if (!active) return
    val mirrored = remember(backdrop) { MirroredBackdrop(backdrop) }
    // 不叠雾化色：拉伸取到的已是不透明的网页边缘色，再叠色会在与网页的接缝处露出一条线
    val colors = BlurDefaults.blurColors(blendColors = emptyList())
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .layout { measurable, constraints ->
                    val h = heightPx().coerceAtLeast(0) + BottomBandOverlap.roundToPx()
                    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(constraints.maxWidth, h) { p.placeRelative(0, 0) }
                }
                .fillMaxWidth()
                .graphicsLayer {
                    this.alpha = alpha().coerceIn(0f, 1f)
                }
                .progressiveTextureBlur(
                    backdrop = mirrored,
                    shape = RectangleShape,
                    gradient = ProgressiveBlur.Bottom.copy(curve = 0.5f),
                    blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                    colors = colors,
                ),
        )
    }
}
