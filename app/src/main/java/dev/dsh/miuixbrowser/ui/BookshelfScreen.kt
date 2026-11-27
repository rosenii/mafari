/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import dev.dsh.miuixbrowser.data.Book
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.BookCategory
import dev.dsh.miuixbrowser.data.BookFeed
import dev.dsh.miuixbrowser.data.BookFeedImport
import dev.dsh.miuixbrowser.data.FeedBook
import dev.dsh.miuixbrowser.data.BookSort
import dev.dsh.miuixbrowser.data.BookShelf
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.File
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 封面比例（宽 : 高 = 3 : 4）。 */
private const val COVER_RATIO = 0.75f

/** 列表模式下封面宽度；高度按 3:4 算出，右侧文字区和它等高。 */
private val LIST_COVER_WIDTH = 72.dp

/** 解码好的封面的小缓存（按 书 id + 封面修订号），滚动回来时不用再读盘。 */
private val coverCache = android.util.LruCache<String, ImageBitmap>(32)

/** 排序图标：三条逐渐变短的横线（miuix 图标库里没有现成的排序图标）。 */
private val SortIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sort",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 6f); horizontalLineTo(21f); verticalLineTo(8f); horizontalLineTo(3f); close()
            moveTo(3f, 11f); horizontalLineTo(15f); verticalLineTo(13f); horizontalLineTo(3f); close()
            moveTo(3f, 16f); horizontalLineTo(9f); verticalLineTo(18f); horizontalLineTo(3f); close()
        }
    }.build()
}

private fun sortLabel(sort: BookSort): String = when (sort) {
    BookSort.ADDED -> tr("添加时间")
    BookSort.TITLE -> tr("书名")
    BookSort.AUTHOR -> tr("作者")
    BookSort.PROGRESS -> tr("阅读进度")
}

