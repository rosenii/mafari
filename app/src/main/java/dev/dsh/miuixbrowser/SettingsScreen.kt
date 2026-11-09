package dev.dsh.miuixbrowser

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onApplyUserAgent: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenScripts: () -> Unit,
    onOpenGeckoExtensions: () -> Unit = {},
    onOpenGeckoProtection: () -> Unit = {},
    onOpenAdBlock: () -> Unit,
    onOpenFingerprint: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenAbout: () -> Unit,
    onPageEnhancementsChange: (Boolean) -> Unit = {},
    blurEnabled: Boolean = true,
) {
    val context = LocalContext.current
    var showUa by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showWallpaper by remember { mutableStateOf(false) }
    var showPermSheet by remember { mutableStateOf(false) }
    var showHomeSheet by remember { mutableStateOf(false) }
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
    val popupVisuals = GlassPopupDefaults.visuals()
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = "设置",
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
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(top = topInset)
                    // 沉浸：内容可滚到手势小白条下方，仅在末尾留出小白条高度
                    .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                SmallTitle(text = "外观")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "显示模式",
                        items = listOf("跟随系统", "日间", "夜间"),
                        selectedIndex = settings.themeMode.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateThemeMode(it) },
                    )
                    SettingsRow(
                        title = "主页壁纸",
                        summary = if (settings.wallpaper.isBlank()) "未设置" else "已设置",
                        onClick = { showWallpaper = true },
                    )
                    AppIconPicker(
                        selected = settings.appIcon,
                        onSelect = {
                            if (it != settings.appIcon) {
                                settings.updateAppIcon(it)
                                Toast.makeText(context, "退出应用后桌面图标将更新", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "高级材质",
                        summary = "开启后提供精美组件效果",
                        items = listOf("miuix-glass", "liquid glass", "关闭模糊"),
                        selectedIndex = settings.blurMode.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateBlurMode(it) },
                    )
                }

                SmallTitle(text = "布局")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "地址栏显示",
                        items = listOf("网站标题", "主域名", "完整域名"),
                        selectedIndex = settings.addressDisplay.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateAddressDisplay(it) },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "标签栏样式",
                        items = listOf("文字显示", "卡片显示"),
                        selectedIndex = settings.tabsStyle.coerceIn(0, 1),
                        onSelectedIndexChange = { settings.updateTabsStyle(it) },
                    )
                    SettingsRow(
                        title = "首页内容显示",
                        summary = homeContentSummary(settings),
                        onClick = { showHomeSheet = true },
                    )
                    AppSwitchPreference(
                        title = "同站链接新标签页打开",
                        summary = "网页内同站的新窗口链接（target=_blank）在新标签页打开；关闭则在当前标签页打开",
                        checked = settings.sameSiteNewTab,
                        onCheckedChange = { settings.updateSameSiteNewTab(it) },
                    )
                }

                SmallTitle(text = "浏览")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "外部链接打开方式",
                        items = listOf("应用内打开", "自定义标签页", "其他浏览器"),
                        selectedIndex = settings.linkOpenMode.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateLinkOpenMode(it) },
                    )
                    SettingsRow(
                        title = "搜索引擎",
                        summary = SEARCH_ENGINES.getOrNull(settings.searchIndex)?.name ?: "自定义",
                        onClick = { showSearch = true },
                    )
                    SettingsRow(
                        title = "书签与历史",
                        summary = "书签分组、浏览记录",
                        onClick = onOpenBookmarks,
                    )
                    SettingsRow(
                        title = "UA 标识管理",
                        summary = uaSummary(settings.userAgent),
                        onClick = { showUa = true },
                    )
                    SettingsRow(
                        title = "下载",
                        summary = "查看已下载的文件",
                        onClick = onOpenDownloads,
                    )
                }

                SmallTitle(text = "隐私与安全")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    // Cookie 直接外部 dropdown，不再进 sheet
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "Cookie 管理",
                        items = listOf("允许第三方 Cookie", "拒绝第三方 Cookie"),
                        selectedIndex = settings.thirdPartyCookie.coerceIn(0, 1),
                        onSelectedIndexChange = { settings.updateThirdPartyCookie(it) },
                    )
                    SettingsRow(
                        title = "网站权限管理",
                        summary = "摄像头 · 麦克风 · 定位 · 网站通知 · 剪贴板 · 媒体发声 · 网页重定向",
                        onClick = { showPermSheet = true },
                    )
                    SettingsRow(
                        title = "指纹保护",
                        summary = AdBlock.let { ab ->
                            val n = listOf(ab.fpCanvas, ab.fpWebgl, ab.fpAudio, ab.fpFonts).count { it }
                            if (n == 0) "已关闭 · Canvas · WebGL · Audio · Fonts" else "已开启 $n / 4 项 · Canvas · WebGL · Audio · Fonts"
                        },
                        onClick = onOpenFingerprint,
                    )
                    AppSwitchPreference(
                        title = "发送禁止跟踪请求",
                        summary = "地址栏与链接导航附带 DNT / Sec-GPC 头，并设置 navigator.doNotTrack",
                        checked = settings.sendDnt,
                        onCheckedChange = { settings.updateSendDnt(it) },
                    )
                    AppSwitchPreference(
                        title = "禁用 WebRTC",
                        summary = "移除 RTCPeerConnection 等接口，防止泄露真实 IP（刷新页面后生效）",
                        checked = settings.webrtcDisabled,
                        onCheckedChange = { settings.updateWebrtcDisabled(it) },
                    )
                }

                SmallTitle(text = "高级")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "浏览器内核",
                        summary = if (settings.browserEngine == ENGINE_GECKO) {
                            "Gecko：新建标签页生效，已打开的标签保持原内核 · 脚本 / 去广告暂不可用，可用扩展替代"
                        } else {
                            "系统 WebView：新建标签页生效，已打开的标签保持原内核"
                        },
                        items = listOf("WebView（系统）", "GeckoView（Nightly）"),
                        selectedIndex = settings.browserEngine.coerceIn(0, 1),
                        onSelectedIndexChange = { index ->
                            if (index == ENGINE_GECKO && !GeckoRuntimeHolder.supported()) {
                                Toast.makeText(context, "当前设备不是 arm64-v8a，无法使用 Gecko 内核", Toast.LENGTH_SHORT).show()
                            } else if (index != settings.browserEngine) {
                                settings.updateBrowserEngine(index)
                                Toast.makeText(
                                    context,
                                    "新建的标签页将使用 " + (if (index == ENGINE_GECKO) "Gecko" else "WebView") + " 内核",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                    // 拓展栏：切到 Gecko 自动展开，切回 WebView 收起（WebExtension 只有 Gecko 才有）
                    AnimatedVisibility(
                        visible = settings.geckoMode,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        SettingsRow(
                            title = "拓展",
                            summary = "Firefox 扩展：搜索 / 导入 / 管理 · 可用 uBlock Origin、Violentmonkey 等替代去广告与脚本",
                            onClick = onOpenGeckoExtensions,
                        )
                    }
                    AnimatedVisibility(
                        visible = settings.geckoMode,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        SettingsRow(
                            title = "Gecko 高级保护",
                            summary = GeckoProtection.activeCount().let {
                                if (it == 0) "反追踪 · 指纹保护 · 安全 DNS · HTTPS-Only"
                                else "已开启 $it 项 · 反追踪 · 指纹保护 · 安全 DNS"
                            },
                            onClick = onOpenGeckoProtection,
                        )
                    }
                    // Gecko 下这三项依赖 WebView 专有 API（脚本注入 / 请求拦截），置灰；改用拓展栏里的扩展替代
                    GeckoLocked(locked = settings.geckoMode) {
                        AppSwitchPreference(
                            title = "网页增强",
                            summary = "总开关：同步启用 / 停用全部油猴脚本和去广告；关闭后也不再检测长按标记广告",
                            checked = settings.pageEnhancements,
                            onCheckedChange = {
                                settings.updatePageEnhancements(it)
                                onPageEnhancementsChange(it)
                                AdBlock.updateEnabled(it)
                            },
                        )
                    }
                    GeckoLocked(locked = settings.geckoMode) {
                        SettingsRow(
                            title = "去广告",
                            summary = if (AdBlock.enabled) {
                                "已开启 · " + AdBlock.subs.count { it.enabled } + " 个订阅 · 订阅源导入、自定义规则"
                            } else {
                                "已关闭"
                            },
                            onClick = onOpenAdBlock,
                        )
                    }
                    GeckoLocked(locked = settings.geckoMode) {
                        SettingsRow(
                            title = "脚本管理",
                            summary = "用户脚本 / 油猴脚本",
                            onClick = onOpenScripts,
                        )
                    }
                    SettingsRow(
                        title = "代理",
                        summary = if (settings.proxyEnabled && settings.proxyHost.isNotBlank()) {
                            val kind = if (settings.proxyType == 1) "SOCKS5" else "HTTP"
                            val auth = if (settings.proxyAuth) " · 认证" else ""
                            "已开启 · $kind ${settings.proxyHost}:${settings.proxyPort}$auth"
                        } else {
                            "关闭 · 支持 HTTP / SOCKS5"
                        },
                        onClick = { showProxyDialog = true },
                    )
                    SettingsRow(
                        title = "清理缓存",
                        summary = "每次冷启动自动清理 · 同系统「清除缓存」范围，保留未完成的下载 · 当前可清理 " +
                            android.text.format.Formatter.formatFileSize(context, reclaimable),
                        onClick = {
                            scope.launch {
                                // WebView 网页缓存要在主线程清；其余文件在 IO 线程删
                                val clearWeb: () -> Unit = {
                                    runCatching { WebView(context.applicationContext).apply { clearCache(true); destroy() } }
                                    GeckoRuntimeHolder.clearCaches()
                                }
                                val freed = withContext(Dispatchers.IO) {
                                    CacheCleaner.cleanNow(context) { runBlocking(Dispatchers.Main) { clearWeb() } }
                                }
                                reclaimable = withContext(Dispatchers.IO) { CacheCleaner.reclaimableBytes(context) }
                                Toast.makeText(
                                    context,
                                    if (freed > 0) "已清理 " + android.text.format.Formatter.formatFileSize(context, freed) else "没有可清理的缓存",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }

                SmallTitle(text = "关于")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    SettingsRow(
                        title = "关于 Mafari",
                        summary = "版本 " + BuildConfig.VERSION_NAME,
                        onClick = onOpenAbout,
                    )
                }
            }

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
                custom = settings.customSearch,
                backdrop = if (glass) backdrop else null,
                onDismiss = { showSearch = false },
                onPick = {
                    settings.updateSearch(it)
                    showSearch = false
                },
                onCustom = {
                    settings.updateCustomSearch(it)
                    showSearch = false
                },
            )

            AppGlassDialog(
                show = showWallpaper,
                title = "主页壁纸",
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showWallpaper = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    WallpaperDialogRow(
                        title = "选择壁纸",
                        onClick = {
                            showWallpaper = false
                            picker.launch(arrayOf("image/*"))
                        },
                    )
                    WallpaperDialogRow(
                        title = "清除壁纸",
                        titleColor = MiuixTheme.colorScheme.error,
                        onClick = {
                            showWallpaper = false
                            settings.updateWallpaper("")
                        },
                    )
                    WallpaperDialogRow(
                        title = "模糊壁纸",
                        summary = "主页壁纸做高斯模糊，减少干扰",
                        onClick = { settings.updateWallpaperBlur(!settings.wallpaperBlur) },
                    ) {
                        AppSwitch(
                            checked = settings.wallpaperBlur,
                            onCheckedChange = { settings.updateWallpaperBlur(it) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    AppDialogButton(
                        onClick = { showWallpaper = false },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        minHeight = 44.dp,
                    ) {
                        Text(text = "关闭", fontSize = 16.sp, style = MiuixTheme.textStyles.button)
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
                title = "代理",
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showProxyDialog = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AppSwitchPreference(
                        title = "启用代理",
                        checked = proxyOn,
                        onCheckedChange = { proxyOn = it },
                    )
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "类型",
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
                            label = "主机",
                            singleLine = true,
                        )
                        TextField(
                            value = proxyPort,
                            onValueChange = { proxyPort = it.filter { c -> c.isDigit() }.take(5) },
                            modifier = Modifier.weight(1f),
                            label = "端口",
                            singleLine = true,
                        )
                    }
                    AppSwitchPreference(
                        title = "认证",
                        checked = proxyAuth,
                        onCheckedChange = { proxyAuth = it },
                        enabled = authSwitchEnabled,
                    )
                    if (proxyAuth && authSwitchEnabled) {
                        TextField(
                            value = proxyUser,
                            onValueChange = { proxyUser = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            label = "用户名",
                            singleLine = true,
                        )
                        TextField(
                            value = proxyPass,
                            onValueChange = { proxyPass = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            label = "密码",
                            singleLine = true,
                        )
                    }
                    if (proxyAuth && proxyType == 1) {
                        Text(
                            text = "SOCKS5 认证由应用内本机中转（127.0.0.1 随机端口）完成，WebView 自身不支持 SOCKS5 认证。",
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
                            Text(text = "取消", style = MiuixTheme.textStyles.button)
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
                                        "代理已${if (proxyType == 1) "（SOCKS5）" else "（HTTP）"}生效"
                                    } else {
                                        "代理已关闭${if (proxyAuth) "（认证配置已保存）" else ""}"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                            role = top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary,
                        ) {
                            Text(text = "保存", style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }

            AppBottomSheet(
                show = showHomeSheet,
                title = "首页内容显示",
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showHomeSheet = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                    listOf(
                        HOME_FAVORITES to "个人收藏",
                        HOME_PRIVACY to "隐私报告",
                        HOME_RECENT to "最近浏览",
                        HOME_DOWNLOADS to "下载项",
                    ).forEach { (kind, label) ->
                        AppDropdownPreference(
                            title = label,
                            items = listOf("显示", "隐藏"),
                            selectedIndex = if (settings.isHomeShown(kind)) 0 else 1,
                            onSelectedIndexChange = { settings.updateHomeShow(kind, it == 0) },
                            backdrop = if (glass) backdrop else null,
                            visuals = popupVisuals,
                            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    Text(
                        text = "在首页长按「隐私报告」「最近浏览」「下载项」卡片可上下拖动排序（个人收藏作为整体，长按「收藏」标题行拖动）；长按后卡片右上角会出现 ×，点击可从首页移除；无痕标签页的首页不显示最近浏览。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }

            AppBottomSheet(
                show = showPermSheet,
                title = "网站权限管理",
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { showPermSheet = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                    val permOptions = listOf("每次使用时询问", "总是允许", "总是拒绝")
                    listOf(
                        Triple("摄像头", settings.cameraPermission, settings::updateCameraPermission),
                        Triple("麦克风", settings.micPermission, settings::updateMicPermission),
                        Triple("定位", settings.locationPermission, settings::updateLocationPermission),
                        Triple("网站通知", settings.notificationPermission, settings::updateNotificationPermission),
                        Triple("剪贴板读写", settings.clipboardPermission, settings::updateClipboardPermission),
                        Triple("网站媒体发声", settings.mediaAutoplay, settings::updateMediaAutoplay),
                        Triple("网页重定向", settings.redirectPermission, settings::updateRedirectPermission),
                    ).forEach { (label, current, update) ->
                        // 这三项在 WebView 里靠注入 JS 实现，Gecko 走自己的权限系统：置灰
                        GeckoLocked(locked = settings.geckoMode && label in GECKO_UNSUPPORTED_PERMISSIONS) {
                            AppDropdownPreference(
                                title = label,
                                items = permOptions,
                                selectedIndex = current.coerceIn(0, 2),
                                onSelectedIndexChange = { update(it) },
                                backdrop = if (glass) backdrop else null,
                                visuals = popupVisuals,
                                insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
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
            .clip(RoundedCornerShape(12.dp))
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

/** 应用图标选择：四个预览并排，选中项带主题色描边和对勾。 */
@Composable
private fun AppIconPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = "应用图标",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "切换后在退出应用时更新桌面图标",
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
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .border(
                                width = if (active) 2.dp else 0.dp,
                                color = if (active) MiuixTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(18.dp),
                            )
                            .padding(3.dp)
                            .clip(RoundedCornerShape(15.dp)),
                    ) {
                        Image(
                            painter = painterResource(option.preview),
                            contentDescription = option.label,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            Icon(
                                imageVector = MiuixIcons.Basic.Check,
                                contentDescription = "已选中",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = option.label,
                            fontSize = 13.sp,
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

/** Gecko 下不适用的网站权限项（WebView 靠注入 JS 实现） */
private val GECKO_UNSUPPORTED_PERMISSIONS = setOf("网站通知", "剪贴板读写", "网站媒体发声")

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

private fun uaSummary(ua: String): String = when {
    ua.isBlank() -> "跟随系统"
    ua == DESKTOP_UA -> "桌面 Chrome"
    else -> ua.take(48) + if (ua.length > 48) "…" else ""
}

@Composable
private fun SearchSheet(
    show: Boolean,
    current: Int,
    custom: String,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onCustom: (String) -> Unit,
) {
    var text by remember(show) { mutableStateOf(custom) }
    AppBottomSheet(
        show = show,
        title = "搜索引擎",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            SEARCH_ENGINES.forEachIndexed { index, engine ->
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
                    if (index == current) {
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
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = "自定义（用 %s 代表关键词）",
                singleLine = true,
            )
            Spacer(Modifier.height(14.dp))
            AppTextButton(
                text = "使用自定义",
                onClick = { onCustom(text) },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
            )
        }
    }
}

private fun homeContentSummary(settings: AppSettings): String {
    val shown = buildList {
        if (settings.homeShowFavorites) add("收藏")
        if (settings.homeShowPrivacy) add("隐私报告")
        if (settings.homeShowRecent) add("最近浏览")
        if (settings.homeShowDownloads) add("下载项")
    }
    return if (shown.isEmpty()) "首页只显示标志" else shown.joinToString(" · ")
}
