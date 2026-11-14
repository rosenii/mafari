package dev.dsh.miuixbrowser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity

/**
 * 滑到顶 / 滑到底时给一下振动反馈（拖动或惯性滑动撞到边缘各触发一次）。
 *
 * 放在页面滚动容器上，且要写在 `.nestedScroll(scrollBehavior.nestedScrollConnection)` 之前：
 * 先写的是外层，会最后收到「子级和顶栏都没消费掉」的剩余滚动量，
 * 所以顶栏还在折叠 / 展开时不会误触发，只有内容真的到头了才振。
 */
@Composable
fun Modifier.edgeHaptic(): Modifier {
    val haptic = LocalHapticFeedback.current
    val connection = remember(haptic) {
        EdgeHapticConnection { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    return nestedScroll(connection)
}

private class EdgeHapticConnection(private val onEdge: () -> Unit) : NestedScrollConnection {
    /** 这一次手势 / 惯性里是否已经振过（同一次撞边只振一下，离开边缘或手势结束后重新计） */
    private var fired = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (available.y != 0f) {
            if (!fired) {
                fired = true
                onEdge()
            }
        } else if (consumed.y != 0f) {
            // 还在正常滚动：离开了边缘，下次撞边再振
            fired = false
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // 快速甩到边缘、滚动量刚好用完时，剩余速度也算撞边
        if (!fired && available.y != 0f) onEdge()
        fired = false
        return Velocity.Zero
    }
}
