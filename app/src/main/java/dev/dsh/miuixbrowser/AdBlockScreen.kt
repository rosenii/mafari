package dev.dsh.miuixbrowser

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Blocklist
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置 → 高级 → 去广告：总开关、订阅源（可从外部 URL / 本地文件导入）、自定义规则、站点白名单。 */
@Composable
fun AdBlockScreen(settings: AppSettings, onBack: () -> Unit, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    val liquidBackdrop = rememberKyantBackdrop()
    val liquid = if (glass) liquidBackdrop else null
    val listState = rememberLazyListState()

    var showAdd by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    var showAllow by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AdSub?>(null) }
    var busyIds by remember { mutableStateOf(setOf<String>()) }
    var refreshingAll by remember { mutableStateOf(false) }
    var blockedTotal by remember { mutableLongStateOf(AdBlock.blockedTotal) }
    var showMore by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(Rect.Zero) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(Unit) {
        AdBlock.blockedFlow.collect {
            blockedTotal = it
            delay(1000)
        }
    }

    fun subscribe(url: String, name: String = "") {
        toast("正在下载规则…")
        scope.launch {
            AdBlock.subscribe(url, name)
                .onSuccess { toast("已订阅 ${it.name}（${it.ruleCount} 条规则）") }
                .onFailure { toast(it.message ?: "订阅失败") }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                toast("读取文件失败")
            } else {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
                AdBlock.importLocal(name, text)
                    .onSuccess { toast("已导入 ${it.name}（${it.ruleCount} 条规则）") }
                    .onFailure { toast(it.message ?: "导入失败") }
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
                title = "去广告",
                backdrop = backdrop,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                backButton = {
                    LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                },
                actions = {
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
        val topInset = innerPadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .edgeHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                val subs = AdBlock.subs.toList()
                val presets = AD_PRESETS.filter { p -> subs.none { it.url.equals(p.url, ignoreCase = true) } }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = topInset + 8.dp, bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    // 隐私报告：与指纹保护页同款矩形框
                    item {
                        val adOn = AdBlock.enabled
                        val activeSubs = subs.count { it.enabled }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "隐私报告",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = when {
                                        !adOn -> "未开启"
                                        AdBlock.compiling -> "加载规则中"
                                        else -> "保护中 · $activeSubs 个订阅"
                                    },
                                    fontSize = 12.sp,
                                    color = if (adOn) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text(
                                    text = "$blockedTotal",
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = " 次广告与跟踪请求已被拦截",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                                )
                            }
                            Text(
                                text = when {
                                    !adOn -> "开启后，这里会统计被拦截的广告与跟踪请求数"
                                    AdBlock.compiling -> "正在加载规则…"
                                    else -> "已加载 ${AdBlock.loadedRules} 条规则 · 累计统计"
                                },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                text = "统计的是被网络层拦截的请求；隐藏页面广告元素不计入",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            AppSwitchPreference(
                                title = "启用去广告",
                                summary = when {
                                    !AdBlock.enabled -> "已关闭"
                                    AdBlock.compiling -> "正在加载规则…"
                                    else -> "已加载 ${AdBlock.loadedRules} 条规则 · 累计拦截 $blockedTotal 次"
                                },
                                checked = AdBlock.enabled,
                                onCheckedChange = { AdBlock.updateEnabled(it) },
                            )
                            AppSwitchPreference(
                                title = "隐藏页面广告元素",
                                summary = "按规则隐藏页面里的广告位，对新打开的页面生效",
                                checked = AdBlock.cosmetic,
                                onCheckedChange = { AdBlock.updateCosmetic(it) },
                                enabled = AdBlock.enabled,
                            )
                            AppSwitchPreference(
                                title = "拦截跟踪与统计",
                                summary = "拦截第三方统计 / 追踪域名的请求",
                                checked = settings.blockTrackingScripts,
                                onCheckedChange = { settings.updateBlockTrackingScripts(it) },
                                enabled = AdBlock.enabled,
                            )
                            AppDropdownPreference(
                                backdrop = if (glass) backdrop else null,
                                title = "自动更新订阅",
                                items = listOf("手动", "每天", "每 3 天", "每周"),
                                selectedIndex = AdBlock.autoUpdate.coerceIn(0, 3),
                                onSelectedIndexChange = { AdBlock.updateAutoUpdate(it) },
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
                                summary = "关闭后无痕标签不拦截请求、不隐藏广告元素",
                                checked = settings.adblockInIncognito,
                                onCheckedChange = { settings.updateAdblockInIncognito(it) },
                                enabled = AdBlock.enabled,
                            )
                            AppSwitchPreference(
                                title = "在轻应用 / 桌面快捷方式中启用",
                                summary = "关闭后从桌面图标打开的页面不去广告，重新打开生效",
                                checked = settings.adblockInWebApp,
                                onCheckedChange = { settings.updateAdblockInWebApp(it) },
                                enabled = AdBlock.enabled,
                            )
                        }
                    }

                    item { SmallTitle(text = "订阅源") }
                    if (subs.isEmpty()) {
                        item {
                            Text(
                                text = "还没有订阅。点右上角「添加」导入订阅链接或本地规则文件，也可以直接选下面的推荐订阅。",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(subs.size) { index ->
                        val sub = subs[index]
                        AdSubCard(
                            sub = sub,
                            refreshing = sub.id in busyIds,
                            onToggle = { AdBlock.setSubEnabled(sub.id, it) },
                            onRefresh = {
                                if (sub.url.isBlank()) {
                                    toast("本地导入的规则没有更新地址")
                                } else if (sub.id !in busyIds) {
                                    busyIds = busyIds + sub.id
                                    scope.launch {
                                        AdBlock.refresh(sub.id)
                                            .onSuccess { toast("已更新（$it 条规则）") }
                                            .onFailure { toast(it.message ?: "更新失败") }
                                        busyIds = busyIds - sub.id
                                    }
                                }
                            },
                            onDelete = { deleting = sub },
                        )
                    }

                    if (presets.isNotEmpty()) {
                        item { SmallTitle(text = "推荐订阅") }
                        items(presets.size) { index ->
                            val p = presets[index]
                            PresetCard(p) { subscribe(p.url, p.name) }
                        }
                    }

                    item { SmallTitle(text = "规则管理") }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            ArrowPreference(
                                title = "自定义规则",
                                summary = if (AdBlock.customLines == 0) {
                                    "手写规则；长按网页元素「标记为广告」也会记在这里"
                                } else {
                                    "${AdBlock.customLines} 条规则"
                                },
                                onClick = { showCustom = true },
                            )
                            ArrowPreference(
                                title = "站点白名单",
                                summary = if (AdBlock.allowHosts.isEmpty()) {
                                    "这些站点不执行去广告"
                                } else {
                                    "${AdBlock.allowHosts.size} 个站点不执行去广告"
                                },
                                onClick = { showAllow = true },
                            )
                        }
                    }
                    item {
                        Text(
                            text = "规则来自第三方订阅源。网络拦截只作用于页面的子资源请求，不影响页面本身的打开；" +
                                "不支持正则规则、重定向 / 改写类选项和扩展选择器，遇到会自动跳过。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }

        AddSubDialog(
            show = showAdd,
            backdrop = if (glass) backdrop else null,
            onDismiss = { showAdd = false },
            onSubscribe = { url ->
                showAdd = false
                subscribe(url)
            },
            onPickFile = {
                showAdd = false
                filePicker.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            },
        )

        CustomRulesDialog(
            show = showCustom,
            backdrop = if (glass) backdrop else null,
            onDismiss = { showCustom = false },
            onSave = {
                AdBlock.saveCustom(it)
                showCustom = false
            },
        )

        AllowListDialog(
            show = showAllow,
            backdrop = if (glass) backdrop else null,
            onDismiss = { showAllow = false },
        )

        val target = deleting
        AppGlassDialog(
            show = target != null,
            title = "删除订阅",
            summary = target?.name,
            backdrop = if (glass) backdrop else null,
            onDismissRequest = { deleting = null },
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppDialogButton(onClick = { deleting = null }, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        target?.let { AdBlock.removeSub(it.id) }
                        deleting = null
                    },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Destructive,
                ) {
                    Text(text = "删除", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
    AppGlassPopup(
        show = showMore,
        onDismissRequest = { showMore = false },
        anchorBounds = Rect(moreAnchor.left, moreAnchor.bottom, moreAnchor.right, moreAnchor.bottom),
        backdrop = if (glass) backdrop else null,
    ) {
        GlassPopupItem(
            text = "添加订阅",
            onClick = {
                showMore = false
                showAdd = true
            },
            icon = MiuixIcons.Glass.Add,
        )
        GlassPopupItem(
            text = if (refreshingAll) "更新中…" else "全部更新",
            enabled = !refreshingAll,
            onClick = {
                showMore = false
                if (AdBlock.subs.none { it.enabled && it.url.isNotBlank() }) {
                    toast("没有可更新的订阅")
                } else {
                    refreshingAll = true
                    scope.launch {
                        val (ok, total) = AdBlock.refreshAll()
                        refreshingAll = false
                        toast("已更新 $ok/$total 个订阅")
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
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun RuleIcon() {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.Blocklist,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AdSubCard(
    sub: AdSub,
    refreshing: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RuleIcon()
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(sub.name, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                val line = when {
                    refreshing -> "正在更新…"
                    sub.error.isNotBlank() -> "更新失败：" + sub.error
                    sub.updatedAt == 0L -> "尚未下载，点右侧刷新"
                    else -> "${sub.ruleCount} 条规则 · 更新于 " + formatTime(sub.updatedAt)
                }
                Text(
                    text = line,
                    fontSize = 12.sp,
                    color = if (sub.error.isNotBlank() && !refreshing) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 2,
                )
                Text(
                    text = sub.url.ifBlank { "本地导入" },
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            AppSwitch(checked = sub.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onRefresh) {
                Icon(imageVector = MiuixIcons.Glass.Refresh, contentDescription = "更新订阅")
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = MiuixIcons.Glass.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun PresetCard(preset: AdPreset, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RuleIcon()
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preset.summary,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(8.dp))
            AppTextButton(
                text = "订阅",
                onClick = onAdd,
                primary = true,
                inPage = true,
                minHeight = 30.dp,
            )
        }
    }
}

@Composable
private fun AddSubDialog(
    show: Boolean,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onSubscribe: (String) -> Unit,
    onPickFile: () -> Unit,
) {
    var url by remember(show) { mutableStateOf("") }
    AppGlassDialog(
        show = show,
        title = "添加订阅",
        summary = "支持 Adblock Plus / uBlock / AdGuard 规则与 hosts 格式",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = "订阅地址（http / https）",
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
                    onClick = { if (url.isNotBlank()) onSubscribe(url.trim()) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "订阅", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun CustomRulesDialog(
    show: Boolean,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(show) { mutableStateOf(if (show) AdBlock.readCustom() else "") }
    AppGlassDialog(
        show = show,
        title = "自定义规则",
        summary = "每行一条。例：||ads.example.com^ 或 example.com##.banner",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                label = "规则",
                maxLines = 12,
            )
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { onSave(text) },
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
private fun AllowListDialog(show: Boolean, backdrop: Backdrop?, onDismiss: () -> Unit) {
    HostListDialog(
        show = show,
        backdrop = backdrop,
        title = "站点白名单",
        summary = "白名单站点（含子域名）不拦截请求、不隐藏元素",
        emptyText = "还没有白名单站点",
        hosts = AdBlock.allowHosts,
        onAdd = { AdBlock.allowSite(it) },
        onRemove = { AdBlock.disallowSite(it) },
        onDismiss = onDismiss,
    )
}

/** 域名列表编辑弹窗：去广告白名单与指纹保护豁免列表共用。 */
@Composable
internal fun HostListDialog(
    show: Boolean,
    backdrop: Backdrop?,
    title: String,
    summary: String,
    emptyText: String,
    hosts: List<String>,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var host by remember(show) { mutableStateOf("") }
    AppGlassDialog(
        show = show,
        title = title,
        summary = summary,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.weight(1f),
                    label = "域名，如 example.com",
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                AppTextButton(
                    text = "添加",
                    onClick = {
                        val input = host.trim().lowercase().removePrefix("https://").removePrefix("http://")
                            .substringBefore('/')
                        if (onAdd(input)) {
                            host = ""
                        } else {
                            Toast.makeText(context, "域名格式不正确", Toast.LENGTH_SHORT).show()
                        }
                    },
                    primary = true,
                    minHeight = 36.dp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            ) {
                if (hosts.isEmpty()) {
                    Text(
                        text = emptyText,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                hosts.toList().forEach { h ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(h, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(h) }) {
                            Icon(imageVector = MiuixIcons.Glass.Delete, contentDescription = "移除")
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            AppDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(text = "完成", style = MiuixTheme.textStyles.button)
            }
        }
    }
}
