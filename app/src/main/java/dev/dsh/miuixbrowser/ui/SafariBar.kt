package dev.dsh.miuixbrowser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.tanh
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 悬浮三段式导航栏（[BarStyles.SafariSplit]）：三块分开的玻璃件——左边圆形「后退」、中间地址栏胶囊、右边圆形「更多」。
 *
 * - 后退按钮是否可点只看 canGoBack（与默认底栏一致）：空白首页置灰；点主页按钮回到首页后页面仍被盖在下面，可点后退还原；
 * - 地址栏：点击 = 输入网址，长按 = 打开起始页（跟随「定制主页 → 自定义起始页」，带弹性缩放动画），左右滑动 = 切换标签页，上滑 = 打开标签页列表；
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
    searchEngine: dev.dsh.miuixbrowser.data.SearchEngine? = null,
    onEngineClick: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onBack: () -> Unit,
    onMore: () -> Unit,
    onRefresh: () -> Unit,
    /** 长按地址栏：打开起始页（跟随设置里的自定义起始页） */
    onHome: () -> Unit,
    /** 左右滑动地址栏：-1 = 上一个标签，+1 = 下一个标签 */
    onSwitchTab: (Int) -> Unit,
    /** 上滑地址栏：打开标签页列表 */
    onOpenTabs: () -> Unit,
) {
    // 三段式 ↔ 小胶囊是同一套布局的连续变形（不是两套界面交叉淡入淡出）：
    //   p: 0 = 三段式，1 = 小胶囊。后退 / 更多 两个圆钮宽度收到 0、淡出并缩小，
    //   地址栏同时变矮、两侧留白变大，最后收成居中的标题胶囊；反向则是原路展开。
    // 进度 p 只在布局 / 绘制阶段读取，动画期间不会逐帧重组整个栏。
    val morphRaw = animateFloatAsState(
        targetValue = if (compact) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
        label = "safariMorph",
    )
    // 弹簧会过冲（<0 或 >1）：槽宽 (1 - p) * w 一旦变负，layout() 会抛
    // "Size(-1 x ..) is out of range" 导致闪退，所以所有读取方统一拿钳制后的进度。
    val morph = remember {
        derivedStateOf { morphRaw.value.coerceIn(0f, 1f) }
    }
    // 地址栏当前的横向位移（像素）：地址栏写，两侧圆钮读（只在绘制阶段读取）
    val pillShift = remember { mutableFloatStateOf(0f) }
    // 地址栏宽度随内容变化（同默认悬浮底栏），最短 = 「搜索或输入网址」占位文字对应的宽度；
    // 两侧圆钮紧贴地址栏（间距恒定 SafariGap），整组居中，地址栏变短时圆钮随之向内移动。
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val titleStyle = TextStyle(fontSize = 14.sp)
    val placeholderText = tr("搜索或输入网址")
    val placeholderPx = remember(placeholderText) {
        textMeasurer.measure(AnnotatedString(placeholderText), titleStyle, maxLines = 1).size.width
    }
    val titlePx = remember(address, placeholderText) {
        val t = if (address.isBlank()) placeholderText else address
        textMeasurer.measure(AnnotatedString(t), titleStyle, maxLines = 1).size.width
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(metrics.height),
        contentAlignment = Alignment.Center,
    ) {
        val rowWidth = maxWidth - metrics.contentPadding * 2
        // 地址栏内部：左右各 12dp 内边距 + 左槽（翻译 / 下载 / 阅读 / 脚本图标）+ 右槽（刷新 / 加载点）+ 一点余量
        val pillChrome = 12.dp * 2 + 8.dp
        val maxPill = if (editing) rowWidth else rowWidth - (metrics.buttonSize + SafariGap) * 2
        val normalDesired = with(density) { maxOf(placeholderPx, titlePx).toDp() } +
            AddressReaderWidth + AddressRefreshWidth + pillChrome
        val normalPick = if (editing || normalDesired >= maxPill) maxPill else normalDesired
        val normalTarget = if (normalPick > 0.dp) normalPick else 0.dp
        val normalWidth = animateDpAsState(
            targetValue = normalTarget,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            label = "safariPillWidth",
        )
        // 小胶囊：只放标题，宽度按标题文字算（有上下限）
        val compactDesired = with(density) { titlePx.toDp() } + pillChrome
        val compactCap = if (SafariCompactWidth < rowWidth) SafariCompactWidth else rowWidth
        val compactWidth = (if (compactDesired > SafariCompactMinWidth) compactDesired else SafariCompactMinWidth)
            .let { if (it < compactCap) it else compactCap }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.height)
                .padding(horizontal = metrics.contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!editing) {
                CollapseSlot(progress = morph, gap = SafariGap, gapAtStart = false) {
                    SafariCircleButton(
                        icon = MiuixIcons.Glass.Back,
                        description = tr("后退"),
                        // 与默认底栏一致：是否可点只看 canGoBack——空白首页为 false；首页盖住了某个页面时为 true（点后退还原该页面）；
                        // 阅读模式中「后退」= 退出阅读模式，所以即使没有历史也可点
                        enabled = canGoBack,
                        size = metrics.buttonSize,
                        side = -1,
                        shift = pillShift,
                        backdrop = backdrop,
                        liquid = liquid,
                        blurEnabled = blurEnabled,
                        textureBlurEnabled = textureBlurEnabled,
                        onClick = onBack,
                    )
                }
            }
            SafariAddressPill(
                modifier = Modifier,
                metrics = metrics,
                morph = morph,
                normalWidth = normalWidth,
                compactWidth = compactWidth,
                shiftOut = pillShift,
                compact = compact,
                onExpand = onExpand,
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
                searchEngine = searchEngine,
                onEngineClick = onEngineClick,
                onRefresh = onRefresh,
                onHome = onHome,
                onSwitchTab = onSwitchTab,
                onOpenTabs = onOpenTabs,
            )
            if (!editing) {
                CollapseSlot(progress = morph, gap = SafariGap, gapAtStart = true) {
                    SafariCircleButton(
                        icon = MiuixIcons.Glass.More,
                        description = tr("更多"),
                        enabled = true,
                        size = metrics.buttonSize,
                        side = 1,
                        shift = pillShift,
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

private val SafariGap = 7.dp

/** 小胶囊的宽度（屏幕更窄时取屏宽减去两侧留白） */
private val SafariCompactWidth = 240.dp

/** 小胶囊的最小宽度（标题很短时也不再缩小） */
private val SafariCompactMinWidth = 100.dp

/** 小胶囊的高度 */
private val SafariCompactHeight = 26.dp

/** 小胶囊的圆角 */
private val SafariCompactCorner = 13.dp

/**
 * 圆钮的占位槽：宽度（含与地址栏之间的间距）随 [progress] 从 100% 收到 0，同时淡出、缩小。
 * 收起过程中地址栏因此被连续地「挤宽」，不会有跳变。
 */
@Composable
private fun CollapseSlot(
    progress: State<Float>,
    gap: Dp,
    gapAtStart: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val gapPx = gap.roundToPx()
                val w = ((placeable.width + gapPx) * (1f - progress.value.coerceIn(0f, 1f))).roundToInt().coerceAtLeast(0)
                layout(w, placeable.height) { placeable.placeRelative(if (gapAtStart) gapPx else 0, 0) }
            }
            .graphicsLayer {
                // 圆钮在前半段就淡完，后半段只剩宽度在收，视觉上更干脆
                val f = (1f - progress.value * 1.8f).coerceIn(0f, 1f)
                alpha = f
                val sc = 0.6f + 0.4f * f
                scaleX = sc
                scaleY = sc
            },
    ) { content() }
}

/** 固定宽度 [full] 的槽，占位宽度随 [progress] 收到 0（内容按原宽度排版，收起时已淡出）。 */
private fun Modifier.collapseWidth(full: Dp, progress: State<Float>): Modifier = this.layout { measurable, constraints ->
    val fullPx = full.roundToPx()
    val p = measurable.measure(Constraints(minWidth = fullPx, maxWidth = fullPx, minHeight = 0, maxHeight = constraints.maxHeight))
    val w = (fullPx * (1f - progress.value.coerceIn(0f, 1f))).roundToInt().coerceAtLeast(0)
    layout(w, p.height) { p.placeRelative(0, 0) }
}

/** 随变形进度改变圆角的玻璃底板：只有这一层在动画期间重组。 */
@Composable
private fun MorphGlass(
    progress: State<Float>,
    fromCorner: Dp,
    toCorner: Dp,
    backdrop: LayerBackdrop,
    liquid: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    textureBlurEnabled: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .glassBar(
                backdrop = backdrop,
                corner = lerp(fromCorner, toCorner, progress.value),
                blurEnabled = blurEnabled,
                textureBlurEnabled = textureBlurEnabled,
                liquid = liquid,
            ),
    )
}

/**
 * 圆形玻璃按钮：玻璃底板与图标分层（避免 backdrop 采集层递归包含自身）。
 *
 * - 手感与设置页顶栏按钮一致：按下放大、手指拖动时按钮跟手并沿拖动方向拉伸、按下处有高光（[applyLiquidDeform]）；
 * - 地址栏左右滑动（切换标签）时与地址栏有「磁力」互动（[applyMagnet]）：地址栏逼近的这一侧被排斥、挤扁，另一侧被吸住、拉长。
 *
 * [side]：-1 = 左边的按钮，+1 = 右边的按钮。[shift] 是地址栏当前的横向位移（像素，向右为正）。
 */
@Composable
private fun SafariCircleButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    size: Dp,
    side: Int,
    shift: State<Float>,
    backdrop: LayerBackdrop,
    liquid: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    textureBlurEnabled: Boolean,
    onClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(animationScope = scope) }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                applyLiquidDeform(highlight)
                applyMagnet(shift.value, side)
            }
            // 手势挂在父层：图标自己的点击在子层，父层 Initial 阶段照样能看到按下和拖动
            .then(if (enabled) highlight.gestureModifier else Modifier),
        contentAlignment = Alignment.Center,
    ) {
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
        // 按下处的高光：盖在玻璃上、图标下
        if (enabled) {
            Box(modifier = Modifier.matchParentSize().clip(CircleShape).then(highlight.modifier))
        }
        PressIcon(icon = icon, description = description, enabled = enabled, buttonSize = size, onClick = onClick)
    }
}

