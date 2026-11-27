/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.data.FeedBook
import dev.dsh.miuixbrowser.data.legado.LegadoBook
import dev.dsh.miuixbrowser.data.legado.LegadoExploreKind
import dev.dsh.miuixbrowser.data.legado.LegadoPager
import dev.dsh.miuixbrowser.data.legado.LegadoSource
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 已导入的 Legado 书源（进程内保存，重启后需要重新导入书源文件）。 */
internal object LegadoLibrary {
    var sources by mutableStateOf<List<LegadoSource>>(emptyList())
        private set

    /** 同一个地址的书源会被新导入的覆盖。 */
    fun add(list: List<LegadoSource>) {
        val urls = list.mapTo(HashSet()) { it.url }
        sources = sources.filter { it.url !in urls } + list
    }

    fun remove(url: String) {
        sources = sources.filter { it.url != url }
    }
}

internal enum class LegadoMode { Search, Explore }

/**
 * 搜索 / 发现弹窗的状态。引擎调用都是阻塞的，放 IO 线程；每次重置结果都会让 [gen] 加一，
 * 迟到的旧请求（换了书源、改了关键字、切了分类）回来后直接丢弃。
 */
@Stable
internal class LegadoBrowseState {
    var sourceIdx by mutableStateOf(0)
        private set
    var mode by mutableStateOf(LegadoMode.Search)
        private set
    var query by mutableStateOf("")
    var kinds by mutableStateOf<List<LegadoExploreKind>>(emptyList())
        private set
    var kindIdx by mutableStateOf(0)
        private set
    var items by mutableStateOf<List<LegadoBook>>(emptyList())
        private set
    /** 已勾选的书（按 bookUrl，保持勾选顺序）；换搜索词 / 分类后仍保留，换书源时清空。 */
    var chosen by mutableStateOf<Map<String, LegadoBook>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var exhausted by mutableStateOf(true)
        private set
    /** 已经发起过搜索 / 发现（用来区分「还没搜」和「没结果」） */
    var touched by mutableStateOf(false)
        private set
    var kindsLoaded by mutableStateOf(false)
        private set

    private val pager = LegadoPager()
    private var gen = 0
    private var activeMode = LegadoMode.Search
    private var activeQuery = ""
    private var activeKind: LegadoExploreKind? = null

    private fun resetResults() {
        gen++
        pager.reset()
        items = emptyList()
        loading = false
        error = null
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

    fun toggle(b: LegadoBook) {
        chosen = if (b.bookUrl in chosen) chosen - b.bookUrl else chosen + (b.bookUrl to b)
    }

    fun clearChosen() {
        chosen = emptyMap()
    }

    fun startSearch(src: LegadoSource, scope: CoroutineScope) {
        val q = query.trim()
        if (q.isEmpty()) return
        resetResults()
        activeMode = LegadoMode.Search
        activeQuery = q
        touched = true
        loadMore(src, scope)
    }

    fun startExplore(src: LegadoSource, idx: Int, scope: CoroutineScope) {
        val kind = kinds.getOrNull(idx) ?: return
        resetResults()
        kindIdx = idx
        activeMode = LegadoMode.Explore
        activeKind = kind
        touched = true
        loadMore(src, scope)
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
    fun loadMore(src: LegadoSource, scope: CoroutineScope) {
        if (loading) return
        val my = gen
        val page = pager.nextPage
        val m = activeMode
        val q = activeQuery
        val kind = activeKind
        loading = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (m == LegadoMode.Search) src.engine.search(q, page)
                    else src.engine.explore(kind ?: throw IllegalStateException("no kind"), page)
                }
            }
            if (my != gen) return@launch
            loading = false
            r.onSuccess {
                pager.add(it)
                items = pager.items.toList()
                exhausted = pager.exhausted
            }.onFailure {
                error = it.message ?: tr("读取失败，检查网络或书源是否失效")
                // 第一页就失败时没有「加载更多」，保留重试入口
                exhausted = false
            }
        }
    }

    fun picks(src: LegadoSource): List<FeedBook> = chosen.values.map { b ->
        FeedBook(
            title = b.name,
            author = b.author,
            cover = b.coverUrl,
            url = null,
            format = null,
            chapters = emptyList(),
            chaptersUrl = null,
            legado = b,
            legadoSource = src,
        )
    }
}

