package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.engine.webview.WebViewDebug
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
    LaunchedEffect(Unit) { WebViewDebug.load(context) }
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
                    title = "WebView 调试",
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
                    .edgeHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    item { SmallTitle(text = "内核信息") }
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

                    item { SmallTitle(text = "日志") }
                    item {
                        WebViewDebugGroup {
                            AppSwitchPreference(
                                title = "运行日志",
                                summary = "记录启动、标签、去广告等运行信息到 Android/media/<包名>/diag.txt；每条都是一次文件写入，排查完请关闭。与 Gecko 调试共用同一开关",
                                checked = Diag.enabledState,
                                onCheckedChange = {
                                    Diag.setPersisted(context, it)
                                    refresh++
                                },
                            )
                            AppSwitchPreference(
                                title = "控制台日志",
                                summary = "把网页 console 输出和 JavaScript 报错写进运行日志（需开启运行日志）",
                                checked = d.consoleLog,
                                onCheckedChange = { d.updateConsoleLog(it) },
                                enabled = Diag.enabledState,
                            )
                            AppSwitchPreference(
                                title = "页面加载日志",
                                summary = "记录页面开始 / 完成加载、加载失败，以及返回 4xx / 5xx 的请求（需开启运行日志）",
                                checked = d.pageLog,
                                onCheckedChange = { d.updatePageLog(it) },
                                enabled = Diag.enabledState,
                            )
                            ArrowPreference(
                                title = "复制日志路径",
                                summary = "把 diag.txt 的完整路径复制到剪贴板，用文件管理器打开",
                                onClick = {
                                    val path = Diag.logFile(context).absolutePath
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("diag", path))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                            )
                            ArrowPreference(
                                title = "清空日志",
                                summary = "清空 diag.txt 的内容，不改变开关",
                                onClick = {
                                    Diag.clear(context)
                                    refresh++
                                    Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }

                    item { SmallTitle(text = "调试") }
                    item {
                        WebViewDebugGroup {
                            AppSwitchPreference(
                                title = "USB 远程调试",
                                summary = "允许电脑 Chrome 通过 USB 连接，在 chrome://inspect 里检查本应用的网页（元素 / 控制台 / 网络），立即生效",
                                checked = d.remoteDebug,
                                onCheckedChange = { d.updateRemoteDebug(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "维护") }
                    item {
                        WebViewDebugGroup {
                            ArrowPreference(
                                title = "清除 WebView 网页缓存",
                                summary = "只清 HTTP 缓存，不影响 Cookie、登录状态和站点数据",
                                onClick = {
                                    WebViewDebug.clearWebCache(context)
                                    Toast.makeText(context, "已清除", Toast.LENGTH_SHORT).show()
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
private fun WebViewDebugGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) { Column { content() } }
}
