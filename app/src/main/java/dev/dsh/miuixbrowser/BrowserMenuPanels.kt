package dev.dsh.miuixbrowser

import android.app.Activity
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.drag
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.glass.Settings
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Phone
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.HorizontalSplit
import top.yukonga.miuix.kmp.icon.glass.Hide
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassMaterials
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassStrokes
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 更多面板：图标 + 文字宫格。长按任一图标进入编辑：面板自动拉长，底部出现高光分割线，
 * 线下方是「隐藏的功能」区；把图标拖到线下方即从菜单隐藏，再拖回线上方即恢复。
 */
/** 更多菜单里长按进入管理页（脚本 / 收藏 / 书签）所需的按住时长，含系统长按判定时间 */
internal const val MENU_LONG_PRESS_MS = 650L

@Composable
internal fun MenuGrid(
    labelFor: (MenuAction) -> String,
    order: List<MenuAction>,
    hidden: List<MenuAction>,
    onAction: (MenuAction) -> Unit,
    onMove: (MenuAction, Boolean, Int) -> Unit,
    onLongPressAction: (MenuAction) -> Unit = {},
    longPressActions: Set<MenuAction> = emptySet(),
    incognitoActive: Boolean = false,
    badgeFor: (MenuAction) -> Int = { 0 },
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val cellHeight = 88.dp
    val dividerHeight = 30.dp
    val columns = 4
    val purple = Color(0xFF9C27B0)
    val gridScope = rememberCoroutineScope()
    // 长按计时：按下满 [MENU_LONG_PRESS_MS] 且手指没动（没进入拖动排序）才触发长按功能
    val systemLongPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val longPressNow by rememberUpdatedState(onLongPressAction)

    var editing by remember { mutableStateOf(false) }
    var dragAction by remember { mutableStateOf<MenuAction?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // 长按后、尚未拖够距离时的累计位移：超过阈值才真正进入编辑，避免手指轻微抖动误触发
    var pendingDrag by remember { mutableStateOf(Offset.Zero) }
    val enterEditThresholdPx = with(density) { 24.dp.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellWidthPx = with(density) { (maxWidth / columns).toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }
        val dividerPx = with(density) { dividerHeight.toPx() }
        val visibleRows = ((order.size + columns - 1) / columns).coerceAtLeast(1)
        val hiddenRows = ((hidden.size + columns - 1) / columns).coerceAtLeast(1)

        // 拖拽结束时按「图标中心落在哪」决定去哪个区、第几格（用最新的列表尺寸）
        val finishDrag by rememberUpdatedState { action: MenuAction, fromHidden: Boolean, row: Int, col: Int ->
            val visibleBottom = visibleRows * cellHeightPx
            val hiddenTop = visibleBottom + dividerPx
            val originY = if (fromHidden) hiddenTop + row * cellHeightPx else row * cellHeightPx
            val cx = col * cellWidthPx + cellWidthPx / 2 + dragOffset.x
            val cy = originY + cellHeightPx / 2 + dragOffset.y
            val toHidden = cy >= visibleBottom + dividerPx / 2
            val rowT = ((if (toHidden) cy - hiddenTop else cy) / cellHeightPx).toInt().coerceAtLeast(0)
            val colT = (cx / cellWidthPx).toInt().coerceIn(0, columns - 1)
            onMove(action, toHidden, rowT * columns + colT)
        }
        val startDrag by rememberUpdatedState { fromHidden: Boolean ->
            // 底部弹层向上生长：第一次进入编辑时补偿增加的高度，图标才不会脱离手指
            dragOffset = if (!editing && !fromHidden) {
                Offset(0f, dividerPx + hiddenRows * cellHeightPx)
            } else {
                Offset.Zero
            }
            editing = true
        }

        @Composable
        fun Cells(items: List<MenuAction>, inHidden: Boolean) {
            items.chunked(columns).forEachIndexed { row, rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (rowItems.any { it == dragAction }) 1f else 0f),
                ) {
                    rowItems.forEachIndexed { col, action ->
                        MenuCell(
                            modifier = Modifier.weight(1f),
                            action = action,
                            label = labelFor(action),
                            badge = badgeFor(action),
                            cellHeight = cellHeight,
                            tint = if (action == MenuAction.Incognito && incognitoActive) purple
                            else MiuixTheme.colorScheme.onSurface,
                            dragged = dragAction == action,
                            dragOffset = { dragOffset },
                            dimmed = (dragAction != null && dragAction != action) || (inHidden && dragAction == null),
                            onClick = {
                                if (!editing) onAction(action)
                            },
                            onDragStart = {
                                // 长按不进编辑；等 onDrag 出现真实位移再展开隐藏区。
                                // 系统长按（约 0.4s）后进入这里，再补足到 [MENU_LONG_PRESS_MS]（0.65s）
                                holdJob?.cancel()
                                if (action in longPressActions && !editing) {
                                    holdJob = gridScope.launch {
                                        kotlinx.coroutines.delay((MENU_LONG_PRESS_MS - systemLongPressMs).coerceAtLeast(0L))
                                        if (dragAction == null && !editing && pendingDrag.getDistance() < enterEditThresholdPx) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            longPressNow(action)
                                        }
                                    }
                                }
                            },
                            onDrag = { delta ->
                                if (delta.x != 0f || delta.y != 0f) {
                                    if (dragAction == null) {
                                        // 已在编辑态：直接跟手；否则累计位移，拖过阈值才进入编辑
                                        pendingDrag += delta
                                        if (editing || pendingDrag.getDistance() >= enterEditThresholdPx) {
                                            dragAction = action
                                            if (!editing) {
                                                startDrag(inHidden)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            dragOffset += pendingDrag
                                            pendingDrag = Offset.Zero
                                        }
                                    } else {
                                        dragOffset += delta
                                    }
                                }
                            },
                            onDragEnd = {
                                holdJob?.cancel()
                                if (editing && dragAction == action) {
                                    finishDrag(action, inHidden, row, col)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                dragAction = null
                                dragOffset = Offset.Zero
                                pendingDrag = Offset.Zero
                            },
                            onDragCancel = {
                                holdJob?.cancel()
                                dragAction = null
                                dragOffset = Offset.Zero
                                pendingDrag = Offset.Zero
                            },
                        )
                    }
                    repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 6.dp)) {
            Cells(order, inHidden = false)
            if (editing) {
                Box(modifier = Modifier.fillMaxWidth().height(dividerHeight)) {
                    Text(
                        text = "隐藏的功能（拖到线下方隐藏）",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 6.dp),
                    )
                    Text(
                        text = "完成",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clickable { editing = false }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.95f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }
                if (hidden.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(cellHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "把不常用的功能拖到这里",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    Cells(hidden, inHidden = true)
                }
            }
        }
    }
}

@Composable
internal fun MenuCell(
    modifier: Modifier,
    action: MenuAction,
    label: String,
    badge: Int,
    cellHeight: androidx.compose.ui.unit.Dp,
    tint: Color,
    dragged: Boolean,
    dragOffset: () -> Offset,
    dimmed: Boolean,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    Box(
        modifier = modifier
            .height(cellHeight)
            .graphicsLayer {
                if (dragged) {
                    val offset = dragOffset()
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
                alpha = if (dimmed) 0.6f else 1f
            }
            .pointerInput(action) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start() },
                    onDrag = { change, delta ->
                        change.consume()
                        drag(delta)
                    },
                    onDragEnd = { end() },
                    onDragCancel = { cancel() },
                )
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 统一 32dp 的图标槽，各格子文字基线一致；脚本图标本身线条偏细偏小，单独放大
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(if (action == MenuAction.ScriptMenu) 31.dp else 26.dp),
                )
                if (badge > 0) {
                    CountBadge(
                        count = badge,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 7.dp, y = (-4).dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(text = label, fontSize = 11.sp, color = tint, maxLines = 1)
        }
    }
}

/** 标签卡片。[blurBackdrop] 非空时标题条使用 miuix-glass 模糊底，否则退回半透明纯色底。[highlight] 当前页高光描边。 */
@Composable
internal fun TabCard(
    item: BrowserTab,
    blurBackdrop: LayerBackdrop?,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val thumb = item.thumbnail
    val cardColor = if (item.incognito) Color(0xFF3D2B4F) else MiuixTheme.colorScheme.surfaceContainerHigh
    val highlightColor = MiuixTheme.colorScheme.primary
    Box(
        modifier = modifier
            .height(160.dp)
            .then(
                if (highlight) {
                    Modifier.shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(16.dp),
                        spotColor = highlightColor,
                        ambientColor = highlightColor,
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
            .then(
                if (highlight) {
                    Modifier.border(2.dp, highlightColor, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onSelect),
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.45f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (blurBackdrop != null) {
                            Modifier.glassPanel(
                                backdrop = blurBackdrop,
                                shape = GlassShape(0.dp),
                                style = GlassDefaults.style(isAppDarkTheme()),
                                material = GlassMaterials.puredThinGlass(isAppDarkTheme()),
                                stroke = GlassStrokes.forTheme(isAppDarkTheme()),
                                shading = false,
                            )
                        } else {
                            Modifier.background(MiuixTheme.colorScheme.surface.copy(alpha = 0.55f))
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = buildString {
                        if (item.incognito) append("无痕 · ")
                        append(item.title.ifBlank { "新标签页" })
                    },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (item.url.isBlank()) "主页" else hostOf(item.url),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, minWidth = 28.dp, minHeight = 28.dp) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** 标签页 sheet 左上角圆形新建按钮：在当前栏（标签页 / 无痕浏览页）新建，无长按操作。 */
@Composable
internal fun TabAddButton(
    canAdd: Boolean,
    onClick: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop?,
    modifier: Modifier = Modifier,
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = canAdd,
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.Add,
            contentDescription = "新建标签页",
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 紧凑分段切换：一个圆角底，选中块弹簧滑动（标签页 / 无痕浏览页）。 */
@Composable
internal fun TabsSegmented(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val thumbPosition by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "tabsSegmentedThumb",
    )
    val container = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val thumb = MiuixTheme.colorScheme.surface
    Row(
        modifier = modifier
            .width(236.dp)
            .height(40.dp)
            .clip(CircleShape)
            .background(container)
            .drawBehind {
                val segment = size.width / labels.size
                val inset = 4.dp.toPx()
                drawRoundRect(
                    color = thumb,
                    topLeft = Offset(segment * thumbPosition + inset, inset),
                    size = Size(segment - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            },
    ) {
        labels.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (index != selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = if (index == selected) FontWeight.Medium else FontWeight.Normal,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
