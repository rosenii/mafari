package dev.dsh.miuixbrowser.ui

import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.player.PlayerPrefs
import dev.dsh.miuixbrowser.player.PlayerTuning
import dev.dsh.miuixbrowser.player.speedLabel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置 → 高级 → 媒体嗅探与播放（两个内核共用）：
 *  - 总开关：一键停用嗅探、原生悬浮播放、自动画中画、脚本宿主能力和 M3U8 媒体下载器脚本；
 *  - 嗅探媒体播放 / 退出自动画中画 / 自动悬浮播放（嗅探到视频直接用原生悬浮播放器打开）；
 *  - 播放器：渲染方式（TextureView / SurfaceView）、默认倍速、长按倍速、后台播放；
 *  - 快捷菜单：悬浮播放器下方那排按钮（投屏 / 下载 / 截屏 / 倍速 / 清晰度 / 视频源）的总开关和各按钮开关；
 *  - 下载：HLS 转 MP4（原来在下载页里）；
 *  - Media3 高级选项：HDR / 清晰度上限、解码、缓冲与内存、其他、稳定性与实验（见 [PlayerTuning]，下次打开播放器生效）。
 * 「网页增强」（WebView）关闭时本页功能同样不运行，详见 [dev.dsh.miuixbrowser.sniff.MediaFeatures]。
 *
 * [onMasterChange]：总开关变化后的联动（启停推荐的媒体脚本）；开关值本身已由本页写入 [AppSettings]。
 */
