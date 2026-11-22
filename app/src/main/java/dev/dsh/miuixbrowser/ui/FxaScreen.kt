package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.browser.rememberRemoteIcon
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.findActivity
import dev.dsh.miuixbrowser.sync.fxa.FxaBookmarkBridge
import dev.dsh.miuixbrowser.sync.fxa.FxaGeckoLogin
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.sync.fxa.FxaUi
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 备份与同步 → Firefox帐户同步。未登录时直接进入内嵌的 Mozilla 账号登录页（页面内 WebView，回调由 [FxaSync.interceptRedirect] 截获）。 */
@Composable
fun FxaScreen(
    onBack: () -> Unit,
    currentTitle: String = "",
    currentUrl: String = "",
    onOpenUrl: (String) -> Unit = {},
    blurEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val bd = if (glass) backdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    // Firefox 同步只在打开本页时才初始化（幂等）
    remember { FxaSync.init(context.applicationContext) }
    val ui = FxaSync.ui
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webCanBack by remember { mutableStateOf(false) }
    // 登录页用哪个内核：默认内核是 Gecko（且本包带 Gecko、设备支持）就用 Gecko，否则 WebView
    val useGecko = remember { EngineSupport.defaultIsGecko(context) }
    var geckoLogin by remember { mutableStateOf<FxaGeckoLogin?>(null) }
    // 正在取登录地址：此时不画账号列表，避免「列表 → 登录页」闪一下
    var starting by remember { mutableStateOf(false) }

    fun startLogin() {
        scope.launch {
            starting = true
            val url = FxaSync.beginLogin()
            if (url != null) loginUrl = url
            else Toast.makeText(context, FxaSync.lastMessage ?: tr("无法登录"), Toast.LENGTH_SHORT).show()
            starting = false
        }
    }
    // 回调被截获、登录完成：收起登录页
    DisposableEffect(Unit) {
        FxaSync.onRedirectHandled = { loginUrl = null }
        onDispose {
            FxaSync.onRedirectHandled = null
            FxaSync.cancelLogin()
            webView?.destroy()
            geckoLogin?.close()
        }
    }
    // 未登录时点进来就直接是登录页
    LaunchedEffect(Unit) {
        if (FxaSync.ui is FxaUi.SignedOut || FxaSync.ui is FxaUi.NeedsReauth) startLogin()
    }
    LaunchedEffect(FxaSync.ui) { if (FxaSync.ui is FxaUi.SignedIn) { FxaSync.refreshDevices(); FxaSync.refreshSynced(); FxaSync.syncTabs() } }
    // 列表 sheet 是否打开、打开的是哪一类「已同步内容」；搜索词 / 已加载行数放在页面层，切分类时重置
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetCat by remember { mutableStateOf(FxaSync.Category.Bookmarks) }
    val listState = rememberListSheetState()
    // 打开密码列表（站点 + 用户名）期间禁止截屏 / 录屏，与「密码」页一致
    val secure = sheetOpen && sheetCat == FxaSync.Category.Passwords
    DisposableEffect(secure) {
        val window = if (secure) context.findActivity()?.window else null
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    BackHandler(enabled = loginUrl != null && webCanBack) { if (useGecko) geckoLogin?.goBack() else webView?.goBack() }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = tr("Firefox帐户同步"),
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = { LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack) },
                )
            },
        ) { innerPadding ->
            val url = loginUrl
            val surface = MiuixTheme.colorScheme.surface
            if (url == null && (starting || ui is FxaUi.Authenticating)) {
                // 取登录地址 / 回调已截获、等待登录完成：只画底色，不闪账号列表
                Box(Modifier.fillMaxSize().background(surface))
                return@Scaffold
            }
            if (url != null && useGecko) {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .padding(top = innerPadding.calculateTopPadding()),
                    factory = { ctx ->
                        FxaGeckoLogin(ctx, url, surface.toArgb()) { webCanBack = it }.also { geckoLogin = it }.view
                    },
                )
                return@Scaffold
            }
            if (url != null) {
                // 玻璃顶栏要取样的 backdrop 图层只挂在后面这块纯色底上，不能挂在 WebView 上：
                // WebView 由系统独立硬件合成，被 layerBackdrop 反复录制进图层会整页闪烁（截图还是空白）
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(surface))
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // 页面加载前 WebView 默认白底，深色模式下会白闪：用主题底色
                            setBackgroundColor(surface.toArgb())
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean =
                                    FxaSync.interceptRedirect(r.url.toString())
                                override fun onPageStarted(v: WebView, u: String?, f: android.graphics.Bitmap?) {
                                    if (FxaSync.interceptRedirect(u)) v.stopLoading()
                                }
                                override fun doUpdateVisitedHistory(v: WebView, u: String?, isReload: Boolean) {
                                    webCanBack = v.canGoBack()
                                }
                            }
                            webView = this
                            loadUrl(url)
                        }
                    },
                )
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .pageBackground(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                item { SmallTitle(text = tr("帐户")) }
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                        when (ui) {
                            is FxaUi.Loading -> ArrowPreference(title = tr("正在读取帐户状态…"), onClick = {})
                            is FxaUi.SignedOut, is FxaUi.NeedsReauth, is FxaUi.Authenticating -> ArrowPreference(
                                title = if (ui is FxaUi.NeedsReauth) tr("登录已失效，重新登录") else tr("登录 Mozilla 帐户"),
                                summary = if (ui is FxaUi.Authenticating) tr("正在等待登录完成…") else tr("同步您的Firefox数据"),
                                onClick = { startLogin() },
                            )
                            is FxaUi.SignedIn -> {
                                // ① 帐户信息：左侧头像（自定义头像异步加载，没有则用主色头像图标）
                                ArrowPreference(
                                    title = ui.displayName?.takeIf { it.isNotBlank() } ?: ui.email.ifBlank { tr("已登录") },
                                    summary = ui.email.takeIf { it.isNotBlank() && ui.displayName != null },
                                    startAction = { FxaAvatar(ui.avatarUrl) },
                                    onClick = {},
                                )
                                // ② 立即同步（蓝底）/ 退出登录（红字，底色随高级材质）左右并排
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AppDialogButton(
                                        onClick = { FxaSync.syncNow() },
                                        enabled = !FxaSync.syncing,
                                        modifier = Modifier.weight(1f),
                                        role = GlassDialogButtonRole.Primary,
                                        inPage = true,
                                    ) {
                                        Text(
                                            text = if (FxaSync.syncing) tr("同步中…") else tr("立即同步"),
                                            style = MiuixTheme.textStyles.button,
                                            maxLines = 1,
                                        )
                                    }
                                    AppDialogButton(
                                        onClick = { FxaSync.logout() },
                                        modifier = Modifier.weight(1f),
                                        inPage = true,
                                    ) {
                                        Text(
                                            text = tr("退出登录"),
                                            style = MiuixTheme.textStyles.button,
                                            color = MiuixTheme.colorScheme.error,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                // ③ 自动同步
                                AppDropdownPreference(
                                    backdrop = bd,
                                    title = tr("自动同步"),
                                    items = FxaSync.AUTO_LABELS,
                                    selectedIndex = FxaSync.autoMode.coerceIn(0, FxaSync.AUTO_LABELS.lastIndex),
                                    onSelectedIndexChange = { FxaSync.updateAutoMode(it) },
                                )
                            }
                        }
                    }
                }
                // ④ 帐户栏 summary：同步状态 + 上次成功同步时间
                if (ui is FxaUi.SignedIn) {
                    item {
                        val status = when {
                            FxaSync.syncing -> tr("同步中…")
                            FxaSync.lastMessage != null -> FxaSync.lastMessage!!
                            FxaSync.lastSuccess > 0 -> tr("已同步")
                            else -> tr("尚未同步")
                        }
                        val last = if (FxaSync.lastSuccess > 0) {
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(FxaSync.lastSuccess))
                        } else tr("暂无")
                        FxaHintText(tr("同步状态：{0}\n上次成功同步：{1}", status, last))
                    }
                }
                // ⑤ 同步高级选项
                if (ui is FxaUi.SignedIn) {
                    item { SmallTitle(text = tr("同步高级选项")) }
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                            AppSwitchPreference(
                                title = tr("绝对双向同步"),
                                summary = tr("开启后，如在本地误删书签会有云端遗失的风险，请备份后谨慎开启。"),
                                checked = FxaSync.absoluteSync,
                                onCheckedChange = { FxaSync.updateAbsoluteSync(it) },
                            )
                        }
                    }
                }
                if (ui is FxaUi.SignedIn) {
                    item { SmallTitle(text = tr("已同步的内容")) }
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                            FxaSync.Category.entries.forEach { cat ->
                                val g = FxaSync.synced[cat]
                                val canOpen = g != null && g.count > 0
                                ArrowPreference(
                                    title = cat.label,
                                    summary = when {
                                        g == null -> tr("读取中…")
                                        g.count == 0 -> tr("暂无")
                                        else -> tr("共 {0} 项，点击查看", g.count)
                                    },
                                    enabled = canOpen,
                                    onClick = { listState.reset(); sheetCat = cat; sheetOpen = true },
                                )
                            }
                        }
                    }
                    if (FxaSync.receivedTabs.isNotEmpty()) {
                        item { SmallTitle(text = tr("收到的标签页")) }
                        item {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                                FxaSync.receivedTabs.forEach { t ->
                                    ArrowPreference(
                                        title = t.title.ifBlank { t.url },
                                        summary = tr("来自 {0}", t.from),
                                        onClick = { FxaSync.dismissReceived(t); onOpenUrl(t.url) },
                                    )
                                }
                            }
                        }
                    }
                    if (FxaSync.devices.isNotEmpty() && currentUrl.startsWith("http")) {
                        item { SmallTitle(text = tr("发送当前页到…")) }
                        item {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                                FxaSync.devices.forEach { d ->
                                    ArrowPreference(
                                        title = d.name,
                                        summary = currentTitle.ifBlank { currentUrl },
                                        onClick = {
                                            FxaSync.sendTab(d, currentTitle, currentUrl)
                                            Toast.makeText(context, tr("正在发送…"), Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    FxaHintText(
                        tr("同步书签、历史记录、标签页和密码，使用 Firefox 官方 Sync 协议，账号数据端到端加密。\n") +
                            tr("书签和历史记录只增不删：Firefox 中的书签会出现在「{0}」分组，本地书签会推送到「移动设备书签」。\n", FxaBookmarkBridge.PULL_ROOT) +
                            tr("书签和历史的删除、重命名暂不互相同步，推送的历史访问时间会记为同步时刻。\n") +
                            (if (FxaSync.absoluteSync) tr("「绝对双向同步」已开启：书签、历史记录的删除会在本机和云端之间互相传递，密码本就双向同步（刚开启的第一轮只记录状态，不会删除）；重命名、移动仍不同步。\n") else "") +
                            tr("密码双向同步（含删除和修改，冲突时以密码修改时间较新者为准）：本机密码仍只存放在「设置 → 隐私与安全 → 密码」的加密库里，") +
                            tr("这里只列出站点和用户名，不显示密码；备注仅保存在本机，不会同步。退出登录会清空同步用的中转库，本机密码不受影响。"),
                    )
                }
            }

            // 已同步内容列表：打开才读取；搜索防抖 + 后台过滤；每页 50 条，点底部「更多」继续
            val cat = sheetCat
            // 本次打开期间缓存已读出的条目（搜索 / 加载更多不重复读）；sheet 关闭后随 remember 的 key 变化丢弃
            val cache = remember(sheetOpen, sheetCat) { arrayOfNulls<List<FxaSync.SyncedItem>>(1) }
            SearchableListSheet<FxaSync.SyncedItem>(
                show = sheetOpen,
                title = cat.label,
                state = listState,
                backdrop = bd,
                onDismiss = { sheetOpen = false },
                searchHint = tr("搜索{0}", cat.label),
                emptyText = tr("暂无"),
                reloadKey = cat,
                load = { q, limit ->
                    if (cat == FxaSync.Category.Passwords) {
                        FxaSync.passwordsPage(q, limit)
                    } else {
                        val all = cache[0] ?: FxaSync.loadItems(cat).also { cache[0] = it }
                        filterPage(all, q, limit) { e, query ->
                            e.title.lowercase().contains(query) || (e.summary?.lowercase()?.contains(query) == true)
                        }
                    }
                },
            ) { entry ->
                ListSheetRow(
                    title = entry.title,
                    summary = entry.summary,
                    onClick = {
                        entry.url?.let {
                            sheetOpen = false
                            onOpenUrl(it)
                        }
                    },
                )
            }
        }
    }
}

/** 帐户行左侧的头像：设置过自定义头像（https）则圆形显示，加载完成前 / 默认头像时用主色头像图标。 */
@Composable
private fun FxaAvatar(avatarUrl: String?) {
    val url = avatarUrl?.takeIf { it.startsWith("https://") }.orEmpty()
    val bitmap = rememberRemoteIcon(url)
    Box(Modifier.padding(end = 12.dp).size(40.dp), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = tr("Firefox 帐户头像"),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = AppIcons.Account,
                contentDescription = tr("Firefox 帐户头像"),
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** 页面底部说明文字：与拓展页等其它页面的说明文字同一规格（13sp、次级文字色、左右 24dp）。 */
@Composable
private fun FxaHintText(text: String) {
    top.yukonga.miuix.kmp.basic.Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}
