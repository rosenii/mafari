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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.geometry.Rect
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
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassStrokes
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ------------------------------ 主页 ------------------------------ */

/** 首页内容块的拖动排序状态（收藏整体算一个块）。 */
internal class HomeReorderState {
    var dragging by mutableIntStateOf(-1)
    var offset by mutableFloatStateOf(0f)
    var live by mutableStateOf<List<Int>>(emptyList())
    var suppressClick = false
    /** 刚切换了「固定到底部」：下一次布局时让卡片从原来的位置平滑滑到新位置。 */
    var pinShiftPending = false
    /** 长按过首页卡片后进入的编辑态：卡片右上角显示 ×，点空白处 / 任意卡片退出。 */
    var homeEditing by mutableStateOf(false)
    var colWindowTop = 0f
    var rootTop = 0f
    var rootHeight = 0f
    val tops = HashMap<Int, Float>()
    val heights = HashMap<Int, Float>()
    private val anims = HashMap<Int, Animatable<Float, AnimationVector1D>>()

    fun animFor(kind: Int): Animatable<Float, AnimationVector1D> = anims.getOrPut(kind) { Animatable(0f) }

    /** 被拖块的中线越过相邻块的中线就交换位置，并补偿偏移让它始终跟手。 */
    fun swapIfNeeded(gapPx: Float) {
        val k = dragging
        val list = live
        val i = list.indexOf(k)
        if (i < 0) return
        val kTop = tops[k] ?: return
        val kH = heights[k] ?: return
        val center = kTop + offset + kH / 2f
        if (i > 0) {
            val p = list[i - 1]
            val pTop = tops[p] ?: return
            val pH = heights[p] ?: return
            if (center < pTop + pH / 2f) {
                offset += kTop - pTop
                tops[p] = pTop + kH + gapPx
                tops[k] = pTop
                live = list.toMutableList().also { it[i - 1] = k; it[i] = p }
                return
            }
        }
        if (i < list.lastIndex) {
            val n = list[i + 1]
            val nH = heights[n] ?: return
            val nTop = tops[n] ?: return
            if (center > nTop + nH / 2f) {
                val newTop = kTop + nH + gapPx
                offset += kTop - newTop
                tops[n] = kTop
                tops[k] = newTop
                live = list.toMutableList().also { it[i] = n; it[i + 1] = k }
            }
        }
    }
}

/** 记录块位置 / 高度；顺序变化时其它块平滑让位，被拖块跟随手指。 */
@Composable
internal fun Modifier.homeReorderItem(kind: Int, st: HomeReorderState): Modifier {
    val scope = rememberCoroutineScope()
    val anim = remember(kind) { st.animFor(kind) }
    val lastTop = remember { floatArrayOf(Float.NaN) }
    return this
        .onGloballyPositioned { c ->
            val top = c.positionInParent().y
            st.heights[kind] = c.size.height.toFloat()
            val prev = lastTop[0]
            if (!prev.isNaN() && prev != top && st.dragging != kind) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    anim.snapTo(anim.value + prev - top)
                    anim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
            lastTop[0] = top
            st.tops[kind] = top
        }
        .graphicsLayer {
            val d = st.dragging == kind
            translationY = if (d) st.offset else anim.value
            val sc = if (d) 1.02f else 1f
            scaleX = sc
            scaleY = sc
        }
        .zIndex(if (st.dragging == kind) 1f else 0f)
}

/** 长按后上下拖动的手势；松手时把新顺序交给 [onCommit]。 */
@Composable
internal fun Modifier.homeReorderHandle(
    kind: Int,
    st: HomeReorderState,
    visible: List<Int>,
    gapPx: Float,
    onCommit: (List<Int>) -> Unit,
    /** 松手时回调这次拖动的净位移（px，向下为正）；只剩一张卡片时用来判断是否固定到底部。 */
    onDrop: (Float) -> Unit = {},
): Modifier {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val visibleNow by rememberUpdatedState(visible)
    val commit by rememberUpdatedState(onCommit)
    val dropNow by rememberUpdatedState(onDrop)
    val finish = {
        if (st.dragging == kind) {
            val anim = st.animFor(kind)
            val net = st.offset
            commit(st.live)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                anim.snapTo(st.offset)
                st.dragging = -1
                st.offset = 0f
                anim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            }
            scope.launch {
                delay(250)
                st.suppressClick = false
            }
            dropNow(net)
        }
    }
    return this.pointerInput(kind) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                st.suppressClick = true
                st.homeEditing = true
                st.live = visibleNow
                st.offset = st.animFor(kind).value
                st.dragging = kind
            },
            onDrag = { change, amount ->
                change.consume()
                st.offset += amount.y
                st.swapIfNeeded(gapPx)
            },
            onDragEnd = { finish() },
            onDragCancel = { finish() },
        )
    }
}

/** 把「可见块的新顺序」合并回完整顺序：隐藏块的位置保持不变。 */
internal fun mergeHomeOrder(full: List<Int>, visible: List<Int>): List<Int> {
    val it = visible.iterator()
    return full.map { k -> if (k in visible && it.hasNext()) it.next() else k }
}

