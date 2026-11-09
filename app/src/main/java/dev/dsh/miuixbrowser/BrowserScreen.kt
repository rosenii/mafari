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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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

private enum class Sheet { None, Tabs, Menu, Link, Image, Element }

private class WebPermPrompt(val origin: String, val what: String, val decide: (Boolean) -> Unit)

private data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val fileName: String,
    val mime: String?,
    /** blob / data URL 已抽成 data URI，确认后直接落盘，不再走 HTTP 下载器 */
    val dataUri: String? = null,
    /** 大 blob 分片写入的缓存临时文件路径，确认后拷到 Download/mafari */
    val tempPath: String? = null,
)

/** 网页 <input type=file capture>：文件 / 拍照 / 录像 选择 */
private class FileChooserChoice(
    val params: WebChromeClient.FileChooserParams,
    val callback: ValueCallback<Array<Uri>>,
)

private fun androidx.compose.ui.geometry.Offset.round(): IntOffset =
    IntOffset(x.toInt(), y.toInt())

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
    // 「拓展」菜单项：切到 Gecko 出现，切回 WebView 收起
    LaunchedEffect(settings.geckoMode) { menuOrder.syncGecko(settings.geckoMode) }
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
            if (store.beginLinkInstall(link)) {
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
            runCatching {
                if (tab.geckoFullscreen) {
                    barHidden = true
                    return@runCatching
                }
                val offset = tab.scrollOffsetY.coerceAtLeast(0)
                val dy = offset - last
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
            delay(80)
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
    val contentTopPadding = animatedTopPadding.coerceAtLeast(0.dp)

    val hide by animateFloatAsState(
        targetValue = if (barHidden && sheet == Sheet.None) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "bottomBar",
    )

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
                    .padding(top = contentTopPadding),
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
                                androidx.core.view.ViewCompat.requestApplyInsets(this)
                                requestLayout()
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
                            // update 在 View 已进入 AndroidView 容器后执行；此时重新请求 Insets
                            // 才能确保 WebView 获得当前 Window 的真实可用区域。
                            view.post {
                                if (view.isAttachedToWindow) {
                                    androidx.core.view.ViewCompat.requestApplyInsets(view)
                                    view.requestLayout()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
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
                        favorites = favorites.items,
                        showFavorites = settings.homeShowFavorites,
                        showPrivacy = settings.homeShowPrivacy,
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
                                context, "已隐藏，可在 设置 → 布局 → 首页内容显示 中恢复", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        editing = favEditing,
                        onOpen = { state.openOrReuse(it, tab.incognito, fromStartPage = true) },
                        onToggleEdit = { favEditing = !favEditing },
                        onRemove = { favorites.remove(it) },
                        onEdit = { index ->
                            editingFavIndex = index
                            showFavEditor = true
                        },
                        onAdd = {
                            editingFavIndex = -1
                            showFavEditor = true
                        },
                    )
                }
            }
            if (tab.pullProgress > 0.05f) {
                RefreshSpinner(
                    progress = tab.pullProgress,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp),
                )
            }
            if (tab.loading) {
                ProgressLine(
                    progress = tab.progress,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = contentTopPadding),
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
            if (!barHidden || hide < 0.995f) {
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
                onHome = { tab.goHome() },
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
                            else -> 0
                        }
                    },
                    // Gecko 下不支持的功能置灰：脚本管理走全局设置（跟随「浏览器内核」），脚本菜单看当前标签
                    lockedFor = { action ->
                        when (action) {
                            MenuAction.Extensions -> settings.geckoMode
                            MenuAction.ScriptMenu -> tab.isGecko || settings.geckoMode
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
                                favorites.add(tab.title.ifBlank { hostOf(tab.url) }, tab.url)
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
                            MenuAction.Home -> tab.goHome()
                            MenuAction.Downloads -> pushPage(AppRoute.Downloads)
                            MenuAction.Extensions -> pushPage(AppRoute.Extensions)
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
                    onLongPressAction = { action ->
                        if (action == MenuAction.GeckoExtensions) {
                            sheet = Sheet.None
                            pushPage(AppRoute.GeckoExtensions)
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
                if (settings.pageEnhancements) {
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
                if (settings.pageEnhancements) {
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

/** MIUIX Glass 底板：主页用 glassPanel 真玻璃；无采样/关模糊时回退到渐变底板。 */
@Composable
private fun Modifier.glassBar(
    backdrop: LayerBackdrop?,
    corner: Dp,
    blurEnabled: Boolean = true,
    textureBlurEnabled: Boolean = true,
    liquid: com.kyant.backdrop.Backdrop? = null,
): Modifier {
    val dark = isAppDarkTheme()
    if (liquid != null && blurEnabled && textureBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val liquidShape = RoundedCornerShape(corner)
        return this.drawBackdrop(
            backdrop = liquid,
            shape = { liquidShape },
            effects = {
                vibrancy()
                backdropBlur(1.5f.dp.toPx())
                lens(18f.dp.toPx(), 40f.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            onDrawSurface = { drawRect(if (dark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.14f)) },
        )
    }
    val glassShape = GlassShape(corner)
    val rounded = RoundedCornerShape(corner)
    val canUseGlass = blurEnabled && textureBlurEnabled && backdrop != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    // 关闭模糊时使用接近不透明的纯色底板；网页态（无采样）提高不透明度，避免内容透出干扰。
    val tint = when {
        !blurEnabled -> Brush.verticalGradient(
            listOf(
                MiuixTheme.colorScheme.surface.copy(alpha = 0.97f),
                MiuixTheme.colorScheme.surface.copy(alpha = 0.97f),
            ),
        )
        dark -> Brush.verticalGradient(listOf(Color(0xF02B2B31), Color(0xE81A1A20)))
        else -> Brush.verticalGradient(listOf(Color(0xF0FFFFFF), Color(0xE8E7EAF0)))
    }

    return this.glassPanel(
        backdrop = if (canUseGlass) backdrop else null,
        shape = glassShape,
        style = GlassDefaults.style(dark),
        material = if (canUseGlass) GlassMaterials.puredThinGlass(dark) else null,
        stroke = if (blurEnabled) GlassStrokes.forTheme(dark) else null,
        shading = false,
        fallback = Modifier
            .clip(rounded)
            .background(tint, rounded)
            .border(
                width = 1.dp,
                color = if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.9f),
                shape = rounded,
            ),
    )
}

@Composable
private fun BottomBar(
    liquid: com.kyant.backdrop.Backdrop? = null,
    blurEnabled: Boolean = true,
    textureBlurEnabled: Boolean = true,
    backdrop: LayerBackdrop,
    address: String,
    loading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onHome: () -> Unit,
    tabCount: Int,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onShare: () -> Unit,
    onTabs: () -> Unit,
    onMore: () -> Unit,
    onCopyAddress: () -> Unit,
    onAddressTap: () -> Unit = {},
    onFocusLost: () -> Unit = {},
    editing: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(29.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        // 空玻璃底板与按钮内容分层，避免 backdrop 采集层递归包含自身。
        Box(
            modifier = Modifier
                .matchParentSize()
                .glassBar(
                    backdrop = backdrop,
                    corner = 29.dp,
                    blurEnabled = blurEnabled,
                    textureBlurEnabled = textureBlurEnabled,
                    liquid = liquid,
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                // 不裁剪内容层：按钮的柔光投影需要向按钮边缘外自然扩散。
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressIcon(icon = MiuixIcons.Glass.Back, description = "后退", enabled = canGoBack, onClick = onBack)
            if (canGoForward) {
                PressIcon(
                    icon = MiuixIcons.Glass.Back,
                    description = "前进",
                    enabled = true,
                    flip = true,
                    onClick = onForward,
                )
            } else {
                PressIcon(
                    icon = MiuixIcons.Home,
                    description = "主页",
                    enabled = true,
                    onClick = onHome,
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAddressTap()
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCopyAddress()
                        },
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    LoadingDot()
                    Spacer(Modifier.width(8.dp))
                }
                AddressField(
                    value = address,
                    onChange = onChange,
                    onGo = onGo,
                    editing = editing,
                    onStartEdit = onAddressTap,
                    onFocusLost = onFocusLost,
                    modifier = Modifier.weight(1f),
                )
            }

            TabIcon(count = tabCount, onClick = onTabs, onLongClick = onMore)
            PressIcon(icon = MiuixIcons.Glass.More, description = "更多", onClick = onMore)
        }
    }
}

/**
 * 图标轮廓重叠柔影：在原始矢量图标下方叠放两层轻微错位、模糊的同形图标。
 * 阴影跟随实际图标路径，不绘制圆形底影或按钮外轮廓。
 */
@Composable
private fun IconWithContourShadow(
    imageVector: ImageVector,
    description: String,
    tint: Color,
    scale: Float,
    flip: Boolean = false,
    boxSize: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val shadowAlpha = if (tint.alpha < 0.5f) 0.12f else 0.28f
    Box(contentAlignment = Alignment.Center) {
        // 暗色投影：向右下轻微错位，模糊后沿图标边缘自然扩散。
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.Black.copy(alpha = shadowAlpha),
            modifier = Modifier
                .size(boxSize)
                .offset(x = 0.8.dp, y = 1.0.dp)
                .blur(2.2.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    if (flip) rotationZ = 180f
                },
        )
        // 反向浅色柔影与暗影交叠，形成玻璃质感的轮廓层次。
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (tint.alpha < 0.5f) 0.10f else 0.24f),
            modifier = Modifier
                .size(boxSize)
                .offset(x = (-0.6).dp, y = (-0.6).dp)
                .blur(1.6.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    if (flip) rotationZ = 180f
                },
        )
        // 清晰图标置顶，避免阴影影响图标辨识度。
        Icon(
            imageVector = imageVector,
            contentDescription = description,
            tint = tint,
            modifier = Modifier
                .size(boxSize - 3.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    if (flip) rotationZ = 180f
                },
        )
    }
}

/** 右上角蓝色数字标（与标签页图标上的数字标同款） */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(15.dp)
            .widthIn(min = 15.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.primary)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
}

@Composable
private fun TabIcon(count: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "tabPress",
    )
    val color = MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(31.dp), contentAlignment = Alignment.Center) {
            IconWithContourShadow(
                imageVector = AppIcons.Tabs,
                description = "标签页",
                tint = color,
                scale = scale,
                boxSize = 30.dp,
            )
            if (count > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .height(13.dp)
                        .widthIn(min = 13.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = count.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun PressIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    flip: Boolean = false,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val color = if (enabled) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    if (enabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconWithContourShadow(
            imageVector = icon,
            description = description,
            tint = color,
            scale = scale,
            flip = flip,
        )
    }
}

/** 加载时地址栏里的小转点 */
@Composable
private fun LoadingDot() {
    val transition = rememberInfiniteTransition(label = "load")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

/* ------------------------------ 小部件 ------------------------------ */

@Composable
private fun AddressField(
    value: String,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    editing: Boolean = true,
    onStartEdit: () -> Unit = {},
    onFocusLost: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val color = MiuixTheme.colorScheme.onSurface
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    // 进入编辑态时主动拿焦点（圆形搜索钮 / 点击地址栏），失焦时通知外部退出编辑态
    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                text = "搜索或输入网址",
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 回主页时地址栏宽度还在弹簧动画里：占位文字必须单行不换行，否则会先折成多行再展开
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = color),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { st ->
                    if (st.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        onFocusLost()
                    }
                },
        )
        if (!editing) {
            // 未编辑时盖一层透明点击区，点一下立刻切到完整网址
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onStartEdit,
                    ),
            )
        }
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    flip: Boolean = false,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val color = if (enabled) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                onClick = {
                    if (enabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = color,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { if (flip) rotationZ = 180f },
        )
    }
}

@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    val color = MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.6.dp, color, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                .height(2.5.dp)
                .background(MiuixTheme.colorScheme.primary),
        )
    }
}

