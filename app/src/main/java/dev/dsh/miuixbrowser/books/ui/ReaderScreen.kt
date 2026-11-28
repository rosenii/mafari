/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.ui

import dev.dsh.miuixbrowser.ui.*

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.READER_PAGE_TURN_SIMULATED
import dev.dsh.miuixbrowser.data.READER_PAGE_TURN_VERTICAL
import dev.dsh.miuixbrowser.data.READER_THEME_AUTO
import dev.dsh.miuixbrowser.tts.SpeechEngineStore
import dev.dsh.miuixbrowser.tts.SpeechPlayer
import dev.dsh.miuixbrowser.tts.SpeechState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Earphones
import dev.dsh.miuixbrowser.data.READER_THEME_DARK
import dev.dsh.miuixbrowser.data.READER_THEME_EYE
import dev.dsh.miuixbrowser.data.READER_THEME_LIGHT
import dev.dsh.miuixbrowser.data.READER_THEME_PAPER
import dev.dsh.miuixbrowser.books.data.Block
import dev.dsh.miuixbrowser.books.data.Book
import dev.dsh.miuixbrowser.books.data.BookContent
import dev.dsh.miuixbrowser.books.data.BookShelf
import dev.dsh.miuixbrowser.books.data.Chapter
import dev.dsh.miuixbrowser.data.READER_FONT_MAX
import dev.dsh.miuixbrowser.data.READER_FONT_MIN
import dev.dsh.miuixbrowser.data.READER_LINE_MAX
import dev.dsh.miuixbrowser.data.READER_LINE_MIN
import dev.dsh.miuixbrowser.data.READER_PAD_H_MAX
import dev.dsh.miuixbrowser.data.READER_PAD_H_MIN
import dev.dsh.miuixbrowser.data.READER_PAD_V_MAX
import dev.dsh.miuixbrowser.data.READER_PAD_V_MIN
import dev.dsh.miuixbrowser.engine.gecko.findActivity
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.roundToInt

/* ------------------------------ 状态 ------------------------------ */

/** 一页：第 [ch] 章的第 [idx] 页。 */
private data class PageRef(val ch: Int, val idx: Int)

/** 一页的内容：一段文字，或一张图（[Image.path] 是 EPUB 压缩包内的条目名）。 */
private sealed interface PageBody {
    class Text(val text: String) : PageBody
    class Image(val path: String) : PageBody
}

/**
 * 一章排好版的结果：每页的内容、每页起点在章内的偏移。
 * 偏移与排版无关：文字按字符数算，每张图占 1，块与块之间隔 1（所以纯文字章节的偏移就是字符偏移）。
 * [length] 是整章的偏移总长，用来算章内进度。
 */
private class PagedChapter(val pages: List<PageBody>, val starts: IntArray, val length: Int)

/** 排版留白（页面四周与页眉页脚的位置），由设置里的边距算出。 */
private class ReaderLayout(val padH: Dp, val padTop: Dp, val padBottom: Dp, val metaTop: Dp, val footerBottom: Dp)

/** 整本书的阅读百分比（0..1）：按「章 + 章内位置」折算；读到最后一章的最后一页算 100%。 */
private fun percentAt(ch: Int, pc: PagedChapter, idx: Int, chapterCount: Int): Float {
    if (ch == chapterCount - 1 && idx >= pc.pages.lastIndex) return 1f
    return ((ch + pc.starts[idx].toFloat() / pc.length.coerceAtLeast(1)) / chapterCount).coerceIn(0f, 1f)
}

/** 解码好的 EPUB 图片的小缓存（约 32MB），翻回去时不用再解压解码。 */
private val imageCache = object : android.util.LruCache<String, ImageBitmap>(32 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4L / 1024).toInt().coerceAtLeast(1)
}

private class ReaderJump(val chapter: Int)

/** 阅读位置（章节下标 + 章内字符偏移，与排版无关）和章节跳转请求。 */
@Stable
private class ReaderController(chapter: Int, offset: Int) {
    var chapter by mutableIntStateOf(chapter)
    var offset by mutableIntStateOf(offset)
    var percent by mutableFloatStateOf(0f)
    var jump by mutableStateOf<ReaderJump?>(null)
}

/* ------------------------------ 文字背景 ------------------------------ */

