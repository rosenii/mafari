package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.browser.UpdateDownload
import dev.dsh.miuixbrowser.browser.UpdatePhase
import dev.dsh.miuixbrowser.browser.MenuSyncState
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.appContainer
import dev.dsh.miuixbrowser.backup.BackupAuto
import dev.dsh.miuixbrowser.browser.AppRoute
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.DefaultBrowser
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.browser.EngineKind
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.browser.MenuOrderStore
import dev.dsh.miuixbrowser.browser.ShortcutHelper
import dev.dsh.miuixbrowser.browser.UpdateChecker
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.BAR_POS_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_FIXED_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_FIXED_TOP
import dev.dsh.miuixbrowser.data.BAR_POS_MINIMIZED
import dev.dsh.miuixbrowser.data.BAR_POS_TOP
import dev.dsh.miuixbrowser.data.Favorite
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoDebug
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtensionStore
import dev.dsh.miuixbrowser.engine.gecko.GeckoSitePerms
import dev.dsh.miuixbrowser.engine.gecko.showWebViewUnsupportedToast
import dev.dsh.miuixbrowser.net.WebViewProxy
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.hostOf
import android.app.Activity
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

import kotlin.math.max
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import dev.dsh.miuixbrowser.ui.icons.Settings
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class Sheet { None, Tabs, Menu, Link, Image, Element }

internal class WebPermPrompt(val origin: String, val what: String, val permKey: String?, val decide: (Boolean) -> Unit)

internal data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val fileName: String,
    val mime: String?,
    /** 来源页（Referer）与是否带 WebView 的 Cookie：登录态 / 防盗链站点 */
    val referer: String? = null,
    val withCookies: Boolean = false,
    val geckoCookies: Boolean = false,
    /** blob / data URL 已抽成 data URI，确认后直接落盘，不再走 HTTP 下载器 */
    val dataUri: String? = null,
    /** 大 blob 分片写入的缓存临时文件路径，确认后拷到 Download/mafari */
    val tempPath: String? = null,
    /** GM_download 要结果：取消 / 被顶替 → (false, ...)；确认后交给 Downloader 在终态回调 */
    val onResult: ((ok: Boolean, error: String?) -> Unit)? = null,
)

/**
 * 把网页 accept 里的类型（MIME / 通配 / 扩展名）整理成系统选择器能用的 MIME 列表。
 * 返回空列表 = 不加类型过滤（任意类型）。
 * 此前把未知扩展名直接去掉点当 MIME（如 ".zip" → "zip"），非法 MIME 会让部分机型的
 * 文件选择器把所有文件灰掉、无法选择。
 */
