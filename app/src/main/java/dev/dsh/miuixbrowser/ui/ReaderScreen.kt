/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import dev.dsh.miuixbrowser.data.Block
import dev.dsh.miuixbrowser.data.Book
import dev.dsh.miuixbrowser.data.BookContent
import dev.dsh.miuixbrowser.data.BookShelf
import dev.dsh.miuixbrowser.data.Chapter
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
    val book = remember(bookId) { BookShelf.books.firstOrNull { it.id == bookId } }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        if (book == null) {
            failed = true
            return@LaunchedEffect
        }
        val r = withContext(Dispatchers.IO) { runCatching { BookContent.load(context, book) }.getOrNull() }
        if (r.isNullOrEmpty()) failed = true else chapters = r
    }

    val dark = isAppDarkTheme()
    val paper = if (dark) Color(0xFF141416) else Color(0xFFF5F0E6)
    val ink = if (dark) Color(0xFFB4B4BA) else Color(0xFF2E2A25)

    Box(modifier = Modifier.fillMaxSize().background(paper)) {
        val list = chapters
        if (book != null && list != null) {
            ReaderBody(book = book, chapters = list, settings = settings, onBack = onBack, blurEnabled = blurEnabled, ink = ink)
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
private fun ReaderBody(book: Book, chapters: List<Chapter>, settings: AppSettings, onBack: () -> Unit, blurEnabled: Boolean, ink: Color) {
    val context = LocalContext.current
    val view = LocalView.current

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val bd = if (glass) backdrop else null

    val ctl = remember(chapters) {
        ReaderController(book.chapter.coerceIn(0, chapters.lastIndex), book.offset.coerceAtLeast(0)).also { it.percent = book.progress }
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
            // 内容层：被玻璃控件采样
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop),
            ) {
                ReaderPager(chapters = chapters, bookFile = bookFile, ctl = ctl, ink = ink, settings = settings, onToggleOverlay = { overlay = !overlay })
            }

            AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp)) {
                        LiquidGlassBackButton(backdrop = liquid, surfaceVisible = true, onClick = onBack)
                    }
                    Box(modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 8.dp)) {
                        LiquidGlassButton(onClick = { showSettings = true }, backdrop = liquid) {
                            Text(
                                text = "Aa",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
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
                ReaderSettingsPanel(settings)
            }

            // 目录：点章节直接跳转
            AppBottomSheet(
                show = showToc,
                title = tr("目录"),
                backdrop = bd,
                onDismissRequest = { showToc = false },
                scrollContent = true,
            ) {
                val insets = LocalBottomSheetInsets.current
                val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
                val listState = rememberLazyListState()
                LaunchedEffect(showToc) {
                    if (showToc) listState.scrollToItem((ctl.chapter - 3).coerceAtLeast(0))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = insets.top)
                        .heightIn(max = maxHeight + insets.bottom),
                    contentPadding = PaddingValues(bottom = insets.bottom + 12.dp),
                ) {
                    itemsIndexed(chapters) { i, c ->
                        val current = i == ctl.chapter
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

/* ------------------------------ 阅读设置 ------------------------------ */

@Composable
private fun ReaderSettingsPanel(settings: AppSettings) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
@Composable
private fun ReaderSliderRow(
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
    ink: Color,
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
        key(widthPx, heightPx, ink, fontSp, linePct) {
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

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { p -> window.getOrNull(p)?.let { it.ch.toLong() * 1_000_000L + it.idx } ?: -(p.toLong() + 1) },
    ) { page ->
        val ref = window.getOrNull(page)
        if (ref != null) {
            val pc = paged(ref.ch)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            val w = size.width
                            when {
                                o.x < w * 0.3f -> go(-1)
                                o.x > w * 0.7f -> go(1)
                                else -> toggle()
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