/** 阅读背景：底色、文字色，以及是否叠加纸张纹理。 */
@Stable
private class ReaderTheme(val bg: Color, val ink: Color, val textured: Boolean = false)

private val ReaderPaper = ReaderTheme(Color(0xFFF5F0E6), Color(0xFF2E2A25), textured = true)
private val ReaderEye = ReaderTheme(Color(0xFFCCE8CF), Color(0xFF243127))
private val ReaderLight = ReaderTheme(Color(0xFFFFFFFF), Color(0xFF222222))
private val ReaderDark = ReaderTheme(Color(0xFF141416), Color(0xFFB4B4BA))

private fun readerThemeOf(id: Int): ReaderTheme = when (id) {
    READER_THEME_EYE -> ReaderEye
    READER_THEME_LIGHT -> ReaderLight
    READER_THEME_DARK -> ReaderDark
    else -> ReaderPaper
}

/** 「跟随应用」时：应用深色 → 深色，否则纸质纹理（与加这个设置之前的外观一致）。 */
private fun resolveReaderTheme(saved: Int, appDark: Boolean): ReaderTheme =
    if (saved == READER_THEME_AUTO) (if (appDark) ReaderDark else ReaderPaper) else readerThemeOf(saved)

/** 纸张纹理：128px 的随机暖褐色细点，平铺。固定种子，每次启动一样。 */
private val paperTile: ImageBitmap by lazy {
    val n = 128
    val rnd = java.util.Random(20260)
    val px = IntArray(n * n) {
        val a = rnd.nextInt(26)           // 0..25 的很淡的透明度
        (a shl 24) or 0x7A5C34            // 暖褐
    }
    Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 画阅读背景。必须用在被玻璃控件采样的内容层里（见 ReaderBody）。 */
private fun Modifier.readerBackground(theme: ReaderTheme): Modifier =
    if (!theme.textured) {
        background(theme.bg)
    } else {
        drawBehind {
            drawRect(theme.bg)
            drawRect(
                object : ShaderBrush() {
                    override fun createShader(size: androidx.compose.ui.geometry.Size) =
                        ImageShader(paperTile, TileMode.Repeated, TileMode.Repeated)
                },
            )
        }
    }

/* ------------------------------ 页面 ------------------------------ */

/**
 * 小说阅读器：极简翻页阅读。
 *
 * 点屏幕左 / 右侧翻页（也可左右滑动，章节之间无缝衔接），点中间呼出控件：返回、右上角「Aa」阅读设置
 * （字号 / 行距 / 页边距）、上一章 / 目录 / 下一章，目录是底部 sheet，点章节直接跳转。
 * 阅读位置按「章节 + 章内偏移」自动保存，下次打开回到原处。EPUB 里的图片整页显示。
 * 控件全部用抽象层材质（液态玻璃 / miuix 玻璃 / 关闭模糊三种随「高级材质」设置切换）。
 */
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, settings: AppSettings, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val book = remember(bookId) { BookShelf.books.firstOrNull { it.id == bookId } }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var failed by remember { mutableStateOf(false) }
    /** 远端章节下载完成后 +1：让分页器丢掉旧的那一页、按新正文重排 */
    var revision by remember { mutableIntStateOf(0) }
    val loading = remember { HashSet<Int>() }

    // 远端书：滑到哪章附近就下哪章；下完替换成真实章节，失败就留一段可读的提示
    fun ensureChapter(index: Int) {
        val b = book ?: return
        val list = chapters ?: return
        val ch = list.getOrNull(index) ?: return
        if (!ch.pending || !loading.add(index)) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { BookContent.loadRemoteChapter(context, b, index) }.getOrElse { e ->
                    Chapter(
                        title = ch.title,
                        text = tr("本章下载失败：{0}。可以翻到下一章继续读，或稍后重新打开再试。", e.message.orEmpty()),
                        index = index,
                    )
                }
            }
            loading.remove(index)
            val now = chapters
            if (now != null && index in now.indices && now[index].pending) {
                chapters = now.toMutableList().also { it[index] = loaded }
                revision++
            }
        }
    }

    LaunchedEffect(bookId) {
        if (book == null) {
            failed = true
            return@LaunchedEffect
        }
        val r = withContext(Dispatchers.IO) { runCatching { BookContent.load(context, book) }.getOrNull() }
        if (r.isNullOrEmpty()) failed = true else {
            chapters = r
            // 先下当前章（和下一章），打开不用等整本
            ensureChapter(book.chapter)
            ensureChapter(book.chapter + 1)
        }
    }

    // 「自动」跟随系统深浅色，而不是应用内固定模式
    val theme = resolveReaderTheme(settings.readerTheme, isSystemInDarkTheme())
    val ink = theme.ink

    Box(modifier = Modifier.fillMaxSize().readerBackground(theme)) {
        val list = chapters
        if (book != null && list != null) {
            ReaderBody(
                book = book,
                chapters = list,
                revision = revision,
                onNeedChapter = { ensureChapter(it) },
                settings = settings,
                onBack = onBack,
                blurEnabled = blurEnabled,
                ink = ink,
                theme = theme,
            )
        } else {
            Text(
                text = if (failed) tr("无法读取这本书") else tr("加载中…"),
                fontSize = 14.sp,
                color = ink.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center),
            )
            Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp)) {
                LiquidGlassBackButton(backdrop = null, surfaceVisible = true, onClick = onBack)
            }
        }
    }
}

