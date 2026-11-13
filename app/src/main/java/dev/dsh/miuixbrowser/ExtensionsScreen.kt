package dev.dsh.miuixbrowser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import top.yukonga.miuix.kmp.icon.glass.File
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll

/** 脚本页：布局与「去广告」一致 —— 顶部设置卡（自动更新）、已安装脚本、推荐脚本；右上角菜单可导入（URL / 本地文件）、新建、全部更新。 */
@Composable
fun ExtensionsScreen(store: ExtensionStore, settings: AppSettings, onBack: () -> Unit, blurEnabled: Boolean = true) {
    var editing by remember { mutableStateOf<UserScript?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var busyIds by remember { mutableStateOf(setOf<String>()) }
    var installingUrls by remember { mutableStateOf(setOf<String>()) }
    var importBusy by remember { mutableStateOf(false) }
    var updatingAll by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<UserScript?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val liquid = if (rememberGlassActive(blurEnabled)) liquidBackdrop else null
    val glass = rememberGlassActive(blurEnabled)
    val deleteTarget = deleting
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    var showLockInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(Rect.Zero) }

    fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** 导入 URL：脚本 → 安装；去广告规则 → 交给「去广告」。 */
    fun importUrl(url: String) {
        if (importBusy) return
        importBusy = true
        toast("正在下载…")
        scope.launch {
            val result = fetchUserScriptText(url, allowFilterList = true)
            result.onSuccess { text ->
                if (AdParser.looksLikeFilterList(text)) {
                    AdBlock.subscribe(url)
                        .onSuccess { toast("这是去广告规则，已加入「设置 → 高级 → 去广告」") }
                        .onFailure { toast(it.message ?: "订阅失败") }
                } else {
                    val script = store.installText(url, text)
                    toast("已导入 ${script.name}")
                }
            }.onFailure { toast(it.message ?: "下载失败，检查链接") }
            importBusy = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
            when {
                text == null -> toast("读取文件失败")
                text.contains("==UserScript==") -> {
                    val parsedName = parseUserScript(text).first
                    store.add(if (parsedName.isBlank()) fileName else "", text)
                    toast("已导入脚本")
                }
                AdParser.looksLikeFilterList(text) -> {
                    AdBlock.importLocal(fileName, text)
                        .onSuccess { toast("这是去广告规则，已导入「设置 → 高级 → 去广告」") }
                        .onFailure { toast(it.message ?: "导入失败") }
                }
                else -> toast("这不是有效的油猴脚本")
            }
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = "脚本",
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
            )
        },
    ) { innerPadding ->
        EncryptionInfoDialog(
            show = showLockInfo,
            onDismiss = { showLockInfo = false },
            backdrop = backdrop,
            liquid = liquid,
            scope = "脚本 GM 数据：SQLCipher 整库 AES-256",
        )
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
                val scripts = store.items.toList()
                val missingBuiltins = BUILTIN_SCRIPTS.filter { builtin ->
                    store.items.none { item ->
                        builtin.userScriptUrl.isNotBlank() && item.sourceUrl == builtin.userScriptUrl
                    }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = topInset + 8.dp, bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            AppDropdownPreference(
                                backdrop = if (glass) backdrop else null,
                                title = "自动更新脚本",
                                items = listOf("手动", "每天", "每 3 天", "每周"),
                                selectedIndex = store.autoUpdate.coerceIn(0, 3),
                                onSelectedIndexChange = { store.updateAutoUpdate(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "启用范围") }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            AppSwitchPreference(
                                title = "在无痕浏览中启用",
                                summary = "关闭后无痕标签不运行脚本，切换后刷新页面生效",
                                checked = settings.scriptsInIncognito,
                                onCheckedChange = { settings.updateScriptsInIncognito(it) },
                            )
                            AppSwitchPreference(
                                title = "在轻应用 / 桌面快捷方式中启用",
                                summary = "关闭后从桌面图标打开的页面不运行脚本，重新打开生效",
                                checked = settings.scriptsInWebApp,
                                onCheckedChange = { settings.updateScriptsInWebApp(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = "已安装脚本") }
                    if (scripts.isEmpty()) {
                        item {
                            Text(
                                text = "还没有脚本。点右上角「导入脚本」输入链接或选择本地文件，也可以直接选下面的推荐脚本。",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(scripts.size) { index ->
                        val script = scripts[index]
                        // 版本号 / 描述 / 图标回退站点都从脚本头自动识别；code 不变就不重复解析
                        val info = remember(script.code) {
                            UserscriptEngine.parse(script.code).let {
                                ScriptListInfo(
                                    version = it.version.trim(),
                                    description = it.description,
                                    iconHost = UserscriptEngine.fallbackIconHost(it),
                                )
                            }
                        }
                        ScriptCard(
                            script = script,
                            info = info,
                            refreshing = script.id in busyIds,
                            onEdit = { editing = script },
                            onToggle = { store.setEnabled(script.id, it) },
                            onRefresh = {
                                if (script.id !in busyIds) {
                                    busyIds = busyIds + script.id
                                    scope.launch {
                                        store.refresh(script.id)
                                            .onSuccess { changed -> toast(if (changed) "已更新 ${script.name}" else "${script.name} 已是最新") }
                                            .onFailure { toast(it.message ?: "更新失败") }
                                        busyIds = busyIds - script.id
                                    }
                                }
                            },
                            onDelete = { deleting = script },
                        )
                    }

                    if (missingBuiltins.isNotEmpty()) {
                        item { SmallTitle(text = "推荐脚本") }
                        items(missingBuiltins.size) { index ->
                            val builtin = missingBuiltins[index]
                            BuiltinScriptCard(builtin, installing = builtin.userScriptUrl in installingUrls) {
                                if (builtin.userScriptUrl.isBlank()) {
                                    store.add(builtin.name, builtin.code)
                                } else if (builtin.userScriptUrl !in installingUrls) {
                                    installingUrls = installingUrls + builtin.userScriptUrl
                                    scope.launch {
                                        store.install(builtin.userScriptUrl)
                                            .onSuccess { toast("已添加 " + builtin.name) }
                                            .onFailure { toast(it.message ?: "下载失败，检查网络") }
                                        installingUrls = installingUrls - builtin.userScriptUrl
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "脚本在页面加载完成后注入运行，可在每个页面的「脚本」菜单里临时开关。" +
                                "首次打开 app 会自动安装推荐脚本；有来源地址的脚本会按上面设置的间隔在后台自动更新。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                        )
                    }
                }
            }

            // 右下圆形添加按钮：新建脚本
            LiquidGlassFab(
                onClick = { creating = true },
                backdrop = liquid,
                primary = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.Add,
                    contentDescription = "新建脚本",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        } // end outer Box

        AppGlassDialog(
            show = deleteTarget != null,
            title = "删除脚本",
            summary = deleteTarget?.name,
            backdrop = backdrop,
            onDismissRequest = { deleting = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(
                    onClick = { deleting = null },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        deleteTarget?.let { store.remove(it.id) }
                        deleting = null
                    },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Destructive,
                ) {
                    Text(text = "删除", style = MiuixTheme.textStyles.button)
                }
            }
        }

        ImportDialog(
            show = importing,
            backdrop = backdrop,
            onDismiss = { importing = false },
            onImport = { url ->
                importing = false
                importUrl(url)
            },
            onPickFile = {
                importing = false
                filePicker.launch(arrayOf("text/*", "application/javascript", "application/octet-stream", "*/*"))
            },
        )

        ScriptDialog(
            show = creating || editing != null,
            initial = editing,
            backdrop = backdrop,
            onDismiss = {
                creating = false
                editing = null
            },
            onConfirmMatch = { name, code, enabled, match ->
                val current = editing
                if (current == null) {
                    store.add(name, code, match, "")
                } else {
                    store.update(current.id, name, code, enabled, match)
                }
                creating = false
                editing = null
            },
        )
    }
    // 右上角菜单：与 Scaffold 同级、放在最后 → 位于最上层（盖住顶栏）
    AppGlassPopup(
        show = showMore,
        onDismissRequest = { showMore = false },
        anchorBounds = Rect(moreAnchor.left, moreAnchor.bottom, moreAnchor.right, moreAnchor.bottom),
        backdrop = if (glass) backdrop else null,
    ) {
        GlassPopupItem(
            text = "导入脚本",
            onClick = {
                showMore = false
                importing = true
            },
            icon = MiuixIcons.Glass.Download,
        )
        GlassPopupItem(
            text = if (updatingAll) "更新中…" else "全部更新",
            enabled = !updatingAll,
            onClick = {
                showMore = false
                if (store.items.none { it.sourceUrl.isNotBlank() }) {
                    toast("没有可更新的脚本")
                } else {
                    updatingAll = true
                    scope.launch {
                        val (ok, total) = store.refreshAll()
                        updatingAll = false
                        toast("已更新 $ok/$total 个脚本")
                    }
                }
            },
            icon = MiuixIcons.Glass.Refresh,
        )
    }
    }
    }
}

private fun formatTime(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
private fun ScriptCard(
    script: UserScript,
    info: ScriptListInfo,
    refreshing: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScriptIcon(script.icon, script.sourceUrl, info.iconHost)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).clickable { onEdit() }) {
                // 标题 + 紧跟的加粗版本号：名字过长时只截断名字，版本号始终完整显示
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = script.name,
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (info.version.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (info.version.startsWith("v", ignoreCase = true)) info.version else "v${info.version}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                val failed = script.error.isNotBlank() && !refreshing
                Text(
                    text = when {
                        refreshing -> "正在更新…"
                        failed -> "更新失败：" + script.error
                        script.sourceUrl.isBlank() -> "本地脚本"
                        script.updatedAt == 0L -> "尚未更新，点右侧刷新"
                        else -> "更新于 " + formatTime(script.updatedAt)
                    },
                    fontSize = 12.sp,
                    color = if (failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
                // 副标题优先用元数据 @description，没有再回退来源地址 / 代码预览
                val subtitle = info.description
                    .ifBlank { script.sourceUrl }
                    .ifBlank { script.code.take(60).replace("\n", " ") }
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            AppSwitch(checked = script.enabled, onCheckedChange = onToggle)
            if (script.sourceUrl.isNotBlank()) {
                IconButton(onClick = onRefresh) {
                    Icon(imageVector = MiuixIcons.Glass.Refresh, contentDescription = "更新")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = MiuixIcons.Glass.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun ScriptDialog(
    show: Boolean,
    initial: UserScript?,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onConfirmMatch: (String, String, Boolean, String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var code by remember(initial) { mutableStateOf(initial?.code ?: "") }
    var match by remember(initial) { mutableStateOf(initial?.match ?: "") }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }

    AppGlassDialog(
        show = show,
        title = if (initial == null) "新建脚本" else "编辑脚本",
        summary = "JavaScript 会在页面加载完成后执行",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = "名称",
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = match,
                onValueChange = { match = it },
                modifier = Modifier.fillMaxWidth(),
                label = "匹配（可留空，如 *://*.example.com/*）",
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                label = "JavaScript",
                maxLines = 8,
            )
            if (initial != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    AppSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { if (code.isNotBlank()) onConfirmMatch(name, code, enabled, match) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "保存", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onPickFile: () -> Unit,
) {
    var url by remember(show) { mutableStateOf("") }
    AppGlassDialog(
        show = show,
        title = "导入脚本",
        summary = "支持 .user.js 链接或本地文件；去广告规则会自动转交「去广告」",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = "脚本地址（http / https，.user.js）",
                singleLine = true,
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
                    onClick = { if (url.isNotBlank()) onImport(url.trim()) },
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
private fun BuiltinScriptCard(script: BuiltinScript, installing: Boolean, onAdd: (BuiltinScript) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.File,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(script.name, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = script.description,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(8.dp))
            AppTextButton(
                text = if (installing) "安装中…" else "安装",
                enabled = !installing,
                onClick = { onAdd(script) },
                primary = true,
                inPage = true,
                minHeight = 30.dp,
            )
        }
    }
}

/** 脚本列表每一项用到的、从脚本头解析出的信息 */
private data class ScriptListInfo(val version: String, val description: String, val iconHost: String)

@Composable
private fun ScriptIcon(url: String, baseUrl: String, fallbackHost: String) {
    val icon = rememberRemoteIcon(url, baseUrl, fallbackHost)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
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
                imageVector = MiuixIcons.Glass.File,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
