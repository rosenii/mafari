package dev.dsh.miuixbrowser

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
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

private const val MIN_PASSWORD_LEN = 8

/** 加密方案说明（界面展示用）：(项目, 内容) */
private val SCHEME_ROWS = listOf(
    "密钥封装" to "X25519 + ML-KEM-768 混合 KEM（后量子）；两个共享密钥拼接后派生，任一算法被攻破仍安全",
    "密钥派生" to "HKDF-SHA-384 → KEK",
    "密钥保护" to "每个单元一个随机 32 字节 DEK，由 KEK 经 AES-256-GCM 封装（AAD = 信封头）",
    "数据加密" to "AES-256-GCM（AAD = 信封头 + 单元名），版本化信封 v1",
    "口令保护" to "备份私钥由口令经 Argon2id（64 MiB · 3 轮）派生的密钥包裹",
    "文件格式" to ".${BackupFormat.EXTENSION} · 每次导出使用全新密钥对",
)

private val INCLUDED = "书签 · 历史 · 应用设置 · Cookie 策略 · 脚本及其数据 · 去广告配置 · 菜单与 Gecko 保护配置"
private val EXCLUDED = "不含：下载的文件、网页缓存、网站登录状态 / Cookie 本体（由浏览器内核管理）、已安装的拓展包"

