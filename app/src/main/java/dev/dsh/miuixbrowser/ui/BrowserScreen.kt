package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.appContainer
import dev.dsh.miuixbrowser.browser.AppRoute
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.DefaultBrowser
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.browser.MenuOrderStore
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.BAR_POS_FIXED_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_FIXED_TOP
import dev.dsh.miuixbrowser.data.BAR_POS_MINIMIZED
import dev.dsh.miuixbrowser.data.BAR_POS_TOP
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoDebug
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.hostOf
import android.app.Activity
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Scaffold
import dev.dsh.miuixbrowser.ui.icons.Settings
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BrowserScreen(
    settings: AppSettings,
    initialUrl: String? = null,
    onInitialUrlConsumed: () -> Unit = {},
    openDownloads: Boolean = false,
    onOpenDownloadsConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val state = remember {
        BrowserState(context, settings.userAgent).also {
            // 启动时把设置里保存的 UA 应用到所有标签（之前只有在设置里点确定才会应用，重启后一律回到系统默认 UA）
            it.applyUserAgent(settings.userAgent)
            // 火狐同步：本机标签（非无痕）随同步上传
            if (EngineSupport.SYNC_BUNDLED) FxaSync.localTabs = { it.tabs.filter { t -> !t.incognito }.map { t -> t.title to t.url } }
        }
    }
    // 标签列表落盘（tabs 表，见 TabDb）：标签增减 / 换页 / 标题 / 顺序 / 切换变化时才触发，
    // 每次保存后至少隔 0.8 秒再处理下一次（snapshotFlow 自带合并，只取最新）；退到后台时 MainActivity 会立刻再存一次
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.tabRows() }.collect { rows ->
            state.saveTabs(rows)
            kotlinx.coroutines.delay(800)
        }
    }
    val favorites = remember { context.appContainer.favorites }
    // 首页收藏栏：只取「个人收藏」分组（读取 items 让列表变化时自动重组）
    val homeFavEntries = favorites.homeEntries()
    // 首页「书签分组」：按已存的文件夹 id 解析出当前路径（改名后 id 不变）；已被删掉的文件夹自动略过。
    // 读 folderRev / items 让文件夹改名、删除、书签增减时自动重组
    val homeGroups = run {
        favorites.folderRev
        favorites.homeGroupIds.mapNotNull { id ->
            favorites.folderPath(id)?.let { path ->
                HomeGroup(id, path, favorites.itemsInTree(path).take(4).map { it.url })
            }
        }
    }
    remember { Diag.log("BrowserScreen composed, favorites=" + favorites.items.size) }
    val menuOrder = remember { MenuOrderStore(context) }
    // 首次安装 / 升级后的用户引导：当前步骤放在这里（引导里点进二级页面再回来，仍停在原步骤）
    var onboardingStep by rememberSaveable { mutableIntStateOf(0) }
    // 设置 → 关于 → 使用指南：已经看过引导的人主动重看（不写完成标记、不依赖 settings.onboardingPending）
    var onboardingReplay by rememberSaveable { mutableStateOf(false) }
    // 首页「快捷菜单」点击功能时转发给下面的 runMenuAction（它要等脚本菜单等函数声明之后才能定义）
    val quickRunner = remember { MenuActionRunner() }
    // 模糊壁纸：读/写磁盘缓存里已模糊好的小图，不再实时渲染模糊（首帧同步读缓存，命中时没有未模糊→模糊的闪动）
    val blurredWallpaper = rememberBlurredWallpaper(settings.wallpaper, settings.wallpaperBlur, settings.wallpaperVersion)
    // 模糊图已就绪时屏幕上只会用到它：不再解一份屏幕分辨率的整图（约 10MB），「有壁纸」的判断也直接用模糊图
    val wallpaperFromCache = settings.wallpaperBlur && blurredWallpaper != null
    val fullWallpaper = rememberWallpaper(settings.wallpaper, settings.wallpaperVersion, enabled = !wallpaperFromCache)
    val wallpaperBitmap = if (wallpaperFromCache) blurredWallpaper else fullWallpaper
    // 壁纸显示层的位置 / 尺寸与预模糊图：首页卡片据此直接贴图当玻璃底（见 HomeBitmapGlass.kt）
    val wallpaperLayout = remember { WallpaperLayout() }
    val wallpaperSource = remember(blurredWallpaper, wallpaperFromCache) {
        if (wallpaperFromCache && blurredWallpaper != null) WallpaperSource(wallpaperLayout, blurredWallpaper) else null
    }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val wallpaperBackdrop = rememberLayerBackdrop()
    val browserSurfaceColor = MiuixTheme.colorScheme.surface
    val browserBackdrop = rememberLayerBackdrop {
        // 给采集层补一层不透明底色，避免网页透明区域出现模糊采样伪影。
        drawRect(browserSurfaceColor)
        drawContent()
    }
    // 首页的玻璃取样源：有壁纸时统一取壁纸（不再采样含卡片的整页内容，避免重复模糊）；无壁纸时回退整页采集层
    val homeBackdrop = if (wallpaperBitmap != null) wallpaperBackdrop else browserBackdrop
    // liquid glass 的取样源（Kyant）：首页卡片只取未模糊的壁纸；底栏取整页采集层（见 barLiquidBackdrop）
    val wallpaperLiquidBackdrop = rememberKyantBackdrop()
    // Kyant 液态玻璃的采集层（只给标签页 sheet 头部的左右按钮用）
    val liquidBackdrop = rememberKyantBackdrop {
        drawRect(browserSurfaceColor)
        drawContent()
    }
    // 底栏 / 缩小后的圆形搜索钮：取样「下层背景」（壁纸 + 滚动中的首页内容）的整页采集层，
    // 不再只采壁纸，这样内容从底栏下方滑过时能看到折射与玻璃效果。
    val barLiquidBackdrop = liquidBackdrop
    // 采集层缓存成离屏纹理：否则每个取样方（底栏 / 每个收藏卡片 / 顶部渐进模糊 / 弹层）
    // 每一帧都会把整页内容（含壁纸 28dp 模糊）重新回放一遍再叠自己的模糊，弹层动画期间必然掉帧。
    // 缓存后内容只在真正变化（滚动、加载）时重绘，取样方只读纹理。
    SideEffect {
        wallpaperBackdrop.graphicsLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
        browserBackdrop.graphicsLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
    }

    // 从其他应用点进来的链接：回到浏览页并新开标签（须在 popToBrowser 定义之后）
    // 由 MainActivity 传入；这里只负责导航与加载
    val extensions = remember { context.appContainer.extensions }
    BrowserStartupJobs(settings, context, extensions)
    // 首次打开：询问是否设为系统默认浏览器。无论选了设置、取消还是点外面关掉都只问这一次；已经是默认就不问
    // 共享界面状态（弹层 / 对话框 / 文件选择等）放在 BrowserUiState（BrowserUiState.kt），这里按原名委托访问
    val ui = remember { BrowserUiState() }
    var showDefaultBrowserPrompt by ui::showDefaultBrowserPrompt
    val defaultBrowserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(Unit) {
        if (settings.defaultBrowserPrompted) return@LaunchedEffect
        // 引导里自带「设为默认浏览器」一步，引导完成 / 跳过时会一并标记已询问，这里不再重复弹
        if (settings.onboardingPending) return@LaunchedEffect
        if (DefaultBrowser.isDefault(context)) {
            settings.markDefaultBrowserPrompted()
        } else {
            kotlinx.coroutines.delay(800) // 等首页画出来再弹，不和启动抢
            showDefaultBrowserPrompt = true
        }
    }
    val history = remember { context.appContainer.history }
    val tab = state.activeTab
    // 页面加载期间也禁用 MIUIX 采集，避免 URL 尚未更新时误把 WebView 帧送进 RuntimeShader。
    // 回主页时 WebView 要先真正隐藏，再开启 MIUIX 背景采集：
    // 隐藏发生在 LaunchedEffect（下一帧）里，若同一帧就开始采集，
    // 会把还可见的 WebView 录进 layerBackdrop 并送进 RuntimeShader，导致原生崩溃。
    var webViewHidden by remember(tab.id) { mutableStateOf(false) }
    val homeBlurAvailable = tab.url.isEmpty() && !tab.loading && webViewHidden
    // WebView 内核：WebView 已显式开启硬件层（见 BrowserTab.initWebView），录进 layerBackdrop 不再闪烁/崩溃，
    // 所以网页态下底栏 / 弹窗也可以采样整页（含 WebView）使用高级材质。
    // Gecko 内核（SurfaceView 合成）仍不采样，继续用假玻璃回退。
    val webGlassAvailable = !tab.isGecko && tab.url.isNotEmpty()
    // 玻璃能否采样：主页（Compose 内容）或 WebView 网页态
    val glassAvailable = homeBlurAvailable || webGlassAvailable
    // 底栏 / 弹窗统一用的 MIUIX 采样层：网页态必须取整页采集层（壁纸层此时没有绘制），主页沿用 homeBackdrop
    val glassBackdrop = if (webGlassAvailable) browserBackdrop else homeBackdrop
    // 新建标签页（首页）的滚动状态：每个标签独立，供顶部渐进模糊带跟随滚动淡入
    val startScroll = remember(tab.id) { ScrollState(0) }
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current

    var sheet by ui::sheet
    var linkUrl by ui::linkUrl
    var imageUrl by ui::imageUrl
    var elementHit by ui::elementHit
    var address by ui::address
    var editing by ui::editing
    var barHidden by ui::barHidden
    /** 底栏被左右滑动缩小为圆形搜索钮 */
    val barMinimizedState = remember { mutableStateOf(settings.barPosition == BAR_POS_MINIMIZED) }
    var barMinimized by barMinimizedState
    // 「布局 → 导航栏位置」：顶部 / 底部 / 始终最小化（圆钮是常态，展开后一段时间无操作自动缩回）
    val barOnTop = settings.barPosition == BAR_POS_TOP || settings.barPosition == BAR_POS_FIXED_TOP
    val alwaysMin = settings.barPosition == BAR_POS_MINIMIZED
    // 固定在顶部 / 底部：不随滚动自动隐藏，也不响应滑动收起 / 缩成圆钮 / 换边
    val barFixed = settings.barPosition == BAR_POS_FIXED_TOP || settings.barPosition == BAR_POS_FIXED_BOTTOM
    var showQuickPicker by ui::showQuickPicker
    var showGroupPicker by ui::showGroupPicker
    // 书签分组弹窗：显示开关与路径 / 锚点分开存，关闭动画期间内容不会突然消失
    var showGroupPopup by ui::showGroupPopup
    var showFavEditor by ui::showFavEditor
    var showBookmarkCreate by ui::showBookmarkCreate
    // 二级页面返回栈：官方 miuix-nav NavDisplay。栈底是浏览主界面。
    val backStack = remember { navBackStackOf(AppRoute.Browser) }
    fun pushPage(route: AppRoute) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }
    fun popPage() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    fun popToBrowser() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** 应用内打开链接：切回浏览主界面并加载（设置/关于/书签等非网页页共用） */
    fun openInBrowser(url: String) {
        if (url.isBlank()) return
        popToBrowser()
        state.openOrReuse(url, navigateIfDeepLink = true)
    }

    // 外部 ACTION_VIEW / 应用内待打开链接：切回浏览页并加载
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            openInBrowser(initialUrl)
            onInitialUrlConsumed()
        }
    }

    // 菜单面板右上角蓝标：当前页面运行的脚本数
    // 菜单「同步」进行中：图标变主题色、文字变「同步中」，防止重复点击
    // 地址栏左侧脚本图标：当前标签页面运行的脚本数（加载完成后查询，见下方 LaunchedEffect）
    // 地址栏左侧下载图标：进行中显示进度环，完成后右上角绿点（点开下载页后清除）
    val addressDownload = SharedAddressDownloadState
    LaunchedEffect(addressDownload) { addressDownload.track() }
    var showFind by ui::showFind
    // 离场动画期间保留最近一次打开的弹层内容
    // 弹层从打开到离场动画结束的整段时间为 true：期间把（主页）整页冻结成一张纹理
    var sheetHostActive by ui::sheetHostActive
    // 标签列表当前栏：0 = 标签页，1 = 无痕浏览页
    var tabsPage by ui::tabsPage
    // 文件选择 + 视频全屏 + 网页权限询问
    var fileChooserChoice by ui::fileChooserChoice
    var customView by ui::customView

    ui.AppUpdateEffects(settings, context)

    LaunchedEffect(openDownloads) {
        if (openDownloads) {
            pushPage(AppRoute.Downloads)
            sheet = Sheet.None
            onOpenDownloadsConsumed()
        }
    }

    // 网页增强总开关 → 所有标签页
    LaunchedEffect(settings.pageEnhancements, state.tabs.size) {
        state.tabs.forEach { it.pageEnhancements = settings.pageEnhancements }
    }

    LaunchedEffect(tab.id) {
        tab.onLongPressLink = { link ->
            linkUrl = link
            sheet = Sheet.Link
        }
        tab.onLongPressImage = { url ->
            imageUrl = url
            sheet = Sheet.Image
        }
        tab.onElementLongPress = { hit ->
            elementHit = hit
            sheet = Sheet.Element
        }
        editing = false
        barHidden = false
        barMinimized = settings.barPosition == BAR_POS_MINIMIZED
    }
    // 切换到「始终最小化」立即缩成圆钮；切走则恢复完整底栏
    LaunchedEffect(alwaysMin) { barMinimized = alwaysMin }
    // 始终最小化：展开后（点圆钮输入网址 / 滑动展开）在不输入、没有弹层时，约 4 秒无导航变化就自动缩回
    LaunchedEffect(alwaysMin, barMinimized, editing, sheet, tab.url, tab.loading) {
        if (alwaysMin && !barMinimized && !editing && sheet == Sheet.None) {
            delay(4_000)
            barMinimized = true
        }
    }
    // URL 发生变化时先清掉上一页标题，避免新页面加载期间地址栏继续显示旧标题。
    // WebChromeClient.onReceivedTitle 更新 tab.title 后，下面的同步逻辑会立即显示新标题。
    var addressTitleUrl by remember(tab.id) { mutableStateOf(tab.url) }
    LaunchedEffect(tab.id, tab.url) {
        if (addressTitleUrl != tab.url) {
            addressTitleUrl = tab.url
            // 真正导航加载时旧标题会残留；同文档 history.pushState 则保留当前标题。
            tab.title = if (tab.loading) "" else tab.currentPageTitle()
        }
    }

    // 非编辑态的地址栏文字直接由当前标签状态推导（不经过 LaunchedEffect，避免滞后一帧或漏更新）；
    // 编辑态才使用用户正在输入的 address。
    val displayAddress = if (editing) {
        address
    } else {
        when (settings.addressDisplay) {
            0 -> if (tab.url.isBlank()) "" else tab.title.ifBlank { tab.url }
            1 -> if (tab.url.isBlank()) "" else hostOf(tab.url)
            else -> tab.url
        }
    }
    // 用户是否真的改过输入框里的文字
    val addressDirtyState = remember(tab.id) { mutableStateOf(false) }
    var addressDirty by addressDirtyState
    LaunchedEffect(editing) { if (!editing) addressDirty = false }

    // 页面导航导致 URL 变化时：若用户没有在输入，退出编辑态让地址栏跟随网页
    LaunchedEffect(tab.id, tab.url) {
        if (editing && !addressDirty) {
            editing = false
            focus.clearFocus()
        }
    }

    // 键盘收起（返回键 / 手势）后退出编辑态，否则地址栏会一直停在旧内容上
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottom > 0) {
        val visible = imeBottom > 0
        if (imeWasVisible && !visible && editing) {
            editing = false
            focus.clearFocus()
        }
        imeWasVisible = visible
    }

    // 回到主页时隐藏 WebView：alpha=0 仍会吃触摸，必须 INVISIBLE + 禁用，
    // 否则主页看起来是首页，点击却落到下层网页。
    LaunchedEffect(tab.id, tab.url) {
        val onHome = tab.url.isEmpty()
        if (!onHome) webViewHidden = false
        tab.contentView.apply {
            alpha = if (onHome) 0f else 1f
            visibility = if (onHome) {
                android.view.View.INVISIBLE
            } else {
                android.view.View.VISIBLE
            }
            isEnabled = !onHome
            isClickable = !onHome
            isFocusable = !onHome
            isFocusableInTouchMode = !onHome
        }
        // WebView 已经隐藏，主页现在才允许采集
        if (onHome) webViewHidden = true
    }

    // 文件选择 / 拍照 / 扫码 / 权限申请的启动器（BrowserLaunchers.kt）
    val launchers = ui.rememberBrowserLaunchers(settings, context, state, clipboard)

    ui.BindTabCallbacks(
        settings = settings,
        context = context,
        tab = tab,
        launchers = launchers,
        scope = scope,
        haptic = haptic,
        extensions = extensions,
        history = history,
    )

    ui.BarAutoHideEffects(tab, barFixed)

    val statusBarHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    val animatedTopPadding by animateDpAsState(
        targetValue = if (barHidden) 0.dp else statusBarHeight,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "statusInset",
    )
    // 弹簧会过冲到负值，padding 不允许负数
    // Gecko：留白随底栏收起而变化会让 GeckoView 在滑动途中逐帧重新布局 / 调整视口（掉帧主因），
    // 默认固定为状态栏高度；「Gecko 调试 → 状态栏留白随滚动收起」打开后才恢复动画。
    val fixedContentTop = tab.isGecko && !GeckoDebug.nativeImmersive
    // 只在布局阶段读取动画值（见 TopInset.kt）：滚动收起 / 回落的每一帧不再触发整个 BrowserScreen 重组
    val contentTopPx: androidx.compose.ui.unit.Density.() -> Int = {
        if (fixedContentTop) statusBarHeight.roundToPx() else animatedTopPadding.coerceAtLeast(0.dp).roundToPx()
    }
    // Gecko 原生沉浸（仅网页状态；主页仍按普通留白排版）：GeckoView 占满内容区、尺寸恒定，
    // 状态栏留白用 offset 平移出来（只改位置、不触发 Gecko 重新布局），并把被推出屏幕的高度告诉 Gecko。
    val nativeInset = tab.isGecko && GeckoDebug.nativeImmersive && tab.url.isNotEmpty()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarPx = with(density) { statusBarHeight.roundToPx() }
    LaunchedEffect(tab.id, nativeInset, statusBarPx) {
        if (!nativeInset) {
            tab.geckoApplyImmersive(0, 0)
        } else {
            androidx.compose.runtime.snapshotFlow {
                with(density) { animatedTopPadding.coerceAtLeast(0.dp).roundToPx() }
            }.collect { tab.geckoApplyImmersive(statusBarPx, it) }
        }
    }

    val hide by animateFloatAsState(
        targetValue = if (barHidden && sheet == Sheet.None) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "bottomBar",
    )
    // 弹簧动画每帧都会改 hide；直接在组合里比较 hide < 0.995f 会让整个 BrowserScreen 逐帧重组。
    // 包进 derivedStateOf 后只有「显示 / 不显示」这个结果翻转时才重组，位移 / 透明度仍在绘制阶段读取。
    val barVisible by remember { androidx.compose.runtime.derivedStateOf { !barHidden || hide < 0.995f } }

    // 二级页面的返回（含预见性返回）由 NavDisplay 接管；这里只处理浏览主界面自己的返回。
    BackHandler(enabled = backStack.size <= 1 && !settings.onboardingPending && !onboardingReplay) {
        when {
            // 视频全屏优先退出
            customView != null -> {
                tab.exitCustomView()
                customView = null
            }
            fileChooserChoice != null -> {
                fileChooserChoice?.callback?.onReceiveValue(null)
                fileChooserChoice = null
            }
            showFind -> {
                tab.clearFind()
                showFind = false
            }
            showBookmarkCreate -> showBookmarkCreate = false
            showFavEditor -> showFavEditor = false
            showGroupPopup -> showGroupPopup = false
            showGroupPicker -> showGroupPicker = false
            showQuickPicker -> showQuickPicker = false
            sheet != Sheet.None -> sheet = Sheet.None
            // 阅读模式：返回先退出阅读模式
            tab.readerActive -> tab.exitReader()
            tab.canGoBack -> tab.goBack()
            state.tabs.size > 1 -> state.closeTab(state.activeIndex)
            else -> (context as? Activity)?.finish()
        }
    }

    // 二级页面（设置 / 关于 / 下载 / 书签历史等）共用的依赖，页面内容在 BrowserPages.kt
    val nav = BrowserNav(
        settings = settings,
        state = state,
        favorites = favorites,
        history = history,
        extensions = extensions,
        pushPage = ::pushPage,
        popPage = ::popPage,
        popToBrowser = ::popToBrowser,
        openInBrowser = ::openInBrowser,
        onOpenGuide = {
            // 先退回浏览主界面（引导只在 backStack 只剩主界面时摆放），再从第一步开始
            popToBrowser()
            onboardingStep = 0
            onboardingReplay = true
        },
    )
    Box(modifier = Modifier.fillMaxSize()) {
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = { popPage() },
        effects = NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
            backdropColor = MiuixTheme.colorScheme.surface,
        ),
    ) {
    entry<AppRoute.Browser> {
    CompositionLocalProvider(
        LocalLiquidBackdrop provides if (glassAvailable && settings.blurEnabled) liquidBackdrop else null,
        // 壁纸模糊开启且模糊图已读出：首页取样源已是预模糊图，卡片不再叠加实时模糊
        LocalWallpaperPreBlurred provides (settings.wallpaperBlur && blurredWallpaper != null && wallpaperBitmap != null),
        LocalWallpaperSource provides wallpaperSource,
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 弹层动画期间页面本身没有任何变化，但整窗每帧都会重绘；把页面（含底栏、收藏卡片、
            // 顶部模糊带等所有玻璃取样方）冻结成一张离屏纹理，每帧只做一次贴图合成，
            // 不再重复执行这些模糊着色器。仅主页（无 WebView）启用，避免 WebView 进离屏层。
            .graphicsLayer {
                compositingStrategy = if (homeBlurAvailable && sheetHostActive) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
            }
            .background(MiuixTheme.colorScheme.surface),
    ) {
        // 采集网页 / 首页内容，供外部的 MIUIX 玻璃底栏取样。底栏本身不能放进采集层。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // MIUIX backdrop 在主页采集 Compose 内容；WebView 内核的网页态也采集（WebView 已显式开启硬件层，
                // 不会再与 RuntimeShader 组合闪烁 / 崩溃）。Gecko 内核（SurfaceView）不采集，继续用假玻璃。
                .then(
                    if (glassAvailable) {
                        Modifier.layerBackdrop(browserBackdrop).then(
                            // Kyant 整页层只有液态玻璃模式才有人读（miuix 模式下 activeLiquidBackdrop / 各按钮都忽略它），
                            // 不是该模式就不录制，省掉每帧对整个首页的第二份录制
                            if (settings.liquidGlass) Modifier.kyantLayerBackdrop(liquidBackdrop) else Modifier,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {

        // ---- 首页壁纸：铺满全屏，盖住状态栏 ----
        BrowserWallpaperLayer(
            tab = tab,
            wallpaperBitmap = wallpaperBitmap,
            wallpaperFromCache = wallpaperFromCache,
            blurredWallpaper = blurredWallpaper,
            wallpaperLayout = wallpaperLayout,
            wallpaperBackdrop = wallpaperBackdrop,
            liquidRecord = if (settings.liquidGlass && settings.blurEnabled) Modifier.kyantLayerBackdrop(wallpaperLiquidBackdrop) else Modifier,
        )

        // ---- 网页内容：从状态栏下面开始，不再压到状态栏 ----
        ui.BrowserContentLayer(
            settings = settings,
            state = state,
            tab = tab,
            context = context,
            favorites = favorites,
            history = history,
            homeFavEntries = homeFavEntries,
            homeGroups = homeGroups,
            wallpaperBitmap = wallpaperBitmap,
            wallpaperBackdrop = wallpaperBackdrop,
            wallpaperLiquidBackdrop = wallpaperLiquidBackdrop,
            startScroll = startScroll,
            barOnTop = barOnTop,
            quickRunner = quickRunner,
            nativeInset = nativeInset,
            contentTopPx = contentTopPx,
            animatedTopPadding = { animatedTopPadding },
            pushPage = ::pushPage,
        )
        } // backdrop capture layer ends before the floating toolbar

        // ---- 新建标签页：顶部渐进模糊（与子页面顶栏同款 miuix 渐进模糊）----
        // 放在采集层之外，只采样首页内容；仅在内容滚到状态栏下方时淡入。
        if (homeBlurAvailable && settings.blurEnabled &&
            top.yukonga.miuix.kmp.material.isRuntimeShaderSupported()
        ) {
            val startFadePx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
            Box(modifier = Modifier.fillMaxSize()) {
                TopProgressiveBlurBand(
                    backdrop = browserBackdrop,
                    alpha = { startScroll.value / startFadePx },
                    modifier = Modifier.matchParentSize(),
                    barHeight = 8.dp,
                    // 有壁纸时不叠主题色，避免白雾盖住壁纸；无壁纸时跟随主题 surface
                    tint = if (wallpaperBitmap != null) Color.Unspecified
                    else MiuixTheme.colorScheme.surface.copy(alpha = 0.3f),
                )
            }
        }

        // ---- 地址栏输入中：底栏以外的区域是「收键盘」热区 ----
        // 透明遮罩盖在网页之上、底栏之下：点击只退出编辑并收起键盘，不会落到网页上
        // （避免误触链接 / 按钮）；同时吞掉拖动，输入时不让网页跟着滚动。
        BrowserFloatingBar(
            ui = ui,
            settings = settings,
            state = state,
            tab = tab,
            context = context,
            clipboard = clipboard,
            focus = focus,
            haptic = haptic,
            barOnTop = barOnTop,
            alwaysMin = alwaysMin,
            barFixed = barFixed,
            displayAddress = displayAddress,
            glassAvailable = glassAvailable,
            glassBackdrop = glassBackdrop,
            barLiquidBackdrop = barLiquidBackdrop,
            barMinimizedState = barMinimizedState,
            addressDirtyState = addressDirtyState,
            hide = { hide },
            barVisible = { barVisible },
            quickRunner = quickRunner,
            pushPage = ::pushPage,
        )

        // 隐藏状态不放置透明热区：底部网页区域完全交给 WebView 处理点击、长按和滚动。
        // 通过 WebView 的 scrollY 方向判断下拉恢复，不通过点击底部区域恢复。

    // 菜单功能的执行：底部「更多」菜单和首页「快捷菜单」共用（实现在 BrowserMenuActions.kt）
    fun runMenuAction(action: MenuAction) {
        ui.performMenuAction(action, state, favorites, context, scope, clipboard, launchers, ::pushPage)
    }
    quickRunner.run = { runMenuAction(it) }

    // 脚本总开关 / 启用范围 / 脚本菜单与地址栏脚本图标的状态同步（BrowserMenuActions.kt）
    ui.ScriptStateEffects(settings, state, context)

    // 标签页列表：打开时自动滚到当前标签并高亮（逻辑在 BrowserTabsSheet.kt）
    val tabsListState = rememberLazyListState()
    TabsAutoScroll(
        sheetOpen = sheet == Sheet.Tabs,
        tabsPage = tabsPage,
        state = state,
        tabsStyle = settings.tabsStyle,
        listState = tabsListState,
    )

    // ---- 底部弹层（BrowserSheetHost.kt）----
    ui.BrowserSheetHost(
        settings = settings,
        context = context,
        state = state,
        menuOrder = menuOrder,
        liquidBackdrop = liquidBackdrop,
        glassAvailable = glassAvailable,
        glassBackdrop = glassBackdrop,
        clipboard = clipboard,
        tab = tab,
        tabsListState = tabsListState,
        pushPage = ::pushPage,
        runMenuAction = ::runMenuAction,
    )

    // ---- 对话框 / 弹窗（BrowserDialogHost.kt）----
    ui.BrowserDialogHost(
        settings = settings,
        context = context,
        state = state,
        favorites = favorites,
        homeGroups = homeGroups,
        scope = scope,
        liquidBackdrop = liquidBackdrop,
        extensions = extensions,
        defaultBrowserLauncher = defaultBrowserLauncher,
        tab = tab,
        glassAvailable = glassAvailable,
        glassBackdrop = glassBackdrop,
        pushPage = ::pushPage,
        openInBrowser = ::openInBrowser,
        launchers = launchers,
        box = this@Box,
    )
    }
    // 引导进行中进入二级页面（备份 / Firefox 同步）：浏览主页被推到下面，转场里不能露出首页，用纯色盖住
    if (settings.onboardingPending || onboardingReplay) {
        Box(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
    }
    }
    }
    }
    entry<AppRoute.Settings> { nav.SubPage(AppRoute.Settings) }
    entry<AppRoute.About> { nav.SubPage(AppRoute.About) }
    entry<AppRoute.PrivacyPolicyPage> { nav.SubPage(AppRoute.PrivacyPolicyPage) }
    entry<AppRoute.Downloads> { nav.SubPage(AppRoute.Downloads) }
    entry<AppRoute.AdBlockPage> { nav.SubPage(AppRoute.AdBlockPage) }
    entry<AppRoute.CookiePage> { nav.SubPage(AppRoute.CookiePage) }
    entry<AppRoute.PasswordPage> { nav.SubPage(AppRoute.PasswordPage) }
    entry<AppRoute.FxaPage> { nav.SubPage(AppRoute.FxaPage) }
    entry<AppRoute.BackupPage> { nav.SubPage(AppRoute.BackupPage) }
    entry<AppRoute.PrivacyEnhancePage> { nav.SubPage(AppRoute.PrivacyEnhancePage) }
    entry<AppRoute.Extensions> { nav.SubPage(AppRoute.Extensions) }
    entry<AppRoute.GeckoProtectionPage> { nav.SubPage(AppRoute.GeckoProtectionPage) }
    entry<AppRoute.WebViewDebugPage> { nav.SubPage(AppRoute.WebViewDebugPage) }
    entry<AppRoute.GeckoDebugPage> { nav.SubPage(AppRoute.GeckoDebugPage) }
    entry<AppRoute.GeckoExtensions> { nav.SubPage(AppRoute.GeckoExtensions) }
    entry<AppRoute.Library> { route -> nav.SubPage(route) }
    }
    // 用户引导：盖在最上层。进入二级页面（备份 / Firefox 同步）时只是「不摆放」（visible = false），并不撤出组合：
    // 当前步骤、滚动位置和背景动画都留着，返回时第一帧就是完整的引导页，不会再闪出下面的浏览器首页
    if (settings.onboardingPending || onboardingReplay) {
        OnboardingScreen(
            visible = backStack.size <= 1,
            settings = settings,
            menuOrder = menuOrder,
            step = onboardingStep,
            onStepChange = { onboardingStep = it },
            onOpenBackup = { pushPage(AppRoute.BackupPage) },
            onOpenFxa = { pushPage(AppRoute.FxaPage) },
            onFinish = {
                if (settings.onboardingPending) settings.markOnboardingDone()
                onboardingReplay = false
                onboardingStep = 0
            },
        )
    }
    }
}
