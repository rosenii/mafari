package dev.dsh.miuixbrowser

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Gecko 专属「拓展」页（WebExtension）。页面骨架沿用脚本页：玻璃顶栏 + 卡片列表。
 *
 * - 右上角：搜索 / 更多。搜索在顶栏下方展开输入框，向 Firefox 拓展商店（AMO）搜索，结果显示在列表最上方；
 * - 更多菜单：导入（底部 sheet：从链接 / 从文件）、全部更新；
 * - 下面是已安装的拓展。
 */
@Composable
fun GeckoExtensionsScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit = {}, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = GeckoExtensionStore

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AmoAddon>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var installingIds by remember { mutableStateOf(setOf<String>()) }
    var showMore by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(Rect.Zero) }
    var showImport by remember { mutableStateOf(false) }
    var updatingAll by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var uninstalling by remember { mutableStateOf<GeckoExtItem?>(null) }
    var busyIds by remember { mutableStateOf(setOf<String>()) }
    var menuItem by remember { mutableStateOf<GeckoExtItem?>(null) }
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    val detail = detailId?.let { id -> store.items.firstOrNull { it.id == id } }
    val focus = remember { FocusRequester() }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // 安装确认框要 Activity；页面存在期间登记，离开时撤销
    DisposableEffect(Unit) {
        store.activityProvider = { context.findActivity() }
        // 不在离开页面时清掉：浏览页里点 .xpi 链接同样需要弹确认框（由 MainActivity 统一维护）
        onDispose { }
    }
    LaunchedEffect(Unit) { runCatching { store.refresh(context) }.onFailure { toast("读取拓展失败：${it.message}") } }
    LaunchedEffect(searchOpen) {
        if (searchOpen) runCatching { focus.requestFocus() }
    }

    fun installFrom(key: String, uri: String, label: String) {
        if (key in installingIds) {
            // 之前这里是静默 return，用户看起来就是「点了没反应」
            toast("$label 正在安装中，请稍候…")
            return
        }
        installingIds = installingIds + key
        toast("正在安装 $label…")
        scope.launch {
            try {
                store.install(context, uri)
                    .onSuccess { toast("已添加 ${it.name}") }
                    .onFailure { if (it.message != "已取消安装") toast(it.message ?: "安装失败") }
            } finally {
                // 无论成功 / 取消 / 失败 / 协程被取消，都要把「安装中」状态放掉
                installingIds = installingIds - key
            }
        }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        searchError = null
        scope.launch {
            store.searchAmo(q)
                .onSuccess { results = it }
                .onFailure {
                    results = emptyList()
                    searchError = it.message
                }
            searching = false
        }
    }

    fun importLink(raw: String) {
        val link = raw.trim()
        if (!link.startsWith("http://") && !link.startsWith("https://")) {
            toast("请输入 http / https 链接")
            return
        }
        scope.launch {
            // 商店页面链接 → 解析出 xpi；其它按 .xpi 直链处理
            val addon = store.resolveAmoPage(link).getOrElse {
                toast(it.message ?: "访问商店失败")
                return@launch
            }
            if (addon != null) installFrom("amo:${addon.id}", addon.xpiUrl, addon.name)
            else installFrom(link, link, "拓展")
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { store.stageFile(context, uri) }
                    .onSuccess { installFrom(it, it, "本地拓展") }
                    .onFailure { toast(it.message ?: "读取文件失败") }
            }
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    PageGlassTopBar(
                        title = "拓展",
                        backdrop = backdrop,
                        glass = glass,
                        scrollBehavior = scrollBehavior,
                        onBack = onBack,
                        backButton = {
                            LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                        },
                        actions = {
                            LiquidGlassButton(
                                onClick = {
                                    searchOpen = !searchOpen
                                    if (!searchOpen) {
                                        query = ""
                                        results = null
                                        searchError = null
                                    }
                                },
                                backdrop = liquid,
                                surfaceVisible = topScrolled,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Search,
                                    contentDescription = "搜索",
                                    modifier = Modifier.size(22.dp),
                                    tint = if (searchOpen) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
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
                                    contentDescription = "更多",
                                    modifier = Modifier.size(22.dp),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        bottomContent = {
                            // 搜索框：在顶栏下方展开 / 收起
                            AnimatedVisibility(
                                visible = searchOpen,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                TextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .focusRequester(focus),
                                    label = "在 Firefox 拓展商店搜索",
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                )
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .kyantLayerBackdrop(liquidBackdrop)
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .background(MiuixTheme.colorScheme.surface),
                ) {
                    val found = results
                    LazyColumn(
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        ),
                    ) {
                        // ---- 商店搜索结果（页面最上方）
                        if (searchOpen && (searching || found != null)) {
                            item { SmallTitle(text = "商店搜索结果") }
                            when {
                                searching -> item { HintText("正在搜索…") }
                                searchError != null -> item { HintText(searchError ?: "") }
                                found.isNullOrEmpty() -> item { HintText("没有找到「${query.trim()}」相关的拓展") }
                                else -> items(found, key = { it.id }) { addon ->
                                    val installed = store.items.any { it.id == addon.slug || it.name == addon.name }
                                    AddonCard(
                                        addon = addon,
                                        installed = installed,
                                        installing = "amo:${addon.id}" in installingIds,
                                        onAdd = { installFrom("amo:${addon.id}", addon.xpiUrl, addon.name) },
                                    )
                                }
                            }
                        }

                        // ---- 自动更新
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                AppDropdownPreference(
                                    backdrop = if (glass) backdrop else null,
                                    title = "自动检查更新",
                                    summary = if (store.lastCheck.value == 0L) "尚未检查" else "上次检查 " + formatTime(store.lastCheck.value),
                                    items = listOf("手动", "每天", "每 3 天", "每周"),
                                    selectedIndex = store.autoUpdate.value.coerceIn(0, 3),
                                    onSelectedIndexChange = { store.updateAutoUpdate(context, it) },
                                )
                            }
                        }

                        // ---- 已安装
                        item { SmallTitle(text = "已安装拓展") }
                        if (store.items.isEmpty()) {
                            item {
                                HintText(
                                    if (store.loading.value) "正在读取…"
                                    else "还没有安装拓展。点右上角搜索，从 Firefox 拓展商店添加，或在「更多」里导入。",
                                )
                            }
                        }
                        items(store.items, key = { it.id }) { item ->
                            InstalledCard(
                                item = item,
                                busy = item.id in busyIds,
                                onOpen = { detailId = item.id },
                                onMore = { bounds ->
                                    menuAnchor = bounds
                                    menuItem = item
                                },
                                onToggle = { on ->
                                    busyIds = busyIds + item.id
                                    scope.launch {
                                        store.setEnabled(context, item, on).onFailure { toast(it.message ?: "操作失败") }
                                        busyIds = busyIds - item.id
                                    }
                                },
                            )
                        }
                        item {
                            HintText("拓展由Gecko内核运行，搜索结果来源于Firefox拓展商店。")
                        }
                    }
                }

            // 弹窗 / sheet 必须放在 Scaffold 的内容里：miuix 的 Overlay 弹层由 Scaffold 的弹层宿主渲染，放在 Scaffold 外面会一直不显示
            ExtSettingsSheet(
                item = detail,
                backdrop = if (glass) backdrop else null,
                busy = detail?.id in busyIds,
                onDismiss = { detailId = null },
                onToggle = { on ->
                    val d = detail ?: return@ExtSettingsSheet
                    busyIds = busyIds + d.id
                    scope.launch {
                        store.setEnabled(context, d, on).onFailure { toast(it.message ?: "操作失败") }
                        busyIds = busyIds - d.id
                    }
                },
                onTogglePrivate = { on ->
                    val d = detail ?: return@ExtSettingsSheet
                    busyIds = busyIds + d.id
                    scope.launch {
                        store.setAllowedInPrivate(context, d, on).onFailure { toast(it.message ?: "设置失败") }
                        busyIds = busyIds - d.id
                    }
                },
                onOptions = {
                    val url = detail?.optionsUrl
                    detailId = null
                    if (url != null) onOpenUrl(url)
                },
                onCheckUpdate = {
                    val d = detail ?: return@ExtSettingsSheet
                    busyIds = busyIds + d.id
                    scope.launch {
                        store.updateOne(context, d)
                            .onSuccess { toast(if (it) "已更新 ${d.name}" else "${d.name} 已是最新") }
                            .onFailure { toast(it.message ?: "检查更新失败") }
                        busyIds = busyIds - d.id
                    }
                },
                onUninstall = { uninstalling = detail },
            )

            AppGlassDialog(
                show = uninstalling != null,
                title = "卸载拓展",
                summary = uninstalling?.name,
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { uninstalling = null },
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppDialogButton(onClick = { uninstalling = null }, modifier = Modifier.weight(1f)) {
                        Text(text = "取消", style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            val target = uninstalling
                            uninstalling = null
                            detailId = null
                            if (target != null) {
                                scope.launch {
                                    store.uninstall(context, target)
                                        .onSuccess { toast("已卸载 ${target.name}") }
                                        .onFailure { toast(it.message ?: "卸载失败") }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Destructive,
                    ) {
                        Text(text = "卸载", style = MiuixTheme.textStyles.button)
                    }
                }
            }

            GeckoImportDialog(
                show = showImport,
                backdrop = if (glass) backdrop else null,
                onDismiss = { showImport = false },
                onLink = { link ->
                    showImport = false
                    importLink(link)
                },
                onPickFile = {
                    showImport = false
                    runCatching {
                        filePicker.launch(arrayOf("application/x-xpinstall", "application/zip", "application/octet-stream", "*/*"))
                    }.onFailure { toast("无法打开文件选择器：${it.message}") }
                },
            )
            }

            // 右上角更多菜单：与 Scaffold 同级、放在最后 → 位于最上层
            AppGlassPopup(
                show = showMore,
                onDismissRequest = { showMore = false },
                anchorBounds = Rect(moreAnchor.left, moreAnchor.bottom, moreAnchor.right, moreAnchor.bottom),
                backdrop = if (glass) backdrop else null,
            ) {
                GlassPopupItem(
                    text = "导入拓展",
                    onClick = {
                        showMore = false
                        showImport = true
                    },
                    icon = MiuixIcons.Glass.Download,
                )
                GlassPopupItem(
                    text = if (updatingAll) "更新中…" else "全部更新",
                    enabled = !updatingAll,
                    onClick = {
                        showMore = false
                        updatingAll = true
                        scope.launch {
                            runCatching { store.updateAll(context) }
                                .onSuccess { (ok, total) ->
                                    toast(
                                        when {
                                            total == 0 -> "没有已安装的拓展"
                                            ok == 0 -> "全部拓展已是最新"
                                            else -> "已更新 $ok/$total 个拓展"
                                        },
                                    )
                                }
                                .onFailure { toast("检查更新失败：${it.message}") }
                            updatingAll = false
                        }
                    },
                    icon = MiuixIcons.Glass.Refresh,
                )
            }

            // 每个已安装拓展卡片上的「更多」菜单
            AppGlassPopup(
                show = menuItem != null,
                onDismissRequest = { menuItem = null },
                anchorBounds = Rect(menuAnchor.left, menuAnchor.bottom, menuAnchor.right, menuAnchor.bottom),
                backdrop = if (glass) backdrop else null,
            ) {
                GlassPopupItem(
                    text = "拓展设置",
                    onClick = {
                        detailId = menuItem?.id
                        menuItem = null
                    },
                    icon = MiuixIcons.Glass.Settings,
                )
                GlassPopupItem(
                    text = "删除",
                    onClick = {
                        uninstalling = menuItem
                        menuItem = null
                    },
                    icon = MiuixIcons.Glass.Delete,
                )
            }

        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/** 导入拓展弹窗（与脚本页的导入弹窗一致）：填链接导入，或点「从本地文件导入」选 .xpi */
@Composable
private fun GeckoImportDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onLink: (String) -> Unit,
    onPickFile: () -> Unit,
) {
    var link by remember(show) { mutableStateOf("") }
    AppGlassDialog(
        show = show,
        title = "导入拓展",
        summary = "支持拓展商店页面链接、.xpi 直链，或本地 .xpi 文件",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                label = "拓展链接（http / https）",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) onLink(link.trim()) }),
            )
            Spacer(Modifier.height(10.dp))
            AppTextButton(
                text = "从本地文件导入",
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { if (link.isNotBlank()) onLink(link.trim()) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "导入", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun AddonCard(addon: AmoAddon, installed: Boolean, installing: Boolean, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonIcon(url = addon.iconUrl, bitmapKey = null)
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
                        if (addon.users > 0) append(" · ").append(formatUsers(addon.users)).append(" 用户")
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
                    installed -> "已添加"
                    installing -> "安装中"
                    else -> "添加"
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

@Composable
private fun InstalledCard(
    item: GeckoExtItem,
    busy: Boolean,
    onOpen: () -> Unit,
    onMore: (Rect) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    var moreBounds by remember { mutableStateOf(Rect.Zero) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonIcon(url = "", bitmapKey = item.id)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).clickable(onClick = onOpen)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.version.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(text = item.version, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 开关样式跟随「设置 → 高级 → 材质」
            AppSwitch(
                checked = item.enabled,
                onCheckedChange = { if (!busy) onToggle(it) },
            )
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .onGloballyPositioned { moreBounds = it.boundsInRoot() }
                    .clickable { onMore(moreBounds) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.More,
                    contentDescription = "更多",
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 拓展图标：商店结果按 url 加载；已安装的取 Gecko 给的位图；都没有时画占位色块 */
@Composable
private fun AddonIcon(url: String, bitmapKey: String?) {
    val bmp = if (bitmapKey != null) GeckoExtensionStore.icons[bitmapKey] else rememberSearchIcon(url)
    val shape = RoundedCornerShape(10.dp)
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(40.dp).clip(shape))
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
        )
    }
}

private fun formatUsers(n: Long): String = when {
    n >= 100_000_000 -> String.format(java.util.Locale.US, "%.1f 亿", n / 1e8)
    n >= 10_000 -> String.format(java.util.Locale.US, "%.1f 万", n / 1e4)
    else -> n.toString()
}

private fun formatTime(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

/** 拓展设置 sheet：启用 / 隐私浏览 / 设置 / 详细信息 / 权限 / 移除（与 Firefox 的拓展设置页一致） */
@Composable
private fun ExtSettingsSheet(
    item: GeckoExtItem?,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTogglePrivate: (Boolean) -> Unit,
    onOptions: () -> Unit,
    onCheckUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    // 关闭动画期间 item 已变 null：保留最后一次内容，避免内容闪空
    var last by remember { mutableStateOf<GeckoExtItem?>(null) }
    if (item != null) last = item
    val shown = item ?: last
    // 0 = 主页，1 = 详细信息，2 = 权限
    var page by remember(item?.id) { mutableStateOf(0) }
    AppBottomSheet(
        show = item != null,
        title = shown?.name ?: "",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        if (shown == null) return@AppBottomSheet
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            if (page != 0) {
                SettingsRow(title = "‹ 返回", summary = if (page == 1) "详细信息" else "权限") { page = 0 }
            }
            when (page) {
                0 -> {
                    ToggleRow(title = "已启用", checked = shown.enabled, enabled = !busy, onChange = onToggle)
                    ToggleRow(
                        title = "在隐私浏览中运行",
                        checked = shown.allowedInPrivate,
                        enabled = !busy && shown.enabled,
                        onChange = onTogglePrivate,
                    )
                    if (shown.optionsUrl != null) {
                        SettingsRow(title = "设置", summary = "打开拓展自带的设置页", enabled = shown.enabled, onClick = onOptions)
                    }
                    SettingsRow(title = "详细信息", summary = "版本、作者、简介") { page = 1 }
                    SettingsRow(title = "权限", summary = "拓展可访问的数据") { page = 2 }
                    Spacer(Modifier.height(10.dp))
                    AppTextButton(
                        text = if (busy) "处理中…" else "检查更新",
                        onClick = onCheckUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextButton(text = "移除", onClick = onUninstall, modifier = Modifier.fillMaxWidth())
                }
                1 -> {
                    DetailLine("名称", shown.name)
                    if (shown.version.isNotBlank()) DetailLine("版本", shown.version)
                    if (shown.author.isNotBlank()) DetailLine("作者", shown.author)
                    if (shown.homepage.isNotBlank()) DetailLine("主页", shown.homepage)
                    DetailLine("ID", shown.id)
                    if (shown.description.isNotBlank()) DetailLine("简介", shown.description)
                }
                else -> {
                    val perms = GeckoExtensionStore.describePermissions(shown.permissions, shown.origins)
                    if (perms.isEmpty()) {
                        Text(
                            text = "此拓展不需要额外权限",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    } else {
                        perms.forEach {
                            Text(
                                text = "· $it",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.weight(1f),
        )
        AppSwitch(checked = checked, onCheckedChange = { if (enabled) onChange(it) }, enabled = enabled)
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
        )
        Spacer(Modifier.height(2.dp))
        Text(text = summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
        Text(text = label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}
