package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kyant.backdrop.Backdrop as LiquidBackdrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.i18n.tr
import kotlin.math.max
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 标签页 sheet（从 BrowserScreen 拆出）：自动定位、头部、列表三块。
 * 状态仍由 BrowserScreen 持有（tabsPage / 列表状态 / 清空确认），这里只收参数和回调。
 */

/** 打开标签页列表时定位到当前标签：已完整可见就不动，否则才滚过去；之后切换标签再动画滚动。 */
@Composable
internal fun TabsAutoScroll(
    sheetOpen: Boolean,
    tabsPage: Int,
    state: BrowserState,
    tabsStyle: Int,
    listState: LazyListState,
) {
    var tabsOpenScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(sheetOpen, tabsPage, state.activeIndex, tabsStyle) {
        if (!sheetOpen) {
            tabsOpenScrolled = false
            return@LaunchedEffect
        }
        val pageIncognito = tabsPage == 1
        val visible = state.tabs.withIndex().filter { it.value.incognito == pageIncognito }
        val pos = visible.indexOfFirst { it.index == state.activeIndex }
        if (pos >= 0) {
            val row = if (tabsStyle == 1) pos / 2 else pos
            if (!tabsOpenScrolled) {
                // 刚打开：弹层还在滑入，直接定位，不在动画期间再跑一段滚动动画。
                // 先回到顶部（列表状态跨次打开保留），当前标签所在行已经完整可见就不动，
                // 否则标签多时一打开就被「滚到当前标签」顶进头部下面，第一行被头部盖住
                tabsOpenScrolled = true
                runCatching {
                    listState.scrollToItem(0)
                    withFrameNanos { }
                    val info = listState.layoutInfo
                    val target = info.visibleItemsInfo.firstOrNull { it.index == row }
                    val viewportEnd = info.viewportEndOffset - info.afterContentPadding
                    val fullyVisible = target != null && target.offset + target.size <= viewportEnd
                    if (!fullyVisible) listState.scrollToItem(row)
                }
            } else {
                runCatching { listState.animateScrollToItem(row) }
            }
        }
    }
}

/** 头部同一行：新建 | 标签页/无痕浏览页 切换 | 删除。 */
@Composable
internal fun TabsSheetHeader(
    state: BrowserState,
    tabsPage: Int,
    onTabsPageChange: (Int) -> Unit,
    liquid: LiquidBackdrop?,
    /** 柔光玻璃的采样源（页面内容层）；非空且当前是柔光玻璃时，两个圆形按钮用柔光玻璃材质，否则沿用 [liquid] 的材质 */
    softBackdrop: LayerBackdrop? = null,
    onClose: () -> Unit,
    onClearAll: () -> Unit,
) {
    val soft = if (GlassLook.shading()) softBackdrop else null
    val pageCount = state.tabs.count { it.incognito == (tabsPage == 1) }
    // Row 而不是叠放的 Box：中间的分段切换占两侧按钮之外的剩余宽度（最宽 236dp 并居中），
    // 窄屏上只会收缩，不会再和两侧按钮重叠
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TabAddButton(
            canAdd = state.tabs.size < 99,
            backdrop = liquid,
            softBackdrop = soft,
            onClick = {
                state.newTab(incognito = tabsPage == 1)
                onClose()
            },
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TabsSegmented(
                labels = listOf(tr("标签页"), tr("无痕浏览页")),
                selected = tabsPage,
                onSelect = onTabsPageChange,
            )
        }
        TabsHeaderButton(
            onClick = onClearAll,
            liquid = liquid,
            softBackdrop = soft,
            enabled = pageCount > 0,
        ) {
            Icon(
                imageVector = MiuixIcons.Glass.Delete,
                contentDescription = tr("全部删除"),
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 标签列表主体（懒加载网格 / 行）。 */
@Composable
internal fun TabsSheetBody(
    state: BrowserState,
    settings: AppSettings,
    tabsPage: Int,
    listState: LazyListState,
    blurBackdrop: LayerBackdrop?,
    onClose: () -> Unit,
) {
    val sheetInsets = LocalBottomSheetInsets.current
    val haptic = LocalHapticFeedback.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    // 只显示当前栏的标签；index 仍是全局下标，选中/关闭时用
    val pageIncognito = tabsPage == 1
    val visibleTabs = state.tabs.withIndex().filter { it.value.incognito == pageIncognito }
    val cardStyle = settings.tabsStyle == 1
    // 懒加载：只组合/绘制可见行（最多 99 个标签时不再一次性全部组合）
    // 程序化滚动（打开时定位当前标签）不走 NestedScroll：把列表真实位置同步给顶部渐进模糊带
    SyncSheetBandScroll(listState)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = screenH * 0.68f + sheetInsets.top + sheetInsets.bottom),
        contentPadding = PaddingValues(
            top = sheetInsets.top + 4.dp,
            bottom = sheetInsets.bottom + 8.dp,
        ),
        verticalArrangement = if (cardStyle) Arrangement.spacedBy(10.dp) else Arrangement.Top,
    ) {
        if (cardStyle) {
            items(
                items = visibleTabs.chunked(2),
                key = { row -> row.first().value.id },
                contentType = { "tabCardRow" },
            ) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEach { indexed ->
                        TabCard(
                            item = indexed.value,
                            highlight = indexed.index == state.activeIndex,
                            blurBackdrop = blurBackdrop,
                            modifier = Modifier.weight(1f),
                            onSelect = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                state.select(indexed.index)
                                onClose()
                            },
                            onClose = {
                                // 关掉最后一个标签：BrowserState 会按「自定义起始页」补一个新标签，收起列表直接回到它
                                val last = state.tabs.size == 1
                                state.closeTab(indexed.index)
                                if (last) onClose()
                            },
                        )
                    }
                    repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items(
                items = visibleTabs,
                key = { it.value.id },
                contentType = { "tabRow" },
            ) { (index, item) ->
                SheetRow(
                    title = buildString {
                        if (item.incognito) append(tr("无痕 · "))
                        append(item.title.ifBlank { tr("新标签页") })
                    },
                    subtitle = item.url.ifBlank { null },
                    highlight = index == state.activeIndex,
                    trailing = {
                        BarButton(
                            icon = MiuixIcons.Glass.Close,
                            description = tr("关闭"),
                            // 只剩一个标签时也能清除：清掉后按「自定义起始页」新建一个，并收起列表回到它
                            onClick = {
                                val last = state.tabs.size == 1
                                state.closeTab(index)
                                if (last) onClose()
                            },
                        )
                    },
                    onClick = {
                        state.select(index)
                        onClose()
                    },
                )
            }
        }
        if (visibleTabs.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (pageIncognito) tr("没有无痕浏览页") else tr("没有标签页"),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