@Composable
internal fun StartPage(
    scrollState: ScrollState,
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    favorites: List<Favorite>,
    showFavorites: Boolean,
    /** 个人收藏在首页显示的行数（每行 3 个） */
    favRows: Int = HOME_FAV_ROWS_DEFAULT,
    /** 点击「更多」：跳转书签页 */
    onOpenBookmarks: () -> Unit = {},
    showPrivacy: Boolean,
    showRecent: Boolean,
    showDownloads: Boolean,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    order: List<Int>,
    onReorder: (List<Int>) -> Unit,
    recent: List<HistoryEntry>,
    onOpenDownloads: () -> Unit,
    onHide: (kind: Int) -> Unit,
    editing: Boolean,
    onOpen: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
    /** 当前标签是 Gecko 内核：隐私报告改为显示 Gecko 高级保护清单 */
    geckoMode: Boolean = false,
    /** 首页顶部的 LOGO 标识 */
    showLogo: Boolean = true,
    /** 底部导航栏被设为靠上：首页内容顶部让出栏的位置，底部不再留栏的空间 */
    barOnTop: Boolean = false,
    /** 「书签分组」卡片 */
    showGroups: Boolean = false,
    groups: List<HomeGroup> = emptyList(),
    /** 书签分组显示的行数（每行 [HOME_GROUP_COLS] 个）。 */
    groupRows: Int = HOME_GROUP_ROWS_DEFAULT,
    groupEditing: Boolean = false,
    /** 点击分组磁贴：参数为分组路径与磁贴图标的屏幕坐标，由上层在图标下方弹出书签窗口。 */
    onGroupOpen: (path: String, anchor: Rect) -> Unit = { _, _ -> },
    onGroupToggleEdit: () -> Unit = {},
    onGroupRemove: (Int) -> Unit = {},
    onGroupMove: (Int, Int) -> Unit = { _, _ -> },
    onGroupAdd: () -> Unit = {},
    /** 只剩一张卡片可见时是否固定在底部；长按拖动该卡片往下 / 往上切换。 */
    pinBottom: Boolean = false,
    onPinBottomChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 14.dp.toPx() }
    val pinScope = rememberCoroutineScope()
    val hasWallpaper = wallpaper != null
    val st = remember { HomeReorderState() }
    val visibleKinds = order.filter {
        when (it) {
            HOME_FAVORITES -> showFavorites
            HOME_GROUPS -> showGroups
            HOME_PRIVACY -> showPrivacy
            HOME_RECENT -> showRecent
            else -> showDownloads
        }
    }
    val shown = if (st.dragging >= 0) st.live else visibleKinds
    val commit: (List<Int>) -> Unit = { onReorder(mergeHomeOrder(order, it)) }
    // 只剩一张卡片可见且设为固定：卡片贴底（LOGO 仍在顶部）
    val pinned = pinBottom && visibleKinds.size == 1
    // 只剩一张卡片时，松手前净位移超过阈值：往下拖 = 固定到底部，往上拖 = 取消固定
    val pinThresholdPx = with(density) { 48.dp.toPx() }
    val onDrop: (Float) -> Unit = { net ->
        if (visibleKinds.size == 1) {
            val target = when {
                !pinned && net > pinThresholdPx -> true
                pinned && net < -pinThresholdPx -> false
                else -> pinBottom
            }
            if (target != pinBottom) {
                st.pinShiftPending = true
                onPinBottomChange(target)
            }
        }
    }
    // 长按拖动结束后的短时间内忽略点击，避免松手触发卡片 / 条目的点击
    // 编辑态下的点击只用来退出编辑
    val guard: (() -> Unit) -> (() -> Unit) = { f ->
        {
            if (!st.suppressClick) {
                if (st.homeEditing) st.homeEditing = false else f()
            }
        }
    }

    // 拖到视口边缘时自动滚动
    LaunchedEffect(st.dragging) {
        if (st.dragging < 0) return@LaunchedEffect
        val topEdge = with(density) { 90.dp.toPx() }
        val bottomEdge = with(density) { 150.dp.toPx() }
        while (st.dragging >= 0) {
            val k = st.dragging
            val top = st.colWindowTop - st.rootTop + (st.tops[k] ?: 0f) + st.offset
            val bottom = top + (st.heights[k] ?: 0f)
            val limit = st.rootHeight - bottomEdge
            val dy = when {
                top < topEdge -> -(topEdge - top) / topEdge * 24f
                st.rootHeight > 0f && bottom > limit -> (bottom - limit) / bottomEdge * 24f
                else -> 0f
            }.coerceIn(-24f, 24f)
            if (dy != 0f) {
                val consumed = scrollState.scrollBy(dy)
                if (consumed != 0f) {
                    st.offset += consumed
                    st.swapIfNeeded(gapPx)
                }
            }
            delay(16)
        }
    }

    // 根 Box 吸收空白区点击，避免穿透到下层 WebView（子项仍优先命中）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                st.rootTop = it.positionInWindow().y
                st.rootHeight = it.size.height.toFloat()
            }
            .pointerInput(Unit) {
                detectTapGestures { st.homeEditing = false } // 吸收空白处点击，并退出编辑态
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                // 底栏在上方时（浮在状态栏下 ≈ 70dp）顶部让位，底部不用再留底栏的空间
                .padding(top = if (barOnTop) 114.dp else 44.dp, bottom = if (barOnTop) 40.dp else 150.dp),
            // 外层 Column 的最小高度 = 视口高度（fillMaxSize + verticalScroll），所以固定时用排列方式把卡片推到底部
            verticalArrangement = when {
                !pinned -> Arrangement.Top
                showLogo -> Arrangement.SpaceBetween
                else -> Arrangement.Bottom
            },
        ) {
            if (showLogo) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassLogoText("Mafari", fontSize = 58, wallpaper = wallpaper)
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        val newTop = it.positionInWindow().y
                        val prevTop = st.colWindowTop
                        st.colWindowTop = newTop
                        if (st.pinShiftPending) {
                            st.pinShiftPending = false
                            // 固定 / 取消固定后卡片的布局位置变了：从原来的视觉位置平滑滑过去，而不是瞬移
                            val only = visibleKinds.firstOrNull()
                            if (only != null && st.dragging < 0) {
                                val anim = st.animFor(only)
                                pinScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    anim.snapTo(anim.value + prevTop - newTop)
                                    anim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                }
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                shown.forEach { kind ->
                    key(kind) {
                        val itemMod = Modifier.homeReorderItem(kind, st)
                        val handle = Modifier.homeReorderHandle(kind, st, visibleKinds, gapPx, commit, onDrop)
                        when (kind) {
                            HOME_FAVORITES -> FavoritesBlock(
                                modifier = itemMod,
                                headerModifier = handle,
                                favorites = favorites,
                                rows = favRows,
                                onOpenBookmarks = onOpenBookmarks,
                                wallpaper = wallpaper,
                                wallpaperBackdrop = wallpaperBackdrop,
                                liquidBackdrop = liquidBackdrop,
                                blurEnabled = blurEnabled,
                                editing = editing,
                                guard = guard,
                                onOpen = onOpen,
                                onToggleEdit = onToggleEdit,
                                onRemove = onRemove,
                                onMove = onMove,
                                onEdit = onEdit,
                                onAdd = onAdd,
                            )
                            HOME_GROUPS -> GroupsBlock(
                                modifier = itemMod,
                                headerModifier = handle,
                                groups = groups,
                                rows = groupRows,
                                wallpaper = wallpaper,
                                wallpaperBackdrop = wallpaperBackdrop,
                                liquidBackdrop = liquidBackdrop,
                                blurEnabled = blurEnabled,
                                editing = groupEditing,
                                guard = guard,
                                onOpen = onGroupOpen,
                                onToggleEdit = onGroupToggleEdit,
                                onRemove = onGroupRemove,
                                onMove = onGroupMove,
                                onAdd = onGroupAdd,
                            )
                            HOME_PRIVACY -> PrivacyReportCard(
                                geckoMode = geckoMode,
                                modifier = itemMod.then(handle),
                                wallpaperBackdrop = wallpaperBackdrop,
                                liquidBackdrop = liquidBackdrop,
                                blurEnabled = blurEnabled,
                                hasWallpaper = hasWallpaper,
                                showRemove = st.homeEditing,
                                onClick = guard {},
                                onRemove = { onHide(HOME_PRIVACY) },
                            )
                            HOME_RECENT -> HomeCard(
                                modifier = itemMod.then(handle),
                                title = "最近浏览",
                                wallpaperBackdrop = wallpaperBackdrop,
                                liquidBackdrop = liquidBackdrop,
                                blurEnabled = blurEnabled,
                                hasWallpaper = hasWallpaper,
                                showRemove = st.homeEditing,
                                onClick = guard {},
                                onRemove = { onHide(HOME_RECENT) },
                            ) {
                                val items = recent.take(3)
                                if (items.isEmpty()) {
                                    HomeEmptyText("还没有浏览记录")
                                } else {
                                    items.forEach { entry ->
                                        HomeRow(
                                            primary = entry.title.ifBlank { entry.url },
                                            secondary = runCatching { android.net.Uri.parse(entry.url).host }.getOrNull().orEmpty(),
                                            onClick = guard { onOpen(entry.url) },
                                        )
                                    }
                                }
                            }
                            else -> HomeCard(
                                modifier = itemMod.then(handle),
                                title = "下载项",
                                wallpaperBackdrop = wallpaperBackdrop,
                                liquidBackdrop = liquidBackdrop,
                                blurEnabled = blurEnabled,
                                hasWallpaper = hasWallpaper,
                                showRemove = st.homeEditing,
                                onRemove = { onHide(HOME_DOWNLOADS) },
                                onClick = guard(onOpenDownloads),
                            ) {
                                val tasks = Downloader.tasks.toList().takeLast(3).reversed()
                                if (tasks.isEmpty()) {
                                    HomeEmptyText("还没有下载任务")
                                } else {
                                    tasks.forEach { t ->
                                        HomeRow(
                                            primary = t.name,
                                            secondary = when (t.state) {
                                                DownloadState.RUNNING -> "下载中 ${(Downloader.progressOf(t) * 100).toInt()}%"
                                                DownloadState.PAUSED -> "已暂停"
                                                DownloadState.DONE -> "已完成"
                                                DownloadState.FAILED -> "失败"
                                            },
                                            onClick = guard(onOpenDownloads),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 个人收藏：整体作为一个大块参与排序；长按「收藏」标题行拖动，磁贴长按仍是进入编辑。 */
@Composable
internal fun FavoritesBlock(
    modifier: Modifier,
    headerModifier: Modifier,
    favorites: List<Favorite>,
    rows: Int,
    onOpenBookmarks: () -> Unit,
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    editing: Boolean,
    guard: (() -> Unit) -> (() -> Unit),
    onOpen: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HomeBlockHeader(title = "收藏", headerModifier = headerModifier, editing = editing, onDone = onToggleEdit)

        Spacer(Modifier.height(6.dp))
        // 容量 = 行数 × 3；超出时最后一格让给「更多」
        val capacity = rows.coerceIn(1, HOME_FAV_ROWS_MAX) * 3
        val overflow = favorites.size > capacity
        val visibleFavorites = if (overflow) favorites.take(capacity - 1) else favorites
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FavoriteGrid(
                favorites = visibleFavorites,
                showMore = overflow,
                onMore = onOpenBookmarks,
                wallpaper = wallpaper,
                wallpaperBackdrop = wallpaperBackdrop,
                liquidBackdrop = liquidBackdrop,
                blurEnabled = blurEnabled,
                editing = editing,
                guard = guard,
                onOpen = onOpen,
                onToggleEdit = onToggleEdit,
                onRemove = onRemove,
                onMove = onMove,
                onEdit = onEdit,
            )
            if (editing && !overflow) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = 1.dp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .clickable(onClick = onAdd),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+ 添加收藏",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.weight(2f))
                }
            }
        }
    }
}

/**
 * 取样源（壁纸）是否已经是磁盘缓存里预模糊好的图。为 true 时首页卡片不再对它叠加第二次实时模糊：
 * 壁纸本身已经约等于 28dp 的模糊，再叠 20dp / 5dp 在视觉上几乎看不出差别，
 * 却会让收藏页的每张卡片各多一次离屏模糊 pass（收藏最多 6 行，可达二十多张）。
 */
internal val LocalWallpaperPreBlurred = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 首页卡片的玻璃底板：与收藏卡片同一套 miuix-glass blur，无采样 / 关模糊时回退到实色。 */
@Composable
internal fun Modifier.homeGlass(
    wallpaperBackdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    hasWallpaper: Boolean = true,
    liquid: com.kyant.backdrop.Backdrop? = null,
    radius: Int = 16,
): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    val dark = isAppDarkTheme()
    val preBlurred = LocalWallpaperPreBlurred.current
    // liquid glass 模式：用 Kyant 液态玻璃从未模糊的壁纸取样
    if (liquid != null && blurEnabled) {
        if (dark) {
            // 深色模式：背景本身暗，折射 / 高光不明显，卡片显得发平。加大模糊与折射、加宽边缘高光，
            // 再用「上亮下暗」的叠色做出厚度，增加玻璃深度；浅色模式保持原样。
            return this.drawBackdrop(
                backdrop = liquid,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (!preBlurred) backdropBlur(5f.dp.toPx())
                    lens(26f.dp.toPx(), 60f.dp.toPx(), depthEffect = true, chromaticAberration = true)
                },
                highlight = {
                    com.kyant.backdrop.highlight.Highlight.Plain.copy(
                        width = com.kyant.backdrop.highlight.Highlight.Plain.width * 1.5f,
                    )
                },
                onDrawSurface = {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.09f), Color.Black.copy(alpha = 0.16f)),
                        ),
                    )
                },
            )
        }
        return this.drawBackdrop(
            backdrop = liquid,
            shape = { shape },
            effects = {
                vibrancy()
                if (!preBlurred) backdropBlur(2f.dp.toPx())
                lens(16f.dp.toPx(), 36f.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f)) },
        )
    }
    val useGlass = blurEnabled && wallpaperBackdrop != null
    val glassMaterial = remember(dark, preBlurred, useGlass) {
        if (!useGlass) null
        else GlassMaterials.puredThinGlass(dark).let { if (preBlurred) it.copy(blurRadius = 0.dp) else it }
    }
    return this.glassPanel(
        backdrop = if (useGlass) wallpaperBackdrop else null,
        shape = GlassShape(radius.dp),
        style = GlassDefaults.style(dark),
        material = glassMaterial,
        stroke = if (blurEnabled) GlassStrokes.forTheme(dark) else null,
        shading = false,
        fallback = Modifier
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, shape)
            .border(
                width = 1.dp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                shape = shape,
            ),
    )
}

