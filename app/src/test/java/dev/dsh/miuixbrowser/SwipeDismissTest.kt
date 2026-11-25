/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.ui.SwipeDismiss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 纯 JVM：标签卡片左右滑动清除的阻尼曲线与清除判定 */
class SwipeDismissTest {
    private val w = 400f

    @Test
    fun dampedIsZeroAtZeroAndSymmetric() {
        assertEquals(0f, SwipeDismiss.damped(0f, w), 0f)
        assertEquals(-SwipeDismiss.damped(120f, w), SwipeDismiss.damped(-120f, w), 1e-4f)
    }

    @Test
    fun dampedIsMonotonicSlowerThanFingerAndBounded() {
        var prev = 0f
        for (raw in 10..4000 step 10) {
            val v = SwipeDismiss.damped(raw.toFloat(), w)
            assertTrue("单调递增 @${raw}", v > prev)
            assertTrue("比手指慢 @${raw}", v < raw)
            assertTrue("不超过渐近线 @${raw}", v < w * SwipeDismiss.MAX_TRAVEL)
            prev = v
        }
    }

    @Test
    fun resistanceGrowsWithDistance() {
        // 每多拖 50px，卡片多走的距离越来越少（越拖越吃力）
        val steps = (0..6).map { SwipeDismiss.damped(it * 50f, w) }
        val gains = steps.zipWithNext { a, b -> b - a }
        assertTrue(gains.zipWithNext().all { (a, b) -> b < a })
        // 起手就已经有阻尼：前 50px 只走不到 50px 的 55% 附近
        assertTrue(gains.first() < 50f * 0.6f)
    }

    @Test
    fun thresholdIsReachableButNeedsRealDrag() {
        // 需要拖出的原始位移：明显大于卡片宽度的一半，又不会离谱
        var raw = 0f
        while (!SwipeDismiss.passed(SwipeDismiss.damped(raw, w), w)) raw += 1f
        assertTrue("原始位移 $raw", raw in (w * 0.5f)..(w * 1.2f))
    }

    @Test
    fun dismissOnlyAfterThresholdOrFastFling() {
        assertFalse(SwipeDismiss.shouldDismiss(w * 0.1f, 0f, w))
        assertTrue(SwipeDismiss.shouldDismiss(w * 0.31f, 0f, w))
        assertTrue(SwipeDismiss.shouldDismiss(-w * 0.31f, 0f, w))
        // 甩动：方向一致且拖出了一点
        assertTrue(SwipeDismiss.shouldDismiss(w * 0.1f, 2500f, w))
        assertTrue(SwipeDismiss.shouldDismiss(-w * 0.1f, -2500f, w))
        // 方向相反 / 几乎没拖 / 速度不够：不清除
        assertFalse(SwipeDismiss.shouldDismiss(w * 0.1f, -2500f, w))
        assertFalse(SwipeDismiss.shouldDismiss(w * 0.02f, 3000f, w))
        assertFalse(SwipeDismiss.shouldDismiss(w * 0.1f, 900f, w))
        assertFalse(SwipeDismiss.shouldDismiss(w * 0.5f, 0f, 0f))
    }

    @Test
    fun alphaFadesButStaysVisible() {
        assertEquals(1f, SwipeDismiss.alpha(0f, w), 1e-6f)
        val far = SwipeDismiss.alpha(w * 0.89f, w)
        assertTrue(far in 0.39f..0.45f)
        assertTrue(abs(SwipeDismiss.alpha(-100f, w) - SwipeDismiss.alpha(100f, w)) < 1e-6f)
    }
}