/**
 * 设置 → 浏览 → 书架。
 *
 * 复用其他二级页面的框架（玻璃大标题顶栏 + 右上角按钮）。顶栏三个按钮：搜索（写法同历史页：顶栏下方展开输入框，按书名 / 作者过滤）、
 * 排序（添加时间 / 书名 / 作者 / 阅读进度，升降序）、更多（选择 / 网格 / 列表 / 创建分类 / 导入）。
 * 网格 / 列表和排序方式存在 [AppSettings]（与阅读器排版同一套 SharedPreferences）。分类用顶栏下方一行可横滑的标签切换；
 * 「选择」进入多选，底部「移动到分类 / 删除」；长按封面（非选择模式）弹出编辑窗口。「从订阅源导入」读取通用 JSON 小说订阅源（链接或本地文件，弹窗与脚本页共用），勾选后下载入架。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit = {},
    blurEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val bd = if (glass) backdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { BookShelf.load(context) }
        loaded = true
    }
    val books = BookShelf.books
    val categories = BookShelf.categories

    // 搜索：写法同历史页（searching 开关 + query，输入框放顶栏 bottomContent）
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 当前分类（null = 全部）；分类被删掉后自动回到「全部」
    var currentCategory by remember { mutableStateOf<String?>(null) }
    val activeCategory = currentCategory?.takeIf { id -> categories.any { it.id == id } }

    val grid = settings.bookshelfGrid
    val sort = settings.bookshelfSort
    val desc = settings.bookshelfSortDesc

    val shown = remember(books, query, activeCategory, sort, desc) {
        BookShelf.visible(books, query, activeCategory, sort, desc)
    }

    // 多选
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 只算还在书架里的（被别处删掉的书不再计数）
    val selectedIds = remember(selected, books) {
        val alive = books.mapTo(HashSet()) { it.id }
        selected.filterTo(HashSet()) { it in alive }
    }
    val allSelected = shown.isNotEmpty() && shown.all { it.id in selectedIds }
    fun exitSelect() { selecting = false; selected = emptySet() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    BackHandler(enabled = selecting) { exitSelect() }

    var showMore by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(Rect.Zero) }
    var showSort by remember { mutableStateOf(false) }
    var sortAnchor by remember { mutableStateOf(Rect.Zero) }
    var importing by remember { mutableStateOf(false) }

    // 从订阅源导入：输入弹窗 → 解析出的书目（勾选）→ 逐本下载（带进度、可取消）
    var feedDialog by remember { mutableStateOf(false) }
    var feedLoading by remember { mutableStateOf(false) }
    var feedPick by remember { mutableStateOf<BookFeed?>(null) }
    var feedPickShow by remember { mutableStateOf(false) }
    var feedSel by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var feedRunning by remember { mutableStateOf(false) }
    var feedProgress by remember { mutableStateOf("") }
    var feedJob by remember { mutableStateOf<Job?>(null) }
    val shelfKeys = remember(books) { books.mapTo(HashSet()) { it.title.trim() to it.author.trim() } }

    // 编辑窗口 / 删除确认：书和显示状态分开存，关闭动画期间内容不会突然消失
    var editBook by remember { mutableStateOf<Book?>(null) }
    var editShow by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var pendingCover by remember { mutableStateOf<Bitmap?>(null) }
    var confirmShow by remember { mutableStateOf(false) }

    // 分类：创建 / 重命名共用一个输入窗口；移动、批量删除、删分类各一个窗口
    var nameShow by remember { mutableStateOf(false) }
    var nameRename by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    var moveAfterCreate by remember { mutableStateOf(false) }
    var moveShow by remember { mutableStateOf(false) }
    var deleteManyShow by remember { mutableStateOf(false) }
    var deleteCatShow by remember { mutableStateOf(false) }
    val activeCategoryName = categories.firstOrNull { it.id == activeCategory }?.name.orEmpty()

    fun submitName() {
        val text = nameText
        val renameId = activeCategory
        val rename = nameRename
        val ids = selectedIds.toSet()
        val moving = moveAfterCreate
        scope.launch {
            if (rename) {
                val ok = renameId != null && withContext(Dispatchers.IO) { BookShelf.renameCategory(context, renameId, text) }
                if (ok) nameShow = false else toast(tr("分类名不能为空，也不能与已有分类重名"))
            } else {
                val c = withContext(Dispatchers.IO) { BookShelf.createCategory(context, text) }
                if (c == null) {
                    toast(tr("分类名不能为空，也不能与已有分类重名"))
                } else {
                    nameShow = false
                    if (moving) {
                        withContext(Dispatchers.IO) { BookShelf.setCategory(context, ids, c.id) }
                        moveAfterCreate = false
                        exitSelect()
                        toast(tr("已移入「{0}」", c.name))
                    } else {
                        currentCategory = c.id
                    }
                }
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { BookShelf.importUris(context, uris) }
            importing = false
            toast(
                when {
                    r.added > 0 && r.skipped == 0 -> tr("已导入 {0} 本", r.added)
                    r.added > 0 -> tr("已导入 {0} 本，{1} 个文件无法导入（仅支持 TXT / EPUB）", r.added, r.skipped)
                    else -> tr("无法导入所选文件（仅支持 TXT / EPUB）")
                },
            )
        }
    }

    /** 取订阅源并解析（[load] 在 IO 线程跑）；成功后弹出书目勾选窗，已在书架的书默认不勾。 */
    fun loadFeed(remote: Boolean, load: () -> BookFeed) {
        if (feedLoading) return
        feedLoading = true
        if (remote) toast(tr("正在下载…"))
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(load) }
            feedLoading = false
            r.onSuccess { f ->
                feedPick = f
                feedSel = f.books.indices.filter { (f.books[it].title.trim() to f.books[it].author.trim()) !in shelfKeys }.toSet()
                feedPickShow = true
            }.onFailure { toast(it.message ?: tr("下载失败，检查链接")) }
        }
    }

    val feedFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadFeed(remote = false) { BookFeedImport.loadFromUri(context, uri) }
    }

    /** 逐本下载入架；可取消，取消后已入架的保留。 */
    fun startFeedImport(picks: List<FeedBook>) {
        feedPickShow = false
        feedProgress = ""
        feedRunning = true
        importing = true
        feedJob = scope.launch {
            var ok = 0
            var fail = 0
            var lastErr: String? = null
            var cancelled = false
            try {
                for ((i, b) in picks.withIndex()) {
                    feedProgress = tr("《{0}》（{1}/{2}）", b.title, i + 1, picks.size)
                    val r = BookFeedImport.importBook(context, b) { done, total ->
                        feedProgress = tr("《{0}》（{1}/{2}）· 章节 {3}/{4}", b.title, i + 1, picks.size, done, total)
                    }
                    val err = r.exceptionOrNull()
                    if (err is CancellationException) throw err
                    if (err == null) ok++ else { fail++; lastErr = err.message }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                feedRunning = false
                importing = false
                feedJob = null
                toast(
                    when {
                        cancelled -> tr("已取消，已导入 {0} 本", ok)
                        fail == 0 -> tr("已导入 {0} 本", ok)
                        ok == 0 -> tr("导入失败：{0}", lastErr.orEmpty())
                        else -> tr("已导入 {0} 本，{1} 本失败：{2}", ok, fail, lastErr.orEmpty())
                    },
                )
            }
        }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { BookShelf.decodeCoverFromUri(context, uri) }
            if (bmp == null) toast(tr("无法读取这张图片")) else pendingCover = bmp
        }
    }

    // 点按 / 长按：选择模式下都是勾选；否则点按打开、长按编辑
    fun onBookClick(book: Book) { if (selecting) toggle(book.id) else onOpenBook(book.id) }
    fun onBookLongClick(book: Book) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (selecting) {
            toggle(book.id)
        } else {
            editBook = book
            editTitle = book.title
            pendingCover = null
            editShow = true
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    PageGlassTopBar(
                        title = if (selecting) tr("已选 {0} 本", selectedIds.size) else tr("书架"),
                        backdrop = backdrop,
                        glass = glass,
                        scrollBehavior = scrollBehavior,
                        onBack = { if (selecting) exitSelect() else onBack() },
                        backButton = {
                            LiquidGlassBackButton(
                                backdrop = liquid,
                                surfaceVisible = topScrolled,
                                onClick = { if (selecting) exitSelect() else onBack() },
                            )
                        },
                        actions = {
                            if (selecting) {
                                AppTextButton(
                                    text = if (allSelected) tr("取消全选") else tr("全选"),
                                    onClick = {
                                        val ids = shown.map { it.id }
                                        selected = if (allSelected) selected - ids.toSet() else selected + ids
                                    },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            } else {
                                LiquidGlassButton(
                                    onClick = {
                                        searching = !searching
                                        if (!searching) query = ""
                                    },
                                    backdrop = liquid,
                                    surfaceVisible = topScrolled,
                                    modifier = Modifier.padding(end = 8.dp),
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.Search,
                                        contentDescription = tr("搜索"),
                                        modifier = Modifier.size(22.dp),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                                LiquidGlassButton(
                                    onClick = { showSort = true },
                                    backdrop = liquid,
                                    surfaceVisible = topScrolled,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .onGloballyPositioned { sortAnchor = it.boundsInRoot() },
                                ) {
                                    Icon(
                                        imageVector = SortIcon,
                                        contentDescription = tr("排序"),
                                        modifier = Modifier.size(22.dp),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                                LiquidGlassButton(
                                    onClick = { showMore = true },
                                    backdrop = liquid,
                                    surfaceVisible = topScrolled,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .onGloballyPositioned { moreAnchor = it.boundsInRoot() },
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.More,
                                        contentDescription = tr("更多"),
                                        modifier = Modifier.size(22.dp),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        },
                        bottomContent = {
                            Column {
                                if (searching) {
                                    TextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        label = tr("搜索书名或作者"),
                                        singleLine = true,
                                    )
                                }
                                if (categories.isNotEmpty()) {
                                    CategoryChips(
                                        categories = categories,
                                        selectedId = activeCategory,
                                        onSelect = { currentCategory = it },
                                    )
                                }
                            }
                        },
                    )
                },
            ) { innerPadding ->
                val listPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    // 选择模式下底部有操作栏，多留一截不挡住最后一行
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        (if (selecting) 72.dp else 0.dp),
                )
                val contentModifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .scrollEndHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .pageBackground()
                val emptyHint: String? = when {
                    !loaded -> null
                    books.isEmpty() -> tr("书架是空的。点右上角「更多」→「从本地导入」添加 TXT / EPUB 小说。")
                    shown.isEmpty() && query.isNotBlank() -> tr("没有找到相关书籍")
                    shown.isEmpty() && activeCategory != null ->
                        tr("这个分类里还没有书。点右上角「更多」→「选择」勾选书籍，再「移动到分类」。")
                    else -> null
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (grid) {
                        LazyVerticalGrid(
                            // 一行固定 3 本（封面 3:4，随屏宽缩放）
                            columns = GridCells.Fixed(3),
                            modifier = contentModifier,
                            contentPadding = listPadding,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (emptyHint != null) {
                                item(span = { GridItemSpan(maxLineSpan) }) { EmptyHint(emptyHint) }
                            }
                            items(shown, key = { it.id }) { book ->
                                BookItem(
                                    book = book,
                                    selecting = selecting,
                                    checked = book.id in selectedIds,
                                    onClick = { onBookClick(book) },
                                    onLongClick = { onBookLongClick(book) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = contentModifier,
                            contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            if (emptyHint != null) item { EmptyHint(emptyHint) }
                            items(shown, key = { it.id }) { book ->
                                BookRow(
                                    book = book,
                                    selecting = selecting,
                                    checked = book.id in selectedIds,
                                    onClick = { onBookClick(book) },
                                    onLongClick = { onBookLongClick(book) },
                                )
                            }
                        }
                    }

                    // 选择模式的底部操作栏：移动到分类 / 删除
                    if (selecting) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LiquidGlassPillButton(
                                text = tr("移动到分类"),
                                onClick = { moveShow = true },
                                backdrop = liquid,
                                modifier = Modifier.weight(1f),
                                enabled = selectedIds.isNotEmpty(),
                            )
                            LiquidGlassPillButton(
                                text = tr("删除"),
                                onClick = { deleteManyShow = true },
                                backdrop = liquid,
                                modifier = Modifier.weight(1f),
                                enabled = selectedIds.isNotEmpty(),
                                role = LiquidRole.Destructive,
                            )
                        }
                    }
                }

                // 弹窗 / sheet 必须放在 Scaffold 的内容里：miuix 的 Overlay 弹层由 Scaffold 的弹层宿主渲染，放在 Scaffold 外面会一直不显示
                // ---- 编辑窗口：左下角删除、右下角保存（删除先弹确认框，此时编辑窗口先收起）
                AppGlassDialog(
                    show = editShow && !confirmShow,
                    title = tr("编辑书籍"),
                    backdrop = bd,
                    onDismissRequest = { editShow = false },
                ) {
                    val b = editBook
                    if (b != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(130.dp)
                                    .aspectRatio(COVER_RATIO)
                                    .squircleClip(12.dp)
                                    .clickable { coverPicker.launch(arrayOf("image/*")) },
                            ) {
                                BookCover(book = b, override = pendingCover, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.height(6.dp))
                            AppTextButton(
                                text = tr("上传封面"),
                                onClick = { coverPicker.launch(arrayOf("image/*")) },
                            )
                            Spacer(Modifier.height(6.dp))
                            TextField(
                                value = editTitle,
                                onValueChange = { editTitle = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = tr("书名"),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppDialogButton(
                                    onClick = { confirmShow = true },
                                    modifier = Modifier.weight(1f),
                                    role = GlassDialogButtonRole.Destructive,
                                ) { Text(text = tr("删除"), style = MiuixTheme.textStyles.button) }
                                AppDialogButton(
                                    onClick = {
                                        val title = editTitle
                                        val cover = pendingCover
                                        editShow = false
                                        scope.launch {
                                            withContext(Dispatchers.IO) { BookShelf.update(context, b.id, title, newCover = cover) }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    role = GlassDialogButtonRole.Primary,
                                ) { Text(text = tr("保存"), style = MiuixTheme.textStyles.button) }
                            }
                        }
                    }
                }

                // ---- 删除确认
                AppGlassDialog(
                    show = confirmShow,
                    title = tr("删除这本书？"),
                    summary = tr("《{0}》会从书架移除，书的文件和封面也会一并删除，无法恢复。", editBook?.title.orEmpty()),
                    backdrop = bd,
                    onDismissRequest = { confirmShow = false },
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppDialogButton(onClick = { confirmShow = false }, modifier = Modifier.weight(1f)) {
                            Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val id = editBook?.id
                                confirmShow = false
                                editShow = false
                                if (id != null) scope.launch { withContext(Dispatchers.IO) { BookShelf.delete(context, id) } }
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Destructive,
                        ) { Text(text = tr("删除"), style = MiuixTheme.textStyles.button) }
                    }
                }

                // ---- 批量删除确认（选择模式）
                AppGlassDialog(
                    show = deleteManyShow,
                    title = tr("删除选中的 {0} 本书？", selectedIds.size),
                    summary = tr("书的文件和封面也会一并删除，无法恢复。"),
                    backdrop = bd,
                    onDismissRequest = { deleteManyShow = false },
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppDialogButton(onClick = { deleteManyShow = false }, modifier = Modifier.weight(1f)) {
                            Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val ids = selectedIds.toSet()
                                deleteManyShow = false
                                exitSelect()
                                scope.launch { withContext(Dispatchers.IO) { BookShelf.deleteMany(context, ids) } }
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Destructive,
                        ) { Text(text = tr("删除"), style = MiuixTheme.textStyles.button) }
                    }
                }

                // ---- 创建 / 重命名分类
                AppGlassDialog(
                    show = nameShow,
                    title = if (nameRename) tr("重命名分类") else tr("创建分类"),
                    backdrop = bd,
                    onDismissRequest = {
                        nameShow = false
                        moveAfterCreate = false
                    },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TextField(
                            value = nameText,
                            onValueChange = { nameText = it.take(20) },
                            modifier = Modifier.fillMaxWidth(),
                            label = tr("分类名称"),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppDialogButton(
                                onClick = {
                                    nameShow = false
                                    moveAfterCreate = false
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(text = tr("取消"), style = MiuixTheme.textStyles.button) }
                            AppDialogButton(
                                onClick = { submitName() },
                                modifier = Modifier.weight(1f),
                                enabled = nameText.isNotBlank(),
                                role = GlassDialogButtonRole.Primary,
                            ) { Text(text = tr("确定"), style = MiuixTheme.textStyles.button) }
                        }
                    }
                }

                // ---- 从订阅源导入：输入链接 / 选本地文件（与脚本页同一个弹窗）
                ImportDialog(
                    show = feedDialog,
                    backdrop = bd,
                    onDismiss = { feedDialog = false },
                    onImport = { url ->
                        feedDialog = false
                        loadFeed(remote = true) { BookFeedImport.loadFromUrl(url) }
                    },
                    onPickFile = {
                        feedDialog = false
                        feedFilePicker.launch(arrayOf("application/json", "text/*", "application/octet-stream", "*/*"))
                    },
                    title = tr("从订阅源导入"),
                    summary = tr("支持通用 JSON 小说订阅源：输入链接或选择本地文件，再勾选要导入的书"),
                    urlLabel = tr("订阅源地址（http / https，JSON）"),
                )

                // ---- 订阅源书目：勾选要导入的书
                val pickFeed = feedPick
                AppGlassDialog(
                    show = feedPickShow,
                    title = pickFeed?.name?.ifBlank { null } ?: tr("订阅源"),
                    summary = pickFeed?.let { tr("共 {0} 本，勾选要导入的书（已在书架的默认不选）", it.books.size) },
                    backdrop = bd,
                    onDismissRequest = { feedPickShow = false },
                ) {
                    if (pickFeed != null) {
                        val total = pickFeed.books.size
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LazyColumn(modifier = Modifier.fillMaxWidth().height(minOf(total, 6) * 56.dp)) {
                                items(total) { i ->
                                    val b = pickFeed.books[i]
                                    FeedRow(
                                        title = b.title,
                                        author = b.author,
                                        checked = i in feedSel,
                                        onShelf = (b.title.trim() to b.author.trim()) in shelfKeys,
                                    ) { feedSel = if (i in feedSel) feedSel - i else feedSel + i }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            AppTextButton(
                                text = if (feedSel.size == total) tr("取消全选") else tr("全选"),
                                onClick = { feedSel = if (feedSel.size == total) emptySet() else (0 until total).toSet() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppDialogButton(onClick = { feedPickShow = false }, modifier = Modifier.weight(1f)) {
                                    Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                                }
                                AppDialogButton(
                                    onClick = {
                                        if (feedSel.isEmpty()) toast(tr("请先勾选要导入的书"))
                                        else startFeedImport(feedSel.sorted().map { pickFeed.books[it] })
                                    },
                                    modifier = Modifier.weight(1f),
                                    role = GlassDialogButtonRole.Primary,
                                ) { Text(text = tr("导入 {0} 本", feedSel.size), style = MiuixTheme.textStyles.button) }
                            }
                        }
                    }
                }

                // ---- 导入进度（可取消）
                AppGlassDialog(
                    show = feedRunning,
                    title = tr("正在导入…"),
                    summary = feedProgress,
                    backdrop = bd,
                    onDismissRequest = {},
                ) {
                    AppDialogButton(onClick = { feedJob?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                    }
                }

                // ---- 删除分类确认（书不会被删）
                AppGlassDialog(
                    show = deleteCatShow,
                    title = tr("删除分类「{0}」？", activeCategoryName),
                    summary = tr("分类里的书不会被删除，仍可在「全部」里找到。"),
                    backdrop = bd,
                    onDismissRequest = { deleteCatShow = false },
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppDialogButton(onClick = { deleteCatShow = false }, modifier = Modifier.weight(1f)) {
                            Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val id = activeCategory
                                deleteCatShow = false
                                currentCategory = null
                                if (id != null) scope.launch { withContext(Dispatchers.IO) { BookShelf.deleteCategory(context, id) } }
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Destructive,
                        ) { Text(text = tr("删除"), style = MiuixTheme.textStyles.button) }
                    }
                }

                // ---- 移动到分类：列出所有分类 + 「移出分类」；一个分类都没有时引导去创建
                AppGlassDialog(
                    show = moveShow,
                    title = tr("移动到分类"),
                    summary = if (categories.isEmpty()) tr("还没有分类，先创建一个。") else null,
                    backdrop = bd,
                    onDismissRequest = { moveShow = false },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (categories.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 每行 48dp（含「移出分类」那一行）；超过 6 行就固定高度、内部滚动
                                    .height(minOf(categories.size + 1, 6) * 48.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                categories.forEach { c ->
                                    MoveRow(text = c.name) {
                                        val ids = selectedIds.toSet()
                                        moveShow = false
                                        exitSelect()
                                        scope.launch { withContext(Dispatchers.IO) { BookShelf.setCategory(context, ids, c.id) } }
                                        toast(tr("已移入「{0}」", c.name))
                                    }
                                }
                                MoveRow(text = tr("移出分类"), dim = true) {
                                    val ids = selectedIds.toSet()
                                    moveShow = false
                                    exitSelect()
                                    scope.launch { withContext(Dispatchers.IO) { BookShelf.setCategory(context, ids, "") } }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppDialogButton(onClick = { moveShow = false }, modifier = Modifier.weight(1f)) {
                                Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                            }
                            AppDialogButton(
                                onClick = {
                                    moveShow = false
                                    moveAfterCreate = true
                                    nameRename = false
                                    nameText = ""
                                    nameShow = true
                                },
                                modifier = Modifier.weight(1f),
                                role = GlassDialogButtonRole.Primary,
                            ) { Text(text = tr("新建分类"), style = MiuixTheme.textStyles.button) }
                        }
                    }
                }
            }

            // 排序菜单：与 Scaffold 同级、放在最后 → 位于最上层（盖住顶栏）。当前项后面带 ✓；点已选中的排序项不变，方向单独两项
            AppGlassPopup(
                show = showSort,
                onDismissRequest = { showSort = false },
                anchorBounds = Rect(sortAnchor.left, sortAnchor.bottom, sortAnchor.right, sortAnchor.bottom),
                backdrop = bd,
            ) {
                BookSort.entries.forEach { opt ->
                    GlassPopupItem(
                        text = sortLabel(opt) + if (opt == sort) "  ✓" else "",
                        onClick = {
                            showSort = false
                            settings.updateBookshelfSort(opt, desc)
                        },
                    )
                }
                GlassPopupItem(
                    text = tr("升序") + if (!desc) "  ✓" else "",
                    onClick = {
                        showSort = false
                        settings.updateBookshelfSort(sort, false)
                    },
                )
                GlassPopupItem(
                    text = tr("降序") + if (desc) "  ✓" else "",
                    onClick = {
                        showSort = false
                        settings.updateBookshelfSort(sort, true)
                    },
                )
            }

            // 右上角菜单：与 Scaffold 同级、放在最后 → 位于最上层（盖住顶栏）
            AppGlassPopup(
                show = showMore,
                onDismissRequest = { showMore = false },
                anchorBounds = Rect(moreAnchor.left, moreAnchor.bottom, moreAnchor.right, moreAnchor.bottom),
                backdrop = bd,
            ) {
                GlassPopupItem(
                    text = tr("选择"),
                    enabled = books.isNotEmpty(),
                    onClick = {
                        showMore = false
                        selected = emptySet()
                        selecting = true
                    },
                )
                GlassPopupItem(
                    text = tr("网格") + if (grid) "  ✓" else "",
                    onClick = {
                        showMore = false
                        settings.updateBookshelfGrid(true)
                    },
                )
                GlassPopupItem(
                    text = tr("列表") + if (!grid) "  ✓" else "",
                    onClick = {
                        showMore = false
                        settings.updateBookshelfGrid(false)
                    },
                )
                GlassPopupItem(
                    text = tr("创建分类"),
                    onClick = {
                        showMore = false
                        moveAfterCreate = false
                        nameRename = false
                        nameText = ""
                        nameShow = true
                    },
                )
                if (activeCategory != null) {
                    GlassPopupItem(
                        text = tr("重命名当前分类"),
                        onClick = {
                            showMore = false
                            nameRename = true
                            nameText = activeCategoryName
                            nameShow = true
                        },
                    )
                    GlassPopupItem(
                        text = tr("删除当前分类"),
                        onClick = {
                            showMore = false
                            deleteCatShow = true
                        },
                    )
                }
                GlassPopupItem(
                    text = tr("从订阅源导入"),
                    enabled = !importing && !feedLoading,
                    onClick = {
                        showMore = false
                        feedDialog = true
                    },
                    icon = MiuixIcons.Glass.Download,
                )
                GlassPopupItem(
                    text = if (importing) tr("正在导入…") else tr("从本地导入"),
                    enabled = !importing,
                    onClick = {
                        showMore = false
                        importPicker.launch(arrayOf("text/plain", "application/epub+zip", "*/*"))
                    },
                    icon = MiuixIcons.Glass.File,
                )
            }

        }
    }
}

/** 空状态 / 无结果的提示文字。 */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 48.dp),
    )
}

/** 顶栏下方的分类标签：「全部」+ 各分类，可横向滑动。 */
@Composable
private fun CategoryChips(categories: List<BookCategory>, selectedId: String?, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryChip(text = tr("全部"), selected = selectedId == null) { onSelect(null) }
        categories.forEach { c -> CategoryChip(text = c.name, selected = selectedId == c.id) { onSelect(c.id) } }
    }
}

