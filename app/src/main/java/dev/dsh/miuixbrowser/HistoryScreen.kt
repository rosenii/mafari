package dev.dsh.miuixbrowser

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
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
    Day("清除最近 1 天", 24L * 60 * 60 * 1000),
    Week("清除最近一周", 7L * 24 * 60 * 60 * 1000),
    Month("清除最近一月", 30L * 24 * 60 * 60 * 1000),
    All("全部清除", null),
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
                ?: error("无法读取所选文件")
            val result = favorites.importBookmarks(decodeBookmarkText(bytes))
            val msg = when {
                result.added > 0 -> "已导入 ${result.added} 条书签"
                result.found > 0 -> "识别到 ${result.found} 条书签，均已存在，无需重复导入"
                else -> "未识别到书签（文件 ${bytes.size} 字节；支持 Chrome / Edge / Firefox 导出的 HTML 或 JSON）"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = if (pagerState.currentPage == TAB_BOOKMARKS) "书签" else "历史",
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
                            contentDescription = "搜索",
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
                                label = if (pagerState.currentPage == TAB_BOOKMARKS) "搜索书签" else "搜索历史",
                                singleLine = true,
                            )
                        }
                        GlassTabRow(
                            tabs = listOf("书签", "历史"),
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
            scope = "书签：加密套件加密；历史：SQLCipher 整库 AES-256",
        )
        val topInset = innerPadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .background(MiuixTheme.colorScheme.surface),
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
                            contentDescription = "书签操作",
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
                        text = "新建书签",
                        onClick = {
                            showMenu = false
                            creatingFav = true
                        },
                        icon = MiuixIcons.Glass.Add,
                    )
                    GlassPopupItem(
                        text = "导入",
                        summary = "Chrome / Edge / Firefox 书签（HTML / JSON）",
                        onClick = {
                            showMenu = false
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        icon = MiuixIcons.Glass.Download,
                    )
                    GlassPopupItem(
                        text = "导出",
                        summary = "保存到 Download/mafari",
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
                            contentDescription = "清空历史",
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
            title = "清除历史记录",
            backdrop = backdrop,
            onDismissRequest = { showClearConfirm = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (confirmCount > 0) {
                        "将删除${clearRange.label.removePrefix("清除")}共 $confirmCount 条历史记录，此操作无法撤销。"
                    } else {
                        "${clearRange.label.removePrefix("清除")}没有可删除的历史记录。"
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
                        text = "取消",
                        onClick = { showClearConfirm = false },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                    )
                    LiquidGlassPillButton(
                        text = "确认删除",
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
            title = "编辑分组",
            backdrop = backdrop,
            onDismissRequest = { renamingGroup = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "分组路径（用 / 分层）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LiquidGlassPillButton(
                        text = "删除",
                        onClick = {
                            deletingGroup = groupToRename
                            renamingGroup = null
                        },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                        role = LiquidRole.Destructive,
                    )
                    LiquidGlassPillButton(
                        text = "保存",
                        onClick = {
                            val old = groupToRename
                            if (old != null) {
                                if (renameText.isBlank()) {
                                    Toast.makeText(context, "分组名不能为空", Toast.LENGTH_SHORT).show()
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
            title = "删除分组",
            backdrop = backdrop,
            onDismissRequest = { deletingGroup = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "将删除分组「${groupToDelete.orEmpty()}」（含子分组）及其中的 ${groupToDelete?.let { favorites.itemsInTree(it).size } ?: 0} 个书签，此操作无法撤销。",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LiquidGlassPillButton(
                        text = "取消",
                        onClick = { deletingGroup = null },
                        backdrop = liquid,
                        modifier = Modifier.weight(1f),
                    )
                    LiquidGlassPillButton(
                        text = "删除",
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
            onConfirm = { name, url, group ->
                if (editingFav != null) {
                    val idx = favorites.items.indexOfFirst {
                        it.url == editingFav!!.url && it.name == editingFav!!.name
                    }
                    if (idx >= 0) {
                        favorites.update(idx, name, url, group)
                    } else {
                        favorites.add(name, url, group)
                    }
                } else {
                    favorites.add(name, url, group)
                }
                creatingFav = false
                editingFav = null
            },
        )
    }
    }
}

/** 书签树节点：[path] 是完整分组路径（以 / 分层）；[items] 是直接放在该文件夹里的书签。 */
private class BmNode(val path: String) {
    val name: String = path.substringAfterLast('/').ifBlank { "未命名" }
    val children = ArrayList<BmNode>()
    val items = ArrayList<Favorite>()
    /** 含全部子孙文件夹的（已按搜索词过滤的）书签总数 */
    var total = 0
}

/** 按分组路径里的 / 建出层级；文件夹顺序沿用 [FavoritesStore.groups]，缺失的祖先自动补齐。 */
private fun buildBookmarkTree(favorites: FavoritesStore, q: String): List<BmNode> {
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
    return roots
}

@Composable
private fun BookmarksPage(
    favorites: FavoritesStore,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    onOpen: (String) -> Unit,
    onEdit: (Favorite) -> Unit,
    onGroupLongClick: (String) -> Unit,
    query: String = "",
) {
    val q = query.trim()
    val roots = buildBookmarkTree(favorites, q)
    val groupHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val groupTick = { groupHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    // 顶层分组默认折叠（只有一个时默认展开），次级文件夹默认折叠；搜索时全部自动展开
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val onlyOneGroup = roots.count { it.total > 0 } <= 1
    val noMatch = q.isNotEmpty() && roots.none { it.total > 0 }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .edgeHaptic()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
    ) {
        if (favorites.items.isEmpty()) {
            item {
                Text(
                    text = "还没有书签。点右下角按钮新建，或从 Chrome / Firefox 导入。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        if (noMatch) {
            item {
                Text(
                    text = "没有匹配的书签",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        roots.forEach { root ->
            // 「个人收藏」即使为空也保留标题（不能删除的保留分组）；其它空分组 / 搜索无匹配时不显示
            if (root.total == 0 && (q.isNotEmpty() || !favorites.isProtectedGroup(root.path))) return@forEach
            val open = q.isNotEmpty() || (expanded[root.path] ?: onlyOneGroup)
            item(key = "g-${root.path}") {
                BookmarkGroupHeader(
                    name = root.name,
                    count = root.total,
                    open = open,
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
                )
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
            bookmarkFolderContent(child, level + 1, searching, expanded, onOpen, onEdit, onGroupLongClick, onTick)
        }
    }
    items(node.items.size, key = { i -> "b-${node.path}-${node.items[i].url}-$i" }) { i ->
        BookmarkRow(fav = node.items[i], level = level, onOpen = onOpen, onEdit = onEdit)
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
                    .clip(RoundedCornerShape(10.dp))
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
                        if (folderCount > 0) "$folderCount 个分组" else null,
                        "$bookmarkCount 个书签",
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
                    contentDescription = if (open) "收起" else "展开",
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
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
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
                text = "编辑",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onEdit(fav) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** 书签分组标题：左侧分组名 + 数量，右侧按钮点一下展开 / 收起（整行也可点击；长按管理分组）。 */
@Composable
private fun BookmarkGroupHeader(
    name: String,
    count: Int,
    open: Boolean,
    onClick: () -> Unit,
    /** null = 不响应长按 */
    onLongClick: (() -> Unit)? = null,
) {
    // ChevronBackward 默认朝左：收起时转 180° 朝右（›），展开时转 270° 朝下
    val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 270f else 180f, label = "groupArrow")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                contentDescription = if (open) "收起" else "展开",
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
                .edgeHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentAlignment = Alignment.Center,
        ) {
            if (loaded) {
                Text(
                    text = if (q.isEmpty()) "还没有浏览记录" else "没有匹配的历史记录",
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "分组",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    HistoryGroupMode.entries.forEach { m ->
                        val selected = m == mode
                        Text(
                            text = m.label,
                            fontSize = 13.sp,
                            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                )
                                .clickable {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    if (!selected) {
                                        mode = m
                                        sp.edit().putString("history_group_mode", m.id).apply()
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .edgeHaptic()
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
                            contentDescription = "删除",
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

/** 历史页每次从数据库多读的条数 */
private const val HISTORY_PAGE = 200

enum class HistoryGroupMode(val id: String, val label: String) {
    Day("day", "按日"),
    Week("week", "按周"),
    Month("month", "按月"),
    Year("year", "按年");

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
    val md = SimpleDateFormat("M月d日", Locale.getDefault())
    val wk = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    fun title(start: java.util.Calendar): String {
        val t = start.timeInMillis
        val sameYear = start.get(java.util.Calendar.YEAR) == nowStart.get(java.util.Calendar.YEAR)
        return when (mode) {
            HistoryGroupMode.Day -> when (t) {
                cur -> "今天"
                prev -> "昨天"
                else -> md.format(Date(t)) + " " + wk[start.get(java.util.Calendar.DAY_OF_WEEK) - 1]
            }
            HistoryGroupMode.Week -> {
                val end = shift(start, 1).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
                val range = md.format(Date(t)) + " - " + md.format(end.time)
                when (t) {
                    cur -> "本周 · $range"
                    prev -> "上周 · $range"
                    else -> range
                }
            }
            HistoryGroupMode.Month -> when (t) {
                cur -> "本月"
                prev -> "上月"
                else -> if (sameYear) "${start.get(java.util.Calendar.MONTH) + 1}月"
                else "${start.get(java.util.Calendar.YEAR)}年${start.get(java.util.Calendar.MONTH) + 1}月"
            }
            HistoryGroupMode.Year -> when (t) {
                cur -> "今年"
                prev -> "去年"
                else -> "${start.get(java.util.Calendar.YEAR)}年"
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
                contentDescription = if (open) "收起" else "展开",
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
            ?: error("无法创建文件")
        resolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Toast.makeText(context, "已导出到 Download/mafari/$name", Toast.LENGTH_LONG).show()
    }.onFailure {
        Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 分组路径（以 / 分层，缺失的祖先自动补齐）→ 它的直接子分组完整路径；顶层用 "" 作键，顺序沿用传入顺序。 */
private fun groupChildren(groups: List<String>): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    val seen = HashSet<String>()
    fun ensure(p: String) {
        if (p.isEmpty() || !seen.add(p)) return
        val parent = if (p.contains('/')) p.substringBeforeLast('/') else ""
        ensure(parent)
        out.getOrPut(parent) { mutableListOf() }.add(p)
    }
    groups.forEach { ensure(it) }
    return out
}

/**
 * 分组选择：一行显示当前路径，点开后在同一个面板里按层级展开分组树，
 * 点名字选中（有子分组时顺带展开继续往下选），点右侧箭头只展开 / 收起，可以一直选到最内层。
 */
@Composable
private fun GroupTreePicker(
    childMap: Map<String, List<String>>,
    current: String,
    onSelect: (String) -> Unit,
    /** 每变一次（在下方输入框打字时）就收起整个选择面板和所有展开的分组 */
    collapseToken: Int = 0,
) {
    var open by remember { mutableStateOf(false) }
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    androidx.compose.runtime.LaunchedEffect(collapseToken) {
        if (collapseToken > 0) {
            open = false
            expanded.clear()
        }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
    // 手风琴：只展开 [path] 及其各级祖先，其余分组（含同级、其子孙）全部收起
    fun expandOnly(path: String) {
        expanded.clear()
        var cur = path
        expanded[cur] = true
        while (cur.contains('/')) {
            cur = cur.substringBeforeLast('/')
            expanded[cur] = true
        }
    }
    val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 270f else 180f, label = "groupPickerArrow")
    val line = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    tick()
                    if (!open) {
                        // 打开时只展开当前选中项的各级祖先，直接看到它
                        expanded.clear()
                        var cur = current
                        while (cur.contains('/')) {
                            cur = cur.substringBeforeLast('/')
                            expanded[cur] = true
                        }
                    }
                    open = !open
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "分组",
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
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = MiuixIcons.Glass.ChevronBackward,
                contentDescription = if (open) "收起" else "展开",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
            )
        }
        if (open) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
            val rows = buildList {
                fun walk(parent: String, depth: Int) {
                    childMap[parent].orEmpty().forEach { path ->
                        add(path to depth)
                        if (expanded[path] == true) walk(path, depth + 1)
                    }
                }
                walk("", 0)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val guide = MiuixTheme.colorScheme.primary.copy(alpha = 0.30f)
                val levelTint = MiuixTheme.colorScheme.primary
                rows.forEachIndexed { index, (path, depth) ->
                    // 并列：同为顶层的分组之间加一条细分隔线
                    if (depth == 0 && index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                    val selected = path == current
                    val hasKids = childMap[path].orEmpty().isNotEmpty()
                    val isOpen = expanded[path] == true
                    val kidAngle by androidx.compose.animation.core.animateFloatAsState(
                        if (isOpen) 270f else 180f, label = "groupPickerKidArrow",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 层级：越深底色越明显 + 每一级祖先各一条竖向引导线 + 连到自己的短横线
                            .drawBehind {
                                if (depth > 0) {
                                    drawRect(levelTint.copy(alpha = (0.03f * depth).coerceAtMost(0.12f)))
                                    for (d in 1..depth) {
                                        val x = ((d - 1) * 18 + 13).dp.toPx()
                                        drawLine(guide, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                                    }
                                    val x = ((depth - 1) * 18 + 13).dp.toPx()
                                    drawLine(guide, androidx.compose.ui.geometry.Offset(x, size.height / 2), androidx.compose.ui.geometry.Offset(x + 9.dp.toPx(), size.height / 2), strokeWidth = 1.5.dp.toPx())
                                }
                            }
                            .clickable {
                                tick()
                                onSelect(path)
                                if (hasKids) expandOnly(path) else open = false
                            }
                            .padding(start = (depth * 18 + 8).dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = path.substringAfterLast('/').ifBlank { "未命名" },
                            fontSize = if (depth == 0) 15.sp else 14.sp,
                            fontWeight = if (depth == 0) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        )
                        if (selected) {
                            Text(
                                text = "✓",
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        if (hasKids) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        tick()
                                        if (isOpen) expanded[path] = false else expandOnly(path)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Glass.ChevronBackward,
                                    contentDescription = if (isOpen) "收起" else "展开",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = kidAngle },
                                )
                            }
                        } else {
                            Spacer(Modifier.width(36.dp))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
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
    onConfirm: (name: String, url: String, group: String) -> Unit,
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
    var collapseToken by remember(initial, show) { mutableStateOf(0) }
    AppGlassDialog(
        show = show,
        title = if (initial == null) "新建书签" else "编辑书签",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = "名称",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            top.yukonga.miuix.kmp.basic.TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = "网址",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            // 分组按 / 分层：有层级或分组超过 3 个时用「可展开的分组树」一个菜单选到最内层；否则仍是横排标签
            val childMap = remember(groups) { groupChildren(groups) }
            val current = group.trim().ifBlank { Favorite.GROUP_PERSONAL }
            val chipHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            if (childMap.keys.any { it.isNotEmpty() } || groups.size > 3) {
                GroupTreePicker(
                    childMap = childMap,
                    current = current,
                    onSelect = {
                        group = it
                        typedGroup = ""
                    },
                    collapseToken = collapseToken,
                )
            } else {
                Text(
                    text = "分组",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                // 已有分组一点即选，免输入
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.forEach { g ->
                        val selected = g == group.trim().ifBlank { Favorite.GROUP_PERSONAL }
                        Text(
                            text = g,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                )
                                .clickable {
                                    chipHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    group = g
                                    typedGroup = ""
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            top.yukonga.miuix.kmp.basic.TextField(
                value = typedGroup,
                onValueChange = {
                    typedGroup = it
                    group = it
                    collapseToken++
                },
                modifier = Modifier.fillMaxWidth(),
                label = "或输入/新建分组路径(以/分层)",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (initial != null && onDelete != null) {
                    LiquidGlassPillButton(
                        text = "删除",
                        onClick = onDelete,
                        backdrop = liquidBackdrop,
                        modifier = Modifier.weight(1f),
                        role = LiquidRole.Destructive,
                    )
                } else {
                    LiquidGlassPillButton(
                        text = "取消",
                        onClick = onDismiss,
                        backdrop = liquidBackdrop,
                        modifier = Modifier.weight(1f),
                    )
                }
                LiquidGlassPillButton(
                    text = "保存",
                    onClick = {
                        // 规整路径：去掉各段首尾空格和多余的 /（如 "a/b/ " → "a/b"）
                        val path = group.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
                        if (url.isNotBlank()) onConfirm(name, url, path)
                    },
                    backdrop = liquidBackdrop,
                    modifier = Modifier.weight(1f),
                    role = LiquidRole.Primary,
                )
            }
        }
    }
}
