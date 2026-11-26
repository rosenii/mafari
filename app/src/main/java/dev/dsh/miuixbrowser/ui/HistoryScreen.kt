/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.browser.FaviconLoader
import dev.dsh.miuixbrowser.browser.rememberFavicon
import dev.dsh.miuixbrowser.crypto.EncryptionInfoDialog
import dev.dsh.miuixbrowser.crypto.EncryptionLockButton
import dev.dsh.miuixbrowser.data.Favorite
import dev.dsh.miuixbrowser.data.FavoritesStore
import dev.dsh.miuixbrowser.data.HistoryEntry
import dev.dsh.miuixbrowser.data.HistoryStore
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.dsh.miuixbrowser.engine.gecko.findActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassTabRow
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.File
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 清除历史的时间范围；millis 为 null 表示全部 */
private enum class ClearRange(val label: String, val millis: Long?) {
    Day(tr("清除最近 1 天"), 24L * 60 * 60 * 1000),
    Week(tr("清除最近一周"), 7L * 24 * 60 * 60 * 1000),
    Month(tr("清除最近一月"), 30L * 24 * 60 * 60 * 1000),
    All(tr("全部清除"), null),
}

/**
 * 菜单锚点：位于按钮顶部再往上一小段距离。
 * GlassPopup 在空间不足以向下展开时会以锚点 bottom 为底边向上展开，
 * 所以 bottom 取按钮顶部上方，top 取按钮底部以强制向上展开。
 */
@Composable
private fun rememberAboveAnchor(bounds: Rect): Rect {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    return Rect(bounds.left, bounds.bottom, bounds.right, bounds.top - gap)
}

private const val TAB_BOOKMARKS = 0
private const val TAB_HISTORY = 1

/**
 * 书签 + 历史库：顶部分两栏（可左右滑动），书签按分组展示；
 * 书签栏右下角 FAB 提供新建 / 导入 / 导出（兼容 Chrome、Firefox 的 Netscape 书签 HTML）。
 */