@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 14.sp,
        maxLines = 1,
        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** 「移动到分类」窗口里的一行。 */
@Composable
private fun MoveRow(text: String, dim: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 16.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (dim) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 4.dp),
    )
}

/** 订阅源书目窗口里的一行：勾选圆点 + 书名 / 作者（已在书架的标出来）。 */
@Composable
private fun FeedRow(title: String, author: String, checked: Boolean, onShelf: Boolean, onClick: () -> Unit) {
    val sub = listOf(author, if (onShelf) tr("已在书架") else "").filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) MiuixTheme.colorScheme.primary else Color.Transparent)
                .border(1.5.dp, if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text(text = "✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 选择模式下的勾选圆点。 */
@Composable
private fun SelectMark(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) MiuixTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f))
            .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text(text = "✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** 列表模式的一行：左边封面，右边书名 / 作者，底部是格式标签和阅读进度；点按打开，长按编辑。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: Book, selecting: Boolean, checked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(LIST_COVER_WIDTH)
                .aspectRatio(COVER_RATIO)
                .squircleClip(10.dp),
        ) {
            BookCover(book = book, override = null, modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .height(LIST_COVER_WIDTH / COVER_RATIO)
                .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        ) {
            Text(
                text = book.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.author.ifBlank { tr("佚名") },
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = book.fileName.substringAfterLast('.', "").uppercase(),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                val label = progressLabel(book)
                if (label != null) {
                    Text(text = label, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                }
            }
        }
        if (selecting) SelectMark(checked = checked, modifier = Modifier.padding(end = 6.dp))
    }
}

