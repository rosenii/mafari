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
import java.io.ByteArrayOutputStream
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

/** 口令对话框确认后，备份写到哪里 */
private enum class ExportTarget { FILE, WEBDAV, GIT }

/** 加密方案说明（界面展示用）：(项目, 内容)。随默认算法套件变化。 */
private fun schemeRows(): List<Pair<String, String>> {
    val suite = CipherSuites.default
    return listOf(
        "算法套件" to "#${suite.id} · ${suite.shortName}",
        "密钥封装" to suite.kemDescription,
        "密钥派生" to suite.kdfDescription,
        "密钥保护" to "每单元随机 DEK，经 KEK 以 AES-256-GCM 封装",
        "数据加密" to "AES-256-GCM · 信封 v${Envelope.VERSION}",
        "口令保护" to "Argon2id（64 MiB · 3 轮）",
        "文件格式" to ".${BackupFormat.EXTENSION} v${BackupFormat.VERSION} · 每次导出换新密钥对",
    )
}

private const val SCOPE_NOTE = "含书签、历史、设置、脚本等；不含下载文件、缓存、Cookie 本体与拓展包"

/** 设置 → 备份与同步：导出加密备份；同步 → 从备份文件恢复，或经 WebDAV / Git 上传与恢复。 */
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

    // —— WebDAV ——
    var webdavConfig by remember { mutableStateOf(WebDavStore.load(context)) }
    var showWebDavConfig by remember { mutableStateOf(false) }
    var webdavTesting by remember { mutableStateOf(false) }
    var webdavConfigError by remember { mutableStateOf<String?>(null) }
    var webdavTask by remember { mutableStateOf<String?>(null) }
    var exportTarget by remember { mutableStateOf(ExportTarget.FILE) }
    // —— Git ——
    var gitConfig by remember { mutableStateOf(GitStore.load(context)) }
    var showGitConfig by remember { mutableStateOf(false) }
    var gitTesting by remember { mutableStateOf(false) }
    var gitConfigError by remember { mutableStateOf<String?>(null) }
    var gitTask by remember { mutableStateOf<String?>(null) }
    var lastPush by remember { mutableStateOf(GitStore.lastPush(context)) }
    var lastUpload by remember { mutableStateOf(WebDavStore.lastUpload(context)) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    val working = busy || restoring || webdavTask != null || gitTask != null

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
                                summary = if (busy) "正在加密并导出…" else "导出为 .${BackupFormat.EXTENSION} 文件",
                                enabled = !working,
                                onClick = {
                                    exportTarget = ExportTarget.FILE
                                    showPasswordDialog = true
                                },
                            )
                        }
                    }
                    item {
                        Text(
                            text = SCOPE_NOTE,
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
                                summary = if (restoring) "正在解密并校验…" else "选择 .${BackupFormat.EXTENSION} 文件",
                                enabled = !working,
                                onClick = { openLauncher.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                    item { SmallTitle(text = "WebDAV") }
                    item {
                        val cfg = webdavConfig
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            ArrowPreference(
                                title = "服务器设置",
                                summary = cfg?.url?.substringAfter("://")?.substringAfterLast('@')?.trimEnd('/') ?: "未配置",
                                enabled = !working,
                                onClick = {
                                    webdavConfigError = null
                                    showWebDavConfig = true
                                },
                            )
                            ArrowPreference(
                                title = "上传备份",
                                summary = when {
                                    webdavTask != null && exportTarget == ExportTarget.WEBDAV -> webdavTask
                                    lastUpload > 0 -> "上次：" + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastUpload))
                                    else -> "加密后上传到服务器"
                                },
                                enabled = cfg != null && !working,
                                onClick = {
                                    exportTarget = ExportTarget.WEBDAV
                                    showPasswordDialog = true
                                },
                            )
                            ArrowPreference(
                                title = "从 WebDAV 恢复",
                                summary = if (webdavTask != null && exportTarget != ExportTarget.WEBDAV) webdavTask else "使用服务器上最新的备份",
                                enabled = cfg != null && !working,
                                onClick = {
                                    exportTarget = ExportTarget.FILE
                                    webdavTask = "正在获取最新备份…"
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { runCatching { WebDavClient(cfg!!).downloadLatest() } }
                                        webdavTask = null
                                        val got = result.getOrNull()
                                        if (got != null) {
                                            restoreBytes = got.second
                                            showRestorePassword = true
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "获取失败：" + (result.exceptionOrNull()?.message ?: "未知错误"),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                    item {
                        Text(
                            text = "存放在服务器的 ${WebDavNames.REMOTE_DIR} 文件夹，保留最近 ${WebDavNames.KEEP_BACKUPS} 份；账号只存本机。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }

                    item { SmallTitle(text = "Git") }
                    item {
                        val cfg = gitConfig
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            ArrowPreference(
                                title = "仓库设置",
                                summary = cfg?.url?.substringAfter("://")?.trimEnd('/')?.removeSuffix(".git") ?: "未配置",
                                enabled = !working,
                                onClick = {
                                    gitConfigError = null
                                    showGitConfig = true
                                },
                            )
                            ArrowPreference(
                                title = "推送备份",
                                summary = when {
                                    gitTask != null && exportTarget == ExportTarget.GIT -> gitTask
                                    lastPush > 0 -> "上次：" + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastPush))
                                    else -> "加密后提交并推送"
                                },
                                enabled = cfg != null && !working,
                                onClick = {
                                    exportTarget = ExportTarget.GIT
                                    showPasswordDialog = true
                                },
                            )
                            ArrowPreference(
                                title = "从 Git 恢复",
                                summary = if (gitTask != null && exportTarget != ExportTarget.GIT) gitTask else "使用仓库里最新的备份",
                                enabled = cfg != null && !working,
                                onClick = {
                                    exportTarget = ExportTarget.FILE
                                    gitTask = "正在拉取仓库…"
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { runCatching { GitSyncClient(context, cfg!!).download() } }
                                        gitTask = null
                                        val got = result.getOrNull()
                                        if (got != null) {
                                            restoreBytes = got
                                            showRestorePassword = true
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "拉取失败：" + (result.exceptionOrNull()?.message ?: "未知错误"),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                    item {
                        Text(
                            text = "每次备份提交为 ${GitNames.FILE_NAME}，历史即版本；仅支持 http(s) 地址，建议用私有仓库。",
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
                                schemeRows().forEach { (label, value) ->
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
                            text = "口令不会保存，忘记后无法解密。",
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
                confirmText = when (exportTarget) {
                    ExportTarget.FILE -> "导出"
                    ExportTarget.WEBDAV -> "上传"
                    ExportTarget.GIT -> "推送"
                },
                onConfirm = { password ->
                    showPasswordDialog = false
                    val cfg = webdavConfig
                    if (exportTarget == ExportTarget.FILE) {
                        pendingPassword = password
                        saveLauncher.launch(BackupManager.suggestedFileName())
                    } else if (exportTarget == ExportTarget.GIT) {
                        val git = gitConfig
                        if (git == null) {
                            password.fill('\u0000')
                        } else {
                            gitTask = "正在加密并推送…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val buffer = ByteArrayOutputStream()
                                        val summary = BackupManager.export(context, buffer, password)
                                        GitSyncClient(context, git).upload(buffer.toByteArray())
                                        summary
                                    }.onFailure { password.fill('\u0000') }
                                }
                                gitTask = null
                                val summary = result.getOrNull()
                                if (summary != null) {
                                    GitStore.markPushed(context)
                                    lastPush = GitStore.lastPush(context)
                                }
                                Toast.makeText(
                                    context,
                                    if (summary != null) {
                                        "已推送到 Git：${summary.units} 项 · " +
                                            android.text.format.Formatter.formatFileSize(context, summary.bytes.toLong()) +
                                            if (summary.skipped > 0) "（${summary.skipped} 项无法解密，已跳过）" else ""
                                    } else {
                                        "推送失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                                    },
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    } else if (cfg == null) {
                        password.fill('\u0000')
                    } else {
                        webdavTask = "正在加密并上传…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val buffer = ByteArrayOutputStream()
                                    val summary = BackupManager.export(context, buffer, password)
                                    WebDavClient(cfg).upload(buffer.toByteArray())
                                    summary
                                }.onFailure { password.fill('\u0000') }
                            }
                            webdavTask = null
                            val summary = result.getOrNull()
                            if (summary != null) {
                                WebDavStore.markUploaded(context)
                                lastUpload = WebDavStore.lastUpload(context)
                            }
                            Toast.makeText(
                                context,
                                if (summary != null) {
                                    "已上传到 WebDAV：${summary.units} 项 · " +
                                        android.text.format.Formatter.formatFileSize(context, summary.bytes.toLong()) +
                                        if (summary.skipped > 0) "（${summary.skipped} 项无法解密，已跳过）" else ""
                                } else {
                                    "上传失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )

            GitConfigDialog(
                show = showGitConfig,
                initial = gitConfig,
                testing = gitTesting,
                error = gitConfigError,
                backdrop = if (glass) backdrop else null,
                onDismiss = { if (!gitTesting) showGitConfig = false },
                onSave = { cfg ->
                    gitTesting = true
                    gitConfigError = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                GitSyncClient(context, cfg).test()
                                GitStore.save(context, cfg)
                            }
                        }
                        gitTesting = false
                        if (result.isSuccess) {
                            gitConfig = cfg
                            showGitConfig = false
                            Toast.makeText(context, "Git 仓库已连接并保存", Toast.LENGTH_SHORT).show()
                        } else {
                            gitConfigError = result.exceptionOrNull()?.message ?: "未知错误"
                        }
                    }
                },
                onClear = {
                    GitStore.clear(context)
                    gitConfig = null
                    lastPush = 0L
                    showGitConfig = false
                },
            )

            WebDavConfigDialog(
                show = showWebDavConfig,
                initial = webdavConfig,
                testing = webdavTesting,
                error = webdavConfigError,
                backdrop = if (glass) backdrop else null,
                onDismiss = { if (!webdavTesting) showWebDavConfig = false },
                onSave = { cfg ->
                    webdavTesting = true
                    webdavConfigError = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                WebDavClient(cfg).test()
                                WebDavStore.save(context, cfg)
                            }
                        }
                        webdavTesting = false
                        if (result.isSuccess) {
                            webdavConfig = cfg
                            showWebDavConfig = false
                            Toast.makeText(context, "WebDAV 已连接并保存", Toast.LENGTH_SHORT).show()
                        } else {
                            webdavConfigError = result.exceptionOrNull()?.message ?: "未知错误"
                        }
                    }
                },
                onClear = {
                    WebDavStore.clear(context)
                    webdavConfig = null
                    lastUpload = 0L
                    showWebDavConfig = false
                },
            )

            RestorePasswordDialog(
                show = showRestorePassword,
                backdrop = if (glass) backdrop else null,
                onDismiss = {
                    showRestorePassword = false
                    restoreUri = null
                    restoreBytes = null
                },
                onConfirm = { password ->
                    showRestorePassword = false
                    val uri = restoreUri
                    val remote = restoreBytes
                    restoreUri = null
                    restoreBytes = null
                    if (uri == null && remote == null) {
                        password.fill('\u0000')
                    } else {
                        restoring = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes = remote ?: uri?.let { u ->
                                        context.contentResolver.openInputStream(u)?.use { BackupManager.readAll(it) }
                                    } ?: error("无法读取所选文件")
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
    confirmText: String,
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
                    Text(text = confirmText, style = MiuixTheme.textStyles.button)
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
        summary = "输入导出备份时设置的口令",
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
            append("加密方案：").append(plan.suiteName).append(" · 文件格式 v").append(plan.formatVersion).append('\n')
            append("包含 ").append(plan.itemCount).append(" 项数据\n\n")
            append("将覆盖当前对应数据且无法撤销，完成后自动重启。")
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

@Composable
private fun WebDavConfigDialog(
    show: Boolean,
    initial: WebDavConfig?,
    testing: Boolean,
    error: String?,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
    onClear: () -> Unit,
) {
    var url by remember(show) { mutableStateOf(initial?.url.orEmpty()) }
    var user by remember(show) { mutableStateOf(initial?.username.orEmpty()) }
    var pass by remember(show) { mutableStateOf(initial?.password.orEmpty()) }
    val normalized = WebDavNames.normalizeBaseUrl(url)

    AppGlassDialog(
        show = show,
        title = "WebDAV 服务器",
        summary = "备份将存放在其中的 ${WebDavNames.REMOTE_DIR} 文件夹",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "服务器地址（https://…）",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            TextField(
                value = user,
                onValueChange = { user = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "用户名",
                singleLine = true,
            )
            TextField(
                value = pass,
                onValueChange = { pass = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "密码",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (error != null) {
                Text(
                    text = error,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (url.isNotBlank() && normalized == null) {
                Text(
                    text = "地址需以 http:// 或 https:// 开头",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, enabled = !testing, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { normalized?.let { onSave(WebDavConfig(it, user.trim(), pass)) } },
                    enabled = normalized != null && !testing,
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = if (testing) "连接中…" else "测试并保存", style = MiuixTheme.textStyles.button)
                }
            }
            if (initial != null) {
                AppDialogButton(
                    onClick = onClear,
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    role = GlassDialogButtonRole.Destructive,
                ) {
                    Text(text = "清除配置", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun GitConfigDialog(
    show: Boolean,
    initial: GitConfig?,
    testing: Boolean,
    error: String?,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onSave: (GitConfig) -> Unit,
    onClear: () -> Unit,
) {
    var url by remember(show) { mutableStateOf(initial?.url.orEmpty()) }
    var user by remember(show) { mutableStateOf(initial?.username.orEmpty()) }
    var token by remember(show) { mutableStateOf(initial?.token.orEmpty()) }
    var branch by remember(show) { mutableStateOf(initial?.branch.orEmpty()) }
    val normalizedUrl = GitNames.normalizeRepoUrl(url)
    val normalizedBranch = GitNames.normalizeBranch(branch)
    val valid = normalizedUrl != null && normalizedBranch != null

    AppGlassDialog(
        show = show,
        title = "Git 仓库",
        summary = "令牌填在下方，不要写进地址",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "仓库地址（https://…）",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            TextField(
                value = user,
                onValueChange = { user = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "用户名（可选）",
                singleLine = true,
            )
            TextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "访问令牌 / 密码",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            TextField(
                value = branch,
                onValueChange = { branch = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = "分支（留空用默认）",
                singleLine = true,
            )
            val hint = when {
                error != null -> error
                url.isNotBlank() && normalizedUrl == null -> "地址需为 http(s)://主机/路径，且不含账号密码"
                normalizedBranch == null -> "分支名不合法"
                else -> null
            }
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, enabled = !testing, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        if (normalizedUrl != null && normalizedBranch != null) {
                            onSave(GitConfig(normalizedUrl, user.trim(), token, normalizedBranch))
                        }
                    },
                    enabled = valid && !testing,
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = if (testing) "连接中…" else "测试并保存", style = MiuixTheme.textStyles.button)
                }
            }
            if (initial != null) {
                AppDialogButton(
                    onClick = onClear,
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    role = GlassDialogButtonRole.Destructive,
                ) {
                    Text(text = "清除配置", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}