@Composable
fun LibraryScreen(
    favorites: FavoritesStore,
    store: HistoryStore,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    initialTab: Int = TAB_HISTORY,
    blurEnabled: Boolean = true,
    initialGroup: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(0, 1),
        pageCount = { 2 },
    )
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    var showLockInfo by remember { mutableStateOf(false) }
    // 分页内容各自的滚动状态，避免两栏互相干扰
    val bookmarkListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val historyListState = androidx.compose.foundation.lazy.rememberLazyListState()

    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    var showMenu by remember { mutableStateOf(false) }
    var editingFav by remember { mutableStateOf<Favorite?>(null) }
    var creatingFav by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<String?>(null) }
    var renamingGroup by remember { mutableStateOf<String?>(null) }
    var showClearMenu by remember { mutableStateOf(false) }
    var clearAnchor by remember { mutableStateOf(Rect.Zero) }
    var clearRange by remember { mutableStateOf(ClearRange.All) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(tr("无法读取所选文件"))
            val result = favorites.importBookmarks(decodeBookmarkText(bytes))
            val msg = when {
                result.added > 0 -> tr("已导入 {0} 条书签", result.added)
                result.found > 0 -> tr("识别到 {0} 条书签，均已存在，无需重复导入", result.found)
                else -> tr("未识别到书签（文件 {0} 字节；支持 Chrome / Edge / Firefox 导出的 HTML 或 JSON）", bytes.size)
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(context, tr("导入失败：{0}", it.message), Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = if (pagerState.currentPage == TAB_BOOKMARKS) tr("书签") else tr("历史"),
                backdrop = backdrop,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                backButton = {
                    LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                },
                actions = {
                    EncryptionLockButton(onClick = { showLockInfo = true }, liquid = liquid, topScrolled = topScrolled)
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
                                label = if (pagerState.currentPage == TAB_BOOKMARKS) tr("搜索书签") else tr("搜索历史"),
                                singleLine = true,
                            )
                        }
                        GlassTabRow(
                            tabs = listOf(tr("书签"), tr("历史")),
                            selectedIndex = pagerState.currentPage,
                            onSelect = { index ->
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            backdrop = if (glass) backdrop else null,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        EncryptionInfoDialog(
            show = showLockInfo,
            onDismiss = { showLockInfo = false },
            backdrop = backdrop,
            liquid = liquid,
            scope = tr("书签：加密套件加密；历史：SQLCipher 整库 AES-256"),
        )
        val topInset = innerPadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .pageBackground(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = true,
                    contentPadding = PaddingValues(top = topInset),
                ) { page ->
                    when (page) {
                        TAB_BOOKMARKS -> BookmarksPage(
                            favorites = favorites,
                            listState = bookmarkListState,
                            scrollBehavior = scrollBehavior,
                            onOpen = onOpen,
                            onEdit = { editingFav = it },
                            onGroupLongClick = { renamingGroup = it },
                            query = query,
                            initialGroup = initialGroup,
                        )

                        else -> HistoryPage(
                            store = store,
                            listState = historyListState,
                            scrollBehavior = scrollBehavior,
                            onOpen = onOpen,
                            onDelete = { store.remove(it.url) },
                            query = query,
                        )
                    }
                }
            }

            // 书签栏右下角 FAB：GlassPopup 放在全屏 Box 根坐标系，锚点用 boundsInRoot
            if (pagerState.currentPage == TAB_BOOKMARKS) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 96.dp),
                ) {
                    LiquidGlassFab(
                        onClick = { showMenu = true },
                        backdrop = liquid,
                        primary = true,
                        modifier = Modifier.onGloballyPositioned { menuAnchor = it.boundsInRoot() },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Glass.Add,
                            contentDescription = tr("书签操作"),
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                // popup 与 FAB 同级、同在全屏 Box 内，坐标一致
                AppGlassPopup(
                    show = showMenu,
                    onDismissRequest = { showMenu = false },
                    anchorBounds = rememberAboveAnchor(menuAnchor),
                    backdrop = backdrop,
                ) {
                    GlassPopupItem(
                        text = tr("新建书签"),
                        onClick = {
                            showMenu = false
                            creatingFav = true
                        },
                        icon = MiuixIcons.Glass.Add,
                    )
                    GlassPopupItem(
                        text = tr("导入"),
                        summary = tr("Chrome / Edge / Firefox 书签（HTML / JSON）"),
                        onClick = {
                            showMenu = false
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        icon = MiuixIcons.Glass.Download,
                    )
                    GlassPopupItem(
                        text = tr("导出"),
                        summary = tr("保存到 Download/mafari"),
                        onClick = {
                            showMenu = false
                            exportBookmarks(context, favorites.exportNetscapeHtml())
                        },
                        icon = MiuixIcons.Glass.File,
                    )
                }
            }

            // 历史栏右下角清空 FAB：位置 / 大小 / 形状与书签 FAB 一致，图标为红色垃圾桶
            if (pagerState.currentPage == TAB_HISTORY && store.totalCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 96.dp),
                ) {
                    LiquidGlassFab(
                        onClick = { showClearMenu = true },
                        backdrop = liquid,
                        modifier = Modifier.onGloballyPositioned { clearAnchor = it.boundsInRoot() },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Glass.Delete,
                            contentDescription = tr("清空历史"),
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                AppGlassPopup(
                    show = showClearMenu,
                    onDismissRequest = { showClearMenu = false },
                    anchorBounds = rememberAboveAnchor(clearAnchor),
                    backdrop = backdrop,
                ) {
                    ClearRange.entries.forEach { range ->
                        GlassPopupItem(
                            text = range.label,
                            onClick = {
                                showClearMenu = false
                                clearRange = range
                                showClearConfirm = true
                            },
                            icon = MiuixIcons.Glass.Delete,
                            contentColor = if (range == ClearRange.All) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onSurfaceContainer
                            },
                        )
                    }
                }
            }
        }

        // 清除历史确认：居中对话框
        val confirmCount = if (showClearConfirm) {
            clearRange.millis?.let { store.countWithin(it) } ?: store.totalCount
        } else {
            0
        }
        AppGlassDialog(
            show = showClearConfirm,
            title = tr("清除历史记录"),
            backdrop = backdrop,
            onDismissRequest = { showClearConfirm = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (confirmCount > 0) {
                        tr("将删除{0}共 {1} 条历史记录，此操作无法撤销。", clearRange.label.removePrefix(tr("清除")), confirmCount)
                    } else {
                        tr("{0}没有可删除的历史记录。", clearRange.label.removePrefix(tr("清除")))
                    },
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LiquidGlassPillButton(
                        text = tr("取消"),
                        onClick = { showClearConfirm = false },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                    )
                    LiquidGlassPillButton(
                        text = tr("确认删除"),
                        onClick = {
                            val within = clearRange.millis
                            if (within == null) store.clear() else store.clearWithin(within)
                            showClearConfirm = false
                        },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                        enabled = confirmCount > 0,
                        role = LiquidRole.Destructive,
                    )
                }
            }
        }

        // 分组管理（长按分组行）：重命名；左下删除（再弹确认），右下保存
        val groupToRename = renamingGroup
        var renameText by remember(groupToRename) { mutableStateOf(groupToRename.orEmpty()) }
        AppGlassDialog(
            show = groupToRename != null,
            title = tr("编辑分组"),
            backdrop = backdrop,
            onDismissRequest = { renamingGroup = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("分组路径（用 / 分层）"),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LiquidGlassPillButton(
                        text = tr("删除"),
                        onClick = {
                            deletingGroup = groupToRename
                            renamingGroup = null
                        },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                        role = LiquidRole.Destructive,
                    )
                    LiquidGlassPillButton(
                        text = tr("保存"),
                        onClick = {
                            val old = groupToRename
                            if (old != null) {
                                if (renameText.isBlank()) {
                                    Toast.makeText(context, tr("分组名不能为空"), Toast.LENGTH_SHORT).show()
                                    return@LiquidGlassPillButton
                                }
                                favorites.renameGroup(old, renameText)
                            }
                            renamingGroup = null
                        },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                        role = LiquidRole.Primary,
                    )
                }
            }
        }

        val groupToDelete = deletingGroup
        AppGlassDialog(
            show = groupToDelete != null && !favorites.isProtectedGroup(groupToDelete),
            title = tr("删除分组"),
            backdrop = backdrop,
            onDismissRequest = { deletingGroup = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = tr("将删除分组「{0}」（含子分组）及其中的 {1} 个书签，此操作无法撤销。", groupToDelete.orEmpty(), groupToDelete?.let { favorites.itemsInTree(it).size } ?: 0),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LiquidGlassPillButton(
                        text = tr("取消"),
                        onClick = { deletingGroup = null },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                    )
                    LiquidGlassPillButton(
                        text = tr("删除"),
                        onClick = {
                            groupToDelete?.let { favorites.removeGroup(it) }
                            deletingGroup = null
                        },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                        role = LiquidRole.Destructive,
                    )
                }
            }
        }

        FavoriteEditorDialog(
            show = creatingFav || editingFav != null,
            initial = editingFav,
            groups = favorites.groups(),
            backdrop = backdrop,
            liquidBackdrop = liquid,
            onDismiss = {
                creatingFav = false
                editingFav = null
            },
            onDelete = editingFav?.let { fav ->
                {
                    val idx = favorites.items.indexOfFirst {
                        it.url == fav.url && it.name == fav.name
                    }
                    if (idx >= 0) favorites.remove(idx)
                    creatingFav = false
                    editingFav = null
                }
            },
            onConfirm = { name, url, group, pinned ->
                if (editingFav != null) {
                    val idx = favorites.items.indexOfFirst {
                        it.url == editingFav!!.url && it.name == editingFav!!.name
                    }
                    if (idx >= 0) {
                        favorites.update(idx, name, url, group, pinned)
                    } else {
                        favorites.add(name, url, group, pinned)
                    }
                } else {
                    favorites.add(name, url, group, pinned)
                }
                creatingFav = false
                editingFav = null
            },
        )
    }
    }
}

