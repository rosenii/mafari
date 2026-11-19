package dev.dsh.miuixbrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.data.rememberSearchIcon
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.net.AmoAddon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 拓展商店（Firefox 拓展商店 / AMO）搜索的公共 UI：状态 + 搜索框 + 结果列表。
 * 拓展管理页（Gecko / WebView 两个内核共用）只需：
 *   val search = rememberExtStoreSearch()
 *   顶栏按钮 → search.toggle()；顶栏下方 → ExtStoreSearchBox(...)；列表里 → extStoreResults(...)
 * 网络层见 net/AmoClient，经 [ExtManagerBackend.searchStore] 进来，本文件不依赖任何内核。
 */

/** 商店搜索的界面状态；搜索本身通过 [ExtManagerBackend.searchStore] 完成 */
@Stable
class ExtStoreSearchState {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var results by mutableStateOf<List<AmoAddon>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    internal val focus = FocusRequester()

    /** 展开 / 收起搜索框；收起时清空关键字与结果 */
    fun toggle() {
        open = !open
        if (!open) {
            query = ""
            results = null
            error = null
        }
    }

    suspend fun search(backend: ExtManagerBackend) {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        error = null
        try {
            backend.searchStore(q)
                .onSuccess { results = it }
                .onFailure {
                    results = emptyList()
                    error = it.message
                }
        } finally {
            searching = false
        }
    }
}

@Composable
fun rememberExtStoreSearch(): ExtStoreSearchState = remember { ExtStoreSearchState() }

/** 搜索框：放在顶栏下方（`bottomContent`），随 [ExtStoreSearchState.open] 展开 / 收起并自动聚焦 */
@Composable
fun ExtStoreSearchBox(state: ExtStoreSearchState, onSearch: () -> Unit) {
    LaunchedEffect(state.open) {
        if (state.open) runCatching { state.focus.requestFocus() }
    }
    AnimatedVisibility(
        visible = state.open,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        TextField(
            value = state.query,
            onValueChange = { state.query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .focusRequester(state.focus),
            label = tr("在 Firefox 拓展商店搜索"),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
    }
}

/**
 * 搜索结果（放在列表最上方）。
 * [installedItems]：已安装的拓展，用来判断「已添加」；[isInstalling]：该条目是否正在安装；[onAdd]：点「添加」。
 */
fun LazyListScope.extStoreResults(
    state: ExtStoreSearchState,
    installedItems: () -> List<ExtEntry>,
    isInstalling: (AmoAddon) -> Boolean,
    onAdd: (AmoAddon) -> Unit,
) {
    val found = state.results
    if (!state.open || !(state.searching || found != null)) return
    item { SmallTitle(text = tr("商店搜索结果")) }
    val error = state.error
    when {
        state.searching -> item { StoreHint(tr("正在搜索…")) }
        error != null -> item { StoreHint(error) }
        found.isNullOrEmpty() -> item { StoreHint(tr("没有找到「{0}」相关的拓展", state.query.trim())) }
        else -> items(found, key = { it.id }) { addon ->
            // 已装拓展的 id 是 GUID，与 AMO 返回的 guid 对应（GUID 大小写不敏感）；没有 guid 时才退回按名称比
            val installed = installedItems().any {
                if (addon.guid.isNotEmpty()) it.id.equals(addon.guid, ignoreCase = true)
                else it.id == addon.slug || it.name == addon.name
            }
            AddonCard(
                addon = addon,
                installed = installed,
                installing = isInstalling(addon),
                onAdd = { onAdd(addon) },
            )
        }
    }
}

@Composable
private fun StoreHint(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun AddonCard(addon: AmoAddon, installed: Boolean, installing: Boolean, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonIcon(url = addon.iconUrl, bitmap = null, fromStore = true)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(addon.version)
                        if (addon.users > 0) append(" · ").append(formatUsers(addon.users)).append(tr(" 用户"))
                        if (addon.rating > 0) append(" · ★ ").append(String.format(java.util.Locale.US, "%.1f", addon.rating))
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (addon.summary.isNotBlank()) {
                    Text(
                        text = addon.summary,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            AppTextButton(
                text = when {
                    installed -> tr("已添加")
                    installing -> tr("安装中")
                    else -> tr("添加")
                },
                onClick = onAdd,
                enabled = !installed && !installing,
                primary = !installed,
                minWidth = 64.dp,
                inPage = true, // 位于采样层内部，必须用实色底，否则液态玻璃下递归采样闪退
            )
        }
    }
}

/** 拓展图标：商店结果按 url 加载；已安装的取后端给的位图；都没有时画占位色块 */
@Composable
internal fun AddonIcon(url: String, bitmap: ImageBitmap?, fromStore: Boolean) {
    val bmp = if (fromStore) rememberSearchIcon(url) else bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(40.dp).squircleClip(10.dp))
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .squircleClip(10.dp)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
        )
    }
}

private fun formatUsers(n: Long): String = when {
    n >= 100_000_000 -> String.format(java.util.Locale.US, tr("%.1f 亿"), n / 1e8)
    n >= 10_000 -> String.format(java.util.Locale.US, tr("%.1f 万"), n / 1e4)
    else -> n.toString()
}