@Composable
private fun ReaderBody(
    book: Book,
    chapters: List<Chapter>,
    revision: Int,
    onNeedChapter: (Int) -> Unit,
    settings: AppSettings,
    onBack: () -> Unit,
    blurEnabled: Boolean,
    ink: Color,
    theme: ReaderTheme,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val bd = if (glass) backdrop else null

    val ctl = remember(book.id) {
        ReaderController(book.chapter.coerceIn(0, chapters.lastIndex), book.offset.coerceAtLeast(0)).also { it.percent = book.progress }
    }

    val scope = rememberCoroutineScope()
    val speech = remember { SpeechPlayer(context, scope) }
    var showSpeech by remember { mutableStateOf(false) }
    /** 自动下一章：本章读完先标记，等新章正文到齐再接着读 */
    var speechNext by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { speech.release() } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SpeechEngineStore.load(context) } }

    fun startSpeech(fromChapterStart: Boolean = false) {
        val ch = chapters.getOrNull(ctl.chapter) ?: return
        if (ch.pending) {
            Toast.makeText(context, tr("本章还在下载，稍后再听"), Toast.LENGTH_SHORT).show()
            return
        }
        val offset = if (fromChapterStart) 0 else ctl.offset.coerceIn(0, ch.text.length)
        val text = ch.text.substring(offset).ifBlank { ch.text }
        speech.start(SpeechEngineStore.current(), text, SpeechEngineStore.speed) {
            if (SpeechEngineStore.autoNext && ctl.chapter < chapters.lastIndex) {
                speechNext = true
                ctl.jump = ReaderJump(ctl.chapter + 1)
            }
        }
    }

    // 翻到新章节 / 有章节下载完成：把当前章和前后各一章排进下载队列
    LaunchedEffect(ctl.chapter, revision, chapters) {
        onNeedChapter(ctl.chapter)
        onNeedChapter(ctl.chapter - 1)
        onNeedChapter(ctl.chapter + 1)
        if (speechNext) {
            val ch = chapters.getOrNull(ctl.chapter)
            if (ch != null && !ch.pending) {
                speechNext = false
                startSpeech(fromChapterStart = true)
            }
        }
    }
    var overlay by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val bookFile = remember(book.id, book.fileName) { BookShelf.bookFile(context, book) }

    // 阅读位置变了就存（BookShelf 内部会跳过没变化的写入）
    LaunchedEffect(ctl) {
        snapshotFlow { Triple(ctl.chapter, ctl.offset, ctl.percent) }.collect { (c, o, p) ->
            withContext(Dispatchers.IO) { BookShelf.saveProgress(context, book.id, c, o, p) }
        }
    }

    // 沉浸：阅读时隐藏系统栏，呼出控件时显示；离开页面恢复
    val window = context.findActivity()?.window
    DisposableEffect(overlay) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (overlay) {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }
    DisposableEffect(Unit) {
        onDispose { window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }

    // LocalPageGlassBackdrop：返回 / Aa / 上一章 / 目录 / 下一章 不在顶栏里，miuix 材质下要显式给采样源，否则退回平底色（透明）
    CompositionLocalProvider(LocalLiquidBackdrop provides liquid, LocalPageGlassBackdrop provides bd) {
        // 阅读器没有顶栏，但 sheet（目录 / 阅读设置）是 miuix 的 Overlay 弹层，由 Scaffold 的弹层宿主渲染；
        // 没有 Scaffold 就一直不显示。所以用一个不画任何东西的 Scaffold 当宿主（透明底、无 insets），整页放在它的内容里
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            // 内容层：被玻璃控件采样。背景（纸色 / 纹理）必须画在录制层「里面」（同其他页面的 pageBackground 写法）：
            // 否则采样到的是「透明底 + 文字」，柔光玻璃 / 磨砂模糊时半透明像素被错误混合，控件和 sheet 上就是一块块脏色块
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .readerBackground(theme),
            ) {
                ReaderPager(
                    chapters = chapters,
                    bookFile = bookFile,
                    ctl = ctl,
                    revision = revision,
                    ink = ink,
                    pageBackground = Modifier.readerBackground(theme),
                    settings = settings,
                    onToggleOverlay = { overlay = !overlay },
                )
            }

            AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp)) {
                        LiquidGlassBackButton(backdrop = liquid, surfaceVisible = true, onClick = onBack)
                    }
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LiquidGlassButton(onClick = { showSettings = true }, backdrop = liquid) {
                            Text(
                                text = "Aa",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        LiquidGlassButton(onClick = { showSpeech = true }, backdrop = liquid) {
                            Icon(
                                imageVector = MiuixIcons.Glass.Earphones,
                                contentDescription = tr("听书"),
                                modifier = Modifier.size(22.dp),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LiquidGlassPillButton(
                            text = tr("上一章"),
                            onClick = { ctl.jump = ReaderJump(ctl.chapter - 1) },
                            backdrop = liquid,
                            enabled = ctl.chapter > 0,
                            modifier = Modifier.weight(1f),
                        )
                        LiquidGlassPillButton(
                            text = tr("目录"),
                            onClick = { showToc = true },
                            backdrop = liquid,
                            modifier = Modifier.weight(1f),
                        )
                        LiquidGlassPillButton(
                            text = tr("下一章"),
                            onClick = { ctl.jump = ReaderJump(ctl.chapter + 1) },
                            backdrop = liquid,
                            enabled = ctl.chapter < chapters.lastIndex,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 阅读设置：不压暗背景，调整时上面的文字能直接看到效果（松手后重新排版）
            AppBottomSheet(
                show = showSettings,
                title = tr("阅读设置"),
                backdrop = bd,
                onDismissRequest = { showSettings = false },
                enableWindowDim = false,
            ) {
                ReaderSettingsPanel(settings, bd)
            }

            // 听书：播放 / 暂停 / 语速 / 语音引擎
            SpeechSheet(
                show = showSpeech,
                backdrop = bd,
                onDismiss = { showSpeech = false },
                player = speech,
                chapterTitle = chapters.getOrNull(ctl.chapter)?.title.orEmpty(),
                onPlay = { startSpeech() },
                onPause = { speech.pause() },
                onStop = {
                    speechNext = false
                    speech.stop()
                },
            )

            // 目录：顶部章节进度条（左右拖动快速切章，下面的列表跟着滚）；点章节直接跳转
            AppBottomSheet(
                show = showToc,
                title = tr("目录"),
                backdrop = bd,
                onDismissRequest = { showToc = false },
                scrollContent = true,
            ) {
                val insets = LocalBottomSheetInsets.current
                val haptic = LocalHapticFeedback.current
                val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
                val listState = rememberLazyListState()
                val last = chapters.lastIndex

                // 进度条的草稿值：拖动时跟手，松手才真正跳转；不拖动时跟着当前阅读的章节走
                var dragging by remember { mutableStateOf(false) }
                var draft by remember { mutableFloatStateOf(ctl.chapter.toFloat()) }
                val draftIdx = draft.roundToInt().coerceIn(0, last)
                LaunchedEffect(ctl.chapter, showToc) { if (!dragging) draft = ctl.chapter.toFloat() }

                LaunchedEffect(showToc) {
                    if (showToc) listState.scrollToItem((ctl.chapter - 3).coerceAtLeast(0))
                }
                // 拖动时列表跟随：目标章节保持在靠上的位置（和打开时一致，前面留 3 章上下文）；每跨一章轻震一下
                LaunchedEffect(draftIdx, dragging) {
                    if (dragging) {
                        listState.scrollToItem((draftIdx - 3).coerceAtLeast(0))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().padding(top = insets.top)) {
                    if (last > 0) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = chapters[draftIdx].title,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                )
                                Text(
                                    text = "${draftIdx + 1} / ${last + 1}",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Slider(
                                value = draft,
                                onValueChange = { raw ->
                                    dragging = true
                                    draft = raw.roundToInt().toFloat().coerceIn(0f, last.toFloat())
                                },
                                onValueChangeFinished = {
                                    dragging = false
                                    if (draftIdx != ctl.chapter) ctl.jump = ReaderJump(draftIdx)
                                },
                                valueRange = 0f..last.toFloat(),
                            )
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 进度条那块占了约 76dp，列表相应矮一点，整个 sheet 高度不变
                            .heightIn(max = (maxHeight + insets.bottom - (if (last > 0) 76.dp else 0.dp)).coerceAtLeast(120.dp)),
                        contentPadding = PaddingValues(bottom = insets.bottom + 12.dp),
                    ) {
                    itemsIndexed(chapters) { i, c ->
                        val current = i == (if (dragging) draftIdx else ctl.chapter)
                        Text(
                            text = c.title,
                            fontSize = 15.sp,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            color = if (current) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .squircleClip(12.dp)
                                .clickable {
                                    showToc = false
                                    overlay = false
                                    ctl.jump = ReaderJump(i)
                                }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                        )
                    }
                    }
                }
            }
        }
        }
    }
}

/* ------------------------------ 阅读设置 ------------------------------ */

@Composable
private fun ReaderSettingsPanel(settings: AppSettings, backdrop: top.yukonga.miuix.kmp.blur.Backdrop?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ReaderThemeRow(settings)
        AppDropdownPreference(
            onSelectedIndexChange = { settings.updateReaderPageTurn(it) },
            items = listOf(tr("上下翻页"), tr("左右平滑"), tr("仿真翻页")),
            selectedIndex = settings.readerPageTurn,
            title = tr("翻页方式"),
            backdrop = backdrop,
            // 这一行和字号 / 行距等滑块共用 sheet 的左右留白，不要再叠加组件默认内边距
            insideMargin = PaddingValues(0.dp),
        )
        ReaderSliderRow(
            name = tr("字号"),
            value = settings.readerFontSp.toFloat(),
            range = READER_FONT_MIN.toFloat()..READER_FONT_MAX.toFloat(),
            step = 1f,
            show = { "${it.roundToInt()}" },
            onCommit = { settings.updateReaderFontSp(it.roundToInt()) },
        )
        ReaderSliderRow(
            name = tr("行距"),
            value = settings.readerLinePct.toFloat(),
            range = READER_LINE_MIN.toFloat()..READER_LINE_MAX.toFloat(),
            step = 5f,
            show = { String.format("%.2f", it / 100f) },
            onCommit = { settings.updateReaderLinePct(it.roundToInt()) },
        )
        ReaderSliderRow(
            name = tr("左右边距"),
            value = settings.readerPadH.toFloat(),
            range = READER_PAD_H_MIN.toFloat()..READER_PAD_H_MAX.toFloat(),
            step = 2f,
            show = { "${it.roundToInt()}" },
            onCommit = { settings.updateReaderPadH(it.roundToInt()) },
        )
        ReaderSliderRow(
            name = tr("上下边距"),
            value = settings.readerPadV.toFloat(),
            range = READER_PAD_V_MIN.toFloat()..READER_PAD_V_MAX.toFloat(),
            step = 2f,
            show = { "${it.roundToInt()}" },
            onCommit = { settings.updateReaderPadV(it.roundToInt()) },
        )
        Spacer(Modifier.height(4.dp))
        AppTextButton(text = tr("恢复默认"), onClick = { settings.resetReaderLayout() }, modifier = Modifier.fillMaxWidth())
    }
}