@Composable
fun MediaSniffScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onMasterChange: (Boolean) -> Unit = {},
    blurEnabled: Boolean = true,
) {
    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    // 子开关是否可操作：总开关开，且（WebView 下）网页增强开
    val active = settings.mediaActive
    val enhancementsOff = !settings.geckoMode && !settings.pageEnhancements

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = tr("媒体嗅探与播放"),
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
                        .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    SmallTitle(text = tr("总开关"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppSwitchPreference(
                            title = tr("媒体嗅探与播放"),
                            summary = tr("一键停用：嗅探、原生悬浮播放、自动画中画、自动悬浮播放及相关脚本；关闭后不再拦截 / 弹窗，刷新页面后生效"),
                            checked = settings.mediaMaster,
                            onCheckedChange = {
                                settings.updateMediaMaster(it)
                                onMasterChange(it)
                            },
                        )
                    }
                    if (enhancementsOff) {
                        Text(
                            text = tr("「网页增强」已关闭，本页功能暂不运行。重新打开网页增强后恢复。"),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                        )
                    }

                    SmallTitle(text = tr("嗅探与播放"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppSwitchPreference(
                            title = tr("嗅探媒体播放"),
                            summary = tr("网页里出现视频（m3u8 / mp4 等）时弹出提示，可用原生播放器打开；刷新页面后生效"),
                            checked = settings.mediaSniff,
                            onCheckedChange = { settings.updateMediaSniff(it) },
                            enabled = active,
                        )
                        AppSwitchPreference(
                            title = tr("退出自动画中画"),
                            summary = tr("全屏播放视频时按 Home / 手势返回桌面，自动进入画中画小窗"),
                            checked = settings.mediaAutoPip,
                            onCheckedChange = { settings.updateMediaAutoPip(it) },
                            enabled = active,
                        )
                        AppSwitchPreference(
                            title = tr("自动悬浮播放"),
                            summary = tr("嗅探到正片后自动用悬浮播放器打开，预览 / 短视频除外；已有播放器开着时不会重复打开。关闭后也不会主动弹窗，可点地址栏的媒体图标手动选择"),
                            checked = settings.mediaAutoFloat,
                            onCheckedChange = { settings.updateMediaAutoFloat(it) },
                            enabled = active,
                        )
                    }

                    SmallTitle(text = tr("播放器"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppDropdownPreference(
                            backdrop = if (glass) backdrop else null,
                            title = tr("渲染方式"),
                            summary = tr("TextureView 支持玻璃模糊；SurfaceView 更省电、延迟更低，但没有玻璃模糊。下次打开播放器生效"),
                            items = listOf("TextureView", "SurfaceView"),
                            selectedIndex = settings.playerRenderMode,
                            onSelectedIndexChange = { settings.updatePlayerRenderMode(it) },
                        )
                        AppDropdownPreference(
                            backdrop = if (glass) backdrop else null,
                            title = tr("默认倍速"),
                            summary = tr("每次打开播放器按这个速度起播，播放页里仍可临时修改"),
                            items = PlayerPrefs.SPEEDS.map { speedLabel(it) },
                            selectedIndex = PlayerPrefs.SPEEDS.indexOf(settings.playerDefaultSpeed).coerceAtLeast(0),
                            onSelectedIndexChange = { settings.updatePlayerDefaultSpeed(PlayerPrefs.SPEEDS[it]) },
                        )
                        AppDropdownPreference(
                            backdrop = if (glass) backdrop else null,
                            title = tr("长按倍速"),
                            summary = tr("长按播放画面时临时加速，松手恢复"),
                            items = PlayerPrefs.LONG_PRESS_SPEEDS.map { speedLabel(it) },
                            selectedIndex = PlayerPrefs.LONG_PRESS_SPEEDS.indexOf(settings.playerLongPressSpeed).coerceAtLeast(0),
                            onSelectedIndexChange = { settings.updatePlayerLongPressSpeed(PlayerPrefs.LONG_PRESS_SPEEDS[it]) },
                        )
                        AppSwitchPreference(
                            title = tr("后台播放"),
                            summary = tr("切到后台 / 锁屏继续播放，并在通知栏显示控制条；关闭后离开界面就暂停"),
                            checked = settings.playerBgPlay,
                            onCheckedChange = { settings.updatePlayerBgPlay(it) },
                        )
                    }

                    SmallTitle(text = tr("快捷菜单"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppSwitchPreference(
                            title = tr("快捷菜单"),
                            summary = tr("悬浮播放器卡片下方的一排快捷按钮（全屏 / 画中画里不显示）"),
                            checked = settings.playerQuickMenu,
                            onCheckedChange = { settings.updatePlayerQuickMenu(it) },
                        )
                        listOf(
                            "cast" to tr("投屏"),
                            "download" to tr("下载"),
                            "shot" to tr("截屏"),
                            "speed" to tr("倍速"),
                            "quality" to tr("清晰度"),
                            "source" to tr("视频源"),
                        ).forEach { (id, label) ->
                            AppSwitchPreference(
                                title = label,
                                checked = settings.playerQuickItems[id] != false,
                                onCheckedChange = { settings.updatePlayerQuickItem(id, it) },
                                enabled = settings.playerQuickMenu,
                            )
                        }
                    }

                    SmallTitle(text = tr("下载"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        // m3u8 一律走内置下载器，所以这一项不受下载页里「下载器」选择影响；也不受总开关约束（下载页里手动下 m3u8 同样用它）
                        AppSwitchPreference(
                            title = tr("HLS 转 MP4"),
                            summary = tr("m3u8 下载完成后把 TS 转封装成 MP4（不重新编码；编码不支持时保留 TS）"),
                            checked = settings.hlsToMp4,
                            onCheckedChange = { settings.updateHlsToMp4(it) },
                        )
                    }

                    // ---- Media3 高级选项：不动 = Media3 自己的默认行为；改了下次打开播放器生效 ----
                    val bk = if (glass) backdrop else null
                    val choices = settings.playerAdvChoices
                    val flags = settings.playerAdvFlags
                    fun choice(key: String) = choices[key] ?: PlayerTuning.choiceDefault(key)
                    fun flag(key: String) = flags[key] ?: PlayerTuning.SWITCH_DEFAULTS.getValue(key)

                    SmallTitle(text = tr("Media3 高级选项 · HDR 与画质"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("HDR 策略"),
                            summary = tr("流里同时有 HDR 和 SDR 清晰度（常见于 HLS / DASH）时，自动选档只在偏好的一类里挑；只有一类时不起作用。Media3 1.12 起能读出 HLS 的 VIDEO-RANGE（PQ / HLG）和 DASH 的色彩描述，这类流的 HDR 档会正确识别并在清晰度里标 HDR。HDR 直出需要 SurfaceView 渲染，TextureView 下 HDR 画面可能发灰"),
                            items = listOf(tr("自动"), tr("优先 SDR"), tr("优先 HDR")),
                            selectedIndex = choice(PlayerTuning.KEY_HDR),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_HDR, it) },
                        )
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("最高清晰度"),
                            summary = tr("自动选档时不超过这个清晰度（省流量 / 省电 / 低端机防卡顿）；手动选更高的档不受限"),
                            items = PlayerTuning.MAX_RES_LABELS_P.map { if (it == 0) tr("不限") else "${it}p" },
                            selectedIndex = choice(PlayerTuning.KEY_MAX_RES),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_MAX_RES, it) },
                        )
                    }

                    SmallTitle(text = tr("Media3 高级选项 · 解码"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("解码器偏好"),
                            summary = tr("只调整候选顺序、不会删掉候选：硬件优先省电，软件优先兼容性好但耗电发热（高分辨率可能掉帧）"),
                            items = listOf(tr("自动"), tr("硬件优先"), tr("软件优先")),
                            selectedIndex = choice(PlayerTuning.KEY_DECODER),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_DECODER, it) },
                        )
                        AppSwitchPreference(
                            title = tr("解码器回退"),
                            summary = tr("首选解码器初始化失败时自动换下一个（比如硬解起不来换软解），而不是直接报错"),
                            checked = flag(PlayerTuning.KEY_DECODER_FALLBACK),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_DECODER_FALLBACK, it) },
                        )
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("MediaCodec 异步队列"),
                            summary = tr("自动 = 由 Media3 按系统版本决定。个别机型解码花屏 / 卡死时可以试试强制关闭"),
                            items = listOf(tr("自动"), tr("强制开启"), tr("强制关闭")),
                            selectedIndex = choice(PlayerTuning.KEY_ASYNC),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_ASYNC, it) },
                        )
                        AppSwitchPreference(
                            title = tr("隧道播放"),
                            summary = tr("音视频直通硬件解码器，省电、同步更准；只在渲染方式为 SurfaceView 且设备支持时生效，部分机型有兼容问题"),
                            checked = flag(PlayerTuning.KEY_TUNNELING),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_TUNNELING, it) },
                        )
                    }

                    SmallTitle(text = tr("Media3 高级选项 · 缓冲与内存"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("缓冲策略"),
                            summary = tr("省内存：提前缓冲 30 秒；均衡：60 秒（默认）；流畅优先：2 分钟；大缓冲：5 分钟。越大越不容易卡，但占内存、费流量；实际还受下面的大小上限约束"),
                            items = listOf(tr("省内存"), tr("均衡"), tr("流畅优先"), tr("大缓冲")),
                            selectedIndex = choice(PlayerTuning.KEY_BUF_PRESET),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_BUF_PRESET, it) },
                        )
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("缓冲大小上限"),
                            summary = tr("缓冲占用内存的上限。自动 = 由 Media3 按视频估算（省内存档为 48 MB）；超过上限就暂停缓冲，哪怕还没缓冲够"),
                            items = PlayerTuning.BUFFER_MB.map { if (it == 0) tr("自动") else "$it MB" },
                            selectedIndex = choice(PlayerTuning.KEY_BUF_MB),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_BUF_MB, it) },
                        )
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("后向缓冲"),
                            summary = tr("已播放的部分在内存里多留一会儿，往回拖不用重新下载；会多占内存"),
                            items = PlayerTuning.BACK_BUFFER_SEC.map { if (it == 0) tr("不保留") else tr("{0} 秒", it) },
                            selectedIndex = choice(PlayerTuning.KEY_BACK_BUF),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_BACK_BUF, it) },
                        )
                        AppSwitchPreference(
                            title = tr("只加载选中的轨道"),
                            summary = tr("MP4 等整个文件里有多条画面 / 图片轨道时，只加载选中的那条，省内存和流量；中途切换清晰度时可能要多缓冲一会儿。只有一条画面轨的普通视频没有区别"),
                            checked = flag(PlayerTuning.KEY_LOAD_SELECTED),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_LOAD_SELECTED, it) },
                        )
                    }

                    SmallTitle(text = tr("Media3 高级选项 · 其他"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("拖动定位"),
                            summary = tr("精确：落在你拖到的那一帧，要多解码一段；就近关键帧：落在最近的关键帧，快很多，但位置会有几秒偏差"),
                            items = listOf(tr("精确"), tr("就近关键帧")),
                            selectedIndex = choice(PlayerTuning.KEY_SEEK),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_SEEK, it) },
                        )
                        AppSwitchPreference(
                            title = tr("拖动进度时实时预览"),
                            summary = tr("拖动进度条时画面跟着手指实时更新（Media3 的 Scrubbing 模式：拖动期间暂停声音，松手后按「拖动定位」精确跳转）。拖动时会比平时多做一些读取和解码；点按进度条不受影响"),
                            checked = flag(PlayerTuning.KEY_SCRUB),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_SCRUB, it) },
                        )
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("网络超时"),
                            summary = tr("连接 / 读取超时：严格 8 秒 / 10 秒，默认 15 秒 / 20 秒，宽松 30 秒 / 40 秒。线路慢时用宽松，想快速失败后换源用严格"),
                            items = listOf(tr("默认"), tr("严格"), tr("宽松")),
                            selectedIndex = choice(PlayerTuning.KEY_NET),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_NET, it) },
                        )
                        AppSwitchPreference(
                            title = tr("管理音频焦点"),
                            summary = tr("来电 / 其他应用播放声音时自动暂停。关闭后会和其他应用的声音混在一起播放"),
                            checked = flag(PlayerTuning.KEY_AUDIO_FOCUS),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_AUDIO_FOCUS, it) },
                        )
                        AppSwitchPreference(
                            title = tr("拔出耳机时暂停"),
                            summary = tr("耳机拔出 / 蓝牙断开时自动暂停，免得突然外放"),
                            checked = flag(PlayerTuning.KEY_NOISY),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_NOISY, it) },
                        )
                    }

                    SmallTitle(text = tr("Media3 高级选项 · 稳定性与实验"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("卡死检测"),
                            summary = tr("画面 / 进度停住、缓冲没进展时主动报错并给出重试，而不是一直转圈。默认：进度停住 10 秒就报错；宽松：30 秒（线路抖动大时用）；关闭：不检测，卡住了会一直转圈"),
                            items = listOf(tr("默认"), tr("宽松"), tr("关闭检测")),
                            selectedIndex = choice(PlayerTuning.KEY_STUCK),
                            onSelectedIndexChange = { settings.updatePlayerAdvChoice(PlayerTuning.KEY_STUCK, it) },
                        )
                        AppSwitchPreference(
                            title = tr("Media3 灰度特性"),
                            summary = tr("Media3 会把一些新改动先在「灰度」里默认打开（例如更严格的数据解析检查）。关闭后这些改动不生效、行为更保守；个别站点的流突然解析失败或异常时可以试试关闭"),
                            checked = flag(PlayerTuning.KEY_CANARY),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_CANARY, it) },
                        )
                        AppSwitchPreference(
                            title = tr("加快首次起播（实验）"),
                            summary = tr("创建音频输出时提前初始化，缩短第一次起播的等待。Media3 的实验选项，默认关闭"),
                            checked = flag(PlayerTuning.KEY_FAST_START),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_FAST_START, it) },
                        )
                        AppSwitchPreference(
                            title = tr("快速精确定位（实验）"),
                            summary = tr("精确跳转时，目标位置之前的画面只解码、不渲染，跳得更快。Media3 的实验选项，Android 14 及以上才有效果"),
                            checked = flag(PlayerTuning.KEY_FAST_SEEK),
                            onCheckedChange = { settings.updatePlayerAdvFlag(PlayerTuning.KEY_FAST_SEEK, it) },
                        )
                        AppDropdownPreference(
                            backdrop = bk,
                            title = tr("恢复默认"),
                            summary = tr("把上面所有 Media3 高级选项恢复成默认值"),
                            items = listOf(tr("保持当前"), tr("恢复默认")),
                            selectedIndex = 0,
                            onSelectedIndexChange = { if (it == 1) settings.resetPlayerAdvanced() },
                        )
                    }
                    Text(
                        text = tr("以上选项在下次打开播放器时生效。出现花屏、黑屏或无法播放时，先试试「恢复默认」。"),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
