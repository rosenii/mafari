@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.FullScreen
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Pause
import top.yukonga.miuix.kmp.icon.glass.Play
import top.yukonga.miuix.kmp.icon.glass.VolumeOff
import top.yukonga.miuix.kmp.icon.glass.VolumeUp
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShape

/*
 * 和 float-player.js 一致的播放界面；下载 / 复制链接 / 画中画 / 后台播放开关由调用方（[NativePlayerActivity]）接入。
 *
 * 布局对应脚本里的 DOM：
 *   scrim 暗角 · 中部 后退10 / 播放 / 快进10 · 底部 时间 + 胶囊进度条 + 时间
 *   左下 静音 · 右下 倍速 / 画中画 / 全屏 · 左上 ✕（全屏时 ‹）· 右上 ⋯ 菜单（下载 / 复制 / 清晰度）
 * 手势：单击显隐，双击左 40% / 右 40% 快退 / 快进 10 秒，双击中间播放 / 暂停，长按倍速（默认 2x，设置里可改；松手恢复）。
 *   全屏时左右滑动快进 / 快退：整屏宽度 ≈ [SWIPE_SPAN_MS]（视频不足这么长就按整段），滑动中屏幕中央显示目标时间，松手跳转；
 *   直播 / 时长未知 / 长按倍速中 / 弹层开着时不响应；开了「拖动进度时实时预览」就和拖进度条一样边滑边预览。
 *
 * 材质：按钮 / 弹层统一用 miuix-glass（GlassIconButton / glassPanel），采样源是视频层的 LayerBackdrop。
 * 视频必须用 TextureView（画进 View 层级，和 WebView 开硬件层同理）才能被采样；SurfaceView 是独立图层，采不到。
 * 视频层放在录制层里，所有玻璃表面都在录制层之外，不会采样到自己。
 */

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

private val GlassFill = Color(0x8018181A) // rgba(24,24,26,.5)
private val GlassBorder = Color(0x47FFFFFF) // rgba(255,255,255,.28)
private val PanelBorder = Color(0x38FFFFFF) // rgba(255,255,255,.22)
private val Hairline = Color(0x1FFFFFFF) // rgba(255,255,255,.12)
private val ChipFill = Color(0x1AFFFFFF) // rgba(255,255,255,.1)
private val Scrim = Brush.verticalGradient(
    0f to Color(0x38000000),
    0.3f to Color.Transparent,
    0.7f to Color.Transparent,
    1f to Color(0x73000000),
)

private const val POP_NONE = 0
private const val POP_SPEED = 1
private const val POP_MENU = 2
private const val POP_VOLUME = 3

private const val HIDE_DELAY_MS = 3800L
private const val SKIP_MS = 10_000L

/** 全屏左右滑动：滑过整屏宽度对应的时间跨度（视频比它短就按整段时长） */
private const val SWIPE_SPAN_MS = 180_000L

private class Toast(val text: String)

/** 滑动快进 / 快退进行中：[target] 松手后跳到的位置，[delta] 相对滑动开始时位置的偏移（毫秒，可为负） */
private class SwipeSeek(val target: Long, val delta: Long)

/**
 * @param fullscreen 是否全屏：全屏时左上角是「‹ 退出全屏」，控件整体内缩（避开刘海 / 圆角）。
 * @param pip 系统画中画小窗里：只剩画面，不画控件 / 手势 / 提示（操作走系统小窗的操作栏）。
 * @param onPip / [onDownload] / [onCopyLink] / [onCast] 传 null 时按钮照常显示，但灰掉、点了没反应（和脚本里 disabled 的样式一致）。
 * @param bgPlay 「后台播放」开关当前值；null = 不提供这个选项（菜单里不显示）。
 */
