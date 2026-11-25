/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** 一条路径：[fill] 为 true 时填充，否则描边。 */
internal class IconPath(val d: String, val fill: Boolean = false)

/** 图标：路径数据直接取自 float-player.js 里的 SVG，[box] 是 viewBox 边长，[stroke] 是描边宽度（viewBox 单位）。 */
internal class PlayerIcon(val box: Float, val stroke: Float, val paths: List<IconPath>)

/** miuix 的 Glass 图标集里没有的几个：后退 / 快进 10 秒、画中画、退出全屏。其余图标都用 MiuixIcons.Glass。 */
internal object PlayerIcons {
    // 里面的「10」不在路径里，由调用方叠一层文字
    val Back10 = PlayerIcon(36f, 2.2f, listOf(IconPath("M18 6a12 12 0 1 0 8.49 3.51"), IconPath("M27 2L26.5 9.5L19 9")))
    val Fwd10 = PlayerIcon(36f, 2.2f, listOf(IconPath("M18 6a12 12 0 1 1-8.49 3.51"), IconPath("M9 2L9.5 9.5L17 9")))

    val Pip = PlayerIcon(
        24f, 1.8f,
        listOf(
            IconPath("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"),
            IconPath("M13.3 11.6h5a1.1 1.1 0 0 1 1.1 1.1v3a1.1 1.1 0 0 1-1.1 1.1h-5a1.1 1.1 0 0 1-1.1-1.1v-3a1.1 1.1 0 0 1 1.1-1.1z", fill = true),
        ),
    )
    val FullscreenExit = PlayerIcon(
        24f, 2f,
        listOf(IconPath("M9 3v3a2 2 0 0 1-2 2H4"), IconPath("M15 3v3a2 2 0 0 0 2 2h3"), IconPath("M9 21v-3a2 2 0 0 0-2-2H4"), IconPath("M15 21v-3a2 2 0 0 1 2-2h3")),
    )
}

@Composable
internal fun PlayerIconView(icon: PlayerIcon, size: Dp, color: Color, modifier: Modifier = Modifier) {
    // 路径只解析一次
    val parsed = remember(icon) { icon.paths.map { PathParser().parsePathString(it.d).toPath() to it.fill } }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / icon.box
        scale(k, k, pivot = Offset.Zero) {
            parsed.forEach { (path, fill) ->
                if (fill) {
                    drawPath(path, color, style = Fill)
                } else {
                    drawPath(path, color, style = Stroke(width = icon.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}
