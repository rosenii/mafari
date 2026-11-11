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

internal enum class Sheet { None, Tabs, Menu, Link, Image, Element }

internal class WebPermPrompt(val origin: String, val what: String, val decide: (Boolean) -> Unit)

internal data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val fileName: String,
    val mime: String?,
    /** blob / data URL 已抽成 data URI，确认后直接落盘，不再走 HTTP 下载器 */
    val dataUri: String? = null,
    /** 大 blob 分片写入的缓存临时文件路径，确认后拷到 Download/mafari */
    val tempPath: String? = null,
    /** GM_download 要结果：取消 / 被顶替 → (false, ...)；确认后交给 Downloader 在终态回调 */
    val onResult: ((ok: Boolean, error: String?) -> Unit)? = null,
)

/** 网页 <input type=file capture>：文件 / 拍照 / 录像 选择 */
internal class FileChooserChoice(
    val params: WebChromeClient.FileChooserParams,
    val callback: ValueCallback<Array<Uri>>,
)


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
        BrowserState(context).also {
            // 启动时把设置里保存的 UA 应用到所有标签（之前只有在设置里点确定才会应用，重启后一律回到系统默认 UA）
            it.applyUserAgent(settings.userAgent)
        }
    }
    val favorites = remember { FavoritesStore(context) }
    // 首页收藏栏：只取「个人收藏」分组（读取 items 让列表变化时自动重组）
    val homeFavEntries = favorites.homeEntries()
    remember { Diag.log("BrowserScreen composed, favorites=" + favorites.items.size) }
    val menuOrder = remember { MenuOrderStore(context) }
    // Gecko 拓展定期检查更新：应用在前台、Gecko 模式下，启动后稍等一会儿检查一次，之后每 6 小时看一次是否到点
    LaunchedEffect(settings.geckoMode) {
        if (!settings.geckoMode) return@LaunchedEffect
        kotlinx.coroutines.delay(15_000)
        while (true) {
            runCatching { GeckoExtensionStore.maybeAutoUpdate(context) }
            kotlinx.coroutines.delay(6 * 3_600_000L)
        }
    }
    val wallpaperBitmap = rememberWallpaper(settings.wallpaper)
    // 模糊壁纸：读/写磁盘缓存里已模糊好的小图，不再实时渲染模糊
    val blurredWallpaper = rememberBlurredWallpaper(settings.wallpaper, settings.wallpaperBlur, settings.wallpaperVersion)
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
    val extensions = remember { ExtensionStore(context) }
    // 旧版本放在脚本列表里的去广告订阅 → 迁移到「去广告」页；之后按设置的间隔在后台更新订阅
    remember {
        AdBlock.init(context)
        AdBlock.migrateLegacy(extensions.legacyAdSubs.toList())
        extensions.commitLegacyMigration()
        true
    }
    LaunchedEffect(Unit) { AdBlock.autoUpdateIfDue() }
    // 首次打开 app：自动拉取并安装推荐脚本（每个只自动装一次，失败的下次启动重试）；之后按设置的间隔后台自动更新脚本
    LaunchedEffect(Unit) {
        runCatching { extensions.bootstrapRecommended() }
        runCatching { extensions.autoUpdateIfDue() }
    }
    val history = remember { HistoryStore(context) }
    val tab = state.activeTab
    // 页面加载期间也禁用 MIUIX 采集，避免 URL 尚未更新时误把 WebView 帧送进 RuntimeShader。
    // 回主页时 WebView 要先真正隐藏，再开启 MIUIX 背景采集：
    // 隐藏发生在 LaunchedEffect（下一帧）里，若同一帧就开始采集，
    // 会把还可见的 WebView 录进 layerBackdrop 并送进 RuntimeShader，导致原生崩溃。
    var webViewHidden by remember(tab.id) { mutableStateOf(false) }
    val homeBlurAvailable = tab.url.isEmpty() && !tab.loading && webViewHidden
    // 新建标签页（首页）的滚动状态：每个标签独立，供顶部渐进模糊带跟随滚动淡入
    val startScroll = remember(tab.id) { ScrollState(0) }
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current

    var sheet by remember { mutableStateOf(Sheet.None) }
    var linkUrl by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var elementHit by remember { mutableStateOf<BrowserTab.ElementHit?>(null) }
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var barHidden by remember { mutableStateOf(false) }
    /** 底栏被左右滑动缩小为圆形搜索钮 */
    var barMinimized by remember { mutableStateOf(false) }
    var favEditing by remember { mutableStateOf(false) }
    var showFavEditor by remember { mutableStateOf(false) }
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

    var pendingScript by remember { mutableStateOf<String?>(null) }
    var scriptSheetShow by remember { mutableStateOf(false) }
    var geckoExtSheetShow by remember { mutableStateOf(false) }
    var scriptMenuItems by remember { mutableStateOf<List<ScriptMenuCommand>>(emptyList()) }
    var runningScripts by remember { mutableStateOf<List<RunningScript>>(emptyList()) }
    // 菜单面板右上角蓝标：当前页面运行的脚本数
    var scriptRunCount by remember { mutableIntStateOf(0) }
    var pendingDownload by remember { mutableStateOf<DownloadRequest?>(null) }
    var showFind by remember { mutableStateOf(false) }
    var editingFavIndex by remember { mutableStateOf(-1) }
    // 离场动画期间保留最近一次打开的弹层内容
    var lastActiveSheet by remember { mutableStateOf(Sheet.None) }
    // 弹层从打开到离场动画结束的整段时间为 true：期间把（主页）整页冻结成一张纹理
    var sheetHostActive by remember { mutableStateOf(false) }
    var showClearTabsConfirm by remember { mutableStateOf(false) }
    // 标签列表当前栏：0 = 标签页，1 = 无痕浏览页
    var tabsPage by remember { mutableIntStateOf(0) }
    // 文件选择 + 视频全屏 + 网页权限询问
    var fileChooserChoice by remember { mutableStateOf<FileChooserChoice?>(null) }
    var customView by remember { mutableStateOf<android.view.View?>(null) }
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var webPermPrompt by remember { mutableStateOf<WebPermPrompt?>(null) }
    var androidPermDone by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 启动自动检查更新：每个进程只查一次；有新版本且未被忽略时弹底部提示
    var updateRelease by remember { mutableStateOf<AppRelease?>(null) }
    var lastUpdateRelease by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) {
        if (UpdateChecker.checkedThisProcess) return@LaunchedEffect
        UpdateChecker.checkedThisProcess = true
        // 稍等首屏稳定再请求，避免和启动渲染抢资源
        kotlinx.coroutines.delay(1500)
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            UpdateChecker.findPending(context, settings.ignoredUpdate, settings.updateChannel)
        }
        if (found != null) {
            lastUpdateRelease = found
            updateRelease = found
        }
    }

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
        barMinimized = false
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
    var addressDirty by remember(tab.id) { mutableStateOf(false) }
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
    DisposableEffect(tab.id) {
        onDispose {
            tab.onLongPressLink = null
            tab.onShowFileChooser = { _, callback -> callback.onReceiveValue(null) }
            tab.onShowCustomView = null
            tab.onHideCustomView = null
            tab.onPermissionPrompt = null
            tab.requestAndroidPermissions = null
            tab.onBlobReady = null
            tab.onBlobError = null
            tab.onBlobStart = null
            fileChooserChoice = null
            customView = null
            // 未决的权限请求按拒绝收尾，否则 WebView 回调 / 重定向待决标记会一直悬空
            webPermPrompt?.decide?.invoke(false)
            webPermPrompt = null
        }
    }

    // Android 运行时权限申请结果
    val androidPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val done = androidPermDone
        androidPermDone = null
        done?.invoke()
    }

    // 系统文件选择器（含多选）
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val choice = fileChooserChoice
        fileChooserChoice = null
        val cb = choice?.callback ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            val uris = when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            cb.onReceiveValue(uris)
        } else {
            cb.onReceiveValue(null)
        }
    }

    // 拍照 / 录像
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val choice = fileChooserChoice
        fileChooserChoice = null
        val cb = choice?.callback
        val out = cameraOutputUri
        cameraOutputUri = null
        if (result.resultCode == Activity.RESULT_OK && out != null) {
            cb?.onReceiveValue(arrayOf(out))
        } else {
            out?.let { uri ->
                runCatching {
                    context.contentResolver.update(
                        uri,
                        android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        },
                        null,
                        null,
                    )
                }
            }
            cb?.onReceiveValue(null)
        }
    }

    /** 拉起系统文件选择器（按 acceptTypes / 多选） */
    fun launchFilePicker(choice: FileChooserChoice) {
        val params = choice.params
        // showSaveFilePicker()（File System Access API，WebView 132+）：新建文件并把 Uri 交还给网页，
        // 网页用 createWritable() 边下边写，不经 blob、不占内存；文件名用页面给的原名，不改名
        if (params.mode == WebChromeClient.FileChooserParams.MODE_SAVE) {
            val hint = params.filenameHint?.takeIf { it.isNotBlank() }
            val ext = hint?.substringAfterLast('.', "")?.lowercase().orEmpty()
            // MIME 按原文件名的扩展名取，避免系统因类型不符在文件名后再追加扩展名
            val mimeByName = ext.takeIf { it.isNotEmpty() }
                ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeByName ?: "application/octet-stream"
                if (hint != null) putExtra(Intent.EXTRA_TITLE, hint)
            }
            runCatching { filePickerLauncher.launch(saveIntent) }.onFailure {
                Diag.log("save picker launch failed: ${it}")
                choice.callback.onReceiveValue(null)
                fileChooserChoice = null
            }
            return
        }
        val types = params.acceptTypes
            ?.mapNotNull { it?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .orEmpty()
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                types.isEmpty() -> "*/*"
                types.size == 1 && types[0].contains("/") -> types[0]
                else -> "*/*"
            }
            if (types.size > 1 || (types.size == 1 && !types[0].contains("/"))) {
                val mimes = types.map { t ->
                    if (t.startsWith(".")) {
                        when (t.lowercase()) {
                            ".jpg", ".jpeg" -> "image/jpeg"
                            ".png" -> "image/png"
                            ".gif" -> "image/gif"
                            ".webp" -> "image/webp"
                            ".mp4" -> "video/mp4"
                            ".webm" -> "video/webm"
                            ".pdf" -> "application/pdf"
                            else -> t.removePrefix(".")
                        }
                    } else t
                }.distinct().toTypedArray()
                if (mimes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            }
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        val canResolve = intent.resolveActivity(context.packageManager) != null
        if (canResolve) {
            filePickerLauncher.launch(intent)
        } else {
            runCatching { filePickerLauncher.launch(Intent.createChooser(intent, "选择文件")) }
                .onFailure {
                    choice.callback.onReceiveValue(null)
                    fileChooserChoice = null
                }
        }
    }

    /** 拍照或录像：MediaStore pending 输出 + 相机 Intent */
    fun launchCamera(choice: FileChooserChoice, video: Boolean) {
        val resolver = context.contentResolver
        val uri = runCatching {
            if (video) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "rec_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mafari")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "photo_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mafari")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
        }.getOrNull()
        if (uri == null) {
            choice.callback.onReceiveValue(null)
            fileChooserChoice = null
            return
        }
        cameraOutputUri = uri
        val intent = if (video) {
            Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, uri) }
        } else {
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, uri) }
        }.apply {
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            val resInfo = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (info in resInfo) {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        val canResolve = intent.resolveActivity(context.packageManager) != null
        if (canResolve) {
            cameraLauncher.launch(intent)
        } else {
            cameraOutputUri = null
            runCatching { resolver.delete(uri, null, null) }
            choice.callback.onReceiveValue(null)
            fileChooserChoice = null
            android.widget.Toast.makeText(context, "未找到相机应用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(tab.id) {
        tab.onPermissionPrompt = { origin, what, decide ->
            // 同一时刻只显示一个弹窗：被顶掉的请求按拒绝处理，避免 WebView 回调悬空
            webPermPrompt?.decide?.invoke(false)
            webPermPrompt = WebPermPrompt(origin, what, decide)
        }
        tab.requestAndroidPermissions = { perms, done ->
            androidPermDone = done
            androidPermLauncher.launch(perms)
        }
    }

    LaunchedEffect(
        tab.id,
        tab.incognito,
        settings.cookieMode,
        settings.sendDnt,
        settings.webrtcDisabled,
        settings.clipboardPermission,
        settings.mediaAutoplay,
        settings.cameraPermission,
        settings.micPermission,
        settings.locationPermission,
        settings.notificationPermission,
        settings.redirectPermission,
        settings.blockTrackingScripts,
        AdBlock.fpCanvas,
        AdBlock.fpWebgl,
        AdBlock.fpAudio,
        AdBlock.fpFonts,
    ) {
        tab.applyPrivacy(
            cookieMode = settings.cookieMode,
            sendDnt = settings.sendDnt,
            webrtcDisabled = settings.webrtcDisabled,
            clipboardMode = settings.clipboardPermission,
            mediaMode = settings.mediaAutoplay,
            cameraMode = settings.cameraPermission,
            micMode = settings.micPermission,
            locationMode = settings.locationPermission,
            notificationMode = settings.notificationPermission,
            redirectMode = settings.redirectPermission,
            blockTrackers = settings.blockTrackingScripts,
            fingerprintProtect = AdBlock.fpCanvas || AdBlock.fpWebgl || AdBlock.fpAudio || AdBlock.fpFonts,
        )
    }

    // 显示模式深浅色 → WebView 背景 + 官方 algorithmic darkening（跟随设置/系统）
    val appDark = isAppDarkTheme()
    LaunchedEffect(tab.id, appDark) {
        tab.applyDisplayTheme(appDark)
    }

    // 代理（ProxyController）：设置变化或进入浏览页时应用到全部 WebView
    LaunchedEffect(
        settings.proxyEnabled,
        settings.proxyType,
        settings.proxyHost,
        settings.proxyPort,
    ) {
        WebViewProxy.applyFrom(settings)
    }

    // WebView 不会把滚动事件抛给 Compose，这里轻量轮询页面滚动位置：
    // 向下滚超过一小段 → 收起底栏；向上滚或回到顶部 → 展开。
    LaunchedEffect(tab.id, extensions.items.toList()) {
        tab.userScripts = extensions.items.toList()
        tab.onVisited = { url, title -> if (!tab.incognito) history.record(url, title) }
    }

    // 文件选择：非 capture 直接拉系统选择器；单一类型 capture 直接开相机；
    // 混合类型 capture 由下方 GlassDialog 让用户选。
    LaunchedEffect(fileChooserChoice) {
        val choice = fileChooserChoice ?: return@LaunchedEffect
        val params = choice.params
        val accepts = params.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
        val wantsImage = accepts.contains("image") || accepts.isBlank()
        val wantsVideo = accepts.contains("video")
        val capture = params.isCaptureEnabled
        when {
            !capture -> launchFilePicker(choice)
            wantsImage && !wantsVideo -> launchCamera(choice, video = false)
            wantsVideo && !wantsImage -> launchCamera(choice, video = true)
            // 混合 + capture：等用户在弹层里选
        }
    }

    LaunchedEffect(tab.id, settings.searchTemplate) {
        tab.searchTemplate = settings.searchTemplate
        tab.onPullRefresh = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            tab.refresh()
        }
        tab.onUserScriptLink = { url -> pendingScript = url }
        tab.onExtensionLink = { link ->
            val store = GeckoExtensionStore
            // 拓展只属于 Gecko：WebView 标签不识别、不安装拓展链接
            if (tab.isGecko && store.beginLinkInstall(link)) {
                android.widget.Toast.makeText(context, "正在安装拓展…", android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    try {
                        store.install(context, link)
                            .onSuccess {
                                android.widget.Toast.makeText(context, "已添加 ${it.name}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                if (it.message != "已取消安装") {
                                    android.widget.Toast.makeText(context, it.message ?: "安装失败", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                    } finally {
                        store.endLinkInstall(link)
                    }
                }
            }
        }
        tab.onDownload = { url, ua, disposition, mime ->
            // blob/data 由 BrowserState 抽取后走 onBlobReady
            if (!url.startsWith("blob:", ignoreCase = true) && !url.startsWith("data:", ignoreCase = true)) {
                pendingDownload = DownloadRequest(
                    url = url,
                    userAgent = ua,
                    fileName = Downloader.fileNameFrom(url, disposition),
                    mime = mime,
                )
            }
        }
        tab.onGmDownload = { url, name, onResult ->
            pendingDownload?.onResult?.invoke(false, "superseded")
            pendingDownload = DownloadRequest(
                url = url,
                userAgent = null,
                fileName = Downloader.fileNameFrom(url, if (name.isBlank()) null else "attachment; filename=\"$name\""),
                mime = null,
                onResult = onResult,
            )
        }
        tab.onBlobStart = {
            android.widget.Toast.makeText(context, "正在读取文件…", android.widget.Toast.LENGTH_SHORT).show()
        }
        tab.onBlobReady = { dataUri, fileName, mime, tempPath ->
            // 上一个还没确认的 blob 被新的顶替：先删它的缓存文件，避免泄漏
            pendingDownload?.tempPath?.let { p -> runCatching { java.io.File(p).delete() } }
            pendingDownload = DownloadRequest(
                url = "",
                userAgent = null,
                fileName = fileName,
                mime = mime,
                dataUri = dataUri,
                tempPath = tempPath,
            )
        }
        tab.onBlobError = { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        tab.onShowFileChooser = { params, callback ->
            fileChooserChoice = FileChooserChoice(params, callback)
        }
        tab.onShowCustomView = { view, _ ->
            customView = view
            // 横屏在 BrowserTab.lockLandscape 已处理；这里确保容器铺满
        }
        tab.onHideCustomView = {
            customView = null
        }
    }

    // Gecko 视频全屏：应用自己的顶栏 / 底栏一并收起，退出全屏后恢复
    LaunchedEffect(tab.id, tab.geckoFullscreen) {
        barHidden = tab.geckoFullscreen
    }

    LaunchedEffect(tab.id) {
        var last = 0
        var lastShow = 0
        var scrollDownAccum = 0f
        var scrollUpAccum = 0f
        while (true) {
            var moved = false
            runCatching {
                if (tab.geckoFullscreen) {
                    barHidden = true
                    return@runCatching
                }
                val offset = tab.scrollOffsetY.coerceAtLeast(0)
                val dy = offset - last
                moved = dy != 0
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
            // 滚动中保持 80ms 采样（累计阈值手感不变）；静止后改为等原生滚动事件唤醒，
            // 1 秒超时只用来兜底（全屏状态切换、导航后偏移被重置等没有滚动事件的情况）
            if (moved || tab.geckoFullscreen) {
                delay(80)
            } else {
                withTimeoutOrNull(1000L) { tab.scrollSignal.receive() }
            }
        }
    }

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
    BackHandler(enabled = backStack.size <= 1) {
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
            showFavEditor -> showFavEditor = false
            sheet != Sheet.None -> sheet = Sheet.None
            tab.canGoBack -> tab.goBack()
            state.tabs.size > 1 -> state.closeTab(state.activeIndex)
            else -> (context as? Activity)?.finish()
        }
    }

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
    CompositionLocalProvider(LocalLiquidBackdrop provides if (homeBlurAvailable && settings.blurEnabled) liquidBackdrop else null) {
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
                // MIUIX backdrop 仅在主页采集 Compose 内容；网页状态不采集 WebView，
                // 避免 AndroidView / SurfaceView 与 RuntimeShader 组合时出现闪烁或崩溃。
                .then(
                    if (homeBlurAvailable) {
                        Modifier.layerBackdrop(browserBackdrop).kyantLayerBackdrop(liquidBackdrop)
                    } else {
                        Modifier
                    },
                ),
        ) {

        // ---- 首页壁纸：铺满全屏，盖住状态栏 ----
        if (tab.url.isEmpty() && wallpaperBitmap != null) {
            val wallpaperImage = wallpaperBitmap
            if (wallpaperImage != null) {
                // 取样层永远是「未模糊」的原图（+压暗）：首页所有玻璃（卡片 / 底栏 / 弹层）都从它取色并各自模糊，
                // 不受「壁纸模糊」开关影响，也不会对已经模糊过的壁纸再采样一次。
                // 液态玻璃 + 壁纸模糊：取样层本身就画模糊后的壁纸（即卡片真正的「下层背景」），卡片取色与所见一致，
                // 此时不再另盖显示层。其它材质维持原行为：取样层始终是未模糊原图。
                val sampleBlurred = settings.wallpaperBlur && settings.liquidGlass
                Box(modifier = Modifier.fillMaxSize().layerBackdrop(wallpaperBackdrop).kyantLayerBackdrop(wallpaperLiquidBackdrop)) {
                    Image(
                        bitmap = if (sampleBlurred && blurredWallpaper != null) blurredWallpaper else wallpaperImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize().then(if (sampleBlurred && blurredWallpaper == null) Modifier.blur(28.dp) else Modifier),
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
                }
                // 「壁纸模糊」只是显示层：不透明地盖在取样层上，不参与采样
                if (settings.wallpaperBlur && !sampleBlurred) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = blurredWallpaper ?: wallpaperImage,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                            modifier = Modifier.fillMaxSize().then(if (blurredWallpaper == null) Modifier.blur(28.dp) else Modifier),
                        )
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
                    }
                }
            }
        }

        // ---- 网页内容：从状态栏下面开始，不再压到状态栏 ----
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .topInsetPx { if (nativeInset) 0 else contentTopPx() },
            ) {
                key(tab.id) {
                    AndroidView(
                        factory = { _ ->
                            // AndroidView.factory 接收的是 Context，而不是 View。
                            // 这里返回已经由 BrowserTab 持有的内容视图（WebView 或 GeckoView）。
                            tab.contentView.apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                // Insets 只在 View 首次附着时请求一次。不要在每次 Compose
                                // factory/update 中重复 requestLayout，避免打断 Gecko 的帧提交。
                                if (isAttachedToWindow) {
                                    androidx.core.view.ViewCompat.requestApplyInsets(this)
                                } else {
                                    val target = this
                                    addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                                        override fun onViewAttachedToWindow(v: android.view.View) {
                                            v.removeOnAttachStateChangeListener(this)
                                            androidx.core.view.ViewCompat.requestApplyInsets(target)
                                        }
                                        override fun onViewDetachedFromWindow(v: android.view.View) = Unit
                                    })
                                }
                            }
                        },
                        update = { view ->
                            // 主页盖在 WebView 上面，但 WebView 仍保留着刚才网页的画面。
                            // 主页开启 MIUIX 背景采集后，这帧会被录进 layerBackdrop 再送进 RuntimeShader，
                            // 点主页按钮时会原生崩溃。主页状态下把 WebView 设为 INVISIBLE（不销毁，
                            // 返回时仍能还原网页），这样采集层里只有 Compose 内容。
                            // 这里读取了 tab.url，主页/网页切换时 update 会重新执行。
                            val wantedVisibility = if (tab.url.isEmpty()) {
                                android.view.View.INVISIBLE
                            } else {
                                android.view.View.VISIBLE
                            }
                            if (view.visibility != wantedVisibility) view.visibility = wantedVisibility
                            // AndroidView 自己负责尺寸/布局同步。这里仅同步可见性，禁止每次
                            // Compose 重组都 post requestApplyInsets + requestLayout。
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (nativeInset) {
                                    Modifier.offset {
                                        IntOffset(0, animatedTopPadding.coerceAtLeast(0.dp).roundToPx())
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .onSizeChanged { size ->
                                // Compose 的尺寸才是当前沉浸式内容区域的真实可用尺寸。
                                // 尺寸有效后让 WebView 重新布局，再通知页面重新计算响应式布局。
                                if (size.width > 0 && size.height > 0) tab.onViewportChanged()
                            },
                    )
                }
                if (tab.url.isEmpty()) {
                    if (wallpaperBitmap == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MiuixTheme.colorScheme.surface),
                        )
                    }
                    StartPage(
                        scrollState = startScroll,
                        wallpaper = wallpaperBitmap,
                        wallpaperBackdrop = wallpaperBackdrop,
                        blurEnabled = settings.blurEnabled,
                        favorites = homeFavEntries.map { it.second },
                        showFavorites = settings.homeShowFavorites,
                        favRows = settings.homeFavRows,
                        onOpenBookmarks = { pushPage(AppRoute.Library(0)) },
                        showPrivacy = settings.homeShowPrivacy,
                        geckoMode = tab.isGecko,
                        showRecent = settings.homeShowRecent && !tab.incognito,
                        showDownloads = settings.homeShowDownloads,
                        liquidBackdrop = if (settings.liquidGlass && wallpaperBitmap != null) wallpaperLiquidBackdrop else null,
                        order = settings.homeOrder,
                        onReorder = { settings.updateHomeOrder(it) },
                        recent = history.items,
                        onOpenDownloads = { pushPage(AppRoute.Downloads) },
                        onHide = { kind ->
                            settings.updateHomeShow(kind, false)
                            android.widget.Toast.makeText(
                                context, "已隐藏，可在 设置 → 布局 → 定制主页 中恢复", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        editing = favEditing,
                        onOpen = { state.openOrReuse(it, tab.incognito, fromStartPage = true) },
                        onToggleEdit = { favEditing = !favEditing },
                        // 首页只显示「个人收藏」分组：回调里的下标是首页列表里的位置，要换算回总列表的下标
                        onRemove = { pos -> homeFavEntries.getOrNull(pos)?.let { favorites.remove(it.first) } },
                        onMove = { from, to ->
                            val a = homeFavEntries.getOrNull(from)?.first
                            val b = homeFavEntries.getOrNull(to)?.first
                            if (a != null && b != null) favorites.move(a, b)
                        },
                        onEdit = { pos ->
                            editingFavIndex = homeFavEntries.getOrNull(pos)?.first ?: -1
                            showFavEditor = editingFavIndex >= 0
                        },
                        onAdd = {
                            editingFavIndex = -1
                            showFavEditor = true
                        },
                    )
                }
            }
            PullSpinnerHost(tab)
            if (tab.loading) {
                ProgressLine(
                    progress = { tab.progress },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .topInsetPx(contentTopPx),
                )
            }
        }
        } // backdrop capture layer ends before the floating toolbar

        // ---- 新建标签页：顶部渐进模糊（与子页面顶栏同款 miuix 渐进模糊）----
        // 放在采集层之外，只采样首页内容；仅在内容滚到状态栏下方时淡入。
        if (homeBlurAvailable && settings.blurEnabled &&
            top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported()
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

        // ---- 底部：地址栏 + 工具栏 ----
        // 根据地址文本长度动态调整宽度，并使用弹簧动画平滑过渡。
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
        ) {
            // 按实际字体宽度计算地址栏所需空间：固定按钮区 + 地址文字 + 地址栏内边距。
            // 不设置人为最小宽度，短文字不会留下空白；只有屏幕可用宽度作为上限。
            val textMeasurer = rememberTextMeasurer()
            val addressMeasureText = displayAddress.ifBlank { "搜索或输入网址" }
            val measuredAddressPx = textMeasurer.measure(
                text = AnnotatedString(addressMeasureText),
                style = TextStyle(fontSize = 15.sp),
                maxLines = 1,
            ).size.width
            val measuredAddressWidth = with(LocalDensity.current) { measuredAddressPx.toDp() }
            val fixedToolbarWidth = 44.dp * 4 + 12.dp + 24.dp
            val loadingIndicatorWidth = if (tab.loading) 15.dp else 0.dp
            val requestedBarWidth = fixedToolbarWidth + measuredAddressWidth + loadingIndicatorWidth
            // 显式比较 Dp，避免当前 Kotlin/Compose 版本对 coerceAtMost 的错误重载推断。
            val targetBarWidth = if (requestedBarWidth < maxWidth) requestedBarWidth else maxWidth
            val animatedBarWidth by animateDpAsState(
                targetValue = targetBarWidth,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                label = "dynamicBottomBarWidth",
            )
            var dragDistance by remember(tab.id) { mutableFloatStateOf(0f) }
            var hDragDistance by remember(tab.id) { mutableFloatStateOf(0f) }

            // 退出动画播放期间仍保留底栏；动画结束后直接从组合树移除，
            // 不留下透明/平移后的 Compose 节点拦截 WebView 的触摸事件。
            if (barVisible) {
                // 缩小后的圆形搜索钮（弹性缩放入场）
                if (barMinimized) {
                    val circleScale by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                        label = "barCircleIn",
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                            .size(48.dp)
                            .pointerInput(tab.id) {
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        hDragDistance += dragAmount.x
                                        // 再次左右滑或上滑可展开
                                        if (kotlin.math.abs(hDragDistance) > 72f || dragAmount.y < -40f) {
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
                                translationY = hide * 210.dp.toPx()
                                alpha = (1f - hide) * circleScale
                                scaleX = circleScale
                                scaleY = circleScale
                                transformOrigin = TransformOrigin(0.5f, 1f)
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
                                    backdrop = homeBackdrop,
                                    corner = 24.dp,
                                    blurEnabled = settings.blurEnabled,
                                    textureBlurEnabled = homeBlurAvailable,
                                    liquid = if (settings.liquidGlass) barLiquidBackdrop else null,
                                ),
                        )
                        Icon(
                            imageVector = MiuixIcons.Glass.Search,
                            contentDescription = "搜索或输入网址",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .width(animatedBarWidth)
                        // 在底栏上向下滑动主动收起；左右滑动缩小为搜索圆钮。
                        .pointerInput(tab.id, barHidden) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) {
                                        hDragDistance += dragAmount.x
                                        if (kotlin.math.abs(hDragDistance) > 72f) {
                                            barMinimized = true
                                            hDragDistance = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            change.consume()
                                        }
                                    } else {
                                        dragDistance += dragAmount.y
                                        // 提高竖向隐藏阈值，降低误触
                                        if (dragDistance > 56f && !barHidden) {
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
                                    dragDistance = 0f
                                    hDragDistance = 0f
                                },
                                onDragCancel = {
                                    dragDistance = 0f
                                    hDragDistance = 0f
                                },
                            )
                        }
                        .graphicsLayer {
                            translationY = hide * 210.dp.toPx()
                            alpha = 1f - hide
                            val s = 1f - 0.08f * hide
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
            BottomBar(
                blurEnabled = settings.blurEnabled,
                liquid = if (settings.liquidGlass) barLiquidBackdrop else null,
                textureBlurEnabled = homeBlurAvailable,
                backdrop = homeBackdrop,
                address = displayAddress,
                loading = tab.loading,
                canGoBack = tab.canGoBack,
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
                    if (tab.url.isNotBlank()) clipboard.setText(AnnotatedString(tab.url))
                },
                onAddressTap = {
                    if (!editing) {
                        editing = true
                        address = tab.url
                    }
                },
                editing = editing,
                onShare = {
                    if (tab.url.isNotBlank()) {
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, tab.url)
                                    },
                                    "分享链接",
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
                onMore = { sheet = Sheet.Menu },
            )
                }
                } // end else (!barMinimized)
            }
        }

        // 隐藏状态不放置透明热区：底部网页区域完全交给 WebView 处理点击、长按和滚动。
        // 通过 WebView 的 scrollY 方向判断下拉恢复，不通过点击底部区域恢复。

    // 脚本快捷菜单：查询当前页面运行的脚本 + 它们注册的快捷命令
    fun refreshScriptSheet() {
        val t = state.activeTab
        t.queryRunningScriptIds { ids ->
            val byId = t.userScripts.associateBy { it.id }
            runningScripts = ids.map { id ->
                val us = byId[id]
                RunningScript(
                    id = id,
                    name = us?.name?.ifBlank { null } ?: "未命名脚本",
                    icon = us?.icon.orEmpty(),
                    iconBase = us?.sourceUrl.orEmpty(),
                    iconFallbackHost = us?.let { UserscriptEngine.fallbackIconHost(UserscriptEngine.parse(it.code)) }.orEmpty(),
                )
            }
        }
        t.queryMenuCommands { scriptMenuItems = it }
    }

    // 脚本总开关 → 所有标签页
    LaunchedEffect(settings.scriptsEnabled, state.tabs.size) {
        state.tabs.forEach { it.scriptsEnabled = settings.scriptsEnabled }
    }

    // 启用范围（无痕）→ 所有标签页
    LaunchedEffect(settings.scriptsInIncognito, settings.adblockInIncognito, state.tabs.size) {
        state.tabs.forEach {
            it.scriptsInIncognito = settings.scriptsInIncognito
            it.adblockInIncognito = settings.adblockInIncognito
        }
    }

    LaunchedEffect(settings.sameSiteNewTab, state.tabs.size) {
        state.tabs.forEach { it.sameSiteNewTab = settings.sameSiteNewTab }
    }

    // Sheet 打开期间，页面（重新）加载完成后刷新脚本列表；停用时保留旧列表（仅置灰）
    // 页面加载完成后先等一下再取，之后每 1.5 秒复查一次（document-idle 的脚本、延迟注册的菜单命令可能比首次查询晚出现）；
    // 脚本被停用时刷新后页面里不会再有运行中的脚本，列表同步清空
    LaunchedEffect(scriptSheetShow, tab.loading, settings.scriptsEnabled) {
        if (!scriptSheetShow) return@LaunchedEffect
        if (!settings.scriptsEnabled) {
            runningScripts = emptyList()
            scriptMenuItems = emptyList()
        } else if (!tab.loading) {
            kotlinx.coroutines.delay(500)
            while (true) {
                refreshScriptSheet()
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    // 打开更多菜单时刷新「当前页面运行的脚本数」
    LaunchedEffect(sheet, state.activeIndex) {
        if (sheet == Sheet.Menu) {
            scriptRunCount = 0
            state.activeTab.queryRunningScripts { scriptRunCount = it }
            // 「拓展」角标：刷新已启用的拓展数
            if (settings.geckoMode) runCatching { GeckoExtensionStore.refresh(context) }
        }
    }

    // 标签页列表：打开时自动滚到当前标签并高亮
    val tabsListState = rememberLazyListState()
    var tabsOpenScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(sheet, tabsPage, state.activeIndex, settings.tabsStyle) {
        if (sheet != Sheet.Tabs) {
            tabsOpenScrolled = false
            return@LaunchedEffect
        }
        val pageIncognito = tabsPage == 1
        val visible = state.tabs.withIndex().filter { it.value.incognito == pageIncognito }
        val pos = visible.indexOfFirst { it.index == state.activeIndex }
        if (pos >= 0) {
            val row = if (settings.tabsStyle == 1) pos / 2 else pos
            if (!tabsOpenScrolled) {
                // 刚打开：弹层还在滑入，直接定位，不在动画期间再跑一段滚动动画
                tabsOpenScrolled = true
                runCatching { tabsListState.scrollToItem(row) }
            } else {
                runCatching { tabsListState.animateScrollToItem(row) }
            }
        }
    }

    // ---- 弹层：GlassBottomSheet（保持组合直到离场动画结束）----
        val activeSheet = sheet
        if (activeSheet != Sheet.None) {
            lastActiveSheet = activeSheet
        }
        LaunchedEffect(activeSheet) {
            if (activeSheet != Sheet.None) sheetHostActive = true
        }
        AppBottomSheet(
            show = activeSheet != Sheet.None,
            title = when (lastActiveSheet) {
                Sheet.Link -> hostOf(linkUrl)
                Sheet.Image -> "图片"
                Sheet.Element -> "页面元素"
                Sheet.Tabs -> null // 标签页自定义头部（渐进模糊 + 添加/删除）
                else -> null
            },
            // 头部同一行：新建 | 标签页/无痕浏览页 切换 | 删除（整行放在 startAction，自己铺满宽度）
            startAction = if (lastActiveSheet == Sheet.Tabs) {
                {
                    val pageCount = state.tabs.count { it.incognito == (tabsPage == 1) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val liquid = if (homeBlurAvailable && settings.blurEnabled) liquidBackdrop else null
                        TabAddButton(
                            canAdd = state.tabs.size < 99,
                            backdrop = liquid,
                            modifier = Modifier.align(Alignment.CenterStart),
                            onClick = {
                                state.newTab(incognito = tabsPage == 1)
                                sheet = Sheet.None
                            },
                        )
                        TabsSegmented(
                            labels = listOf("标签页", "无痕浏览页"),
                            selected = tabsPage,
                            onSelect = { tabsPage = it },
                            modifier = Modifier.align(Alignment.Center),
                        )
                        LiquidGlassButton(
                            onClick = { showClearTabsConfirm = true },
                            backdrop = liquid,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            enabled = pageCount > 0,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Glass.Delete,
                                contentDescription = "全部删除",
                                modifier = Modifier.size(22.dp),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            } else {
                null
            },
            endAction = null,
            // 所有弹层统一采样页面模糊。卡顿的根因不在弹层自己的模糊，而在页面每帧被重复回放，
            // 已由上方的离屏缓存 / 整页冻结解决，所以更多菜单、标签列表可以恢复模糊。
            backdrop = if (homeBlurAvailable && settings.blurEnabled) homeBackdrop else null,
            // 更多菜单 / 标签列表在有模糊采样时不压暗；网页态（无法采样 WebView）没有模糊，必须压暗以区分层次
            enableWindowDim = (lastActiveSheet != Sheet.Menu && lastActiveSheet != Sheet.Tabs) ||
                !(homeBlurAvailable && settings.blurEnabled),
            onDismissRequest = { sheet = Sheet.None },
            onDismissFinished = { if (sheet == Sheet.None) sheetHostActive = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
            ) {
            when (lastActiveSheet) {
            Sheet.Tabs -> {
                val screenH = LocalConfiguration.current.screenHeightDp.dp
                // 只显示当前栏的标签；index 仍是全局下标，选中/关闭时用
                val pageIncognito = tabsPage == 1
                val visibleTabs = state.tabs.withIndex().filter { it.value.incognito == pageIncognito }
                val cardStyle = settings.tabsStyle == 1
                // 懒加载：只组合/绘制可见行（最多 99 个标签时不再一次性全部组合）
                LazyColumn(
                    state = tabsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = screenH * 0.68f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
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
                                        blurBackdrop = if (settings.blurEnabled && homeBlurAvailable) homeBackdrop else null,
                                        modifier = Modifier.weight(1f),
                                        onSelect = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            state.select(indexed.index)
                                            sheet = Sheet.None
                                        },
                                        onClose = { state.closeTab(indexed.index) },
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
                                    if (item.incognito) append("无痕 · ")
                                    append(item.title.ifBlank { "新标签页" })
                                },
                                subtitle = item.url.ifBlank { null },
                                highlight = index == state.activeIndex,
                                trailing = {
                                    BarButton(
                                        icon = MiuixIcons.Glass.Close,
                                        description = "关闭",
                                        enabled = state.tabs.size > 1,
                                        onClick = { state.closeTab(index) },
                                    )
                                },
                                onClick = {
                                    state.select(index)
                                    sheet = Sheet.None
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
                                    text = if (pageIncognito) "没有无痕浏览页" else "没有标签页",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }


            Sheet.Menu -> {
                MenuGrid(
                    labelFor = { action ->
                        if (action == MenuAction.Desktop) {
                            if (tab.desktopMode) "移动版" else "桌面版"
                        } else {
                            action.label
                        }
                    },
                    badgeFor = { action ->
                        when (action) {
                            MenuAction.Downloads ->
                                Downloader.tasks.count { it.state == DownloadState.RUNNING }
                            MenuAction.ScriptMenu -> scriptRunCount
                            MenuAction.GeckoExtensions -> if (settings.geckoMode) {
                                GeckoExtensionStore.items.count {
                                    it.enabled && (!tab.incognito || it.allowedInPrivate)
                                }
                            } else {
                                0
                            }
                            else -> 0
                        }
                    },
                    // 置灰：仅 Gecko 专属功能在 WebView 下不可用（脚本在两种内核下都可用）
                    lockedFor = { action ->
                        when (action) {
                            // 拓展只在 Gecko 内核可用：WebView 下置灰，点击提示不可用
                            MenuAction.GeckoExtensions -> !settings.geckoMode
                            else -> false
                        }
                    },
                    order = menuOrder.order,
                    hidden = menuOrder.hidden,
                    onAction = { action ->
                        sheet = Sheet.None
                        when (action) {
                            MenuAction.NewTab -> state.newTab(incognito = false)
                            MenuAction.Incognito -> state.toggleIncognito()
                            MenuAction.Desktop -> tab.toggleDesktopMode(!tab.desktopMode)
                            MenuAction.Find -> showFind = true
                            MenuAction.Translate -> if (tab.url.isNotBlank()) {
                                state.newTab(
                                    "https://translate.google.com/translate?sl=auto&tl=zh-CN&u=" +
                                        java.net.URLEncoder.encode(tab.url, "UTF-8"),
                                )
                            }
                            MenuAction.Favorite -> if (tab.url.isNotBlank()) {
                                val favName = tab.title.ifBlank { hostOf(tab.url) }
                                val msg = if (favorites.exists(favName, tab.url)) {
                                    "已添加此书签"
                                } else {
                                    favorites.add(favName, tab.url)
                                    "添加书签成功"
                                }
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                            MenuAction.Share -> if (tab.url.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, tab.url)
                                            },
                                            "分享链接",
                                        ),
                                    )
                                }
                            }
                            MenuAction.Refresh -> tab.reload()
                            MenuAction.CopyLink -> if (tab.url.isNotBlank()) {
                                clipboard.setText(AnnotatedString(tab.url))
                            }
                            MenuAction.External -> if (tab.url.isNotBlank()) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url)))
                                }
                            }
                            MenuAction.AddToDesktop -> ShortcutHelper.addToDesktop(context, scope, tab)
                            MenuAction.Home -> state.goHome(tab)
                            MenuAction.Downloads -> pushPage(AppRoute.Downloads)
                            // 单击：拓展快捷菜单；长按（见 onLongPressAction）：进入拓展页
                            MenuAction.GeckoExtensions -> geckoExtSheetShow = true
                            MenuAction.ScriptMenu -> {
                                scriptSheetShow = true
                                refreshScriptSheet()
                            }
                            MenuAction.History -> pushPage(AppRoute.Library(1))
                            MenuAction.Settings -> pushPage(AppRoute.Settings)
                            MenuAction.CloseTab -> state.closeTab(state.activeIndex)
                        }
                    },
                    onMove = { action, toHidden, toIndex -> menuOrder.place(action, toHidden, toIndex) },
                    // 长按 0.65 秒（图标不动）：直接进入管理页；单击保持原有操作
                    longPressActions = setOf(MenuAction.ScriptMenu, MenuAction.Favorite, MenuAction.GeckoExtensions),
                    onLongPressAction = { action ->
                        when (action) {
                            MenuAction.ScriptMenu -> {
                                sheet = Sheet.None
                                pushPage(AppRoute.Extensions)
                            }
                            MenuAction.Favorite -> {
                                sheet = Sheet.None
                                pushPage(AppRoute.Library(0))
                            }
                            MenuAction.GeckoExtensions ->
                                if (!settings.geckoMode) {
                                    showWebViewUnsupportedToast(context)
                                } else {
                                    sheet = Sheet.None
                                    pushPage(AppRoute.GeckoExtensions)
                                }
                            else -> Unit
                        }
                    },
                    incognitoActive = tab.incognito,
                )
            }

            Sheet.Link -> {
                SheetRow(title = "打开链接") {
                    sheet = Sheet.None
                    tab.loadUrl(linkUrl)
                }
                SheetRow(title = "在新标签页打开") {
                    sheet = Sheet.None
                    state.newTab(linkUrl, tab.incognito, fromLink = true)
                }
                SheetRow(title = "复制链接") {
                    sheet = Sheet.None
                    clipboard.setText(AnnotatedString(linkUrl))
                }
                SheetRow(title = "在外部浏览器打开") {
                    sheet = Sheet.None
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))) }
                }
                // 标记广告依赖 WebView 的页面内 JS 取元素：Gecko 下不提供
                if (settings.pageEnhancements && !tab.isGecko) {
                    SheetRow(
                        title = "标记为广告",
                        subtitle = "选中这个广告位，可记住规则下次自动隐藏",
                    ) {
                        sheet = Sheet.None
                        tab.markAdAtTouch {
                            android.widget.Toast.makeText(context, "没找到可标记的元素", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            Sheet.Image -> {
                val inlineImage = imageUrl.startsWith("data:", ignoreCase = true)
                val blobImage = imageUrl.startsWith("blob:", ignoreCase = true)
                SheetRow(
                    title = "保存图片",
                    subtitle = when {
                        inlineImage -> "内嵌图片"
                        blobImage -> "页面内图片"
                        else -> hostOf(imageUrl)
                    },
                ) {
                    sheet = Sheet.None
                    when {
                        inlineImage -> {
                            val name = "image_" + System.currentTimeMillis() + ".png"
                            val ok = Downloader.saveDataUri(context, imageUrl, name)
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "已保存到 Download/mafari" else "保存失败",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        blobImage -> tab.extractBlob(imageUrl, null, null, "image/*")
                        else -> pendingDownload = DownloadRequest(
                            url = imageUrl,
                            userAgent = null,
                            fileName = Downloader.fileNameFrom(imageUrl, null),
                            mime = null,
                        )
                    }
                }
                if (!inlineImage && !blobImage) {
                    SheetRow(title = "复制图片地址") {
                        sheet = Sheet.None
                        clipboard.setText(AnnotatedString(imageUrl))
                    }
                    SheetRow(title = "在新标签页打开") {
                        sheet = Sheet.None
                        state.newTab(imageUrl, tab.incognito, fromLink = true)
                    }
                }
                // 标记广告依赖 WebView 的页面内 JS 取元素：Gecko 下不提供
                if (settings.pageEnhancements && !tab.isGecko) {
                    SheetRow(
                        title = "标记为广告",
                        subtitle = "选中这个广告位，可记住规则下次自动隐藏",
                    ) {
                        sheet = Sheet.None
                        tab.markAdAtTouch {
                            android.widget.Toast.makeText(context, "没找到可标记的元素", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            Sheet.Element -> {
                val hit = elementHit
                if (hit != null) {
                    if (hit.imageUrl.isNotBlank()) {
                        SheetRow(title = "保存图片", subtitle = hostOf(hit.imageUrl)) {
                            sheet = Sheet.None
                            pendingDownload = DownloadRequest(
                                url = hit.imageUrl,
                                userAgent = null,
                                fileName = Downloader.fileNameFrom(hit.imageUrl, null),
                                mime = null,
                            )
                        }
                    }
                    SheetRow(
                        title = "标记为广告并移除",
                        subtitle = "记住这条规则，下次访问该站点自动隐藏",
                    ) {
                        sheet = Sheet.None
                        if (!AdBlock.addUserCosmetic(hostOf(tab.url), hit.selector)) {
                            // 选择器不安全 / 站点名不合法时规则没有保存，不能让人以为下次会自动隐藏
                            android.widget.Toast.makeText(context, "这个元素无法生成规则，本次仅移除", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        tab.evaluateJs(removeElementJs(hit.selector))
                    }
                    SheetRow(title = "仅移除该元素") {
                        sheet = Sheet.None
                        tab.evaluateJs(removeElementJs(hit.selector))
                    }
                }
            }

            Sheet.None -> Unit
            }
            }
        }


        UpdateSheet(
            show = updateRelease != null,
            release = updateRelease ?: lastUpdateRelease,
            backdrop = if (homeBlurAvailable && settings.blurEnabled) homeBackdrop else null,
            onDismiss = { updateRelease = null },
            onIgnore = {
                settings.updateIgnoredUpdate(it.version)
                updateRelease = null
            },
            onDownload = {
                UpdateChecker.download(context, it, openInApp = { openInBrowser(it) })
                updateRelease = null
            },
        )

        val downloadRequest = pendingDownload
        AppGlassDialog(
            show = downloadRequest != null,
            title = "下载文件",
            summary = downloadRequest?.let {
                it.fileName + "\n" +
                    when {
                        it.tempPath != null -> "页面内文件（blob）"
                        it.dataUri != null -> "页面内文件（data）"
                        else -> "保存到 /sdcard/Download/mafari"
                    }
            },
            backdrop = if (homeBlurAvailable) homeBackdrop else null,
            onDismissRequest = {
                pendingDownload?.tempPath?.let { p -> runCatching { java.io.File(p).delete() } }
                pendingDownload?.onResult?.invoke(false, "cancelled")
                pendingDownload = null
            },
        ) {
            if (downloadRequest != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppDialogButton(
                            onClick = {
                                downloadRequest.tempPath?.let { p -> runCatching { java.io.File(p).delete() } }
                                downloadRequest.onResult?.invoke(false, "cancelled")
                                pendingDownload = null
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "取消", style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val req = downloadRequest
                                pendingDownload = null
                                scope.launch {
                                    val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        when {
                                            req.tempPath != null -> {
                                                val f = java.io.File(req.tempPath)
                                                val ok = f.exists() && Downloader.saveFile(
                                                    context,
                                                    f,
                                                    req.fileName,
                                                    req.mime,
                                                )
                                                runCatching { f.delete() }
                                                ok
                                            }
                                            req.dataUri != null ->
                                                Downloader.saveDataUri(context, req.dataUri, req.fileName)
                                            else -> {
                                                Downloader.start(
                                                    context = context,
                                                    url = req.url,
                                                    userAgent = req.userAgent,
                                                    fileName = req.fileName,
                                                    mimeType = req.mime,
                                                    onComplete = req.onResult,
                                                )
                                                true
                                            }
                                        }
                                    }
                                    if (req.tempPath != null || req.dataUri != null) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            android.widget.Toast.makeText(
                                                context,
                                                if (saved) "已保存到 Download/mafari" else "保存失败",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Primary,
                        ) {
                            Text(text = "下载", style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }
        }

        // capture 混合类型：让用户选拍照 / 录像 / 文件
        // 注意：launchCamera / filePickerLauncher 依赖 fileChooserChoice 里的 callback，
        // 弹层里不要先置 null，由 launcher 回调时再清。
        val captureChoice = fileChooserChoice
        AppGlassDialog(
            show = captureChoice != null && captureChoice.params.isCaptureEnabled &&
                run {
                    val a = captureChoice.params.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
                    a.contains("image") && a.contains("video")
                },
            title = "选择来源",
            summary = "网页需要图片或视频",
            backdrop = if (homeBlurAvailable) homeBackdrop else null,
            onDismissRequest = {
                captureChoice?.callback?.onReceiveValue(null)
                fileChooserChoice = null
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppDialogButton(
                    onClick = {
                        if (captureChoice != null) launchCamera(captureChoice, video = false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "拍照", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        if (captureChoice != null) launchCamera(captureChoice, video = true)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "录像", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        if (captureChoice != null) launchFilePicker(captureChoice)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "从文件选择", style = MiuixTheme.textStyles.button)
                }
            }
        }

        // HTML5 视频全屏：沉浸播放，完全隐藏状态栏和底部导航条（边缘上滑可临时唤出），退出全屏时恢复
        val videoFullscreen = customView != null
        DisposableEffect(videoFullscreen) {
            val window = (context as? Activity)?.window
            val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
            var oldCutout = -1
            if (videoFullscreen && window != null && controller != null) {
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    oldCutout = window.attributes.layoutInDisplayCutoutMode
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = attrs
                }
            }
            onDispose {
                if (videoFullscreen && window != null && controller != null) {
                    controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    if (oldCutout >= 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val attrs = window.attributes
                        attrs.layoutInDisplayCutoutMode = oldCutout
                        window.attributes = attrs
                    }
                }
            }
        }

        // HTML5 视频全屏：把 WebView 给的 View 挂到全屏黑底容器
        if (customView != null) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { container ->
                    val cv = customView
                    if (cv != null) {
                        if (cv.parent !== container) {
                            (cv.parent as? android.view.ViewGroup)?.removeView(cv)
                            container.removeAllViews()
                            container.addView(
                                cv,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    } else {
                        container.removeAllViews()
                    }
                },
                onRelease = { container ->
                    container.removeAllViews()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f)
                    .background(Color.Black),
            )
        }

        GeckoExtMenuSheet(
            show = geckoExtSheetShow,
            backdrop = if (homeBlurAvailable) homeBackdrop else null,
            activeSession = state.activeTab.geckoSession,
            onOpenUrl = { url -> state.newTab(url, tab.incognito) },
            onDismiss = { geckoExtSheetShow = false },
        )

        ScriptMenuSheet(
            show = scriptSheetShow,
            backdrop = if (homeBlurAvailable) homeBackdrop else null,
            scripts = runningScripts,
            commands = scriptMenuItems,
            scriptsEnabled = settings.scriptsEnabled,
            onScriptsEnabledChange = { on ->
                settings.updateScriptsEnabled(on)
                state.tabs.forEach { it.scriptsEnabled = on }
                // 旧列表对应的是切换前的页面，先清空，页面重载完成后由上面的刷新逻辑重新填充
                runningScripts = emptyList()
                scriptMenuItems = emptyList()
                // 已注入的脚本无法撤销，刷新页面使开关生效（直接 reload：页面正在加载时 tab.reload() 只会停止加载）
                runCatching { tab.refresh() }
            },
            onRunCommand = { cmd ->
                scriptSheetShow = false
                tab.runMenuCommand(cmd.id)
            },
            onDismiss = { scriptSheetShow = false },
        )

        val permPrompt = webPermPrompt
        AppGlassDialog(
            show = permPrompt != null,
            title = "允许网站" + (permPrompt?.what ?: "") + "？",
            summary = permPrompt?.origin,
            backdrop = if (homeBlurAvailable) homeBackdrop else null,
            onDismissRequest = {
                webPermPrompt = null
                permPrompt?.decide?.invoke(false)
            },
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppDialogButton(
                    onClick = {
                        webPermPrompt = null
                        permPrompt?.decide?.invoke(false)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "拒绝", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        webPermPrompt = null
                        permPrompt?.decide?.invoke(true)
                    },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "允许", style = MiuixTheme.textStyles.button)
                }
            }
        }

        val scriptImportUrl = pendingScript
        AppGlassDialog(
            show = scriptImportUrl != null,
            title = "导入脚本？",
            summary = scriptImportUrl,
            backdrop = if (homeBlurAvailable) homeBackdrop else null,
            onDismissRequest = { pendingScript = null },
        ) {
            if (scriptImportUrl != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppDialogButton(
                            onClick = { pendingScript = null },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "取消", style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val target = scriptImportUrl
                                pendingScript = null
                                scope.launch {
                                    val code = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        fetchRemoteScript(target)
                                    }
                                    if (code != null) {
                                        extensions.add("", code, sourceUrl = target)
                                        pushPage(AppRoute.Extensions)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Primary,
                        ) {
                            Text(text = "导入", style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }
        }

        AppGlassDialog(
            show = showClearTabsConfirm,
            title = if (tabsPage == 1) "删除全部无痕浏览页？" else "删除全部标签页？",
            summary = if (tabsPage == 1) {
                "将关闭所有无痕浏览页，普通标签页不受影响。"
            } else {
                "将关闭所有标签页，无痕浏览页不受影响，并新建一个空白标签。"
            },
            backdrop = if (homeBlurAvailable && settings.blurEnabled) homeBackdrop else null,
            onDismissRequest = { showClearTabsConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(
                    onClick = { showClearTabsConfirm = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        state.closeAllOf(incognito = tabsPage == 1)
                        showClearTabsConfirm = false
                        sheet = Sheet.None
                    },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Destructive,
                ) {
                    Text(text = "全部删除", style = MiuixTheme.textStyles.button)
                }
            }
        }

        FindDialog(
            show = showFind,
            backdrop = if (homeBlurAvailable) homeBackdrop else null,
            onDismiss = {
                tab.clearFind()
                showFind = false
            },
            onQuery = { tab.findAll(it) },
            onPrev = { tab.findNext(false) },
            onNext = { tab.findNext(true) },
        )

        FavoriteEditor(
            show = showFavEditor,
            initial = favorites.items.getOrNull(editingFavIndex),
            backdrop = if (homeBlurAvailable) homeBackdrop else null,
            onDismiss = { showFavEditor = false },
            onConfirm = { name, url ->
                if (editingFavIndex >= 0) {
                    favorites.update(editingFavIndex, name, url)
                } else {
                    favorites.add(name, url)
                }
                showFavEditor = false
            },
        )
    }
    }
    }
    }
    entry<AppRoute.Settings> {
        SettingsScreen(
            settings = settings,
            onBack = { popPage() },
            onApplyUserAgent = { state.applyUserAgent(it) },
            onOpenDownloads = { pushPage(AppRoute.Downloads) },
            onOpenScripts = { pushPage(AppRoute.Extensions) },
            onOpenGeckoExtensions = { pushPage(AppRoute.GeckoExtensions) },
            onOpenGeckoProtection = { pushPage(AppRoute.GeckoProtectionPage) },
            onOpenGeckoDebug = { pushPage(AppRoute.GeckoDebugPage) },
            onOpenAdBlock = { pushPage(AppRoute.AdBlockPage) },
            onOpenCookies = { pushPage(AppRoute.CookiePage) },
            onOpenPrivacyEnhance = { pushPage(AppRoute.PrivacyEnhancePage) },
            onOpenBookmarks = { pushPage(AppRoute.Library(0)) },
            onOpenAbout = { pushPage(AppRoute.About) },
            onPageEnhancementsChange = { extensions.setAllEnabled(it) },
            blurEnabled = settings.blurEnabled,
        )
    }
    entry<AppRoute.About> {
        AboutScreen(
            onBack = { popPage() },
            onOpenUrl = { openInBrowser(it) },
            blurEnabled = settings.blurEnabled,
            updateChannel = settings.updateChannel,
            onUpdateChannelChange = { settings.updateUpdateChannel(it) },
        )
    }
    entry<AppRoute.Downloads> {
        DownloadsScreen(
            onBack = { popPage() },
            blurEnabled = settings.blurEnabled,
            multiThread = settings.downloadMultiThread,
            onMultiThreadChange = { settings.updateDownloadMultiThread(it) },
            threads = settings.downloadThreads,
            onThreadsChange = { settings.updateDownloadThreads(it) },
            multiThreadMinMb = settings.downloadMultiThreadMinMb,
            onMultiThreadMinMbChange = { settings.updateDownloadMultiThreadMinMb(it) },
        )
    }
    entry<AppRoute.AdBlockPage> {
        AdBlockScreen(
            settings = settings,
            onBack = { popPage() },
            blurEnabled = settings.blurEnabled,
        )
    }
    entry<AppRoute.CookiePage> {
        CookieScreen(
            settings = settings,
            currentUrl = tab.url,
            onBack = { popPage() },
            blurEnabled = settings.blurEnabled,
        )
    }
    entry<AppRoute.PrivacyEnhancePage> {
        PrivacyEnhanceScreen(
            settings = settings,
            onBack = { popPage() },
            blurEnabled = settings.blurEnabled,
        )
    }
    entry<AppRoute.Extensions> {
        ExtensionsScreen(
            store = extensions,
            settings = settings,
            onBack = { popPage() },
            blurEnabled = settings.blurEnabled,
        )
    }
    entry<AppRoute.GeckoProtectionPage> {
        GeckoProtectionScreen(
            onBack = { popPage() },
            blurEnabled = settings.blurEnabled,
        )
    }
    entry<AppRoute.GeckoDebugPage> {
        GeckoDebugScreen(
            onBack = { popPage() },
            blurEnabled = settings.blurEnabled,
        )
    }
    entry<AppRoute.GeckoExtensions> {
        GeckoExtensionsScreen(
            onBack = { popPage() },
            // 拓展的「选项」页（moz-extension://…）在新标签里打开
            onOpenUrl = { url ->
                popToBrowser()
                state.newTab(target = url, incognito = false)
            },
            blurEnabled = settings.blurEnabled,
        )
    }
    entry<AppRoute.Library> { route ->
        LibraryScreen(
            favorites = favorites,
            store = history,
            onBack = { popPage() },
            onOpen = { url ->
                popToBrowser()
                state.openOrReuse(url, tab.incognito, navigateIfDeepLink = true)
            },
            initialTab = route.tab,
            blurEnabled = settings.blurEnabled,
        )
    }
    }
}

/* ------------------------------ 玻璃 ------------------------------ */


/** UA 选择：GlassBottomSheet 预设 + 自定义 */
@Composable
internal fun UaDialog(
    show: Boolean,
    initial: String,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var custom by remember(show) { mutableStateOf(initial) }
    AppBottomSheet(
        show = show,
        title = "UA 标识管理",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            UA_PRESETS.forEach { preset ->
                val presetIcon = when (preset.name) {
                    "跟随系统" -> MiuixIcons.Glass.Settings
                    "Android Chrome" -> MiuixIcons.Glass.Phone
                    "桌面 Chrome" -> MiuixIcons.Glass.HorizontalSplit
                    "iPhone Safari" -> MiuixIcons.Glass.Phone
                    else -> MiuixIcons.Glass.Info
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onConfirm(preset.value) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val netIcon = rememberFirstIcon(preset.iconUrls)
                    if (netIcon != null) {
                        Image(
                            bitmap = netIcon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    } else {
                        Icon(
                            imageVector = presetIcon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).padding(1.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = preset.name,
                        fontSize = 15.sp,
                        color = if (preset.value == initial) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (preset.value == initial) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = MiuixIcons.Basic.Check,
                            contentDescription = "已选中",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = custom,
                onValueChange = { custom = it },
                modifier = Modifier.fillMaxWidth(),
                label = "UA 字符串",
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { onConfirm(custom) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "使用", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/** 页内查找：GlassDialog */
@Composable
internal fun FindDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onQuery: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AppGlassDialog(
        show = show,
        title = "查找页内",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    onQuery(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = "查找内容",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onPrev, modifier = Modifier.weight(1f)) {
                    Text(text = "上一个", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text(text = "下一个", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "关闭", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}




/**
 * 首页 Mafari 标志：液态玻璃字。
 * - 有壁纸：字形内部显示字后面的那一块壁纸（轻微放大模拟折射、提亮加饱和），颜色随背景变化；
 * - 无壁纸：退回主题色的半透明玻璃渐变；
 * - 再叠：内侧左上高光 / 右下暗边、对角渐变镜面描边、字下柔和投影。
 * 字形遮罩用 saveLayer + BlendMode（SrcIn / DstOut）实现，不依赖 RuntimeShader。
 */
@Composable
internal fun GlassLogoText(text: String, fontSize: Int, wallpaper: ImageBitmap?) {
    val dark = isAppDarkTheme()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(fontSize) {
        TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Black, color = Color.Black)
    }
    val layout = remember(text, style) { measurer.measure(text, style) }
    val pad = with(density) { 14.dp.toPx() }
    val widthDp = with(density) { (layout.size.width + pad * 2).toDp() }
    val heightDp = with(density) { (layout.size.height + pad * 2).toDp() }
    val windowSize = LocalWindowInfo.current.containerSize
    var origin by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = Modifier
            .size(widthDp, heightDp)
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val textTopLeft = Offset(pad, pad)
        val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
        val d = this.density
        fun mask(offset: Offset = Offset.Zero) {
            drawText(layout, color = Color.Black, topLeft = textTopLeft + offset)
        }

        val gTop = textTopLeft.y
        val gBottom = textTopLeft.y + layout.size.height

        // 1. 字外投影：先画模糊投影，再把字形本身挖掉，只留字外的柔和阴影（玻璃本体是透的，不能让阴影透进字里）
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            val fw = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            fw.maskFilter = android.graphics.BlurMaskFilter(9f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
            fw.color = android.graphics.Color.argb(if (dark) 110 else 62, 0, 0, 0)
            fw.textSize = fontSize.sp.toPx()
            fw.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false)
            val baseline = textTopLeft.y - fw.fontMetrics.ascent
            canvas.nativeCanvas.drawText(text, textTopLeft.x, baseline + 4f * d, fw)
            drawText(layout, color = Color.Black, topLeft = textTopLeft, blendMode = BlendMode.DstOut)
            canvas.restore()
        }

        // 2. 玻璃本体：字形内显示背后的壁纸——放大折射、提亮、加饱和，再叠一层上亮下淡的磨砂白
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            if (wallpaper != null && windowSize.width > 0 && windowSize.height > 0) {
                val bw = wallpaper.width.toFloat()
                val bh = wallpaper.height.toFloat()
                val scale = max(windowSize.width / bw, windowSize.height / bh)
                val x0 = (windowSize.width - bw * scale) / 2f
                val y0 = (windowSize.height - bh * scale) / 2f
                val lens = 0.78f
                val w = size.width / scale * lens
                val h = size.height / scale * lens
                val cx = (origin.x + size.width / 2f - x0) / scale
                val cy = (origin.y + size.height / 2f - y0) / scale
                val srcX = (cx - w / 2f).toInt().coerceIn(0, wallpaper.width - 1)
                val srcY = (cy - h / 2f).toInt().coerceIn(0, wallpaper.height - 1)
                val srcW = w.toInt().coerceIn(1, wallpaper.width - srcX)
                val srcH = h.toInt().coerceIn(1, wallpaper.height - srcY)
                val boost = ColorMatrix().apply {
                    setToSaturation(1.7f)
                    this[0, 4] = 46f
                    this[1, 4] = 46f
                    this[2, 4] = 46f
                }
                drawImage(
                    image = wallpaper,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    colorFilter = ColorFilter.colorMatrix(boost),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    blendMode = BlendMode.SrcIn,
                )
            } else {
                // 无壁纸：用主题色做底
                drawRect(
                    color = if (dark) Color(0xFF5A6578) else Color(0xFFA9B8CE),
                    blendMode = BlendMode.SrcIn,
                )
            }
            // 磨砂白：整体提亮，顶部更亮，像光从上面打进玻璃
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.40f else 0.52f),
                        Color.White.copy(alpha = if (dark) 0.18f else 0.26f),
                        Color.White.copy(alpha = if (dark) 0.28f else 0.36f),
                    ),
                    startY = gTop,
                    endY = gBottom,
                ),
                blendMode = BlendMode.SrcAtop,
            )
            canvas.restore()
        }

        // 3. 内侧下缘暗影（玻璃厚度）：字形减去上移的字形，剩下底部窄带，渐变到透明
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            drawText(layout, color = Color.Black, topLeft = textTopLeft + Offset(-1.5f * d, -4f * d), blendMode = BlendMode.DstOut)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.40f else 0.26f)),
                    startY = gTop,
                    endY = gBottom,
                ),
                blendMode = BlendMode.SrcIn,
            )
            canvas.restore()
        }
        // 4. 内侧上缘高光：字形减去下移的字形，剩下顶部亮带，向下淡出
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            drawText(layout, color = Color.Black, topLeft = textTopLeft + Offset(1.5f * d, 3.5f * d), blendMode = BlendMode.DstOut)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.15f)),
                    startY = gTop,
                    endY = gBottom,
                ),
                blendMode = BlendMode.SrcIn,
            )
            canvas.restore()
        }
        // 5. 左右两侧的内侧边缘光：同样用「字形减去平移字形」得到，只在字形外缘，
        //    不描轮廓线，所以字体自带的重叠笔画（a / f / r）内部不会出现线条
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            drawText(layout, color = Color.Black, topLeft = textTopLeft + Offset(1.4f * d, 0f), blendMode = BlendMode.DstOut)
            drawRect(Color.White.copy(alpha = 0.5f), blendMode = BlendMode.SrcIn)
            canvas.restore()
        }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            drawText(layout, color = Color.Black, topLeft = textTopLeft + Offset(-1.4f * d, 0f), blendMode = BlendMode.DstOut)
            drawRect(Color.White.copy(alpha = 0.3f), blendMode = BlendMode.SrcIn)
            canvas.restore()
        }
    }
}