@Composable
internal fun PlayerSurface(
    player: MafariPlayer,
    fullscreen: Boolean,
    onClose: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onPip: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null,
    /** 「投屏」菜单项：弹「投屏到」面板（见 [NativeCast]）；null 时灰掉 */
    onCast: (() -> Unit)? = null,
    pip: Boolean = false,
    bgPlay: Boolean? = null,
    onBgPlayChange: (Boolean) -> Unit = {},
) {
    var controls by remember { mutableStateOf(true) }
    var popup by remember { mutableIntStateOf(POP_NONE) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var poke by remember { mutableIntStateOf(0) } // 每次操作 +1，重置自动隐藏计时
    var toast by remember { mutableStateOf<Toast?>(null) }
    var swipe by remember { mutableStateOf<SwipeSeek?>(null) } // 全屏滑动快进 / 快退进行中
    var holdPrev by remember { mutableStateOf<Float?>(null) } // 长按 2x 之前的倍速
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // true = TextureView（可玻璃采样），false = SurfaceView；初值来自设置 → 媒体嗅探与播放 → 渲染方式，菜单里改了会一并保存
    var textureMode by remember { mutableStateOf(PlayerPrefs.renderMode(appCtx) == PlayerPrefs.RENDER_TEXTURE) }
    val longPressSpeed = remember { PlayerPrefs.longPressSpeed(appCtx) } // 长按倍速（设置里可改，默认 2x）

    // 进度刷新：暂停时也要刷（拖动后的位置要立刻反映）
    LaunchedEffect(player) {
        while (true) {
            player.refresh()
            delay(250)
        }
    }
    // 播放中且没有在拖进度 / 开着弹层时，3.8 秒后收起控件
    LaunchedEffect(controls, player.isPlaying, scrub != null, popup, poke) {
        if (controls && player.isPlaying && scrub == null && popup == POP_NONE) {
            delay(HIDE_DELAY_MS)
            controls = false
        }
    }
    LaunchedEffect(toast) {
        if (toast != null && holdPrev == null) {
            delay(700)
            toast = null
        }
    }
    val controlsAlpha by animateFloatAsState(if (controls) 1f else 0f, tween(280), label = "controlsAlpha")

    BoxWithConstraints(modifier.background(Color.Black)) {
        // 弹层整体随播放器宽度缩放，对应脚本里的 --iosgp-scale（0.85 ~ 1.25）
        val scale = (maxWidth.value / 480f).coerceIn(0.85f, 1.25f)
        // 嵌套的 Box / Row 里不能再隐式访问外层的 maxWidth / maxHeight（DslMarker），先取成局部变量
        val boxW = maxWidth
        val boxH = maxHeight
        val compact = boxH < 230.dp // 超宽电影、窗口偏矮时中间按钮缩小
        val fo = if (fullscreen) 18.dp else 0.dp // 全屏统一位移量，对应脚本 --fo

        val ratio = if (player.videoWidth > 0 && player.videoHeight > 0) player.videoWidth.toFloat() / player.videoHeight else 0f
        // 采样源：只录视频层（含黑底），玻璃按钮 / 弹层都在它外面。
        // SurfaceView 模式下录不到画面，传 null 让 miuix 玻璃退回不透明底
        val recorded = rememberLayerBackdrop()
        val backdrop: Backdrop? = if (textureMode) recorded else null
        Box(Modifier.fillMaxSize().layerBackdrop(recorded).background(Color.Black)) {
            // key：切换渲染方式时整个 View 重建；旧 View 在 onRelease 里 detach
            key(textureMode) {
                AndroidView(
                    factory = { ctx -> (if (textureMode) TextureView(ctx) else SurfaceView(ctx)).also { player.attach(it) } },
                    onRelease = { player.detach(it) },
                    modifier = if (ratio > 0f) Modifier.align(Alignment.Center).aspectRatio(ratio) else Modifier.fillMaxSize(),
                )
            }
        }

        // 手势层：在控件层下面；控件层空白处不拦截，事件会落到这里（画中画小窗里不要，点击交给系统）
        if (!pip) Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    detectTapGestures(
                        onPress = {
                            tryAwaitRelease()
                            val prev = holdPrev
                            if (prev != null) {
                                player.changeSpeed(prev)
                                holdPrev = null
                                toast = null
                            }
                        },
                        onLongPress = {
                            if (player.isPlaying && holdPrev == null) {
                                holdPrev = player.speed
                                player.changeSpeed(longPressSpeed)
                                toast = Toast(tr("{0} 倍速播放中", speedLabel(longPressSpeed)))
                            }
                        },
                        onTap = {
                            if (popup != POP_NONE) {
                                popup = POP_NONE
                            } else {
                                controls = !controls
                                poke++
                            }
                        },
                        onDoubleTap = { o ->
                            val rel = o.x / size.width.coerceAtLeast(1)
                            popup = POP_NONE
                            when {
                                rel < 0.4f -> { player.seekBy(-SKIP_MS); toast = Toast(tr("-10 秒")) }
                                rel > 0.6f -> { player.seekBy(SKIP_MS); toast = Toast(tr("+10 秒")) }
                                else -> player.toggle()
                            }
                            poke++
                        },
                    )
                }
                // 全屏左右滑动快进 / 快退：和上面的点按 / 长按各管各的——滑动一开始（过了 touch slop）点按就自动取消。
                // 进度条、音量滑条在控件层里自己处理拖动，不会落到这里
                .pointerInput(player, fullscreen) {
                    if (!fullscreen) return@pointerInput
                    var from = 0L
                    var acc = 0f
                    var active = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            acc = 0f
                            from = player.positionMs
                            // 长按倍速中、弹层开着、直播 / 时长未知时不接管这次滑动
                            active = holdPrev == null && popup == POP_NONE && !player.isLive && player.durationMs > 0
                        },
                        onHorizontalDrag = { change, dx ->
                            if (!active) return@detectHorizontalDragGestures
                            change.consume()
                            acc += dx
                            val dur = player.durationMs
                            val span = minOf(dur, SWIPE_SPAN_MS)
                            val target = (from + (acc / size.width.coerceAtLeast(1) * span).toLong()).coerceIn(0L, dur)
                            swipe = SwipeSeek(target, target - from)
                            player.scrubTo(target) // 没开「拖动进度时实时预览」时什么也不做
                        },
                        onDragEnd = {
                            val s = swipe
                            swipe = null
                            active = false
                            player.endScrub()
                            if (s != null) {
                                player.seekTo(s.target)
                                poke++
                            }
                        },
                        onDragCancel = {
                            val s = swipe
                            swipe = null
                            active = false
                            player.endScrub()
                            if (s != null) player.seekTo(from) // 被打断（如系统手势抢走）：回到滑动前的位置
                        },
                    )
                },
        )

        if (!pip && controlsAlpha > 0.01f) {
            Box(Modifier.fillMaxSize().alpha(controlsAlpha)) {
                Box(Modifier.fillMaxSize().background(Scrim))

                val inner = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier
                Box(Modifier.fillMaxSize().then(inner).padding(12.dp + fo)) {
                    // ---- 中部：后退 / 播放暂停 / 快进 ----
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .offset(y = if (compact) -(boxH * 0.06f) else 0.dp)
                            .graphicsLayer {
                                val k = 0.85f + 0.15f * controlsAlpha
                                scaleX = k
                                scaleY = k
                            },
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val skip = if (compact) 42.dp else 52.dp
                        val skipIcon = if (compact) 23.dp else 28.dp
                        val playSize = if (compact) 52.dp else 68.dp
                        val playIcon = if (compact) 26.dp else 32.dp
                        PlayerGlassButton(skip, backdrop, onClick = { player.seekBy(-SKIP_MS); toast = Toast(tr("-10 秒")); poke++ }) {
                            SkipIcon(PlayerIcons.Back10, skipIcon)
                        }
                        PlayerGlassButton(playSize, backdrop, onClick = { player.toggle(); poke++ }) {
                            GlassGlyph(if (player.isPlaying) MiuixIcons.Glass.Pause else MiuixIcons.Glass.Play, playIcon + 4.dp)
                        }
                        PlayerGlassButton(skip, backdrop, onClick = { player.seekBy(SKIP_MS); toast = Toast(tr("+10 秒")); poke++ }) {
                            SkipIcon(PlayerIcons.Fwd10, skipIcon)
                        }
                    }

                    // ---- 底部：时间 + 胶囊进度条 + 时间（在角落按钮上方） ----
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, bottom = 44.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val shown = scrub?.let { (it * player.durationMs).toLong() } ?: player.positionMs
                        TimeText(fmt(shown))
                        SeekTrack(
                            modifier = Modifier.weight(1f),
                            position = player.positionMs,
                            duration = player.durationMs,
                            buffered = player.bufferedMs,
                            enabled = !player.isLive && player.durationMs > 0,
                            // 拖动：开了「拖动进度时实时预览」就边拖边 seek（Scrubbing 模式）；松手 / 取消时 onScrub(null) 先退出该模式，
                            // 随后 onSeek 再精确定位到松手位置（点按只走 onSeek）
                            onScrub = { f ->
                                scrub = f
                                poke++
                                if (f == null) player.endScrub() else player.scrubTo((f * player.durationMs).toLong())
                            },
                            onSeek = { player.seekTo(it) },
                        )
                        TimeText(if (player.isLive) tr("直播") else fmt(player.durationMs))
                    }

                    // ---- 左下：静音 ----
                    PlayerGlassButton(36.dp, backdrop, modifier = Modifier.align(Alignment.BottomStart), onClick = {
                        popup = if (popup == POP_VOLUME) POP_NONE else POP_VOLUME
                        poke++
                    }) {
                        GlassGlyph(if (player.muted || player.volume == 0f) MiuixIcons.Glass.VolumeOff else MiuixIcons.Glass.VolumeUp, 22.dp)
                    }

                    // ---- 右下：倍速 / 画中画 / 全屏 ----
                    Row(
                        Modifier.align(Alignment.BottomEnd),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpeedButton(speedLabel(player.speed)) {
                            popup = if (popup == POP_SPEED) POP_NONE else POP_SPEED
                            poke++
                        }
                        PlayerGlassButton(36.dp, backdrop, enabled = onPip != null, onClick = { onPip?.invoke(); poke++ }) {
                            PlayerIconView(PlayerIcons.Pip, 17.dp, Color.White)
                        }
                        PlayerGlassButton(36.dp, backdrop, onClick = { onToggleFullscreen() }) {
                            if (fullscreen) PlayerIconView(PlayerIcons.FullscreenExit, 17.dp, Color.White) else GlassGlyph(MiuixIcons.Glass.FullScreen, 22.dp)
                        }
                    }

                    // ---- 左上：✕ 关闭（全屏时 ‹ 退出全屏） ----
                    PlayerGlassButton(36.dp, backdrop, modifier = Modifier.align(Alignment.TopStart), onClick = { if (fullscreen) onToggleFullscreen() else onClose() }) {
                        GlassGlyph(if (fullscreen) MiuixIcons.Glass.ChevronBackward else MiuixIcons.Glass.Close, 22.dp)
                    }

                    // ---- 右上：更多 ----
                    PlayerGlassButton(36.dp, backdrop, modifier = Modifier.align(Alignment.TopEnd), onClick = {
                        popup = if (popup == POP_MENU) POP_NONE else POP_MENU
                        poke++
                    }) {
                        GlassGlyph(MiuixIcons.Glass.More, 22.dp)
                    }

                    // ---- 倍速弹层：贴在倍速按钮上方，右对齐 ----
                    if (popup == POP_SPEED) {
                        GlassPanel(
                            Modifier.align(Alignment.BottomEnd).padding(bottom = 44.dp),
                            backdrop = backdrop,
                            radius = 14.dp * scale,
                        ) {
                            Column(Modifier.padding(8.dp * scale)) {
                                ChipGrid(
                                    labels = SPEED_OPTIONS.map { speedLabel(it) },
                                    isActive = { Math.abs(SPEED_OPTIONS[it] - player.speed) < 0.001f },
                                    scale = scale,
                                    chipWidth = 54.dp * scale,
                                    onPick = { player.changeSpeed(SPEED_OPTIONS[it]); popup = POP_NONE; poke++ },
                                )
                            }
                        }
                    }

                    // ---- 音量弹层：贴在静音钮上方，左对齐。点喇叭静音 / 取消静音，拖动滑条调节 ----
                    if (popup == POP_VOLUME) {
                        GlassPanel(
                            Modifier.align(Alignment.BottomStart).padding(bottom = 44.dp),
                            backdrop = backdrop,
                            radius = 14.dp * scale,
                        ) {
                            Row(
                                Modifier.padding(start = 6.dp * scale, end = 12.dp * scale, top = 4.dp * scale, bottom = 4.dp * scale),
                                horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val silent = player.muted || player.volume == 0f
                                Box(
                                    Modifier.size(36.dp * scale).clickable { player.toggleMute(); poke++ },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    GlassGlyph(if (silent) MiuixIcons.Glass.VolumeOff else MiuixIcons.Glass.VolumeUp, 22.dp)
                                }
                                VolumeSlider(
                                    modifier = Modifier.width(130.dp * scale),
                                    value = if (player.muted) 0f else player.volume,
                                    onChange = { player.changeVolume(it); poke++ },
                                )
                                Text(
                                    text = "${((if (player.muted) 0f else player.volume) * 100).toInt()}%",
                                    color = Color.White,
                                    fontSize = (11.5f * scale).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(34.dp * scale),
                                )
                            }
                        }
                    }

                    // ---- 更多菜单：下载 / 复制链接 / 投屏 / 清晰度 / 后台播放（开关）/ 渲染方式 / 解码方式（只读） ----
                    if (popup == POP_MENU) {
                        val menuWidth = minOf(180.dp * scale, boxW - 48.dp - fo * 2)
                        GlassPanel(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 42.dp)
                                .width(menuWidth)
                                .heightIn(max = (boxH - 64.dp).coerceAtLeast(120.dp))
                                .verticalScroll(rememberScrollState()),
                            backdrop = backdrop,
                            radius = 13.dp * scale,
                        ) {
                            MenuItem(MiuixIcons.Glass.Download, tr("下载视频"), enabled = onDownload != null, scale = scale) {
                                onDownload?.invoke(); popup = POP_NONE
                                toast = Toast(tr("已加入下载"))
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                            MenuItem(MiuixIcons.Glass.Link, tr("复制视频链接"), enabled = onCopyLink != null, scale = scale) {
                                onCopyLink?.invoke(); popup = POP_NONE
                                toast = Toast(tr("已复制链接"))
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                            MenuItem(dev.dsh.miuixbrowser.ui.AppIcons.ScreenMirror, tr("投屏"), enabled = onCast != null, scale = scale) {
                                onCast?.invoke(); popup = POP_NONE
                            }
                            if (player.qualities.size > 1) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                                Text(
                                    text = tr("清晰度"),
                                    color = Color(0x8CFFFFFF),
                                    fontSize = (9.5f * scale).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 11.dp * scale, end = 11.dp * scale, top = 7.dp * scale, bottom = 3.dp * scale),
                                )
                                val labels = listOf(tr("自动")) + player.qualities.map { it.label }
                                Column(Modifier.padding(start = 9.dp * scale, end = 9.dp * scale, top = 4.dp * scale, bottom = 9.dp * scale)) {
                                    val chipW = (menuWidth - 18.dp * scale - 10.dp * scale) / 3f
                                    ChipGrid(
                                        labels = labels,
                                        isActive = { i -> if (i == 0) player.selectedQuality == null else player.selectedQuality === player.qualities[i - 1] },
                                        scale = scale,
                                        chipWidth = chipW,
                                        onPick = { i ->
                                            player.selectQuality(if (i == 0) null else player.qualities[i - 1])
                                            popup = POP_NONE
                                            poke++
                                        },
                                    )
                                }
                            }
                            if (bgPlay != null) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                                MenuSwitchRow(tr("后台播放"), checked = bgPlay, scale = scale) {
                                    onBgPlayChange(!bgPlay)
                                    toast = Toast(if (!bgPlay) tr("后台播放：已开启（切到后台继续播放音频）") else tr("后台播放：已关闭（切到后台会暂停）"))
                                    poke++
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                            MenuSectionTitle(tr("渲染方式"), scale)
                            Column(Modifier.padding(start = 9.dp * scale, end = 9.dp * scale, top = 4.dp * scale, bottom = 9.dp * scale)) {
                                ChipGrid(
                                    labels = listOf("SurfaceView", "TextureView"),
                                    isActive = { (it == 1) == textureMode },
                                    scale = scale,
                                    chipWidth = (menuWidth - 18.dp * scale - 5.dp * scale) / 2f,
                                    columns = 2,
                                    onPick = { i ->
                                        textureMode = i == 1
                                        PlayerPrefs.setRenderMode(appCtx, if (textureMode) PlayerPrefs.RENDER_TEXTURE else PlayerPrefs.RENDER_SURFACE)
                                        popup = POP_NONE
                                        toast = Toast(
                                            if (textureMode) tr("渲染方式：TextureView（支持玻璃模糊）") else tr("渲染方式：SurfaceView（无玻璃模糊，更省电）"),
                                        )
                                        poke++
                                    },
                                )
                            }
                            // ---- 解码方式：只读，显示当前实际在用的视频解码器 ----
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                            MenuSectionTitle(tr("解码方式"), scale)
                            val dec = player.videoDecoder
                            Text(
                                text = if (dec == null) "—" else (if (isSoftwareDecoder(dec)) tr("软件解码") else tr("硬件解码")) + "\n" + dec,
                                color = Color.White,
                                fontSize = (11.5f * scale).sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 11.dp * scale, end = 11.dp * scale, top = 2.dp * scale, bottom = 9.dp * scale),
                            )
                        }
                    }
                }
            }
        }

        // ---- 缓冲转圈 ----
        if (player.buffering && player.error == null) {
            Spinner(Modifier.align(Alignment.Center))
        }

        // ---- 提示条：顶部居中，在左上 / 右上按钮下方 ----
        toast?.takeIf { !pip }?.let { t ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp + fo)
                    .widthIn(max = boxW * 0.86f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0xD1141420))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(text = t.text, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }

        // ---- 全屏滑动快进 / 快退：屏幕中央显示目标时间和偏移 ----
        swipe?.takeIf { !pip }?.let { s ->
            val sec = s.delta / 1000
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xD1141420))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = fmt(s.target), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${fmt(player.durationMs)}  ·  " + tr("{0} 秒", if (sec >= 0) "+$sec" else "$sec"),
                    color = Color(0xCCFFFFFF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // ---- 出错：文案 + 重试 ----
        player.error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = msg, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GlassFill)
                        .border(1.dp, GlassBorder, RoundedCornerShape(50))
                        .clickable { player.retry() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) { Text(text = tr("重试"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/**
 * 圆形玻璃按钮：miuix 的 [GlassIconButton]，以视频层为采样源（内置按压反馈）。
 * [enabled] 为 false 时变灰、点击无效（调用方没接这个功能时）。
 */
@Composable
private fun PlayerGlassButton(
    size: Dp,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    GlassIconButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        backdrop = backdrop,
        size = size,
        content = content,
    )
}

/** 按钮里的 miuix 玻璃图标（统一白色，叠在视频上）。 */
@Composable
private fun GlassGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(size), tint = Color.White)
}

