package dev.dsh.miuixbrowser

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

/** 备份与同步 → Firefox账户同步。未登录时直接进入内嵌的 Mozilla 账号登录页（页面内 WebView，回调由 [FxaSync.interceptRedirect] 截获）。 */
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
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    val ui = FxaSync.ui
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webCanBack by remember { mutableStateOf(false) }

    fun startLogin() {
        scope.launch {
            val url = FxaSync.beginLogin()
            if (url != null) loginUrl = url
            else Toast.makeText(context, FxaSync.lastMessage ?: "无法登录", Toast.LENGTH_SHORT).show()
        }
    }
    // 回调被截获、登录完成：收起登录页
    DisposableEffect(Unit) {
        FxaSync.onRedirectHandled = { loginUrl = null }
        onDispose {
            FxaSync.onRedirectHandled = null
            FxaSync.cancelLogin()
            webView?.destroy()
        }
    }
    // 未登录时点进来就直接是登录页
    LaunchedEffect(Unit) {
        if (FxaSync.ui is FxaUi.SignedOut || FxaSync.ui is FxaUi.NeedsReauth) startLogin()
    }
    LaunchedEffect(FxaSync.ui) { if (FxaSync.ui is FxaUi.SignedIn) FxaSync.refreshDevices() }
    BackHandler(enabled = loginUrl != null && webCanBack) { webView?.goBack() }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = "Firefox账户同步",
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = { LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack) },
                )
            },
        ) { innerPadding ->
            val url = loginUrl
            if (url != null) {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .padding(top = innerPadding.calculateTopPadding()),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
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
                    .background(MiuixTheme.colorScheme.surface),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                item { SmallTitle(text = "账号") }
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                        when (ui) {
                            is FxaUi.Loading -> ArrowPreference(title = "正在读取账号状态…", onClick = {})
                            is FxaUi.SignedOut, is FxaUi.NeedsReauth, is FxaUi.Authenticating -> ArrowPreference(
                                title = if (ui is FxaUi.NeedsReauth) "登录已失效，重新登录" else "登录 Mozilla 账号",
                                summary = if (ui is FxaUi.Authenticating) "正在等待登录完成…" else "同步您的Firefox数据",
                                onClick = { startLogin() },
                            )
                            is FxaUi.SignedIn -> {
                                ArrowPreference(
                                    title = ui.displayName?.takeIf { it.isNotBlank() } ?: ui.email.ifBlank { "已登录" },
                                    summary = ui.email.takeIf { it.isNotBlank() && ui.displayName != null },
                                    onClick = {},
                                )
                                ArrowPreference(
                                    title = "立即同步",
                                    summary = if (FxaSync.syncing) "同步中…" else FxaSync.lastMessage ?: "书签 · 历史 · 标签页",
                                    enabled = !FxaSync.syncing,
                                    onClick = { FxaSync.syncNow() },
                                )
                                ArrowPreference(title = "退出登录", onClick = { FxaSync.logout() })
                            }
                        }
                    }
                }
                if (ui is FxaUi.SignedIn) {
                    if (FxaSync.receivedTabs.isNotEmpty()) {
                        item { SmallTitle(text = "收到的标签页") }
                        item {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                                FxaSync.receivedTabs.forEach { t ->
                                    ArrowPreference(
                                        title = t.title.ifBlank { t.url },
                                        summary = "来自 ${t.from}",
                                        onClick = { FxaSync.dismissReceived(t); onOpenUrl(t.url) },
                                    )
                                }
                            }
                        }
                    }
                    if (FxaSync.devices.isNotEmpty() && currentUrl.startsWith("http")) {
                        item { SmallTitle(text = "发送当前页到…") }
                        item {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                                FxaSync.devices.forEach { d ->
                                    ArrowPreference(
                                        title = d.name,
                                        summary = currentTitle.ifBlank { currentUrl },
                                        onClick = {
                                            FxaSync.sendTab(d, currentTitle, currentUrl)
                                            Toast.makeText(context, "正在发送…", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    FxaSync.remoteDevices.filter { it.tabs.isNotEmpty() }.forEach { dev ->
                        item { SmallTitle(text = "其他设备的标签页") }
                        item {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                                dev.tabs.take(30).forEach { t ->
                                    ArrowPreference(title = t.title, summary = t.url, onClick = { onOpenUrl(t.url) })
                                }
                            }
                        }
                    }
                }
                item {
                    FxaHintText(
                        "同步书签、历史记录和标签页，使用 Firefox 官方 Sync 协议，账号数据端到端加密。\n" +
                            "书签和历史记录只增不删：Firefox 中的书签会出现在「${FxaBookmarkBridge.PULL_ROOT}」分组，本地书签会推送到「移动设备书签」。\n" +
                            "删除和重命名暂不互相同步，推送的历史访问时间会记为同步时刻。",
                    )
                }
            }
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
