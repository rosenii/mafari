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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    // 状态栏那一条在整页采集层里是空的：以状态栏下沿为轴镜像，这一条采到的是网页最顶部；轴以下保持原位
    val mirrored = remember(backdrop) { MirroredBackdrop(backdrop) { statusInsets.getTop(density).toFloat() } }
    Box(modifier = modifier.fillMaxSize()) {
        TopProgressiveBlurBand(
            backdrop = mirrored,
            alpha = { alpha },
            modifier = Modifier.matchParentSize(),
            // 渐隐整段收在状态栏里（下沿正好结束，与下面的真实网页无缝衔接），不再向下盖住网页内容
            barHeight = 0.dp,
            rampExtra = 0.dp,
            heightFactor = 1f,
            tint = MiuixTheme.colorScheme.surface.copy(alpha = 0.3f),
        )
    }
}

/**
 * 采样层镜像：停靠栏占的那一行里网页是空的，镜像后这一行采到的是紧贴在它旁边的网页内容，
 * 并且在接缝处与真实网页无缝衔接（平移则会在接缝处错位一整行）。
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
            withTransform({ scale(1f, -1f, pivot = Offset.Zero) }) {
                with(base) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
            }
            return
        }
        // 降采样录制时，绘制坐标系已按 1/downscaleFactor 缩小，轴位置同比例缩小
        val p = split() / downscaleFactor.coerceAtLeast(1f)
        withTransform({ clipRect(top = 0f, bottom = p) }) {
            withTransform({ scale(1f, -1f, pivot = Offset(0f, p)) }) {
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
 * 采样的是镜像后的网页底部内容。
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
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.3f))),
    )
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .layout { measurable, constraints ->
                    val h = heightPx().coerceAtLeast(0)
                    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(constraints.maxWidth, h) { p.placeRelative(0, 0) }
                }
                .fillMaxWidth()
                .graphicsLayer {
                    this.alpha = alpha().coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                // 顶端（紧贴网页底栏处）整体渐隐：镜像出来的内容在那里模糊半径接近 0，会露出倒映的底栏图标 / 文字，
                // 所以用透明度渐变把这一段遮掉，露出原来的底色；越往下越实，到下半段完全显示
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.65f to Color.Black,
                            1f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
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