/** 后退 / 快进图标：路径是弧线 + 箭头，里面的「10」单独叠一层文字。 */
@Composable
private fun SkipIcon(icon: PlayerIcon, size: Dp) {
    Box(contentAlignment = Alignment.Center) {
        PlayerIconView(icon, size, Color.White)
        Text(
            text = "10",
            color = Color.White,
            fontSize = (size.value * 0.30f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(y = size * 0.1f),
        )
    }
}

/** 倍速：纯文字按钮（脚本里的 .iosgp-speed-btn，无底色）。 */
@Composable
private fun SpeedButton(label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .height(36.dp)
            .widthIn(min = 40.dp)
            .alpha(if (pressed) 0.6f else 1f)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text = label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun MenuSectionTitle(text: String, scale: Float) {
    Text(
        text = text,
        color = Color(0x8CFFFFFF),
        fontSize = (9.5f * scale).sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 11.dp * scale, end = 11.dp * scale, top = 7.dp * scale, bottom = 3.dp * scale),
    )
}

/** 菜单里的开关行：左边文字，右边胶囊开关（开 = 白底黑点）。点整行切换，不关菜单。 */
@Composable
private fun MenuSwitchRow(label: String, checked: Boolean, scale: Float, onToggle: () -> Unit) {
    val trackW = 32.dp * scale
    val trackH = 19.dp * scale
    val pad = 2.dp * scale
    val thumb = trackH - pad * 2
    val x by animateDpAsState(if (checked) trackW - thumb - pad else pad, tween(160), label = "switchThumb")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 11.dp * scale, vertical = 8.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = (11.5f * scale).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(trackW, trackH)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (checked) Color.White else ChipFill)
                .border(1.dp, if (checked) Color.White else PanelBorder, androidx.compose.foundation.shape.CircleShape),
        ) {
            Box(
                Modifier
                    .offset(x = x, y = pad)
                    .size(thumb)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (checked) Color(0xFF111111) else Color(0xB3FFFFFF)),
            )
        }
    }
}

