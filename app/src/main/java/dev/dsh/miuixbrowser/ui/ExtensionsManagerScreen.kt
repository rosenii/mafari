package dev.dsh.miuixbrowser.ui

import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import dev.dsh.miuixbrowser.i18n.tr

import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.delay
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
import dev.dsh.miuixbrowser.ui.icons.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Gecko 内核的「拓展」页：共用的 [ExtensionsManagerScreen] + [GeckoExtBackend]（WebExtensionController） */
@Composable
fun GeckoExtensionsScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit = {}, blurEnabled: Boolean = true) {
    // 只在 Gecko 内核下才会走到这里（无内核版没有 Gecko 类，见 EngineSupport）
    val backend = remember { GeckoExtBackend() }
    ExtensionsManagerScreen(backend = backend, onBack = onBack, onOpenUrl = onOpenUrl, blurEnabled = blurEnabled)
}

/**
 * WebView 内核的「拓展」页：同一个管理页 + [WebViewExtBackend]（自研 WebExtension 运行时）。
 * [onChanged]：安装 / 启停 / 卸载 / 更新之后调用，让已打开的标签重新装配拓展注入。
 */
@Composable
fun WebViewExtensionsScreen(
    system: dev.dsh.miuixbrowser.ext.ExtensionSystem,
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
    blurEnabled: Boolean = true,
) {
    val currentOnChanged by rememberUpdatedState(onChanged)
    val backend = remember(system) { WebViewExtBackend(system) { currentOnChanged() } }
    ExtensionsManagerScreen(
        backend = backend,
        onBack = onBack,
        blurEnabled = blurEnabled,
        // 功能菜单 / 设置页需要拓展 bridge，用 Sheet 承载拓展自己的页面
        pageSheet = { request, backdrop, onDismiss ->
            WebViewExtPageSheet(system = system, request = request, backdrop = backdrop, onDismiss = onDismiss)
        },
    )
}

/**
 * 拓展管理页（两个内核共用）。页面骨架沿用脚本页：玻璃顶栏 + 卡片列表。
 *
 * - 右上角：搜索（后端有商店才显示）/ 更多。搜索在顶栏下方展开输入框，向拓展商店搜索，结果显示在列表最上方；
 * - 更多菜单：导入（底部 sheet：从链接 / 从文件）、全部更新；
 * - 下面是已安装的拓展。
 *
 * 页面只面对 [ExtManagerBackend]，不直接依赖任何内核。
 */