/**
 * 无壁纸时玻璃自带的白色高光描边落在浅色底上几乎看不见：在卡片最上层叠一圈对角渐变描边
 * （左上较亮、右下较淡；浅色主题用深线，深色主题用亮线）。必须作为子层叠在玻璃之上，
 * 否则会被玻璃材质盖住。有壁纸时不画，沿用玻璃自带的高光。
 */
@Composable
internal fun BoxScope.HomeOutlineOverlay(hasWallpaper: Boolean, radius: Dp) {
    if (hasWallpaper) return
    val dark = isAppDarkTheme()
    val brush = Brush.linearGradient(
        listOf(
            if (dark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.26f),
            if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f),
        ),
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .border(width = 1.dp, brush = brush, shape = RoundedCornerShape(radius)),
    )
}

/** 首页通用卡片：标题 + 内容；长按进入编辑后右上角出现 × 可从首页移除，长按拖动排序（手势由 [modifier] 提供），可选整卡点击。 */
@Composable
internal fun HomeCard(
    title: String,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    hasWallpaper: Boolean,
    showRemove: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .homeGlass(wallpaperBackdrop, blurEnabled, hasWallpaper, liquidBackdrop)
            .clickable(
                enabled = onClick != null,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick?.invoke() },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 28.dp),
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
        HomeOutlineOverlay(hasWallpaper, 16.dp)
        if (showRemove) Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