/* ------------------------------ 弹层行 ------------------------------ */

@Composable
private fun SheetRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (highlight) {
                    Modifier.border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 按选择器移除页面元素（长按菜单用） */
private fun removeElementJs(selector: String): String =
    "(function(){try{var el=document.querySelector(" + org.json.JSONObject.quote(selector) +
        ");if(el){el.style.setProperty('display','none','important');el.remove();}}catch(e){}})();"

/* ------------------------------ 主页 ------------------------------ */

/** 首页内容块的拖动排序状态（收藏整体算一个块）。 */
private class HomeReorderState {
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
private fun Modifier.homeReorderItem(kind: Int, st: HomeReorderState): Modifier {
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
private fun Modifier.homeReorderHandle(
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
private fun mergeHomeOrder(full: List<Int>, visible: List<Int>): List<Int> {
    val it = visible.iterator()
    return full.map { k -> if (k in visible && it.hasNext()) it.next() else k }
}

@Composable
private fun StartPage(
    scrollState: ScrollState,
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    favorites: List<Favorite>,
    showFavorites: Boolean,
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
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassLogoText("Mafari", fontSize = 58, wallpaper = wallpaper)
            }
            Spacer(Modifier.height(30.dp))

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
                                wallpaper = wallpaper,
                                wallpaperBackdrop = wallpaperBackdrop,
                                liquidBackdrop = liquidBackdrop,
                                blurEnabled = blurEnabled,
                                editing = editing,
                                guard = guard,
                                onOpen = onOpen,
                                onToggleEdit = onToggleEdit,
                                onRemove = onRemove,
                                onEdit = onEdit,
                                onAdd = onAdd,
                            )
                            HOME_PRIVACY -> PrivacyReportCard(
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
private fun FavoritesBlock(
    modifier: Modifier,
    headerModifier: Modifier,
    favorites: List<Favorite>,
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    editing: Boolean,
    guard: (() -> Unit) -> (() -> Unit),
    onOpen: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onRemove: (Int) -> Unit,
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            favorites.chunked(3).forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEachIndexed { colIndex, favorite ->
                        val index = rowIndex * 3 + colIndex
                        FavoriteTile(
                            favorite = favorite,
                            wallpaper = wallpaper,
                            wallpaperBackdrop = wallpaperBackdrop,
                            liquidBackdrop = liquidBackdrop,
                            blurEnabled = blurEnabled,
                            editing = editing,
                            modifier = Modifier.weight(1f),
                            onClick = guard {
                                if (editing) onEdit(index) else onOpen(favorite.url)
                            },
                            onLongPress = {
                                if (!editing) onToggleEdit()
                            },
                            onRemove = { onRemove(index) },
                        )
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (editing) {
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
private fun Modifier.homeGlass(
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
private fun BoxScope.HomeOutlineOverlay(hasWallpaper: Boolean, radius: Dp) {
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
private fun HomeCard(
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
private fun HomeEmptyText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun HomeRow(primary: String, secondary: String, onClick: () -> Unit) {
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

/** 隐私报告：拦截广告数、处理指纹读取数、频繁获取敏感权限的网站提醒。 */
@Composable
private fun PrivacyReportCard(
    modifier: Modifier,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    hasWallpaper: Boolean,
    showRemove: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    // 拦截计数在后台线程累加、不是 Compose 状态：可见期间低频轮询
    val blocked by produceState(AdBlock.blockedTotal) {
        while (true) {
            value = AdBlock.blockedTotal
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

@Composable
private fun PrivacyStat(value: String, label: String, modifier: Modifier = Modifier) {
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

@Composable
private fun FavoriteTile(
    favorite: Favorite,
    wallpaper: ImageBitmap?,
    wallpaperBackdrop: LayerBackdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    blurEnabled: Boolean,
    editing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .height(58.dp)
            .homeGlass(wallpaperBackdrop, blurEnabled, wallpaper != null, liquidBackdrop)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
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
private fun FavoriteEditor(
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
private fun FindDialog(
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
 * 更多面板：图标 + 文字宫格。长按任一图标进入编辑：面板自动拉长，底部出现高光分割线，
 * 线下方是「隐藏的功能」区；把图标拖到线下方即从菜单隐藏，再拖回线上方即恢复。
 */
@Composable
private fun MenuGrid(
    labelFor: (MenuAction) -> String,
    order: List<MenuAction>,
    hidden: List<MenuAction>,
    onAction: (MenuAction) -> Unit,
    onMove: (MenuAction, Boolean, Int) -> Unit,
    onLongPressAction: (MenuAction) -> Unit = {},
    incognitoActive: Boolean = false,
    badgeFor: (MenuAction) -> Int = { 0 },
    lockedFor: (MenuAction) -> Boolean = { false },
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val menuContext = LocalContext.current
    val cellHeight = 88.dp
    val dividerHeight = 30.dp
    val columns = 4
    val purple = Color(0xFF9C27B0)

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
                            locked = lockedFor(action),
                            cellHeight = cellHeight,
                            tint = if (action == MenuAction.Incognito && incognitoActive) purple
                            else MiuixTheme.colorScheme.onSurface,
                            dragged = dragAction == action,
                            dragOffset = { dragOffset },
                            dimmed = (dragAction != null && dragAction != action) || (inHidden && dragAction == null),
                            onClick = {
                                if (!editing) {
                                    // 置灰项（Gecko 下不支持）：只提示，不执行
                                    if (lockedFor(action)) showGeckoUnsupportedToast(menuContext) else onAction(action)
                                }
                            },
                            onDragStart = {
                                // 长按不进编辑；等 onDrag 出现真实位移再展开隐藏区
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
                                if (editing && dragAction == action) {
                                    finishDrag(action, inHidden, row, col)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (dragAction == null && !editing && pendingDrag.getDistance() < enterEditThresholdPx) {
                                    // 长按后没有真正拖动：当作「长按」触发该项的长按功能
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongPressAction(action)
                                }
                                dragAction = null
                                dragOffset = Offset.Zero
                                pendingDrag = Offset.Zero
                            },
                            onDragCancel = {
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

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
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
private fun MenuCell(
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
                Icon(
                    imageVector = action.icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(if (action == MenuAction.Extensions) 31.dp else 26.dp),
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
private fun TabCard(
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
private fun TabAddButton(
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
private fun TabsSegmented(
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

private val GoogleColors = listOf(
    Color(0xFF4285F4),
    Color(0xFFEA4335),
    Color(0xFFFBBC05),
    Color(0xFF4285F4),
    Color(0xFF34A853),
    Color(0xFFEA4335),
)

/**
 * 首页 Mafari 标志：液态玻璃字。
 * - 有壁纸：字形内部显示字后面的那一块壁纸（轻微放大模拟折射、提亮加饱和），颜色随背景变化；
 * - 无壁纸：退回主题色的半透明玻璃渐变；
 * - 再叠：内侧左上高光 / 右下暗边、对角渐变镜面描边、字下柔和投影。
 * 字形遮罩用 saveLayer + BlendMode（SrcIn / DstOut）实现，不依赖 RuntimeShader。
 */
@Composable
private fun GlassLogoText(text: String, fontSize: Int, wallpaper: ImageBitmap?) {
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
private fun fetchRemoteScript(url: String): String? = runCatching {
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

/** 顶部下拉刷新指示器：直接复用轻应用（WebAppActivity）的 RefreshSpinnerView，观感与动效完全一致 */
@Composable
private fun RefreshSpinner(progress: Float, modifier: Modifier = Modifier) {
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
private fun rememberBlurredWallpaper(uri: String, enabled: Boolean, version: Int): ImageBitmap? {
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
private fun rememberWallpaper(uri: String): ImageBitmap? {
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
