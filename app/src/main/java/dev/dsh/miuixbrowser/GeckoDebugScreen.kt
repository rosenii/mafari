package dev.dsh.miuixbrowser

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
import androidx.compose.runtime.remember
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
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                info.forEach { (label, value) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                    ) {
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
                                title = "网页字体跟随系统字体",
                                summary = "用系统字体作为浏览器默认字体，不使用 Gecko 自带的默认字体；网页自己指定的字体不受影响",
                                checked = d.systemFont,
                                onCheckedChange = { d.updateSystemFont(it) },
                            )
                            AppSwitchPreference(
                                title = "请求高刷新率",
                                summary = "向系统声明屏幕最高刷新率，避免滑动时被降到低刷新率",
                                checked = d.highRefresh,
                                onCheckedChange = { d.updateHighRefresh(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "进程") }
                    item {
                        DebugGroup {
                            AppSwitchPreference(
                                title = "进程隔离",
                                summary = "网页在独立隔离进程里运行。重启应用后生效。Gecko 已知问题（bug 2023367）：开启后 H.264 视频（多数 MP4）无法播放，AV1 等可播，暂无解决办法，需要看视频请保持关闭",
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
                                title = "渲染性能叠层",
                                summary = "在页面上显示 WebRender 帧率与耗时统计；仅用于排查，会增加开销",
                                checked = d.profiler,
                                onCheckedChange = { d.updateProfiler(it) },
                            )
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

@Composable
private fun DebugGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) { Column { content() } }
}

