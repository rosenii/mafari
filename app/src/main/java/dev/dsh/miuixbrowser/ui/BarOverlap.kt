/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

/**
 * 停靠（固定）导航栏的「页面深入栏下」判定。纯函数，不依赖 Compose / 引擎，便于单元测试。
 *
 * 固定在顶部 / 底部的栏平时让网页从栏的另一侧展开（页面不进栏下，栏的底板只有纯色）。
 * 开始滑动后让页面深入到栏下面（[next] 返回 true = 深入），栏的玻璃材质才有内容可取样；
 * 滑回页面顶部（顶栏）/ 页面底部（底栏）时恢复（返回 false）。
 *
 *   - 顶栏：离开页面顶部超过 [TOP_OVERLAP_PX] 就深入；回到 [TOP_RESTORE_PX] 以内恢复。
 *   - 底栏：页面在顶部（初始状态）和到达页面底部时让出栏的位置，其余时候深入。
 *     页面底部是否到达取决于「距底部还剩多少像素」，引擎给不出来（[UNKNOWN]）时一律不深入，维持原先的让位布局。
 *
 * 进出用不同的阈值（迟滞）：深入 / 恢复会改变网页区域高度，剩余距离随之跳变，同一个阈值会来回闪。
 */
internal object BarOverlap {
    /** 引擎取不到「距页面底部还剩多少」 */
    const val UNKNOWN = -1

    /** 离开顶部超过这么多像素才算开始滑动（深入栏下） */
    const val TOP_OVERLAP_PX = 24

    /** 深入状态下回到这么近才恢复 */
    const val TOP_RESTORE_PX = 4

    /** 深入状态下，距页面底部不足这么多像素就恢复（让出栏的位置） */
    const val BOTTOM_RESTORE_PX = 24

    /** 恢复状态下，距页面底部超过「栏占位 + 这个余量」才重新深入。余量 > 0 才能避开恢复瞬间剩余距离的跳变 */
    const val BOTTOM_LEAVE_MARGIN_PX = 48

    /**
     * @param edge 栏所在的边（必须是停靠样式）
     * @param overlapped 当前是否已经深入
     * @param scrollY 页面滚动位置（像素，顶部为 0）
     * @param remaining 距页面底部还剩的像素；取不到为 [UNKNOWN]
     * @param barPx 栏占掉网页区域的高度（像素，底栏含导航栏）
     */
    fun next(edge: BarEdge, overlapped: Boolean, scrollY: Int, remaining: Int, barPx: Int): Boolean {
        val y = scrollY.coerceAtLeast(0)
        return when (edge) {
            BarEdge.Top -> if (overlapped) y > TOP_RESTORE_PX else y > TOP_OVERLAP_PX
            BarEdge.Bottom -> {
                if (remaining < 0) return false
                val nearTop = if (overlapped) y <= TOP_RESTORE_PX else y <= TOP_OVERLAP_PX
                val nearBottom = if (overlapped) {
                    remaining <= BOTTOM_RESTORE_PX
                } else {
                    remaining <= barPx.coerceAtLeast(0) + BOTTOM_LEAVE_MARGIN_PX
                }
                !(nearTop || nearBottom)
            }
        }
    }
}