/** 历史页「分组」与书签页「排序」共用的一行胶囊选项：放在列表外面，滑动时固定在上层；放不下时可横向滑动。 */
@Composable
private fun <T> OptionPillRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(end = 4.dp),
        )
        options.forEach { (value, text) ->
            val isSelected = value == selected
            Text(
                text = text,
                fontSize = 13.sp,
                maxLines = 1,
                color = if (isSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    )
                    .clickable {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        if (!isSelected) onSelect(value)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** 书签排序方式。书签没有单独的创建时间，「新 / 旧」按添加顺序（新建、导入、同步进来的先后）。 */
private enum class BookmarkSortMode(val id: String, val label: String) {
    NewOld("new_old", tr("新-旧")),
    OldNew("old_new", tr("旧-新")),
    AZ("az", "A-Z"),
    ZA("za", "Z-A"),
    Custom("custom", tr("自定义"));

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: OldNew
    }
}

/** 书签树节点：[path] 是完整分组路径（以 / 分层）；[items] 是直接放在该文件夹里的书签。 */
private class BmNode(val path: String) {
    val name: String = path.substringAfterLast('/').ifBlank { tr("未命名") }
    val children = ArrayList<BmNode>()
    val items = ArrayList<Favorite>()
    /** 含全部子孙文件夹的（已按搜索词过滤的）书签总数 */
    var total = 0
}

/** 按分组路径里的 / 建出层级；文件夹顺序沿用 [FavoritesStore.groups]，缺失的祖先自动补齐。 */
private fun buildBookmarkTree(favorites: FavoritesStore, q: String, mode: BookmarkSortMode): List<BmNode> {
    val nodes = HashMap<String, BmNode>()
    val roots = ArrayList<BmNode>()
    fun ensure(path: String): BmNode {
        nodes[path]?.let { return it }
        val node = BmNode(path)
        nodes[path] = node
        val cut = path.lastIndexOf('/')
        if (cut > 0) ensure(path.substring(0, cut)).children.add(node) else roots.add(node)
        return node
    }
    favorites.groups().forEach { ensure(it) }
    favorites.items.forEach { f ->
        if (q.isEmpty() || f.name.contains(q, ignoreCase = true) || f.url.contains(q, ignoreCase = true)) {
            ensure(f.group.ifBlank { Favorite.GROUP_PERSONAL }).items.add(f)
        }
    }
    fun count(n: BmNode): Int {
        n.total = n.items.size + n.children.sumOf { count(it) }
        return n.total
    }
    roots.forEach { count(it) }
    sortBookmarkTree(roots, favorites, mode)
    return roots
}

/**
 * 按 [mode] 排序：每个文件夹里的子文件夹和书签，以及顶层分组。
 * 「个人收藏」始终固定在最上面；「自定义」只影响顶层分组（用拖动手柄排），其余保持添加顺序。
 * 每一层里置顶的书签（[Favorite.pinned]）无论哪种排序都排在本层书签最前。
 */
private fun sortBookmarkTree(roots: MutableList<BmNode>, favorites: FavoritesStore, mode: BookmarkSortMode) {
    val collator = java.text.Collator.getInstance(Locale.getDefault())
    fun <T> sortList(list: MutableList<T>, name: (T) -> String) {
        when (mode) {
            BookmarkSortMode.NewOld -> list.reverse()
            BookmarkSortMode.OldNew, BookmarkSortMode.Custom -> Unit
            BookmarkSortMode.AZ -> list.sortWith(Comparator { a, b -> collator.compare(name(a), name(b)) })
            BookmarkSortMode.ZA -> list.sortWith(Comparator { a, b -> collator.compare(name(b), name(a)) })
        }
    }
    // 「同层置顶」：本层书签里置顶的排最前（后置顶的在上面），其余按所选排序；分组文件夹始终在书签上面，不受影响
    fun pinFirst(list: MutableList<Favorite>) {
        if (list.none { it.pinned }) return
        val pinned = list.filter { it.pinned }.sortedByDescending { it.pinnedAt }
        val rest = list.filter { !it.pinned }
        list.clear()
        list.addAll(pinned)
        list.addAll(rest)
    }
    fun walk(n: BmNode) {
        sortList(n.children) { it.name }
        sortList(n.items) { it.name }
        pinFirst(n.items)
        n.children.forEach { walk(it) }
    }
    roots.forEach { walk(it) }
    val personal = roots.firstOrNull { favorites.isProtectedGroup(it.path) }
    val others = roots.filter { it !== personal }.toMutableList()
    if (mode == BookmarkSortMode.Custom) {
        val order = favorites.rootGroupOrder()
        others.sortBy { n -> order.indexOf(n.path).let { if (it < 0) Int.MAX_VALUE else it } }
    } else {
        sortList(others) { it.name }
    }
    roots.clear()
    personal?.let { roots.add(it) }
    roots.addAll(others)
}

/** 书签（单条）拖动排序的控制器：「自定义」排序下长按书签图标拖动，只在同一文件夹内换位。 */
private class BmDragCtl(
    val enabled: Boolean = false,
    val draggingId: String? = null,
    /** 被拖书签行相对它布局位置的 y 偏移（px） */
    val offsetY: (String) -> Float = { 0f },
    val onStart: (String, Float) -> Unit = { _, _ -> },
    val onDrag: (Float) -> Unit = {},
    val onEnd: () -> Unit = {},
)

@Composable
private fun BookmarksPage(
    favorites: FavoritesStore,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    onOpen: (String) -> Unit,
    onEdit: (Favorite) -> Unit,
    onGroupLongClick: (String) -> Unit,
    query: String = "",
    initialGroup: String? = null,
) {
    val q = query.trim()
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("mafari", android.content.Context.MODE_PRIVATE) }
    var sortMode by remember { mutableStateOf(BookmarkSortMode.fromId(sp.getString("bookmark_sort_mode", null))) }
    // 订阅文件夹注册表的变化（拖动排序改的是注册表顺序）
    @Suppress("UNUSED_VARIABLE") val folderRev = favorites.folderRev
    val roots = buildBookmarkTree(favorites, q, sortMode)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 拖动排序状态：[dragPointer] 是手指在列表里的 y（与 layoutInfo 的 offset 同一坐标），[dragGrab] 是手指距行顶的距离
    var dragPath by remember { mutableStateOf<String?>(null) }
    var dragPointer by remember { mutableFloatStateOf(0f) }
    var dragGrab by remember { mutableFloatStateOf(0f) }
    // 单条书签的拖动排序状态（含义同上；只在「自定义」排序且没有搜索词时启用）
    var dragBm by remember { mutableStateOf<String?>(null) }
    var bmPointer by remember { mutableFloatStateOf(0f) }
    var bmGrab by remember { mutableFloatStateOf(0f) }
    val groupHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val groupTick = { groupHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    // 顶层分组默认折叠（只有一个时默认展开），次级文件夹默认折叠；搜索时全部自动展开
    val expanded = remember {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().also { m ->
            // 从首页「书签分组」进来：展开目标分组及其全部上级
            var cur = initialGroup.orEmpty()
            while (cur.isNotEmpty()) {
                m[cur] = true
                cur = if (cur.contains('/')) cur.substringBeforeLast('/') else ""
            }
        }
    }
    val onlyOneGroup = roots.count { it.total > 0 } <= 1
    val noMatch = q.isNotEmpty() && roots.none { it.total > 0 }
    // 拖动期间：自动滚动 + 检测是否越过相邻分组（越过就交换顺序）；被拖的行滑出屏幕则终止拖动
    LaunchedEffect(dragPath) {
        val p = dragPath ?: return@LaunchedEffect
        val key = "g-$p"
        val edge = with(density) { 48.dp.toPx() }
        val step = with(density) { 12.dp.toPx() }
        var missing = 0
        var waitOffset: Int? = null
        var waitFrames = 0
        while (true) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val me = info.visibleItemsInfo.firstOrNull { it.key == key }
            if (me == null) {
                if (++missing > 3) { dragPath = null; return@LaunchedEffect }
                continue
            }
            missing = 0
            if (waitOffset == me.offset && waitFrames++ < 10) {
                // 刚交换过，等布局更新后再判断，避免来回抖动
            } else {
                waitOffset = null
                waitFrames = 0
                val top = dragPointer - dragGrab
                val center = top + me.size / 2f
                val target = info.visibleItemsInfo.firstOrNull {
                    val k = it.key as? String
                    k != null && k != key && k.startsWith("g-") && center >= it.offset && center < it.offset + it.size
                }
                val tp = (target?.key as? String)?.removePrefix("g-")
                if (tp != null && !favorites.isProtectedGroup(tp)) {
                    favorites.reorderRootGroup(p, tp)
                    waitOffset = me.offset
                    groupHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                }
            }
            val top = dragPointer - dragGrab
            val d = when {
                top < info.viewportStartOffset + edge -> -step
                top + me.size > info.viewportEndOffset - edge -> step
                else -> 0f
            }
            if (d != 0f) listState.scrollBy(d)
        }
    }
    // 书签拖动期间：自动滚动 + 越过同文件夹里相邻（未置顶）书签就交换顺序；被拖的行滑出屏幕则终止
    LaunchedEffect(dragBm) {
        val id = dragBm ?: return@LaunchedEffect
        val key = "b-$id"
        val edge = with(density) { 48.dp.toPx() }
        val step = with(density) { 12.dp.toPx() }
        var missing = 0
        var waitOffset: Int? = null
        var waitFrames = 0
        fun groupOf(f: Favorite) = f.group.ifBlank { Favorite.GROUP_PERSONAL }
        while (true) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val me = info.visibleItemsInfo.firstOrNull { it.key == key }
            if (me == null) {
                if (++missing > 3) { dragBm = null; return@LaunchedEffect }
                continue
            }
            missing = 0
            if (waitOffset == me.offset && waitFrames++ < 10) {
                // 刚交换过，等布局更新后再判断，避免来回抖动
            } else {
                waitOffset = null
                waitFrames = 0
                val mine = favorites.items.firstOrNull { it.id == id }
                if (mine == null) { dragBm = null; return@LaunchedEffect }
                val top = bmPointer - bmGrab
                val center = top + me.size / 2f
                val target = info.visibleItemsInfo.firstOrNull {
                    val k = it.key as? String
                    k != null && k != key && k.startsWith("b-") && center >= it.offset && center < it.offset + it.size
                }
                val other = (target?.key as? String)?.removePrefix("b-")?.let { tid -> favorites.items.firstOrNull { it.id == tid } }
                if (other != null && !other.pinned && groupOf(other) == groupOf(mine)) {
                    favorites.reorderBookmark(id, other.id)
                    waitOffset = me.offset
                    groupHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                }
            }
            val top = bmPointer - bmGrab
            val d = when {
                top < info.viewportStartOffset + edge -> -step
                top + me.size > info.viewportEndOffset - edge -> step
                else -> 0f
            }
            if (d != 0f) listState.scrollBy(d)
        }
    }
    // 搜索时不允许拖书签（过滤后的列表顺序不完整）；切换排序方式同样终止拖动
    val bmCtl = BmDragCtl(
        enabled = sortMode == BookmarkSortMode.Custom && q.isEmpty(),
        draggingId = dragBm,
        offsetY = { id ->
            val off = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "b-$id" }?.offset ?: 0
            (bmPointer - bmGrab) - off
        },
        onStart = { id, grab ->
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "b-$id" }
            if (info != null) {
                bmGrab = grab
                bmPointer = info.offset + grab
                dragBm = id
                groupHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
        },
        onDrag = { dy -> bmPointer += dy },
        onEnd = { dragBm = null },
    )
    Column(modifier = Modifier.fillMaxSize()) {
    // 排序方式固定在顶部（与历史页的分组同款），放在列表外面，滑动时不随列表滚走
    if (favorites.items.isNotEmpty()) {
        OptionPillRow(
            label = tr("排序"),
            options = BookmarkSortMode.entries.map { it to it.label },
            selected = sortMode,
            onSelect = { m ->
                sortMode = m
                dragPath = null
                dragBm = null
                sp.edit().putString("bookmark_sort_mode", m.id).apply()
            },
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .scrollEndHaptic()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(top = 0.dp, bottom = 96.dp),
    ) {
        if (favorites.items.isEmpty()) {
            item {
                Text(
                    text = tr("还没有书签。点右下角按钮新建，或从 Chrome / Firefox 导入。"),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        if (noMatch) {
            item {
                Text(
                    text = tr("没有匹配的书签"),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        roots.forEach { root ->
            // 「个人收藏」即使为空也保留标题（不能删除的保留分组）；其它空分组 / 搜索无匹配时不显示
            if (root.total == 0 && (q.isNotEmpty() || !favorites.isProtectedGroup(root.path))) return@forEach
            // 拖动排序时所有分组临时收起，只剩一行行分组标题，好拖
            val open = dragPath == null && (q.isNotEmpty() || (expanded[root.path] ?: onlyOneGroup))
            val draggable = sortMode == BookmarkSortMode.Custom && !favorites.isProtectedGroup(root.path)
            val isDragged = dragPath == root.path
            item(key = "g-${root.path}") {
                BookmarkGroupHeader(
                    modifier = if (isDragged) Modifier else Modifier.animateItem(),
                    name = root.name,
                    count = root.total,
                    open = open,
                    dragHandle = draggable,
                    // 「自定义」排序下，个人收藏固定在最上面：左侧显示同样大小的置顶图标，不可点击 / 拖动 / 取消
                    pinMark = sortMode == BookmarkSortMode.Custom && favorites.isProtectedGroup(root.path),
                    isDragged = isDragged,
                    dragOffsetY = {
                        val off = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == "g-${root.path}" }?.offset ?: 0
                        (dragPointer - dragGrab) - off
                    },
                    onDragStart = { grab ->
                        // 先把注册表补全，之后按注册表顺序排
                        favorites.knownFolders()
                        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "g-${root.path}" }
                        if (info != null) {
                            dragGrab = grab
                            dragPointer = info.offset + grab
                            dragPath = root.path
                            groupHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { dy -> dragPointer += dy },
                    onDragEnd = { dragPath = null },
                    onClick = {
                        groupTick()
                        expanded[root.path] = !open
                    },
                    // 长按分组行可重命名 / 删除（展开 / 收起都行）；「个人收藏」是保留分组，不可操作
                    onLongClick = if (!favorites.isProtectedGroup(root.path)) ({ onGroupLongClick(root.path) }) else null,
                )
            }
            if (open) {
                bookmarkFolderContent(
                    node = root,
                    level = 0,
                    searching = q.isNotEmpty(),
                    expanded = expanded,
                    onOpen = onOpen,
                    onEdit = onEdit,
                    onGroupLongClick = onGroupLongClick,
                    onTick = groupTick,
                    dragCtl = bmCtl,
                )
            }
        }
    }
    }
}

/**
 * 展开某个分组 / 文件夹后的内容：先列次级文件夹（点击就地展开，递归），再列该层的书签。
 * [level] 控制缩进，每深一层右移一点。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.bookmarkFolderContent(
    node: BmNode,
    level: Int,
    searching: Boolean,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onOpen: (String) -> Unit,
    onEdit: (Favorite) -> Unit,
    onGroupLongClick: (String) -> Unit,
    onTick: () -> Unit,
    dragCtl: BmDragCtl = BmDragCtl(),
) {
    node.children.forEach { child ->
        if (child.total == 0) return@forEach
        val open = searching || (expanded[child.path] ?: false)
        item(key = "f-${child.path}") {
            BookmarkFolderRow(
                name = child.name,
                folderCount = child.children.count { it.total > 0 },
                bookmarkCount = child.total,
                open = open,
                level = level,
                onClick = {
                    onTick()
                    expanded[child.path] = !open
                },
                onLongClick = { onGroupLongClick(child.path) },
            )
        }
        if (open) {
            bookmarkFolderContent(child, level + 1, searching, expanded, onOpen, onEdit, onGroupLongClick, onTick, dragCtl)
        }
    }
    // key 用书签 id（稳定）：拖动换位时行跟着走，不会因下标变化被当成新行
    items(node.items.size, key = { i -> "b-${node.items[i].id}" }) { i ->
        val fav = node.items[i]
        val dragged = dragCtl.draggingId == fav.id
        BookmarkRow(
            modifier = if (dragCtl.enabled && !dragged) Modifier.animateItem() else Modifier,
            fav = fav,
            level = level,
            onOpen = onOpen,
            onEdit = onEdit,
            // 置顶书签的先后由置顶时间决定，不参与拖动
            dragHandle = dragCtl.enabled && !fav.pinned,
            isDragged = dragged,
            dragOffsetY = { dragCtl.offsetY(fav.id) },
            onDragStart = { grab -> dragCtl.onStart(fav.id, grab) },
            onDrag = dragCtl.onDrag,
            onDragEnd = dragCtl.onEnd,
        )
    }
}

/** 次级分组：文件夹图标 + 分组名，样式与普通书签行一致；点一下展开 / 收起，长按管理。 */
@Composable
private fun BookmarkFolderRow(
    name: String,
    folderCount: Int,
    bookmarkCount: Int,
    open: Boolean,
    level: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // ChevronBackward 默认朝左：收起时转 180° 朝右（›），展开时转 270° 朝下
    val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 270f else 180f, label = "folderArrow")
    val tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.05f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (12 + level * 14).dp, end = 12.dp)
            .padding(bottom = 8.dp)
            // 非常淡的蓝色底色：一眼区分分组行和普通书签行
            .drawWithContent {
                drawContent()
                drawRoundRect(tint, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
            },
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .squircleClip(10.dp)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Folder,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        if (folderCount > 0) tr("{0} 个分组", folderCount) else null,
                        tr("{0} 个书签", bookmarkCount),
                    ).joinToString(" · "),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 与主分组标题同款：36dp 圆形小按钮 + 旋转的箭头
            AppSmallButton(onClick = onClick, size = 36.dp, inPage = true) {
                Icon(
                    imageVector = MiuixIcons.Glass.ChevronBackward,
                    contentDescription = if (open) tr("收起") else tr("展开"),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
                )
            }
        }
    }
}

/** 普通书签行。 */
@Composable
private fun BookmarkRow(
    fav: Favorite,
    level: Int,
    onOpen: (String) -> Unit,
    onEdit: (Favorite) -> Unit,
    modifier: Modifier = Modifier,
    /** 「自定义」排序下：长按左侧书签图标可拖动排序（点一下图标仍是打开书签） */
    dragHandle: Boolean = false,
    isDragged: Boolean = false,
    dragOffsetY: () -> Float = { 0f },
    /** 参数：手指按下点距本行顶部的距离（px） */
    onDragStart: (Float) -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var iconCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val startCb by rememberUpdatedState(onDragStart)
    val dragCb by rememberUpdatedState(onDrag)
    val endCb by rememberUpdatedState(onDragEnd)
    val openCb by rememberUpdatedState(onOpen)
    val dragMod = if (dragHandle) {
        Modifier
            // 点按图标照旧打开书签；长按没拖动就松手也不会误触发打开
            .pointerInput(fav.url) { detectTapGestures(onTap = { openCb(fav.url) }, onLongPress = {}) }
            // 长按图标才开始拖动（有触感反馈），松手结束；没长按就上下滑仍是滚动列表
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { off ->
                        val h = iconCoords?.positionInRoot()?.y ?: 0f
                        val r = rowCoords?.positionInRoot()?.y ?: 0f
                        startCb(h + off.y - r)
                    },
                    onDragEnd = { endCb() },
                    onDragCancel = { endCb() },
                    onDrag = { change, drag ->
                        change.consume()
                        dragCb(drag.y)
                    },
                )
            }
    } else Modifier
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isDragged) Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationY = dragOffsetY() }
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                else Modifier,
            )
            .onGloballyPositioned { rowCoords = it }
            .padding(start = (12 + level * 14).dp, end = 12.dp)
            .padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(fav.url) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 外层不裁剪：置顶角标要探出图标底的右上角
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .onGloballyPositioned { iconCoords = it }
                    .then(dragMod),
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .squircleClip(10.dp)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                val favicon = rememberFavicon(fav.url)
                if (favicon != null) {
                    androidx.compose.foundation.Image(
                        bitmap = favicon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    // 图标还在加载或抓取失败时的占位
                    Icon(
                        imageVector = MiuixIcons.Glass.File,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (fav.pinned) {
                // 置顶角标：主色小圆 + 斜插的白色图钉，压在图标右上角
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-5).dp)
                        .size(16.dp)
                        .border(1.5.dp, MiuixTheme.colorScheme.surface, CircleShape)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Pin,
                        contentDescription = tr("已置顶"),
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(9.dp).graphicsLayer { rotationZ = 45f },
                    )
                }
            }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fav.name,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = fav.url,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = tr("编辑"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .squircleClip(8.dp)
                    .clickable { onEdit(fav) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 书签分组标题：左侧分组名 + 数量，右侧按钮点一下展开 / 收起（整行也可点击；长按管理分组）。
 * 「自定义」排序下，顶层分组（个人收藏除外）左侧多一个始终显示的排序手柄（自绘圆点图标，无底框），按住上下拖动即可排序；
 * 个人收藏左侧是同样大小的置顶图标（固定在最上面，不可点击 / 拖动）。
 */
@Composable
private fun BookmarkGroupHeader(
    name: String,
    count: Int,
    open: Boolean,
    onClick: () -> Unit,
    /** null = 不响应长按 */
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    dragHandle: Boolean = false,
    /** true = 左侧显示固定的置顶图标（与排序手柄同大小，不响应任何手势）；与 [dragHandle] 互斥 */
    pinMark: Boolean = false,
    isDragged: Boolean = false,
    dragOffsetY: () -> Float = { 0f },
    /** 参数：手指按下点距本行顶部的距离（px） */
    onDragStart: (Float) -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    // ChevronBackward 默认朝左：收起时转 180° 朝右（›），展开时转 270° 朝下
    val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 270f else 180f, label = "groupArrow")
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val startCb by rememberUpdatedState(onDragStart)
    val dragCb by rememberUpdatedState(onDrag)
    val endCb by rememberUpdatedState(onDragEnd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isDragged) Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationY = dragOffsetY() }
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .squircleBackground(MiuixTheme.colorScheme.surface, 16.dp)
                else Modifier,
            )
            .onGloballyPositioned { rowCoords = it }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = if (dragHandle || pinMark) 12.dp else 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle) {
            var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            // 无底色的排序手柄（自绘 AppIcons.DragGrip：两列三排圆点）；36dp 是触控区，拖动中图标变主色
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .onGloballyPositioned { handleCoords = it }
                    // 点按手柄不触发整行的展开
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    // 长按手柄才开始拖动排序（长按时有触感反馈），松手结束；没长按就上下滑仍是滚动列表
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { off ->
                                val h = handleCoords?.positionInRoot()?.y ?: 0f
                                val r = rowCoords?.positionInRoot()?.y ?: 0f
                                startCb(h + off.y - r)
                            },
                            onDragEnd = { endCb() },
                            onDragCancel = { endCb() },
                            onDrag = { change, drag ->
                                change.consume()
                                dragCb(drag.y)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.DragGrip,
                    contentDescription = tr("拖动排序"),
                    tint = if (isDragged) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
        } else if (pinMark) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    // 不可点击：吞掉点按 / 长按，既不触发整行展开，也没有任何可操作的反馈
                    .pointerInput(Unit) { detectTapGestures(onTap = {}, onLongPress = {}) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Pin,
                    contentDescription = tr("固定在最上面"),
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = 45f },
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$count",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        AppSmallButton(onClick = onClick, size = 36.dp, inPage = true) {
            Icon(
                imageVector = MiuixIcons.Glass.ChevronBackward,
                contentDescription = if (open) tr("收起") else tr("展开"),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
            )
        }
    }
}