/**
 * 地址栏左右滑动时对两侧圆钮的磁力：
 * - 地址栏朝这个按钮靠近 → 排斥：按钮被推向屏幕边缘并被挤扁（宽度变小、略变高）；
 * - 地址栏远离这个按钮 → 吸附：按钮被地址栏拉着往里伸长，像被磁铁吸住。
 * 位移用 tanh 做橡皮筋：越拖越吃力，反应幅度有上限；松手后随地址栏的弹簧一起回弹。
 */
private fun GraphicsLayerScope.applyMagnet(shiftPx: Float, side: Int) {
    if (shiftPx == 0f) return
    val t = tanh(shiftPx / 64.dp.toPx())
    // >0：地址栏正朝这个按钮靠近（左钮 + 向左滑；右钮 + 向右滑）
    val toward = t * -side
    // 朝地址栏方向的单位向量（左钮向右，右钮向左）
    val toPill = -side
    val w = size.width
    if (toward > 0f) {
        val sx = 1f - 0.16f * toward
        // 屏幕侧的边缘保持不动，再整体往屏幕边缘推一点
        translationX += toPill * (w * (1f - sx) / 2f - 5.dp.toPx() * toward)
        scaleX *= sx
        scaleY *= 1f + 0.06f * toward
    } else {
        val away = -toward
        val sx = 1f + 0.14f * away
        // 屏幕侧的边缘保持不动，朝地址栏方向被拉长
        translationX += toPill * (w * (sx - 1f) / 2f + 9.dp.toPx() * away)
        scaleX *= sx
        scaleY *= 1f - 0.05f * away
    }
}