/**
 * Legado 书源的「搜索 / 发现」弹窗：选书源 → 搜索关键字或浏览发现分类 → 勾选 → 交给 [onImport] 下载入架。
 * [sources] 为空时只显示提示。
 */
@Composable
internal fun LegadoBrowseDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    sources: List<LegadoSource>,
    shelfKeys: Set<Pair<String, String>>,
    onDismiss: () -> Unit,
    onRemoveSource: (LegadoSource) -> Unit,
    onImport: (List<FeedBook>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { LegadoBrowseState() }
    val src = sources.getOrNull(state.sourceIdx)
    // 书源被移除后下标可能越界：回到第一个
    LaunchedEffect(sources.size) {
        if (state.sourceIdx >= sources.size && sources.isNotEmpty()) state.pickSource(0)
    }
    val mode: LegadoMode? = when {
        src == null -> null
        state.mode == LegadoMode.Search && src.canSearch -> LegadoMode.Search
        state.mode == LegadoMode.Explore && src.canExplore -> LegadoMode.Explore
        src.canSearch -> LegadoMode.Search
        src.canExplore -> LegadoMode.Explore
        else -> null
    }
    LaunchedEffect(src?.url, mode, state.kindsLoaded) {
        if (src != null && mode == LegadoMode.Explore && !state.kindsLoaded) state.loadKinds(src, scope)
    }

    AppGlassDialog(
        show = show,
        title = tr("书源搜索 / 发现"),
        summary = src?.name,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (src == null) {
                Text(
                    text = tr("还没有书源：先用「从订阅源导入」导入 Legado 书源文件"),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                AppDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = tr("关闭"), style = MiuixTheme.textStyles.button)
                }
            } else {
                if (sources.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sources.forEachIndexed { i, s ->
                            CategoryChip(text = shortName(s.name), selected = i == state.sourceIdx) { state.pickSource(i) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        if (src.canSearch) CategoryChip(text = tr("搜索"), selected = mode == LegadoMode.Search) { state.pickMode(LegadoMode.Search) }
                        if (src.canExplore) CategoryChip(text = tr("发现"), selected = mode == LegadoMode.Explore) { state.pickMode(LegadoMode.Explore) }
                    }
                    Text(
                        text = tr("移除书源"),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clickable { onRemoveSource(src) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    )
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
                        keyboardActions = KeyboardActions(onSearch = { state.startSearch(src, scope) }),
                    )
                    LegadoMode.Explore -> if (state.kinds.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.kinds.forEachIndexed { i, k ->
                                CategoryChip(text = k.title, selected = i == state.kindIdx) { state.startExplore(src, i, scope) }
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
                    Spacer(Modifier.height(8.dp))
                    AppTextButton(
                        text = tr("搜索"),
                        onClick = { state.startSearch(src, scope) },
                        enabled = state.query.isNotBlank() && !state.loading,
                        primary = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))

                // ---- 结果列表 / 状态提示
                val list = state.items
                if (list.isEmpty()) {
                    val hint: String = when {
                        state.loading -> tr("加载中…")
                        state.error != null -> state.error.orEmpty()
                        state.touched -> tr("没有找到相关书籍")
                        mode == LegadoMode.Search -> tr("输入书名或作者，点「搜索」")
                        else -> ""
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                                        if (mode == LegadoMode.Explore && state.kinds.isEmpty()) state.loadKinds(src, scope)
                                        else state.loadMore(src, scope)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(56.dp * minOf(list.size + 1, 5))) {
                        items(list, key = { it.bookUrl }) { b ->
                            FeedRow(
                                title = b.name,
                                author = listOfNotNull(b.author.ifBlank { null }, b.lastChapter).joinToString(" · "),
                                checked = b.bookUrl in state.chosen,
                                onShelf = (b.name.trim() to b.author.trim()) in shelfKeys,
                            ) { state.toggle(b) }
                        }
                        item(key = "legado-footer") {
                            Box(modifier = Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
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
                                        AppTextButton(text = tr("重试"), onClick = { state.loadMore(src, scope) })
                                    }
                                    !state.exhausted -> AppTextButton(text = tr("加载更多"), onClick = { state.loadMore(src, scope) })
                                    else -> Text(
                                        text = tr("没有更多了"),
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(text = tr("关闭"), style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            val picks = state.picks(src)
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

/** 书源名常带 emoji、站点域名和备注，做成标签时截短。 */
private fun shortName(name: String): String = if (name.length > 14) name.take(13) + "…" else name
