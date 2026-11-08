package dev.dsh.miuixbrowser

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
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
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.File
import top.yukonga.miuix.kmp.icon.glass.Import
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Gecko 专属「拓展」页（WebExtension）。样式沿用「脚本」页：玻璃顶栏、卡片列表、玻璃弹窗 / 底部 sheet。
 *
 * - 右上角：搜索（顶部展开搜索框，搜 Firefox 附加组件商店）、更多（导入 / 全部更新）；
 * - 搜索结果显示在页面最上方，已安装的会标出；
 * - 「导入」弹底部 sheet：从链接 / 从文件（.xpi）；
 * - 顶部卡片：定期检查更新（手动 / 每天 / 每 3 天 / 每周）与上次检查时间；
 * - 已安装拓展：启用开关、检查更新、删除；点卡片进入详情（权限、无痕、设置页、主页）。
 */
@Composable
fun GeckoExtensionsScreen(
    store: GeckoExtensionStore,
    onBack: () -> Unit,
    blurEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    // 搜索
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchBusy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<AmoResult>?>(null) }
    var searchError by remember { mutableStateOf("") }

    // 菜单 / 导入 / 详情 / 删除
    var showMore by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(Rect.Zero) }
    var importStage by remember { mutableStateOf(0) } // 0 关闭，1 选择方式，2 输入链接
    var linkText by remember { mutableStateOf("") }
    var importBusy by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<GeckoAddon?>(null) }
    var updatingAll by remember { mutableStateOf(false) }
    var busyIds by remember { mutableStateOf(setOf<String>()) }
    var installingKeys by remember { mutableStateOf(setOf<String>()) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // 进入页面：标记可见（权限确认弹窗只在页面可见时出现），刷新列表，到期则自动检查更新
    DisposableEffect(Unit) {
        store.pageVisible = true
        onDispose { store.pageVisible = false }
    }
    LaunchedEffect(Unit) {
        runCatching { store.refresh() }
            .onFailure { toast("读取拓展列表失败：Gecko 未就绪") }
        store.autoUpdateIfDue()
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || searchBusy) return
        searchBusy = true
        searchError = ""
        scope.launch {
            store.search(q)
                .onSuccess { results = it }
                .onFailure {
                    results = emptyList()
                    searchError = it.message ?: "搜索失败"
                }
            searchBusy = false
        }
    }

    fun installAmo(item: AmoResult) {
        if (item.guid in installingKeys) return
        installingKeys = installingKeys + item.guid
        scope.launch {
            store.installFromAmo(item)
                .onSuccess { toast("已安装 $it") }
                .onFailure { toast(it.message ?: "安装失败") }
            installingKeys = installingKeys - item.guid
        }
    }

    fun importLink(url: String) {
        if (importBusy) return
        importBusy = true
        toast("正在下载并安装…")
        scope.launch {
            store.installFromUrl(url)
                .onSuccess { toast("已安装 $it") }
                .onFailure { toast(it.message ?: "安装失败") }
            importBusy = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (importBusy) return@rememberLauncherForActivityResult
        importBusy = true
        toast("正在安装…")
        scope.launch {
            store.installFromFile(uri)
                .onSuccess { toast("已安装 $it") }
                .onFailure { toast(it.message ?: "安装失败") }
            importBusy = false
        }
    }

    fun updateAll() {
        if (updatingAll) return
        if (store.items.isEmpty()) {
            toast("没有已安装的拓展")
            return
        }
        updatingAll = true
        scope.launch {
            val (updated, checked, total) = store.updateAll()
            updatingAll = false
            toast(
                when {
                    checked == 0 -> "检查更新失败，检查网络"
                    updated > 0 -> "已更新 $updated 个拓展（共 $total 个）"
                    else -> "全部已是最新（$checked/$total）"
                },
            )
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
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
                                    searching = !searching
                                    if (!searching) {
                                        query = ""
                                        results = null
                                        searchError = ""
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
                                    contentDescription = "更多",
                                    modifier = Modifier.size(22.dp),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        bottomContent = {
                            if (searching) {
                                TextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    label = "在 Firefox 拓展商店搜索",
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                    trailingIcon = {
                                        IconButton(onClick = { runSearch() }) {
                                            Icon(
                                                imageVector = MiuixIcons.Glass.Search,
                                                contentDescription = "搜索",
                                                modifier = Modifier.size(20.dp),
                                                tint = MiuixTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    )
                },
            ) { innerPadding ->
                val topInset = innerPadding.calculateTopPadding()
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(backdrop)
                            .kyantLayerBackdrop(liquidBackdrop)
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .background(MiuixTheme.colorScheme.surface),
                    ) {
                        val installed = store.items.toList()
                        val installedIds = installed.map { it.id }.toSet()
                        LazyColumn(
                            contentPadding = PaddingValues(
                                top = topInset + 8.dp,
                                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                            ),
                        ) {
                            // ---- 搜索结果（页面最上方） ----
                            if (searching && (searchBusy || results != null)) {
                                item(key = "search_title") {
                                    SmallTitle(
                                        text = when {
                                            searchBusy -> "正在搜索…"
                                            else -> "搜索结果（${results?.size ?: 0}）"
                                        },
                                    )
                                }
                                if (!searchBusy && results.isNullOrEmpty()) {
                                    item(key = "search_empty") {
                                        Text(
                                            text = searchError.ifBlank { "没有找到支持 Firefox for Android 的相关拓展" },
                                            fontSize = 13.sp,
                                            color = if (searchError.isNotBlank()) {
                                                MiuixTheme.colorScheme.error
                                            } else {
                                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            },
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                        )
                                    }
                                }
                                if (!searchBusy) {
                                    items(results.orEmpty(), key = { "amo_" + it.guid }) { item ->
                                        AmoCard(
                                            item = item,
                                            installed = item.guid in installedIds,
                                            installing = item.guid in installingKeys,
                                            onInstall = { installAmo(item) },
                                        )
                                    }
                                }
                            }

                            // ---- 定期检查更新 ----
                            item(key = "auto_update") {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    insideMargin = PaddingValues(0.dp),
                                ) {
                                    AppDropdownPreference(
                                        backdrop = if (glass) backdrop else null,
                                        title = "定期检查更新",
                                        summary = if (store.lastCheck == 0L) "尚未检查" else "上次检查：" + formatTime(store.lastCheck),
                                        items = listOf("手动", "每天", "每 3 天", "每周"),
                                        selectedIndex = store.autoUpdate.coerceIn(0, 3),
                                        onSelectedIndexChange = { store.updateAutoUpdate(it) },
                                    )
                                }
                            }

                            // ---- 已安装 ----
                            item(key = "installed_title") { SmallTitle(text = "已安装拓展（${installed.size}）") }
                            if (installed.isEmpty()) {
                                item(key = "installed_empty") {
                                    Text(
                                        text = if (store.loading) {
                                            "正在读取…"
                                        } else {
                                            "还没有拓展。点右上角搜索，从 Firefox 拓展商店安装；或点「更多 → 导入」从链接 / 文件（.xpi）安装。"
                                        },
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                    )
                                }
                            }
                            items(installed, key = { "ext_" + it.id }) { addon ->
                                AddonCard(
                                    addon = addon,
                                    refreshing = addon.id in busyIds,
                                    onOpenDetail = { detailId = addon.id },
                                    onToggle = { on ->
                                        scope.launch {
                                            store.setEnabled(addon, on).onFailure { toast(it.message ?: "操作失败") }
                                        }
                                    },
                                    onUpdate = {
                                        if (addon.id !in busyIds) {
                                            busyIds = busyIds + addon.id
                                            scope.launch {
                                                store.update(addon)
                                                    .onSuccess { changed -> toast(if (changed) "已更新 ${addon.name}" else "${addon.name} 已是最新") }
                                                    .onFailure { toast(it.message ?: "检查更新失败") }
                                                busyIds = busyIds - addon.id
                                            }
                                        }
                                    },
                                    onDelete = { deleting = addon },
                                )
                            }

                            item(key = "footer") {
                                Text(
                                    text = "拓展由 Gecko 运行，仅对 Gecko 标签生效。可安装 Tampermonkey / Violentmonkey（用户脚本）、" +
                                        "uBlock Origin（去广告）等来替代 WebView 内核下的内置功能。" +
                                        "有新版本时 Gecko 会按拓展自己的更新地址下载；更新带来新权限时需要在本页确认。",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                                )
                            }
                        }
                    }
                }

                // ---- 删除确认 ----
                val deleteTarget = deleting
                AppGlassDialog(
                    show = deleteTarget != null,
                    title = "移除拓展",
                    summary = deleteTarget?.name,
                    backdrop = backdrop,
                    onDismissRequest = { deleting = null },
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppDialogButton(onClick = { deleting = null }, modifier = Modifier.weight(1f)) {
                            Text(text = "取消", style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                val target = deleteTarget
                                deleting = null
                                if (target != null) {
                                    if (detailId == target.id) detailId = null
                                    scope.launch {
                                        store.uninstall(target)
                                            .onSuccess { toast("已移除 ${target.name}") }
                                            .onFailure { toast(it.message ?: "移除失败") }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Destructive,
                        ) {
                            Text(text = "移除", style = MiuixTheme.textStyles.button)
                        }
                    }
                }

                // ---- 安装 / 更新权限确认（Gecko 回调进来的） ----
                val prompt = store.prompt
                var lastPrompt by remember { mutableStateOf<GeckoExtPrompt?>(null) }
                if (prompt != null) lastPrompt = prompt
                var privateAllowed by remember(prompt) { mutableStateOf(false) }
                AppGlassDialog(
                    show = prompt != null,
                    title = if (lastPrompt?.isUpdate == true) "「${lastPrompt?.name}」需要新的权限" else "添加「${lastPrompt?.name}」？",
                    summary = if (lastPrompt?.isUpdate == true) "更新后的版本申请了以下额外权限" else "这个拓展将可以：",
                    backdrop = backdrop,
                    onDismissRequest = { store.prompt?.deny() },
                ) {
                    val shown = lastPrompt
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = PROMPT_LIST_MAX_HEIGHT)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            val lines = buildList {
                                shown?.origins?.forEach { add(describeOrigin(it)) }
                                shown?.permissions?.forEach { add(describePermission(it)) }
                                shown?.dataCollection?.forEach { add(describePermission(it)) }
                            }.distinct()
                            if (lines.isEmpty()) {
                                Text("无需额外权限", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            lines.forEach { line ->
                                Text(
                                    text = "•  $line",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                        }
                        if (shown?.isUpdate == false) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "允许在无痕标签中运行",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                AppSwitch(checked = privateAllowed, onCheckedChange = { privateAllowed = it })
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppDialogButton(onClick = { store.prompt?.deny() }, modifier = Modifier.weight(1f)) {
                                Text(text = "取消", style = MiuixTheme.textStyles.button)
                            }
                            AppDialogButton(
                                onClick = { store.prompt?.allow(privateAllowed) },
                                modifier = Modifier.weight(1f),
                                role = GlassDialogButtonRole.Primary,
                            ) {
                                Text(text = if (shown?.isUpdate == true) "更新" else "添加", style = MiuixTheme.textStyles.button)
                            }
                        }
                    }
                }

                // ---- 导入 sheet：从链接 / 从文件 ----
                AppBottomSheet(
                    show = importStage != 0,
                    title = if (importStage == 2) "从链接导入" else "导入拓展",
                    backdrop = backdrop,
                    onDismissRequest = { importStage = 0 },
                ) {
                    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
                        if (importStage == 2) {
                            TextField(
                                value = linkText,
                                onValueChange = { linkText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = "拓展地址（商店页面或 .xpi 直链）",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    if (linkText.isNotBlank()) {
                                        importStage = 0
                                        importLink(linkText)
                                    }
                                }),
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppDialogButton(onClick = { importStage = 1 }, modifier = Modifier.weight(1f)) {
                                    Text(text = "返回", style = MiuixTheme.textStyles.button)
                                }
                                AppDialogButton(
                                    onClick = {
                                        if (linkText.isNotBlank()) {
                                            importStage = 0
                                            importLink(linkText)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    role = GlassDialogButtonRole.Primary,
                                ) {
                                    Text(text = "导入", style = MiuixTheme.textStyles.button)
                                }
                            }
                        } else {
                            ImportRow(
                                icon = MiuixIcons.Glass.Link,
                                title = "从链接导入",
                                subtitle = "Firefox 拓展商店页面，或 .xpi 直链",
                                onClick = {
                                    linkText = ""
                                    importStage = 2
                                },
                            )
                            ImportRow(
                                icon = MiuixIcons.Glass.File,
                                title = "从文件导入",
                                subtitle = "选择本地 .xpi 文件",
                                onClick = {
                                    importStage = 0
                                    filePicker.launch(arrayOf("application/x-xpinstall", "application/zip", "application/octet-stream", "*/*"))
                                },
                            )
                        }
                    }
                }

                // ---- 详情 sheet（模仿 Firefox「管理拓展」） ----
                val detail = store.items.firstOrNull { it.id == detailId }
                var lastDetail by remember { mutableStateOf<GeckoAddon?>(null) }
                if (detail != null) lastDetail = detail
                AppBottomSheet(
                    show = detail != null,
                    title = lastDetail?.name,
                    backdrop = backdrop,
                    onDismissRequest = { detailId = null },
                ) {
                    val d = detail ?: lastDetail
                    if (d != null) {
                        AddonDetail(
                            addon = d,
                            onToggle = { on ->
                                scope.launch { store.setEnabled(d, on).onFailure { toast(it.message ?: "操作失败") } }
                            },
                            onPrivate = { on ->
                                scope.launch {
                                    store.setAllowedInPrivate(d, on)
                                        .onSuccess { toast(if (on) "已允许在无痕标签中运行" else "已禁止在无痕标签中运行") }
                                        .onFailure { toast(it.message ?: "操作失败") }
                                }
                            },
                            onOptions = {
                                if (store.openOptions(d)) detailId = null else toast("这个拓展没有设置页")
                            },
                            onOpenUrl = { url ->
                                detailId = null
                                store.openUrl?.invoke(url)
                            },
                            onDelete = {
                                detailId = null
                                deleting = d
                            },
                        )
                    }
                }
            }

            // 右上角菜单：放在 Scaffold 之后 → 位于最上层
            AppGlassPopup(
                show = showMore,
                onDismissRequest = { showMore = false },
                anchorBounds = Rect(moreAnchor.left, moreAnchor.bottom, moreAnchor.right, moreAnchor.bottom),
                backdrop = if (glass) backdrop else null,
            ) {
                GlassPopupItem(
                    text = "导入",
                    onClick = {
                        showMore = false
                        importStage = 1
                    },
                    icon = MiuixIcons.Glass.Import,
                )
                GlassPopupItem(
                    text = if (updatingAll) "更新中…" else "全部更新",
                    enabled = !updatingAll,
                    onClick = {
                        showMore = false
                        updateAll()
                    },
                    icon = MiuixIcons.Glass.Refresh,
                )
            }
        }
    }
}

private val PROMPT_LIST_MAX_HEIGHT = 168.dp

private fun formatTime(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun formatUsers(n: Long): String = when {
    n >= 10_000 -> "%.1f 万".format(n / 10_000.0)
    else -> n.toString()
}

/** 拓展图标：Gecko 给的位图；没有时用拼图占位 */
@Composable
private fun AddonIcon(icon: ImageBitmap?, size: Int = 36) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.Puzzle,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size((size * 0.58f).dp),
            )
        }
    }
}

