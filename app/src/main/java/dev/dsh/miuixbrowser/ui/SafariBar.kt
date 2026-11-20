package dev.dsh.miuixbrowser.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Safari 样式导航栏（[BarStyles.SafariSplit]）：三块分开的玻璃件——左边圆形「后退」、中间地址栏胶囊、右边圆形「更多」。
 *
 * - 处于首页（[atHome]）时后退按钮置灰不可点；
 * - 地址栏：点击 = 输入网址，长按 = 新建标签页，左右滑动 = 切换标签页，上滑 = 打开标签页列表；
 * - 输入网址时两侧圆钮收起，地址栏占满整行。
 *
 * 只放内容，不画整条底板（底板由 [BarStyle.surface] 负责，这个样式的底板是透明的，三个玻璃件各自画自己的）。
 */
@Composable
internal fun SafariSplitBar(
    metrics: BarMetrics,
    backdrop: LayerBackdrop,
    liquid: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    textureBlurEnabled: Boolean,
    address: String,
    loading: Boolean,
    /** 当前是首页（新标签页 / 主页）：后退不可点 */
    atHome: Boolean,
    canGoBack: Boolean,
    editing: Boolean,
    /** 页面向下滑动后缩成只显示标题的小胶囊 */
    compact: Boolean,
    /** 点小胶囊：恢复三段式 */
    onExpand: () -> Unit,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    onFocusLost: () -> Unit,
    onAddressTap: () -> Unit,
    readerVisible: Boolean,
    readerActive: Boolean,
    onReader: () -> Unit,
    scriptsRunning: Boolean,
    onScripts: () -> Unit,
    download: AddressDownloadState?,
    onDownload: () -> Unit,
    translateVisible: Boolean,
    translateOriginal: Boolean,
    onTranslateToggle: () -> Unit,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onRefresh: () -> Unit,
    /** 长按地址栏：新建标签页 */
    onNewTab: () -> Unit,
    /** 左右滑动地址栏：-1 = 上一个标签，+1 = 下一个标签 */
    onSwitchTab: (Int) -> Unit,
    /** 上滑地址栏：打开标签页列表 */
    onOpenTabs: () -> Unit,
) {
    Crossfade(targetState = compact, animationSpec = tween(180), label = "safariBarCompact") { isCompact ->
        if (isCompact) {
            Box(
                modifier = Modifier.fillMaxWidth().height(metrics.height),
                contentAlignment = Alignment.Center,
            ) {
                SafariCompactPill(
                    title = address,
                    backdrop = backdrop,
                    liquid = liquid,
                    blurEnabled = blurEnabled,
                    textureBlurEnabled = textureBlurEnabled,
                    onClick = onExpand,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.height)
                    .padding(horizontal = metrics.contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!editing) {
                    SafariCircleButton(
                        icon = MiuixIcons.Glass.Back,
                        description = tr("后退"),
                        // 首页不可点；阅读模式中「后退」= 退出阅读模式，所以即使没有历史也可点
                        enabled = !atHome && canGoBack,
                        size = metrics.buttonSize,
                        backdrop = backdrop,
                        liquid = liquid,
                        blurEnabled = blurEnabled,
                        textureBlurEnabled = textureBlurEnabled,
                        onClick = onBack,
                    )
                }
                SafariAddressPill(
                    modifier = Modifier.weight(1f),
                    metrics = metrics,
                    backdrop = backdrop,
                    liquid = liquid,
                    blurEnabled = blurEnabled,
                    textureBlurEnabled = textureBlurEnabled,
                    address = address,
                    loading = loading,
                    editing = editing,
                    onChange = onChange,
                    onGo = onGo,
                    onFocusLost = onFocusLost,
                    onAddressTap = onAddressTap,
                    readerVisible = readerVisible,
                    readerActive = readerActive,
                    onReader = onReader,
                    scriptsRunning = scriptsRunning,
                    onScripts = onScripts,
                    download = download,
                    onDownload = onDownload,
                    translateVisible = translateVisible,
                    translateOriginal = translateOriginal,
                    onTranslateToggle = onTranslateToggle,
                    onRefresh = onRefresh,
                    onNewTab = onNewTab,
                    onSwitchTab = onSwitchTab,
                    onOpenTabs = onOpenTabs,
                )
                if (!editing) {
                    SafariCircleButton(
                        icon = MiuixIcons.Glass.More,
                        description = tr("更多"),
                        enabled = true,
                        size = metrics.buttonSize,
                        backdrop = backdrop,
                        liquid = liquid,
                        blurEnabled = blurEnabled,
                        textureBlurEnabled = textureBlurEnabled,
                        onClick = onMore,
                    )
                }
            }

        }
    }
}

/** 缩小后的标题小胶囊：只显示标题，点一下恢复三段式。 */
@Composable
private fun SafariCompactPill(
    title: String,
    backdrop: LayerBackdrop,
    liquid: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    textureBlurEnabled: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .height(36.dp)
            .widthIn(max = 300.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .glassBar(
                    backdrop = backdrop,
                    corner = 18.dp,
                    blurEnabled = blurEnabled,
                    textureBlurEnabled = textureBlurEnabled,
                    liquid = liquid,
                ),
        )
        Text(
            text = title.ifBlank { tr("搜索或输入网址") },
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
    }
}

