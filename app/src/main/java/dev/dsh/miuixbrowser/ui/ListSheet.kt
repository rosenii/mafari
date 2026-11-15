package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「摘要一行 → 点开底部 sheet（顶部搜索框 + 列表，每次只画 [PAGE] 行，点底部「更多」再加载）」的状态。
 * 放在页面层 remember：sheet 因为弹出详情对话框而暂时收起、再回来时，搜索词和已加载的行数都还在。
 */
@Stable
class ListSheetState {
    var query by mutableStateOf("")
    var limit by mutableIntStateOf(PAGE)

    fun reset() {
        query = ""
        limit = PAGE
    }

    companion object {
        const val PAGE = 50
    }
}

@Composable
fun rememberListSheetState(): ListSheetState = remember { ListSheetState() }

/** 一页结果：[items] 是当前要显示的行，[total] 是匹配搜索词的总条数（用于判断还有没有「更多」）。 */
class ListPage<T>(val items: List<T>, val total: Int)

/** 内存里已有的完整列表：在后台线程过滤 + 截取前 [limit] 条。 */
suspend fun <T> filterPage(all: List<T>, query: String, limit: Int, matches: (T, String) -> Boolean): ListPage<T> =
    withContext(Dispatchers.Default) {
        val f = if (query.isEmpty()) all else all.filter { matches(it, query) }
        ListPage(if (f.size > limit) f.subList(0, limit).toList() else f, f.size)
    }

/**
 * 带搜索框、分页加载的列表 sheet。数据只在 sheet 打开时通过 [load] 读取，关闭后丢弃，页面本身不常驻列表。
 *
 * @param load 参数是 trim 后的小写搜索词和要显示的最大行数；在协程里调用，自己负责切到合适的线程
 *   （数据库查询用 IO，内存过滤用 [filterPage]）
 * @param reloadKey 变化时重新 [load]（比如底层数据被改动）
 * @param key 列表项的稳定 key；条目可能重复（如同一网址的多个标签）时传 null
 * @param row 每一行的内容，通常用 [ListSheetRow]
 */
@Composable
fun <T> SearchableListSheet(
    show: Boolean,
    title: String,
    state: ListSheetState,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    searchHint: String,
    emptyText: String,
    load: suspend (query: String, limit: Int) -> ListPage<T>,
    reloadKey: Any? = null,
    key: ((T) -> Any)? = null,
    row: @Composable (T) -> Unit,
) {
    var page by remember { mutableStateOf<ListPage<T>?>(null) }
    var debounced by remember { mutableStateOf(state.query) }
    val latestLoad by rememberUpdatedState(load)

    // 搜索词防抖：打字时停 250ms 才触发查询；清空 / 重置立即生效
    LaunchedEffect(state.query, show) {
        if (!show) return@LaunchedEffect
        if (state.query.isNotEmpty() && state.query != debounced) delay(250)
        debounced = state.query
    }
    LaunchedEffect(show, debounced, state.limit, reloadKey) {
        if (!show) {
            // 等收起动画走完再释放结果
            delay(400)
            page = null
            return@LaunchedEffect
        }
        page = latestLoad(debounced.trim().lowercase(), state.limit)
    }

    AppBottomSheet(
        show = show,
        title = title,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        val p = page
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp

        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            TextField(
                value = state.query,
                onValueChange = {
                    state.query = it
                    state.limit = ListSheetState.PAGE
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                label = searchHint,
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            if (p == null || p.items.isEmpty()) {
                Text(
                    text = when {
                        p == null -> "加载中…"
                        state.query.isBlank() -> emptyText
                        else -> "没有匹配的内容"
                    },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                    items(p.items, key = key) { row(it) }
                    if (p.total > p.items.size) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { state.limit += ListSheetState.PAGE }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "更多（还有 ${p.total - p.items.size} 项）",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [SearchableListSheet] 里的一行：标题 + 副标题。 */
@Composable
fun ListSheetRow(title: String, summary: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
