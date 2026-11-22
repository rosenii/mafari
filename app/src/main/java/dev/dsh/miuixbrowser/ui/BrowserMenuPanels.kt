package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.util.cancelCompat
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.browser.rememberRemoteIcon
import dev.dsh.miuixbrowser.data.TabThumbs
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GECKO_LOCKED_ALPHA
import dev.dsh.miuixbrowser.engine.gecko.showGeckoUnsupportedToast
import dev.dsh.miuixbrowser.engine.gecko.showWebViewUnsupportedToast
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.sync.fxa.FxaUi
import dev.dsh.miuixbrowser.util.hostOf
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.withFrameNanos
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
import kotlin.math.abs
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.animate
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
import dev.dsh.miuixbrowser.ui.icons.Settings
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
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 更多面板：图标 + 文字宫格。长按任一图标进入编辑：面板自动拉长（装不下时菜单自动下滚），底部出现高光分割线，
 * 线下方是「隐藏的功能」区；右上角出现「完成」，每个图标右上角出现 ×。
 * 点 × 或把图标拖到线下方即从菜单隐藏；隐藏区的图标点 + 或拖回线上方即恢复。
 *
 * 进入方式：没有长按功能的图标，长按即进入编辑（不用先拖动）；有长按功能的图标
 * （[longPressActions]）保持原样：按住不动 0.65 秒触发长按功能，拖动超过阈值才进入编辑。
 */
/** 更多菜单里长按进入管理页（脚本 / 收藏 / 拓展）所需的按住时长，含系统长按判定时间 */
internal const val MENU_LONG_PRESS_MS = 650L

