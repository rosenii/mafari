package dev.dsh.miuixbrowser

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 导入结果弹窗的内容。 */
private class ImportReport(val title: String, val lines: List<String>, val success: Boolean)

/**
 * 设置 → 隐私与安全 → 密码。
 *
 * 功能：从其他浏览器 / 密码管理器导出的 CSV 导入、加密存储（见 [PasswordDb]）、Firefox 同步（gecko / sync_lite 版，见 [FxaPasswordBridge]）。
 * 「保存密码 / 自动填充 / 填充前验证身份」三个开关见 [PasswordPrefs]，生效在 Gecko 标签页（[GeckoLogins] / [GeckoPromptHandler]）。
 * 查看 / 复制密码要先通过系统验证（生物识别或锁屏密码）；本页显示期间禁止截屏 / 录屏。
 */
@Composable
fun PasswordScreen(onBack: () -> Unit, onOpenFxa: () -> Unit = {}, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val bd = if (glass) backdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    var loaded by remember { mutableStateOf<List<PasswordDb.Item>?>(null) } // null = 还在加载
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showLockInfo by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var detail by remember { mutableStateOf<PasswordDb.Item?>(null) }
    var shownPassword by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<PasswordDb.Item?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var fxaSyncing by remember { mutableStateOf(false) }
    var saveOn by remember { mutableStateOf(PasswordPrefs.saveEnabled(context)) }
    var fillOn by remember { mutableStateOf(PasswordPrefs.fillEnabled(context)) }
    var verifyOn by remember { mutableStateOf(PasswordPrefs.verifyBeforeFill(context)) }
    val autofillAvailable = EngineSupport.GECKO_BUNDLED // 只有 Gecko 内核的标签页接了；无内核版开关置灰

    // 本页显示期间禁止截屏 / 录屏（页面上会出现站点、用户名，点开还会出现明文密码）
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // 同步（Firefox 密码）拉下来 / 推出去后本地记录会变：PasswordDb.version 变化也重新读取
    LaunchedEffect(refresh, PasswordDb.version) {
        loaded = withContext(Dispatchers.IO) { HistoryDb.get(context)?.let { PasswordDb.list(it) } ?: emptyList() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            report = withContext(Dispatchers.IO) { runImport(context, uri) }
            importing = false
            refresh++
        }
    }

    /** 验证身份后解开单条密码交给 [block]。 */
    fun withSecret(guid: String, block: (PasswordEntry) -> Unit) {
        BiometricGate.authenticate(context, "验证身份", "验证后才能查看或复制密码") { ok ->
            if (!ok) return@authenticate
            scope.launch {
                val e = withContext(Dispatchers.IO) { HistoryDb.get(context)?.let { PasswordDb.reveal(context, it, guid) } }
                if (e == null) Toast.makeText(context, "无法解密这条密码（密钥已失效或数据损坏）", Toast.LENGTH_LONG).show() else block(e)
            }
        }
    }

    /** 点同步行右侧刷新：同步并 toast 结果。 */
    fun syncFirefox() {
        if (!EngineSupport.SYNC_BUNDLED || fxaSyncing) return
        scope.launch {
            fxaSyncing = true
            val msg = FxaSync.syncAndReport()
            fxaSyncing = false
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            refresh++
        }
    }

    val all = loaded.orEmpty()
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) all else all.filter { it.origin.lowercase().contains(q) || it.username.lowercase().contains(q) }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = "密码",
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = { LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack) },
                    actions = { EncryptionLockButton(onClick = { showLockInfo = true }, liquid = liquid, topScrolled = topScrolled) },
                )
            },
        ) { innerPadding ->
            EncryptionInfoDialog(
                show = showLockInfo,
                onDismiss = { showLockInfo = false },
                backdrop = backdrop,
                liquid = liquid,
                scope = "密码：SQLCipher 整库 AES-256，密码与备注再用加密套件逐条加密",
            )
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .kyantLayerBackdrop(liquidBackdrop)
                        .edgeHaptic()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .background(MiuixTheme.colorScheme.surface),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    // ① 功能开关（只作用于 Gecko 内核的标签页；系统 WebView 的网页仍用系统自动填充服务）
                    item {
                        SmallTitle(text = "自动填充")
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                            AppSwitchPreference(
                                title = "保存密码",
                                summary = if (autofillAvailable) "登录后询问是否保存或更新 · 无痕标签页不保存" else "仅 Gecko 内核可用",
                                checked = saveOn && autofillAvailable,
                                onCheckedChange = {
                                    saveOn = it
                                    PasswordPrefs.set(context, PasswordPrefs.KEY_SAVE, it)
                                },
                                enabled = autofillAvailable,
                            )
                            AppSwitchPreference(
                                title = "自动填充",
                                summary = if (autofillAvailable) "点登录框时选择已保存的账号并填入" else "仅 Gecko 内核可用",
                                checked = fillOn && autofillAvailable,
                                onCheckedChange = {
                                    fillOn = it
                                    PasswordPrefs.set(context, PasswordPrefs.KEY_FILL, it)
                                },
                                enabled = autofillAvailable,
                            )
                            AppSwitchPreference(
                                title = "填充前验证身份",
                                summary = "填入密码前先通过生物识别或锁屏密码",
                                checked = verifyOn,
                                onCheckedChange = {
                                    verifyOn = it
                                    PasswordPrefs.set(context, PasswordPrefs.KEY_VERIFY, it)
                                },
                                enabled = autofillAvailable && fillOn,
                            )
                        }
                    }

                    // ② 导入与同步
                    item {
                        SmallTitle(text = "导入与同步")
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                            ArrowPreference(
                                title = if (importing) "正在导入…" else "从 CSV 文件导入",
                                summary = "Chrome · Edge · Firefox · Safari · Bitwarden · 1Password · LastPass · KeePass 导出的密码文件",
                                onClick = {
                                    if (!importing) picker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel", "*/*"))
                                },
                            )
                            if (EngineSupport.SYNC_BUNDLED) {
                                val fxa = FxaSync.ui
                                val signedIn = fxa as? FxaUi.SignedIn
                                if (signedIn != null) {
                                    // 已登录：右侧是刷新图标，点它同步 Firefox 密码并 toast 结果
                                    BasicComponent(
                                        title = "Firefox 同步",
                                        summary = "已登录" + signedIn.email.takeIf { it.isNotBlank() }?.let { "•$it" }.orEmpty(),
                                        endActions = {
                                            Icon(
                                                imageVector = AppIcons.Refresh,
                                                contentDescription = "同步 Firefox 密码",
                                                modifier = Modifier.size(22.dp),
                                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (fxaSyncing || FxaSync.syncing) 0.4f else 1f),
                                            )
                                        },
                                        onClick = { syncFirefox() },
                                        enabled = !fxaSyncing && !FxaSync.syncing,
                                    )
                                } else {
                                    ArrowPreference(
                                        title = "Firefox 同步",
                                        summary = "未登录，点击前往Firefox帐户同步页登录",
                                        onClick = onOpenFxa,
                                    )
                                }
                            }
                        }
                    }

                    // ③ 已保存的密码
                    item {
                        SmallTitle(text = if (loaded == null) "已保存的密码" else "已保存的密码（${all.size}）")
                        if (all.size > 5) {
                            TextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                label = "搜索网站或用户名",
                                singleLine = true,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (loaded != null && all.isEmpty()) {
                        item {
                            Text(
                                text = "还没有保存的密码。可以从其他浏览器或密码管理器导出 CSV 后导入。",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    } else if (loaded != null && shown.isEmpty()) {
                        item {
                            Text(
                                text = "没有匹配的密码",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(shown, key = { it.guid }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        shownPassword = null
                                        detail = item
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = PasswordCsv.displayHost(item.origin) + (item.httpRealm?.let { " · $it" } ?: ""),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = item.username.ifEmpty { "（无用户名）" },
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // ④ 清空 + 说明
                    if (all.isNotEmpty()) {
                        item {
                            SmallTitle(text = "管理")
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                                ArrowPreference(
                                    title = "清除所有密码",
                                    summary = "删除本机保存的全部 ${all.size} 条密码，不可恢复",
                                    onClick = { confirmClear = true },
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            text = "密码保存在本机的加密数据库里，数据库密钥由系统密钥库保护，密码字段还会再经加密套件逐条加密；" +
                                "查看或复制密码前需要通过系统验证。导入用的 CSV 文件里是明文密码，导入完成后请及时删除。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }

                // ---- 导入结果
                val r = report
                AppGlassDialog(show = r != null, title = r?.title, backdrop = bd, onDismissRequest = { report = null }) {
                    Column(Modifier.fillMaxWidth()) {
                        r?.lines?.forEach {
                            Text(text = it, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        if (r?.success == true) {
                            Text(
                                text = "CSV 文件里是明文密码，请现在就删除它（也包括下载目录和云盘里的副本）。",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        AppDialogButton(
                            onClick = { report = null },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            role = GlassDialogButtonRole.Primary,
                        ) { Text(text = "好", style = MiuixTheme.textStyles.button) }
                    }
                }

                // ---- 单条详情
                val d = detail
                AppGlassDialog(
                    show = d != null,
                    title = d?.let { PasswordCsv.displayHost(it.origin) },
                    summary = d?.let { it.origin },
                    backdrop = bd,
                    onDismissRequest = { detail = null; shownPassword = null },
                ) {
                    if (d != null) Column(Modifier.fillMaxWidth()) {
                        // 用户名：右侧复制（不需要验证）
                        DetailLine(
                            label = "用户名",
                            value = d.username.ifEmpty { "（无）" },
                        ) {
                            if (d.username.isNotEmpty()) DetailIconButton(AppIcons.Copy, "复制用户名") { copySecret(context, "用户名", d.username) }
                        }
                        // 密码：右侧「眼睛 + 复制」，显示和复制都要先通过生物验证；眼睛闭合 = 已隐藏，睁开 = 已显示，点一下切换
                        DetailLine(
                            label = "密码",
                            value = shownPassword ?: "••••••••••",
                        ) {
                            DetailIconButton(if (shownPassword != null) AppIcons.Eye else AppIcons.EyeOff, if (shownPassword != null) "隐藏密码" else "显示密码") {
                                if (shownPassword != null) shownPassword = null
                                else withSecret(d.guid) { shownPassword = it.password }
                            }
                            DetailIconButton(AppIcons.Copy, "复制密码") {
                                withSecret(d.guid) { copySecret(context, "密码", it.password, sensitive = true) }
                            }
                        }
                        if (d.timePasswordChanged > 0) DetailLine("修改时间", formatTime(d.timePasswordChanged))
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppDialogButton(
                                onClick = { confirmDelete = d },
                                modifier = Modifier.weight(1f),
                            ) { Text(text = "删除", style = MiuixTheme.textStyles.button, color = MiuixTheme.colorScheme.error) }
                            AppDialogButton(
                                onClick = { detail = null; shownPassword = null },
                                modifier = Modifier.weight(1f),
                                role = GlassDialogButtonRole.Primary,
                            ) { Text(text = "完成", style = MiuixTheme.textStyles.button) }
                        }
                    }
                }

                // ---- 删除确认
                val del = confirmDelete
                AppGlassDialog(
                    show = del != null,
                    title = "删除这条密码？",
                    summary = del?.let { PasswordCsv.displayHost(it.origin) + " · " + it.username.ifEmpty { "（无用户名）" } },
                    backdrop = bd,
                    onDismissRequest = { confirmDelete = null },
                ) {
                    ConfirmButtons(
                        confirmText = "删除",
                        onCancel = { confirmDelete = null },
                        onConfirm = {
                            val g = del?.guid
                            confirmDelete = null
                            detail = null
                            shownPassword = null
                            if (g != null) scope.launch {
                                withContext(Dispatchers.IO) { HistoryDb.get(context)?.let { PasswordDb.delete(it, g) } }
                                refresh++
                            }
                        },
                    )
                }

                // ---- 清空确认
                AppGlassDialog(
                    show = confirmClear,
                    title = "清除所有密码？",
                    summary = "本机保存的全部 ${all.size} 条密码将被删除，不可恢复",
                    backdrop = bd,
                    onDismissRequest = { confirmClear = false },
                ) {
                    ConfirmButtons(
                        confirmText = "全部清除",
                        onCancel = { confirmClear = false },
                        onConfirm = {
                            confirmClear = false
                            scope.launch {
                                withContext(Dispatchers.IO) { HistoryDb.get(context)?.let { PasswordDb.clear(it) } }
                                refresh++
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 详情里的一行：左边标签 + 值，[actions] 放在右侧（图标按钮）。 */
@Composable
private fun DetailLine(label: String, value: String, actions: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(text = value, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions?.invoke()
    }
}

/** 值右侧的图标按钮（40dp 点击区）。 */
@Composable
private fun DetailIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun ConfirmButtons(confirmText: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppDialogButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(text = "取消", style = MiuixTheme.textStyles.button)
        }
        AppDialogButton(onClick = onConfirm, modifier = Modifier.weight(1f), role = GlassDialogButtonRole.Primary) {
            Text(text = confirmText, style = MiuixTheme.textStyles.button)
        }
    }
}

/** 读文件 → 解析 → 加密写库。在 IO 线程执行；任何异常都转成人能看懂的提示（提示里不带文件内容）。 */
private fun runImport(context: Context, uri: android.net.Uri): ImportReport {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(PasswordCsv.MAX_BYTES + 1) }
            ?: return ImportReport("导入失败", listOf("无法读取所选文件"), false)
        if (bytes.size > PasswordCsv.MAX_BYTES) {
            return ImportReport("导入失败", listOf("文件超过 ${PasswordCsv.MAX_BYTES / 1024 / 1024} MB，不像是密码文件"), false)
        }
        val parsed = PasswordCsv.parse(PasswordCsv.decode(bytes))
        val db = HistoryDb.get(context) ?: return ImportReport("导入失败", listOf("加密数据库不可用，无法保存密码"), false)
        val stats = PasswordDb.importEntries(context, db, parsed.entries)
        val lines = ArrayList<String>()
        lines.add("识别为：${parsed.format}")
        lines.add("新增 ${stats.added} 条" + if (stats.updated > 0) "，更新 ${stats.updated} 条" else "")
        if (stats.unchanged > 0) lines.add("${stats.unchanged} 条已存在，未改动")
        if (parsed.skipped > 0) lines.add("${parsed.skipped} 行被跳过（缺少网址 / 密码、网址不是 http(s)、或重复）")
        if (parsed.totalRows == 0) lines.add("文件里没有数据行")
        ImportReport("导入完成", lines, true)
    } catch (e: PasswordCsv.FormatException) {
        ImportReport("无法导入", listOf(e.message ?: "文件格式不对"), false)
    } catch (e: Exception) {
        Diag.log("password import failed: ${e.javaClass.simpleName}")
        ImportReport("导入失败", listOf("保存时出错，已全部撤销，没有导入任何密码"), false)
    }
}

/** 复制到剪贴板；[sensitive] 时标记为敏感内容，系统不在剪贴板预览里明文显示。 */
private fun copySecret(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
    Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
}

private fun formatTime(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
