/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import dev.dsh.miuixbrowser.download.Downloader
import android.net.Uri
import dev.dsh.miuixbrowser.MainActivity
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.script.MediaHost
import org.json.JSONObject
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.MiuixIcons
import dev.dsh.miuixbrowser.appContainer
import dev.dsh.miuixbrowser.data.BLUR_OFF
import dev.dsh.miuixbrowser.ui.AppBottomSheet
import dev.dsh.miuixbrowser.ui.GlassLook
import dev.dsh.miuixbrowser.ui.LocalBlurMode
import dev.dsh.miuixbrowser.ui.LocalGlassParams
import dev.dsh.miuixbrowser.ui.LocalLiquidParams
import dev.dsh.miuixbrowser.ui.isAppDarkTheme
import dev.dsh.miuixbrowser.ui.CastPickerBody
import top.yukonga.miuix.kmp.basic.Scaffold
import dev.dsh.miuixbrowser.ui.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import android.widget.Toast
import android.provider.MediaStore
import android.os.Environment
import android.graphics.Bitmap
import android.content.ContentValues
import dev.dsh.miuixbrowser.util.Diag
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController


/** 卡片下方快捷菜单 + 视频标题区的底色不透明度（高级材质开启时；关闭时不透明）。越大越不透 */
private const val CARD_SURFACE_ALPHA = 0.95f

/**
 * 原生播放页：ExoPlayer 内核（[MafariPlayer]）+ 和 float-player.js 一致的 miuix 界面（[PlayerSurface]）。
 *
 * 形态和脚本一致：竖屏时是顶部一张 16:9 圆角卡片（✕ 关闭），点右下角全屏钮铺满屏幕并转横屏，
 * 全屏里左上角 ‹ 退出。
 * 卡片态下长按快捷菜单 / 标题区再拖动，可以上下移动整个播放器窗口（松手停在哪就在哪；全屏 / 画中画不受影响，退回卡片后仍在原位置）。
 *
 * 阶段三（同一个 [MafariPlayer] 实例，界面 / 画中画 / 后台共用，切换不重建播放器）：
 *  - 画中画：点右下角 PiP 按钮进入；播放中按 Home / 手势返回桌面自动进入；小窗操作栏 后退 10 秒 / 播放·暂停 / 快进 10 秒。
 *    小窗里只剩画面；点 ✕ 关闭小窗 = 结束播放。
 *  - 后台播放（菜单里可关，默认开）：不在画中画时切到后台 / 锁屏继续播放，由 [PlayerService] 提供前台服务和媒体通知；
 *    关掉时离开界面就暂停（原来的行为）。
 *  - 下载 / 复制链接 / 视频源切换：菜单里。
 *
 * 入口：[PlayerRequest.toIntent]（脚本宿主的 `play` 操作也走这里）。
 */
class NativePlayerActivity : ComponentActivity() {

    private var player: MafariPlayer? = null
    private var fullscreen by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    private var bgPlay by mutableStateOf(true)

    /** 从系统「打开方式」直接打开的本地视频：全屏起步，返回 / 退出全屏即关闭，没有卡片形态 */
    private var standalone = false
    private var orientationFitted = false
    private var pipAt = 0L // 最近一次进入画中画的时间，用来分辨「刚进小窗的过渡性 onStop」和「用户点 ✕ 关了小窗」
    private var pipReceiver: BroadcastReceiver? = null
    private var cast: NativeCast? = null

    /** 卡片态窗口距屏幕顶部的偏移（像素）：长按拖动快捷菜单 / 标题区改它；全屏 / 画中画 / 投屏面板铺满窗口时按 0 摆放，退回卡片后恢复 */
    private var cardY = 0

    /**
     * 「投屏到」底部弹窗的宿主是否在场。抽象层的弹窗（AppBottomSheet）要挂在 miuix Scaffold 的弹窗层上，
     * 播放页平时是只有卡片那么高的窗口，所以弹窗出现时才挂上宿主并把窗口撑满；弹窗收起动画结束后再还原。
     */
    private var castSheetHost by androidx.compose.runtime.mutableStateOf(false)