/** 书架网格里的一格：大封面 + 书名 + 作者；点按打开，长按编辑（选择模式下点按 / 长按都是勾选）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookItem(book: Book, selecting: Boolean, checked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_RATIO)
                .squircleClip(12.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            BookCover(book = book, override = null, modifier = Modifier.fillMaxSize())
            // 作者嵌在封面下部：底部一层渐变暗幕托住白字，亮封面 / 浅色封面上也看得清
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 8.dp),
            ) {
                Text(
                    text = book.author.ifBlank { tr("佚名") },
                    fontSize = 12.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 选择模式：选中的封面压暗，左上角显示勾选圆点
            if (selecting) {
                if (checked) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)))
                SelectMark(checked = checked, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            // 阅读进度：右上角百分比角标 + 封面底边一条细进度条（没开始读的书不显示）
            val label = progressLabel(book)
            if (label != null) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.3f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(book.progress.coerceIn(0f, 1f))
                            .background(MiuixTheme.colorScheme.primary),
                    )
                }
            }
        }
        Text(
            text = book.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 封面角标文字：没开始读返回 null；读完「已读完」；否则向下取整的百分比（至少 1%，99% 以内）。 */
private fun progressLabel(book: Book): String? = when {
    book.progress >= 0.999f -> tr("已读完")
    book.progress > 0f -> "${(book.progress * 100).toInt().coerceIn(1, 99)}%"
    else -> null
}

