package dev.dsh.miuixbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
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
 * 设置 → 高级 → Gecko 调试。
 * 调试项默认全部关闭（除沉浸 / 高刷新率这两项性能相关的），排查完建议关掉。
 */
@Composable
fun GeckoDebugScreen(onBack: () -> Unit, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { GeckoDebug.load(context) }
    val d = GeckoDebug

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    // 每次进入页面重新取一次（Gecko 运行时状态、刷新率可能变了）
    val info = remember { GeckoDebug.infoLines(context) }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = "Gecko 调试",
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
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    item { SmallTitle(text = "渲染信息") }
                    item {
                        DebugGroup {
                            info.forEach { (label, value) ->
                                ArrowPreference(
                                    title = label,
                                    summary = value,
                                    onClick = { copyInfo(context, info) },
                                )
                            }
                        }
                    }
                    item { DebugHint("点任意一行复制全部渲染信息，反馈问题时可以直接贴出来。") }

                    item { SmallTitle(text = "滚动与渲染") }
                    item {
                        DebugGroup {
                            AppSwitchPreference(
                                title = "原生沉浸",
                                summary = "网页顶部留白随滚动收起，且收起时不重新布局网页（实验）。网页异常或顶部被遮挡时请关闭，关闭后留白固定",
                                checked = d.nativeImmersive,
                                onCheckedChange = { d.updateNativeImmersive(it) },
                            )
                            AppSwitchPreference(
                                title = "请求高刷新率",
                                summary = "向系统声明屏幕最高刷新率，避免滑动时被降到低刷新率",
                                checked = d.highRefresh,
                                onCheckedChange = { d.updateHighRefresh(it) },
                            )
                            AppSwitchPreference(
                                title = "渲染性能叠层",
                                summary = "在页面上显示 WebRender 帧率与耗时统计；仅用于排查，会增加开销",
                                checked = d.profiler,
                                onCheckedChange = { d.updateProfiler(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "进程") }
                    item {
                        DebugGroup {
                            AppSwitchPreference(
                                title = "进程隔离",
                                summary = "网页在独立隔离进程里运行，更安全。重启应用后生效。开启后可能无法播放视频（Gecko 已知问题：隔离进程无法启动系统解码服务），默认关闭",
                                checked = d.isolatedProcess,
                                onCheckedChange = {
                                    d.updateIsolatedProcess(it)
                                    Toast.makeText(context, "重启应用后生效", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }

                    item { SmallTitle(text = "诊断") }
                    item {
                        DebugGroup {
                            AppSwitchPreference(
                                title = "运行日志",
                                summary = "记录 Surface 创建 / 销毁等到 Android/media/<包名>/diag.txt；每条都是一次文件写入，排查完请关闭",
                                checked = d.diagLog,
                                onCheckedChange = { d.updateDiagLog(context, it) },
                            )
                            AppSwitchPreference(
                                title = "USB 远程调试",
                                summary = "允许桌面 Firefox 通过 USB 连接并检查页面，立即生效",
                                checked = d.remoteDebug,
                                onCheckedChange = { d.updateRemoteDebug(it) },
                            )
                            AppSwitchPreference(
                                title = "允许 about:config",
                                summary = "允许打开 about:config 手动修改偏好，立即生效；误改可能导致网页异常",
                                checked = d.aboutConfig,
                                onCheckedChange = { d.updateAboutConfig(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun copyInfo(context: Context, info: List<Pair<String, String>>) {
    runCatching {
        val text = info.joinToString("\n") { "${it.first}: ${it.second}" }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Gecko 渲染信息", text))
        Toast.makeText(context, "已复制渲染信息", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DebugGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) { Column { content() } }
}

@Composable
private fun DebugHint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}