/** 圆形玻璃按钮：玻璃底板与图标分层（避免 backdrop 采集层递归包含自身）。 */
@Composable
private fun SafariCircleButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    backdrop: LayerBackdrop,
    liquid: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    textureBlurEnabled: Boolean,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .glassBar(
                    backdrop = backdrop,
                    corner = size / 2,
                    blurEnabled = blurEnabled,
                    textureBlurEnabled = textureBlurEnabled,
                    liquid = liquid,
                ),
        )
        PressIcon(icon = icon, description = description, enabled = enabled, buttonSize = size, onClick = onClick)
    }
}

@Composable
private fun SafariAddressPill(
    modifier: Modifier,
    metrics: BarMetrics,
    backdrop: LayerBackdrop,
    liquid: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    textureBlurEnabled: Boolean,
    address: String,
    loading: Boolean,
    editing: Boolean,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    onFocusLost: () -> Unit,
    onAddressTap: () -> Unit,
    readerVisible: Boolean,
    readerActive: Boolean,
    onReader: () -> Unit,
    scriptsRunning: Boolean,
    onScripts: () -> Unit,
    download: AddressDownloadState?,
    onDownload: () -> Unit,
    translateVisible: Boolean,
    translateOriginal: Boolean,
    onTranslateToggle: () -> Unit,
    onRefresh: () -> Unit,
    onNewTab: () -> Unit,
    onSwitchTab: (Int) -> Unit,
    onOpenTabs: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    // 图标显示条件与原样式一致：翻译 > 下载 > 阅读 > 脚本，同占地址栏最左侧
    val downloadVisible = download?.visible == true
    val showTranslate = shouldShowAddressTranslate(translateVisible = translateVisible, editing = editing)
    val showDownload = shouldShowAddressDownload(downloadVisible = downloadVisible, editing = editing, translateVisible = translateVisible)
    val showReader = shouldShowAddressReader(
        readerVisible = readerVisible,
        editing = editing,
        downloadVisible = downloadVisible,
        translateVisible = translateVisible,
    )
    val showScripts = shouldShowAddressScripts(
        readerVisible = readerVisible,
        scriptsRunning = scriptsRunning,
        editing = editing,
        downloadVisible = downloadVisible,
        translateVisible = translateVisible,
    )
    val showRefresh = shouldShowAddressRefresh(address = address, loading = loading, editing = editing)

    // 拖动时地址栏跟着手指走一小段（橡皮筋），松手弹回；手势在松手时才判定
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val animX by animateFloatAsState(
        targetValue = dragX,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "safariPillX",
    )
    val animY by animateFloatAsState(
        targetValue = dragY,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "safariPillY",
    )
    val latestSwitch by rememberUpdatedState(onSwitchTab)
    val latestOpenTabs by rememberUpdatedState(onOpenTabs)

    Box(
        modifier = modifier
            .height(metrics.addressHeight)
            .graphicsLayer {
                translationX = animX * 0.35f
                // 只跟着上滑走，下拉不动
                translationY = animY.coerceAtMost(0f) * 0.35f
            }
            // 输入网址时不挂手势：横向拖动要留给输入框（移动光标 / 选词）
            .pointerInput(editing) {
                if (editing) return@pointerInput
                val switchPx = 48.dp.toPx()
                val openPx = 40.dp.toPx()
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragX = 0f
                        dragY = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                    },
                    onDragEnd = {
                        val dx = dragX
                        val dy = dragY
                        dragging = false
                        dragX = 0f
                        dragY = 0f
                        if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                            // 竖向为主：上滑打开标签页列表
                            if (dy < -openPx) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestOpenTabs()
                            }
                        } else if (kotlin.math.abs(dx) > switchPx) {
                            // 手指往左滑 = 下一个标签，往右滑 = 上一个标签
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestSwitch(if (dx < 0f) 1 else -1)
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        dragX = 0f
                        dragY = 0f
                    },
                )
            },
    ) {
        // 玻璃底板与内容分层
        Box(
            modifier = Modifier
                .matchParentSize()
                .glassBar(
                    backdrop = backdrop,
                    corner = metrics.addressCorner,
                    blurEnabled = blurEnabled,
                    textureBlurEnabled = textureBlurEnabled,
                    liquid = liquid,
                ),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!editing) {
                // 左槽：固定宽度，没有图标时也占位，标题才能保持居中
                Box(modifier = Modifier.width(AddressReaderWidth), contentAlignment = Alignment.Center) {
                    when {
                        showTranslate -> AddressTranslateButton(original = translateOriginal, onClick = onTranslateToggle)
                        showDownload && download != null -> AddressDownloadButton(state = download, onClick = onDownload)
                        showReader -> AddressReaderButton(active = readerActive, onClick = onReader)
                        showScripts -> AddressScriptButton(onClick = onScripts)
                    }
                }
            }
            if (editing) {
                AddressField(
                    value = address,
                    onChange = onChange,
                    onGo = onGo,
                    editing = true,
                    onStartEdit = onAddressTap,
                    onFocusLost = onFocusLost,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // 标题：居中；点击 = 输入网址，长按 = 新建标签页（只盖标题区，不挡住两侧的小图标）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onAddressTap()
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onNewTab()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val blank = address.isBlank()
                    Text(
                        text = if (blank) tr("搜索或输入网址") else address,
                        fontSize = 16.sp,
                        color = if (blank) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 右槽：加载中是小圆点，加载完是刷新
                Box(modifier = Modifier.width(AddressRefreshWidth), contentAlignment = Alignment.Center) {
                    if (loading) {
                        LoadingDot()
                    } else if (showRefresh) {
                        AddressRefreshButton(onClick = onRefresh)
                    }
                }
            }
        }
    }
}