@Composable
private fun HistoryPage(
    store: HistoryStore,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    onOpen: (String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    query: String = "",
) {
    val q = query.trim()
    // 分页从数据库读：先取一页，滑到接近底部再多取一页；搜索由数据库完成
    var limit by remember(q) { mutableIntStateOf(HISTORY_PAGE) }
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(q, limit, store.version) {
        val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.query(q, limit + 1) }
        hasMore = rows.size > limit
        entries = if (hasMore) rows.take(limit) else rows
        loaded = true
    }
    androidx.compose.runtime.LaunchedEffect(hasMore, entries.size) {
        if (hasMore) {
            androidx.compose.runtime.snapshotFlow {
                val info = listState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
            }.first { it }
            limit += HISTORY_PAGE
        }
    }
    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentAlignment = Alignment.Center,
        ) {
            if (loaded) {
                Text(
                    text = if (q.isEmpty()) tr("还没有浏览记录") else tr("没有匹配的历史记录"),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        return
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val sp = remember { ctx.getSharedPreferences("mafari", android.content.Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(HistoryGroupMode.fromId(sp.getString("history_group_mode", null))) }
    // 默认全部展开：只记录被用户手动收起的分组；换分组方式时重置
    val collapsed = remember(mode) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val groups = remember(entries, mode) { groupHistory(entries, mode) }
    // 分组方式固定在顶部：放在列表外面，滑动时不随列表滚走，顶栏折叠后仍吸附在最上方
    Column(modifier = Modifier.fillMaxSize()) {
                OptionPillRow(
                    label = tr("分组"),
                    options = HistoryGroupMode.entries.map { it to it.label },
                    selected = mode,
                    onSelect = { m ->
                        mode = m
                        sp.edit().putString("history_group_mode", m.id).apply()
                    },
                )
    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .scrollEndHaptic()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(top = 0.dp, bottom = 96.dp),
    ) {
        groups.forEach { group ->
            val open = q.isNotEmpty() || collapsed[group.key] != true
            item(key = "h-${group.key}") {
                HistoryGroupHeader(
                    title = group.title,
                    count = group.entries.size,
                    open = open,
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        collapsed[group.key] = open
                    },
                )
            }
            if (!open) return@forEach
        items(group.entries.size, key = { i -> group.entries[i].url }) { index ->
            val entry = group.entries[index]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
                insideMargin = PaddingValues(14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(entry.url) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HistorySiteIcon(entry.url)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = entry.url,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = formatHistoryTime(entry.time, mode),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onDelete(entry) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Glass.Close,
                            contentDescription = tr("删除"),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        }
    }
    }
}

/** 历史记录里能取站点图标的主机：只认 http(s)，按小写、去 www. 规整（与 [FaviconLoader] 的键一致）；其余（about: / file: / data: 等）返回空串 */
private fun historyIconHost(url: String): String {
    val u = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return ""
    val scheme = u.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return ""
    return FaviconLoader.normalize(u.host.orEmpty())
}

/**
 * 历史页的站点图标：同一站点（域名忽略 www.）的所有记录共用同一张图，不重复加载——
 * 键就是规整后的主机名，[FaviconLoader] 里内存 → 磁盘 → 网络三级缓存，同键并发请求合并成一次，失败按次数退避。
 * 已在内存里的第一帧直接出图；没有时先等 150ms 再加载，快速滑动时滚出屏幕的行不会各自排起一个请求。
 */
@Composable
private fun rememberHistoryFavicon(url: String): ImageBitmap? {
    val host = remember(url) { historyIconHost(url) }
    var icon by remember(host) { mutableStateOf(if (host.isEmpty()) null else FaviconLoader.peek(host)) }
    LaunchedEffect(host) {
        if (icon == null && host.isNotEmpty()) {
            delay(150)
            icon = withContext(Dispatchers.IO) { FaviconLoader.load(host) }
        }
    }
    return icon
}

/** 历史记录左侧的站点图标：与书签行同款底板（主色淡底圆角方块），没有图标 / 还在加载时显示占位图标。 */
@Composable
private fun HistorySiteIcon(url: String) {
    val favicon = rememberHistoryFavicon(url)
    Box(
        modifier = Modifier
            .size(36.dp)
            .squircleClip(10.dp)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (favicon != null) {
            androidx.compose.foundation.Image(
                bitmap = favicon,
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Icon(
                imageVector = MiuixIcons.Glass.File,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 历史页每次从数据库多读的条数 */
private const val HISTORY_PAGE = 200

enum class HistoryGroupMode(val id: String, val label: String) {
    Day("day", tr("按日")),
    Week("week", tr("按周")),
    Month("month", tr("按月")),
    Year("year", tr("按年"));

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Day
    }
}

private class HistoryGroup(val key: String, val title: String, val entries: List<HistoryEntry>)

/** 按日 / 周（周一起算）/ 月分组，保持传入的时间倒序；标题用「今天 / 昨天 / 本周 / 上月」等相对叫法。 */
private fun groupHistory(entries: List<HistoryEntry>, mode: HistoryGroupMode): List<HistoryGroup> {
    fun startOf(time: Long): java.util.Calendar = java.util.Calendar.getInstance().apply {
        firstDayOfWeek = java.util.Calendar.MONDAY
        timeInMillis = time
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        when (mode) {
            HistoryGroupMode.Day -> Unit
            HistoryGroupMode.Week -> set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
            HistoryGroupMode.Month -> set(java.util.Calendar.DAY_OF_MONTH, 1)
            HistoryGroupMode.Year -> set(java.util.Calendar.DAY_OF_YEAR, 1)
        }
    }
    val nowStart = startOf(System.currentTimeMillis())
    fun shift(c: java.util.Calendar, n: Int): java.util.Calendar = (c.clone() as java.util.Calendar).apply {
        when (mode) {
            HistoryGroupMode.Day -> add(java.util.Calendar.DAY_OF_YEAR, n)
            HistoryGroupMode.Week -> add(java.util.Calendar.WEEK_OF_YEAR, n)
            HistoryGroupMode.Month -> add(java.util.Calendar.MONTH, n)
            HistoryGroupMode.Year -> add(java.util.Calendar.YEAR, n)
        }
    }
    val cur = nowStart.timeInMillis
    val prev = shift(nowStart, -1).timeInMillis
    val md = SimpleDateFormat(tr("M月d日"), dev.dsh.miuixbrowser.i18n.I18n.locale())
    val wk = arrayOf(tr("周日"), tr("周一"), tr("周二"), tr("周三"), tr("周四"), tr("周五"), tr("周六"))
    fun title(start: java.util.Calendar): String {
        val t = start.timeInMillis
        val sameYear = start.get(java.util.Calendar.YEAR) == nowStart.get(java.util.Calendar.YEAR)
        return when (mode) {
            HistoryGroupMode.Day -> when (t) {
                cur -> tr("今天")
                prev -> tr("昨天")
                else -> md.format(Date(t)) + " " + wk[start.get(java.util.Calendar.DAY_OF_WEEK) - 1]
            }
            HistoryGroupMode.Week -> {
                val end = shift(start, 1).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
                val range = md.format(Date(t)) + " - " + md.format(end.time)
                when (t) {
                    cur -> tr("本周 · {0}", range)
                    prev -> tr("上周 · {0}", range)
                    else -> range
                }
            }
            HistoryGroupMode.Month -> when (t) {
                cur -> tr("本月")
                prev -> tr("上月")
                else -> if (sameYear) tr("{0}月", start.get(java.util.Calendar.MONTH) + 1)
                else tr("{0}年{1}月", start.get(java.util.Calendar.YEAR), start.get(java.util.Calendar.MONTH) + 1)
            }
            HistoryGroupMode.Year -> when (t) {
                cur -> tr("今年")
                prev -> tr("去年")
                else -> tr("{0}年", start.get(java.util.Calendar.YEAR))
            }
        }
    }
    val out = LinkedHashMap<Long, Pair<java.util.Calendar, MutableList<HistoryEntry>>>()
    entries.forEach { e ->
        val st = startOf(e.time)
        out.getOrPut(st.timeInMillis) { st to mutableListOf() }.second.add(e)
    }
    return out.map { (k, v) -> HistoryGroup(k.toString(), title(v.first), v.second) }
}

/** 分组标题：与书签页主分组标题同款（17sp 加粗 + 条数 + 36dp 圆形箭头按钮），文字用蓝色。 */
@Composable
private fun HistoryGroupHeader(title: String, count: Int, open: Boolean, onClick: () -> Unit) {
    val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 270f else 180f, label = "historyGroupArrow")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$count",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        AppSmallButton(onClick = onClick, size = 36.dp, inPage = true) {
            Icon(
                imageVector = MiuixIcons.Glass.ChevronBackward,
                contentDescription = if (open) tr("收起") else tr("展开"),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
            )
        }
    }
}

/** 条目右侧时间：按日分组时只显示时分（日期已在标题里），按周 / 月显示月日 + 时分。 */
private fun formatHistoryTime(time: Long, mode: HistoryGroupMode): String {
    val pattern = if (mode == HistoryGroupMode.Day) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
}

/** 写入系统 Download/mafari 并提示路径。 */
/** 书签文件解码：处理 UTF-8 / UTF-16 的 BOM，避免 UTF-16 文件被当 UTF-8 读成乱码而识别不到。 */
private fun decodeBookmarkText(b: ByteArray): String = when {
    b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
    b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
    b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
        String(b, 3, b.size - 3, Charsets.UTF_8)
    else -> b.decodeToString()
}

private fun exportBookmarks(context: android.content.Context, html: String) {
    runCatching {
        val name = "mafari-bookmarks-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".html"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/html")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/mafari")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error(tr("无法创建文件"))
        resolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Toast.makeText(context, tr("已导出到 Download/mafari/{0}", name), Toast.LENGTH_LONG).show()
    }.onFailure {
        Toast.makeText(context, tr("导出失败：{0}", it.message), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 分组选择行：一行显示当前分组路径，点开后弹窗右下角紧贴右侧文字、向左上弹出 [GroupChoosePopup]（与首页书签分组弹窗同一套面板）。
 * 右侧文字的窗口坐标通过 [onAnchor] 交给外层换算成弹窗宿主 Box 的坐标。
 */
@Composable
private fun GroupSelectRow(
    current: String,
    open: Boolean,
    onClick: () -> Unit,
    onAnchor: (Rect) -> Unit,
) {
    val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 270f else 180f, label = "groupSelectArrow")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleClip(12.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tr("分组"),
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = current.replace("/", " › "),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            // 弹窗的右下角锚在这段文字上：取它自己的窗口坐标（右端 = 文字右端，上沿 = 文字上沿）
            modifier = Modifier.weight(1f).onGloballyPositioned { onAnchor(it.boundsInWindow()) },
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = MiuixIcons.Glass.ChevronBackward,
            contentDescription = if (open) tr("收起") else tr("展开"),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
        )
    }
}

/**
 * 「同层置顶」的勾选圆。自绘而不用 miuix 的 Checkbox：后者的底色是半透明的，在柔光 / 液态玻璃的弹窗里会和背景糊在一起看不见。
 * 这里用不透明的底 + 实线描边，未勾选是空心圆，勾选是主色实心圆 + 白色对勾，任何背景下都看得清。
 */
@Composable
private fun PinCheckCircle(checked: Boolean) {
    val primary = MiuixTheme.colorScheme.primary
    val ring = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val tick = MiuixTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (checked) primary else MiuixTheme.colorScheme.surface)
            .border(2.dp, if (checked) primary else ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(24.dp)) {
                val w = size.width
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.28f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.67f)
                    lineTo(w * 0.73f, w * 0.35f)
                }
                drawPath(
                    path = path,
                    color = tick,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.2.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun FavoriteEditorDialog(
    show: Boolean,
    initial: Favorite?,
    groups: List<String>,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    liquidBackdrop: com.kyant.backdrop.Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, group: String, pinned: Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
    /** 新建时预填的名称 / 网址（initial 为空才生效） */
    prefillName: String = "",
    prefillUrl: String = "",
) {
    var name by remember(initial, show) { mutableStateOf(initial?.name ?: prefillName) }
    var url by remember(initial, show) { mutableStateOf(initial?.url ?: prefillUrl) }
    // 分组默认选中已有的：编辑时是它当前的组，新建时是「个人收藏」；也可以在下面输入框里写新名字
    var group by remember(initial, show) { mutableStateOf(initial?.group ?: Favorite.GROUP_PERSONAL) }
    // 输入框自己的文本：不能由 group 反推，否则打到与已有分组同名（如 a/b/c）时输入框会被清空，没法继续往后写 /d
    var typedGroup by remember(initial, show) { mutableStateOf("") }
    // 「同层置顶」：编辑时沿用书签当前的置顶状态
    var pinned by remember(initial, show) { mutableStateOf(initial?.pinned ?: false) }
    // 分组弹窗：是否打开、分组行的窗口坐标、宿主 Box 的窗口位置与尺寸（弹窗在宿主 Box 的坐标里定位）
    var groupPopup by remember(initial, show) { mutableStateOf(false) }
    var groupAnchor by remember { mutableStateOf(Rect.Zero) }
    var hostPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var hostWidth by remember { mutableStateOf(0) }
    AppGlassDialog(
        show = show,
        title = if (initial == null) tr("新建书签") else tr("编辑书签"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        // 退到后台时收起键盘并清掉输入焦点：回前台时键盘不再和 Surface 重建同时恢复（输入内容保留）
        val stopFocus = androidx.compose.ui.platform.LocalFocusManager.current
        val stopKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val stopContext = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.DisposableEffect(stopContext) {
            val lifecycle = (stopContext.findActivity() as? androidx.lifecycle.LifecycleOwner)?.lifecycle
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                    runCatching { stopFocus.clearFocus(force = true) }
                    runCatching { stopKeyboard?.hide() }
                }
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }
        // 宿主 Box：GlassPopup 把它的父 Box 当整个页面，所以弹窗叠在表单上、被限制在对话框内部
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    hostPos = it.positionInWindow()
                    hostWidth = it.size.width
                },
        ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 「名称」「网址」的标题放在输入框外面（左侧），字号 / 颜色与下面的「分组」行一致
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("名称"),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                top.yukonga.miuix.kmp.basic.TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("网址"),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                top.yukonga.miuix.kmp.basic.TextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(10.dp))
            // 分组按 / 分层：一行显示当前分组，点开在正下方弹出分组弹窗，可逐层选到最内层
            val current = group.trim().ifBlank { Favorite.GROUP_PERSONAL }
            val chipHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            GroupSelectRow(
                current = current,
                open = groupPopup,
                onClick = {
                    chipHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    groupPopup = !groupPopup
                },
                onAnchor = { groupAnchor = it },
            )
            Spacer(Modifier.height(10.dp))
            top.yukonga.miuix.kmp.basic.TextField(
                value = typedGroup,
                onValueChange = {
                    typedGroup = it
                    group = it
                    groupPopup = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = tr("或输入/新建分组路径(以/分层)"),
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            // 同层置顶：只把这条书签排到所在分组的书签最前（分组文件夹仍在书签上面）；后勾选的排在先勾选的上面
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleClip(10.dp)
                    .clickable {
                        chipHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        pinned = !pinned
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("同层置顶"),
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = tr("排到所在分组的书签最前，分组文件夹仍在书签上面"),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                PinCheckCircle(checked = pinned)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (initial != null && onDelete != null) {
                    LiquidGlassPillButton(
                        text = tr("删除"),
                        onClick = onDelete,
                        backdrop = liquidBackdrop,
                        modifier = Modifier.weight(1f),
                        role = LiquidRole.Destructive,
                    )
                } else {
                    LiquidGlassPillButton(
                        text = tr("取消"),
                        onClick = onDismiss,
                        backdrop = liquidBackdrop,
                        modifier = Modifier.weight(1f),
                    )
                }
                LiquidGlassPillButton(
                    text = tr("保存"),
                    onClick = {
                        // 规整路径：去掉各段首尾空格和多余的 /（如 "a/b/ " → "a/b"）
                        val path = group.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
                        if (url.isNotBlank()) onConfirm(name, url, path, pinned)
                    },
                    backdrop = liquidBackdrop,
                    modifier = Modifier.weight(1f),
                    role = LiquidRole.Primary,
                )
            }
        }
        // 分组弹窗放进独立的 Popup 窗口：对话框的内容会被对话框轮廓裁剪，弹窗必须脱离它，才能盖在对话框之上、不被遮挡。
        // 窗口铺满屏幕，所以锚点直接用窗口坐标、页宽取窗口宽度；退出动画期间窗口继续保留。
        var popupAlive by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(groupPopup) {
            if (groupPopup) {
                popupAlive = true
            } else {
                kotlinx.coroutines.delay(500)
                popupAlive = false
            }
        }
        if (popupAlive) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember {
                    object : androidx.compose.ui.window.PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: androidx.compose.ui.unit.IntRect,
                            windowSize: androidx.compose.ui.unit.IntSize,
                            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                            popupContentSize: androidx.compose.ui.unit.IntSize,
                        ) = androidx.compose.ui.unit.IntOffset.Zero
                    }
                },
                onDismissRequest = { groupPopup = false },
                properties = androidx.compose.ui.window.PopupProperties(
                    focusable = true,
                    clippingEnabled = false,
                    usePlatformDefaultWidth = false,
                ),
            ) {
                var windowWidth by remember { mutableStateOf(0) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { windowWidth = it.width },
                ) {
                    GroupChoosePopup(
                        show = groupPopup,
                        anchorBounds = groupAnchor,
                        pageWidthPx = windowWidth.toFloat(),
                        groups = groups,
                        current = group.trim().ifBlank { Favorite.GROUP_PERSONAL },
                        backdrop = backdrop,
                        onDismiss = { groupPopup = false },
                        onSelect = {
                            group = it
                            typedGroup = ""
                            groupPopup = false
                        },
                    )
                }
            }
        }
        }
    }
}
