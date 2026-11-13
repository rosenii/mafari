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
import kotlinx.coroutines.flow.conflate
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

/* ------------------------------ 主页 ------------------------------ */

/** 首页内容块的拖动排序状态（收藏整体算一个块）。 */
internal class HomeReorderState {
    var dragging by mutableIntStateOf(-1)
    var offset by mutableFloatStateOf(0f)
    var live by mutableStateOf<List<Int>>(emptyList())
    var suppressClick = false
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
): Modifier {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val visibleNow by rememberUpdatedState(visible)
    val commit by rememberUpdatedState(onCommit)
    val finish = {
        if (st.dragging == kind) {
            val anim = st.animFor(kind)
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
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 14.dp.toPx() }
    val hasWallpaper = wallpaper != null
    val st = remember { HomeReorderState() }
    val visibleKinds = order.filter {
        when (it) {
            HOME_FAVORITES -> showFavorites
            HOME_PRIVACY -> showPrivacy
            HOME_RECENT -> showRecent
            else -> showDownloads
        }
    }
    val shown = if (st.dragging >= 0) st.live else visibleKinds
    val commit: (List<Int>) -> Unit = { onReorder(mergeHomeOrder(order, it)) }
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
                .padding(top = 44.dp, bottom = 150.dp),
        ) {
            if (showLogo) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassLogoText("Mafari", fontSize = 58, wallpaper = wallpaper)
                }
                Spacer(Modifier.height(30.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { st.colWindowTop = it.positionInWindow().y },
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                shown.forEach { kind ->
                    key(kind) {
                        val itemMod = Modifier.homeReorderItem(kind, st)
                        val handle = Modifier.homeReorderHandle(kind, st, visibleKinds, gapPx, commit)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .then(headerModifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "收藏",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (editing) {
                AppTextButton(
                    text = "完成",
                    onClick = onToggleEdit,
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
                    backdropBlur(5f.dp.toPx())
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
                backdropBlur(2f.dp.toPx())
                lens(16f.dp.toPx(), 36f.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f)) },
        )
    }
    val useGlass = blurEnabled && wallpaperBackdrop != null
    return this.glassPanel(
        backdrop = if (useGlass) wallpaperBackdrop else null,
        shape = GlassShape(radius.dp),
        style = GlassDefaults.style(dark),
        material = if (useGlass) GlassMaterials.puredThinGlass(dark) else null,
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
        AdBlock.blockedFlow.conflate().collect {
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

/** 收藏磁贴网格（每行 3 个）：长按磁贴后拖动可排序，其余磁贴实时让位。[showMore] 时在最后一格追加「更多」（不参与排序）。 */
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
    val density = LocalDensity.current
    val gapPx = with(density) { 10.dp.toPx() }
    val tileHPx = with(density) { 58.dp.toPx() }
    val count = favorites.size
    val rows = (count + (if (showMore) 1 else 0) + 2) / 3
    var liveOrder by remember { mutableStateOf<List<Int>?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val countNow by rememberUpdatedState(count)
    val onMoveNow by rememberUpdatedState(onMove)
    val editingNow by rememberUpdatedState(editing)
    val onToggleEditNow by rememberUpdatedState(onToggleEdit)
    val haptic = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (rows == 0) 0.dp else 58.dp * rows + 10.dp * (rows - 1)),
    ) {
        val cellWPx = (with(density) { maxWidth.toPx() } - 2 * gapPx) / 3f
        val cellW = with(density) { cellWPx.toDp() }
        if (showMore) {
            val moreTap by rememberUpdatedState(guard { onMore() })
            FavoriteMoreTile(
                wallpaper = wallpaper,
                wallpaperBackdrop = wallpaperBackdrop,
                liquidBackdrop = liquidBackdrop,
                blurEnabled = blurEnabled,
                modifier = Modifier
                    .width(cellW)
                    .offset {
                        IntOffset(
                            ((count % 3) * (cellWPx + gapPx)).roundToInt(),
                            ((count / 3) * (tileHPx + gapPx)).roundToInt(),
                        )
                    }
                    .clickable { moreTap() },
            )
        }
        favorites.forEachIndexed { index, favorite ->
            key(favorite) {
                val indexNow by rememberUpdatedState(index)
                val tapNow by rememberUpdatedState(guard { if (editingNow) onEdit(indexNow) else onOpen(favorite.url) })
                val slot = liveOrder?.indexOf(index) ?: index
                val target = IntOffset(
                    ((slot % 3) * (cellWPx + gapPx)).roundToInt(),
                    ((slot / 3) * (tileHPx + gapPx)).roundToInt(),
                )
                val animated by animateIntOffsetAsState(target, spring(stiffness = Spring.StiffnessMediumLow), label = "favSlot")
                val dragging = dragIndex == index
                FavoriteTile(
                    favorite = favorite,
                    wallpaper = wallpaper,
                    wallpaperBackdrop = wallpaperBackdrop,
                    liquidBackdrop = liquidBackdrop,
                    blurEnabled = blurEnabled,
                    editing = editing,
                    modifier = Modifier
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
                                dragPos = Offset((i % 3) * (cellWPx + gapPx), (i / 3) * (tileHPx + gapPx))
                                val completed = drag(down.id) { change ->
                                    change.consume()
                                    dragPos += change.position - change.previousPosition
                                    val order = liveOrder ?: return@drag
                                    val centerX = dragPos.x + cellWPx / 2f
                                    val centerY = dragPos.y + tileHPx / 2f
                                    val lastRow = (countNow - 1) / 3
                                    val col = (centerX / (cellWPx + gapPx)).toInt().coerceIn(0, 2)
                                    val row = (centerY / (tileHPx + gapPx)).toInt().coerceIn(0, lastRow)
                                    val to = (row * 3 + col).coerceIn(0, countNow - 1)
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
                    onRemove = { onRemove(indexNow) },
                )
            }
        }
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
            val icon = rememberFavicon(favorite.url)
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.25f)),
                )
            }
            Text(
                text = favorite.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", fontSize = 11.sp, color = Color.White)
            }
        }
        HomeOutlineOverlay(hasWallpaper = wallpaper != null, radius = 16.dp)
    }
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
