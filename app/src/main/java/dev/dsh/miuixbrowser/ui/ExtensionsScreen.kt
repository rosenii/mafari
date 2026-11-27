/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.AdParser
import dev.dsh.miuixbrowser.browser.rememberRemoteIcon
import dev.dsh.miuixbrowser.crypto.EncryptionInfoDialog
import dev.dsh.miuixbrowser.crypto.EncryptionLockButton
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.script.ExtensionStore
import dev.dsh.miuixbrowser.script.UserScript
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.script.fetchUserScriptText
import dev.dsh.miuixbrowser.script.parseUserScript
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

/** 脚本页：布局与「去广告」一致 —— 顶部设置卡（自动更新）、已安装脚本；右上角菜单可导入（URL / 本地文件）、新建、全部更新。 */
@Composable
fun ExtensionsScreen(store: ExtensionStore, settings: AppSettings, onBack: () -> Unit, blurEnabled: Boolean = true) {
    var editing by remember { mutableStateOf<UserScript?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var busyIds by remember { mutableStateOf(setOf<String>()) }
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
        toast(tr("正在下载…"))
        scope.launch {
            val result = fetchUserScriptText(url, allowFilterList = true)
            result.onSuccess { text ->
                if (AdParser.looksLikeFilterList(text)) {
                    AdBlock.subscribe(url)
                        .onSuccess { toast(tr("这是去广告规则，已加入「设置 → 高级 → 去广告」")) }
                        .onFailure { toast(it.message ?: tr("订阅失败")) }
                } else {
                    val script = store.installText(url, text)
                    toast(tr("已导入 {0}", script.name))
                }
            }.onFailure { toast(it.message ?: tr("下载失败，检查链接")) }
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
                text == null -> toast(tr("读取文件失败"))
                text.contains("==UserScript==") -> {
                    val parsedName = parseUserScript(text).first
                    store.add(if (parsedName.isBlank()) fileName else "", text)
                    toast(tr("已导入脚本"))
                }
                AdParser.looksLikeFilterList(text) -> {
                    AdBlock.importLocal(fileName, text)
                        .onSuccess { toast(tr("这是去广告规则，已导入「设置 → 高级 → 去广告」")) }
                        .onFailure { toast(it.message ?: tr("导入失败")) }
                }
                else -> toast(tr("这不是有效的油猴脚本"))
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
                title = tr("脚本"),
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
                            contentDescription = tr("更多"),
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
            scope = tr("脚本 GM 数据：SQLCipher 整库 AES-256"),
        )
        val topInset = innerPadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .scrollEndHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .pageBackground(),
            ) {
                val scripts = store.items.toList()
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
                                title = tr("自动更新脚本"),
                                items = listOf(tr("手动"), tr("每天"), tr("每 3 天"), tr("每周")),
                                selectedIndex = store.autoUpdate.coerceIn(0, 3),
                                onSelectedIndexChange = { store.updateAutoUpdate(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = tr("启用范围")) }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            AppSwitchPreference(
                                title = tr("在无痕浏览中启用"),
                                summary = tr("关闭后无痕标签不运行脚本，切换后刷新页面生效"),
                                checked = settings.scriptsInIncognito,
                                onCheckedChange = { settings.updateScriptsInIncognito(it) },
                            )
                            AppSwitchPreference(
                                title = tr("在轻应用 / 桌面快捷方式中启用"),
                                summary = tr("关闭后从桌面图标打开的页面不运行脚本，重新打开生效"),
                                checked = settings.scriptsInWebApp,
                                onCheckedChange = { settings.updateScriptsInWebApp(it) },
                            )
                        }
                    }

                    item { SmallTitle(text = tr("已安装脚本")) }
                    if (scripts.isEmpty()) {
                        item {
                            Text(
                                text = tr("还没有脚本。点右上角「导入脚本」输入链接或选择本地文件。"),
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
                                            .onSuccess { changed -> toast(if (changed) tr("已更新 {0}", script.name) else tr("{0} 已是最新", script.name)) }
                                            .onFailure { toast(it.message ?: tr("更新失败")) }
                                        busyIds = busyIds - script.id
                                    }
                                }
                            },
                            onDelete = { deleting = script },
                        )
                    }

                    item {
                        Text(
                            text = tr("脚本在页面加载完成后注入运行，可在每个页面的「脚本」菜单里临时开关。") +
                                tr("有来源地址的脚本会按上面设置的间隔在后台自动更新。"),
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
                    contentDescription = tr("新建脚本"),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        } // end outer Box

        AppGlassDialog(
            show = deleteTarget != null,
            title = tr("删除脚本"),
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
                    Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        deleteTarget?.let { store.remove(it.id) }
                        deleting = null
                    },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Destructive,
                ) {
                    Text(text = tr("删除"), style = MiuixTheme.textStyles.button)
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
            text = tr("导入脚本"),
            onClick = {
                showMore = false
                importing = true
            },
            icon = MiuixIcons.Glass.Download,
        )
        GlassPopupItem(
            text = if (updatingAll) tr("更新中…") else tr("全部更新"),
            enabled = !updatingAll,
            onClick = {
                showMore = false
                if (store.items.none { it.sourceUrl.isNotBlank() }) {
                    toast(tr("没有可更新的脚本"))
                } else {
                    updatingAll = true
                    scope.launch {
                        val (ok, total) = store.refreshAll()
                        updatingAll = false
                        toast(tr("已更新 {0}/{1} 个脚本", ok, total))
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
                        refreshing -> tr("正在更新…")
                        failed -> tr("更新失败：") + script.error
                        script.sourceUrl.isBlank() -> tr("本地脚本")
                        script.updatedAt == 0L -> tr("尚未更新，点右侧刷新")
                        else -> tr("更新于 ") + formatTime(script.updatedAt)
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
                    Icon(imageVector = MiuixIcons.Glass.Refresh, contentDescription = tr("更新"))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = MiuixIcons.Glass.Delete, contentDescription = tr("删除"))
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
        title = if (initial == null) tr("新建脚本") else tr("编辑脚本"),
        summary = tr("JavaScript 会在页面加载完成后执行"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = tr("名称"),
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = match,
                onValueChange = { match = it },
                modifier = Modifier.fillMaxWidth(),
                label = tr("匹配（可留空，如 *://*.example.com/*）"),
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
                    Text(tr("启用"), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
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
                    Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { if (code.isNotBlank()) onConfirmMatch(name, code, enabled, match) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = tr("保存"), style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/**
 * 「输入链接 / 选本地文件」导入弹窗。脚本页和书架的「从订阅源导入」共用；
 * 标题、说明、输入框提示默认是脚本页的文案，其他入口传自己的。
 */
@Composable
internal fun ImportDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onPickFile: () -> Unit,
    title: String = tr("导入脚本"),
    summary: String = tr("支持 .user.js 链接或本地文件；去广告规则会自动转交「去广告」"),
    urlLabel: String = tr("脚本地址（http / https，.user.js）"),
) {
    var url by remember(show) { mutableStateOf("") }
    AppGlassDialog(
        show = show,
        title = title,
        summary = summary,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = urlLabel,
                singleLine = true,
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
                    onClick = { if (url.isNotBlank()) onImport(url.trim()) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = tr("导入"), style = MiuixTheme.textStyles.button)
                }
            }
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
            .squircleClip(9.dp)
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
