/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import kotlin.math.abs
import kotlin.math.sign

/**
 * 标签卡片左右滑动清除的手感参数与换算（纯函数，便于单元测试）。
 *
 * 阻尼：手指拖动的「原始位移」经橡皮筋曲线换算成卡片的「显示位移」——越拖越吃力，
 * 显示位移永远小于 [MAX_TRAVEL] × 卡片宽度，卡片不会被一把拖出屏幕，必须拖过阈值再松手才算清除。
 */
internal object SwipeDismiss {
    /** 显示位移的渐近上限（相对卡片宽度） */
    const val MAX_TRAVEL = 0.9f

    /** 阻尼系数：越大越「跟手」，越小越「吃力」 */
    const val RESISTANCE = 0.55f

    /** 显示位移超过卡片宽度的这个比例，松手即清除，并在越过时给一次振动 */
    const val DISMISS_FRACTION = 0.30f

    /** 快速甩动清除所需的最小速度（px/s），同时位移方向须与甩动方向一致且至少拖出 [FLING_MIN_FRACTION] */
    const val FLING_VELOCITY = 1800f
    const val FLING_MIN_FRACTION = 0.08f

    /** 原始位移 → 显示位移（带符号） */
    fun damped(raw: Float, width: Float): Float {
        if (width <= 0f || raw == 0f) return 0f
        val dim = width * MAX_TRAVEL
        return sign(raw) * (1f - 1f / (abs(raw) * RESISTANCE / dim + 1f)) * dim
    }

    /** 显示位移是否已越过清除阈值 */
    fun passed(shown: Float, width: Float): Boolean = width > 0f && abs(shown) >= width * DISMISS_FRACTION

    /** 松手时是否清除 */
    fun shouldDismiss(shown: Float, velocity: Float, width: Float): Boolean {
        if (width <= 0f) return false
        if (passed(shown, width)) return true
        return abs(velocity) >= FLING_VELOCITY && sign(velocity) == sign(shown) && abs(shown) >= width * FLING_MIN_FRACTION
    }

    /** 拖动时卡片的透明度：越往外越淡，最淡 0.4 */
    fun alpha(shown: Float, width: Float): Float =
        if (width <= 0f) 1f else 1f - (abs(shown) / (width * MAX_TRAVEL)).coerceIn(0f, 1f) * 0.6f
}