internal fun HomeEmptyText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
internal fun HomeRow(primary: String, secondary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = primary,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = secondary,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 隐私报告：拦截广告数、处理指纹读取数、频繁获取敏感权限的网站提醒。
 * Gecko 内核下去广告 / 指纹注入都不存在，改为显示已开启的 Gecko 高级保护清单（紧凑布局）。
 */
@Composable
internal fun PrivacyReportCard(
    geckoMode: Boolean = false,
    modifier: Modifier,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    hasWallpaper: Boolean,
    showRemove: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    if (geckoMode) {
        GeckoPrivacyReportCard(
            modifier = modifier,
            wallpaperBackdrop = wallpaperBackdrop,
            liquidBackdrop = liquidBackdrop,
            blurEnabled = blurEnabled,
            hasWallpaper = hasWallpaper,
            showRemove = showRemove,
            onClick = onClick,
            onRemove = onRemove,
        )
        return
    }
    val context = LocalContext.current
    // 拦截计数由 AdBlock.blockedFlow 推送：有拦截才更新；conflate + delay 把刷新限制在每 1.5 秒至多一次，空闲时不唤醒
    val blocked by produceState(AdBlock.blockedTotal) {
        AdBlock.blockedFlow.collect {
            value = it
            delay(1500)
        }
    }
    val frequent = PrivacyStats.frequent(context)
    HomeCard(
        modifier = modifier,
        title = "隐私报告",
        wallpaperBackdrop = wallpaperBackdrop,
        liquidBackdrop = liquidBackdrop,
        blurEnabled = blurEnabled,
        hasWallpaper = hasWallpaper,
        showRemove = showRemove,
        onClick = onClick,
        onRemove = onRemove,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrivacyStat(blocked.toString(), "已拦截广告", Modifier.weight(1f))
            PrivacyStat(
                if (AdBlock.fingerprintActive) AdBlock.fpReads.toString() else "—",
                if (AdBlock.fingerprintActive) "已处理指纹读取" else "指纹保护未开启",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.height(8.dp))
        if (frequent.isEmpty()) {
            HomeEmptyText("近 24 小时没有网站频繁请求敏感权限")
        } else {
            frequent.take(2).forEach { f ->
                Text(
                    text = "${f.host} 近 24 小时请求${f.kinds.joinToString("、")} ${f.count} 次",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            if (frequent.size > 2) {
                HomeEmptyText("另有 ${frequent.size - 2} 个网站")
            }
        }
    }
}

/** Gecko 版隐私报告：关键保护项两列紧凑排布，每项「圆点 + 名称 + 已开启 / 未开启」，一行一对，尽量省高度 */
@Composable
private fun GeckoPrivacyReportCard(
    modifier: Modifier,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    hasWallpaper: Boolean,
    showRemove: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    // 读取的是 GeckoProtection 里的 Compose 状态，设置页改动后回到首页会自动刷新
    val items = GeckoProtection.reportItems()
    HomeCard(
        modifier = modifier,
        title = "隐私报告",
        wallpaperBackdrop = wallpaperBackdrop,
        liquidBackdrop = liquidBackdrop,
        blurEnabled = blurEnabled,
        hasWallpaper = hasWallpaper,
        showRemove = showRemove,
        onClick = onClick,
        onRemove = onRemove,
    ) {
        items.chunked(2).forEachIndexed { index, pair ->
            if (index > 0) Spacer(Modifier.height(5.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (name, on) -> GeckoPrivacyStatusCell(name, on, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GeckoPrivacyStatusCell(name: String, on: Boolean, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (on) scheme.primary else scheme.onSurface.copy(alpha = 0.25f)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            fontSize = 12.sp,
            color = if (on) scheme.onSurface else scheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (on) "已开启" else "未开启",
            fontSize = 11.sp,
            color = if (on) scheme.primary else scheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

@Composable
internal fun PrivacyStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 可长按拖动排序的磁贴网格（收藏、书签分组共用）：长按磁贴进入编辑并开始拖动，其余磁贴实时让位。
 * [hasTrailing] 时在最后一格放 [trailing]（「更多」/「添加」），它不参与排序。
 * 点击 / 长按 / 拖动合在同一个手势里；[guard] 包住点击回调，拖动刚结束或首页处于编辑态时会拦掉误触。
 */
@Composable
internal fun <T> ReorderTileGrid(
    items: List<T>,
    columns: Int,
    gap: Dp,
    cellHeight: (cellWidth: Dp) -> Dp,
    editing: Boolean,
    guard: (() -> Unit) -> (() -> Unit),
    onTap: (index: Int, item: T, editing: Boolean) -> Unit,
    onToggleEdit: () -> Unit,
    onMove: (Int, Int) -> Unit,
    hasTrailing: Boolean = false,
    trailing: @Composable (modifier: Modifier) -> Unit = {},
    tile: @Composable (item: T, index: Int, modifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { gap.toPx() }
    val count = items.size
    val rows = (count + (if (hasTrailing) 1 else 0) + columns - 1) / columns
    var liveOrder by remember { mutableStateOf<List<Int>?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val countNow by rememberUpdatedState(count)
    val onMoveNow by rememberUpdatedState(onMove)
    val editingNow by rememberUpdatedState(editing)
    val onToggleEditNow by rememberUpdatedState(onToggleEdit)
    val haptic = LocalHapticFeedback.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellWPx = (with(density) { maxWidth.toPx() } - (columns - 1) * gapPx) / columns
        val cellW = with(density) { cellWPx.toDp() }
        val cellH = cellHeight(cellW)
        val tileHPx = with(density) { cellH.toPx() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (rows == 0) 0.dp else cellH * rows + gap * (rows - 1)),
        ) {
            if (hasTrailing) {
                trailing(
                    Modifier
                        .width(cellW)
                        .offset {
                            IntOffset(
                                ((count % columns) * (cellWPx + gapPx)).roundToInt(),
                                ((count / columns) * (tileHPx + gapPx)).roundToInt(),
                            )
                        },
                )
            }
            items.forEachIndexed { index, item ->
                key(item) {
                    val indexNow by rememberUpdatedState(index)
                    val tapNow by rememberUpdatedState(guard { onTap(indexNow, item, editingNow) })
                    val slot = liveOrder?.indexOf(index) ?: index
                    val target = IntOffset(
                        ((slot % columns) * (cellWPx + gapPx)).roundToInt(),
                        ((slot / columns) * (tileHPx + gapPx)).roundToInt(),
                    )
                    val animated by animateIntOffsetAsState(target, spring(stiffness = Spring.StiffnessMediumLow), label = "tileSlot")
                    val dragging = dragIndex == index
                    tile(
                        item,
                        index,
                        Modifier
                            .width(cellW)
                            .offset { if (dragging) IntOffset(dragPos.x.roundToInt(), dragPos.y.roundToInt()) else animated }
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                val sc = if (dragging) 1.05f else 1f
                                scaleX = sc
                                scaleY = sc
                            }
                            .pointerInput(Unit) {
                                // 点击 / 长按 / 拖动合在同一个手势里：不再与 clickable 的长按抢事件
                                //（clickable 长按后会 consumeUntilUp，把后续移动吞掉，导致拖不动）
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                                    val finishedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        up = waitForUpOrCancellation()
                                        true
                                    }
                                    if (finishedEarly != null) {
                                        up?.let { it.consume(); tapNow() }
                                        return@awaitEachGesture
                                    }
                                    // 长按：进入编辑态并开始拖动
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!editingNow) onToggleEditNow()
                                    val i = indexNow
                                    dragIndex = i
                                    liveOrder = List(countNow) { it }
                                    dragPos = Offset((i % columns) * (cellWPx + gapPx), (i / columns) * (tileHPx + gapPx))
                                    val completed = drag(down.id) { change ->
                                        change.consume()
                                        dragPos += change.position - change.previousPosition
                                        val order = liveOrder ?: return@drag
                                        val centerX = dragPos.x + cellWPx / 2f
                                        val centerY = dragPos.y + tileHPx / 2f
                                        val lastRow = (countNow - 1) / columns
                                        val col = (centerX / (cellWPx + gapPx)).toInt().coerceIn(0, columns - 1)
                                        val row = (centerY / (tileHPx + gapPx)).toInt().coerceIn(0, lastRow)
                                        val to = (row * columns + col).coerceIn(0, countNow - 1)
                                        val from = order.indexOf(dragIndex)
                                        if (from >= 0 && to != from) {
                                            liveOrder = order.toMutableList().also { it.removeAt(from); it.add(to, dragIndex) }
                                        }
                                    }
                                    val from = dragIndex
                                    val to = liveOrder?.indexOf(from) ?: -1
                                    dragIndex = -1
                                    liveOrder = null
                                    if (completed && from >= 0 && to >= 0 && to != from) onMoveNow(from, to)
                                }
                            },
                    )
                }
            }
        }
    }
}

/** 收藏磁贴网格（每行 3 个）：长按磁贴后拖动可排序。[showMore] 时在最后一格追加「更多」（不参与排序）。 */
@Composable
internal fun FavoriteGrid(
    favorites: List<Favorite>,
    showMore: Boolean = false,
    onMore: () -> Unit = {},
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    editing: Boolean,
    guard: (() -> Unit) -> (() -> Unit),
    onOpen: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onEdit: (Int) -> Unit,
) {
    val moreTap by rememberUpdatedState(guard { onMore() })
    ReorderTileGrid(
        items = favorites,
        columns = 3,
        gap = 10.dp,
        cellHeight = { 58.dp },
        editing = editing,
        guard = guard,
        onTap = { index, favorite, isEditing -> if (isEditing) onEdit(index) else onOpen(favorite.url) },
        onToggleEdit = onToggleEdit,
        onMove = onMove,
        hasTrailing = showMore,
        trailing = { mod ->
            FavoriteMoreTile(
                wallpaper = wallpaper,
                wallpaperBackdrop = wallpaperBackdrop,
                liquidBackdrop = liquidBackdrop,
                blurEnabled = blurEnabled,
                modifier = mod.clickable { moreTap() },
            )
        },
    ) { favorite, index, mod ->
        FavoriteTile(
            favorite = favorite,
            wallpaper = wallpaper,
            wallpaperBackdrop = wallpaperBackdrop,
            liquidBackdrop = liquidBackdrop,
            blurEnabled = blurEnabled,
            editing = editing,
            modifier = mod,
            onRemove = { onRemove(index) },
        )
    }
}

/** 「更多」磁贴：收藏数量超出所选行数时占据最后一格，点击进入书签页。 */
@Composable
internal fun FavoriteMoreTile(
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(58.dp)
            .homeGlass(wallpaperBackdrop, blurEnabled, wallpaper != null, liquidBackdrop),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "更多",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        HomeOutlineOverlay(hasWallpaper = wallpaper != null, radius = 16.dp)
    }
}

@Composable
internal fun FavoriteTile(
    favorite: Favorite,
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    editing: Boolean,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(58.dp)
            .homeGlass(wallpaperBackdrop, blurEnabled, wallpaper != null, liquidBackdrop),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FaviconBadge(url = favorite.url, size = 16.dp, corner = 4.dp)
            Text(
                text = favorite.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing) TileRemoveBadge(onClick = onRemove)
        HomeOutlineOverlay(hasWallpaper = wallpaper != null, radius = 16.dp)
    }
}

/** 磁贴右上角的删除按钮（编辑态显示；收藏磁贴、书签分组磁贴共用）。 */
@Composable
internal fun BoxScope.TileRemoveBadge(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "×", fontSize = 11.sp, color = Color.White)
    }
}

/** 站点图标：加载到就显示，没有就用主题色占位。 */
@Composable
internal fun FaviconBadge(url: String, size: Dp, corner: Dp) {
    val icon = rememberFavicon(url)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(corner)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.25f)),
        )
    }
}