/** 一行滑块：拖动时只更新右侧数字，松手才提交（提交会让整本书重新排版，拖动过程中不重排）。 */
/** 「文字背景」：四个色块可选，当前项描边。 */
@Composable
private fun ReaderThemeRow(settings: AppSettings) {
    val appDark = isSystemInDarkTheme()
    val current = settings.readerTheme.let { if (it == READER_THEME_AUTO) (if (appDark) READER_THEME_DARK else READER_THEME_PAPER) else it }
    val options = listOf(
        READER_THEME_PAPER to tr("纸质纹理"),
        READER_THEME_EYE to tr("护眼"),
        READER_THEME_LIGHT to tr("浅色"),
        READER_THEME_DARK to tr("深色"),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = tr("文字背景"),
            style = MiuixTheme.textStyles.headline1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (id, label) ->
                val t = readerThemeOf(id)
                val selected = id == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .readerBackground(t)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MiuixTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { settings.updateReaderTheme(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = label, fontSize = 13.sp, maxLines = 1, color = t.ink)
                }
            }
        }
    }
}

@Composable
internal fun ReaderSliderRow(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    show: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = name, style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(text = show(draft), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(
            value = draft,
            onValueChange = { raw -> draft = ((raw / step).roundToInt() * step).coerceIn(range.start, range.endInclusive) },
            onValueChangeFinished = { if (draft != value) onCommit(draft) },
            valueRange = range,
        )
    }
}

