package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.data.SearchEngine
import dev.dsh.miuixbrowser.data.rememberSearchIcon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 搜索引擎小图标：有网络图标就用它，否则是带首字的占位色块 */
@Composable
private fun EngineIcon(engine: SearchEngine?, size: androidx.compose.ui.unit.Dp) {
    val icon = rememberSearchIcon(engine?.iconUrl.orEmpty())
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size / 3.4f)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 3.4f))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = engine?.name?.firstOrNull()?.toString().orEmpty(),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 地址栏最左侧的搜索引擎按钮（仅在输入网址 / 关键词时显示，位置和大小与阅读 / 脚本图标一致）。
 * 点击只回报图标在根坐标里的位置；引擎列表由 BrowserScreen 用抽象的 [AppGlassPopup]（与其他弹出菜单同一套材质）弹出，
 * 不再自己造 Popup 窗口。
 */
@Composable
internal fun AddressEngineButton(
    engine: SearchEngine?,
    onClick: (androidx.compose.ui.geometry.Rect) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick(bounds)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        EngineIcon(engine, 20.dp)
    }
}