    /** 快捷菜单要显示的按钮（来自设置，空 = 整排不显示）；播放页打开期间设置不会变，开页时读一次 */
    private var quickItems: List<String> = emptyList()
    private var pipSuppressUntil = 0L // 拉起外部播放器 / 系统投屏前后一小段时间内不自动进画中画

    /** 局域网访问权限（Android 17 / targetSdk 37 起必需）：投屏搜索前调用，结果回调在主线程 */
    private var localNetCallback: ((Boolean) -> Unit)? = null
    private val localNetLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val cb = localNetCallback
        localNetCallback = null
        cb?.invoke(ok)
    }

    private fun withLocalNetworkPermission(onResult: (Boolean) -> Unit) {
        val perm = "android.permission.ACCESS_LOCAL_NETWORK"
        if (android.os.Build.VERSION.SDK_INT < 37 || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        localNetCallback = onResult
        localNetLauncher.launch(perm)
    }

    /** 即将拉起别的 Activity（外部播放器 / 系统投屏）：3 秒内不要因为 onUserLeaveHint 自动进画中画 */
    private fun suppressPip() {
        pipSuppressUntil = SystemClock.uptimeMillis() + 3000
        updatePip()
        window.decorView.postDelayed({ updatePip() }, 3100)
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 没授权服务照样跑，只是看不到通知 */ }

    /** ExoPlayer 状态变了就刷新画中画参数：操作栏的播放 / 暂停图标、宽高比、「播放中才自动进入」 */
    private val pipSync = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            updatePip()
            // 全屏起步时还不知道视频方向：第一次拿到尺寸后再按竖 / 横屏视频定一次屏幕方向
            if (standalone && fullscreen && !orientationFitted && videoSize.width > 0) {
                orientationFitted = true
                applyFullscreen(true)
            }
        }
    }

    /**
     * 窗口形态：卡片（悬浮）= 透明窗口、只占顶部卡片那么高，其余地方透出下面的网页（不再是整屏黑底）；
     * 全屏 / 画中画 = 铺满屏幕的黑底窗口。
     * 卡片态加 NOT_TOUCH_MODAL：卡片以外的触摸交给下面的页面。
     */
    private fun applyWindowMode() {
        val card = !fullscreen && !inPip
        // 「投屏到」面板打开时窗口临时铺满（背景仍透明），面板才不会被卡片那么高的窗口裁掉
        val wrap = card && !castSheetHost
        window.setBackgroundDrawable(ColorDrawable(if (card) AColor.TRANSPARENT else AColor.BLACK))
        if (wrap) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.TOP)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        } else {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        // 只有卡片（WRAP_CONTENT、贴顶）才带偏移；铺满窗口的形态一律 0
        window.attributes = window.attributes.also { it.y = if (wrap) cardY else 0 }
    }

    /** 卡片窗口最多能下移多少：屏幕高度减去窗口自己的高度 */
    private fun cardMaxY(): Int =
        (windowManager.currentWindowMetrics.bounds.height() - window.decorView.height).coerceAtLeast(0)

    /** 拖动时把卡片窗口移到距顶部 [y] 像素（限制在屏幕内）；只在卡片态生效 */
    private fun moveCard(y: Int) {
        if (fullscreen || inPip || castSheetHost) return
        val v = y.coerceIn(0, cardMaxY())
        if (v == cardY) return
        cardY = v
        window.attributes = window.attributes.also { it.y = v }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = PlayerRequest.from(intent)
        if (req == null) {
            finish()
            return
        }
        showing = true
        instance = this
        bgPlay = PlayerPrefs.bgPlay(this)
        quickItems = PlayerPrefs.quickItems(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        standalone = req.standalone
        applyFullscreen(standalone) // 里面会 applyWindowMode()；「打开方式」进来的本地视频直接全屏

        val p = MafariPlayer(this, req)
        player = p
        p.exo.addListener(pipSync)
        cast = NativeCast(this, { player }, ::withLocalNetworkPermission, ::suppressPip)
        PlayerService.closeCallback = { finish() }
        registerPipReceiver()
        updatePip()
        setContent {
            // 卡片下方的快捷菜单 / 标题 / 投屏面板跟随应用的显示模式（浅色 / 深色 / 跟随系统）和「高级材质」；
            // 叠在视频上的控件仍固定深色（见 PlayerPage 里嵌套的深色主题）
            val settings = remember { applicationContext.appContainer.settings }
            val mode = when (settings.themeMode) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            val themeController = remember(mode) { ThemeController(mode) }
            MiuixTheme(controller = themeController) {
                GlassLook.update(settings.blurMode, settings.glassParams)
                CompositionLocalProvider(
                    LocalBlurMode provides settings.blurMode,
                    LocalGlassParams provides settings.glassParams,
                    LocalLiquidParams provides settings.liquidParams,
                ) {
                    BackHandler(enabled = fullscreen) { if (standalone) finish() else applyFullscreen(false) }
                    PlayerPage(p, title = req.title.ifBlank { req.url })
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PlayerPage(p: MafariPlayer, title: String) {
        val cast = if (p.request.isLocal) null else cast // 本地文件不能投屏
        val pickerOpen = cast?.pickerUi?.show == true
        // 弹窗出现：先挂宿主并撑满窗口；收起动画结束（onDismissFinished）再还原
        LaunchedEffect(pickerOpen) { if (pickerOpen) castSheetHost = true }
        LaunchedEffect(castSheetHost) { applyWindowMode() }

        val view = LocalView.current
        val haptic = LocalHapticFeedback.current
        val dark = isAppDarkTheme()
        val glass = LocalBlurMode.current != BLUR_OFF
        val videoTheme = remember { ThemeController(ColorSchemeMode.Dark) }
        val surfaceColor = MiuixTheme.colorScheme.surfaceContainer.let { if (glass) it.copy(alpha = CARD_SURFACE_ALPHA) else it }
        val edgeColor = MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.14f else 0.10f)

        // 只有一处 PlayerSurface 调用，卡片 <-> 全屏切换时界面状态（弹层、控件显隐）不会丢；
        // 两种形态的外层结构相同（Column 套 Column），只换 Modifier
        val full = fullscreen || inPip
        val cardShape = RoundedCornerShape(20.dp)
        val outer = if (full) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            // 不 fillMaxHeight：窗口是 WRAP_CONTENT，铺满会把整屏变成透明的挡触摸层（投屏面板打开时窗口已铺满，卡片仍贴顶）
            Modifier.fillMaxWidth().then(if (castSheetHost) Modifier.fillMaxHeight() else Modifier)
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
        }
        val panel = if (full) {
            Modifier.fillMaxSize()
        } else {
            // 底色不再写死黑：视频区自己带黑底（letterbox），下面的快捷菜单 / 标题区用材质底色
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .border(1.dp, edgeColor, cardShape)
        }
        val surface = if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        // 卡片态状态栏在网页上方：图标颜色跟随显示模式；全屏 / 画中画是黑底，固定白图标
        LaunchedEffect(dark, full) {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            val light = !dark && !full
            c.isAppearanceLightStatusBars = light
            c.isAppearanceLightNavigationBars = light
        }
        Box {
        Column(outer) {
            Column(panel) {
                Box(surface) {
                    // 视频上的控件固定深色主题：不受浅色模式影响
                    MiuixTheme(controller = videoTheme) {
                    PlayerSurface(
                        player = p,
                        fullscreen = fullscreen,
                        onClose = { finish() },
                        onToggleFullscreen = { if (standalone) finish() else applyFullscreen(!fullscreen) },
                        modifier = Modifier.fillMaxSize(),
                        onPip = if (pipSupported()) ({ enterPip() }) else null,
                        onDownload = if (p.request.isLocal) null else ({ download(p) }),
                        onCopyLink = if (p.request.isLocal) null else ({ copyLink(p) }),
                        onCast = if (cast != null) ({ cast.openPicker() }) else null,
                        pip = inPip,
                        bgPlay = bgPlay,
                        onBgPlayChange = { changeBgPlay(it) },
                    )
                    // 投屏中：盖一层遥控（视频此时在电视上播，本地已暂停）
                    if (cast != null && cast.active && !inPip) CastOverlay(cast)
                    }
                }
                if (!full) {
                    // 快捷菜单 + 标题：同一块材质底色，浅色 / 深色随应用主题
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(surfaceColor)
                            // 长按再拖动：上下移动整个播放器窗口。窗口跟着手指走，所以不能用控件内的相对坐标（会越拖越抖），
                            // 一律换成屏幕坐标算偏移：屏幕 y = 控件内 y + 宿主 View 在屏幕上的 y（两者之差里的固定位移会抵消）
                            .pointerInput(Unit) {
                                var startRaw = 0f
                                var startY = 0
                                val loc = IntArray(2)
                                fun raw(localY: Float): Float {
                                    view.getLocationOnScreen(loc)
                                    return localY + loc[1]
                                }
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { o ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        startY = cardY
                                        startRaw = raw(o.y)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        moveCard((startY + (raw(change.position.y) - startRaw)).roundToInt())
                                    },
                                )
                            },
                    ) {
                        QuickActions(p, cast)
                        Text(
                            text = title,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        // 「投屏到」：抽象层的底部弹窗（和浏览器里其它 sheet 同一套材质）。弹窗层要 Scaffold 当宿主，只在需要时挂
        if (cast != null && castSheetHost) {
            Scaffold(
                modifier = Modifier.matchParentSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { _ ->
                AppBottomSheet(
                    show = cast.pickerUi.show,
                    title = tr("投屏到"),
                    backdrop = null,
                    onDismissRequest = { cast.pickerUi.cancel() },
                    onDismissFinished = { castSheetHost = false },
                ) {
                    CastPickerBody(cast.pickerUi)
                }
            }
        }
        }
    }

    /**
     * 卡片下方、标题上方的快捷菜单（图标 + 文字）：投屏 / 外部播放 / 下载 / 截屏 / 倍速 / 清晰度 / 视频源，
     * 设置 → 媒体嗅探与播放 → 快捷菜单 里可整排关掉或逐个关。
     * 点「倍速」/「清晰度」/「视频源」这一排换成对应的选项，选完（或点返回）恢复。
     */
    @androidx.compose.runtime.Composable
    private fun QuickActions(p: MafariPlayer, cast: NativeCast?) {
        if (quickItems.isEmpty()) return
        // 0 = 按钮排；1 = 倍速选项；2 = 清晰度选项；3 = 视频源选项
        var page by remember { mutableStateOf(0) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
        when (page) {
            1 -> OptionRow(onBack = { page = 0 }) {
                PlayerPrefs.SPEEDS.forEach { v ->
                    CastButton(speedLabel(v), strong = Math.abs(p.speed - v) < 0.01f, compact = true) { p.changeSpeed(v); page = 0 }
                }
            }
            2 -> OptionRow(onBack = { page = 0 }) {
                CastButton(tr("自动"), strong = p.selectedQuality == null, compact = true) { p.selectQuality(null); page = 0 }
                p.qualities.forEach { q ->
                    CastButton(q.label, strong = p.selectedQuality == q, compact = true) { p.selectQuality(q); page = 0 }
                }
            }
            3 -> OptionRow(onBack = { page = 0 }) {
                p.sources.forEachIndexed { i, s ->
                    CastButton(
                        s.label.ifBlank { tr("视频源 {0}", i + 1) },
                        strong = p.sourceIndex == i,
                        compact = true,
                    ) {
                        if (i != p.sourceIndex) {
                            p.playSource(i)
                            Toast.makeText(this@NativePlayerActivity, tr("已切换视频源"), Toast.LENGTH_SHORT).show()
                        }
                        page = 0
                    }
                }
            }
            else -> Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)) {
                quickItems.forEach { id ->
                    when (id) {
                        "cast" -> QuickAction(AppIcons.ScreenMirror, tr("投屏"), Modifier.weight(1f), enabled = cast != null) { cast?.openPicker() }
                        "external" -> QuickAction(AppIcons.External, tr("外部播放"), Modifier.weight(1f)) { openExternal(p) }
                        "download" -> QuickAction(MiuixIcons.Glass.Download, tr("下载"), Modifier.weight(1f), enabled = !p.request.isLocal) {
                            download(p)
                            Toast.makeText(this@NativePlayerActivity, tr("已加入下载"), Toast.LENGTH_SHORT).show()
                        }
                        "shot" -> QuickAction(AppIcons.Screenshot, tr("截屏"), Modifier.weight(1f)) { screenshot(p) }
                        "speed" -> QuickAction(AppIcons.Speed, if (p.speed == 1f) tr("倍速") else speedLabel(p.speed), Modifier.weight(1f)) { page = 1 }
                        "quality" -> QuickAction(
                            AppIcons.Quality,
                            p.selectedQuality?.label ?: tr("清晰度"),
                            Modifier.weight(1f),
                            enabled = p.qualities.size > 1, // 只有一档（或还没解析出来）就灰掉
                        ) { page = 2 }
                        "source" -> QuickAction(
                            AppIcons.Source,
                            if (p.sources.size > 1) tr("视频源 {0}", p.sourceIndex + 1) else tr("视频源"),
                            Modifier.weight(1f),
                            enabled = p.sources.size > 1, // 只有一个源就灰掉
                        ) { page = 3 }
                    }
                }
            }
        }
    }

    /** 选项排：最左一个「返回」，后面是横向滚动的选项 */
    @androidx.compose.runtime.Composable
    private fun OptionRow(onBack: () -> Unit, content: @androidx.compose.runtime.Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CastButton(tr("返回"), compact = true) { onBack() }
            content()
        }
    }

    @androidx.compose.runtime.Composable
    private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
        val fg = MiuixTheme.colorScheme.onSurface
        Column(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(if (enabled) fg else fg.copy(alpha = 0.35f)),
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = label,
                color = if (enabled) fg.copy(alpha = 0.8f) else fg.copy(alpha = 0.35f),
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // ------------------------------------------------------------------ 截屏

    /** 截取当前画面，存到 图片/Mafari（MediaStore，免存储权限）。拿不到画面（还没出画面 / 受保护内容）会提示 */
    private fun screenshot(p: MafariPlayer) {
        p.captureFrame { bmp ->
            if (bmp == null) {
                Toast.makeText(this, tr("截屏失败：当前没有可截取的画面（还没出画面，或是受保护的内容）"), Toast.LENGTH_LONG).show()
                return@captureFrame
            }
            val ctx = applicationContext
            Thread {
                val ok = runCatching {
                    val name = "Mafari_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mafari")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
                    try {
                        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        ctx.contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        ctx.contentResolver.delete(uri, null, null)
                        throw e
                    }
                }.isSuccess
                bmp.recycle()
                runOnUiThread {
                    Toast.makeText(ctx, if (ok) tr("截图已保存到相册（Mafari）") else tr("截图保存失败"), Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }

    /** 投屏遥控：设备名 / 状态 / 进度 + 后退 · 播放暂停 · 快进 · 结束投屏 */
    @androidx.compose.runtime.Composable
    private fun CastOverlay(cast: NativeCast) {
        val name = cast.deviceName.orEmpty()
        Box(
            Modifier.fillMaxSize().background(Color(0xEB000000)).clickable(enabled = true, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (cast.connecting) tr("正在连接「{0}」…", name) else tr("正在投屏到 {0}", name),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    text = if (cast.connecting) tr("等待电视开始播放…") else castClock(cast.posSec, cast.durSec),
                    color = Color(0x99FFFFFF),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CastButton(tr("后退 10 秒"), onVideo = true, enabled = !cast.connecting) { cast.seekBy(-10) }
                    CastButton(if (cast.playing) tr("暂停") else tr("播放"), onVideo = true, enabled = !cast.connecting) { cast.togglePlay() }
                    CastButton(tr("快进 10 秒"), onVideo = true, enabled = !cast.connecting) { cast.seekBy(10) }
                }
                CastButton(tr("结束投屏"), strong = true, onVideo = true, modifier = Modifier.padding(top = 10.dp)) { cast.stop() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CastButton(
        label: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        strong: Boolean = false,
        compact: Boolean = false,
        /** true = 叠在视频上（固定白字深底）；false = 在卡片下方的材质底上，随主题 */
        onVideo: Boolean = false,
        onClick: () -> Unit,
    ) {
        val shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
        val fg = if (onVideo) Color.White else MiuixTheme.colorScheme.onSurface
        val chip = if (onVideo) Color(0x26FFFFFF) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        Box(
            modifier
                .clip(shape)
                .background(if (strong) Color(0xFF3B7DFF) else chip)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 3.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (strong) Color.White else if (enabled) fg else fg.copy(alpha = 0.35f),
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    private fun castClock(pos: Int, dur: Int): String {
        fun f(sec: Int): String {
            val h = sec / 3600
            val m = sec % 3600 / 60
            val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
        return if (dur > 0) "${f(pos)} / ${f(dur)}" else f(pos)
    }

    /**
     * 全屏：藏系统栏（边缘滑动临时唤出）+ 横屏（视频是横向或尚未知尺寸时）；
     * 退出：显示系统栏 + 锁回竖屏。方向由按钮驱动，和脚本的全屏按钮一致。
     */
    private fun applyFullscreen(on: Boolean) {
        fullscreen = on
        applyWindowMode()
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            val p = player
            val portraitVideo = p != null && p.videoWidth > 0 && p.videoHeight > p.videoWidth
            requestedOrientation = if (portraitVideo) {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    // ------------------------------------------------------------------ 下载 / 复制链接

    /** 交给 App 下载器：普通文件按设置里选的下载器；m3u8 由 App 自己取清单、下分片、合并（前台服务，离开页面也不中断）。 */
    private fun download(p: MafariPlayer) {
        val r = p.currentRequest
        val ua = r.ua.takeIf { it.isNotBlank() }
        val referer = r.referer.takeIf { it.isNotBlank() }
        val name = r.downloadName()
        val ctx = applicationContext
        if (r.isHls()) {
            Downloader.startHls(ctx, r.url, ua, name, referer, true, r.gecko)
        } else {
            Downloader.start(ctx, r.url, ua, name, null, null, referer, true, r.gecko)
        }
    }

    /**
     * 交给系统里别的播放器：弹出系统「用其他应用打开」选择器（本 App 自己排除在外）。
     * 网络地址带 UA / Referer / Cookie / 当前进度（和投屏面板里的「外部打开」同一套，见 [MediaHost.launchExternal]）；
     * 本地文件（content:// / file://）走普通 VIEW + 临时读权限。拉起前暂停播放，避免两边同时出声；3 秒内不自动进画中画。
     */
    private fun openExternal(p: MafariPlayer) {
        val r = p.currentRequest
        val wasPlaying = p.exo.isPlaying
        val ok = if (r.isLocal) {
            val uri = Uri.parse(r.url)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("title", r.title)
            val chooser = Intent.createChooser(i, tr("选择播放器 / 投屏 App")).putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)),
            )
            suppressPip()
            runCatching { startActivity(chooser) }.isSuccess
        } else {
            val o = JSONObject()
                .put("url", r.url).put("title", r.title).put("type", r.type)
                .put("ua", r.ua).put("referer", r.referer).put("cookie", r.cookie)
                .put("pos", (p.exo.currentPosition / 1000).toInt())
            MediaHost.launchExternal(this, o, beforeLaunch = ::suppressPip)
        }
        if (ok) {
            if (wasPlaying) p.exo.pause()
        } else {
            Toast.makeText(this, tr("没有找到可用的播放器"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLink(p: MafariPlayer) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("video", p.currentRequest.url))
    }

    // ------------------------------------------------------------------ 画中画

    private fun pipSupported(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipAction(code: Int, icon: Int, title: String, act: String) = RemoteAction(
        Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(
            this, code, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_ACT, act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun pipParams(): PictureInPictureParams {
        val p = player
        val w = p?.videoWidth ?: 0
        val h = p?.videoHeight ?: 0
        // 系统只接受 1:2.39 ~ 2.39:1；尺寸未知时先按 16:9
        val ratio = if (w > 0 && h > 0) (w.toDouble() / h).coerceIn(0.42, 2.39) else 16.0 / 9.0
        val playing = p?.isPlaying == true
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(
                listOf(
                    pipAction(1, android.R.drawable.ic_media_rew, tr("后退 10 秒"), "back"),
                    if (playing) pipAction(2, android.R.drawable.ic_media_pause, tr("暂停"), "pause")
                    else pipAction(3, android.R.drawable.ic_media_play, tr("播放"), "play"),
                    pipAction(4, android.R.drawable.ic_media_ff, tr("快进 10 秒"), "fwd"),
                ),
            )
            // 播放中按 Home / 手势返回桌面，系统直接进画中画（比 onUserLeaveHint 更稳，没有切换闪烁）
            .setAutoEnterEnabled(playing && pipSupported() && SystemClock.uptimeMillis() >= pipSuppressUntil)
            .setSeamlessResizeEnabled(false)
            .build()
    }

    private fun updatePip() {
        if (isDestroyed || isFinishing || !pipSupported()) return
        runCatching { setPictureInPictureParams(pipParams()) }.onFailure { Diag.log("NativePlayer: setPictureInPictureParams $it") }
    }

    private fun enterPip() {
        if (!pipSupported() || isInPictureInPictureMode) return
        pipAt = SystemClock.uptimeMillis()
        runCatching { enterPictureInPictureMode(pipParams()) }.onFailure { Diag.log("NativePlayer: enterPictureInPictureMode $it") }
    }

    /** 小窗操作栏的按钮：经广播回到这里，驱动同一个播放器 */
    private fun registerPipReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val p = player ?: return
                when (i.getStringExtra(EXTRA_ACT)) {
                    "back" -> p.seekBy(-SKIP_MS)
                    "fwd" -> p.seekBy(SKIP_MS)
                    "play" -> p.play()
                    "pause" -> p.pause()
                }
            }
        }
        ContextCompat.registerReceiver(this, r, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiver = r
    }

    /** 按 Home / 切任务：系统没替我们自动进画中画时的兜底（部分 ROM 不认 autoEnter） */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !isInPictureInPictureMode && SystemClock.uptimeMillis() >= pipSuppressUntil) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        applyWindowMode()
        if (isInPictureInPictureMode) pipAt = SystemClock.uptimeMillis()
    }

    // ------------------------------------------------------------------ 后台播放

    private fun changeBgPlay(on: Boolean) {
        bgPlay = on
        PlayerPrefs.setBgPlay(this, on)
        if (on) startBgService() else PlayerService.stop(this)
    }

    /** 界面可见时启动前台服务（后台启动前台服务会被系统拦）；Android 13+ 还要通知权限才看得到通知栏的控制条 */
    private fun startBgService() {
        val p = player ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PlayerService.start(this, p)
    }

    override fun onStart() {
        super.onStart()
        if (bgPlay) startBgService()
    }

    override fun onStop() {
        super.onStop()
        val p = player ?: return
        if (isFinishing) return
        if (isInPictureInPictureMode || inPip) {
            // 刚进小窗的 700ms 内个别系统会先回调一次 onStop：不是退后台，别暂停。
            // 之后再来的 onStop = 用户点了小窗的 ✕，结束播放。
            if (SystemClock.uptimeMillis() - pipAt > 700) {
                p.pause()
                finish()
            }
            return
        }
        // 不在小窗：后台播放开着就继续（服务保活 + 通知栏控制），关着就暂停
        if (!bgPlay) p.pause()
    }

    override fun onDestroy() {
        // 切换媒体时新播放页可能先于旧页销毁：只有自己仍是当前实例才清全局状态，别把新页的状态抹掉
        val owner = instance === this
        if (owner) {
            instance = null
            showing = false
        }
        cast?.release()
        cast = null
        if (owner) {
            PlayerService.stop(this)
            PlayerService.closeCallback = null
            PlayerService.current = null
        }
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        pipReceiver = null
        player?.exo?.removeListener(pipSync)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        /** 播放页正开着：嗅探「自动悬浮播放」据此不重复拉起第二个播放器 */
        @Volatile var showing = false
            private set

        private var instance: NativePlayerActivity? = null

        /** 播放页正在播的地址和它的全部源（去 #片段）：嗅探据此判断页面新出现的视频是不是已经在播的这一条 */
        fun playingUrls(): Set<String> {
            val p = instance?.player ?: return emptySet()
            return (p.sources.map { it.url } + p.currentRequest.url).map { it.substringBefore('#') }.toSet()
        }

        /** 关掉正开着的播放页（地址栏媒体弹窗里切换到另一条媒体前调用，避免叠出两个播放器）。 */
        fun closeCurrent() {
            instance?.let { if (!it.isFinishing) it.finish() }
        }

        private const val ACTION_PIP = "dev.dsh.miuixbrowser.player.PIP_ACT"
        private const val EXTRA_ACT = "a"
        private const val SKIP_MS = 10_000L
    }
}
