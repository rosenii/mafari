package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.dsh.miuixbrowser.data.SearchEngine
import dev.dsh.miuixbrowser.data.rememberSearchIcon
import dev.dsh.miuixbrowser.i18n.tr
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
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
 * 点击弹出引擎列表，选一项即切换当前搜索引擎。弹层不抢焦点（focusable = false），输入框和键盘保持不变。
 */
@Composable
internal fun AddressEngineButton(
    engines: List<SearchEngine>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    expanded = !expanded
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        EngineIcon(engines.getOrNull(current), 20.dp)
        if (expanded) {
            Popup(
                popupPositionProvider = remember(gap) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            val x = anchorBounds.left
                                .coerceAtMost(windowSize.width - popupContentSize.width - gap)
                                .coerceAtLeast(gap)
                            // 按钮在屏幕下半部分（底栏）时向上弹，否则向下弹
                            val above = anchorBounds.center.y > windowSize.height / 2
                            val y = if (above) anchorBounds.top - popupContentSize.height - gap else anchorBounds.bottom + gap
                            return IntOffset(x, y.coerceAtLeast(gap))
                        }
                    }
                },
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                val shape = RoundedCornerShape(16.dp)
                Column(
                    modifier = Modifier
                        .widthIn(min = 150.dp, max = 240.dp)
                        .heightIn(max = 320.dp)
                        .shadow(12.dp, shape)
                        .clip(shape)
                        .background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    engines.forEachIndexed { index, engine ->
                        Row(
                            modifier = Modifier
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPick(index)
                                    expanded = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EngineIcon(engine, 22.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = engine.name,
                                fontSize = 15.sp,
                                color = if (index == current) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                            )
                            if (index == current) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = MiuixIcons.Basic.Check,
                                    contentDescription = tr("已选中"),
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
