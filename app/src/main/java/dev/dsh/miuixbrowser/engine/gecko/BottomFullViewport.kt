/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.gecko

/**
 * Gecko 原生沉浸下「滑到底不触发无限滚动」的修正（纯函数，见 BottomFullViewportTest）。
 *
 * 现象（运行日志实测）：setDynamicToolbarMaxHeight(max) 之后，页面的 innerHeight / visualViewport 恒为
 * 「GeckoView 高度 − max」，但能滑到的位置是页面最底边对齐 GeckoView 底边。于是滑到底时
 * scrollY + innerHeight 永远差 max 个像素（日志里剩余量最小只到 142 ≈ max），页面最底部的「加载更多」哨兵
 * （IntersectionObserver / scrollY + innerHeight >= scrollHeight - 阈值）落在 Gecko 认为的视口之外，永远不触发
 * （Bing 移动版就是这样）。
 *
 * 做法：沉浸已收起（裁剪量为 0）且快到底部时，把最大可收起高度临时设为 0，页面视口 = 整块 GeckoView，
 * 滑到底剩余量归零；离开底部（新内容加载出来或往回滑）再恢复。进入 / 退出用不同阈值，避免在边界来回切换。
 */
internal object BottomFullViewport {
    /** 进入：剩余量 ≤ max + 这个余量（设备像素）。剩余量按「视口 = GeckoView 高度 − max」算，滑到底时约等于 max */
    const val ENTER_SLACK_PX = 100

    /** 退出：剩余量 ≥ 这个值（设备像素）。临时展开后剩余量按整块高度算，比展开前少 max */
    const val EXIT_PX = 500

    /**
     * @param active 当前是否已临时展开
     * @param max 界面层要求的最大可收起高度（0 = 没开原生沉浸）
     * @param clip 界面层要求的底部被推出屏幕的高度（> 0 说明状态栏留白还在，不是收起状态）
     * @param remaining 内容脚本回报的距页面底部剩余像素；< 0 = 没有回报
     */
    fun next(active: Boolean, max: Int, clip: Int, remaining: Int): Boolean {
        if (max <= 0 || clip > 0 || remaining < 0) return false
        return if (active) remaining < EXIT_PX else remaining <= max + ENTER_SLACK_PX
    }
}
