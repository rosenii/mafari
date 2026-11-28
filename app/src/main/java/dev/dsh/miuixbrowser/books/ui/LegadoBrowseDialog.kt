/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.ui

import dev.dsh.miuixbrowser.ui.*

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.books.data.FeedBook
import dev.dsh.miuixbrowser.books.data.legado.LegadoBook
import dev.dsh.miuixbrowser.books.data.legado.LegadoExploreKind
import dev.dsh.miuixbrowser.books.data.legado.LegadoPager
import dev.dsh.miuixbrowser.books.data.legado.LegadoSource
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 搜索结果里的一个条目：书 + 它来自哪个书源（多书源搜索时要用）。 */
internal class BrowseItem(val book: LegadoBook, val source: LegadoSource) {
    /** 勾选 / 去重的键：同一本书在不同书源里算两条 */
    val key: String get() = source.url + "\u0000" + book.bookUrl
}

internal enum class LegadoMode { Search, Explore }

/**
 * 搜索 / 发现弹窗的状态。引擎调用都是阻塞的，放 IO 线程；每次重置结果都会让 [gen] 加一，
 * 迟到的旧请求（换了书源、改了关键字、切了分类）回来后直接丢弃。
 *
 * [sourceIdx] 为 -1 时是「全部书源」：搜索会并发问所有启用的书源并按书源 + 书籍地址去重合并。
 * 聚合模式只做搜索，不做发现（各书源的分类对不上）。
 */
@Stable
internal class LegadoBrowseState {
    /** 当前书源下标；-1 = 全部书源 */
    var sourceIdx by mutableStateOf(0)
        private set
    var mode by mutableStateOf(LegadoMode.Search)
        private set
    var query by mutableStateOf("")
    var kinds by mutableStateOf<List<LegadoExploreKind>>(emptyList())
        private set
    var kindIdx by mutableStateOf(0)
        private set
    var items by mutableStateOf<List<BrowseItem>>(emptyList())
        private set
    /** 已勾选的书（按 [BrowseItem.key]，保持勾选顺序）；换搜索词 / 分类后仍保留，换书源时清空。 */
    var chosen by mutableStateOf<Map<String, BrowseItem>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 多书源搜索时部分书源失败：有结果也把失败原因列出来，方便定位是哪个书源 / 什么错 */
    var warning by mutableStateOf<String?>(null)
        private set
    var exhausted by mutableStateOf(true)
        private set
    /** 已经发起过搜索 / 发现（用来区分「还没搜」和「没结果」） */
    var touched by mutableStateOf(false)
        private set
    var kindsLoaded by mutableStateOf(false)
        private set
    /** 长按书籍打开的详情 */
    var detail by mutableStateOf<BrowseItem?>(null)

    val allSources: Boolean get() = sourceIdx < 0

    private val pager = LegadoPager()
    private var gen = 0
    private var activeMode = LegadoMode.Search
    private var activeQuery = ""
    private var activeKind: LegadoExploreKind? = null

    // 全部书源模式的分页 / 去重（单书源仍用 [LegadoPager]）
    private val multiItems = ArrayList<BrowseItem>()
    private val multiSeen = HashSet<String>()
    private var multiPage = 1
    private var multiExhausted = false

    private fun resetResults() {
        gen++
        pager.reset()
        multiItems.clear()
        multiSeen.clear()
        multiPage = 1
        multiExhausted = false
        items = emptyList()
        loading = false
        error = null
        warning = null
        exhausted = true
        touched = false
    }

    fun pickSource(i: Int) {
        if (i == sourceIdx) return
        sourceIdx = i
        resetResults()
        chosen = emptyMap()
        kinds = emptyList()
        kindIdx = 0
        kindsLoaded = false
    }

    fun pickMode(m: LegadoMode) {
        if (m == mode) return
        mode = m
        resetResults()
    }

    fun showDetail(item: BrowseItem?) {
        detail = item
    }

    fun toggle(item: BrowseItem) {
        chosen = if (item.key in chosen) chosen - item.key else chosen + (item.key to item)
    }

    fun clearChosen() {
        chosen = emptyMap()
    }

    fun startSearch(sources: List<LegadoSource>, scope: CoroutineScope) {
        val q = query.trim()
        if (q.isEmpty()) return
        resetResults()
        activeMode = LegadoMode.Search
        activeQuery = q
        touched = true
        loadMore(sources, scope)
    }

    fun startExplore(src: LegadoSource, idx: Int, scope: CoroutineScope) {
        val kind = kinds.getOrNull(idx) ?: return
        resetResults()
        kindIdx = idx
        activeMode = LegadoMode.Explore
        activeKind = kind
        touched = true
        loadMore(listOf(src), scope)
    }

