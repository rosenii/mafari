package dev.dsh.miuixbrowser

import androidx.compose.ui.unit.dp
import dev.dsh.miuixbrowser.data.BAR_POS_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_FIXED_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_FIXED_TOP
import dev.dsh.miuixbrowser.data.BAR_POS_MINIMIZED
import dev.dsh.miuixbrowser.data.BAR_POS_SAFARI_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_TOP
import dev.dsh.miuixbrowser.ui.BarEdge
import dev.dsh.miuixbrowser.ui.BarStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「导航栏样式」→「边 + 样式」的映射，以及悬浮 / 停靠两种样式的占位约定 */
class BarStyleTest {
    @Test fun floatingPositionsKeepTheCapsule() {
        val top = BarStyles.resolve(BAR_POS_TOP)
        assertEquals(BarEdge.Top, top.edge)
        assertEquals("floating_capsule", top.style.id)
        assertFalse(top.docked)

        val bottom = BarStyles.resolve(BAR_POS_BOTTOM)
        assertEquals(BarEdge.Bottom, bottom.edge)
        assertEquals("floating_capsule", bottom.style.id)

        // 「始终最小化」的圆钮在底部，仍是悬浮样式
        val min = BarStyles.resolve(BAR_POS_MINIMIZED)
        assertEquals(BarEdge.Bottom, min.edge)
        assertFalse(min.docked)
    }

    @Test fun fixedPositionsAreDockedFlat() {
        val top = BarStyles.resolve(BAR_POS_FIXED_TOP)
        assertEquals(BarEdge.Top, top.edge)
        assertEquals("docked_flat", top.style.id)
        assertTrue(top.reservesTop)
        assertFalse(top.reservesBottom)

        val bottom = BarStyles.resolve(BAR_POS_FIXED_BOTTOM)
        assertEquals(BarEdge.Bottom, bottom.edge)
        assertTrue(bottom.reservesBottom)
        assertFalse(bottom.reservesTop)
    }

    @Test fun safariPositionIsDockedSplitPillsAtBottom() {
        val l = BarStyles.resolve(BAR_POS_SAFARI_BOTTOM)
        assertEquals(BarEdge.Bottom, l.edge)
        assertEquals("safari_split", l.style.id)
        assertTrue(l.style.splitPills)
        assertTrue(l.reservesBottom)
        assertFalse(l.reservesTop)
        // 其他样式都不是三段式
        assertFalse(BarStyles.FloatingCapsule.splitPills)
        assertFalse(BarStyles.DockedFlat.splitPills)
    }

    @Test fun floatingBarNeverReservesPageSpace() {
        for (pos in listOf(BAR_POS_TOP, BAR_POS_BOTTOM, BAR_POS_MINIMIZED)) {
            val l = BarStyles.resolve(pos)
            assertFalse(l.reservesTop)
            assertFalse(l.reservesBottom)
        }
    }

    @Test fun dockedBarIsShorterAndFlat() {
        val floating = BarStyles.FloatingCapsule
        val docked = BarStyles.DockedFlat
        assertTrue(docked.metrics.height < floating.metrics.height)
        assertEquals(0.dp, docked.metrics.corner)
        assertEquals(0.dp, docked.horizontalMargin)
        assertEquals(0.dp, docked.edgeGap(BarEdge.Top))
        assertEquals(0.dp, docked.edgeGap(BarEdge.Bottom))
    }

    @Test fun floatingMetricsMatchTheOriginalCapsule() {
        val m = BarStyles.FloatingCapsule.metrics
        assertEquals(58.dp, m.height)
        assertEquals(29.dp, m.corner)
        assertEquals(44.dp, m.buttonSize)
        // 原来写死的 44 * 4 + 12 + 24
        assertEquals(44.dp * 4 + 12.dp + 24.dp, m.fixedWidth)
    }

    @Test fun homePaddingKeepsOldFloatingValues() {
        val floating = BarStyles.FloatingCapsule
        assertEquals(114.dp, floating.homePadding(BarEdge.Top).top)
        assertEquals(40.dp, floating.homePadding(BarEdge.Top).bottom)
        assertEquals(44.dp, floating.homePadding(BarEdge.Bottom).top)
        assertEquals(150.dp, floating.homePadding(BarEdge.Bottom).bottom)
    }
}
