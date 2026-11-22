package dev.dsh.miuixbrowser.ui

import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.engine.webview.WebViewDebug
import dev.dsh.miuixbrowser.engine.webview.WebViewNetSavings
import dev.dsh.miuixbrowser.engine.webview.WebViewPerf
import dev.dsh.miuixbrowser.engine.webview.WebViewSpeculative
import dev.dsh.miuixbrowser.util.hostOf
import dev.dsh.miuixbrowser.util.Diag
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 高级 → WebView 调试（WebView 内核下显示）。
 * 调试项默认全部关闭，排查完建议关掉：日志每条都是一次文件写入。
 */
@Composable
fun WebViewDebugScreen(onBack: () -> Unit, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        WebViewDebug.load(context)
        WebViewSpeculative.load(context)
    }
    val d = WebViewDebug

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    // 日志开关 / 清空后刷新信息（日志文件大小会变）
    var refresh by remember { mutableIntStateOf(0) }
    val info = remember(refresh, Diag.enabledState) { WebViewDebug.infoLines(context) }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = tr("WebView 调试"),
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = {
                        LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .scrollEndHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    item { SmallTitle(text = tr("内核信息")) }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                info.forEach { (label, value) ->
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Text(
                                            text = label,
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.width(76.dp),
                                        )
                                        Text(
                                            text = value,
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { SmallTitle(text = tr("页面性能")) }
                    item {
                        WebViewDebugGroup {
                            AppSwitchPreference(
                                title = tr("记录页面性能"),
                                summary = tr("记录每次加载的首次内容绘制（FCP）和最大内容绘制（LCP）；关闭后不再采集，已有记录保留。需要系统 WebView 支持导航监听"),
                                checked = d.perfRecord,
                                onCheckedChange = { d.updatePerfRecord(it) },
                            )
                        }
                    }
                    item { PerfCard() }
                    item {
                        WebViewDebugGroup {
                            ArrowPreference(
                                title = tr("清空性能记录"),
                                summary = tr("只清内存里的记录；无痕标签不会被记录"),
                                onClick = { WebViewPerf.clear() },
                            )
                        }
                    }

                    item { SmallTitle(text = tr("加速")) }
                    item {
                        WebViewDebugGroup {
                            AppSwitchPreference(
                                title = tr("预连接"),
                                summary = tr("进入地址栏、输入完整网址或按下主页快捷方式时，提前完成 DNS / TLS 握手，缩短首次加载；只建连接不发请求。需要系统 WebView 支持"),
                                checked = WebViewSpeculative.enabled,
                                onCheckedChange = { WebViewSpeculative.updateEnabled(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = tr("日志")) }
                    item {
                        WebViewDebugGroup {
                            AppSwitchPreference(
                                title = tr("运行日志"),
                                summary = tr("记录启动、标签、去广告等运行信息到 Android/media/<包名>/diag.txt；每条都是一次文件写入，排查完请关闭。与 Gecko 调试共用同一开关"),
                                checked = Diag.enabledState,
                                onCheckedChange = {
                                    Diag.setPersisted(context, it)
                                    refresh++
                                },
                            )
                            AppSwitchPreference(
                                title = tr("控制台日志"),
                                summary = tr("把网页 console 输出和 JavaScript 报错写进运行日志（需开启运行日志）"),
                                checked = d.consoleLog,
                                onCheckedChange = { d.updateConsoleLog(it) },
                                enabled = Diag.enabledState,
                            )
                            AppSwitchPreference(
                                title = tr("页面加载日志"),
                                summary = tr("记录页面开始 / 完成加载、加载失败，以及返回 4xx / 5xx 的请求（需开启运行日志）"),
                                checked = d.pageLog,
                                onCheckedChange = { d.updatePageLog(it) },
                                enabled = Diag.enabledState,
                            )
                            ArrowPreference(
                                title = tr("复制日志路径"),
                                summary = tr("把 diag.txt 的完整路径复制到剪贴板，用文件管理器打开"),
                                onClick = {
                                    val path = Diag.logFile(context).absolutePath
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("diag", path))
                                    Toast.makeText(context, tr("已复制"), Toast.LENGTH_SHORT).show()
                                },
                            )
                            ArrowPreference(
                                title = tr("清空日志"),
                                summary = tr("清空 diag.txt 的内容，不改变开关"),
                                onClick = {
                                    Diag.clear(context)
                                    refresh++
                                    Toast.makeText(context, tr("已清空"), Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }

                    item { SmallTitle(text = tr("调试")) }
                    item {
                        WebViewDebugGroup {
                            AppSwitchPreference(
                                title = tr("USB 远程调试"),
                                summary = tr("允许电脑 Chrome 通过 USB 连接，在 chrome://inspect 里检查本应用的网页（元素 / 控制台 / 网络），立即生效"),
                                checked = d.remoteDebug,
                                onCheckedChange = { d.updateRemoteDebug(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = tr("维护")) }
                    item {
                        WebViewDebugGroup {
                            if (WebViewNetSavings.isCacheQuotaSupported()) {
                                var quotaSel by remember { mutableIntStateOf(WebViewNetSavings.selectedIndex()) }
                                val autoLabel = WebViewNetSavings.defaultQuotaBytes()
                                    ?.let { tr("自动（约 {0}）", android.text.format.Formatter.formatShortFileSize(context, it)) }
                                    ?: tr("自动")
                                val choices = listOf(autoLabel) + WebViewNetSavings.QUOTA_CHOICES_MB.map { "$it MB" }
                                AppDropdownPreference(
                                    backdrop = if (glass) backdrop else null,
                                    title = tr("网页缓存上限"),
                                    summary = tr("只限制 WebView 网页缓存的最大占用，不会立即清空；调小可能在后台触发清理。清空请用「清除 WebView 网页缓存」"),
                                    items = choices,
                                    selectedIndex = quotaSel.coerceIn(0, choices.lastIndex),
                                    onSelectedIndexChange = { idx ->
                                        if (WebViewNetSavings.select(idx)) quotaSel = idx
                                    },
                                )
                            }
                            ArrowPreference(
                                title = tr("清除 WebView 网页缓存"),
                                summary = tr("只清 HTTP 缓存，不影响 Cookie、登录状态和站点数据"),
                                onClick = {
                                    WebViewDebug.clearWebCache(context)
                                    Toast.makeText(context, tr("已清除"), Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PerfCard() {
    val rows = WebViewPerf.entries.take(15)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (rows.isEmpty()) {
            Text(
                text = if (!WebViewDebug.perfRecord) tr("已关闭记录。打开上面的开关后，之后加载的页面会显示 FCP / LCP") else tr("还没有记录：打开一个网页后，这里会显示首次内容绘制（FCP）和最大内容绘制（LCP）。系统 WebView 不支持导航监听时不会有数据"),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            return@Card
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { e ->
                Column {
                    Text(
                        text = hostOf(e.url) + android.net.Uri.parse(e.url).path.orEmpty().take(40),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = "FCP " + perfText(WebViewPerf.Metric.FCP, e.fcpMs) + "  ·  LCP " + perfText(WebViewPerf.Metric.LCP, e.lcpMs),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

private fun perfText(metric: WebViewPerf.Metric, ms: Long?): String {
    if (ms == null) return "—"
    val tag = when (WebViewPerf.rate(metric, ms)) {
        WebViewPerf.Rating.GOOD -> ""
        WebViewPerf.Rating.NEEDS_IMPROVEMENT -> tr("（待优化）")
        WebViewPerf.Rating.POOR -> tr("（较慢）")
    }
    return WebViewPerf.format(ms) + tag
}

@Composable
private fun WebViewDebugGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) { Column { content() } }
}
