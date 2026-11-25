/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.engine.gecko.BottomFullViewport.next
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomFullViewportTest {
    private val max = 144

    @Test fun entersNearBottomWhenCollapsed() {
        // 日志里滑到底时剩余量最小是 142
        assertTrue(next(false, max, 0, 142))
        assertTrue(next(false, max, 0, max + 100))
        assertFalse(next(false, max, 0, max + 101))
    }

    @Test fun staysFullUntilContentGrows() {
        assertTrue(next(true, max, 0, 0))
        assertTrue(next(true, max, 0, 499))
        assertFalse(next(true, max, 0, 500))
    }

    @Test fun hysteresisDoesNotFlapAtTheEdge() {
        // 展开后剩余量会少 max；回到进入阈值附近时不能立刻退出
        assertTrue(next(true, max, 0, 142 - max + 150))
    }

    @Test fun neverWhenImmersiveOffOrInsetShown() {
        assertFalse(next(false, 0, 0, 0))
        assertFalse(next(true, max, 144, 0)) // 状态栏留白还在（下滑唤出栏）：恢复原状
        assertFalse(next(true, max, 0, -1)) // 没有回报
    }
}