@Composable
private fun SafariAddressPill(
    modifier: Modifier,
    metrics: BarMetrics,
    /** 三段式 → 小胶囊的变形进度（0..1），只在布局 / 绘制阶段读取 */
    morph: State<Float>,
    /** 三段式状态下地址栏的宽度（已带弹簧动画），只在布局阶段读取 */
    normalWidth: State<Dp>,
    /** 小胶囊状态下的宽度 */
    compactWidth: Dp,
    /** 把地址栏当前的横向位移（像素）同步给两侧圆钮，做磁力互动 */
    shiftOut: MutableFloatState,
    /** 目标状态是小胶囊：不挂手势，整块点击 = 恢复三段式 */
    compact: Boolean,
    onExpand: () -> Unit,
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
    searchEngine: dev.dsh.miuixbrowser.data.SearchEngine? = null,
    onEngineClick: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onRefresh: () -> Unit,
    onHome: () -> Unit,
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
    LaunchedEffect(Unit) {
        snapshotFlow { animX * 0.35f }.collect { shiftOut.floatValue = it }
    }
    val latestSwitch by rememberUpdatedState(onSwitchTab)
    val latestOpenTabs by rememberUpdatedState(onOpenTabs)
    val latestHome by rememberUpdatedState(onHome)
    // 长按打开起始页的弹性动画：先快速缩小，到底时打开起始页，再带过冲弹回（放大）
    val popScope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val playHomePop: () -> Unit = {
        popScope.launch {
            pop.animateTo(0.82f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessHigh))
            latestHome()
            pop.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessLow))
        }
    }

    Box(
        modifier = modifier
            // 高度随变形进度从 addressHeight 连续收到小胶囊高度
            .layout { measurable, constraints ->
                val from = metrics.addressHeight.roundToPx()
                val to = SafariCompactHeight.roundToPx()
                val h = (from + (to - from) * morph.value).roundToInt()
                val wFrom = normalWidth.value.roundToPx()
                val wTo = compactWidth.roundToPx()
                val w = (wFrom + (wTo - wFrom) * morph.value).roundToInt()
                    .coerceIn(0, constraints.maxWidth)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }
            .graphicsLayer {
                translationX = animX * 0.35f
                // 只跟着上滑走，下拉不动
                translationY = animY.coerceAtMost(0f) * 0.35f
                scaleX = pop.value
                scaleY = pop.value
            }
            // 输入网址时不挂手势：横向拖动要留给输入框（移动光标 / 选词）
            .pointerInput(editing, compact) {
                if (editing || compact) return@pointerInput
                val switchPx = 48.dp.toPx()
                val openPx = 40.dp.toPx()
                // 横向拖过切换阈值时「咔」一下：和圆钮的吸附 / 排斥配合，告诉手指松手就会切换
                var crossed = false
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragX = 0f
                        dragY = 0f
                        crossed = false
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                        val horizontal = kotlin.math.abs(dragX) > kotlin.math.abs(dragY)
                        if (horizontal && !crossed && kotlin.math.abs(dragX) > switchPx) {
                            crossed = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else if (crossed && (!horizontal || kotlin.math.abs(dragX) < switchPx * 0.8f)) {
                            crossed = false
                        }
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
        // 玻璃底板与内容分层（圆角随变形进度从 addressCorner 收到小胶囊圆角）
        MorphGlass(
            progress = morph,
            fromCorner = metrics.addressCorner,
            toCorner = SafariCompactCorner,
            backdrop = backdrop,
            liquid = liquid,
            blurEnabled = blurEnabled,
            textureBlurEnabled = textureBlurEnabled,
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!editing) {
                // 左槽：固定宽度，没有图标时也占位，标题才能保持居中
                Box(
                    modifier = Modifier
                        .collapseWidth(AddressReaderWidth, morph)
                        .graphicsLayer { alpha = (1f - morph.value * 1.8f).coerceIn(0f, 1f) },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        showTranslate -> AddressTranslateButton(original = translateOriginal, onClick = onTranslateToggle)
                        showDownload && download != null -> AddressDownloadButton(state = download, onClick = onDownload)
                        showReader -> AddressReaderButton(active = readerActive, onClick = onReader)
                        showScripts -> AddressScriptButton(onClick = onScripts)
                    }
                }
            }
            if (editing) {
                if (searchEngine != null) {
                    AddressEngineButton(engine = searchEngine, onClick = onEngineClick)
                    Spacer(Modifier.width(6.dp))
                }
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
                // 标题：居中；点击 = 输入网址，长按 = 打开起始页（只盖标题区，不挡住两侧的小图标）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (compact) onExpand() else onAddressTap()
                            },
                            onLongClick = {
                                if (!compact) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    playHomePop()
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val blank = address.isBlank()
                    Text(
                        text = if (blank) tr("搜索或输入网址") else address,
                        fontSize = 14.sp,
                        color = if (blank) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 右槽：加载中是小圆点，加载完是刷新
                Box(
                    modifier = Modifier
                        .collapseWidth(AddressRefreshWidth, morph)
                        .graphicsLayer { alpha = (1f - morph.value * 1.8f).coerceIn(0f, 1f) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        LoadingDot()
                    } else if (showRefresh) {
                        AddressRefreshButton(onClick = onRefresh)
                    }
                }
            }
        }
        if (compact) {
            // 小胶囊状态：盖住两侧已淡出的图标槽，整块点击 = 恢复三段式
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onExpand()
                        },
                    ),
            )
        }
    }
}
