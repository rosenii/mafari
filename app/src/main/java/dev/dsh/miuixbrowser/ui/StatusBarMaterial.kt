/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.os.Build
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
 * 网页态的状态栏背景：miuix 渐进模糊（自上而下由强到弱）。适用于所有「网页不压到状态栏下」的
 * 导航栏样式（悬浮默认栏、悬浮三段式、固定在底部的栏）。需要 RuntimeShader（Android 13+）；不支持时不画。
 * 放在整页采集层（[backdrop]）之外（所以任何一层都不会被再次采样），只画不接触摸。
 *
 * 任一时刻只画下面两种之一，按 [stretched] 硬切换——没有透明度 / 淡入淡出动画，也不会两层叠在一起：
 *
 * - [stretched] = true（页面加载完成未滑动 / 滑回最顶部 / 页面带固定顶栏）：网页从状态栏下面开始，状态栏这一条在采集层里是空的，
 *   以状态栏下沿为轴做边缘拉伸，采到网页最顶部的边缘色；轴以下保持原位。这时网页顶部静止，拉伸采样稳定。
 * - [stretched] = false（其余所有时刻，无论上滑还是下滑）：网页已铺到屏幕顶，状态栏区域就是网页本身，
 *   直接采样网页（不镜像、不拉伸），模糊半径 [DirectBlurRadius]。
 */
@Composable
internal fun StatusBarMaterialBand(
    backdrop: Backdrop,
    stretched: Boolean,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isRuntimeShaderSupported()) return
    val density = LocalDensity.current
    val statusInsets = WindowInsets.statusBars
    val mirrored = remember(backdrop) { MirroredBackdrop(backdrop) { statusInsets.getTop(density).toFloat() } }
    Box(modifier = modifier.fillMaxSize()) {
        if (stretched) {
            TopProgressiveBlurBand(
                backdrop = mirrored,
                alpha = { 1f },
                modifier = Modifier.matchParentSize(),
                // 不叠雾化色（拉伸取到的是不透明网页边缘色，叠色会在与网页的接缝处露出一条线）
                // 渐隐段比状态栏再向下延伸 StatusBarRampExtra：盖住状态栏下沿与网页之间的分割块；
                // 延伸出的这一段采样真实网页（轴以下保持原位），渐隐到清晰时与网页无缝衔接
                barHeight = 0.dp,
                rampExtra = StatusBarRampExtra,
                heightFactor = 1f,
                tint = Color.Unspecified,
            )
        } else {
            TopProgressiveBlurBand(
                backdrop = backdrop,
                alpha = { 1f },
                modifier = Modifier.matchParentSize(),
                barHeight = 0.dp,
                rampExtra = StatusBarRampExtra,
                heightFactor = 1f,
                tint = Color.Unspecified,
                blurRadius = DirectBlurRadius,
            )
        }
    }
}

/** 网页铺到屏幕顶时（直接采样）状态栏渐进模糊的半径 */
internal const val DirectBlurRadius = 13f

/**
 * 网页态状态栏的两种形态，由页面滚动位置决定（纯函数，见 StatusInsetPolicyTest）。
 * 拉伸 + 模糊只在「加载完成未滑动 / 滑回最顶部 / 页面带固定顶栏」出现，其余时刻状态栏区域一律是铺满的网页，
 * 不存在任何自绘的状态栏底色或透明度过渡：
 *
 * - **留白 + 拉伸取样**：网页从状态栏下面开始（状态栏这一条是空的），用「拉伸的网页顶边」填出渐进模糊。
 *   只在初始加载完成、页面还没滑动，以及从下面彻底滑回最顶部时使用；页面自带固定顶栏时顶边静止，也用它。
 * - **铺到屏幕顶 + 直接取样**：网页铺到屏幕顶，渐进模糊直接采样状态栏下的网页（不拉伸），渐进范围从屏幕顶边算起、
 *   包含整个状态栏区域。页面一离开顶部就是这一种，不管底栏当前是收起还是因下滑重新展开——
 *   否则下滑唤出栏时留白会回落，状态栏区域是空的，只剩状态栏以下那一小段在模糊，层级就断了。
 */
internal object StatusInsetPolicy {
    /** 状态栏留白是否收起（网页铺到屏幕顶） */
    fun collapsed(
        immersiveScrolled: Boolean,
        keepStatusInset: Boolean,
        statusBandActive: Boolean,
        hasPage: Boolean,
        pageAtTop: Boolean,
    ): Boolean {
        if (keepStatusInset) return false
        // 页面已经离开顶部：栏是否收起都不再给状态栏留白
        val offTop = statusBandActive && hasPage && !pageAtTop
        return immersiveScrolled || offTop
    }

    /** 离开最顶部的阈值（px）：滚动偏移超过它才算「已滑动」 */
    const val LEAVE_TOP_PX = 12

    /** 回到最顶部的阈值（px）：比离开阈值小，加一点滞后，在阈值附近来回抖动时不会反复切换状态栏形态 */
    const val RETURN_TOP_PX = 6

    /** 页面是否在最顶部（带滞后）：没有页面视为在顶部；[wasAtTop] 是上一次的结果 */
    fun atTop(wasAtTop: Boolean, offsetY: Int, hasPage: Boolean): Boolean =
        !hasPage || offsetY <= (if (wasAtTop) LEAVE_TOP_PX else RETURN_TOP_PX)

    /** 是否用「拉伸取样」；否则直接采样网页 */
    fun stretched(pageAtTop: Boolean, keepStatusInset: Boolean, collapsed: Boolean): Boolean =
        (pageAtTop || keepStatusInset) && !collapsed
}

/** 状态栏渐进模糊在状态栏下沿以下继续向下渐隐的高度，用来盖住状态栏与网页之间的分割白块 */
private val StatusBarRampExtra = 16.5.dp

/** 底部下沉背景比让位高度略向上多出的高度：顶边压在网页底边上，避免两者接缝处露出细分割线 */
private val BottomBandOverlap = 2.dp

/** 边缘拉伸倍数：停靠行只取紧贴它的网页边缘一小条（行高 / 倍数），纵向拉满整行 */
private const val EdgeStretch = 24f

/** 拉伸取样起点相对轴向网页内挪的像素数（采样空间；避开与透明留白相邻的那一行） */
private const val EdgeSampleInset = 3f

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
        // 拉伸段多盖过轴 2px、真实网页段再从轴开始盖在它上面：两段裁剪边缘落在小数像素上时，
        // 接缝处那一行不会两边都没盖到而露出底下的白色（交界处的白条）
        withTransform({ clipRect(top = 0f, bottom = (p + 2f).coerceAtMost(size.height)) }) {
            // 取样起点比轴再往网页里挪 EdgeSampleInset：轴正下方那一行与上面留白（透明）相邻，双线性采样会把它们混在一起，
            // 拉伸出来的近轴一段（约 EdgeStretch / 2 px 高）就变得半透明，透出底色形成一条发灰的雾带。
            // 取样点落在 y = p + inset 处，仍让它映射到轴（y = p）：pivot = p + inset * k / (k + 1)
            val pivotY = p + EdgeSampleInset * EdgeStretch / (EdgeStretch + 1f)
            withTransform({ scale(1f, -EdgeStretch, pivot = Offset(0f, pivotY)) }) {
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