/** 首页块的标题行：长按它拖动整块排序；编辑态右侧出现「完成」。 */
@Composable
internal fun HomeBlockHeader(
    title: String,
    headerModifier: Modifier,
    editing: Boolean,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .then(headerModifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        if (editing) {
            AppTextButton(
                text = "完成",
                onClick = onDone,
                primary = true,
                inPage = true,
                minHeight = 28.dp,
                minWidth = 52.dp,
                cornerRadius = 14.dp,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                textStyle = TextStyle(fontSize = 12.sp),
            )
        }
    }
}

/* -------------------------- 书签分组卡片 -------------------------- */

/** 书签分组磁贴的正方形占格子宽度的比例：比例越小，磁贴越小、磁贴之间的空隙越大。 */
private const val GROUP_TILE_FRACTION = 0.80f

/** 书签分组整块大卡片的圆角（dp）与内边距：磁贴们统一落在这一张玻璃卡片上。 */
private const val GROUP_CARD_RADIUS = 22
private val GROUP_CARD_PADDING_H = 8.dp

/** 方块下方标题占的高度：4dp 间距 + 14dp 行高（标题固定行高，格子不再多留空白，大卡片上下边距才对称）。 */
private val GROUP_LABEL_BLOCK = 18.dp
private val GROUP_CARD_PADDING_V = 14.dp

/** 书签分组磁贴的圆角（dp），随方块缩小。 */
private const val GROUP_TILE_RADIUS = 16

/** 书签分组弹窗的边长上限（GlassPopup 自身宽度上限 288dp，取小一点更像一个小窗）。 */
private val GROUP_POPUP_SIDE = 248.dp

/** 首页「书签分组」里的一格：文件夹 id（稳定）、当前路径、预览用的前几条书签地址。 */
internal data class HomeGroup(val id: String, val path: String, val previewUrls: List<String>)

/**
 * 书签分组：正方形磁贴网格（每行 [HOME_GROUP_COLS] 个，显示 [rows] 行，设置里可选 1~[HOME_GROUP_ROWS_MAX]），
 * 最后一格永远是「添加」，格子满了就消失；超出行数的分组只是不显示，不会被删除。
 * 点击磁贴不再跳转，而是回调磁贴图标的坐标，由上层在图标下方弹出书签窗口。
 * 整块作为一个块参与首页排序，长按标题行拖动。
 */