/**
 * 封面图。[override] 非空时显示它（编辑窗口里刚选、还没保存的新封面）；没有封面的书画一张按书名着色的占位封面。
 */
@Composable
private fun BookCover(book: Book, override: Bitmap?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = book.id + ":" + book.coverRev
    val stored by produceState<ImageBitmap?>(initialValue = coverCache.get(key), key, book.hasCover) {
        value = if (book.hasCover) coverCache.get(key) else null
        if (value == null && book.hasCover) {
            val bmp = withContext(Dispatchers.IO) { BookShelf.loadCover(context, book.id, 480)?.asImageBitmap() }
            if (bmp != null) coverCache.put(key, bmp)
            value = bmp
        }
    }
    val picked = remember(override) { override?.asImageBitmap() }
    val image = picked ?: stored
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        val (top, bottom) = PLACEHOLDER_COLORS[Math.floorMod(book.title.hashCode(), PLACEHOLDER_COLORS.size)]
        Box(
            modifier = modifier.background(Brush.verticalGradient(listOf(top, bottom))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

private val PLACEHOLDER_COLORS = listOf(
    Color(0xFF5B8DEF) to Color(0xFF3A5FC4),
    Color(0xFFE5806B) to Color(0xFFC25544),
    Color(0xFF4FB6A0) to Color(0xFF2E8873),
    Color(0xFF9B7FD6) to Color(0xFF6D52AE),
    Color(0xFFE0A23B) to Color(0xFFB87A1A),
    Color(0xFF6C7A89) to Color(0xFF47535F),
)
