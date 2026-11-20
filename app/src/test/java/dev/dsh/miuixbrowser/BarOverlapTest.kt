package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.ui.BarEdge
import dev.dsh.miuixbrowser.ui.BarOverlap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 停靠栏「页面深入栏下」的滚动判定：初始让位、滑动深入、回到边缘恢复，且进出有迟滞 */
class BarOverlapTest {
    private val bar = 200 // 底栏占位（像素）

    private fun top(overlapped: Boolean, y: Int) =
        BarOverlap.next(BarEdge.Top, overlapped, y, BarOverlap.UNKNOWN, 0)

    private fun bottom(overlapped: Boolean, y: Int, remaining: Int) =
        BarOverlap.next(BarEdge.Bottom, overlapped, y, remaining, bar)

    @Test fun topBarStartsReservedAtPageTop() {
        assertFalse(top(false, 0))
        assertFalse(top(false, BarOverlap.TOP_OVERLAP_PX))
    }

    @Test fun topBarOverlapsOnceScrolledAway() {
        assertTrue(top(false, BarOverlap.TOP_OVERLAP_PX + 1))
        assertTrue(top(false, 5000))
    }

    @Test fun topBarRestoresBackAtPageTop() {
        assertFalse(top(true, 0))
        assertFalse(top(true, BarOverlap.TOP_RESTORE_PX))
    }

    @Test fun topBarHasHysteresis() {
        // 深入后在 4..24 之间不来回闪
        assertTrue(top(true, 10))
        assertFalse(top(false, 10))
    }

    @Test fun topBarIgnoresNegativeOverscroll() {
        assertFalse(top(false, -80))
        assertFalse(top(true, -80))
    }

    @Test fun bottomBarStartsReservedAtPageTop() {
        assertFalse(bottom(false, 0, 6000))
    }

    @Test fun bottomBarOverlapsWhileScrollingInTheMiddle() {
        assertTrue(bottom(false, 800, 5000))
        assertTrue(bottom(true, 800, 5000))
    }

    @Test fun bottomBarRestoresAtPageBottom() {
        assertFalse(bottom(true, 5000, 0))
        assertFalse(bottom(true, 5000, BarOverlap.BOTTOM_RESTORE_PX))
    }

    @Test fun bottomBarDoesNotFlipBackRightAfterRestoring() {
        // 恢复时网页区域缩了 bar 像素：剩余距离从 <=8 跳到 <=8+bar，必须仍判为「恢复」
        val remainingAfterRestore = BarOverlap.BOTTOM_RESTORE_PX + bar
        assertFalse(bottom(false, 5000, remainingAfterRestore))
    }

    @Test fun bottomBarOverlapsAgainAfterScrollingUpFromTheEnd() {
        val far = bar + BarOverlap.BOTTOM_LEAVE_MARGIN_PX + 1
        assertTrue(bottom(false, 4000, far))
        // 重新深入后区域变大，剩余距离减 bar，仍留在「深入」
        assertTrue(bottom(true, 4000, far - bar))
    }

    @Test fun bottomBarWithUnknownRemainingKeepsTheOldLayout() {
        assertFalse(BarOverlap.next(BarEdge.Bottom, false, 3000, BarOverlap.UNKNOWN, bar))
        assertFalse(BarOverlap.next(BarEdge.Bottom, true, 3000, BarOverlap.UNKNOWN, bar))
    }

    @Test fun shortPageNeverOverlapsBottomBar() {
        // 页面刚好比屏幕长一点：滑几像素就到底，不应深入后又马上恢复造成抖动
        assertFalse(bottom(false, 20, 20))
    }
}