@Composable
internal fun GroupsBlock(
    modifier: Modifier,
    headerModifier: Modifier,
    groups: List<HomeGroup>,
    rows: Int,
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    editing: Boolean,
    guard: (() -> Unit) -> (() -> Unit),
    onOpen: (path: String, anchor: Rect) -> Unit,
    onToggleEdit: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAdd: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HomeBlockHeader(title = "书签分组", headerModifier = headerModifier, editing = editing, onDone = onToggleEdit)

        Spacer(Modifier.height(6.dp))
        val capacity = rows.coerceIn(1, HOME_GROUP_ROWS_MAX) * HOME_GROUP_COLS
        // 只截取可见的前几个：下标与完整列表一致，拖动 / 删除回调里的位置可直接对应
        val shown = groups.take(capacity)
        val full = shown.size >= capacity
        // 各磁贴图标的屏幕坐标（只在点击时读取，不需要触发重组）
        val anchors = remember { HashMap<String, Rect>() }
        val haptic = LocalHapticFeedback.current
        val addTap by rememberUpdatedState(guard {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onAdd()
        })
        val editingNow by rememberUpdatedState(editing)
        val onToggleEditNow by rememberUpdatedState(onToggleEdit)
        // 整块一张大卡片：只在这里做一次玻璃（复用页面已有的壁纸模糊缓存采样），里面的磁贴只画半透明实色，不再各自开模糊层
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .homeGlass(wallpaperBackdrop, blurEnabled, wallpaper != null, liquidBackdrop, radius = GROUP_CARD_RADIUS),
        ) {
        // 垫在最底层的「空白感应层」：磁贴、添加按钮在它上面各自处理手势，只有落在大卡片空白处（边距、磁贴间隙、空格子）
        // 的长按才会到这里，进入编辑态（与长按磁贴等效）；不消费按下事件，不影响页面滚动。
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // 超时仍没抬起 / 没被别人（滚动）接管 = 长按；提前抬起或被取消直接结束
                        val finishedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                            true
                        }
                        if (finishedEarly == null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (!editingNow) onToggleEditNow()
                            // 等手指抬起，免得同一次按压重复触发
                            waitForUpOrCancellation()
                        }
                    }
                },
        )
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = GROUP_CARD_PADDING_H, vertical = GROUP_CARD_PADDING_V)) {
        ReorderTileGrid(
            items = shown,
            columns = HOME_GROUP_COLS,
            gap = 8.dp,
            // 缩小后的正方形磁贴 + 下方一行名称 + 额外竖向间距
            cellHeight = { it * GROUP_TILE_FRACTION + GROUP_LABEL_BLOCK },
            editing = editing,
            guard = guard,
            onTap = { _, group, isEditing ->
                if (isEditing) {
                    onToggleEdit()
                } else {
                    // 点击磁贴：给一下振动反馈，再弹出书签窗口
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpen(group.path, anchors[group.id] ?: Rect.Zero)
                }
            },
            onToggleEdit = onToggleEdit,
            onMove = onMove,
            // 「添加」：一个分组都没有时常驻（初始状态）；添加过分组后只在长按进入编辑态时出现
            hasTrailing = !full && (shown.isEmpty() || editing),
            trailing = { mod ->
                GroupAddTile(
                    wallpaper = wallpaper,
                    wallpaperBackdrop = wallpaperBackdrop,
                    liquidBackdrop = liquidBackdrop,
                    blurEnabled = blurEnabled,
                    modifier = mod.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { addTap() },
                )
            },
        ) { group, index, mod ->
            GroupTile(
                group = group,
                wallpaper = wallpaper,
                wallpaperBackdrop = wallpaperBackdrop,
                liquidBackdrop = liquidBackdrop,
                blurEnabled = blurEnabled,
                editing = editing,
                modifier = mod,
                onAnchor = { anchors[group.id] = it },
                onRemove = { onRemove(index) },
            )
        }
        }
        // 高光描边叠在玻璃之上（有壁纸时沿用玻璃自带的高光，无壁纸时画对角渐变描边）
        HomeOutlineOverlay(hasWallpaper = wallpaper != null, radius = GROUP_CARD_RADIUS.dp)
        }
    }
}

/** 大卡片里的磁贴底：半透明实色 + 细描边，没有任何模糊 / 离屏层。 */
@Composable
private fun Modifier.groupTileSurface(hasWallpaper: Boolean): Modifier {
    val dark = isAppDarkTheme()
    val shape = RoundedCornerShape(GROUP_TILE_RADIUS.dp)
    val fill = when {
        hasWallpaper && dark -> Color.White.copy(alpha = 0.10f)
        hasWallpaper -> Color.White.copy(alpha = 0.32f)
        else -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }
    val line = when {
        hasWallpaper && dark -> Color.White.copy(alpha = 0.16f)
        hasWallpaper -> Color.White.copy(alpha = 0.55f)
        else -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    return this.background(fill, shape).border(0.8.dp, line, shape)
}

/** 书签分组磁贴：玻璃方块里放该分组前几条书签的站点图标（空分组显示文件夹图标），名称在方块下方。 */
@Composable
internal fun GroupTile(
    group: HomeGroup,
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    editing: Boolean,
    modifier: Modifier = Modifier,
    /** 标题文字的屏幕坐标：弹窗以它为锚点向下展开。 */
    onAnchor: (Rect) -> Unit = {},
    onRemove: () -> Unit,
) {
    // 大卡片真的是「壁纸上的玻璃」才用白字；关了模糊 / 没有取样源时大卡片回退成浅色实底，标题要跟着主题色走
    val hasWallpaper = wallpaper != null && blurEnabled && (wallpaperBackdrop != null || liquidBackdrop != null)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth(GROUP_TILE_FRACTION)
                .aspectRatio(1f)
                .groupTileSurface(hasWallpaper),
            contentAlignment = Alignment.Center,
        ) {
            if (group.previewUrls.isEmpty()) {
                Icon(
                    imageVector = AppIcons.Folder,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    group.previewUrls.take(4).chunked(2).forEach { rowUrls ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            rowUrls.forEach { FaviconBadge(url = it, size = 13.dp, corner = 4.dp) }
                        }
                    }
                }
            }
            if (editing) TileRemoveBadge(onClick = onRemove)
        }
        Text(
            text = group.path.substringAfterLast('/').ifBlank { group.path },
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 与收藏卡片标题一致：跟随系统深 / 浅色（深色白字，浅色黑字）
            color = MiuixTheme.colorScheme.onSurface,
            style = TextStyle(
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 14.sp,
            ),
            // 弹窗锚在标题文字上：标题按文字宽度收紧（超长仍会省略），上报文字自己的屏幕坐标
            modifier = Modifier
                .padding(top = 4.dp)
                .onGloballyPositioned { onAnchor(it.boundsInRoot()) },
        )
    }
}