@Composable
private fun AddonCard(
    addon: GeckoAddon,
    refreshing: Boolean,
    onOpenDetail: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonIcon(addon.icon)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).clickable { onOpenDetail() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = addon.name,
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (addon.version.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (addon.version.startsWith("v", ignoreCase = true)) addon.version else "v${addon.version}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        refreshing -> "正在检查更新…"
                        !addon.enabled -> "已停用"
                        else -> "已启用" + if (addon.allowedInPrivate) " · 允许无痕" else ""
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                if (addon.description.isNotBlank()) {
                    Text(
                        text = addon.description,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AppSwitch(checked = addon.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onUpdate) {
                Icon(imageVector = MiuixIcons.Glass.Refresh, contentDescription = "检查更新")
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = MiuixIcons.Glass.Delete, contentDescription = "移除")
            }
        }
    }
}

@Composable
private fun AmoCard(item: AmoResult, installed: Boolean, installing: Boolean, onInstall: () -> Unit) {
    val icon = rememberRemoteIcon(item.iconUrl)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonIcon(icon)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        item.author.takeIf { it.isNotBlank() },
                        if (item.users > 0) "${formatUsers(item.users)} 用户" else null,
                        item.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    ).joinToString(" · "),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.summary.isNotBlank()) {
                    Text(
                        text = item.summary,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            AppTextButton(
                text = when {
                    installed -> "已安装"
                    installing -> "安装中…"
                    else -> "添加"
                },
                enabled = !installed && !installing,
                onClick = onInstall,
                primary = true,
                inPage = true,
                minHeight = 30.dp,
            )
        }
    }
}