@Composable
private fun TimeText(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 38.dp),
    )
}

/**
 * 胶囊进度条：7dp 高，拖动时加粗到 11dp 并显示圆点。点按跳转，水平拖动 scrub。
 * 触摸区比可见条高（28dp），不然手指很难点中。
 */
@Composable
private fun SeekTrack(
    modifier: Modifier,
    position: Long,
    duration: Long,
    buffered: Long,
    enabled: Boolean,
    onScrub: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufFrac = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val trackH by animateDpAsState(if (drag != null) 11.dp else 7.dp, tween(150), label = "trackH")
    Box(
        modifier
            .height(28.dp)
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { o -> onSeek((o.x / size.width.coerceAtLeast(1) * duration).toLong()) })
            }
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val f = (o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        drag = f
                        onScrub(f)
                    },
                    onDragEnd = {
                        val f = drag
                        drag = null
                        onScrub(null)
                        if (f != null) onSeek((f * duration).toLong())
                    },
                    onDragCancel = {
                        drag = null
                        onScrub(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        val f = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        drag = f
                        onScrub(f)
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = trackH.toPx()
            val top = (size.height - h) / 2f
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(Color(0x47FFFFFF), Offset(0f, top), Size(size.width, h), r) // rgba(255,255,255,.28)
            if (bufFrac > 0f) drawRoundRect(Color(0x52FFFFFF), Offset(0f, top), Size(size.width * bufFrac, h), r) // .32
            if (frac > 0f) drawRoundRect(Color.White, Offset(0f, top), Size(size.width * frac, h), r)
            if (drag != null) drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(size.width * frac, size.height / 2f))
        }
    }
}