/** 「添加」方块：永远排在书签分组的最后一格。 */
@Composable
internal fun GroupAddTile(
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(GROUP_TILE_FRACTION)
                .aspectRatio(1f)
                .groupTileSurface(wallpaper != null && blurEnabled && (wallpaperBackdrop != null || liquidBackdrop != null)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                fontSize = 20.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 分组弹窗的几何：宽 [side]、高 [height]、水平对齐方式，以及按 GlassPopup 规则加工过的锚点。 */
internal class GroupPopupGeometry(val anchor: Rect, val alignStart: Boolean, val side: Dp, val height: Dp)

/**
 * 按 GlassPopup 的规则算分组弹窗的锚点与对齐：
 * - 宽度不能超过 GlassPopup 的宽度上限（288dp），也要给页面两侧各留 SafeMargin；
 * - 水平（[alignStartByHalf] 为 true，磁贴弹窗）：页面左半边（含正中的第 3 列）用 alignStart，左边缘对齐磁贴左边缘、向右展开；
 *   正中的磁贴弹窗宽度收窄到刚好铺到右侧屏幕边缘留一个 SafeMargin 的空隙，高度不变；右半边用右边缘对齐磁贴右边缘；
 *   [alignStartByHalf] 为 false 时固定右边缘对齐锚点右边缘（向左展开）；
 * - 垂直：默认顶边放在锚点下沿 + 间距，窗口出现在锚点正下方；
 *   [openUp] 为 true 时强制向上弹出：底边放在锚点上沿 - 间距（右下角贴着锚点），高度取 [desiredHeight]
 *   （内容刚好显示完整的高度），放不下时压到锚点上方放得下的范围。
 */
@Composable
internal fun groupPopupGeometry(
    anchorBounds: Rect,
    pageWidthPx: Float,
    alignStartByHalf: Boolean = true,
    openUp: Boolean = false,
    gap: Dp = 6.dp,
    desiredHeight: Dp? = null,
): GroupPopupGeometry {
    val density = LocalDensity.current
    val pageWidthDp = with(density) { pageWidthPx.toDp() }
    val margin = GlassPopupDefaults.SafeMargin
    val side = minOf(GROUP_POPUP_SIDE, (pageWidthDp - margin * 2).coerceAtLeast(0.dp))
    val gapPx = with(density) { gap.toPx() }
    if (anchorBounds == Rect.Zero) return GroupPopupGeometry(anchorBounds, alignStartByHalf, side, side)

    val cx = anchorBounds.center.x
    val ratio = if (pageWidthPx > 0f) cx / pageWidthPx else 0.5f
    // 5 列磁贴的中心约在页宽的 0.1 / 0.3 / 0.5 / 0.7 / 0.9：<0.4 左侧、0.4~0.6 正中、>0.6 右侧
    val middle = alignStartByHalf && ratio in 0.4f..0.6f
    val alignStart = alignStartByHalf && ratio <= 0.6f
    if (!openUp) {
        // 正中的磁贴：弹窗左边缘 = 标题左边缘，向右下展开，宽度刚好到右侧安全边距（留 1dp 余量避免取整后被 GlassPopup 挪动）；
        // 其余磁贴宽度不变
        val leftDp = with(density) { anchorBounds.left.toDp() }
        val width = if (middle) {
            minOf(side, (pageWidthDp - leftDp - margin - 1.dp).coerceAtLeast(120.dp))
        } else {
            side
        }
        return GroupPopupGeometry(
            Rect(anchorBounds.left, anchorBounds.bottom + gapPx, anchorBounds.right, anchorBounds.top - gapPx),
            alignStart, width, side,
        )
    }
    // 向上：anchor.top 取一个远在页面之外的值，GlassPopup 判定「下方放不下」而翻到上方，并以 anchor.bottom 为底边
    val marginPx = with(density) { margin.toPx() }
    val bottom = (anchorBounds.top - gapPx).coerceAtLeast(marginPx + 1f)
    // 扣掉 GlassPopup 自带的上下内边距（各 8dp）
    val availDp = with(density) { (bottom - marginPx).toDp() } - GlassPopupDefaults.ContentPaddingVertical * 2
    val want = desiredHeight ?: side
    val height = minOf(want, availDp, 400.dp).coerceAtLeast(96.dp)
    return GroupPopupGeometry(Rect(anchorBounds.left, 1e7f, anchorBounds.right, bottom), alignStart, side, height)
}

/** 分组弹窗的面板：固定边长的正方形，标题行（可返回上一层）+ 列表，两种分组弹窗共用。 */
@Composable
private fun GroupPopupPanel(
    side: Dp,
    height: Dp,
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    trailing: String,
    isEmpty: Boolean,
    emptyText: String,
    /** 标题行最右侧的操作（如排列方式切换）。 */
    headerAction: (@Composable () -> Unit)? = null,
    /** 自定义内容区（传了就替代默认的竖排 [list]），参数是它该占用的 Modifier。 */
    body: (@Composable (Modifier) -> Unit)? = null,
    list: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    Column(modifier = Modifier.size(width = side, height = height)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showBack) {
                // 用 Canvas 画返回箭头：Text 的 ‹ 字形带字体内边距，会比标题文字偏高 / 偏低
                val arrowColor = MiuixTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack,
                        ),
                ) {
                    val w = size.width
                    val h = size.height
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.62f, h * 0.24f)
                        lineTo(w * 0.36f, h * 0.5f)
                        lineTo(w * 0.62f, h * 0.76f)
                    }
                    drawPath(
                        path, arrowColor,
                        style = Stroke(
                            width = 2.2.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    )
                }
            }
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = trailing,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            headerAction?.invoke()
        }
        if (isEmpty) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                HomeEmptyText(emptyText)
            }
        } else {
            val bodyModifier = Modifier.fillMaxWidth().weight(1f)
            if (body != null) body(bodyModifier) else LazyColumn(modifier = bodyModifier, content = list ?: {})
        }
    }
}

/**
 * 分组弹窗里的一行子分组：点整行执行 [onClick]；有下一层时右侧有 ›，点它进入下一层（[chevron]）。
 * [trailing] 是右侧的小字（如书签数），[selected] 时显示 ✓。
 */
@Composable
private fun GroupPopupFolderRow(
    name: String,
    trailing: String?,
    selected: Boolean,
    onClick: () -> Unit,
    chevron: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = AppIcons.Folder,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = name,
            fontSize = 14.sp,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Text(text = "✓", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
        }
        if (chevron != null) {
            Text(
                text = "›",
                fontSize = 20.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = chevron,
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        } else {
            // 没有下一层：留出同样的宽度，让各行右侧对齐
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** [path] 是否正好是 [parent] 的直接子分组（[parent] 为空串表示顶层）。 */
private fun isDirectChild(path: String, parent: String): Boolean =
    if (parent.isEmpty()) '/' !in path else path.startsWith("$parent/") && '/' !in path.removePrefix("$parent/")

/** 排列方式切换图标：[showGridIcon] 为 true 画 2×2 方块（点击切到横排），否则画三条横线（点击切回竖排）。 */
@Composable
private fun GroupLayoutToggle(showGridIcon: Boolean, onClick: () -> Unit) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val w = size.width
            if (showGridIcon) {
                val cell = w * 0.42f
                val r = androidx.compose.ui.geometry.CornerRadius(w * 0.1f)
                for (ix in 0..1) for (iy in 0..1) {
                    drawRoundRect(
                        color,
                        topLeft = Offset(ix * (w - cell), iy * (w - cell)),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                        cornerRadius = r,
                    )
                }
            } else {
                val bar = w * 0.14f
                for (i in 0..2) {
                    drawRoundRect(
                        color,
                        topLeft = Offset(0f, i * (w - bar) / 2f),
                        size = androidx.compose.ui.geometry.Size(w, bar),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2f),
                    )
                }
            }
        }
    }
}