/* ------------------------------ 分页 ------------------------------ */

@Composable
private fun ReaderPager(
    chapters: List<Chapter>,
    bookFile: File,
    ctl: ReaderController,
    revision: Int,
    ink: Color,
    pageBackground: Modifier,
    settings: AppSettings,
    onToggleOverlay: () -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val fontSp = settings.readerFontSp
    val linePct = settings.readerLinePct
    val padH = settings.readerPadH
    val padV = settings.readerPadV
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layout = remember(padH, padV) {
            ReaderLayout(
                padH = padH.dp,
                padTop = (34 + padV).dp,
                padBottom = (26 + padV).dp,
                metaTop = (8 + padV).dp,
                footerBottom = (padV - 2).dp,
            )
        }
        val widthPx = with(density) { (maxWidth - layout.padH * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - layout.padTop - layout.padBottom).roundToPx() }
        val fullW = with(density) { maxWidth.roundToPx() }
        val fullH = with(density) { maxHeight.roundToPx() }
        if (widthPx <= 0 || heightPx <= 0) return@BoxWithConstraints
        val style = remember(ink, fontSp, linePct) {
            TextStyle(
                color = ink,
                fontSize = fontSp.sp,
                lineHeight = (fontSp * linePct / 100f).sp,
                letterSpacing = 0.3.sp,
                lineBreak = LineBreak.Paragraph,
            )
        }
        // 屏幕尺寸 / 主题 / 排版设置变了：整页重排，位置取最近保存在 ctl 里的（章节 + 章内偏移）
        key(widthPx, heightPx, ink, fontSp, linePct, revision, settings.readerPageTurn) {
            PagerContent(
                chapters = chapters,
                bookFile = bookFile,
                ctl = ctl,
                style = style,
                widthPx = widthPx,
                heightPx = heightPx,
                fullW = fullW,
                fullH = fullH,
                layout = layout,
                ink = ink,
                pageBackground = pageBackground,
                pageTurn = settings.readerPageTurn,
                onToggleOverlay = onToggleOverlay,
                measurer = measurer,
            )
        }
    }
}