/** 音量滑条：5dp 胶囊，点按 / 水平拖动都会连续回调 0..1。 */
@Composable
private fun VolumeSlider(modifier: Modifier, value: Float, onChange: (Float) -> Unit) {
    Box(
        modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o -> onChange((o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onChange((o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ -> onChange((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = 5.dp.toPx()
            val top = (size.height - h) / 2f
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(Color(0x47FFFFFF), Offset(0f, top), Size(size.width, h), r)
            if (value > 0f) drawRoundRect(Color.White, Offset(0f, top), Size(size.width * value, h), r)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(size.width * value, size.height / 2f))
        }
    }
}

/** 弹层面板（倍速 / 更多菜单）：miuix 的 glassPanel，以视频层为采样源；自己吞掉点击，免得落到下面的手势层把弹层关掉。 */
@Composable
private fun GlassPanel(modifier: Modifier, backdrop: Backdrop?, radius: Dp, content: @Composable ColumnScope.() -> Unit) {
    val rounded = RoundedCornerShape(radius)
    Column(
        modifier
            .glassPanel(
                backdrop = backdrop,
                shape = GlassShape(radius),
                style = GlassDefaults.style(true),
                material = GlassMaterials.puredThinGlass(true),
                stroke = GlassEdgeStrokes.forTheme(true),
                shading = false,
                // 没有采样源（SurfaceView 模式）时的不透明底
                fallback = Modifier.clip(rounded).background(Color(0xF01C1C1E)).border(1.dp, PanelBorder, rounded),
            )
            .pointerInput(Unit) { detectTapGestures { } },
        content = content,
    )
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, scale: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.32f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp * scale, vertical = 8.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp * scale), tint = Color.White)
        Text(text = label, color = Color.White, fontSize = (11.5f * scale).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 三列的小圆角方块网格（脚本的 .iosgp-speed-chip），选中项白底黑字。 */
@Composable
private fun ChipGrid(labels: List<String>, isActive: (Int) -> Boolean, scale: Float, chipWidth: Dp, columns: Int = 3, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp * scale)) {
        labels.indices.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp * scale)) {
                row.forEach { i ->
                    val active = isActive(i)
                    val shape = RoundedCornerShape(8.dp * scale)
                    Box(
                        Modifier
                            .width(chipWidth)
                            .clip(shape)
                            .background(if (active) Color.White else ChipFill)
                            .border(1.dp, if (active) Color.White else PanelBorder, shape)
                            .clickable { onPick(i) }
                            .padding(vertical = 6.dp * scale),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = labels[i],
                            color = if (active) Color(0xFF111111) else Color.White,
                            fontSize = (11.5f * scale).sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(modifier.size(36.dp)) {
        val w = 3.dp.toPx()
        drawCircle(Color(0x40FFFFFF), radius = (size.minDimension - w) / 2f, style = Stroke(w))
        drawArc(
            Color.White,
            startAngle = angle - 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(size.width - w, size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

internal fun speedLabel(s: Float): String = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

internal fun fmt(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