@Composable
fun ExtensionsManagerScreen(
    backend: ExtManagerBackend,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    blurEnabled: Boolean = true,
    /** 承载拓展自己的页面（功能菜单 / 设置页）的 Sheet；null = 当前内核不支持，相关入口不显示 */
    pageSheet: (@Composable (ExtPageRequest?, top.yukonga.miuix.kmp.blur.Backdrop?, () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    val storeSearch = rememberExtStoreSearch()
    var installingIds by remember { mutableStateOf(setOf<String>()) }
    var showMore by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(Rect.Zero) }
    var showImport by remember { mutableStateOf(false) }
    var updatingAll by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var uninstalling by remember { mutableStateOf<ExtEntry?>(null) }
    var busyIds by remember { mutableStateOf(setOf<String>()) }
    var menuItem by remember { mutableStateOf<ExtEntry?>(null) }
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    var pageRequest by remember { mutableStateOf<ExtPageRequest?>(null) }
    val detail = detailId?.let { id -> backend.items.firstOrNull { it.id == id } }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** 先收起当前的设置 sheet / 菜单，再开拓展页 sheet（两层 sheet 同时切换时后一层可能被宿主吞掉） */
    fun openExtPage(item: ExtEntry, kind: ExtPageRequest.Kind) {
        detailId = null
        menuItem = null
        scope.launch {
            delay(250)
            pageRequest = ExtPageRequest(item.id, item.name, kind)
        }
    }

    // 安装确认框要 Activity；页面存在期间登记，离开时撤销
    DisposableEffect(Unit) {
        backend.bind(context)
        // 不在离开页面时清掉：浏览页里点 .xpi 链接同样需要弹确认框（由 MainActivity 统一维护）
        onDispose { }
    }
    LaunchedEffect(Unit) { runCatching { backend.refresh(context) }.onFailure { toast(tr("读取拓展失败：{0}", it.message)) } }

    fun installFrom(key: String, uri: String, label: String) {
        if (key in installingIds) {
            // 之前这里是静默 return，用户看起来就是「点了没反应」
            toast(tr("{0} 正在安装中，请稍候…", label))
            return
        }
        installingIds = installingIds + key
        toast(tr("正在安装 {0}…", label))
        scope.launch {
            try {
                backend.install(context, uri)
                    .onSuccess { toast(tr("已添加 {0}", it)) }
                    .onFailure { if (it.message != tr("已取消安装")) toast(it.message ?: tr("安装失败")) }
            } finally {
                // 无论成功 / 取消 / 失败 / 协程被取消，都要把「安装中」状态放掉
                installingIds = installingIds - key
            }
        }
    }

    fun importLink(raw: String) {
        val link = raw.trim()
        if (!link.startsWith("http://") && !link.startsWith("https://")) {
            toast(tr("请输入 http / https 链接"))
            return
        }
        scope.launch {
            // 商店页面链接 → 解析出 xpi；其它按 .xpi 直链处理
            val addon = backend.resolveStorePage(link).getOrElse {
                toast(it.message ?: tr("访问商店失败"))
                return@launch
            }
            if (addon != null) installFrom("amo:${addon.id}", addon.xpiUrl, addon.name)
            else installFrom(link, link, tr("拓展"))
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { backend.stageFile(context, uri) }
                    .onSuccess { installFrom(it, it, tr("本地拓展")) }
                    .onFailure { toast(it.message ?: tr("读取文件失败")) }
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
                        title = tr("拓展"),
                        backdrop = backdrop,
                        glass = glass,
                        scrollBehavior = scrollBehavior,
                        onBack = onBack,
                        backButton = {
                            LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                        },
                        actions = {
                            if (backend.supportsStore) LiquidGlassButton(
                                onClick = { storeSearch.toggle() },
                                backdrop = liquid,
                                surfaceVisible = topScrolled,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Search,
                                    contentDescription = tr("搜索"),
                                    modifier = Modifier.size(22.dp),
                                    tint = if (storeSearch.open) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
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
                                    contentDescription = tr("更多"),
                                    modifier = Modifier.size(22.dp),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        bottomContent = {
                            // 搜索框：在顶栏下方展开 / 收起
                            ExtStoreSearchBox(state = storeSearch, onSearch = { scope.launch { storeSearch.search(backend) } })
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .kyantLayerBackdrop(liquidBackdrop)
                        .scrollEndHaptic()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .pageBackground(),
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        ),
                    ) {
                        // ---- 商店搜索结果（页面最上方）
                        if (backend.supportsStore) extStoreResults(
                            state = storeSearch,
                            installedItems = { backend.items },
                            isInstalling = { "amo:${it.id}" in installingIds },
                            onAdd = { installFrom("amo:${it.id}", it.xpiUrl, it.name) },
                        )

                        // ---- 自动更新
                        if (backend.supportsAutoUpdate) item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                AppDropdownPreference(
                                    backdrop = if (glass) backdrop else null,
                                    title = tr("自动检查更新"),
                                    summary = if (backend.lastCheck.value == 0L) tr("尚未检查") else tr("上次检查 ") + formatTime(backend.lastCheck.value),
                                    items = listOf(tr("手动"), tr("每天"), tr("每 3 天"), tr("每周")),
                                    selectedIndex = backend.autoUpdate.value.coerceIn(0, 3),
                                    onSelectedIndexChange = { backend.updateAutoUpdate(context, it) },
                                )
                            }
                        }

                        // ---- 已安装
                        item { SmallTitle(text = tr("已安装拓展")) }
                        if (backend.items.isEmpty()) {
                            item {
                                HintText(
                                    if (backend.loading) tr("正在读取…")
                                    else backend.emptyHint,
                                )
                            }
                        }
                        items(backend.items, key = { it.id }) { item ->
                            InstalledCard(
                                item = item,
                                icon = backend.icon(item.id),
                                busy = item.id in busyIds,
                                onOpen = { detailId = item.id },
                                onMore = { bounds ->
                                    menuAnchor = bounds
                                    menuItem = item
                                },
                                onToggle = { on ->
                                    busyIds = busyIds + item.id
                                    scope.launch {
                                        backend.setEnabled(context, item.id, on).onFailure { toast(it.message ?: tr("操作失败")) }
                                        busyIds = busyIds - item.id
                                    }
                                },
                            )
                        }
                        item {
                            HintText(backend.footerHint)
                        }
                    }
                }

            // 弹窗 / sheet 必须放在 Scaffold 的内容里：miuix 的 Overlay 弹层由 Scaffold 的弹层宿主渲染，放在 Scaffold 外面会一直不显示
            ExtSettingsSheet(
                item = detail,
                supportsPrivate = backend.supportsPrivate,
                backdrop = if (glass) backdrop else null,
                busy = detail?.id in busyIds,
                onDismiss = { detailId = null },
                onToggle = { on ->
                    val d = detail ?: return@ExtSettingsSheet
                    busyIds = busyIds + d.id
                    scope.launch {
                        backend.setEnabled(context, d.id, on).onFailure { toast(it.message ?: tr("操作失败")) }
                        busyIds = busyIds - d.id
                    }
                },
                onTogglePrivate = { on ->
                    val d = detail ?: return@ExtSettingsSheet
                    busyIds = busyIds + d.id
                    scope.launch {
                        backend.setAllowedInPrivate(context, d.id, on).onFailure { toast(it.message ?: tr("设置失败")) }
                        busyIds = busyIds - d.id
                    }
                },
                pageSupported = pageSheet != null,
                onOpenPopup = { detail?.let { openExtPage(it, ExtPageRequest.Kind.POPUP) } },
                onOptions = {
                    val d = detail
                    val url = d?.optionsUrl
                    if (d != null && url == null && d.hasOptionsPage && pageSheet != null) {
                        openExtPage(d, ExtPageRequest.Kind.OPTIONS)
                    } else {
                        detailId = null
                        if (url != null) onOpenUrl(url)
                    }
                },
                onCheckUpdate = {
                    val d = detail ?: return@ExtSettingsSheet
                    busyIds = busyIds + d.id
                    scope.launch {
                        backend.updateOne(context, d.id)
                            .onSuccess { toast(if (it) tr("已更新 {0}", d.name) else tr("{0} 已是最新", d.name)) }
                            .onFailure { toast(it.message ?: tr("检查更新失败")) }
                        busyIds = busyIds - d.id
                    }
                },
                onUninstall = { uninstalling = detail },
            )

            pageSheet?.invoke(pageRequest, if (glass) backdrop else null) { pageRequest = null }

            AppGlassDialog(
                show = uninstalling != null,
                title = tr("卸载拓展"),
                summary = uninstalling?.name,
                backdrop = if (glass) backdrop else null,
                onDismissRequest = { uninstalling = null },
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppDialogButton(onClick = { uninstalling = null }, modifier = Modifier.weight(1f)) {
                        Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            val target = uninstalling
                            uninstalling = null
                            detailId = null
                            if (target != null) {
                                scope.launch {
                                    backend.uninstall(context, target.id)
                                        .onSuccess { toast(tr("已卸载 {0}", target.name)) }
                                        .onFailure { toast(it.message ?: tr("卸载失败")) }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Destructive,
                    ) {
                        Text(text = tr("卸载"), style = MiuixTheme.textStyles.button)
                    }
                }
            }

            ExtImportDialog(
                show = showImport,
                summary = backend.importSummary,
                backdrop = if (glass) backdrop else null,
                onDismiss = { showImport = false },
                onLink = { link ->
                    showImport = false
                    importLink(link)
                },
                onPickFile = {
                    showImport = false
                    runCatching {
                        filePicker.launch(arrayOf("application/x-xpinstall", "application/zip", "application/x-chrome-extension", "application/octet-stream", "*/*"))
                    }.onFailure { toast(tr("无法打开文件选择器：{0}", it.message)) }
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
                    text = tr("导入拓展"),
                    onClick = {
                        showMore = false
                        showImport = true
                    },
                    icon = MiuixIcons.Glass.Download,
                )
                GlassPopupItem(
                    text = if (updatingAll) tr("更新中…") else tr("全部更新"),
                    enabled = !updatingAll,
                    onClick = {
                        showMore = false
                        updatingAll = true
                        scope.launch {
                            runCatching { backend.updateAll(context) }
                                .onSuccess { (ok, total) ->
                                    toast(
                                        when {
                                            total == 0 -> tr("没有已安装的拓展")
                                            ok == 0 -> tr("全部拓展已是最新")
                                            else -> tr("已更新 {0}/{1} 个拓展", ok, total)
                                        },
                                    )
                                }
                                .onFailure { toast(tr("检查更新失败：{0}", it.message)) }
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
                val menuTarget = menuItem
                if (pageSheet != null && menuTarget != null && menuTarget.hasPopup && menuTarget.enabled) {
                    GlassPopupItem(
                        text = tr("功能菜单"),
                        onClick = { openExtPage(menuTarget, ExtPageRequest.Kind.POPUP) },
                        icon = AppIcons.Puzzle,
                    )
                }
                GlassPopupItem(
                    text = tr("拓展设置"),
                    onClick = {
                        detailId = menuItem?.id
                        menuItem = null
                    },
                    icon = MiuixIcons.Glass.Settings,
                )
                GlassPopupItem(
                    text = tr("删除"),
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

/** 导入拓展弹窗（与脚本页的导入弹窗一致）：填链接导入，或点「从本地文件导入」选拓展包 */
@Composable
private fun ExtImportDialog(
    show: Boolean,
    summary: String,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onLink: (String) -> Unit,
    onPickFile: () -> Unit,
) {
    var link by remember(show) { mutableStateOf("") }
    AppGlassDialog(
        show = show,
        title = tr("导入拓展"),
        summary = summary,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                label = tr("拓展链接（http / https）"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) onLink(link.trim()) }),
            )
            Spacer(Modifier.height(10.dp))
            AppTextButton(
                text = tr("从本地文件导入"),
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { if (link.isNotBlank()) onLink(link.trim()) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = tr("导入"), style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun InstalledCard(
    item: ExtEntry,
    icon: ImageBitmap?,
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
            AddonIcon(url = "", bitmap = icon, fromStore = false)
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
                    .squircleClip(20.dp)
                    .onGloballyPositioned { moreBounds = it.boundsInRoot() }
                    .clickable { onMore(moreBounds) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.More,
                    contentDescription = tr("更多"),
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

/** 拓展设置 sheet：启用 / 隐私浏览 / 设置 / 详细信息 / 权限 / 移除（与 Firefox 的拓展设置页一致） */
@Composable
private fun ExtSettingsSheet(
    item: ExtEntry?,
    supportsPrivate: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTogglePrivate: (Boolean) -> Unit,
    pageSupported: Boolean,
    onOpenPopup: () -> Unit,
    onOptions: () -> Unit,
    onCheckUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    // 关闭动画期间 item 已变 null：保留最后一次内容，避免内容闪空
    var last by remember { mutableStateOf<ExtEntry?>(null) }
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
                SettingsRow(title = tr("‹ 返回"), summary = if (page == 1) tr("详细信息") else tr("权限")) { page = 0 }
            }
            when (page) {
                0 -> {
                    ToggleRow(title = tr("已启用"), checked = shown.enabled, enabled = !busy, onChange = onToggle)
                    if (supportsPrivate) {
                        ToggleRow(
                            title = tr("在隐私浏览中运行"),
                            checked = shown.allowedInPrivate,
                            enabled = !busy && shown.enabled,
                            onChange = onTogglePrivate,
                        )
                    }
                    if (pageSupported && shown.hasPopup) {
                        SettingsRow(title = tr("功能菜单"), summary = tr("打开拓展的快捷菜单"), enabled = shown.enabled, onClick = onOpenPopup)
                    }
                    if (shown.optionsUrl != null || (pageSupported && shown.hasOptionsPage)) {
                        SettingsRow(title = tr("设置"), summary = tr("打开拓展自带的设置页"), enabled = shown.enabled, onClick = onOptions)
                    }
                    SettingsRow(title = tr("详细信息"), summary = tr("版本、作者、简介")) { page = 1 }
                    SettingsRow(title = tr("权限"), summary = tr("拓展可访问的数据")) { page = 2 }
                    Spacer(Modifier.height(10.dp))
                    AppTextButton(
                        text = if (busy) tr("处理中…") else tr("检查更新"),
                        onClick = onCheckUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextButton(text = tr("移除"), onClick = onUninstall, modifier = Modifier.fillMaxWidth())
                }
                1 -> {
                    DetailLine(tr("名称"), shown.name)
                    if (shown.version.isNotBlank()) DetailLine(tr("版本"), shown.version)
                    if (shown.author.isNotBlank()) DetailLine(tr("作者"), shown.author)
                    if (shown.homepage.isNotBlank()) DetailLine(tr("主页"), shown.homepage)
                    DetailLine("ID", shown.id)
                    if (shown.description.isNotBlank()) DetailLine(tr("简介"), shown.description)
                    if (shown.notes.isNotEmpty()) DetailLine(tr("兼容性"), shown.notes.joinToString("\n"))
                }
                else -> {
                    val perms = shown.permissions
                    if (perms.isEmpty()) {
                        Text(
                            text = tr("此拓展不需要额外权限"),
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
            .squircleClip(12.dp)
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