internal fun resolvePickerMimeTypes(types: List<String>): List<String> {
    if (types.isEmpty()) return emptyList()
    val extra = mapOf(
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "mkv" to "video/x-matroska", "md" to "text/markdown", "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar", "apk" to "application/vnd.android.package-archive",
        "epub" to "application/epub+zip", "json" to "application/json", "zip" to "application/zip",
    )
    val mimeRe = Regex("^[a-z0-9!#$&^_.+-]+/([a-z0-9!#$&^_.+-]+|\\*)$")
    val out = LinkedHashSet<String>()
    for (raw in types) {
        val t = raw.lowercase()
        val mime = if (t.startsWith(".")) {
            val ext = t.removePrefix(".")
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: extra[ext]
        } else t
        // 解析不了 / 不合法 / 本身就是全通配：整体不过滤
        if (mime == null || mime == "*/*" || !mimeRe.matches(mime)) return emptyList()
        out.add(mime)
    }
    return out.toList()
}

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
    // Gecko 拓展定期检查更新：应用在前台、Gecko 模式下，启动后稍等一会儿检查一次，之后每 6 小时看一次是否到点
    LaunchedEffect(settings.geckoMode) {
        if (!settings.geckoMode) return@LaunchedEffect
        kotlinx.coroutines.delay(15_000)
        while (true) {
            runCatching { GeckoExtensionStore.maybeAutoUpdate(context) }
            kotlinx.coroutines.delay(6 * 3_600_000L)
        }
    }
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
    // 旧版本放在脚本列表里的去广告订阅 → 迁移到「去广告」页；之后按设置的间隔在后台更新订阅
    remember {
        AdBlock.init(context)
        AdBlock.migrateLegacy(extensions.legacyAdSubs.toList())
        extensions.commitLegacyMigration()
        true
    }
    LaunchedEffect(Unit) { AdBlock.autoUpdateIfDue() }
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
    // 首次打开 app：自动拉取并安装推荐脚本（每个只自动装一次，失败的下次启动重试）；之后按设置的间隔后台自动更新脚本
    LaunchedEffect(Unit) {
        runCatching { extensions.bootstrapRecommended() }
        runCatching { extensions.autoUpdateIfDue() }
    }
    // 按设置的间隔把加密备份自动推送到已配置的云同步目标（需已设默认口令）
    LaunchedEffect(Unit) { runCatching { BackupAuto.runIfDue(context) } }
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
    var barMinimized by remember { mutableStateOf(settings.barPosition == BAR_POS_MINIMIZED) }
    // 「布局 → 导航栏位置」：顶部 / 底部 / 始终最小化（圆钮是常态，展开后一段时间无操作自动缩回）
    val barOnTop = settings.barPosition == BAR_POS_TOP || settings.barPosition == BAR_POS_FIXED_TOP
    val alwaysMin = settings.barPosition == BAR_POS_MINIMIZED
    // 固定在顶部 / 底部：不随滚动自动隐藏，也不响应滑动收起 / 缩成圆钮 / 换边
    val barFixed = settings.barPosition == BAR_POS_FIXED_TOP || settings.barPosition == BAR_POS_FIXED_BOTTOM
    var favEditing by ui::favEditing
    var groupEditing by ui::groupEditing
    var quickEditing by ui::quickEditing
    var showQuickPicker by ui::showQuickPicker
    var showGroupPicker by ui::showGroupPicker
    // 书签分组弹窗：显示开关与路径 / 锚点分开存，关闭动画期间内容不会突然消失
    var showGroupPopup by ui::showGroupPopup
    var groupPopupPath by ui::groupPopupPath
    var groupPopupAnchor by ui::groupPopupAnchor
    var showFavEditor by ui::showFavEditor
    var showBookmarkCreate by ui::showBookmarkCreate
    var bookmarkPrefillName by ui::bookmarkPrefillName
    var bookmarkPrefillUrl by ui::bookmarkPrefillUrl
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

    var pendingScript by ui::pendingScript
    var scriptSheetShow by ui::scriptSheetShow
    var geckoExtSheetShow by ui::geckoExtSheetShow
    var requestSheetShow by ui::requestSheetShow
    var scriptMenuItems by ui::scriptMenuItems
    var runningScripts by ui::runningScripts
    // 菜单面板右上角蓝标：当前页面运行的脚本数
    var scriptRunCount by ui::scriptRunCount
    // 菜单「同步」进行中：图标变主题色、文字变「同步中」，防止重复点击
    // 地址栏左侧脚本图标：当前标签页面运行的脚本数（加载完成后查询，见下方 LaunchedEffect）
    var addressScriptCount by ui::addressScriptCount
    // 地址栏左侧下载图标：进行中显示进度环，完成后右上角绿点（点开下载页后清除）
    val addressDownload = SharedAddressDownloadState
    LaunchedEffect(addressDownload) { addressDownload.track() }
    var pendingDownload by ui::pendingDownload
    var showFind by ui::showFind
    var editingFavIndex by ui::editingFavIndex
    // 离场动画期间保留最近一次打开的弹层内容
    var lastActiveSheet by ui::lastActiveSheet
    // 弹层从打开到离场动画结束的整段时间为 true：期间把（主页）整页冻结成一张纹理
    var sheetHostActive by ui::sheetHostActive
    var showClearTabsConfirm by ui::showClearTabsConfirm
    var showShareDialog by ui::showShareDialog
    // 标签列表当前栏：0 = 标签页，1 = 无痕浏览页
    var tabsPage by ui::tabsPage
    // 文件选择 + 视频全屏 + 网页权限询问
    var fileChooserChoice by ui::fileChooserChoice
    var customView by ui::customView
    var cameraOutputUri by ui::cameraOutputUri
    var webPermPrompt by ui::webPermPrompt
    var loginPrompt by ui::loginPrompt
    var androidPermDone by ui::androidPermDone

    // 启动自动检查更新：每个进程只查一次；有新版本且未被忽略时弹底部提示
    var updateRelease by ui::updateRelease
    var lastUpdateRelease by ui::lastUpdateRelease
    LaunchedEffect(Unit) {
        if (UpdateChecker.checkedThisProcess) return@LaunchedEffect
        UpdateChecker.checkedThisProcess = true
        // 稍等首屏稳定再请求，避免和启动渲染抢资源
        kotlinx.coroutines.delay(1500)
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            UpdateChecker.findPending(context, settings.ignoredUpdate, settings.updateChannel)
        }
        if (found != null) {
            // 引导页还在时先不弹更新提示，免得盖在引导上
            androidx.compose.runtime.snapshotFlow { settings.onboardingPending }.first { !it }
            lastUpdateRelease = found
            updateRelease = found
        }
    }

    // 弹窗收起后下载完成：把更新弹窗重新弹出，方便直接点「安装」（只处理从启动提示发起的下载）
    LaunchedEffect(UpdateDownload.phase) {
        val pending = lastUpdateRelease
        if (UpdateDownload.phase == UpdatePhase.DONE && UpdateDownload.origin == "launch" &&
            pending != null && pending.version == UpdateDownload.version && updateRelease == null
        ) {
            updateRelease = pending
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
            // 登录弹窗同理：按「不保存 / 不填」收尾，Gecko 的 prompt 才不会悬空
            tab.onLoginPrompt = null
            loginPrompt?.onDismiss?.invoke()
            loginPrompt = null
        }
    }

    // 文件选择 / 拍照 / 扫码 / 权限申请的启动器（BrowserLaunchers.kt）
    val launchers = ui.rememberBrowserLaunchers(settings, context, state, clipboard)

    LaunchedEffect(tab.id) {
        tab.onLoginPrompt = { req ->
            // 同一时刻只显示一个：被顶掉的按取消处理
            loginPrompt?.onDismiss?.invoke()
            loginPrompt = req
        }
        tab.onPermissionPrompt = { origin, what, permKey, decide ->
            // 同一时刻只显示一个弹窗：被顶掉的请求按拒绝处理，避免 WebView 回调悬空
            webPermPrompt?.decide?.invoke(false)
            webPermPrompt = WebPermPrompt(origin, what, permKey, decide)
        }
        tab.requestAndroidPermissions = { perms, done ->
            androidPermDone = done
            launchers.androidPermLauncher.launch(perms)
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
        settings.externalAppPermission,
        settings.blockTrackingScripts,
        AdBlock.fpCanvas,
        AdBlock.fpWebgl,
        AdBlock.fpAudio,
        AdBlock.fpFonts,
        GeckoSitePerms.exceptions.toMap(), // 例外网站变化 → 重新生成 WebView 的隐私脚本
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
            externalAppMode = settings.externalAppPermission,
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
            !capture -> launchers.launchFilePicker(choice)
            wantsImage && !wantsVideo -> launchers.launchCamera(choice, video = false)
            wantsVideo && !wantsImage -> launchers.launchCamera(choice, video = true)
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
            // 拓展只属于 Gecko：WebView 标签不识别、不安装拓展链接（无内核版里连 GeckoExtensionStore 都不碰）
            val store = if (EngineSupport.GECKO_BUNDLED && tab.isGecko) GeckoExtensionStore else null
            if (store != null && store.beginLinkInstall(link)) {
                android.widget.Toast.makeText(context, tr("正在安装拓展…"), android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    try {
                        store.install(context, link)
                            .onSuccess {
                                android.widget.Toast.makeText(context, tr("已添加 {0}", it.name), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                if (it.message != tr("已取消安装")) {
                                    android.widget.Toast.makeText(context, it.message ?: tr("安装失败"), android.widget.Toast.LENGTH_SHORT).show()
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
                    referer = tab.url,
                    // 无痕标签不带 Cookie（WebView 共用 CookieManager、Gecko 扩展读不到私有 Cookie 罐，带了就是串号）
                    withCookies = !tab.incognito,
                    geckoCookies = tab.engineKind == EngineKind.GECKO,
                )
            }
        }
        tab.onGmDownload = { url, name, onResult ->
            pendingDownload?.onResult?.invoke(false, "superseded")
            pendingDownload = DownloadRequest(
                url = url,
                userAgent = if (tab.engineKind == EngineKind.WEBVIEW) {
                    runCatching { tab.webView.settings.userAgentString }.getOrNull()
                } else {
                    null
                },
                fileName = Downloader.fileNameFrom(url, if (name.isBlank()) null else "attachment; filename=\"$name\""),
                mime = null,
                referer = tab.url,
                withCookies = !tab.incognito,
                geckoCookies = tab.engineKind == EngineKind.GECKO,
                onResult = onResult,
            )
        }
        tab.onBlobStart = {
            android.widget.Toast.makeText(context, tr("正在读取文件…"), android.widget.Toast.LENGTH_SHORT).show()
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

    // 回到主页（页面被盖住 / 没有页面）时底栏必须显示：主页自己没有滚动采样，
    // 之前页面滑动收起底栏后的 barHidden 会一直残留，导致主页上唤不出底栏
    LaunchedEffect(tab.id, tab.url.isEmpty()) {
        if (tab.url.isEmpty()) barHidden = false
    }
    // 兜底：主页上 barHidden 任何时候变成 true（比如在底栏上向下拖）都立刻复位。
    // 主页没有滚动手势能把底栏唤回来，收起后底栏会从组合树里移除，再也点不到、拖不到。
    LaunchedEffect(barHidden, tab.url.isEmpty(), tab.geckoFullscreen) {
        if (barHidden && tab.url.isEmpty() && !tab.geckoFullscreen) barHidden = false
    }

    // 切到「固定」立即把已收起的底栏显示出来（视频全屏除外）
    LaunchedEffect(barFixed) {
        if (barFixed) barHidden = tab.geckoFullscreen
    }

    LaunchedEffect(tab.id, barFixed) {
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
                // 固定模式：不做滚动隐藏
                if (barFixed) {
                    barHidden = false
                    return@runCatching
                }
                // 主页（页面被盖住）上：引擎里的页面可能还在惯性滚动 / 回调滚动事件，
                // 不能让它把底栏重新收起，否则主页上没有任何手势能唤出底栏
                if (tab.url.isEmpty()) {
                    barHidden = false
                    last = tab.scrollOffsetY.coerceAtLeast(0)
                    lastShow = last
                    scrollDownAccum = 0f
                    scrollUpAccum = 0f
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
            // 纯事件驱动：只在引擎滚动事件（CONFLATED 合并，只取最新位置）或下面补发的信号到来时处理一次。
            // 底栏阈值按 dy 累计，与采样间隔无关，所以不再需要 80ms 定时采样，也不再有静止时的超时唤醒。
            tab.scrollSignal.receive()
        }
    }
    // 没有滚动事件、但偏移 / 状态可能已变的时刻补发一次信号：全屏切换、换页、加载状态变化（导航后偏移被重置）
    LaunchedEffect(tab.id, tab.geckoFullscreen, tab.url, tab.loading) {
        tab.scrollSignal.trySend(Unit)
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
BrowserChunk {
            if (tab.url.isEmpty() && wallpaperBitmap != null) {
                val wallpaperImage = wallpaperBitmap
                if (wallpaperImage != null) {
                    // 取样层就是用户看到的那张壁纸（+压暗）：开启「壁纸模糊」时直接画磁盘缓存里已模糊好的图，
                    // 所有材质（柔光 / 液态玻璃）的首页卡片、底栏、弹层都从这一层取色，取色与所见一致，
                    // 不再另盖一层显示用的模糊图。缓存还没读出来时先显示未模糊原图，不做实时模糊，避免卡片闪动。
                    val shownWallpaper = if (wallpaperFromCache && blurredWallpaper != null) blurredWallpaper else wallpaperImage
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                wallpaperLayout.origin = it.positionInRoot()
                                wallpaperLayout.size = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())
                            }
                            .layerBackdrop(wallpaperBackdrop)
                            // Kyant 取样层只在液态玻璃 + 模糊开启时才有人读（见 StartPage 的 liquidBackdrop 与 homeGlass），否则不录制
                            .then(
                                if (settings.liquidGlass && settings.blurEnabled) Modifier.kyantLayerBackdrop(wallpaperLiquidBackdrop)
                                else Modifier,
                            ),
                    ) {
                        Image(
                            bitmap = shownWallpaper,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // 压暗层：缓存图里已烘进，只有显示未模糊原图（模糊关闭 / 缓存未就绪）时才另盖
                        if (!wallpaperFromCache) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = WallpaperBlurCache.SCRIM_ALPHA)))
                        }
                    }
                }
            }
}

        // ---- 网页内容：从状态栏下面开始，不再压到状态栏 ----
BrowserChunk {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 全屏沉浸（decorFitsSystemWindows=false）下系统不会替 WebView / GeckoView 缩小窗口，
                        // 键盘会直接盖住屏幕底部的网页输入框。这里让网页区域避开键盘：
                        // 区域变矮后页面会把获得焦点的输入框滚到可见处。
                        // 地址栏输入时不收缩，避免每次唤起键盘都让网页整体重排。
                        // 注意：不能用 imePadding()——它每帧跟着键盘动画变化，Gecko 的 Surface 会在
                        // 动画期间被连续重排（窗口抖动）。这里用动画「目标」高度，只在动画开始时变一次。
                        .then(if (editing) Modifier else Modifier.windowInsetsPadding(rememberImeTargetInsets()))
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
                            showLogo = settings.homeShowLogo,
                            barOnTop = barOnTop,
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
                            recent = history.recent,
                            onOpenDownloads = { pushPage(AppRoute.Downloads) },
                            onHide = { kind ->
                                settings.updateHomeShow(kind, false)
                                android.widget.Toast.makeText(
                                    context, tr("已隐藏，可在 设置 → 布局 → 定制主页 中恢复"), android.widget.Toast.LENGTH_SHORT,
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
                            showGroups = settings.homeShowGroups,
                            groups = homeGroups,
                            groupRows = settings.homeGroupRows,
                            groupEditing = groupEditing,
                            // 点击分组磁贴：不再跳转，在图标下方弹出书签窗口
                            onGroupOpen = { path, anchor ->
                                groupPopupPath = path
                                groupPopupAnchor = anchor
                                showGroupPopup = true
                            },
                            onGroupToggleEdit = { groupEditing = !groupEditing },
                            // 写回时只保留还存在的分组（顺带清掉已失效的 id）
                            onGroupRemove = { pos ->
                                favorites.updateHomeGroupIds(homeGroups.filterIndexed { i, _ -> i != pos }.map { it.id })
                            },
                            onGroupMove = { from, to ->
                                val ids = homeGroups.map { it.id }.toMutableList()
                                if (from in ids.indices && to in ids.indices) {
                                    ids.add(to, ids.removeAt(from))
                                    favorites.updateHomeGroupIds(ids)
                                }
                            },
                            onGroupAdd = { showGroupPicker = true },
                            showQuick = settings.homeShowQuick,
                            quickActions = settings.homeQuickItems,
                            quickRows = settings.homeQuickRows,
                            quickEditing = quickEditing,
                            onQuickAction = { action ->
                                // 拓展只在 Gecko 内核可用：WebView 下只提示
                                if (action == MenuAction.GeckoExtensions && !settings.geckoMode) {
                                    showWebViewUnsupportedToast(context)
                                } else {
                                    quickRunner.run(action)
                                }
                            },
                            onQuickToggleEdit = { quickEditing = !quickEditing },
                            // 回调里的下标是可见部分的位置，与完整列表的前缀一致
                            onQuickRemove = { pos ->
                                settings.updateHomeQuickItems(settings.homeQuickItems.filterIndexed { i, _ -> i != pos })
                            },
                            onQuickMove = { from, to ->
                                val list = settings.homeQuickItems.toMutableList()
                                if (from in list.indices && to in list.indices) {
                                    list.add(to, list.removeAt(from))
                                    settings.updateHomeQuickItems(list)
                                }
                            },
                            onQuickAdd = { showQuickPicker = true },
                            pinBottom = settings.homePinBottom,
                            onPinBottomChange = { settings.updateHomePinBottom(it) },
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
}
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
                    // 靠上：状态栏下方留 12dp，键盘弹出时不跟着动；靠下：避开键盘 / 导航栏
                    if (barOnTop) Modifier.statusBarsPadding().padding(top = 12.dp)
                    else Modifier.imePadding().navigationBarsPadding().padding(bottom = 8.dp),
                )
                .padding(horizontal = 8.dp),
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
            val fixedToolbarWidth = 44.dp * 4 + 12.dp + 24.dp
            val loadingIndicatorWidth = if (tab.loading) 15.dp else 0.dp
            val refreshWidth = if (shouldShowAddressRefresh(displayAddress, tab.loading, editing)) AddressRefreshWidth else 0.dp
            // 阅读模式图标（地址栏最左侧）：可进入 / 正在阅读，且有页面（主页 url 为空不显示）
            val readerShown = tab.url.isNotEmpty() && (tab.readerable || tab.readerActive)
            // 阅读层按当前应用主题配色：用 surface 亮度判断深浅（MiuixTheme 的取值只能在组合里读，不能放进点击回调）
            val readerSurface = MiuixTheme.colorScheme.surface
            val readerDark = (0.299f * readerSurface.red + 0.587f * readerSurface.green + 0.114f * readerSurface.blue) < 0.5f
            // 脚本图标与阅读图标共用这一个位置（宽度相同）：阅读优先，没有阅读图标时才可能显示脚本图标
            // 优先级：下载 > 阅读 > 脚本，三者共用同一个位置（宽度相同）
            val downloadShown = shouldShowAddressDownload(addressDownload.visible, editing)
            val scriptsShown = shouldShowAddressScripts(readerShown, addressScriptCount > 0, editing, addressDownload.visible)
            val readerWidth = if (downloadShown || shouldShowAddressReader(readerShown, editing, addressDownload.visible) || scriptsShown) AddressReaderWidth else 0.dp
            val requestedBarWidth = fixedToolbarWidth + measuredAddressWidth + loadingIndicatorWidth + refreshWidth + readerWidth
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
                                translationY = barEdgeSign * hide * 210.dp.toPx()
                                alpha = (1f - hide) * circleScale
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
                        .width(animatedBarWidth)
                        // 在底栏上朝屏幕边缘滑动（靠下向下 / 靠上向上）主动收起；左右滑动缩小为搜索圆钮；
                        // 朝屏幕中间快速一扫（靠下快速上扫 / 靠上快速下扫）把底栏换到另一侧并记进设置。
                        // 编辑地址时不挂这组手势：横向拖动要留给输入框（移动光标 / 滚动文字），
                        // 否则累计 72px 就会把底栏缩成圆钮，向下拖还会收起底栏
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
                        .graphicsLayer {
                            translationY = barEdgeSign * hide * 210.dp.toPx()
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
                    name = us?.name?.ifBlank { null } ?: tr("未命名脚本"),
                    icon = us?.icon.orEmpty(),
                    iconBase = us?.sourceUrl.orEmpty(),
                    iconFallbackHost = us?.let { UserscriptEngine.fallbackIconHost(UserscriptEngine.parse(it.code)) }.orEmpty(),
                )
            }
        }
        t.queryMenuCommands { scriptMenuItems = it }
    }

    // 菜单功能的执行：底部「更多」菜单和首页「快捷菜单」共用
    fun runMenuAction(action: MenuAction) {
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
                    tr("已添加此书签")
                } else {
                    favorites.add(favName, tab.url)
                    tr("添加书签成功")
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            MenuAction.Bookmarks -> pushPage(AppRoute.Library(0))
            MenuAction.Share -> if (tab.url.isNotBlank()) {
                if (needsInAppShareDialog()) showShareDialog = true else shareUrlSystem(context, tab.url, tab.title)
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
            MenuAction.Scan -> launchers.launchScan()
            // 单击：拓展快捷菜单；长按（见 onLongPressAction）：进入拓展页
            MenuAction.GeckoExtensions -> geckoExtSheetShow = true
            MenuAction.ScriptMenu -> {
                scriptSheetShow = true
                refreshScriptSheet()
            }
            MenuAction.History -> pushPage(AppRoute.Library(1))
            MenuAction.Requests -> requestSheetShow = true
            MenuAction.Settings -> pushPage(AppRoute.Settings)
            MenuAction.Account -> if (EngineSupport.SYNC_BUNDLED) pushPage(AppRoute.FxaPage)
            MenuAction.CloseTab -> state.closeTab(state.activeIndex)
            MenuAction.CleanCache -> scope.launch {
                val freed = runCatching { dev.dsh.miuixbrowser.browser.CacheCleaner.cleanAll(context) }.getOrDefault(0L)
                android.widget.Toast.makeText(
                    context,
                    if (freed > 0) tr("已清理 ") + android.text.format.Formatter.formatFileSize(context, freed) else tr("没有可清理的缓存"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            MenuAction.Sync -> if (!MenuSyncState.syncing) {
                MenuSyncState.syncing = true
                scope.launch {
                    val r = runCatching { BackupAuto.pushAllNow(context) }.getOrNull()
                    MenuSyncState.syncing = false
                    val msg = when {
                        r == null -> tr("同步失败")
                        r.error != null -> r.error ?: ""
                        r.failed == 0 -> tr("同步完成，已上传到 {0} 个目标", r.ok)
                        r.ok == 0 -> tr("同步失败（{0} 个目标上传失败）", r.failed)
                        else -> tr("同步完成：{0} 个成功，{1} 个失败", r.ok, r.failed)
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // 去广告开关：只有 WebView 内核有（Gecko 标签下菜单里是置灰的）
            MenuAction.AdBlock -> if (!tab.isGecko) {
                AdBlock.updateEnabled(!AdBlock.enabled)
                android.widget.Toast.makeText(context, if (AdBlock.enabled) tr("去广告已开启") else tr("去广告已关闭"), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                dev.dsh.miuixbrowser.engine.gecko.showGeckoUnsupportedToast(context)
            }
        }
    }
    quickRunner.run = { runMenuAction(it) }

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
            // 脚本菜单命令没有「注册完成」事件可订阅，不能纯事件驱动；改成递增退避：
            // 刚加载完 document-idle 脚本 / 延迟注册命令最容易出现，先密后疏（0.5s → 1.5s → 3s → 5s），
            // 之后保持 5 秒一次，菜单关闭或页面重新加载时本协程被取消。
            var gap = 500L
            while (true) {
                refreshScriptSheet()
                kotlinx.coroutines.delay(gap)
                gap = when {
                    gap < 1500L -> 1500L
                    gap < 3000L -> 3000L
                    else -> 5000L
                }
            }
        }
    }

    // 地址栏脚本图标：页面加载完成后查询当前页运行的脚本数。document-idle 的脚本比首次查询出现得晚，
    // 所以加载完后只在 0.6s / 2.6s 各查一次（事件驱动，不轮询）；开始加载就先清掉，避免上一页的图标留在新页面上
    val scriptIconTab = state.activeTab
    LaunchedEffect(scriptIconTab.id, scriptIconTab.url, scriptIconTab.loading, settings.scriptsEnabled) {
        if (!settings.scriptsEnabled || scriptIconTab.url.isEmpty() || scriptIconTab.loading) {
            addressScriptCount = 0
            return@LaunchedEffect
        }
        for (gap in longArrayOf(600L, 2000L)) {
            kotlinx.coroutines.delay(gap)
            scriptIconTab.queryRunningScripts { n -> if (state.activeTab === scriptIconTab) addressScriptCount = n }
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
