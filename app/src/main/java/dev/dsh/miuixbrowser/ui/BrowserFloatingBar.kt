package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.browser.AppRoute
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.BAR_POS_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_TOP
import android.os.Build
import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.focus.FocusManager
import androidx.compose.runtime.MutableState
import top.yukonga.miuix.kmp.blur.LayerBackdrop as MiuixLayerBackdrop


/** 悬浮底栏：地址栏 + 工具栏 / 缩小后的圆形搜索钮，以及地址栏输入时的「收键盘」热区（从 BrowserScreen 拆出）。
 *  [hide] / [barVisible] 是读取函数：弹簧动画每帧都变，只允许在绘制 / 派生层读取，不能让调用方逐帧重组。 */
@Composable
internal fun BoxScope.BrowserFloatingBar(
    ui: BrowserUiState,
    settings: AppSettings,
    state: BrowserState,
    tab: BrowserTab,
    context: android.content.Context,
    clipboard: ClipboardManager,
    focus: FocusManager,
    haptic: HapticFeedback,
    barLayout: BarLayout,
    alwaysMin: Boolean,
    barFixed: Boolean,
    displayAddress: String,
    glassAvailable: Boolean,
    glassBackdrop: MiuixLayerBackdrop,
    barLiquidBackdrop: com.kyant.backdrop.Backdrop,
    barMinimizedState: MutableState<Boolean>,
    addressDirtyState: MutableState<Boolean>,
    hide: () -> Float,
    barVisible: () -> Boolean,
    quickRunner: MenuActionRunner,
    pushPage: (AppRoute) -> Unit,
) {
    // 栏的「边 + 样式」：悬浮胶囊 / 停靠平铺等的差异都从 style 取，这里不再自己判断固定模式
    val style = barLayout.style
    val metrics = style.metrics
    val barOnTop = barLayout.onTop
    val barEdge = barLayout.edge
    var sheet by ui::sheet
    var address by ui::address
    var editing by ui::editing
    var barHidden by ui::barHidden
    var addressScriptCount by ui::addressScriptCount
    var tabsPage by ui::tabsPage
    var barMinimized by barMinimizedState
    var addressDirty by addressDirtyState
    val addressDownload = SharedAddressDownloadState
    if (editing && tab.url.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        editing = false
                        focus.clearFocus()
                    })
                },
        )
    }

    // ---- 底部：地址栏 + 工具栏 ----
    // 根据地址文本长度动态调整宽度，并使用弹簧动画平滑过渡。
    BoxWithConstraints(
        modifier = Modifier
            .align(if (barOnTop) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .then(
                when {
                    // 停靠：底板自己铺到状态栏 / 导航栏下面，系统栏的避让在栏内部处理；
                    // 靠下时只需要让整个栏跟着键盘走，键盘弹出时栏贴在键盘上沿
                    style.docked -> if (barOnTop) Modifier else Modifier.imePadding()
                    // 悬浮靠上：状态栏下方留一段间距，键盘弹出时不跟着动
                    barOnTop -> Modifier.statusBarsPadding().padding(top = style.edgeGap(barEdge))
                    // 悬浮靠下：避开键盘 / 导航栏
                    else -> Modifier.imePadding().navigationBarsPadding().padding(bottom = style.edgeGap(barEdge))
                },
            )
            .padding(horizontal = style.horizontalMargin),
    ) {
        // 收起 / 弹出动画的方向：靠下向下沉，靠上向上缩
        val barEdgeSign = if (barOnTop) -1f else 1f
        // 按实际字体宽度计算地址栏所需空间：固定按钮区 + 地址文字 + 地址栏内边距。
        // 不设置人为最小宽度，短文字不会留下空白；只有屏幕可用宽度作为上限。
        val textMeasurer = rememberTextMeasurer()
        // 编辑地址时宽度至少是「搜索或输入网址」占位文字的宽度；输入内容比它长时随输入自动延长（上限仍是屏幕可用宽度），
        // 否则超出的部分被地址栏裁掉，看不见也点不到。
        val addressStyle = TextStyle(fontSize = 15.sp)
        val placeholderPx = textMeasurer.measure(
            text = AnnotatedString(tr("搜索或输入网址")),
            style = addressStyle,
            maxLines = 1,
        ).size.width
        val measuredAddressPx = if (editing) {
            val typedPx = if (address.isEmpty()) 0 else textMeasurer.measure(
                text = AnnotatedString(address),
                style = addressStyle,
                maxLines = 1,
            ).size.width
            kotlin.math.max(placeholderPx, typedPx)
        } else if (displayAddress.isBlank()) {
            placeholderPx
        } else {
            textMeasurer.measure(
                text = AnnotatedString(displayAddress),
                style = addressStyle,
                maxLines = 1,
            ).size.width
        }
        // 编辑时给光标留一点余量：光标在行尾时正好贴着文字右缘，会被裁掉
        val cursorSlack = if (editing) 4.dp else 0.dp
        val measuredAddressWidth = with(LocalDensity.current) { measuredAddressPx.toDp() } + cursorSlack
        val fixedToolbarWidth = metrics.fixedWidth
        val loadingIndicatorWidth = if (tab.loading) 15.dp else 0.dp
        val refreshWidth = if (shouldShowAddressRefresh(displayAddress, tab.loading, editing)) AddressRefreshWidth else 0.dp
        // 阅读模式图标（地址栏最左侧）：可进入 / 正在阅读，且有页面（主页 url 为空不显示）
        val readerShown = tab.url.isNotEmpty() && (tab.readerable || tab.readerActive)
        // 阅读层按当前应用主题配色：用 surface 亮度判断深浅（MiuixTheme 的取值只能在组合里读，不能放进点击回调）
        val readerSurface = MiuixTheme.colorScheme.surface
        val readerDark = (0.299f * readerSurface.red + 0.587f * readerSurface.green + 0.114f * readerSurface.blue) < 0.5f
        // 脚本图标与阅读图标共用这一个位置（宽度相同）：阅读优先，没有阅读图标时才可能显示脚本图标
        // 优先级：下载 > 阅读 > 脚本，三者共用同一个位置（宽度相同）
        // 优先级：翻译 > 下载 > 阅读 > 脚本
        val translateVisible = tab.translate.iconVisible
        val translateShown = shouldShowAddressTranslate(translateVisible, editing)
        val downloadShown = shouldShowAddressDownload(addressDownload.visible, editing, translateVisible)
        val scriptsShown = shouldShowAddressScripts(readerShown, addressScriptCount > 0, editing, addressDownload.visible, translateVisible)
        val readerWidth = if (translateShown || downloadShown || shouldShowAddressReader(readerShown, editing, addressDownload.visible, translateVisible) || scriptsShown) AddressReaderWidth else 0.dp
        val requestedBarWidth = fixedToolbarWidth + measuredAddressWidth + loadingIndicatorWidth + refreshWidth + readerWidth
        // 显式比较 Dp，避免当前 Kotlin/Compose 版本对 coerceAtMost 的错误重载推断。
        val targetBarWidth = if (requestedBarWidth < maxWidth) requestedBarWidth else maxWidth
        val animatedBarWidth by animateDpAsState(
            targetValue = targetBarWidth,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            label = "dynamicBottomBarWidth",
        )
        // 悬浮三段式：页面向下滑动后缩成标题小胶囊（输入网址 / 主页不缩）。进度只在绘制阶段读取，不让弹簧逐帧重组
        val compact = style.splitPills && ui.barCompact && !editing && tab.url.isNotEmpty()
        val compactProgress = animateFloatAsState(
            targetValue = if (compact) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            label = "safariCompact",
        )
        // 固定在顶部 / 底部（平铺停靠）在首页时底板透明：栏的内容直接叠在首页壁纸上，与壁纸融为一体。
        // 进度只在绘制阶段读取。
        val homeClearProgress = animateFloatAsState(
            targetValue = if (style.docked && !style.splitPills && tab.url.isEmpty()) 1f else 0f,
            animationSpec = tween(durationMillis = 220),
            label = "dockedHomeClear",
        )
        var dragDistance by remember(tab.id) { mutableFloatStateOf(0f) }
        var hDragDistance by remember(tab.id) { mutableFloatStateOf(0f) }

        // 退出动画播放期间仍保留底栏；动画结束后直接从组合树移除，
        // 不留下透明/平移后的 Compose 节点拦截 WebView 的触摸事件。
        if (barVisible()) {
            // 缩小后的圆形搜索钮（弹性缩放入场）
            if (barMinimized && !style.docked) {
                val circleScale by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                    label = "barCircleIn",
                )
                Box(
                    modifier = Modifier
                        .align(if (barOnTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(top = if (barOnTop) 4.dp else 0.dp, bottom = if (barOnTop) 0.dp else 4.dp)
                        .size(48.dp)
                        .pointerInput(tab.id, barOnTop) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    hDragDistance += dragAmount.x
                                    // 再次左右滑，或朝屏幕中间滑（靠下上滑 / 靠上下滑）可展开
                                    if (kotlin.math.abs(hDragDistance) > 72f || dragAmount.y * barEdgeSign < -40f) {
                                        barMinimized = false
                                        hDragDistance = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        change.consume()
                                    }
                                },
                                onDragEnd = { hDragDistance = 0f },
                                onDragCancel = { hDragDistance = 0f },
                            )
                        }
                        .graphicsLayer {
                            translationY = barEdgeSign * hide() * 210.dp.toPx()
                            alpha = (1f - hide()) * circleScale
                            scaleX = circleScale
                            scaleY = circleScale
                            transformOrigin = TransformOrigin(0.5f, if (barOnTop) 0f else 1f)
                        }
                        .clickable {
                            barMinimized = false
                            if (!editing) {
                                editing = true
                                address = tab.url
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .glassBar(
                                backdrop = glassBackdrop,
                                corner = 24.dp,
                                blurEnabled = settings.blurEnabled,
                                textureBlurEnabled = glassAvailable,
                                liquid = if (settings.liquidGlass) barLiquidBackdrop else null,
                            ),
                    )
                    Icon(
                        imageVector = MiuixIcons.Glass.Search,
                        contentDescription = tr("搜索或输入网址"),
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
            Column(
                modifier = Modifier
                    .align(if (barOnTop) Alignment.TopCenter else Alignment.BottomCenter)
                    // 悬浮：宽度随地址文字弹簧伸缩；停靠：整宽，平铺到屏幕边缘
                    .then(if (style.docked) Modifier.fillMaxWidth() else Modifier.width(animatedBarWidth))
                    // 在底栏上朝屏幕边缘滑动（靠下向下 / 靠上向上）主动收起；左右滑动缩小为搜索圆钮；
                    // 朝屏幕中间快速一扫（靠下快速上扫 / 靠上快速下扫）把底栏换到另一侧并记进设置。
                    // 编辑地址时不挂这组手势：横向拖动要留给输入框（移动光标 / 滚动文字），
                    // 否则累计 72px 就会把底栏缩成圆钮，向下拖还会收起底栏
                    // 悬浮三段式不挂这组手势：它的 Column 是整宽的，三块玻璃件之间 / 周围的透明区域也会被当成底栏吃掉点击，
                    // 盖住下面的网页。三段式里只有带材质的三块才算底栏（地址栏自己处理左右滑 / 上滑，页面下滑时整条会缩成小胶囊）
                    .then(
                        if (style.splitPills) Modifier else Modifier
                        .pointerInput(tab.id, barHidden, editing, barOnTop, alwaysMin, barFixed) {
                            if (editing || barFixed) return@pointerInput
                            val tracker = VelocityTracker()
                            val flingPx = 1_200.dp.toPx()
                            detectDragGestures(
                                onDragStart = { tracker.resetTracking() },
                                onDrag = { change, dragAmount ->
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) {
                                        hDragDistance += dragAmount.x
                                        if (kotlin.math.abs(hDragDistance) > 72f) {
                                            barMinimized = true
                                            hDragDistance = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            change.consume()
                                        }
                                    } else {
                                        // 统一成「朝屏幕边缘为正」，靠上时方向取反
                                        dragDistance += dragAmount.y * barEdgeSign
                                        // 提高竖向隐藏阈值，降低误触
                                        // 主页不允许收起底栏：收起后没有任何手势能唤回
                                        if (dragDistance > 56f && !barHidden && tab.url.isNotEmpty()) {
                                            barHidden = true
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            change.consume()
                                        } else if (dragDistance < -56f && barHidden) {
                                            barHidden = false
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            change.consume()
                                        }
                                    }
                                },
                                onDragEnd = {
                                    // 快速朝屏幕中间一扫：换到另一侧（「始终最小化」下不换，位置由设置决定）
                                    if (!alwaysMin) {
                                        val v = tracker.calculateVelocity()
                                        val towardCenter = v.y * barEdgeSign < -flingPx &&
                                            kotlin.math.abs(v.y) > kotlin.math.abs(v.x) * 1.5f
                                        if (towardCenter) {
                                            settings.updateBarPosition(if (barOnTop) BAR_POS_BOTTOM else BAR_POS_TOP)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                    dragDistance = 0f
                                    hDragDistance = 0f
                                },
                                onDragCancel = {
                                    dragDistance = 0f
                                    hDragDistance = 0f
                                },
                            )
                        }
                    )
                    .graphicsLayer {
                        translationY = barEdgeSign * hide() * 210.dp.toPx()
                        alpha = 1f - hide()
                        // 停靠的整宽栏只做位移 + 淡出，不缩放
                        val s = if (style.docked) 1f else 1f - 0.08f * hide()
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
        // 底板由样式画（悬浮：圆角胶囊玻璃；停靠：铺满整宽、一直铺到状态栏 / 导航栏下面的玻璃条），
        // 与按钮内容分层，避免 backdrop 采集层递归包含自身。停靠样式下系统栏的高度只加在内容层上。
        Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                // 缩小成小胶囊时整条底板淡出（网页铺满屏幕，只剩标题胶囊盖在上面）
                .graphicsLayer { alpha = if (style.splitPills) 1f - compactProgress.value else 1f - homeClearProgress.value }
                .then(
                    style.surface(
                        modifier = Modifier,
                        edge = barEdge,
                        backdrop = glassBackdrop,
                        blurEnabled = settings.blurEnabled,
                        textureBlurEnabled = glassAvailable,
                        liquid = if (settings.liquidGlass) barLiquidBackdrop else null,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!style.docked) Modifier else {
                        // 横屏时让按钮避开刘海 / 侧边导航栏；竖直方向：靠上避开状态栏，靠下避开导航栏
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .then(if (barOnTop) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding())
                    },
                ),
        ) {
        if (style.splitPills) {
            // Safari 三段式：左后退 / 中地址栏 / 右更多（地址栏：长按新建、左右滑切换标签、上滑开标签列表）
            // 整体放大 SafariSplitScale（5%）：栏内一切 dp / sp 随 Density 放大；这里传进去的是放大前的尺寸（对外占位用的 metrics 已含放大）
            val baseDensity = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(
                    density = baseDensity.density * SafariSplitScale,
                    fontScale = baseDensity.fontScale,
                ),
            ) {
            SafariSplitBar(
                metrics = metrics.scaledBy(1f / SafariSplitScale),
                backdrop = glassBackdrop,
                liquid = if (settings.liquidGlass) barLiquidBackdrop else null,
                blurEnabled = settings.blurEnabled,
                textureBlurEnabled = glassAvailable,
                address = displayAddress,
                loading = tab.loading,
                // 首页（url 为空）后退不可点；阅读模式中「后退」= 退出阅读模式，所以即使没有历史也可点
                atHome = tab.url.isEmpty(),
                canGoBack = tab.canGoBack || tab.readerActive,
                editing = editing,
                compact = compact,
                // 点小胶囊：恢复三段式，网页让出栏的位置
                onExpand = {
                    ui.barCompact = false
                    ui.barOverlap = false
                },
                onChange = {
                    address = it
                    addressDirty = true
                    editing = true
                },
                onGo = {
                    if (address.isNotBlank()) {
                        editing = false
                        focus.clearFocus()
                        tab.load(address)
                    }
                },
                onFocusLost = { if (editing) editing = false },
                onAddressTap = {
                    if (!editing) {
                        editing = true
                        address = tab.url
                    }
                },
                readerVisible = readerShown,
                readerActive = tab.readerActive,
                onReader = { tab.toggleReader(readerDark) },
                scriptsRunning = addressScriptCount > 0,
                onScripts = { quickRunner.run(MenuAction.ScriptMenu) },
                download = addressDownload,
                onDownload = {
                    addressDownload.acknowledge()
                    pushPage(AppRoute.Downloads)
                },
                translateVisible = tab.translate.iconVisible,
                translateOriginal = tab.translate.showingOriginal,
                onTranslateToggle = { tab.translate.toggleOriginal() },
                searchEngine = settings.allSearchEngines.getOrNull(settings.searchIndex),
                onEngineClick = { ui.engineMenuAnchor = it; ui.engineMenuShow = !ui.engineMenuShow },
                onBack = { tab.goBack() },
                onMore = { sheet = Sheet.Menu },
                onRefresh = { tab.reload() },
                onHome = { state.goHome(tab) },
                onSwitchTab = { dir ->
                    // 只在同一类标签（普通 / 无痕）里切换，不绕回，也不在两端新建
                    val same = state.tabs.withIndex().filter { it.value.incognito == tab.incognito }.map { it.index }
                    val target = same.getOrNull(same.indexOf(state.activeIndex) + dir)
                    if (target != null) state.select(target)
                },
                onOpenTabs = {
                    tabsPage = if (tab.incognito) 1 else 0
                    // 打开标签页面板前刷新当前标签的缩略图
                    tab.captureThumbnailNow()
                    sheet = Sheet.Tabs
                },
            )
            }
        } else BottomBar(
            drawSurface = false,
            metrics = metrics,
            blurEnabled = settings.blurEnabled,
            liquid = if (settings.liquidGlass) barLiquidBackdrop else null,
            textureBlurEnabled = glassAvailable,
            backdrop = glassBackdrop,
            address = displayAddress,
            loading = tab.loading,
            // 阅读模式中「后退」= 退出阅读模式，所以即使没有历史也可点
            canGoBack = tab.canGoBack || tab.readerActive,
            canGoForward = tab.canGoForward,
            tabCount = state.tabs.size,
            onChange = {
                address = it
                addressDirty = true
                editing = true
            },
            onFocusLost = {
                if (editing) editing = false
            },
            onGo = {
                if (address.isNotBlank()) {
                    editing = false
                    focus.clearFocus()
                    tab.load(address)
                }
            },
            onBack = { tab.goBack() },
            onForward = { tab.goForward() },
            onHome = { state.goHome(tab) },
            onCopyAddress = {
                if (tab.url.isNotBlank()) {
                    clipboard.setText(AnnotatedString(tab.url))
                    // Android 13+ 系统会自己弹「已复制」，旧版本补一个提示
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                        android.widget.Toast.makeText(context, tr("已复制网址"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onAddressTap = {
                if (!editing) {
                    editing = true
                    address = tab.url
                }
            },
            editing = editing,
            readerVisible = readerShown,
            readerActive = tab.readerActive,
            onReader = { tab.toggleReader(readerDark) },
            scriptsRunning = addressScriptCount > 0,
            onScripts = { quickRunner.run(MenuAction.ScriptMenu) },
            download = addressDownload,
            onDownload = {
                addressDownload.acknowledge()
                pushPage(AppRoute.Downloads)
            },
            translateVisible = tab.translate.iconVisible,
            translateOriginal = tab.translate.showingOriginal,
            onTranslateToggle = { tab.translate.toggleOriginal() },
            searchEngine = settings.allSearchEngines.getOrNull(settings.searchIndex),
            onEngineClick = { ui.engineMenuAnchor = it; ui.engineMenuShow = !ui.engineMenuShow },
            onShare = {
                if (tab.url.isNotBlank()) {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, tab.url)
                                },
                                tr("分享链接"),
                            ),
                        )
                    }
                }
            },
            onTabs = {
                tabsPage = if (tab.incognito) 1 else 0
                // 打开标签页面板前刷新当前标签的缩略图
                tab.captureThumbnailNow()
                sheet = Sheet.Tabs
            },
            onNewTab = { state.newTab(incognito = tab.incognito) },
            onMore = { sheet = Sheet.Menu },
            onRefresh = { tab.reload() },
        )
        }
        }
            }
            } // end else (!barMinimized)
        }
    }
}
