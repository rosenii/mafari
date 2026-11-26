/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import dev.dsh.miuixbrowser.data.Book
import dev.dsh.miuixbrowser.data.BookContent
import dev.dsh.miuixbrowser.data.BookShelf
import dev.dsh.miuixbrowser.data.Chapter
import dev.dsh.miuixbrowser.engine.gecko.findActivity
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ------------------------------ 状态 ------------------------------ */

/** 一页：第 [ch] 章的第 [idx] 页。 */
private data class PageRef(val ch: Int, val idx: Int)

/** 一章排好版的结果：每页的文字、每页起点在章内的字符偏移。 */
private class PagedChapter(val pages: List<String>, val starts: IntArray, val length: Int)

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
 * 点屏幕左 / 右侧翻页（也可左右滑动，章节之间无缝衔接），点中间呼出控件：返回、上一章 / 目录 / 下一章，
 * 目录是底部 sheet，点章节直接跳转。阅读位置按「章节 + 章内偏移」自动保存，下次打开回到原处。
 * 控件全部用抽象层材质（液态玻璃 / miuix 玻璃 / 关闭模糊三种随「高级材质」设置切换）。
 */
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, blurEnabled: Boolean = true) {
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
            ReaderBody(book = book, chapters = list, onBack = onBack, blurEnabled = blurEnabled, ink = ink)
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
private fun ReaderBody(book: Book, chapters: List<Chapter>, onBack: () -> Unit, blurEnabled: Boolean, ink: Color) {
    val context = LocalContext.current
    val view = LocalView.current

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val bd = if (glass) backdrop else null

    val ctl = remember(chapters) {
        ReaderController(book.chapter.coerceIn(0, chapters.lastIndex), book.offset.coerceAtLeast(0))
    }
    var overlay by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }

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

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 内容层：被玻璃控件采样
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop),
            ) {
                ReaderPager(chapters = chapters, ctl = ctl, ink = ink, onToggleOverlay = { overlay = !overlay })
            }

            AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp)) {
                        LiquidGlassBackButton(backdrop = liquid, surfaceVisible = true, onClick = onBack)
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

/* ------------------------------ 分页 ------------------------------ */

private val PAD_H = 26.dp
private val PAD_TOP = 60.dp
private val PAD_BOTTOM = 52.dp

@Composable
private fun ReaderPager(chapters: List<Chapter>, ctl: ReaderController, ink: Color, onToggleOverlay: () -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth - PAD_H * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - PAD_TOP - PAD_BOTTOM).roundToPx() }
        if (widthPx <= 0 || heightPx <= 0) return@BoxWithConstraints
        val style = remember(ink) {
            TextStyle(
                color = ink,
                fontSize = 18.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.3.sp,
                lineBreak = LineBreak.Paragraph,
            )
        }
        // 屏幕尺寸 / 主题变了：整页重排，位置取最近保存在 ctl 里的（章节 + 章内偏移）
        key(widthPx, heightPx, ink) {
            PagerContent(
                chapters = chapters,
                ctl = ctl,
                style = style,
                widthPx = widthPx,
                heightPx = heightPx,
                ink = ink,
                onToggleOverlay = onToggleOverlay,
                measurer = measurer,
            )
        }
    }
}

private fun paginate(
    text: String,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
    measurer: androidx.compose.ui.text.TextMeasurer,
): PagedChapter {
    val layout = measurer.measure(
        text = AnnotatedString(text),
        style = style,
        constraints = Constraints(maxWidth = widthPx),
        skipCache = true,
    )
    val pages = ArrayList<String>()
    val starts = ArrayList<Int>()
    var line = 0
    val lineCount = layout.lineCount
    while (line < lineCount) {
        val top = layout.getLineTop(line)
        var end = line
        while (end + 1 < lineCount && layout.getLineBottom(end + 1) - top <= heightPx) end++
        val s = layout.getLineStart(line)
        val e = layout.getLineEnd(end, visibleEnd = false)
        pages.add(text.substring(s, e).trimEnd('\n'))
        starts.add(s)
        line = end + 1
    }
    if (pages.isEmpty()) {
        pages.add("")
        starts.add(0)
    }
    return PagedChapter(pages, starts.toIntArray(), text.length)
}

@Composable
private fun PagerContent(
    chapters: List<Chapter>,
    ctl: ReaderController,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
    ink: Color,
    onToggleOverlay: () -> Unit,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val toggle by rememberUpdatedState(onToggleOverlay)
    val cache = remember { HashMap<Int, PagedChapter>() }

    fun paged(ch: Int): PagedChapter = cache.getOrPut(ch) { paginate(chapters[ch].text, style, widthPx, heightPx, measurer) }

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
            val off = pc.starts[ref.idx]
            ctl.chapter = ref.ch
            ctl.offset = off
            ctl.percent = ((ref.ch + off.toFloat() / pc.length.coerceAtLeast(1)) / chapters.size).coerceIn(0f, 1f)
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
        ctl.percent = (ch.toFloat() / chapters.size).coerceIn(0f, 1f)
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
                BasicText(
                    text = chapters[ref.ch].title,
                    style = metaStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = PAD_H, end = PAD_H, top = 34.dp),
                )
                BasicText(
                    text = pc.pages[ref.idx],
                    style = style,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = PAD_TOP).width(widthDp),
                )
                val total = ((ref.ch + pc.starts[ref.idx].toFloat() / pc.length.coerceAtLeast(1)) / chapters.size * 100f).coerceIn(0f, 100f)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = PAD_H, end = PAD_H, bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(text = String.format("%.1f%%", total), style = metaStyle)
                    BasicText(text = "${ref.idx + 1} / ${pc.pages.size}", style = metaStyle)
                }
            }
        }
    }
}
