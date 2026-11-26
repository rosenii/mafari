/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import dev.dsh.miuixbrowser.data.BookShelf
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.Dispatchers
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
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 封面比例（宽 : 高 = 3 : 4）。 */
private const val COVER_RATIO = 0.75f

/** 解码好的封面的小缓存（按 书 id + 封面修订号），滚动回来时不用再读盘。 */
private val coverCache = android.util.LruCache<String, ImageBitmap>(32)

/**
 * 设置 → 浏览 → 书架。
 *
 * 复用其他二级页面的框架（玻璃大标题顶栏 + 右上角「更多」菜单）。目前只做「从本地导入」小说（TXT / EPUB）：
 * 书架里以大封面 + 书名 + 作者的网格展示（读过的书封面右上角有进度角标、底边有进度条），长按封面弹出编辑窗口（改书名、上传封面；左下角删除、右下角保存，
 * 删除前再确认一次）。「订阅网络小说」入口先占位。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(onBack: () -> Unit, onOpenBook: (String) -> Unit = {}, blurEnabled: Boolean = true) {
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

    var showMore by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(Rect.Zero) }
    var importing by remember { mutableStateOf(false) }

    // 编辑窗口 / 删除确认：书和显示状态分开存，关闭动画期间内容不会突然消失
    var editBook by remember { mutableStateOf<Book?>(null) }
    var editShow by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var pendingCover by remember { mutableStateOf<Bitmap?>(null) }
    var confirmShow by remember { mutableStateOf(false) }

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

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { BookShelf.decodeCoverFromUri(context, uri) }
            if (bmp == null) toast(tr("无法读取这张图片")) else pendingCover = bmp
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    PageGlassTopBar(
                        title = tr("书架"),
                        backdrop = backdrop,
                        glass = glass,
                        scrollBehavior = scrollBehavior,
                        onBack = onBack,
                        backButton = { LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack) },
                        actions = {
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
                        },
                    )
                },
            ) { innerPadding ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .kyantLayerBackdrop(liquidBackdrop)
                        .scrollEndHaptic()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .pageBackground(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (books.isEmpty() && loaded) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = tr("书架是空的。点右上角「更多」→「从本地导入」添加 TXT / EPUB 小说。"),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 48.dp),
                            )
                        }
                    }
                    items(books, key = { it.id }) { book ->
                        BookItem(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                editBook = book
                                editTitle = book.title
                                pendingCover = null
                                editShow = true
                            },
                        )
                    }
                }
            }

            // 右上角菜单：与 Scaffold 同级、放在最后 → 位于最上层（盖住顶栏）
            AppGlassPopup(
                show = showMore,
                onDismissRequest = { showMore = false },
                anchorBounds = Rect(moreAnchor.left, moreAnchor.bottom, moreAnchor.right, moreAnchor.bottom),
                backdrop = bd,
            ) {
                GlassPopupItem(
                    text = tr("订阅网络小说"),
                    onClick = {
                        showMore = false
                        toast(tr("网络订阅暂未开放，敬请期待"))
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
        }
    }
}

/** 书架网格里的一格：大封面 + 书名 + 作者；点按打开，长按编辑。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookItem(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
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
