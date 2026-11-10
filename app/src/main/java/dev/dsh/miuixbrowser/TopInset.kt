package dev.dsh.miuixbrowser

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.offset

/**
 * 与 `Modifier.padding(top = …)` 等价，但顶部留白在「布局阶段」才读取。
 *
 * 状态栏留白随滚动收起 / 回落时是一段逐帧变化的弹簧动画：如果在组合阶段读取它，
 * 动画的每一帧都会让整个 BrowserScreen（两千多行的大组合）重新组合一遍；
 * 放到布局阶段读取后，每帧只重新测量 / 摆放这一块，组合完全不参与。
 */
internal fun Modifier.topInsetPx(top: Density.() -> Int): Modifier = layout { measurable, constraints ->
    val t = top().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.offset(vertical = -t))
    val h = (placeable.height + t).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, h) { placeable.placeRelative(0, t) }
}
