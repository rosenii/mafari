/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.highlight.Highlight
import dev.dsh.miuixbrowser.data.LiquidParams

/** 液态玻璃的当前参数，由 MainActivity 等入口随 AppSettings.liquidParams 提供（与 LocalGlassParams 同位置）。 */
val LocalLiquidParams = compositionLocalOf { LiquidParams.Default }

/** 边缘高光：Plain 基础宽度 × [factor]（各表面原本的加宽倍数）× 参数里的高光倍率。 */
internal fun liquidHighlight(p: LiquidParams, factor: Float): Highlight =
    Highlight.Plain.copy(width = Highlight.Plain.width * factor * p.highlightScale)

/** 把颜色的 alpha 按「叠色浓度」缩放。 */
internal fun Color.liquidTint(p: LiquidParams): Color = copy(alpha = p.tintAlpha(alpha))
