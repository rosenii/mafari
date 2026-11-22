package dev.dsh.miuixbrowser.ui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
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
    Box(modifier = modifier.fillMaxSize()) {
        TopProgressiveBlurBand(
            backdrop = backdrop,
            alpha = { alpha },
            modifier = Modifier.matchParentSize(),
            // 栏高 = 状态栏本身；下沿只留一小段渐隐，不遮住太多网页顶部内容
            barHeight = 0.dp,
            rampExtra = 10.dp,
            tint = MiuixTheme.colorScheme.surface.copy(alpha = 0.3f),
        )
    }
}
