/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.data.legado.LegadoRssArticle
import dev.dsh.miuixbrowser.data.legado.LegadoRssSource
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Legado 订阅源（RSS / JSON / 网页）的浏览状态：[loadMore] 从上一页的 nextPage 继续，
 * 按文章地址去重；勾选状态跨翻页保留，换订阅源时清空。
 */
@Stable
internal class LegadoRssState {
    var sourceIdx by mutableStateOf(0)
        private set
    var items by mutableStateOf<List<LegadoRssArticle>>(emptyList())
        private set
    var chosen by mutableStateOf<Map<String, LegadoRssArticle>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var exhausted by mutableStateOf(false)
        private set
    var touched by mutableStateOf(false)
        private set
    var detail by mutableStateOf<LegadoRssArticle?>(null)

    private var nextUrl: String? = null
    private var gen = 0

    fun pickSource(i: Int) {
        if (i == sourceIdx) return
        sourceIdx = i
        reset()
        chosen = emptyMap()
    }

    private fun reset() {
        gen++
        items = emptyList()
        loading = false
        error = null
        exhausted = false
        touched = false
        nextUrl = null
    }

    fun toggle(article: LegadoRssArticle) {
        chosen = if (article.link in chosen) chosen - article.link else chosen + (article.link to article)
    }

    fun clearChosen() {
        chosen = emptyMap()
    }

    fun showDetail(a: LegadoRssArticle?) {
        detail = a
    }

    fun loadMore(sources: List<LegadoRssSource>, scope: CoroutineScope) {
        if (loading) return
        val src = sources.getOrNull(sourceIdx) ?: return
        if (touched && nextUrl == null) {
            exhausted = true
            return
        }
        val my = gen
        val pageUrl = nextUrl
        loading = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { src.loadPage(pageUrl) } }
            if (my != gen) return@launch
            loading = false
            r.onSuccess { page ->
                touched = true
                val seen = items.mapTo(HashSet()) { it.link }
                val fresh = page.articles.filter { it.link !in seen }
                items = items + fresh
                nextUrl = page.nextUrl
                exhausted = page.nextUrl == null
                if (fresh.isEmpty() && items.isEmpty()) error = tr("这个订阅源没有读到文章")
            }.onFailure {
                error = it.message ?: tr("读取失败，检查网络或订阅源是否失效")
                exhausted = false
            }
        }
    }
}

/**
 * Legado 订阅源文章列表：选订阅源 → 勾选文章 → 「导入」逐篇取正文并作为单章书入架。
 */
@Composable
internal fun LegadoRssDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    sources: List<LegadoRssSource>,
    shelfKeys: Set<Pair<String, String>>,
    initialSourceUrl: String? = null,
    onDismiss: () -> Unit,
    onImport: (LegadoRssSource, List<LegadoRssArticle>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { LegadoRssState() }
    LaunchedEffect(show, initialSourceUrl) {
        if (!show) return@LaunchedEffect
        val idx = if (initialSourceUrl == null) 0 else sources.indexOfFirst { it.url == initialSourceUrl }
        if (idx >= 0) state.pickSource(idx)
    }
    val src = sources.getOrNull(state.sourceIdx)

    // 打开弹窗 / 换订阅源后自动读第一页
    LaunchedEffect(show, src?.url) {
        if (show && src != null) state.loadMore(sources, scope)
    }

    AppGlassDialog(
        show = show,
        title = tr("Legado 订阅源"),
        summary = src?.name,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (src == null) {
                Text(
                    text = tr("还没有 Legado 订阅源：先在「订阅源」里导入（sourceUrl + ruleArticles）"),
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
                            CategoryChip(text = rssShortName(s.name), selected = i == state.sourceIdx) { state.pickSource(i) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                val list = state.items
                if (list.isEmpty()) {
                    val hint = when {
                        state.loading -> tr("加载中…")
                        state.error != null -> state.error.orEmpty()
                        state.touched -> tr("没有读到文章")
                        else -> tr("点「刷新」读取文章")
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = hint, fontSize = 14.sp, textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (state.error != null && !state.loading) {
                                AppTextButton(text = tr("重试"), onClick = { state.loadMore(sources, scope) })
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(56.dp * minOf(list.size + 1, 5))) {
                        items(list, key = { it.link }) { a ->
                            FeedRow(
                                title = a.title,
                                author = listOfNotNull(
                                    a.author.ifBlank { null },
                                    a.pubDate.ifBlank { null },
                                    a.description?.takeIf { it.isNotBlank() }?.take(40),
                                ).joinToString(" · "),
                                checked = a.link in state.chosen,
                                onShelf = (a.title.trim() to (a.author.ifBlank { src.name }).trim()) in shelfKeys,
                                onClick = { state.toggle(a) },
                                onLongClick = { state.showDetail(a) },
                            )
                        }
                        item(key = "rss-footer") {
                            Box(modifier = Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                                when {
                                    state.loading -> Text(tr("加载中…"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(state.error.orEmpty(), fontSize = 12.sp, textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        AppTextButton(text = tr("重试"), onClick = { state.loadMore(sources, scope) })
                                    }
                                    !state.exhausted -> AppTextButton(text = tr("加载更多"), onClick = { state.loadMore(sources, scope) })
                                    else -> Text(tr("没有更多了"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
                            if (state.chosen.isEmpty()) {
                                Toast.makeText(context, tr("请先勾选要导入的文章"), Toast.LENGTH_SHORT).show()
                            } else {
                                val picks = state.chosen.values.toList()
                                state.clearChosen()
                                onImport(src, picks)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Primary,
                    ) { Text(text = tr("导入 {0} 篇", state.chosen.size), style = MiuixTheme.textStyles.button) }
                }
            }
        }
    }

    val detail = state.detail
    AppGlassDialog(
        show = detail != null,
        title = detail?.title,
        summary = detail?.let { listOfNotNull(it.author.ifBlank { null }, it.pubDate.ifBlank { null }).joinToString(" · ") },
        backdrop = backdrop,
        onDismissRequest = { state.showDetail(null) },
    ) {
        if (detail != null) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    text = detail.description?.takeIf { it.isNotBlank() } ?: tr("（这篇文章没有摘要）"),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
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
                        Text(if (detail.link in state.chosen) tr("取消勾选") else tr("勾选这篇"), style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}

private fun rssShortName(name: String): String = if (name.length > 14) name.take(13) + "…" else name
