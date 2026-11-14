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

/**
 * 设置 → 隐私与安全 → 隐私增强保护（仅 WebView 内核）：
 * 把「发送禁止跟踪请求」「禁用 WebRTC」「指纹保护」合并在同一页。Gecko 内核对应的是「Gecko 高级保护」。
 */
@Composable
fun PrivacyEnhanceScreen(settings: AppSettings, onBack: () -> Unit, blurEnabled: Boolean = true) {
    var showFpExempt by remember { mutableStateOf(false) }
    var fpPending by remember { mutableStateOf<Int?>(null) } // 等待用户确认开启的指纹保护项

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    var showLockInfo by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = "隐私增强保护",
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
                actions = {
                    EncryptionLockButton(
                        onClick = { showLockInfo = true },
                        liquid = if (glass) liquidBackdrop else null,
                        topScrolled = topScrolled,
                    )
                },
            )
        },
    ) { innerPadding ->
        EncryptionInfoDialog(
            show = showLockInfo,
            onDismiss = { showLockInfo = false },
            backdrop = backdrop,
            liquid = if (glass) liquidBackdrop else null,
            scope = "隐私报告记录：SQLCipher 整库 AES-256",
        )
        val topInset = innerPadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .edgeHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(top = topInset)
                    .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                SmallTitle(text = "请求与连接")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
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

                SmallTitle(text = "指纹保护")
                // ① 隐私报告
                val fpOn = AdBlock.fpMaster
                val fpCount = listOf(AdBlock.fpCanvas, AdBlock.fpWebgl, AdBlock.fpAudio, AdBlock.fpFonts).count { it }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "隐私报告",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (fpOn) "保护中 · $fpCount / 4 项" else "未开启",
                            fontSize = 12.sp,
                            color = if (fpOn) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = "${AdBlock.fpReads}",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = " 次指纹读取已被处理",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                        )
                    }
                    Text(
                        text = when {
                            AdBlock.fpSince == 0L && !fpOn -> "开启保护后，这里会统计网页读取你设备指纹的次数"
                            AdBlock.fpSince == 0L -> "已开启保护，暂未发现网页读取指纹"
                            fpOn && AdBlock.fpReads == 0L -> "自 ${fpSinceText(AdBlock.fpSince)} 开启保护以来，暂未发现网页读取指纹"
                            fpOn -> "自 ${fpSinceText(AdBlock.fpSince)} 开启保护以来的累计"
                            else -> "保护已关闭 · 数字停留在 ${fpSinceText(AdBlock.fpSince)} 开启的那一轮"
                        },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = "网页在隐藏或关闭时上报，数字仅供参考",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // ② 总开关 + 展开的四项
                SmallTitle(text = "保护项目")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppSwitchPreference(
                        title = "指纹保护",
                        summary = if (fpOn) "已开启，可在下方选择保护哪些项目" else "开启后可选择 Canvas · WebGL · Audio · Fonts",
                        checked = fpOn,
                        onCheckedChange = { if (it) fpPending = AdBlock.FP_ALL else AdBlock.setFingerprintMaster(false) },
                    )
                    AnimatedVisibility(
                        visible = fpOn,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            AppSwitchPreference(
                                title = "Canvas 指纹",
                                summary = "导出画布 / 读取像素时加入不可见的细微噪声",
                                checked = AdBlock.fpCanvas,
                                onCheckedChange = { if (it) fpPending = AdBlock.FP_CANVAS else AdBlock.updateFingerprint(AdBlock.FP_CANVAS, false) },
                            )
                            AppSwitchPreference(
                                title = "WebGL 指纹",
                                summary = "给 readPixels / WebGL 画布导出加入细微噪声",
                                checked = AdBlock.fpWebgl,
                                onCheckedChange = { if (it) fpPending = AdBlock.FP_WEBGL else AdBlock.updateFingerprint(AdBlock.FP_WEBGL, false) },
                            )
                            AppSwitchPreference(
                                title = "Audio 指纹",
                                summary = "给音频缓冲和分析器输出加入听不出来的噪声",
                                checked = AdBlock.fpAudio,
                                onCheckedChange = { if (it) fpPending = AdBlock.FP_AUDIO else AdBlock.updateFingerprint(AdBlock.FP_AUDIO, false) },
                            )
                            AppSwitchPreference(
                                title = "Fonts 指纹",
                                summary = "干扰 Canvas 文字测量与本机字体加载，无法枚举已安装字体",
                                checked = AdBlock.fpFonts,
                                onCheckedChange = { if (it) fpPending = AdBlock.FP_FONTS else AdBlock.updateFingerprint(AdBlock.FP_FONTS, false) },
                            )
                        }
                    }
                }

                // ③ 高级：兜底策略与豁免
                SmallTitle(text = "高级")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppDropdownPreference(
                        backdrop = if (glass) backdrop else null,
                        title = "未预注册页面的兜底",
                        items = listOf("仅记录，不干预", "停止并在保护下重载", "停止加载"),
                        selectedIndex = AdBlock.fpUnprotectedPolicy.coerceIn(0, 2),
                        onSelectedIndexChange = { AdBlock.updateFpUnprotectedPolicy(it) },
                    )
                    ArrowPreference(
                        title = "保护豁免站点",
                        summary = if (AdBlock.fpExemptHosts.isEmpty()) {
                            "这些站点（含子域名）不注入指纹保护"
                        } else {
                            "${AdBlock.fpExemptHosts.size} 个站点不注入指纹保护"
                        },
                        onClick = { showFpExempt = true },
                    )
                }

                Text(
                    text = "默认关闭。开启会改动网页读到的数据，可能引起兼容问题（开启时会有具体提示）；" +
                        "对新打开或刷新后的页面生效。保护作用于页面本身、同站点子域名以及预注册清单内的跨源 iframe，" +
                        "清单之外的跨源 iframe 不保护；若页面（如重定向落点）在注册前就开始加载，会按「兜底」策略处理" +
                        "（登录回调这类带一次性凭证的地址不会被重载）。" +
                        "豁免列表与去广告的「站点白名单」相互独立；若某个网站异常，可关掉对应项或把该站点加入豁免。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )

            }

            HostListDialog(
                show = showFpExempt,
                backdrop = if (glass) backdrop else null,
                title = "保护豁免站点",
                summary = "豁免站点（含子域名）不注入指纹保护；对新打开或刷新后的页面生效",
                emptyText = "还没有豁免站点",
                hosts = AdBlock.fpExemptHosts,
                onAdd = { AdBlock.exemptFingerprintSite(it) },
                onRemove = { AdBlock.unexemptFingerprintSite(it) },
                onDismiss = { showFpExempt = false },
            )

            val fpAsk = fpPending
            AppGlassDialog(
                show = fpAsk != null,
                title = when (fpAsk) {
                    AdBlock.FP_CANVAS -> "开启 Canvas 指纹保护？"
                    AdBlock.FP_WEBGL -> "开启 WebGL 指纹保护？"
                    AdBlock.FP_AUDIO -> "开启 Audio 指纹保护？"
                    AdBlock.FP_FONTS -> "开启 Fonts 指纹保护？"
                    else -> "开启指纹保护？"
                },
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { fpPending = null },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = when (fpAsk) {
                            AdBlock.FP_CANVAS -> "网页读到的 Canvas 像素会带有极细微的噪声（约 1/64 的像素 ±1），可能引起：\n" +
                                "· 在线绘图 / 取色 / 图片处理 / 截图工具的结果与原图有细微差异\n" +
                                "· 银行、验证码、风控类网站发现数据被改动，要求额外验证或拒绝登录\n" +
                                "· 浏览器检测站点提示 Canvas 被篡改\n" +
                                "· 导出的图片与原画布不再逐字节一致"
                            AdBlock.FP_WEBGL -> "WebGL 读回的像素会带有极细微的噪声，可能引起：\n" +
                                "· 3D / 地图 / 游戏截图、读像素的工具结果有细微差异\n" +
                                "· 风控类网站发现渲染数据被改动，要求额外验证\n" +
                                "· 浏览器检测站点提示 WebGL 被篡改"
                            AdBlock.FP_AUDIO -> "音频缓冲和分析器输出会带有听不出来的噪声，可能引起：\n" +
                                "· 音频分析 / 调音 / 频谱类工具读数有微小偏差\n" +
                                "· 依赖音频指纹的校验或风控失败"
                            AdBlock.FP_FONTS -> "Canvas 文字测量结果会有极小偏差，本机字体加载对非常见字体会失败，可能引起：\n" +
                                "· 依赖精确测量的排版、编辑器、图表出现细微错位\n" +
                                "· 使用本机字体的网页回退到其他字体\n" +
                                "· 浏览器检测站点提示字体异常"
                            AdBlock.FP_ALL -> "将恢复你上次选择的保护项（首次开启为 Canvas / WebGL / Audio / Fonts 全部四项）。" +
                                "网页读到的 Canvas / WebGL 像素、音频输出和字体测量会带有极细微的噪声，可能引起：\n" +
                                "· 在线绘图 / 取色 / 图片处理 / 3D 与地图工具的结果有细微差异\n" +
                                "· 银行、验证码、风控类网站发现数据被改动，要求额外验证\n" +
                                "· 依赖精确测量的排版 / 编辑器出现细微错位，本机字体回退\n" +
                                "开启后可在列表里逐项关闭。"
                            else -> ""
                        },
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "指纹保护只能降低被跨站追踪的概率，不等于匿名。若遇到问题可随时关闭，或把该网站加入去广告「站点白名单」。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppDialogButton(
                            onClick = { fpPending = null },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "取消", style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                fpAsk?.let {
                                    if (it == AdBlock.FP_ALL) AdBlock.setFingerprintMaster(true) else AdBlock.updateFingerprint(it, true)
                                }
                                fpPending = null
                            },
                            modifier = Modifier.weight(1f),
                            role = top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary,
                        ) {
                            Text(text = "仍要开启", style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }

        }
    }
    }
}

private fun fpSinceText(time: Long): String =
    java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