    /** 读发现入口（分类 / 榜单），读到后自动打开第一个。 */
    fun loadKinds(src: LegadoSource, scope: CoroutineScope) {
        kindsLoaded = true
        resetResults()
        val my = gen
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { src.engine.exploreKinds() } }
            if (my != gen) return@launch
            loading = false
            r.onSuccess { list ->
                kinds = list
                if (list.isEmpty()) error = tr("这个书源没有发现入口") else startExplore(src, 0, scope)
            }.onFailure { error = it.message ?: tr("读取失败，检查网络或书源是否失效") }
        }
    }

    /** 读下一页（第一页也走这里）；失败后再调一次就是重试同一页。 */
    fun loadMore(sources: List<LegadoSource>, scope: CoroutineScope) {
        if (loading) return
        val my = gen
        val m = activeMode
        val q = activeQuery
        val kind = activeKind
        val targets = if (allSources) {
            if (m == LegadoMode.Explore) emptyList() else sources.filter { it.canSearch }
        } else {
            listOfNotNull(sources.getOrNull(sourceIdx))
        }
        if (targets.isEmpty()) {
            error = tr("没有可用的书源")
            exhausted = false
            return
        }
        val pageNo = if (allSources) multiPage else pager.nextPage
        loading = true
        error = null
        warning = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (allSources) {
                    // 并发问所有书源；个别书源失败不影响其它书源的结果
                    coroutineScope {
                        targets.map { s ->
                            async { s to runCatching { s.engine.search(q, pageNo) } }
                        }.awaitAll()
                    }
                } else {
                    val src = targets.first()
                    listOf(
                        src to runCatching {
                            if (m == LegadoMode.Search) src.engine.search(q, pageNo)
                            else src.engine.explore(kind ?: throw IllegalStateException("no kind"), pageNo)
                        },
                    )
                }
            }
            if (my != gen) return@launch
            loading = false
            val failures = r.filter { it.second.isFailure }
            if (failures.isNotEmpty()) {
                warning = failures.joinToString("\n") { (s, res) ->
                    val msg = res.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                        ?: res.exceptionOrNull()?.javaClass?.simpleName.orEmpty()
                    tr("{0}：{1}", s.name, msg)
                }
            }
            if (allSources) {
                val newItems = ArrayList<BrowseItem>()
                for ((src, res) in r) {
                    for (b in res.getOrNull().orEmpty()) {
                        if (multiSeen.add(src.url + "\u0000" + b.bookUrl)) newItems.add(BrowseItem(b, src))
                    }
                }
                if (newItems.isEmpty()) {
                    multiExhausted = true
                    if (failures.isNotEmpty() && multiItems.isEmpty()) {
                        error = warning ?: tr("读取失败，检查网络或书源是否失效")
                    }
                }
                multiItems.addAll(newItems)
                items = multiItems.toList()
                exhausted = multiExhausted
                if (newItems.isNotEmpty()) multiPage++
            } else {
                val res = r.first().second
                res.onSuccess {
                    pager.add(it)
                    val src = targets.first()
                    items = pager.items.map { b -> BrowseItem(b, src) }
                    exhausted = pager.exhausted
                }.onFailure {
                    error = it.message ?: tr("读取失败，检查网络或书源是否失效")
                    // 第一页就失败时没有「加载更多」，保留重试入口
                    exhausted = false
                }
            }
        }
    }

    fun picks(): List<FeedBook> = chosen.values.map { item ->
        FeedBook(
            title = item.book.name,
            author = item.book.author,
            cover = item.book.coverUrl,
            url = null,
            format = null,
            chapters = emptyList(),
            chaptersUrl = null,
            legado = item.book,
            legadoSource = item.source,
        )
    }
}

/**
 * Legado 书源的「搜索 / 发现」弹窗：选书源（或全部书源）→ 搜索关键字 / 浏览发现分类 → 勾选 → 交给 [onImport] 下载入架。
 * [sources] 为空时只显示提示。长按一条结果可以看详情（简介 / 分类 / 最新章节）。
 */
