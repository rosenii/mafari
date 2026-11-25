/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonDefaults
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 彩色（主色 / 危险色）液态玻璃按钮的叠色不透明度：越大越不透。 */
private const val COLORED_TINT_ALPHA = 0.92f

/** 玻璃按钮的语义色：对应 miuix 的 Normal / Primary / Destructive */
enum class LiquidRole { Normal, Primary, Destructive }

/**
 * Kyant0/AndroidLiquidGlass（io.github.kyant0:backdrop）的液态玻璃表面，写法对齐官方 catalog 的 LiquidButton：
 * 按压放大、拖拽时跟手形变、按下处有高光（见 InteractiveHighlight.kt）。
 *
 * - [backdrop] 必须是 Kyant 库自己的 LayerBackdrop，录制层要包住「页面内容」，本控件必须放在录制层之外。
 * - [backdrop] 为 null（用户关了模糊 / 网页态）时退回不透明的 [tint] 实色底，形变与高光照常。
 * - 形状固定为 CircleShape：宽高相等是圆，宽大于高就是胶囊。
 */
@Composable
fun LiquidGlassSurface(
    onClick: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.surfaceContainer,
    tintAlpha: Float = 0.35f,
    /** 为 true 时按 catalog 的 LiquidButton 写法着色（Hue 混合 + 75% 叠色），用于主色 / 危险色按钮。 */
    coloredTint: Boolean = false,
    enabled: Boolean = true,
    /** 玻璃底板是否显示。顶栏按钮传「内容已上滑」：与 miuix GlassTopAppBar 一致，未滚动时只有图标没有圆形背景。 */
    surfaceVisible: Boolean = true,
    /**
     * 为 false 时去掉按压放大 / 拖拽形变 / 跟指高光，只保留玻璃底和按下变淡的反馈。
     * 弹窗、sheet 里的按钮用 false；顶栏按钮、FAB 保持 true。
     */
    deformable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val lp = LocalLiquidParams.current
    val haptic = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { InteractiveHighlight(animationScope = animationScope) }
    // 与 miuix 顶栏按钮同一条淡入曲线：350ms 线性
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (surfaceVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 350, easing = LinearEasing),
        label = "liquidSurfaceAlpha",
    )
    // 官方写法：按压放大 + 拖拽时按钮跟手形变，全部走 layerBlock
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (!deformable && pressed) 0.7f else 1f,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "liquidPressAlpha",
    )
    val liquidLayer: GraphicsLayerScope.() -> Unit = liquid@{
        if (!deformable) return@liquid
        applyLiquidDeform(highlight)
    }
    val showSurface = surfaceAlpha > 0.001f

    Box(
        modifier = modifier.alpha((if (enabled) 1f else 0.4f) * pressAlpha),
        contentAlignment = Alignment.Center,
    ) {
        // 玻璃底板与图标分层：底板可以单独淡入淡出，图标始终可见；两层用同一个 liquidLayer，形变一致。
        if (showSurface) {
            if (backdrop != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { CircleShape },
                            effects = {
                                if (lp.vibrancy) vibrancy()
                                if (lp.hasColorTweak) colorControls(brightness = lp.brightness, saturation = lp.saturation)
                                if (lp.hasBlur) blur(lp.blurDp(2f).dp.toPx())
                                if (lp.hasLens) {
                                    lens(
                                        lp.lensHeightDp(12f).dp.toPx(), lp.lensAmountDp(24f).dp.toPx(),
                                        depthEffect = lp.depthEffect, chromaticAberration = lp.chromaticAberration,
                                    )
                                }
                            },
                            layerBlock = {
                                liquidLayer()
                                alpha = surfaceAlpha
                            },
                            onDrawSurface = {
                                if (coloredTint) {
                                    drawRect(tint, blendMode = BlendMode.Hue)
                                    // 彩色按钮（主色 / 危险色）：叠色不透明度由 0.75 提到 0.92，降低通透度，字更清楚
                                    drawRect(tint.copy(alpha = COLORED_TINT_ALPHA))
                                } else {
                                    drawRect(tint.copy(alpha = lp.tintAlpha(tintAlpha)))
                                }
                            },
                        ),
                )
            } else {
                // 关闭模糊 / 网页态：实色底
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            liquidLayer()
                            alpha = surfaceAlpha
                        }
                        .clip(CircleShape)
                        .background(tint)
                        // 实色底在同色的 sheet 上会隐形：非彩色按钮叠一层淡淡的前景色，保证圆形底板可见
                        .then(
                            if (coloredTint) Modifier
                            else Modifier.background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        ),
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer(block = liquidLayer)
                .clip(CircleShape)
                .clickable(
                    interactionSource = if (deformable) null else pressSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
                // 底板不可见时不画按下高光，避免出现一块没有底的亮斑
                .then(if (enabled && showSurface && deformable) highlight.modifier else Modifier)
                .then(if (enabled && deformable) highlight.gestureModifier else Modifier),
            contentAlignment = Alignment.Center,
            content = { content() },
        )
    }
}

/**
 * 液态玻璃按钮的按压放大 + 拖拽形变（跟手位移、沿拖动方向拉伸），写进 graphicsLayer。
 * 顶栏按钮（[LiquidGlassSurface]）和悬浮三段式的后退 / 更多圆钮共用同一套参数，手感一致。
 */
internal fun GraphicsLayerScope.applyLiquidDeform(highlight: InteractiveHighlight) {
    val width = size.width
    val height = size.height
    val progress = highlight.pressProgress
    val scale = lerp(1f, 1f + 4f.dp.toPx() / height, progress)

    val maxOffset = size.minDimension
    val initialDerivative = 0.05f
    val offset = highlight.offset
    translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
    translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

    val maxDragScale = 4f.dp.toPx() / height
    val offsetAngle = atan2(offset.y, offset.x)
    scaleX = scale +
        maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
        (width / height).fastCoerceAtMost(1f)
    scaleY = scale +
        maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
        (height / width).fastCoerceAtMost(1f)
}

/** 顶栏是否已被内容顶起（上滑）：与 GlassTopAppBar 内部判断相同，contentOffset < 0。 */
@Composable
fun rememberTopBarScrolled(scrollBehavior: ScrollBehavior): Boolean {
    val scrolled by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.contentOffset < 0f }
    }
    return scrolled
}