/** 一章按块排版：文字块按行切页，图片块一张图一页。 */
private fun paginate(
    chapter: Chapter,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
    measurer: androidx.compose.ui.text.TextMeasurer,
): PagedChapter {
    val pages = ArrayList<PageBody>()
    val starts = ArrayList<Int>()
    var base = 0
    for (block in chapter.blocks) {
        when (block) {
            is Block.Text -> {
                val text = block.text
                if (text.isEmpty()) continue
                val layout = measurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    constraints = Constraints(maxWidth = widthPx),
                    skipCache = true,
                )
                var line = 0
                val lineCount = layout.lineCount
                while (line < lineCount) {
                    val top = layout.getLineTop(line)
                    var end = line
                    while (end + 1 < lineCount && layout.getLineBottom(end + 1) - top <= heightPx) end++
                    val s = layout.getLineStart(line)
                    val e = layout.getLineEnd(end, visibleEnd = false)
                    pages.add(PageBody.Text(text.substring(s, e).trimEnd('\n')))
                    starts.add(base + s)
                    line = end + 1
                }
                base += text.length + 1
            }
            is Block.Image -> {
                pages.add(PageBody.Image(block.path))
                starts.add(base)
                base += 1
            }
        }
    }
    if (pages.isEmpty()) {
        pages.add(PageBody.Text(""))
        starts.add(0)
    }
    return PagedChapter(pages, starts.toIntArray(), base.coerceAtLeast(1))
}

