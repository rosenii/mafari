package dev.dsh.miuixbrowser.ui

import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.net.WebViewProxy
import dev.dsh.miuixbrowser.browser.HOME_QUICK_ROWS_MAX
import dev.dsh.miuixbrowser.data.BLUR_FROST
import dev.dsh.miuixbrowser.data.BLUR_LIQUID
import dev.dsh.miuixbrowser.data.BLUR_MIUIX
import dev.dsh.miuixbrowser.data.BLUR_OFF
import dev.dsh.miuixbrowser.data.HOME_FAV_ROWS_MAX
import dev.dsh.miuixbrowser.data.HOME_GROUP_ROWS_MAX
import dev.dsh.miuixbrowser.data.HOME_PRIVACY
import dev.dsh.miuixbrowser.data.HOME_RECENT
import dev.dsh.miuixbrowser.data.HOME_DOWNLOADS
import dev.dsh.miuixbrowser.util.looksLikeUrl
import dev.dsh.miuixbrowser.BuildConfig
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.browser.CacheCleaner
import dev.dsh.miuixbrowser.browser.DefaultBrowser
import dev.dsh.miuixbrowser.crypto.CipherSuites
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.DESKTOP_UA
import dev.dsh.miuixbrowser.data.ENGINE_GECKO
import dev.dsh.miuixbrowser.data.HOME_MODE_CUSTOM
import dev.dsh.miuixbrowser.data.HOME_MODE_NEW_TAB
import dev.dsh.miuixbrowser.data.PasswordDb
import dev.dsh.miuixbrowser.data.RestoreMode
import dev.dsh.miuixbrowser.data.SEARCH_ENGINES
import dev.dsh.miuixbrowser.data.rememberSearchIcon
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoDebug
import dev.dsh.miuixbrowser.engine.gecko.GeckoProtection
import dev.dsh.miuixbrowser.engine.gecko.GeckoRuntimeHolder
import dev.dsh.miuixbrowser.engine.gecko.GeckoSitePerms
import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupVisuals
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 「高级材质」下拉的顺序（下标 ↔ blurMode）。 */
private val blurModes = listOf(BLUR_MIUIX, BLUR_FROST, BLUR_LIQUID, BLUR_OFF)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onApplyUserAgent: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenScripts: () -> Unit,
    onOpenExtensions: () -> Unit = {},
    onOpenGeckoProtection: () -> Unit = {},
    onOpenGeckoDebug: () -> Unit = {},
    onOpenWebViewDebug: () -> Unit = {},
    onOpenAdBlock: () -> Unit,
    onOpenPrivacyEnhance: () -> Unit,
    onOpenCookies: () -> Unit = {},
    onOpenPasswords: () -> Unit = {},
    onOpenBookmarks: () -> Unit,
    onOpenBackup: () -> Unit = {},
    onOpenAbout: () -> Unit,
    /** 「使用指南」：重新打开新用户引导（由 BrowserScreen 实现：退回浏览主界面并盖上引导）。 */
    onOpenGuide: () -> Unit = {},
    onPageEnhancementsChange: (Boolean) -> Unit = {},
    blurEnabled: Boolean = true,
) {
    val context = LocalContext.current
    // 「设为默认浏览器」：从系统角色弹窗 / 系统设置页回来后刷新状态
    var isDefaultBrowser by remember { mutableStateOf(DefaultBrowser.isDefault(context)) }
    var defaultRequestAt by remember { mutableStateOf(0L) }
    val defaultBrowserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultBrowser = DefaultBrowser.isDefault(context)
        // 用户多次拒绝后系统不再弹角色窗口，会立刻返回取消：直接带去系统「默认应用」设置页
        if (!isDefaultBrowser && android.os.SystemClock.elapsedRealtime() - defaultRequestAt < 500) {
            runCatching { context.startActivity(DefaultBrowser.settingsIntent()) }
        }
    }
    DisposableEffect(context) {
        val owner = context as? androidx.lifecycle.LifecycleOwner
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) isDefaultBrowser = DefaultBrowser.isDefault(context)
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    // 「密码」行的摘要：已保存条数（只数行数，不解密）；从密码页返回时重新统计
    var passwordCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(PasswordDb.version) {
        passwordCount = withContext(Dispatchers.IO) { AppDatabase.get(context)?.let { PasswordDb.count(it) } }
    }
    var showUa by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showAddEngine by remember { mutableStateOf(false) }
    var showWallpaper by remember { mutableStateOf(false) }
    var showGlassParams by remember { mutableStateOf(false) }
    var showPermSheet by remember { mutableStateOf(false) }
    var showPermExceptions by remember { mutableStateOf(false) }
    var exceptionSite by remember { mutableStateOf<String?>(null) }
    // Gecko 网站权限（自动播放 / 持久存储 / 跨站 Cookie / DRM / 例外网站…）的存储
    LaunchedEffect(Unit) { GeckoSitePerms.load(context) }
    var showDonate by remember { mutableStateOf(false) }
    var showHomeSheet by remember { mutableStateOf(false) }
    var showHomeUrlDialog by remember { mutableStateOf(false) }
    var showProxyDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var reclaimable by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { reclaimable = withContext(Dispatchers.IO) { CacheCleaner.reclaimableBytes(context) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            settings.updateWallpaper(uri.toString())
        }
    }

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val popupVisuals = appPopupVisuals()
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = tr("设置"),
                backdrop = backdrop,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                backButton = {
                    LiquidGlassBackButton(
                        backdrop = if (glass) liquidBackdrop else null,
                        surfaceVisible = topScrolled,
                        onClick = onBack,
                    )
                },
            )
        },
    ) { innerPadding ->
        val topInset = innerPadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .scrollEndHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .pageBackground()
                    .verticalScroll(rememberScrollState())
                    .padding(top = topInset)
                    // 沉浸：内容可滚到手势小白条下方，仅在末尾留出小白条高度
                    .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                SmallTitle(text = tr("外观"))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    var languageSel by remember { mutableStateOf(dev.dsh.miuixbrowser.i18n.I18n.selected) }
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("语言"),
                        items = listOf(tr("跟随系统"), "English", "简体中文", "繁體中文"),
                        selectedIndex = languageSel.coerceIn(0, 3),
                        onSelectedIndexChange = { idx ->
                            if (idx != languageSel) {
                                languageSel = idx
                                dev.dsh.miuixbrowser.i18n.I18n.save(context, idx)
                                Toast.makeText(context, tr("语言已切换，正在重启…"), Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    kotlinx.coroutines.delay(500)
                                    dev.dsh.miuixbrowser.backup.BackupManager.restartApp(context)
                                }
                            }
                        },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("显示模式"),
                        items = listOf(tr("跟随系统"), tr("浅色模式"), tr("深色模式")),
                        selectedIndex = settings.themeMode.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateThemeMode(it) },
                    )
                    SettingsRow(
                        title = tr("壁纸"),
                        summary = if (settings.wallpaper.isBlank()) tr("未设置") else tr("已设置"),
                        onClick = { showWallpaper = true },
                    )
                    AppIconPicker(
                        selected = settings.appIcon,
                        onSelect = {
                            if (it != settings.appIcon) {
                                settings.updateAppIcon(it)
                                Toast.makeText(context, tr("退出应用后桌面图标将更新"), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("高级材质"),
                        summary = tr("柔光玻璃 / 磨砂 / 液态玻璃 / 纯色"),
                        items = listOf(tr("柔光玻璃"), tr("磨砂"), tr("液态玻璃"), tr("纯色")),
                        selectedIndex = blurModes.indexOf(settings.blurMode).coerceAtLeast(0),
                        onSelectedIndexChange = { settings.updateBlurMode(blurModes[it]) },
                    )
                    if (settings.blurMode == BLUR_MIUIX) {
                        SettingsRow(
                            title = tr("自定义材质参数"),
                            summary = glassParamsSummary(settings),
                            onClick = { showGlassParams = true },
                        )
                    }
                }

                SmallTitle(text = tr("布局"))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("地址栏显示"),
                        items = listOf(tr("网站标题"), tr("主域名"), tr("完整网址")),
                        selectedIndex = settings.addressDisplay.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateAddressDisplay(it) },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("标签栏样式"),
                        items = listOf(tr("文字显示"), tr("卡片显示")),
                        selectedIndex = settings.tabsStyle.coerceIn(0, 1),
                        onSelectedIndexChange = { settings.updateTabsStyle(it) },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("导航栏样式"),
                        // 顶部 / 底部：随页面滚动自动隐藏；固定在底部 / 顶部：始终显示，不再自动隐藏
                        items = listOf(tr("顶部"), tr("底部"), tr("始终最小化"), tr("固定在底部"), tr("固定在顶部"), tr("悬浮三段式")),
                        // 选项顺序与 BAR_POS_* 数值一致（0..5）
                        selectedIndex = settings.barPosition.coerceIn(0, 5),
                        onSelectedIndexChange = { settings.updateBarPosition(it) },
                    )
                    SettingsRow(
                        title = tr("定制主页"),
                        summary = homeContentSummary(settings),
                        onClick = { showHomeSheet = true },
                    )
                    AppSwitchPreference(
                        title = tr("同站链接新标签页打开"),
                        summary = tr("网页内同站的新窗口链接（target=_blank）在新标签页打开；关闭则在当前标签页打开"),
                        checked = settings.sameSiteNewTab,
                        onCheckedChange = { settings.updateSameSiteNewTab(it) },
                    )
                    // 与「Gecko 调试」里的原生沉浸是同一个开关（Gecko 专属）
                    EngineRow(visible = settings.geckoMode) {
                        AppSwitchPreference(
                            title = tr("原生沉浸"),
                            summary = tr("状态栏、导航栏全沉浸浏览"),
                            checked = GeckoDebug.nativeImmersive,
                            onCheckedChange = { GeckoDebug.updateNativeImmersive(it) },
                        )
                    }
                }

                SmallTitle(text = tr("浏览"))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("重启恢复标签页"),
                        summary = when (settings.restoreTabsMode) {
                            RestoreMode.KEEP_NEW -> tr("恢复之前的标签页，但不跳转，直接新建一个新标签页")
                            RestoreMode.NONE -> tr("不恢复，直接打开新标签页")
                            else -> tr("恢复到退出前的状态，冷启动会回到当时浏览的页面")
                        },
                        items = listOf(tr("恢复为退出前的标签列表状态"), tr("恢复标签并打开新标签页"), tr("不恢复，直接打开新标签页")),
                        selectedIndex = settings.restoreTabsMode.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateRestoreTabsMode(it) },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("外部链接打开方式"),
                        items = listOf(tr("应用内打开"), tr("自定义标签页"), tr("其他浏览器")),
                        selectedIndex = settings.linkOpenMode.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateLinkOpenMode(it) },
                    )
                    SettingsRow(
                        title = tr("书签与历史"),
                        summary = tr("书签分组、浏览记录"),
                        onClick = onOpenBookmarks,
                    )
                    SettingsRow(
                        title = tr("下载"),
                        summary = tr("查看已下载的文件"),
                        onClick = onOpenDownloads,
                    )
                    SettingsRow(
                        title = tr("搜索引擎"),
                        summary = settings.allSearchEngines.getOrNull(settings.searchIndex)?.name ?: tr("自定义"),
                        onClick = { showSearch = true },
                    )
                    SettingsRow(
                        title = tr("UA 标识管理"),
                        summary = uaSummary(settings.userAgent),
                        onClick = { showUa = true },
                    )
                }

                SmallTitle(text = tr("隐私与安全"))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    SettingsRow(
                        title = tr("密码"),
                        summary = passwordSummary(passwordCount),
                        onClick = onOpenPasswords,
                    )
                    SettingsRow(
                        title = tr("Cookie 管理"),
                        summary = COOKIE_MODE_NAMES[settings.cookieMode.coerceIn(0, 3)] +
                            (if (settings.cookieClearOnLaunch > 0) tr(" · 启动时清理") else "") +
                            tr(" · 接受策略 · 自动清理 · 清除数据"),
                        onClick = onOpenCookies,
                    )
                    SettingsRow(
                        title = tr("网站权限管理"),
                        summary = if (settings.geckoMode) {
                            tr("自动播放 · 摄像头 · 位置 · 麦克风 · 通知 · 持久存储 · 跨站 Cookie · DRM · 例外网站")
                        } else {
                            tr("摄像头 · 麦克风 · 定位 · 网站通知 · 剪贴板 · 媒体发声 · 网页重定向 · 打开其他应用")
                        },
                        onClick = { showPermSheet = true },
                    )
                    // 隐私增强保护：WebView 专属（Gecko 对应的是并列的「Gecko 高级保护」）
                    EngineRow(visible = !settings.geckoMode) {
                        SettingsRow(
                            title = tr("隐私增强保护"),
                            summary = privacyEnhanceSummary(settings),
                            onClick = onOpenPrivacyEnhance,
                        )
                    }
                    EngineRow(visible = settings.geckoMode) {
                        SettingsRow(
                            title = tr("Gecko 高级保护"),
                            summary = GeckoProtection.activeCount().let {
                                if (it == 0) tr("反追踪 · 指纹保护 · 安全 DNS · HTTPS-Only")
                                else tr("已开启 {0} 项 · 反追踪 · 指纹保护 · 安全 DNS", it)
                            },
                            onClick = onOpenGeckoProtection,
                        )
                    }
                }

                SmallTitle(text = tr("高级"))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    // 已是系统默认浏览器时整行隐藏（从系统弹窗 / 设置页回来或回到前台时 isDefaultBrowser 会刷新，自动出现 / 消失）
                    EngineRow(visible = !isDefaultBrowser) {
                        SettingsRow(
                            title = tr("设为默认浏览器"),
                            summary = tr("让其他应用里的网页链接用 Mafari 打开"),
                            onClick = {
                                val ok = runCatching {
                                    defaultRequestAt = android.os.SystemClock.elapsedRealtime()
                                    defaultBrowserLauncher.launch(DefaultBrowser.requestIntent(context))
                                }.isSuccess
                                if (!ok) Toast.makeText(context, tr("无法打开系统设置，请在 系统设置 → 应用 → 默认应用 里选择 Mafari"), Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                    // 内核切换只在带 Gecko 的版本里出现；无内核版只有系统 WebView
                    if (EngineSupport.GECKO_BUNDLED) {
                        AppDropdownPreference(
                            backdrop = if (glass) backdrop else null,
                            title = tr("浏览器内核"),
                            summary = if (settings.browserEngine == ENGINE_GECKO) {
                                tr("Gecko · 新建标签页生效")
                            } else {
                                tr("系统 WebView · 新建标签页生效")
                            },
                            items = listOf(tr("WebView（系统）"), "GeckoView（Nightly）"),
                            selectedIndex = settings.browserEngine.coerceIn(0, 1),
                            onSelectedIndexChange = { index ->
                                if (index == ENGINE_GECKO && !EngineSupport.geckoAvailable()) {
                                    Toast.makeText(context, tr("当前设备不是 arm64-v8a，无法使用 Gecko 内核"), Toast.LENGTH_SHORT).show()
                                } else if (index != settings.browserEngine) {
                                    settings.updateBrowserEngine(index)
                                    // 切 Gecko：记住当前状态并关闭去广告 / 隐私增强保护（脚本两种内核通用，保持不变）；切回 WebView：恢复切换前状态
                                    if (index == ENGINE_GECKO) {
                                        settings.suspendForGecko()
                                        // 关掉 WebView 拓展的后台页（隐藏 WebView），WebView 侧不再常驻
                                        dev.dsh.miuixbrowser.StartupCoordinator.onEngineSwitchedToGecko(context)
                                    } else {
                                        settings.resumeFromGecko()
                                        // 补做 Gecko 默认时跳过的 WebView 侧工作（去广告编译、拓展后台页）
                                        dev.dsh.miuixbrowser.StartupCoordinator.onEngineSwitchedToWebView(context)
                                    }
                                    Toast.makeText(
                                        context,
                                        tr("新建的标签页将使用 {0} 内核", if (index == ENGINE_GECKO) "Gecko" else "WebView"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                    // 网页增强是 WebView 的总开关（脚本 + 去广告 + 拓展）；Gecko 下脚本只受脚本菜单里的开关控制
                    EngineRow(visible = !settings.geckoMode) {
                        AppSwitchPreference(
                            title = tr("网页增强"),
                            summary = tr("总开关：一键启停全部油猴脚本、去广告和拓展；关闭后全部停止加载，不占内存和资源，冷启动也不再加载"),
                            checked = settings.pageEnhancements,
                            onCheckedChange = {
                                settings.updatePageEnhancements(it)
                                onPageEnhancementsChange(it)
                                AdBlock.updateEnabled(it)
                            },
                        )
                    }
                    // 拓展两种内核都有入口（Gecko：Firefox 扩展；WebView：自研运行时，实验性）；网页增强 / 去广告依赖 WebView 专有 API；脚本管理两种内核通用
                    SettingsRow(
                        title = tr("拓展"),
                        summary = if (settings.geckoMode) tr("管理 Firefox 扩展") else if (!settings.pageEnhancements) tr("网页增强已关闭 · 拓展未运行") else tr("管理 WebView 拓展（实验性）"),
                        onClick = onOpenExtensions,
                    )
                    EngineRow(visible = !settings.geckoMode) {
                        SettingsRow(
                            title = tr("去广告"),
                            summary = if (AdBlock.enabled) {
                                tr("已开启 · {0} 个订阅 · 订阅源导入、自定义规则", AdBlock.subs.count { it.enabled })
                            } else {
                                tr("已关闭")
                            },
                            onClick = onOpenAdBlock,
                        )
                    }
                    SettingsRow(
                        title = tr("脚本管理"),
                        summary = tr("用户脚本 / 油猴脚本"),
                        onClick = onOpenScripts,
                    )
                    SettingsRow(
                        title = tr("代理"),
                        summary = if (settings.proxyEnabled && settings.proxyHost.isNotBlank()) {
                            val kind = if (settings.proxyType == 1) "SOCKS5" else "HTTP"
                            val auth = if (settings.proxyAuth) tr(" · 认证") else ""
                            tr("已开启 · {0} {1}:{2}{3}", kind, settings.proxyHost, settings.proxyPort, auth)
                        } else {
                            tr("关闭 · 支持 HTTP / SOCKS5")
                        },
                        onClick = { showProxyDialog = true },
                    )
                    EngineRow(visible = settings.geckoMode) {
                        SettingsRow(
                            title = tr("Gecko 调试"),
                            summary = tr("渲染信息 · 沉浸模式 · 系统字体 · 性能叠层 · 运行日志"),
                            onClick = onOpenGeckoDebug,
                        )
                    }
                    EngineRow(visible = !settings.geckoMode) {
                        SettingsRow(
                            title = tr("WebView 调试"),
                            summary = tr("内核信息 · 运行日志 · 控制台日志 · 远程调试 · 清理缓存"),
                            onClick = onOpenWebViewDebug,
                        )
                    }
                }

                SmallTitle(text = tr("数据"))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    SettingsRow(
                        title = tr("备份与同步"),
                        summary = tr("加密备份导出 · 从备份文件恢复 · {0} 混合抗量子加密", CipherSuites.default.shortName),
                        onClick = onOpenBackup,
                    )
                    SettingsRow(
                        title = tr("清理缓存"),
                        summary = tr("每次冷启动自动清理 · 同系统「清除缓存」范围，保留未完成的下载 · 当前可清理 ") +
                            android.text.format.Formatter.formatFileSize(context, reclaimable),
                        onClick = {
                            scope.launch {
                                val freed = CacheCleaner.cleanAll(context)
                                reclaimable = withContext(Dispatchers.IO) { CacheCleaner.reclaimableBytes(context) }
                                Toast.makeText(
                                    context,
                                    if (freed > 0) tr("已清理 ") + android.text.format.Formatter.formatFileSize(context, freed) else tr("没有可清理的缓存"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }

                SmallTitle(text = tr("关于"))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    SettingsRow(
                        title = tr("使用指南"),
                        summary = tr("重新查看新用户引导"),
                        onClick = onOpenGuide,
                    )
                    SettingsRow(
                        title = tr("捐赠"),
                        summary = DONATE_SUMMARY,
                        onClick = { showDonate = true },
                    )
                    SettingsRow(
                        title = tr("关于 Mafari"),
                        summary = tr("版本 ") + BuildConfig.VERSION_NAME,
                        onClick = onOpenAbout,
                    )
                }
            }

            DonateSheet(show = showDonate, backdrop = if (glass) backdrop else null, onDismiss = { showDonate = false })

            // UA 选择放在设置页自己的 Scaffold 里：浏览主界面的覆盖层会被二级页面盖住
            UaDialog(
                show = showUa,
                initial = settings.userAgent,
                // 采样设置页自己的采样层（不采 browserBackdrop，主页可能含壁纸）；关闭模糊时回退不透明色
                backdrop = if (glass) backdrop else null,
                onDismiss = { showUa = false },
                onConfirm = {
                    settings.updateUserAgent(it)
                    onApplyUserAgent(it)
                    showUa = false
                },
            )

            SearchSheet(
                show = showSearch,
                current = settings.searchIndex,
                engines = settings.allSearchEngines,
                backdrop = if (glass) backdrop else null,
                onDismiss = { showSearch = false },
                onPick = {
                    settings.updateSearch(it)
                    showSearch = false
                },
                onDelete = { settings.removeCustomEngine(it) },
                onAddCustom = {
                    showSearch = false
                    showAddEngine = true
                },
            )

            AddEngineDialog(
                show = showAddEngine,
                backdrop = if (glass) backdrop else null,
                onDismiss = { showAddEngine = false },
                onSave = { name, address ->
                    if (settings.addCustomEngine(name, address)) {
                        showAddEngine = false
                        true
                    } else {
                        false
                    }
                },
            )

            AppGlassDialog(
                show = showWallpaper,
                title = tr("壁纸"),
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showWallpaper = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    WallpaperDialogRow(
                        title = tr("选择壁纸"),
                        onClick = {
                            showWallpaper = false
                            picker.launch(arrayOf("image/*"))
                        },
                    )
                    WallpaperDialogRow(
                        title = tr("清除壁纸"),
                        titleColor = MiuixTheme.colorScheme.error,
                        onClick = {
                            showWallpaper = false
                            settings.updateWallpaper("")
                        },
                    )
                    WallpaperDialogRow(
                        title = tr("模糊壁纸"),
                        summary = tr("主页壁纸做高斯模糊，减少干扰"),
                        onClick = { settings.updateWallpaperBlur(!settings.wallpaperBlur) },
                    ) {
                        AppSwitch(
                            checked = settings.wallpaperBlur,
                            onCheckedChange = { settings.updateWallpaperBlur(it) },
                        )
                    }
                    WallpaperDialogRow(
                        title = tr("全局应用"),
                        summary = tr("壁纸同时作为设置等二级页面的背景"),
                        onClick = { settings.updateWallpaperGlobal(!settings.wallpaperGlobal) },
                    ) {
                        AppSwitch(
                            checked = settings.wallpaperGlobal,
                            onCheckedChange = { settings.updateWallpaperGlobal(it) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    AppDialogButton(
                        onClick = { showWallpaper = false },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        minHeight = 44.dp,
                    ) {
                        Text(text = tr("关闭"), fontSize = 16.sp, style = MiuixTheme.textStyles.button)
                    }
                }
            }

            // 代理：官方 ProxyController，HTTP / SOCKS5
            var proxyOn by remember(showProxyDialog) { mutableStateOf(settings.proxyEnabled) }
            var proxyType by remember(showProxyDialog) { mutableStateOf(settings.proxyType) }
            var proxyHost by remember(showProxyDialog) { mutableStateOf(settings.proxyHost) }
            var proxyPort by remember(showProxyDialog) {
                mutableStateOf(if (settings.proxyPort > 0) settings.proxyPort.toString() else "")
            }
            var proxyAuth by remember(showProxyDialog) { mutableStateOf(settings.proxyAuth) }
            var proxyUser by remember(showProxyDialog) { mutableStateOf(settings.proxyUser) }
            var proxyPass by remember(showProxyDialog) { mutableStateOf(settings.proxyPass) }
            // SOCKS5 官方 API 不支持账密：认证开关置灰；代理关闭时仍可先配好认证
            val authSwitchEnabled = true
            AppGlassDialog(
                show = showProxyDialog,
                title = tr("代理"),
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showProxyDialog = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AppSwitchPreference(
                        title = tr("启用代理"),
                        checked = proxyOn,
                        onCheckedChange = { proxyOn = it },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = tr("类型"),
                        items = listOf("HTTP", "SOCKS5"),
                        selectedIndex = proxyType.coerceIn(0, 1),
                        onSelectedIndexChange = { proxyType = it },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = proxyHost,
                            onValueChange = { proxyHost = it },
                            modifier = Modifier.weight(2f),
                            label = tr("主机"),
                            singleLine = true,
                        )
                        TextField(
                            value = proxyPort,
                            onValueChange = { proxyPort = it.filter { c -> c.isDigit() }.take(5) },
                            modifier = Modifier.weight(1f),
                            label = tr("端口"),
                            singleLine = true,
                        )
                    }
                    AppSwitchPreference(
                        title = tr("认证"),
                        checked = proxyAuth,
                        onCheckedChange = { proxyAuth = it },
                        enabled = authSwitchEnabled,
                    )
                    if (proxyAuth && authSwitchEnabled) {
                        TextField(
                            value = proxyUser,
                            onValueChange = { proxyUser = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            label = tr("用户名"),
                            singleLine = true,
                        )
                        TextField(
                            value = proxyPass,
                            onValueChange = { proxyPass = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            label = tr("密码"),
                            singleLine = true,
                        )
                    }
                    if (proxyAuth && proxyType == 1) {
                        Text(
                            text = tr("SOCKS5 认证由应用内本机中转（127.0.0.1 随机端口）完成，WebView 自身不支持 SOCKS5 认证。"),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    WebViewProxy.lastError?.let { err ->
                        Text(
                            text = err,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppDialogButton(
                            onClick = { showProxyDialog = false },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val port = proxyPort.toIntOrNull() ?: 0
                                settings.updateProxy(
                                    enabled = proxyOn,
                                    type = proxyType,
                                    host = proxyHost,
                                    port = port,
                                    // 关闭代理时也保存认证配置，便于下次直接启用
                                    auth = proxyAuth,
                                    user = proxyUser,
                                    pass = proxyPass,
                                )
                                showProxyDialog = false
                                Toast.makeText(
                                    context,
                                    if (proxyOn && proxyHost.isNotBlank() && port in 1..65535) {
                                        tr("代理已（{0}）生效", if (proxyType == 1) "SOCKS5" else "HTTP")
                                    } else {
                                        tr("代理已关闭{0}", if (proxyAuth) tr("（认证配置已保存）") else "")
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                            role = top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary,
                        ) {
                            Text(text = tr("保存"), style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }

            GlassParamsSheet(
                show = showGlassParams && settings.blurMode == BLUR_MIUIX,
                settings = settings,
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showGlassParams = false },
            )

            AppBottomSheet(
                show = showHomeSheet,
                title = tr("定制主页"),
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showHomeSheet = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                    AppDropdownPreference(
                        title = tr("自定义起始页"),
                        items = listOf(tr("默认主页"), tr("新建标签页"), tr("指定页面")),
                        selectedIndex = settings.homeMode.coerceIn(0, 2),
                        onSelectedIndexChange = {
                            // 指定页面要先填网址，确认后才切换模式；其它两项立即生效
                            if (it == HOME_MODE_CUSTOM) showHomeUrlDialog = true else settings.updateHomeMode(it)
                        },
                        backdrop = if (glass) backdrop else null,
                        visuals = popupVisuals,
                        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    )
                    if (settings.homeMode == HOME_MODE_CUSTOM) {
                        SettingsRow(
                            title = tr("指定页面地址"),
                            summary = settings.homeCustomUrl.ifBlank { tr("未设置") },
                            onClick = { showHomeUrlDialog = true },
                        )
                    }
                    AppDropdownPreference(
                        title = tr("LOGO 标识"),
                        items = listOf(tr("显示"), tr("隐藏")),
                        selectedIndex = if (settings.homeShowLogo) 0 else 1,
                        onSelectedIndexChange = { settings.updateHomeShowLogo(it == 0) },
                        backdrop = if (glass) backdrop else null,
                        visuals = popupVisuals,
                        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    )
                    AppDropdownPreference(
                        title = tr("快捷菜单"),
                        items = (1..HOME_QUICK_ROWS_MAX).map { tr("显示{0}行", it) } + tr("不显示"),
                        selectedIndex = if (settings.homeQuickRows == 0) HOME_QUICK_ROWS_MAX else settings.homeQuickRows - 1,
                        onSelectedIndexChange = { settings.updateHomeQuickRows(if (it >= HOME_QUICK_ROWS_MAX) 0 else it + 1) },
                        backdrop = if (glass) backdrop else null,
                        visuals = popupVisuals,
                        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    )
                    AppDropdownPreference(
                        title = tr("个人收藏"),
                        items = (1..HOME_FAV_ROWS_MAX).map { tr("显示{0}行", it) } + tr("不显示"),
                        selectedIndex = if (settings.homeFavRows == 0) HOME_FAV_ROWS_MAX else settings.homeFavRows - 1,
                        onSelectedIndexChange = { settings.updateHomeFavRows(if (it >= HOME_FAV_ROWS_MAX) 0 else it + 1) },
                        backdrop = if (glass) backdrop else null,
                        visuals = popupVisuals,
                        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    )
                    AppDropdownPreference(
                        title = tr("书签分组"),
                        items = (1..HOME_GROUP_ROWS_MAX).map { tr("显示{0}行", it) } + tr("不显示"),
                        selectedIndex = if (settings.homeGroupRows == 0) HOME_GROUP_ROWS_MAX else settings.homeGroupRows - 1,
                        onSelectedIndexChange = { settings.updateHomeGroupRows(if (it >= HOME_GROUP_ROWS_MAX) 0 else it + 1) },
                        backdrop = if (glass) backdrop else null,
                        visuals = popupVisuals,
                        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    )
                    listOf(
                        HOME_PRIVACY to tr("隐私报告"),
                        HOME_RECENT to tr("最近浏览"),
                        HOME_DOWNLOADS to tr("下载项"),
                    ).forEach { (kind, label) ->
                        AppDropdownPreference(
                            title = label,
                            items = listOf(tr("显示"), tr("隐藏")),
                            selectedIndex = if (settings.isHomeShown(kind)) 0 else 1,
                            onSelectedIndexChange = { settings.updateHomeShow(kind, it == 0) },
                            backdrop = if (glass) backdrop else null,
                            visuals = popupVisuals,
                            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    Text(
                        text = tr("在首页长按「隐私报告」「最近浏览」「下载项」卡片可上下拖动排序（个人收藏作为整体，长按「收藏」标题行拖动；快捷菜单每行 6 个，可放底部菜单里的功能按钮（依赖当前网页的功能除外），长按进入编辑，可拖动排序、点 × 移除、点「+」添加，同样长按「快捷菜单」标题行拖动排序；收藏超出所选行数时，最后一格显示「更多」，点击进入书签页；书签分组每行 5 个，点击分组磁贴会在图标下方弹出窗口查看其中的书签与子分组）；长按后卡片右上角会出现 ×，点击可从首页移除；无痕标签页的首页不显示最近浏览。"),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }

            // 自定义起始页 → 指定页面：输入网址
            var homeUrlText by remember(showHomeUrlDialog) { mutableStateOf(settings.homeCustomUrl) }
            AppGlassDialog(
                show = showHomeUrlDialog,
                title = tr("指定页面"),
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showHomeUrlDialog = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = tr("启动应用、点击主页按钮、新建标签页时都会打开这个页面（无痕标签页除外）。请输入完整网址，例如 https://www.baidu.com"),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    TextField(
                        value = homeUrlText,
                        onValueChange = { homeUrlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("网址"),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppDialogButton(
                            onClick = { showHomeUrlDialog = false },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val text = homeUrlText.trim()
                                if (text.isEmpty() || !looksLikeUrl(text)) {
                                    Toast.makeText(context, tr("请输入有效的网址"), Toast.LENGTH_SHORT).show()
                                } else {
                                    settings.updateHomeCustomUrl(text)
                                    settings.updateHomeMode(HOME_MODE_CUSTOM)
                                    showHomeUrlDialog = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            role = top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary,
                        ) {
                            Text(text = tr("保存"), style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }

            AppBottomSheet(
                show = showPermSheet,
                title = tr("网站权限管理"),
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showPermSheet = false },
                scrollContent = true,
            ) {
                val sheetInsets = LocalBottomSheetInsets.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = sheetInsets.top, bottom = sheetInsets.bottom + 28.dp),
                ) {
                    val permOptions = listOf(tr("每次使用时询问"), tr("总是允许"), tr("总是拒绝"))
                    val permBackdrop = if (glass) backdrop else null
                    if (settings.geckoMode) {
                        // Gecko：与 Firefox「网站设置」一致的一组权限
                        PermDropdown(
                            tr("自动播放"), GeckoSitePerms.autoplay,
                            listOf(tr("允许音频和视频"), tr("仅阻止音频"), tr("阻止音频和视频")),
                            { GeckoSitePerms.updateAutoplay(it) }, permBackdrop, popupVisuals,
                        )
                        PermDropdown(tr("摄像头"), settings.cameraPermission, permOptions, settings::updateCameraPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("位置"), settings.locationPermission, permOptions, settings::updateLocationPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("麦克风"), settings.micPermission, permOptions, settings::updateMicPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("通知"), settings.notificationPermission, permOptions, settings::updateNotificationPermission, permBackdrop, popupVisuals)
                        GeckoSitePerms.GLOBAL_ITEMS.forEach { item ->
                            PermDropdown(
                                item.label, GeckoSitePerms.mode(item.key), permOptions,
                                { GeckoSitePerms.setMode(item.key, it) }, permBackdrop, popupVisuals,
                            )
                        }
                        PermDropdown(tr("网页重定向"), settings.redirectPermission, permOptions, settings::updateRedirectPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("打开其他应用"), settings.externalAppPermission, permOptions, settings::updateExternalAppPermission, permBackdrop, popupVisuals)
                        ArrowPreference(
                            title = tr("例外网站"),
                            summary = GeckoSitePerms.hostsFor(GeckoSitePerms.EXCEPTION_ITEMS).let {
                                if (it.isEmpty()) tr("没有例外") else tr("{0} 个网站", it.size)
                            },
                            onClick = {
                                exceptionSite = null
                                showPermSheet = false
                                showPermExceptions = true
                            },
                        )
                    } else {
                        PermDropdown(tr("摄像头"), settings.cameraPermission, permOptions, settings::updateCameraPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("麦克风"), settings.micPermission, permOptions, settings::updateMicPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("定位"), settings.locationPermission, permOptions, settings::updateLocationPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("网站通知"), settings.notificationPermission, permOptions, settings::updateNotificationPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("剪贴板读写"), settings.clipboardPermission, permOptions, settings::updateClipboardPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("网站媒体发声"), settings.mediaAutoplay, permOptions, settings::updateMediaAutoplay, permBackdrop, popupVisuals)
                        PermDropdown(tr("网页重定向"), settings.redirectPermission, permOptions, settings::updateRedirectPermission, permBackdrop, popupVisuals)
                        PermDropdown(tr("打开其他应用"), settings.externalAppPermission, permOptions, settings::updateExternalAppPermission, permBackdrop, popupVisuals)
                        ArrowPreference(
                            title = tr("例外网站"),
                            summary = GeckoSitePerms.hostsFor(GeckoSitePerms.WEBVIEW_ITEMS).let {
                                if (it.isEmpty()) tr("没有例外") else tr("{0} 个网站", it.size)
                            },
                            onClick = {
                                exceptionSite = null
                                showPermSheet = false
                                showPermExceptions = true
                            },
                        )
                    }
                }
            }

            // 例外网站：按站点覆盖上面的全局设置（弹窗里勾选「记住此网站的选择」时产生）
            AppBottomSheet(
                show = showPermExceptions,
                title = exceptionSite ?: tr("例外网站"),
                backdrop = if (glass) backdrop else null,
                onDismissRequest = {
                    if (exceptionSite != null) exceptionSite = null else showPermExceptions = false
                },
                scrollContent = true,
            ) {
                val sheetInsets = LocalBottomSheetInsets.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = sheetInsets.top, bottom = sheetInsets.bottom + 28.dp),
                ) {
                    val site = exceptionSite
                    val permBackdrop = if (glass) backdrop else null
                    // 两个引擎共用一份例外存储，各自只显示 / 修改自己认的项
                    val excItems = if (settings.geckoMode) GeckoSitePerms.EXCEPTION_ITEMS else GeckoSitePerms.WEBVIEW_ITEMS
                    if (site == null) {
                        val hosts = GeckoSitePerms.hostsFor(excItems)
                        if (hosts.isEmpty()) {
                            Text(
                                text = tr("还没有例外网站。网站申请权限时，在弹窗里打开「记住此网站的选择」，它就会出现在这里。"),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        } else {
                            hosts.forEach { host ->
                                ArrowPreference(
                                    title = host,
                                    summary = GeckoSitePerms.summaryOf(host, excItems),
                                    onClick = { exceptionSite = host },
                                )
                            }
                            ArrowPreference(
                                title = tr("清除全部例外"),
                                onClick = { GeckoSitePerms.clearExceptions(excItems) },
                            )
                        }
                    } else {
                        val current = GeckoSitePerms.exceptions[site].orEmpty()
                        val options = listOf(tr("跟随默认"), tr("总是允许"), tr("总是拒绝"))
                        excItems.forEach { item ->
                            PermDropdown(
                                item.label, current[item.key] ?: 0, options,
                                { GeckoSitePerms.setException(site, item.key, it.takeIf { v -> v > 0 }) },
                                permBackdrop, popupVisuals,
                            )
                        }
                        ArrowPreference(
                            title = tr("删除此网站的全部例外"),
                            onClick = {
                                GeckoSitePerms.removeSite(site, excItems)
                                exceptionSite = null
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

/** 壁纸弹窗里的一行：标题统一 16sp、说明统一 12sp，右侧可放开关。 */
@Composable
private fun WallpaperDialogRow(
    title: String,
    onClick: () -> Unit,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    summary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleClip(12.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = titleColor)
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 应用图标选择：六个预览并排一行（图标随宽度自适应缩小），选中项带主题色描边和对勾。 */
@Composable
private fun AppIconPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = tr("应用图标"),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tr("退出应用时更新桌面图标；极简↔极暗、多彩 / 立体→深色 随显示模式自动切换"),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AppIconManager.ICONS.forEachIndexed { index, option ->
                val active = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .squircleClip(14.dp)
                        .clickable { onSelect(index) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .widthIn(max = 52.dp)
                            .aspectRatio(1f)
                            .squircleBorder(
                                width = if (active) 2.dp else 0.dp,
                                color = if (active) MiuixTheme.colorScheme.primary else Color.Transparent,
                                cornerRadius = 15.dp,
                            )
                            .padding(2.5.dp)
                            .squircleClip(12.dp),
                    ) {
                        Image(
                            painter = painterResource(option.preview),
                            contentDescription = option.label,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            Icon(
                                imageVector = MiuixIcons.Basic.Check,
                                contentDescription = tr("已选中"),
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Text(
                            text = option.label,
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (active) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 网站权限弹层里的一行下拉 */
@Composable
private fun PermDropdown(
    label: String,
    current: Int,
    options: List<String>,
    onChange: (Int) -> Unit,
    backdrop: Backdrop?,
    visuals: GlassPopupVisuals,
) {
    AppDropdownPreference(
        title = label,
        items = options,
        selectedIndex = current.coerceIn(0, options.lastIndex),
        onSelectedIndexChange = onChange,
        backdrop = backdrop,
        visuals = visuals,
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** 按内核显示 / 隐藏一行：切换内核时带展开 / 收起动画 */
@Composable
private fun EngineRow(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) { Column { content() } }
}

private fun privacyEnhanceSummary(settings: AppSettings): String {
    val fp = AdBlock.let { listOf(it.fpCanvas, it.fpWebgl, it.fpAudio, it.fpFonts).count { on -> on } }
    val n = (if (settings.sendDnt) 1 else 0) + (if (settings.webrtcDisabled) 1 else 0) + (if (fp > 0) 1 else 0)
    return if (n == 0) tr("禁止跟踪请求 · 指纹保护 · 禁用 WebRTC") else tr("已开启 {0} / 3 项 · 禁止跟踪请求 · 指纹保护 · 禁用 WebRTC", n)
}

@Composable
private fun SettingsRow(title: String, summary: String, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
    )
}

@Composable
internal fun webViewVersion(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
    }
}

private fun passwordSummary(count: Int?): String =
    (if (count != null && count > 0) tr("已保存 {0} 个 · ", count) else "") + tr("CSV 导入 · 加密存储 · 自动填充")

private fun uaSummary(ua: String): String = when {
    ua.isBlank() -> tr("跟随系统")
    ua == DESKTOP_UA -> tr("桌面 Chrome")
    else -> ua.take(48) + if (ua.length > 48) "…" else ""
}

@Composable
private fun SearchSheet(
    show: Boolean,
    current: Int,
    engines: List<dev.dsh.miuixbrowser.data.SearchEngine>,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAddCustom: () -> Unit,
) {
    AppBottomSheet(
        show = show,
        title = tr("搜索引擎"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            engines.forEachIndexed { index, engine ->
                val isCustom = index >= SEARCH_ENGINES.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(index) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = rememberSearchIcon(engine.iconUrl)
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = engine.name,
                        fontSize = 16.sp,
                        color = if (index == current) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (isCustom) {
                        Text(
                            text = tr("删除"),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onDelete(index) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    if (index == current) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = MiuixIcons.Basic.Check,
                            contentDescription = tr("已选中"),
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            // 末尾固定一行「自定义」：点击弹出添加框，保存后固化进上面的引擎列表
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddCustom() }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "+", fontSize = 18.sp, color = MiuixTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = tr("自定义"),
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 添加自定义搜索引擎：引擎名 + 引擎地址（用 %s 代表关键词）；[onSave] 返回 false 表示输入不合法，弹窗保持打开 */
@Composable
private fun AddEngineDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onSave: (name: String, address: String) -> Boolean,
) {
    var name by remember(show) { mutableStateOf("") }
    var address by remember(show) { mutableStateOf("") }
    var error by remember(show) { mutableStateOf(false) }
    AppGlassDialog(
        show = show,
        title = tr("自定义搜索引擎"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = name,
                onValueChange = { name = it; error = false },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = tr("引擎名"),
                singleLine = true,
            )
            TextField(
                value = address,
                onValueChange = { address = it; error = false },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = tr("引擎地址（用 %s 代表关键词）"),
                singleLine = true,
            )
            Text(
                text = if (error) tr("请填写引擎名，地址需包含 %s（例如 https://www.google.com/search?q=%s）") else tr("例如 https://www.google.com/search?q=%s"),
                fontSize = 12.sp,
                color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    cornerRadius = 14.dp,
                    minHeight = 44.dp,
                ) {
                    Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { if (!onSave(name, address)) error = true },
                    modifier = Modifier.weight(1f),
                    cornerRadius = 14.dp,
                    minHeight = 44.dp,
                ) {
                    Text(text = tr("保存"), style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

private fun homeContentSummary(settings: AppSettings): String {
    val shown = buildList {
        if (settings.homeShowFavorites) add(tr("收藏{0}行", settings.homeFavRows))
        if (settings.homeShowGroups) add(tr("书签分组{0}行", settings.homeGroupRows))
        if (settings.homeShowPrivacy) add(tr("隐私报告"))
        if (settings.homeShowRecent) add(tr("最近浏览"))
        if (settings.homeShowDownloads) add(tr("下载项"))
    }
    val mode = when (settings.homeMode) {
        HOME_MODE_NEW_TAB -> tr("主页按钮：新建标签页 · ")
        HOME_MODE_CUSTOM -> tr("指定页面 · ")
        else -> ""
    }
    return mode + if (shown.isEmpty()) (if (settings.homeShowLogo) tr("首页只显示标志") else tr("首页无内容")) else shown.joinToString(" · ")
}
