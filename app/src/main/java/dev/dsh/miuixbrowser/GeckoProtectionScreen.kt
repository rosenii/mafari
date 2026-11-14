package dev.dsh.miuixbrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Gecko 高级保护页：用 Gecko 自带的隐私 / 安全能力做反追踪、指纹保护、安全 DNS 等。
 * 设置项见 [GeckoProtection]，改动立即生效（新打开 / 刷新的页面起作用）。
 */
@Composable
fun GeckoProtectionScreen(onBack: () -> Unit, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { GeckoProtection.load(context) }
    val p = GeckoProtection

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    val bd = if (glass) backdrop else null

    var resetAsk by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = "Gecko 高级保护",
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
                    item {
                        Hint("以下设置由 Gecko 内核执行，仅对 Gecko 标签生效；修改后新打开或刷新的页面起作用。")
                    }

                    item { SmallTitle(text = "反追踪") }
                    item {
                        Group {
                            AppDropdownPreference(
                                backdrop = bd,
                                title = "增强型跟踪保护",
                                summary = "标准：拦截广告 / 统计 / 社交跟踪；严格：另拦截更多内容跟踪，个别网页可能异常",
                                items = listOf("默认（标准）", "关闭", "标准", "严格"),
                                selectedIndex = p.etp,
                                onSelectedIndexChange = { p.updateEtp(it) },
                            )
                            AppSwitchPreference(
                                title = "清理网址跟踪参数",
                                summary = "打开链接时去掉 utm_*、fbclid、gclid 等跟踪参数",
                                checked = p.stripTracking,
                                onCheckedChange = { p.updateStripTracking(it) },
                            )
                            AppSwitchPreference(
                                title = "弹跳跟踪保护",
                                summary = "识别并清除经由中转站点（一闪而过的跳转页）建立的跟踪数据",
                                checked = p.bounceTracking,
                                onCheckedChange = { p.updateBounceTracking(it) },
                            )
                            AppSwitchPreference(
                                title = "定期清理跟踪者数据",
                                summary = "清除已知跟踪站点留下的 Cookie 与存储（标准 / 严格档位下始终开启）",
                                checked = p.purgeTrackers,
                                onCheckedChange = { p.updatePurgeTrackers(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "指纹") }
                    item {
                        Group {
                            AppSwitchPreference(
                                title = "指纹保护",
                                summary = "拦截已知指纹脚本，并对少量易被用来识别设备的信息做随机化，兼容性好",
                                checked = p.fingerprint,
                                onCheckedChange = { p.updateFingerprint(it) },
                            )
                            AppSwitchPreference(
                                title = "强力抗指纹",
                                summary = "统一时区（UTC）、语言、窗口尺寸等；防护更强，但部分网页的时间 / 布局会异常",
                                checked = p.resistFingerprint,
                                onCheckedChange = { p.updateResistFingerprint(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "身份与请求") }
                    item {
                        Group {
                            AppSwitchPreference(
                                title = "全局隐私控制（GPC）",
                                summary = "向网站发送 Sec-GPC 与 DNT，声明不同意出售 / 共享个人数据",
                                checked = p.gpc,
                                onCheckedChange = { p.updateGpc(it) },
                            )
                            AppSwitchPreference(
                                title = "精简 Referer",
                                summary = "跨站请求只带来源域名，不带完整页面地址",
                                checked = p.trimReferrer,
                                onCheckedChange = { p.updateTrimReferrer(it) },
                            )
                            AppSwitchPreference(
                                title = "防 WebRTC 泄露 IP",
                                summary = "只使用默认出口地址，不向网页暴露内网 / 多网卡地址（视频通话仍可用）",
                                checked = p.webrtcLeak,
                                onCheckedChange = { p.updateWebrtcLeak(it) },
                            )
                            AppSwitchPreference(
                                title = "禁用 WebRTC",
                                summary = "彻底关闭 WebRTC 接口，不再泄露真实 IP（视频通话 / 在线会议将不可用）",
                                checked = p.webrtcOff,
                                onCheckedChange = { p.updateWebrtcOff(it) },
                            )
                            AppSwitchPreference(
                                title = "严格第一方隔离",
                                summary = "每个站点使用独立的 Cookie 与缓存；跨站登录（如「用 Google 登录」）可能失效",
                                checked = p.firstPartyIsolate,
                                onCheckedChange = { p.updateFirstPartyIsolate(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "连接安全") }
                    item {
                        Group {
                            AppSwitchPreference(
                                title = "仅 HTTPS 模式",
                                summary = "自动把 http 升级为 https；不支持的站点会先提示再决定是否继续",
                                checked = p.httpsOnly,
                                onCheckedChange = { p.updateHttpsOnly(it) },
                            )
                            AppDropdownPreference(
                                backdrop = bd,
                                title = "安全 DNS（DoH）",
                                summary = "加密域名解析，防运营商篡改与窥探；「仅安全 DNS」下服务不可用时网页将打不开",
                                items = listOf("关闭（跟随系统）", "优先（失败回退系统）", "仅安全 DNS"),
                                selectedIndex = p.dohMode,
                                onSelectedIndexChange = { p.updateDohMode(it) },
                            )
                            if (p.dohMode > 0) {
                                AppDropdownPreference(
                                    backdrop = bd,
                                    title = "DNS 服务商",
                                    items = p.DOH_PROVIDERS.map { it.name },
                                    selectedIndex = p.dohProvider,
                                    onSelectedIndexChange = { p.updateDohProvider(it) },
                                )
                                if (p.dohProvider == p.DOH_PROVIDERS.lastIndex) {
                                    var url by remember { mutableStateOf(p.dohCustom) }
                                    TextField(
                                        value = url,
                                        onValueChange = {
                                            url = it
                                            p.updateDohCustom(it)
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        label = "DoH 地址（https://…/dns-query）",
                                        singleLine = true,
                                    )
                                }
                            }
                            AppSwitchPreference(
                                title = "加密 Client Hello（ECH）",
                                summary = "对支持的站点隐藏所访问的域名；需要开启安全 DNS 才会生效",
                                checked = p.ech,
                                onCheckedChange = { p.updateEch(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "其他") }
                    item {
                        Group {
                            AppSwitchPreference(
                                title = "禁用预取 / 预连接",
                                summary = "不提前解析、连接未打开的链接，减少对外暴露的请求（页面加载可能略慢）",
                                checked = p.noPrefetch,
                                onCheckedChange = { p.updateNoPrefetch(it) },
                            )
                            AppSwitchPreference(
                                title = "禁用 WebGL",
                                summary = "彻底关闭 WebGL 指纹面；3D 网页、地图的部分效果将不可用",
                                checked = p.disableWebgl,
                                onCheckedChange = { p.updateDisableWebgl(it) },
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(16.dp))
                        AppTextButton(
                            text = "恢复默认",
                            onClick = { resetAsk = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            inPage = true, // 页面内按钮不能采样页面层，否则液态玻璃下递归采样闪退
                        )
                    }
                }
            }
        }

        AppGlassDialog(
            show = resetAsk,
            title = "恢复默认",
            summary = "Gecko 高级保护的全部设置将恢复为默认值",
            backdrop = bd,
            onDismissRequest = { resetAsk = false },
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = { resetAsk = false }, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        resetAsk = false
                        GeckoProtection.reset()
                    },
                    modifier = Modifier.weight(1f),
                    role = top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Destructive,
                ) {
                    Text(text = "恢复", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) { Column { content() } }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}