/** 设置 → 备份与同步：导出加密备份；同步 → 从备份文件恢复（其余同步方式后续再加）。 */
@Composable
fun BackupScreen(onBack: () -> Unit, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    var showPasswordDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val password = pendingPassword
        pendingPassword = null
        if (uri == null || password == null) {
            password?.fill('\u0000')
        } else {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri, "w")
                            ?: error("无法写入所选位置")
                        out.use { BackupManager.export(context, it, password) }
                    }.onFailure {
                        password.fill('\u0000')
                        // 写到一半失败：不要留下半个文件
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    }
                }
                busy = false
                val summary = result.getOrNull()
                Toast.makeText(
                    context,
                    if (summary != null) {
                        "已导出加密备份：${summary.units} 项 · " +
                            android.text.format.Formatter.formatFileSize(context, summary.bytes.toLong()) +
                            if (summary.skipped > 0) "（${summary.skipped} 项无法解密，已跳过）" else ""
                    } else {
                        "导出失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // —— 从备份文件恢复：选文件 → 输口令解密 → 确认 → 写入并重启 ——
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var showRestorePassword by remember { mutableStateOf(false) }
    var restorePlan by remember { mutableStateOf<BackupManager.RestorePlan?>(null) }
    var restoring by remember { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            restoreUri = uri
            showRestorePassword = true
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = "备份与同步",
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = {
                        LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
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
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    item { SmallTitle(text = "备份") }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            ArrowPreference(
                                title = "导出加密备份",
                                summary = if (busy) "正在加密并导出…" else "设置口令后导出为 .${BackupFormat.EXTENSION} 文件",
                                enabled = !busy,
                                onClick = { showPasswordDialog = true },
                            )
                        }
                    }
                    item {
                        Text(
                            text = INCLUDED + "\n" + EXCLUDED,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }

                    item { SmallTitle(text = "同步") }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            ArrowPreference(
                                title = "从备份文件恢复",
                                summary = if (restoring) "正在解密并校验…" else "选择 .${BackupFormat.EXTENSION} 备份文件，输入口令后恢复",
                                enabled = !busy && !restoring,
                                onClick = { openLauncher.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                    item {
                        Text(
                            text = "恢复会用备份中的数据覆盖当前对应的数据，完成后自动重启应用。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }

                    item { SmallTitle(text = "加密方案") }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SCHEME_ROWS.forEach { (label, value) ->
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Text(
                                            text = label,
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.width(76.dp),
                                        )
                                        Text(
                                            text = value,
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            text = "口令不会保存，也无法找回：忘记口令，备份将无法解密。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            PasswordDialog(
                show = showPasswordDialog,
                backdrop = if (glass) backdrop else null,
                onDismiss = { showPasswordDialog = false },
                onConfirm = { password ->
                    showPasswordDialog = false
                    pendingPassword = password
                    saveLauncher.launch(BackupManager.suggestedFileName())
                },
            )

            RestorePasswordDialog(
                show = showRestorePassword,
                backdrop = if (glass) backdrop else null,
                onDismiss = {
                    showRestorePassword = false
                    restoreUri = null
                },
                onConfirm = { password ->
                    showRestorePassword = false
                    val uri = restoreUri
                    restoreUri = null
                    if (uri == null) {
                        password.fill('\u0000')
                    } else {
                        restoring = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes = context.contentResolver.openInputStream(uri)
                                        ?.use { BackupManager.readAll(it) }
                                        ?: error("无法读取所选文件")
                                    BackupManager.prepareRestore(context, bytes, password)
                                }.onFailure { password.fill('\u0000') }
                            }
                            restoring = false
                            val plan = result.getOrNull()
                            if (plan != null) {
                                restorePlan = plan
                            } else {
                                Toast.makeText(
                                    context,
                                    "无法恢复：" + (result.exceptionOrNull()?.message ?: "未知错误"),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
            )

            val plan = restorePlan
            RestoreConfirmDialog(
                show = plan != null,
                plan = plan,
                backdrop = if (glass) backdrop else null,
                onDismiss = { if (!restoring) restorePlan = null },
                onConfirm = {
                    val p = restorePlan ?: return@RestoreConfirmDialog
                    restoring = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { BackupManager.applyRestore(context, p) } }
                        if (result.isSuccess) {
                            Toast.makeText(context, "恢复完成，正在重启应用…", Toast.LENGTH_SHORT).show()
                            delay(600)
                            BackupManager.restartApp(context)
                        } else {
                            restoring = false
                            restorePlan = null
                            Toast.makeText(
                                context,
                                "恢复失败：" + (result.exceptionOrNull()?.message ?: "未知错误") + "。建议重启应用后检查数据",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun PasswordDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember(show) { mutableStateOf("") }
    var confirm by remember(show) { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LEN
    val mismatch = confirm.isNotEmpty() && confirm != password
    val valid = password.length >= MIN_PASSWORD_LEN && password == confirm

    AppGlassDialog(
        show = show,
        title = "设置备份口令",
        summary = "口令不会保存，忘记后无法找回备份",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "口令",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            TextField(
                value = confirm,
                onValueChange = { confirm = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "再次输入口令",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (tooShort || mismatch) {
                Text(
                    text = if (tooShort) "口令至少 $MIN_PASSWORD_LEN 位" else "两次输入不一致",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { onConfirm(password.toCharArray()) },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "导出", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun RestorePasswordDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember(show) { mutableStateOf("") }

    AppGlassDialog(
        show = show,
        title = "输入备份口令",
        summary = "输入导出这份备份时设置的口令。",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "口令",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { onConfirm(password.toCharArray()) },
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "解密", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun RestoreConfirmDialog(
    show: Boolean,
    plan: BackupManager.RestorePlan?,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val summary = if (plan == null) {
        ""
    } else {
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(plan.createdAt))
        buildString {
            append("备份时间：").append(time).append('\n')
            if (plan.versionName.isNotBlank()) append("来源版本：").append(plan.versionName).append('\n')
            append("包含 ").append(plan.itemCount).append(" 项数据\n\n")
            append("恢复会覆盖当前的书签、历史、设置等对应数据，且无法撤销；完成后应用将自动重启。")
        }
    }
    AppGlassDialog(
        show = show,
        title = "恢复这份备份？",
        summary = summary,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(text = "取消", style = MiuixTheme.textStyles.button)
            }
            AppDialogButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Destructive,
            ) {
                Text(text = "恢复并重启", style = MiuixTheme.textStyles.button)
            }
        }
    }
}
