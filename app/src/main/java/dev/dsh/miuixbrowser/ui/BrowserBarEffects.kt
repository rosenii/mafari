package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.browser.BrowserTab
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect


/** 底栏自动隐藏：随网页滚动方向收起 / 展开，Gecko 视频全屏时收起，主页与「固定」模式下强制显示；
 *  从 BrowserScreen 拆出，逻辑未改。 */
@Composable
internal fun BrowserUiState.BarAutoHideEffects(
    tab: BrowserTab,
    barFixed: Boolean,
    /** 固定（停靠）栏所在的边；悬浮样式为 null */
    dockedEdge: BarEdge?,
    /** 停靠栏占掉网页区域的高度（像素；底栏含导航栏）。读取函数：取值依赖当前窗口 insets */
    dockedBarPx: () -> Int,
    /** Safari 样式：滑动页面时栏缩成小胶囊（[barCompact]），而不是隐藏；此时页面深入栏下（[barOverlap] 跟随 [barCompact]） */
    compactOnScroll: Boolean = false,
) {
    // Gecko 视频全屏：应用自己的顶栏 / 底栏一并收起，退出全屏后恢复
    LaunchedEffect(tab.id, tab.geckoFullscreen) {
        barHidden = tab.geckoFullscreen
    }

    // 回到主页（页面被盖住 / 没有页面）时底栏必须显示：主页自己没有滚动采样，
    // 之前页面滑动收起底栏后的 barHidden 会一直残留，导致主页上唤不出底栏
    LaunchedEffect(tab.id, tab.url.isEmpty()) {
        if (tab.url.isEmpty()) barHidden = false
    }
    // 兜底：主页上 barHidden 任何时候变成 true（比如在底栏上向下拖）都立刻复位。
    // 主页没有滚动手势能把底栏唤回来，收起后底栏会从组合树里移除，再也点不到、拖不到。
    LaunchedEffect(barHidden, tab.url.isEmpty(), tab.geckoFullscreen) {
        if (barHidden && tab.url.isEmpty() && !tab.geckoFullscreen) barHidden = false
    }

    // 切到「固定」立即把已收起的底栏显示出来（视频全屏除外）；离开固定样式则页面不再深入栏下
    LaunchedEffect(barFixed) {
        if (barFixed) {
            barHidden = tab.geckoFullscreen
        } else {
            barOverlap = false
        }
    }

    // 离开 Safari 样式：缩小状态复位
    LaunchedEffect(compactOnScroll) {
        if (!compactOnScroll) barCompact = false
    }

    LaunchedEffect(tab.id, barFixed, dockedEdge, compactOnScroll) {
        if (compactOnScroll) barCompact = false // 换标签：先展开，再按新标签的滚动重新判定
        var last = 0
        var lastShow = 0
        var scrollDownAccum = 0f
        var scrollUpAccum = 0f
        while (true) {
            runCatching {
                if (tab.geckoFullscreen) {
                    barHidden = true
                    return@runCatching
                }
                // Safari 样式：栏固定显示，但随滚动方向在「完整三段式」和「标题小胶囊」之间切换；
                // 缩小时页面深入栏下（网页铺满屏幕），展开时让出栏的位置。阈值与自动隐藏一致
                if (barFixed && compactOnScroll) {
                    barHidden = false
                    if (tab.url.isEmpty()) {
                        barCompact = false
                        barOverlap = false
                        last = 0
                        lastShow = 0
                        scrollDownAccum = 0f
                        scrollUpAccum = 0f
                        return@runCatching
                    }
                    val offset = tab.scrollOffsetY.coerceAtLeast(0)
                    val dy = offset - last
                    when {
                        offset <= 12 -> {
                            barCompact = false
                            lastShow = offset
                            scrollDownAccum = 0f
                            scrollUpAccum = 0f
                        }
                        dy > 0 -> {
                            scrollUpAccum = 0f
                            scrollDownAccum += dy
                            if (scrollDownAccum >= 100f && offset - lastShow >= 96f) {
                                barCompact = true
                                scrollDownAccum = 0f
                                scrollUpAccum = 0f
                            }
                        }
                        dy < 0 -> {
                            scrollDownAccum = 0f
                            scrollUpAccum += -dy
                            if (barCompact && scrollUpAccum >= 120f) {
                                barCompact = false
                                lastShow = offset
                                scrollUpAccum = 0f
                            } else if (!barCompact) {
                                lastShow = offset
                            }
                        }
                    }
                    last = offset
                    barOverlap = barCompact
                    return@runCatching
                }
                // 固定模式：不做滚动隐藏；但页面要随滚动位置深入 / 退出栏下（判定见 BarOverlap）。
                // 主页（没有滚动）、视频全屏以外才判定；换页后偏移被重置，回到顶部自然恢复
                if (barFixed) {
                    barHidden = false
                    barOverlap = if (dockedEdge == null || tab.url.isEmpty()) {
                        false
                    } else {
                        BarOverlap.next(
                            edge = dockedEdge,
                            overlapped = barOverlap,
                            scrollY = tab.scrollOffsetY,
                            remaining = tab.scrollRemainingY,
                            barPx = dockedBarPx(),
                        )
                    }
                    return@runCatching
                }
                // 主页（页面被盖住）上：引擎里的页面可能还在惯性滚动 / 回调滚动事件，
                // 不能让它把底栏重新收起，否则主页上没有任何手势能唤出底栏
                if (tab.url.isEmpty()) {
                    barHidden = false
                    last = tab.scrollOffsetY.coerceAtLeast(0)
                    lastShow = last
                    scrollDownAccum = 0f
                    scrollUpAccum = 0f
                    return@runCatching
                }
                val offset = tab.scrollOffsetY.coerceAtLeast(0)
                val dy = offset - last
                when {
                    offset <= 12 -> {
                        barHidden = false
                        lastShow = offset
                        scrollDownAccum = 0f
                        scrollUpAccum = 0f
                    }
                    dy > 0 -> {
                        scrollUpAccum = 0f
                        scrollDownAccum += dy
                        // 向下累计约 100px 且离开顶部足够远才隐藏，降低误触
                        if (scrollDownAccum >= 100f && offset - lastShow >= 96f) {
                            barHidden = true
                            scrollDownAccum = 0f
                            scrollUpAccum = 0f
                        }
                    }
                    dy < 0 -> {
                        scrollDownAccum = 0f
                        // 隐藏后须向上累计约 120px 才恢复，避免轻微回弹就弹出
                        scrollUpAccum += -dy
                        if (barHidden && scrollUpAccum >= 120f) {
                            barHidden = false
                            lastShow = offset
                            scrollUpAccum = 0f
                        } else if (!barHidden) {
                            lastShow = offset
                        }
                    }
                }
                last = offset
            }
            // 纯事件驱动：只在引擎滚动事件（CONFLATED 合并，只取最新位置）或下面补发的信号到来时处理一次。
            // 底栏阈值按 dy 累计，与采样间隔无关，所以不再需要 80ms 定时采样，也不再有静止时的超时唤醒。
            tab.scrollSignal.receive()
        }
    }
    // 没有滚动事件、但偏移 / 状态可能已变的时刻补发一次信号：全屏切换、换页、加载状态变化（导航后偏移被重置）
    LaunchedEffect(tab.id, tab.geckoFullscreen, tab.url, tab.loading) {
        tab.scrollSignal.trySend(Unit)
    }
}