/** 抓取远程脚本 */
internal fun fetchRemoteScript(url: String): String? = runCatching {
    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "Mozilla/5.0 mafari")
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        connection.inputStream.bufferedReader().readText()
    } finally {
        connection.disconnect()
    }
}.getOrNull()

/** 下拉刷新指示器的宿主：pullProgress 每个触摸事件都在变，把读取隔离在这个小组合里，不让整个 BrowserScreen 跟着重组 */
@Composable
private fun BoxScope.PullSpinnerHost(tab: BrowserTab) {
    val p = tab.pullProgress
    if (p > 0.05f) {
        RefreshSpinner(
            progress = p,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp),
        )
    }
}

/** 顶部下拉刷新指示器：直接复用轻应用（WebAppActivity）的 RefreshSpinnerView，观感与动效完全一致 */
@Composable
internal fun RefreshSpinner(progress: Float, modifier: Modifier = Modifier) {
    val bg = MiuixTheme.colorScheme.surface.copy(alpha = 0.92f).toArgb()
    AndroidView(
        modifier = modifier
            .size(34.dp)
            .shadow(6.dp, CircleShape),
        factory = { ctx ->
            RefreshSpinnerView(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(bg)
                }
            }
        },
        update = { v ->
            (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(bg)
            v.progress = progress
            v.alpha = 0.4f + 0.6f * progress
            val scale = 0.7f + 0.3f * progress
            v.scaleX = scale
            v.scaleY = scale
        },
    )
}

/** 从 content:// 读壁纸 */
@Composable
internal fun rememberBlurredWallpaper(uri: String, enabled: Boolean, version: Int): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, uri, enabled, version) {
        value = if (!enabled || uri.isBlank()) {
            null
        } else {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                WallpaperBlurCache.getOrCreate(context, uri)?.asImageBitmap()
            }
        }
    }
    return image
}

@Composable
internal fun rememberWallpaper(uri: String): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = if (uri.isBlank()) {
            null
        } else {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                        android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
    }
    return image
}