/** 「无痕浏览」开启时的高亮色（更多菜单与首页快捷菜单共用） */
internal val MenuIncognitoPurple = Color(0xFF9C27B0)

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
    /** 「网络请求」记录开着时图标变黄 */
    requestsActive: Boolean = false,
    /** 脚本总开关开着时「脚本」图标和文字变浅蓝（角标仍是深蓝底白字，和图标颜色区分开） */
    scriptsActive: Boolean = false,
    /** 至少有一个拓展在运行（Gecko 内核）时「拓展」图标和文字变青绿色 */
    extensionsActive: Boolean = false,
    /** 去广告开着时「去广告」图标和文字变红 */
    adBlockActive: Boolean = false,
    /** 正在同步：「同步」图标和文字变主题色 */
    syncActive: Boolean = false,
    /** 当前页正显示译文：「翻译」图标和文字变主题色 */
    translateActive: Boolean = false,
    badgeFor: (MenuAction) -> Int = { 0 },
    lockedFor: (MenuAction) -> Boolean = { false },
    /**
     * 编辑状态由外部持有时传入：「完成」按钮由外部画在菜单右上角（不随菜单滚动，见 [MenuEditDoneButton]）。
     * 不传（如引导页）就用内部状态，「完成」画在网格顶部的栏里。
     */
    editingHost: MutableState<Boolean>? = null,
    /** 菜单外层的滚动状态：进入编辑后自动下滚，让隐藏区露出来（菜单最高占屏幕 44%，装不下时才会滚动） */
    scrollState: ScrollState? = null,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val menuContext = LocalContext.current
    val cellHeight = 88.dp
    val dividerHeight = 30.dp
    val columns = 4
    val purple = MenuIncognitoPurple
    val amber = Color(0xFFFFB300)
    val lightBlue = Color(0xFF5AC8FA)
    val teal = Color(0xFF1DB9A5)
    val gridScope = rememberCoroutineScope()
    // 长按计时：按下满 [MENU_LONG_PRESS_MS] 且手指没动（没进入拖动排序）才触发长按功能
    val systemLongPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val longPressNow by rememberUpdatedState(onLongPressAction)

    val ownEditing = remember { mutableStateOf(false) }
    val editingState = editingHost ?: ownEditing
    var editing by editingState
    var dragAction by remember { mutableStateOf<MenuAction?>(null) }
    // 手指在屏幕上累计的位移（不含下面的布局位移补偿）
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // 长按后、尚未拖够距离时的累计位移：超过阈值才真正进入编辑，避免手指轻微抖动误触发（仅有长按功能的图标用）
    var pendingDrag by remember { mutableStateOf(Offset.Zero) }
    val enterEditThresholdPx = with(density) { 24.dp.toPx() }

    // 布局位移补偿：进入编辑时弹层会长高、菜单会自动下滚，图标在屏幕上的位置随之变了，手指却没动。
    // 按下那一刻记下图标的屏幕 y（[anchorY]），之后实时对比它当前的屏幕 y（[trackedY]），差值就是要补的位移——
    // 不再手算「分割线 + 隐藏区高度」，弹层长高 / 菜单下滚 / 头部栏有无，都自动算在里面。
    var trackedAction by remember { mutableStateOf<MenuAction?>(null) }
    var anchorY by remember { mutableFloatStateOf(0f) }
    var trackedY by remember { mutableFloatStateOf(0f) }
    // 靠长按进入编辑的那个图标：同一次手势里它接着被拖起时，沿用按下时记下的锚点
    var entryAction by remember { mutableStateOf<MenuAction?>(null) }
    val rootYs = remember { HashMap<MenuAction, Float>() }
    val effectiveDrag: () -> Offset = {
        Offset(dragOffset.x, dragOffset.y + if (trackedAction != null) anchorY - trackedY else 0f)
    }
    val beginTracking = { action: MenuAction ->
        trackedAction = action
        anchorY = rootYs[action] ?: 0f
        trackedY = anchorY
    }
    val enterEdit = { action: MenuAction ->
        beginTracking(action) // 先记锚点，再改 editing（改了之后才会重新布局）
        entryAction = action
        editing = true
    }
    val headerHeight = 32.dp

    // 进入编辑后自动下沉：等隐藏区排进布局（maxValue 更新）再下滚到底，让隐藏区露出来；
    // 内容没超过菜单最大高度时 maxValue 为 0，什么也不做
    LaunchedEffect(editing) {
        val sc = scrollState
        if (editing && sc != null) {
            withFrameNanos { }
            withFrameNanos { }
            sc.animateScrollTo(sc.maxValue)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellWidthPx = with(density) { (maxWidth / columns).toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }
        val dividerPx = with(density) { dividerHeight.toPx() }
        val visibleRows = ((order.size + columns - 1) / columns).coerceAtLeast(1)

        // 拖拽结束时按「图标中心落在哪」决定去哪个区、第几格（用最新的列表尺寸）。坐标是网格内的：编辑时顶部多一条头部栏
        val finishDrag by rememberUpdatedState { action: MenuAction, fromHidden: Boolean, row: Int, col: Int ->
            val top = if (editing) with(density) { headerHeight.toPx() } else 0f
            val visibleBottom = top + visibleRows * cellHeightPx
            val hiddenTop = visibleBottom + dividerPx
            val originY = if (fromHidden) hiddenTop + row * cellHeightPx else top + row * cellHeightPx
            val drag = effectiveDrag()
            val cx = col * cellWidthPx + cellWidthPx / 2 + drag.x
            val cy = originY + cellHeightPx / 2 + drag.y
            val toHidden = cy >= visibleBottom + dividerPx / 2
            val rowT = ((if (toHidden) cy - hiddenTop else cy - top) / cellHeightPx).toInt().coerceAtLeast(0)
            val colT = (cx / cellWidthPx).toInt().coerceIn(0, columns - 1)
            onMove(action, toHidden, rowT * columns + colT)
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
                            locked = lockedFor(action),
                            cellHeight = cellHeight,
                            tint = when {
                                action == MenuAction.Incognito && incognitoActive -> purple
                                action == MenuAction.Requests && requestsActive -> amber
                                action == MenuAction.ScriptMenu && scriptsActive -> lightBlue
                                action == MenuAction.GeckoExtensions && extensionsActive -> teal
                                action == MenuAction.AdBlock && adBlockActive -> Color(0xFFE53935)
                                action == MenuAction.Sync && syncActive -> MiuixTheme.colorScheme.primary
                                action == MenuAction.Translate && translateActive -> MiuixTheme.colorScheme.primary
                                else -> MiuixTheme.colorScheme.onSurface
                            },
                            dragged = dragAction == action,
                            dragOffset = effectiveDrag,
                            dimmed = (dragAction != null && dragAction != action) || (inHidden && dragAction == null),
                            // 编辑时每个图标右上角：显示区 ×（点了隐藏到下方），隐藏区 +（点了恢复）；「设置」不能隐藏；拖动中不显示
                            editBadge = when {
                                !editing || dragAction != null -> MenuEditBadge.None
                                inHidden -> MenuEditBadge.Restore
                                action == MenuAction.Settings -> MenuEditBadge.None
                                else -> MenuEditBadge.Remove
                            },
                            onBadgeClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (inHidden) onMove(action, false, order.size) else onMove(action, true, hidden.size)
                            },
                            onPositioned = { y ->
                                rootYs[action] = y
                                if (trackedAction == action) trackedY = y
                            },
                            onClick = {
                                if (!editing) {
                                    // 置灰项（Gecko 下不支持）：只提示，不执行
                                    if (lockedFor(action)) {
                                        if (action == MenuAction.GeckoExtensions) {
                                            showWebViewUnsupportedToast(menuContext)
                                        } else {
                                            showGeckoUnsupportedToast(menuContext)
                                        }
                                    } else {
                                        onAction(action)
                                    }
                                }
                            },
                            onDragStart = {
                                // 系统长按（约 0.4s）后进入这里
                                holdJob?.cancelCompat()
                                if (!editing) {
                                    if (action in longPressActions) {
                                        // 有长按功能的图标：再补足到 [MENU_LONG_PRESS_MS]（0.65s）仍没拖动才触发长按功能；拖动则进入编辑（见 onDrag）
                                        holdJob = gridScope.launch {
                                            kotlinx.coroutines.delay((MENU_LONG_PRESS_MS - systemLongPressMs).coerceAtLeast(0L))
                                            if (dragAction == null && !editing && pendingDrag.getDistance() < enterEditThresholdPx) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                longPressNow(action)
                                            }
                                        }
                                    } else {
                                        // 没有长按功能的图标：长按即进入编辑，不用先拖动（弹层自动拉长、菜单自动下沉，出现「完成」和各图标的 ×）。
                                        // 此刻只进入编辑、不拎起图标：松手不会改变顺序；手指接着拖才拎起它
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        enterEdit(action)
                                    }
                                }
                            },
                            onDrag = { delta ->
                                if (delta.x != 0f || delta.y != 0f) {
                                    if (dragAction == null) {
                                        // 已在编辑态：直接跟手；否则累计位移，拖过阈值才进入编辑
                                        pendingDrag += delta
                                        if (editing || pendingDrag.getDistance() >= enterEditThresholdPx) {
                                            if (!editing) {
                                                enterEdit(action)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else if (entryAction != action) {
                                                beginTracking(action) // 编辑中拎起别的图标：锚点取它此刻的位置
                                            }
                                            dragAction = action
                                            dragOffset = pendingDrag
                                            pendingDrag = Offset.Zero
                                        }
                                    } else {
                                        dragOffset += delta
                                    }
                                }
                            },
                            onDragEnd = {
                                holdJob?.cancelCompat()
                                if (editing && dragAction == action) {
                                    finishDrag(action, inHidden, row, col)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                dragAction = null
                                dragOffset = Offset.Zero
                                pendingDrag = Offset.Zero
                                trackedAction = null
                                entryAction = null
                            },
                            onDragCancel = {
                                holdJob?.cancelCompat()
                                dragAction = null
                                dragOffset = Offset.Zero
                                pendingDrag = Offset.Zero
                                trackedAction = null
                                entryAction = null
                            },
                        )
                    }
                    repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 6.dp)) {
            if (editing) {
                // 头部栏：右上角「完成」。外部持有编辑状态时，「完成」由外部画（不随菜单滚动），这里只留出位置
                Box(modifier = Modifier.fillMaxWidth().height(headerHeight)) {
                    Text(
                        text = tr("拖动排序，点 × 隐藏"),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    )
                    if (editingHost == null) {
                        MenuEditDoneButton(onClick = { editing = false }, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
            Cells(order, inHidden = false)
            if (editing) {
                Box(modifier = Modifier.fillMaxWidth().height(dividerHeight)) {
                    Text(
                        text = tr("隐藏的功能（拖到线下方隐藏）"),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 6.dp),
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
                            text = tr("把不常用的功能拖到这里"),
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
    locked: Boolean,
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
    /** 编辑时图标右上角的小标：× 隐藏 / + 恢复 / 无 */
    editBadge: MenuEditBadge = MenuEditBadge.None,
    onBadgeClick: () -> Unit = {},
    /** 布局完成 / 位置变化时回报格子左上角的屏幕 y（不含拖动位移） */
    onPositioned: (Float) -> Unit = {},
) {
    val positioned by rememberUpdatedState(onPositioned)
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    Box(
        modifier = modifier
            .height(cellHeight)
            // 放在 graphicsLayer 之前：报的是布局位置，不含拖动时的平移
            .onGloballyPositioned { positioned(it.positionInRoot().y) }
            .graphicsLayer {
                if (dragged) {
                    val offset = dragOffset()
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
                alpha = if (locked) GECKO_LOCKED_ALPHA else if (dimmed) 0.6f else 1f
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
                if (action == MenuAction.Account) {
                    AccountMenuIcon(label = label)
                } else {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier.size(if (action == MenuAction.ScriptMenu) 31.dp else 26.dp),
                    )
                }
                if (badge > 0 && editBadge == MenuEditBadge.None) {
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
        if (editBadge != MenuEditBadge.None) {
            // 画在格子里（不在 32dp 图标槽里），点击区才不会被图标槽的边界裁掉。
            // 偏移：图标槽中心约在格子中心上方 9dp，× 圆心落在图标右上角附近
            MenuEditBadgeButton(
                kind = editBadge,
                onClick = onBadgeClick,
                modifier = Modifier.align(Alignment.Center).offset(x = 15.dp, y = (-22).dp),
            )
        }
    }
}

/** 编辑时图标右上角的小标种类 */
internal enum class MenuEditBadge { None, Remove, Restore }

/** 圆形小标（16dp，点击区 28dp）：[MenuEditBadge.Remove] 红底 ×，[MenuEditBadge.Restore] 主题色底 + */
@Composable
private fun MenuEditBadgeButton(kind: MenuEditBadge, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (kind == MenuEditBadge.Remove) Color(0xFFE53935) else MiuixTheme.colorScheme.primary
    Box(
        modifier = modifier.size(28.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp).clip(CircleShape).background(bg)) {
            val a = size.minDimension * 0.3f
            val b = size.minDimension * 0.7f
            val m = size.minDimension * 0.5f
            val w = 1.6.dp.toPx()
            if (kind == MenuEditBadge.Remove) {
                drawLine(Color.White, Offset(a, a), Offset(b, b), w)
                drawLine(Color.White, Offset(a, b), Offset(b, a), w)
            } else {
                drawLine(Color.White, Offset(a, m), Offset(b, m), w)
                drawLine(Color.White, Offset(m, a), Offset(m, b), w)
            }
        }
    }
}

/** 编辑态的「完成」按钮（主题色字 + 淡底圆角）。更多菜单把它画在滚动区外面的右上角，见 BrowserSheetHost。 */
@Composable
internal fun MenuEditDoneButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = tr("完成"),
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.primary,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
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
    // 缩略图被 LRU 挤出内存（或重启后还没读回）时，卡片显示出来才从 tabs 表读（解码在后台线程）
    LaunchedEffect(item.id, thumb == null) {
        if (thumb == null) TabThumbs.ensureLoaded(item)
    }
    val cardColor = if (item.incognito) Color(0xFF3D2B4F) else MiuixTheme.colorScheme.surfaceContainerHigh
    val highlightColor = MiuixTheme.colorScheme.primary

    // ---- 左右滑动清除：带阻尼（橡皮筋）跟手，越过阈值时振动一次，松手越过阈值则清除并再振动 ----
    val haptic = LocalHapticFeedback.current
    val onCloseNow by rememberUpdatedState(onClose)
    // 以 item.id 为 key：卡片被清除后列表重排，复用到的卡片不会带着上一张的位移
    var rawDrag by remember(item.id) { mutableFloatStateOf(0f) }
    var shown by remember(item.id) { mutableFloatStateOf(0f) }
    var cardWidth by remember { mutableIntStateOf(0) }
    var passedThreshold by remember(item.id) { mutableStateOf(false) }
    var dismissing by remember(item.id) { mutableStateOf(false) }
    val dragState = rememberDraggableState { delta ->
        if (dismissing) return@rememberDraggableState
        val w = cardWidth.toFloat()
        rawDrag += delta
        shown = SwipeDismiss.damped(rawDrag, w)
        val pass = SwipeDismiss.passed(shown, w)
        if (pass != passedThreshold) {
            passedThreshold = pass
            // 越过阈值（会清除）与退回阈值内（取消清除）都给一次轻振动，让用户知道松手会发生什么
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { cardWidth = it.width }
            .graphicsLayer {
                translationX = shown
                alpha = SwipeDismiss.alpha(shown, cardWidth.toFloat())
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = !dismissing,
                onDragStopped = { velocity ->
                    val w = cardWidth.toFloat()
                    if (SwipeDismiss.shouldDismiss(shown, velocity, w)) {
                        dismissing = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val dir = if (shown >= 0f) 1f else -1f
                        animate(shown, dir * w * 1.15f, animationSpec = tween(180)) { v, _ -> shown = v }
                        onCloseNow()
                    } else {
                        passedThreshold = false
                        rawDrag = 0f
                        animate(shown, 0f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) { v, _ -> shown = v }
                    }
                },
            )
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
            .squircleClip(16.dp)
            .background(cardColor)
            .then(
                if (highlight) {
                    Modifier.squircleBorder(2.dp, highlightColor, 16.dp)
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
                                style = GlassLook.style(isAppDarkTheme()),
                                material = GlassLook.material(isAppDarkTheme()),
                                stroke = GlassEdgeStrokes.forTheme(isAppDarkTheme()),
                                shading = GlassLook.shading(),
                            )
                        } else {
                            Modifier.background(MiuixTheme.colorScheme.surface.copy(alpha = 0.55f))
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = buildString {
                        if (item.incognito) append(tr("无痕 · "))
                        append(item.title.ifBlank { tr("新标签页") })
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
                    text = when {
                        // 重启后恢复出来、还没加载的标签：显示它保存的页面，而不是「主页」
                        item.showsSavedPage -> hostOf(item.effectiveUrl)
                        item.url.isBlank() -> tr("主页")
                        else -> hostOf(item.url)
                    },
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, minWidth = 28.dp, minHeight = 28.dp) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Close,
                        contentDescription = tr("关闭"),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * 标签页 sheet 头部的圆形按钮。柔光玻璃（[softBackdrop] 非空）：用柔光玻璃材质并以页面内容层为采样源，
 * 与底栏 / 顶栏按钮一致；miuix 的玻璃按钮在顶栏之外不传采样源会退回平底色，所以必须显式传。
 * 其它模式沿用 [LiquidGlassButton]（液态玻璃 / 磨砂 / 关闭模糊各自的材质）。
 */
@Composable
internal fun TabsHeaderButton(
    onClick: () -> Unit,
    liquid: com.kyant.backdrop.Backdrop?,
    softBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (softBackdrop != null) {
        HapticGlassIconButton(
            onClick = { if (enabled) onClick() },
            modifier = modifier.alpha(if (enabled) 1f else 0.4f),
            backdrop = softBackdrop,
            content = content,
        )
    } else {
        LiquidGlassButton(onClick = onClick, backdrop = liquid, modifier = modifier, enabled = enabled, content = content)
    }
}

/** 标签页 sheet 左上角圆形新建按钮：在当前栏（标签页 / 无痕浏览页）新建，无长按操作。 */
@Composable
internal fun TabAddButton(
    canAdd: Boolean,
    onClick: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop?,
    modifier: Modifier = Modifier,
    softBackdrop: LayerBackdrop? = null,
) {
    TabsHeaderButton(
        onClick = onClick,
        liquid = backdrop,
        softBackdrop = softBackdrop,
        modifier = modifier,
        enabled = canAdd,
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.Add,
            contentDescription = tr("新建标签页"),
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
        // 最宽 236dp，窄屏时随可用宽度收缩，不再把两侧按钮挤得重叠
        modifier = modifier
            .widthIn(max = 236.dp)
            .fillMaxWidth()
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

/**
 * 更多菜单里「账户」的图标：未登录 = 灰色头像图标；已登录且账号设置了头像 = 圆形头像（异步加载，加载完成前先显示图标）；
 * 已登录但用默认头像 / 头像取不到 = 主色头像图标。头像地址只认 https。
 */
@Composable
internal fun AccountMenuIcon(
    label: String,
    avatarSize: androidx.compose.ui.unit.Dp = 28.dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
    /** 未登录时的图标颜色；null = 默认的灰色 */
    unsignedTint: Color? = null,
) {
    val signedIn = if (EngineSupport.SYNC_BUNDLED) FxaSync.ui as? FxaUi.SignedIn else null
    val url = signedIn?.avatarUrl?.takeIf { it.startsWith("https://") }.orEmpty()
    val avatar = rememberRemoteIcon(url)
    if (avatar != null) {
        androidx.compose.foundation.Image(
            bitmap = avatar,
            contentDescription = label,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(avatarSize)
                .clip(androidx.compose.foundation.shape.CircleShape),
        )
    } else {
        Icon(
            imageVector = AppIcons.Account,
            contentDescription = label,
            tint = if (signedIn != null) MiuixTheme.colorScheme.primary else unsignedTint ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(iconSize),
        )
    }
}