@Composable
private fun ImportRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

/** 详情：版本 / 作者 / 描述、权限、开关（启用、无痕）、设置页、主页、移除 */
@Composable
private fun AddonDetail(
    addon: GeckoAddon,
    onToggle: (Boolean) -> Unit,
    onPrivate: (Boolean) -> Unit,
    onOptions: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            AddonIcon(addon.icon, 44)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(
                        addon.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                        addon.creator.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { addon.id },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (addon.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = addon.description,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            AppSwitchPreference(
                title = "启用",
                checked = addon.enabled,
                onCheckedChange = onToggle,
            )
            AppSwitchPreference(
                title = "在无痕标签中运行",
                summary = "允许后，这个拓展可以看到你在无痕标签里的浏览活动",
                checked = addon.allowedInPrivate,
                onCheckedChange = onPrivate,
            )
        }

        val perms = buildList {
            addon.origins.forEach { add(describeOrigin(it)) }
            addon.permissions.forEach { add(describePermission(it)) }
        }.distinct()
        SmallTitle(text = "权限")
        Text(
            text = if (perms.isEmpty()) "无需特殊权限" else perms.joinToString("\n") { "•  $it" },
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            if (addon.optionsUrl.isNotBlank()) {
                ImportRow(MiuixIcons.Glass.Settings, "拓展设置", "在新标签页打开这个拓展的设置页", onOptions)
            }
            val home = addon.amoUrl.ifBlank { addon.homepageUrl }
            if (home.isNotBlank()) {
                ImportRow(MiuixIcons.Glass.Link, "主页", home) { onOpenUrl(home) }
            }
            ImportRow(MiuixIcons.Glass.Delete, "移除", "从 Gecko 中卸载这个拓展", onDelete)
        }
    }
}