@Composable
private fun PagerContent(
    chapters: List<Chapter>,
    bookFile: File,
    ctl: ReaderController,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
    fullW: Int,
    fullH: Int,
    layout: ReaderLayout,
    ink: Color,
    pageBackground: Modifier,
    pageTurn: Int,
    onToggleOverlay: () -> Unit,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val toggle by rememberUpdatedState(onToggleOverlay)
    val cache = remember { HashMap<Int, PagedChapter>() }

    fun paged(ch: Int): PagedChapter = cache.getOrPut(ch) { paginate(chapters[ch], style, widthPx, heightPx, measurer) }

    /** 以 [center] 为中心的三章页面窗口：相邻章节的页已排好，滑过章节边界时无缝衔接。 */
    fun build(center: Int): List<PageRef> {
        val out = ArrayList<PageRef>()
        for (c in (center - 1)..(center + 1)) {
            if (c in chapters.indices) repeat(paged(c).pages.size) { out.add(PageRef(c, it)) }
        }
        return out
    }

    fun pageIndexFor(ch: Int, offset: Int): Int {
        val starts = paged(ch).starts
        var idx = 0
        for (k in starts.indices) if (starts[k] <= offset) idx = k
        return idx
    }

    val startCh = ctl.chapter.coerceIn(0, chapters.lastIndex)
    val startRef = remember { PageRef(startCh, pageIndexFor(startCh, ctl.offset)) }
    var window by remember { mutableStateOf(build(startCh)) }
    var center by remember { mutableIntStateOf(startCh) }
    val pagerState = rememberPagerState(initialPage = window.indexOf(startRef).coerceAtLeast(0)) { window.size }
    val rebuilding = remember { booleanArrayOf(false) }

    /** 换窗口并停在 [target]（外观上是同一页，不会跳）。 */
    suspend fun recenter(newCenter: Int, target: PageRef) {
        rebuilding[0] = true
        try {
            center = newCenter
            val w = build(newCenter)
            window = w
            pagerState.scrollToPage(w.indexOf(target).coerceAtLeast(0))
        } finally {
            rebuilding[0] = false
        }
    }

    // 翻到哪页就记到哪页；落到相邻章节后把窗口挪到那一章
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (rebuilding[0]) return@collect
            val ref = window.getOrNull(page) ?: return@collect
            val pc = paged(ref.ch)
            ctl.chapter = ref.ch
            ctl.offset = pc.starts[ref.idx]
            ctl.percent = percentAt(ref.ch, pc, ref.idx, chapters.size)
            if (ref.ch != center) recenter(ref.ch, ref)
        }
    }

    // 上一章 / 下一章 / 目录跳转
    val jump = ctl.jump
    LaunchedEffect(jump) {
        if (jump == null) return@LaunchedEffect
        val ch = jump.chapter.coerceIn(0, chapters.lastIndex)
        recenter(ch, PageRef(ch, 0))
        ctl.chapter = ch
        ctl.offset = 0
        ctl.percent = percentAt(ch, paged(ch), 0, chapters.size)
        ctl.jump = null
    }

    fun go(delta: Int) {
        scope.launch {
            val target = pagerState.currentPage + delta
            if (target in 0 until window.size) pagerState.animateScrollToPage(target)
        }
    }

    val widthDp = with(density) { widthPx.toDp() }
    val faint = ink.copy(alpha = 0.45f)
    val metaStyle = remember(faint) { TextStyle(color = faint, fontSize = 11.sp) }

    val pageKey: (Int) -> Any = { p ->
        window.getOrNull(p)?.let { it.ch.toLong() * 1_000_000L + it.idx } ?: -(p.toLong() + 1)
    }

    val pageContent: @Composable (Int) -> Unit = { page ->
        val ref = window.getOrNull(page)
        if (ref != null) {
            val pc = paged(ref.ch)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pageTurn) {
                        detectTapGestures { o ->
                            if (pageTurn == READER_PAGE_TURN_VERTICAL) {
                                val h = size.height.toFloat()
                                when {
                                    o.y < h * 0.3f -> go(-1)
                                    o.y > h * 0.7f -> go(1)
                                    else -> toggle()
                                }
                            } else {
                                val w = size.width.toFloat()
                                when {
                                    o.x < w * 0.3f -> go(-1)
                                    o.x > w * 0.7f -> go(1)
                                    else -> toggle()
                                }
                            }
                        }
                    },
            ) {
                when (val body = pc.pages[ref.idx]) {
                    is PageBody.Text -> {
                        BasicText(
                            text = chapters[ref.ch].title,
                            style = metaStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.TopStart).padding(start = layout.padH, end = layout.padH, top = layout.metaTop),
                        )
                        BasicText(
                            text = body.text,
                            style = style,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = layout.padTop).width(widthDp),
                        )
                    }
                    is PageBody.Image -> ReaderImage(
                        file = bookFile,
                        entry = body.path,
                        targetW = fullW,
                        targetH = fullH,
                        messageStyle = metaStyle,
                        modifier = Modifier.fillMaxSize().padding(top = 16.dp, bottom = 44.dp),
                    )
                }
                val total = percentAt(ref.ch, pc, ref.idx, chapters.size) * 100f
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = layout.padH, end = layout.padH, bottom = layout.footerBottom),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(text = String.format("%.1f%%", total), style = metaStyle)
                    BasicText(text = "${ref.idx + 1} / ${pc.pages.size}", style = metaStyle)
                }
            }
        }
    }

    when (pageTurn) {
        READER_PAGE_TURN_VERTICAL -> VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = pageKey,
        ) { page -> pageContent(page) }

        READER_PAGE_TURN_SIMULATED -> HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = pageKey,
        ) { page ->
            // offset > 0：正在被翻走（或翻回来）的那一页，位于视口左侧；offset < 0：垫在下面、等着被露出的那一页
            val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 翻动的页必须盖在上面；Pager 默认后面的页盖前面的页，会把正在翻的页挡住
                    .zIndex(if (offset > 0f) 1f else 0f)
                    .graphicsLayer {
                        val w = size.width
                        if (offset > 0f) {
                            // 以左边缘为轴，右边缘翘起并随手指移出
                            val t = offset.coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            cameraDistance = 14f * density
                            rotationY = t * 52f
                        } else if (offset < 0f) {
                            // 抵消 Pager 的横向位移：下面这页原地不动，被翻页逐渐露出
                            translationX = offset.coerceIn(-1f, 0f) * w
                        }
                    }
                    // 每页自带不透明纸色；Pager 的底色是共用的，页面叠在一起时文字会互相透出来
                    .then(pageBackground)
                    .drawWithContent {
                        drawContent()
                        val t = offset.coerceIn(-1f, 1f)
                        if (t > 0.001f) {
                            // 翻起的页：越靠右越暗，像纸面弯曲；右侧外缘投一道阴影到下面那页上
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f * t)),
                                ),
                            )
                            val shadowW = 28.dp.toPx()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.30f * (1f - t * 0.6f)), Color.Transparent),
                                    startX = size.width,
                                    endX = size.width + shadowW,
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(size.width, 0f),
                                size = androidx.compose.ui.geometry.Size(shadowW, size.height),
                            )
                        } else if (t < -0.001f) {
                            // 下面那页：被盖住时略暗，随着露出逐渐恢复
                            drawRect(Color.Black.copy(alpha = 0.22f * (-t)))
                        }
                    },
            ) { pageContent(page) }
        }

        else -> HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = pageKey,
        ) { page -> pageContent(page) }
    }
}

/** EPUB 里的一张图，等比缩放铺满页面；解码在后台线程，结果进 [imageCache]。 */
@Composable
private fun ReaderImage(file: File, entry: String, targetW: Int, targetH: Int, messageStyle: TextStyle, modifier: Modifier = Modifier) {
    val key = "${file.path}|$entry|$targetW"
    // null = 还在解码；Failed = 读不出来（比如 SVG、损坏的图）
    val state by produceState<Any?>(initialValue = imageCache.get(key), key) {
        if (value == null) {
            val bmp = withContext(Dispatchers.IO) { BookContent.loadImage(file, entry, targetW, targetH)?.asImageBitmap() }
            if (bmp != null) imageCache.put(key, bmp)
            value = bmp ?: ImageFailed
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val v = state) {
            is ImageBitmap -> Image(bitmap = v, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            ImageFailed -> BasicText(text = tr("无法显示这张图片"), style = messageStyle)
            else -> {}
        }
    }
}

private object ImageFailed