@Composable
internal fun LegadoBrowseDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    sources: List<LegadoSource>,
    shelfKeys: Set<Pair<String, String>>,
    /** 打开时默认选中的书源（地址）；为空或找不到时保持原来的选择 */
    initialSourceUrl: String? = null,
    onDismiss: () -> Unit,
    onImport: (List<FeedBook>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { LegadoBrowseState() }
    LaunchedEffect(show, initialSourceUrl) {
        if (!show) return@LaunchedEffect
        val idx = if (initialSourceUrl == null) 0 else sources.indexOfFirst { it.url == initialSourceUrl }
        if (idx >= 0) state.pickSource(idx)
    }
    val src = sources.getOrNull(state.sourceIdx)
    // 书源被移除后下标可能越界：回到第一个
    LaunchedEffect(sources.size, state.allSources) {
        if (sources.isEmpty()) state.pickSource(0)
        else if (state.sourceIdx >= sources.size && !state.allSources) state.pickSource(0)
    }
    val mode: LegadoMode? = when {
        state.allSources -> if (sources.any { it.canSearch }) LegadoMode.Search else null
        src == null -> null
        state.mode == LegadoMode.Search && src.canSearch -> LegadoMode.Search
        state.mode == LegadoMode.Explore && src.canExplore -> LegadoMode.Explore
        src.canSearch -> LegadoMode.Search
        src.canExplore -> LegadoMode.Explore
        else -> null
    }
    LaunchedEffect(src?.url, mode, state.kindsLoaded, state.allSources) {
        if (!state.allSources && src != null && mode == LegadoMode.Explore && !state.kindsLoaded) state.loadKinds(src, scope)
    }

    val srcNow = src
    AppBottomSheet(
        show = show,
        title = tr("书源搜索 / 发现"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        scrollContent = true,
    ) {
        val insets = LocalBottomSheetInsets.current
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val cap = (screenH * 0.86f).coerceAtLeast(280.dp)
        val rows = state.items.size.coerceAtMost(6)
        val desired = (370.dp + 56.dp * rows).coerceAtMost(cap)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(desired),
            contentPadding = PaddingValues(top = insets.top + 4.dp, bottom = insets.bottom + 12.dp),
        ) {
            item(key = "browse-head") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (state.allSources) tr("全部书源（{0} 个）", sources.count { it.canSearch }) else srcNow?.name.orEmpty(),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (sources.isEmpty()) {
                        Text(
                            text = tr("还没有书源：先在「订阅源」里添加 Legado 书源"),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                        AppDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text(text = tr("关闭"), style = MiuixTheme.textStyles.button)
                        }
                    } else {
                        if (sources.size > 1) {
                            if (sources.size > 6) {
                                // 书源多时横向滑动找不到头，改用抽象层的下拉菜单
                                val items = remember(sources) { listOf(tr("全部")) + sources.map { it.name } }
                                AppDropdownPreference(
                                    onSelectedIndexChange = { i -> state.pickSource(i - 1) },
                                    items = items,
                                    selectedIndex = if (state.allSources) 0 else state.sourceIdx + 1,
                                    title = tr("书源"),
                                    summary = tr("共 {0} 个", sources.size),
                                    backdrop = backdrop,
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CategoryChip(text = tr("全部"), selected = state.allSources) { state.pickSource(-1) }
                                    sources.forEachIndexed { i, s ->
                                        CategoryChip(text = shortName(s.name), selected = i == state.sourceIdx) { state.pickSource(i) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                if (state.allSources) {
                                    Text(
                                        text = tr("在 {0} 个书源里搜索", sources.count { it.canSearch }),
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                } else if (srcNow != null) {
                                    if (srcNow.canSearch) CategoryChip(text = tr("搜索"), selected = mode == LegadoMode.Search) { state.pickMode(LegadoMode.Search) }
                                    if (srcNow.canExplore) CategoryChip(text = tr("发现"), selected = mode == LegadoMode.Explore) { state.pickMode(LegadoMode.Explore) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        when (mode) {
                            LegadoMode.Search -> TextField(
                                value = state.query,
                                onValueChange = { state.query = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = tr("搜索书名或作者"),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { state.startSearch(sources, scope) }),
                            )
                            LegadoMode.Explore -> if (state.kinds.isNotEmpty()) {
                                if (state.kinds.size > 5) {
                                    // 分类多时不再横向滑动，改成下拉菜单
                                    AppDropdownPreference(
                                        onSelectedIndexChange = { i -> srcNow?.let { state.startExplore(it, i, scope) } },
                                        items = state.kinds.map { it.title },
                                        selectedIndex = state.kindIdx.coerceIn(0, state.kinds.lastIndex),
                                        title = tr("分类"),
                                        summary = tr("共 {0} 个", state.kinds.size),
                                        backdrop = backdrop,
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        state.kinds.forEachIndexed { i, k ->
                                            CategoryChip(text = k.title, selected = i == state.kindIdx) { srcNow?.let { state.startExplore(it, i, scope) } }
                                        }
                                    }
                                }
                            }
                            null -> Text(
                                text = tr("这个书源既没有搜索规则，也没有发现入口"),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (mode == LegadoMode.Search) {
                            Spacer(Modifier.height(6.dp))
                            AppTextButton(
                                text = tr("搜索"),
                                onClick = { state.startSearch(sources, scope) },
                                enabled = state.query.isNotBlank() && !state.loading,
                                primary = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            if (sources.isNotEmpty()) {
                if (state.items.isEmpty()) {
                    item(key = "browse-hint") {
                        Box(modifier = Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val hint: String = when {
                                    state.loading -> tr("加载中…")
                                    state.error != null -> state.error.orEmpty()
                                    state.touched -> tr("没有找到相关书籍")
                                    mode == LegadoMode.Search -> tr("输入书名或作者，点「搜索」")
                                    else -> ""
                                }
                                Text(
                                    text = hint,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                if (state.error != null && !state.loading) {
                                    AppTextButton(
                                        text = tr("重试"),
                                        onClick = {
                                            if (!state.allSources && mode == LegadoMode.Explore && state.kinds.isEmpty() && srcNow != null) {
                                                state.loadKinds(srcNow, scope)
                                            } else {
                                                state.loadMore(sources, scope)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(state.items, key = { it.key }) { item ->
                        FeedRow(
                            title = item.book.name,
                            author = listOfNotNull(
                                item.book.author.ifBlank { null },
                                item.book.lastChapter,
                                if (state.allSources) shortName(item.source.name) else null,
                            ).joinToString(" · "),
                            checked = item.key in state.chosen,
                            onShelf = (item.book.name.trim() to item.book.author.trim()) in shelfKeys,
                            onClick = { state.toggle(item) },
                            onLongClick = { state.showDetail(item) },
                        )
                    }
                    item(key = "browse-footer") {
                        Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.loading -> Text(
                                    text = tr("加载中…"),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = state.error.orEmpty(),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    AppTextButton(text = tr("重试"), onClick = { state.loadMore(sources, scope) })
                                }
                                !state.exhausted -> AppTextButton(text = tr("加载更多"), onClick = { state.loadMore(sources, scope) })
                                else -> Text(
                                    text = tr("没有更多了"),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }

                if (state.items.isNotEmpty() && state.warning != null) {
                    item(key = "browse-warning") {
                        Text(
                            text = tr("部分书源失败：{0}", state.warning.orEmpty()),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 3,
                        )
                    }
                }
                if (state.items.isNotEmpty()) {
                    item(key = "browse-longpress") {
                        Column {
                            Text(
                                text = tr("长按结果可看简介、分类和最新章节"),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                item(key = "browse-actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text(text = tr("关闭"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val picks = state.picks()
                                if (picks.isEmpty()) {
                                    Toast.makeText(context, tr("请先勾选要导入的书"), Toast.LENGTH_SHORT).show()
                                } else {
                                    state.clearChosen()
                                    onImport(picks)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Primary,
                        ) { Text(text = tr("导入 {0} 本", state.chosen.size), style = MiuixTheme.textStyles.button) }
                    }
                }
            }
        }
    }

    // ---- 书籍详情（长按结果）：简介 / 分类 / 最新章节 / 来源书源
    val detail = state.detail
    AppGlassDialog(
        show = detail != null,
        title = detail?.book?.name,
        summary = detail?.let { listOfNotNull(it.book.author.ifBlank { null }, it.source.name).joinToString(" · ") },
        backdrop = backdrop,
        onDismissRequest = { state.showDetail(null) },
    ) {
        if (detail != null) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val kind = detail.book.kind
                val last = detail.book.lastChapter
                if (!kind.isNullOrBlank()) {
                    Text(text = tr("分类：{0}", kind), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(6.dp))
                }
                if (!last.isNullOrBlank()) {
                    Text(text = tr("最新章节：{0}", last), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = detail.book.intro?.takeIf { it.isNotBlank() } ?: tr("（这本书没有简介）"),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppDialogButton(onClick = { state.showDetail(null) }, modifier = Modifier.weight(1f)) {
                        Text(text = tr("关闭"), style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            state.toggle(detail)
                            state.showDetail(null)
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Primary,
                    ) {
                        Text(
                            text = if (detail.key in state.chosen) tr("取消勾选") else tr("勾选这本书"),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                }
            }
        }
    }
}

/** 书源名常带 emoji、站点域名和备注，做成标签时截短。 */
private fun shortName(name: String): String = if (name.length > 14) name.take(13) + "…" else name