/** 横排模式的一格：图标在上、标题在下。 */
@Composable
private fun GroupPopupGridCell(title: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(
            text = title,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurface,
            style = TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * 书签分组弹窗：以磁贴图标为锚点向下弹出的正方形窗口，沿用 [AppGlassPopup]（miuix 玻璃 / 液态玻璃 / 无模糊三种材质由它统一适配）。
 * 里面先列出该分组的子分组，再列出该分组的书签；点子分组在同一个窗口里进入下一层（标题行左侧 ‹ 返回上一层），点书签直接打开。
 */
@Composable
internal fun BoxScope.GroupBookmarkPopup(
    show: Boolean,
    rootPath: String,
    anchorBounds: Rect,
    favorites: FavoritesStore,
    /** 排列方式：false 竖排（默认），true 横排（图标在上标题在下）。由上层持久化，重启后保持。 */
    gridMode: Boolean,
    onGridModeChange: (Boolean) -> Unit,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    // 每次重新弹出都回到分组根；窗口里的层级只在窗口内部记录
    var path by remember(rootPath, show) { mutableStateOf(rootPath) }
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    // 弹窗宿主 Box 自己的位置和宽度：锚点（根坐标）换算成宿主内坐标，页宽取宿主的真实宽度，
    // 这样宿主不是整屏 / 不在根原点时也不会错位
    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    val hostW = if (hostBounds.width > 0f) hostBounds.width else screenWidthPx
    val localAnchor = if (anchorBounds == Rect.Zero) anchorBounds else anchorBounds.translate(-hostBounds.topLeft)
    val geo = groupPopupGeometry(localAnchor, hostW)
    // 只读取不登记：不在组合里改文件夹注册表（同 GroupPickerDialog）
    val folders = if (show) withAncestors(favorites.groups()).filter { isDirectChild(it, path) && it != path } else emptyList()
    val marks = if (show) favorites.itemsIn(path) else emptyList()
    Box(modifier = Modifier.matchParentSize().onGloballyPositioned { hostBounds = it.boundsInRoot() }) {
    AppGlassPopup(
        show = show,
        onDismissRequest = onDismiss,
        anchorBounds = geo.anchor,
        backdrop = backdrop,
        alignStart = geo.alignStart,
    ) {
        GroupPopupPanel(
            side = geo.side,
            height = geo.height,
            title = path.substringAfterLast('/').ifBlank { path },
            showBack = path != rootPath,
            onBack = { path = path.substringBeforeLast('/') },
            trailing = "${folders.size + marks.size} 项",
            isEmpty = folders.isEmpty() && marks.isEmpty(),
            emptyText = "这个分组还没有书签",
            headerAction = { GroupLayoutToggle(showGridIcon = !gridMode, onClick = { onGridModeChange(!gridMode) }) },
            body = { bodyModifier ->
                if (gridMode) {
                    // 横排：每行放几个按弹窗宽度自适应（约 248dp 宽 3 个，收窄后 2~3 个）
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(64.dp),
                        modifier = bodyModifier,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        gridItems(folders, key = { "f:$it" }) { folder ->
                            GroupPopupGridCell(
                                title = folder.substringAfterLast('/'),
                                onClick = { path = folder },
                            ) {
                                Icon(
                                    imageVector = AppIcons.Folder,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                        gridItems(marks, key = { "b:${it.id}" }) { mark ->
                            GroupPopupGridCell(
                                title = mark.name.ifBlank { mark.url },
                                onClick = { onOpenUrl(mark.url) },
                            ) {
                                FaviconBadge(url = mark.url, size = 32.dp, corner = 8.dp)
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = bodyModifier) {
                        items(folders, key = { "f:$it" }) { folder ->
                            GroupPopupFolderRow(
                                name = folder.substringAfterLast('/'),
                                trailing = "${favorites.itemsInTree(folder).size}",
                                selected = false,
                                onClick = { path = folder },
                                chevron = { path = folder },
                            )
                        }
                        items(marks, key = { "b:${it.id}" }) { mark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onOpenUrl(mark.url) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FaviconBadge(url = mark.url, size = 22.dp, corner = 6.dp)
                                Text(
                                    text = mark.name.ifBlank { mark.url },
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            },
        )
    }
    }
}

/**
 * 分组选择弹窗（新建 / 编辑书签时选分组）：与 [GroupBookmarkPopup] 同一套面板与动效，
 * 只列分组：点一行直接选中并关闭，有下一层的分组右侧有 ›，点它进入下一层继续选（标题行 ‹ 返回上一层）。
 * 打开时先停在当前分组所在的那一层，直接看到它（✓ 标出）。
 *
 * 必须放在一个尺寸确定（如 matchParentSize）的 Box 里：GlassPopup 把父 Box 当作整个页面；
 * [anchorBounds] 要换算成该 Box 的坐标，[pageWidthPx] 是该 Box 的宽度。
 */
@Composable
internal fun BoxScope.GroupChoosePopup(
    show: Boolean,
    anchorBounds: Rect,
    pageWidthPx: Float,
    groups: List<String>,
    current: String,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val all = remember(groups) { withAncestors(groups) }
    var path by remember(show) { mutableStateOf(current.substringBeforeLast('/', "")) }
    val children = all.filter { isDirectChild(it, path) }
    // 标题行 44dp + 每行约 43dp：让所有分组一次显示完整，放不下才滚动
    val desired = 44.dp + 43.dp * children.size.coerceAtLeast(2)
    val geo = groupPopupGeometry(
        anchorBounds, pageWidthPx, alignStartByHalf = false, openUp = true, gap = 4.dp, desiredHeight = desired,
    )
    AppGlassPopup(
        show = show,
        onDismissRequest = onDismiss,
        anchorBounds = geo.anchor,
        backdrop = backdrop,
        alignStart = geo.alignStart,
    ) {
        GroupPopupPanel(
            side = geo.side,
            height = geo.height,
            title = if (path.isEmpty()) "选择分组" else path.substringAfterLast('/'),
            showBack = path.isNotEmpty(),
            onBack = { path = path.substringBeforeLast('/', "") },
            trailing = "${children.size} 项",
            isEmpty = children.isEmpty(),
            emptyText = "还没有分组",
        ) {
            items(children, key = { it }) { p ->
                GroupPopupFolderRow(
                    name = p.substringAfterLast('/'),
                    trailing = null,
                    selected = p == current,
                    onClick = { onSelect(p) },
                    chevron = if (all.any { isDirectChild(it, p) }) ({ path = p }) else null,
                )
            }
        }
    }
}

/** 选择要添加到首页的书签分组：根分组和子分组都能选，已添加的置灰。 */
@Composable
internal fun GroupPickerDialog(
    show: Boolean,
    favorites: FavoritesStore,
    pickedIds: List<String>,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onPick: (path: String) -> Unit,
) {
    AppGlassDialog(
        show = show,
        title = "添加书签分组",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 展示顺序：根分组在前，子分组紧跟各自的上级（缩进表示层级）
            // （只读取，不在组合里改注册表；选中时再由调用方登记拿 id）
            val paths = if (show) groupTreeOrder(withAncestors(favorites.groups())) else emptyList()
            val picked = pickedIds.toSet()
            if (paths.isEmpty()) {
                HomeEmptyText("还没有书签分组，请先在书签页新建")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(paths.size, key = { paths[it] }) { i ->
                        val path = paths[i]
                        val added = favorites.folderId(path)?.let { it in picked } == true
                        val depth = path.count { it == '/' }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = !added,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onPick(path) }
                                .alpha(if (added) 0.4f else 1f)
                                .padding(start = 4.dp + 16.dp * depth, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Folder,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = path.substringAfterLast('/'),
                                fontSize = 15.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (added) "已添加" else "${favorites.itemsInTree(path).size} 个书签",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            AppDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(text = "取消", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/** 补齐缺失的上级路径（"A/B" 存在而 "A" 没有书签时也要能选 "A"），保持首次出现的先后。 */
private fun withAncestors(paths: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    paths.forEach { p ->
        var cur = ""
        p.split('/').forEach { seg ->
            cur = if (cur.isEmpty()) seg else "$cur/$seg"
            if (cur.isNotEmpty()) seen.add(cur)
        }
    }
    return seen.toList()
}

/** 把分组路径排成「上级在前、子分组紧跟其后」的顺序；各层内部保持传入的先后。 */
private fun groupTreeOrder(paths: List<String>): List<String> {
    val out = ArrayList<String>()
    fun add(parent: String?) {
        paths.filter { p ->
            if (parent == null) '/' !in p else p.startsWith("$parent/") && '/' !in p.removePrefix("$parent/")
        }.forEach {
            out.add(it)
            add(it)
        }
    }
    add(null)
    paths.filter { it !in out }.forEach { out.add(it) }
    return out
}

/** 新增 / 编辑收藏：GlassDialog */
@Composable
internal fun FavoriteEditor(
    show: Boolean,
    initial: Favorite?,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var url by remember(initial) { mutableStateOf(initial?.url ?: "") }
    AppGlassDialog(
        show = show,
        title = if (initial == null) "添加收藏" else "编辑收藏",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = "名称",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = "网址",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { if (url.isNotBlank()) onConfirm(name, url) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "保存", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}
