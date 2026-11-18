package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.backup.BackupAuto
import dev.dsh.miuixbrowser.backup.BackupCategory
import dev.dsh.miuixbrowser.backup.BackupFormat
import dev.dsh.miuixbrowser.backup.BackupManager
import dev.dsh.miuixbrowser.crypto.BiometricGate
import dev.dsh.miuixbrowser.crypto.CipherSuites
import dev.dsh.miuixbrowser.crypto.EncryptionInfoDialog
import dev.dsh.miuixbrowser.crypto.EncryptionLockButton
import dev.dsh.miuixbrowser.crypto.Envelope
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.sync.GitConfig
import dev.dsh.miuixbrowser.sync.GitNames
import dev.dsh.miuixbrowser.sync.GitStore
import dev.dsh.miuixbrowser.sync.GitSyncClient
import dev.dsh.miuixbrowser.sync.WebDavClient
import dev.dsh.miuixbrowser.sync.WebDavConfig
import dev.dsh.miuixbrowser.sync.WebDavNames
import dev.dsh.miuixbrowser.sync.WebDavStore
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.sync.fxa.FxaUi
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTabRow
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val MIN_PASSWORD_LEN = 8

private const val TAB_WEBDAV = 0
private const val TAB_GIT = 1

/** 口令对话框确认后，备份写到哪里 */
private enum class ExportTarget { FILE, WEBDAV, GIT }

/** 加密方案说明（界面展示用）：(项目, 内容)。随默认算法套件变化。 */
private fun schemeRows(): List<Pair<String, String>> {
    val suite = CipherSuites.default
    return listOf(
        tr("算法套件") to "#${suite.id} · ${suite.shortName}",
        tr("密钥封装") to suite.kemDescription,
        tr("密钥派生") to suite.kdfDescription,
        tr("密钥保护") to tr("每单元随机 DEK，经 KEK 以 AES-256-GCM 封装"),
        tr("数据加密") to tr("AES-256-GCM · 信封 v{0}", Envelope.VERSION),
        tr("口令保护") to tr("Argon2id（64 MiB · 3 轮）"),
        tr("文件格式") to tr(".{0} v{1} · 每次导出换新密钥对", BackupFormat.EXTENSION, BackupFormat.VERSION),
    )
}

private fun formatTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

/** 导出 / 上传 / 推送成功后的一行提示。 */
private fun doneText(context: Context, prefix: String, summary: BackupManager.Summary): String =
    prefix + "：" + summary.included.joinToString(tr("、")) { it.title } + " · " +
        android.text.format.Formatter.formatFileSize(context, summary.bytes.toLong()) +
        (if (summary.empty.isNotEmpty()) tr("\n没有数据，已跳过：") + summary.empty.joinToString(tr("、")) { it.title } else "") +
        (if (summary.skipped > 0) tr("\n无法解密而跳过 {0} 项", summary.skipped) else "")

private fun failText(prefix: String, e: Throwable?): String = tr("{0}：{1}", prefix, e?.message ?: tr("未知错误"))

/** 远端的一份历史备份：WebDAV 为文件，Git 为提交。[id] 交回 download(id) 取回内容；[tag] 是下拉里附带的短标识（Git 的提交号）。 */
private data class RemoteBackup(val id: String, val time: Long, val tag: String? = null)

private fun historyLabel(b: RemoteBackup, newest: Boolean): String =
    (if (b.time > 0) formatTime(b.time) else b.id) +
        (b.tag?.let { " · $it" } ?: "") +
        (if (newest) tr(" · 最新") else "")

/** 弹窗 / sheet 里「已保存配置」的一行。 */
private data class ProfileRow(val id: String, val title: String, val subtitle: String, val syncAlso: Boolean)

/** 设置 → 备份与同步：本地备份与恢复；云同步（WebDAV / Git）。 */
@Composable
fun BackupScreen(onBack: () -> Unit, onOpenFxa: () -> Unit = {}, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 本页要显示 / 使用 Firefox 登录状态：冷启动不初始化同步，这里按需后台预热（已预热则无操作；没登录过的用户不会初始化）
    LaunchedEffect(Unit) { if (EngineSupport.SYNC_BUNDLED) FxaSync.warmUp(context) }

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    var showLockInfo by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    val sheetBackdrop = if (glass) backdrop else null

    var showDefaultPw by remember { mutableStateOf(false) }
    var defaultPwSet by remember { mutableStateOf(BackupAuto.hasPassword(context)) }
    var defaultPwInitial by remember { mutableStateOf("") }   // 验证通过后才读出，关闭时清空
    var autoMode by remember { mutableIntStateOf(BackupAuto.mode(context)) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var syncTab by rememberSaveable { mutableIntStateOf(TAB_WEBDAV) }
    // 备份内容（手动导出 / WebDAV / Git / 自动推送共用）；Cookie 按当前默认内核（Gecko / WebView）读取
    var selectedCategories by remember { mutableStateOf(BackupAuto.categories(context)) }
    val effectiveCategories = BackupCategory.entries.filter { it in selectedCategories }
    val hasContent = effectiveCategories.isNotEmpty()

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
                            ?: error(tr("无法写入所选位置"))
                        out.use { BackupManager.export(context, it, password, BackupAuto.effectiveCategories(context)) }
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
                    if (summary != null) doneText(context, tr("已导出"), summary) else failText(tr("导出失败"), result.exceptionOrNull()),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // —— 从备份文件恢复：选文件 → 输口令解密 → 确认 → 写入并重启 ——
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var showRestorePassword by remember { mutableStateOf(false) }
    var restorePlan by remember { mutableStateOf<BackupManager.RestorePlan?>(null) }
    var restoreSelected by remember { mutableStateOf<Set<BackupCategory>>(emptySet()) }
    var restoring by remember { mutableStateOf(false) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var exportTarget by remember { mutableStateOf(ExportTarget.FILE) }

    // —— WebDAV：已保存的服务器列表 + 当前使用的一台 ——
    var webdavProfiles by remember { mutableStateOf(WebDavStore.list(context)) }
    var webdavActiveId by remember { mutableStateOf(WebDavStore.activeId(context)) }
    val webdavConfig = webdavProfiles.firstOrNull { it.id == webdavActiveId }?.config
    var showWebDavSheet by remember { mutableStateOf(false) }
    var showWebDavConfig by remember { mutableStateOf(false) }
    var webdavEditId by remember { mutableStateOf<String?>(null) }   // null = 新增
    var webdavDialogInitial by remember { mutableStateOf<WebDavConfig?>(null) }  // 打开时的快照，避免关闭动画中内容跳变
    var webdavFromSheet by remember { mutableStateOf(false) }
    var webdavTesting by remember { mutableStateOf(false) }
    var webdavConfigError by remember { mutableStateOf<String?>(null) }
    var webdavTask by remember { mutableStateOf<String?>(null) }
    var lastUpload by remember { mutableStateOf(WebDavStore.lastUpload(context)) }
    fun reloadWebDav() {
        webdavProfiles = WebDavStore.list(context)
        webdavActiveId = WebDavStore.activeId(context)
        lastUpload = WebDavStore.lastUpload(context)
    }

    // —— Git：已保存的仓库列表 + 当前使用的一个 ——
    var gitProfiles by remember { mutableStateOf(GitStore.list(context)) }
    var gitActiveId by remember { mutableStateOf(GitStore.activeId(context)) }
    val gitConfig = gitProfiles.firstOrNull { it.id == gitActiveId }?.config
    var showGitSheet by remember { mutableStateOf(false) }
    var showGitConfig by remember { mutableStateOf(false) }
    var gitEditId by remember { mutableStateOf<String?>(null) }
    var gitDialogInitial by remember { mutableStateOf<GitConfig?>(null) }
    var gitFromSheet by remember { mutableStateOf(false) }
    var gitTesting by remember { mutableStateOf(false) }
    var gitConfigError by remember { mutableStateOf<String?>(null) }
    var gitTask by remember { mutableStateOf<String?>(null) }
    var lastPush by remember { mutableStateOf(GitStore.lastPush(context)) }
    var gitImportedKey by remember { mutableStateOf<String?>(null) }
    fun reloadGit() {
        gitProfiles = GitStore.list(context)
        gitActiveId = GitStore.activeId(context)
        lastPush = GitStore.lastPush(context)
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(32 * 1024 + 1) }
                            ?: error(tr("无法读取所选文件"))
                        if (bytes.size > 32 * 1024) error(tr("文件过大，不像是私钥"))
                        String(bytes, Charsets.UTF_8)
                    }
                }
                val got = text.getOrNull()
                if (got != null) {
                    gitImportedKey = got
                } else {
                    Toast.makeText(context, failText(tr("导入失败"), text.exceptionOrNull()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val autoPushReady = defaultPwSet && (webdavConfig != null || gitConfig != null)
    val working = busy || restoring || webdavTask != null || gitTask != null

    /** 先过生物识别（失败可退回锁屏密码），通过才执行 [onOk]。 */
    fun requireAuth(subtitle: String, onOk: () -> Unit) {
        BiometricGate.authenticate(context, tr("验证身份"), subtitle) { ok -> if (ok) onOk() }
    }

    /** 用 [password] 解密并校验当前待恢复的来源；[fromDefault] 为真时失败会退回手动输入口令。 */
    fun runRestore(password: CharArray, fromDefault: Boolean) {
        val uri = restoreUri
        val remote = restoreBytes
        if (uri == null && remote == null) {
            password.fill('\u0000')
            return
        }
        restoring = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = remote ?: uri?.let { u ->
                        context.contentResolver.openInputStream(u)?.use { BackupManager.readAll(it) }
                    } ?: error(tr("无法读取所选文件"))
                    BackupManager.prepareRestore(context, bytes, password)
                }.onFailure { password.fill('\u0000') }
            }
            restoring = false
            val plan = result.getOrNull()
            if (plan != null) {
                restoreUri = null
                restoreBytes = null
                restoreSelected = plan.defaultSelection
                restorePlan = plan
            } else if (fromDefault) {
                Toast.makeText(context, failText(tr("默认口令未能解密"), result.exceptionOrNull()) + tr("，请手动输入口令"), Toast.LENGTH_LONG).show()
                showRestorePassword = true
            } else {
                restoreUri = null
                restoreBytes = null
                Toast.makeText(context, failText(tr("无法恢复"), result.exceptionOrNull()), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 拿到备份来源后：有默认口令先用它解密，否则（或失败时）弹口令框。 */
    fun beginRestore(uri: Uri?, bytes: ByteArray?) {
        restoreUri = uri
        restoreBytes = bytes
        val saved = BackupAuto.password(context)
        if (saved == null) showRestorePassword = true else runRestore(saved.toCharArray(), fromDefault = true)
    }

    // —— 从服务器 / 仓库恢复：验证身份 → 拉取历史记录 → 下拉选一份 → 下载并走恢复流程 ——
    var showHistory by remember { mutableStateOf(false) }
    var historySource by remember { mutableStateOf(ExportTarget.WEBDAV) }
    var historyItems by remember { mutableStateOf<List<RemoteBackup>>(emptyList()) }
    var historyIndex by remember { mutableIntStateOf(0) }

    fun setRemoteTask(target: ExportTarget, text: String?) {
        if (target == ExportTarget.GIT) gitTask = text else webdavTask = text
    }

    fun openHistory(target: ExportTarget) {
        val dav = webdavConfig
        val git = gitConfig
        requireAuth(if (target == ExportTarget.GIT) tr("从仓库恢复备份") else tr("从服务器恢复备份")) {
            exportTarget = ExportTarget.FILE
            historySource = target
            setRemoteTask(target, tr("正在获取历史备份…"))
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (target == ExportTarget.GIT) {
                            GitSyncClient(context, git!!).history().map { RemoteBackup(it.id, it.time, it.id.take(7)) }
                        } else {
                            WebDavClient(dav!!).listBackups().map { RemoteBackup(it, WebDavNames.backupTime(it)) }
                        }
                    }
                }
                setRemoteTask(target, null)
                val list = result.getOrNull()
                when {
                    list == null -> Toast.makeText(context, failText(tr("获取失败"), result.exceptionOrNull()), Toast.LENGTH_LONG).show()
                    list.isEmpty() -> Toast.makeText(context, tr("还没有可恢复的备份"), Toast.LENGTH_SHORT).show()
                    else -> {
                        historyItems = list.sortedByDescending { it.time }
                        historyIndex = 0
                        showHistory = true
                    }
                }
            }
        }
    }

    fun restoreSelectedHistory() {
        val item = historyItems.getOrNull(historyIndex) ?: return
        val target = historySource
        val dav = webdavConfig
        val git = gitConfig
        showHistory = false
        setRemoteTask(target, tr("正在下载所选备份…"))
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (target == ExportTarget.GIT) GitSyncClient(context, git!!).download(item.id)
                    else WebDavClient(dav!!).download(item.id)
                }
            }
            setRemoteTask(target, null)
            val bytes = result.getOrNull()
            if (bytes != null) beginRestore(null, bytes)
            else Toast.makeText(context, failText(tr("下载失败"), result.exceptionOrNull()), Toast.LENGTH_LONG).show()
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) beginRestore(uri, null)
    }

    /** 按 [exportTarget] 把备份写到本地文件 / WebDAV / Git；[password] 用完即清零。 */
    fun performExport(password: CharArray) {
        val cfg = webdavConfig
        if (exportTarget == ExportTarget.FILE) {
            pendingPassword = password
            saveLauncher.launch(BackupManager.suggestedFileName())
        } else if (exportTarget == ExportTarget.GIT) {
            val git = gitConfig
            if (git == null) {
                password.fill('\u0000')
            } else {
                gitTask = tr("加密推送中")
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val buffer = ByteArrayOutputStream()
                            val summary = BackupManager.export(context, buffer, password, BackupAuto.effectiveCategories(context))
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
                        if (summary != null) doneText(context, tr("已推送"), summary) else failText(tr("推送失败"), result.exceptionOrNull()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } else if (cfg == null) {
            password.fill('\u0000')
        } else {
            webdavTask = tr("加密上传中")
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val buffer = ByteArrayOutputStream()
                        val summary = BackupManager.export(context, buffer, password, BackupAuto.effectiveCategories(context))
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
                    if (summary != null) doneText(context, tr("已上传"), summary) else failText(tr("上传失败"), result.exceptionOrNull()),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** 有默认口令就直接用，否则弹口令对话框。 */
    fun startExport(target: ExportTarget) {
        exportTarget = target
        val saved = BackupAuto.password(context)
        if (saved != null) performExport(saved.toCharArray()) else showPasswordDialog = true
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = tr("备份与同步"),
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = {
                        LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                    },
                    actions = {
                        EncryptionLockButton(onClick = { showLockInfo = true }, liquid = liquid, topScrolled = topScrolled)
                    },
                )
            },
        ) { innerPadding ->
            EncryptionInfoDialog(
                show = showLockInfo,
                onDismiss = { showLockInfo = false },
                backdrop = backdrop,
                liquid = liquid,
                scope = tr("备份文件：Argon2id 口令 + 套件加密；同步凭据：套件加密"),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .edgeHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    // ———— Firefox同步（置顶）————
                    if (EngineSupport.SYNC_BUNDLED) {
                        item { SmallTitle(text = tr("Firefox同步")) }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                val fxa = FxaSync.ui
                                ArrowPreference(
                                    title = tr("Firefox帐户同步"),
                                    summary = when (fxa) {
                                        is FxaUi.SignedIn -> tr("已登录") + fxa.email.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                        is FxaUi.NeedsReauth -> tr("登录已失效，点击重新登录")
                                        else -> tr("登录Mozilla账号，同步您的Firefox数据")
                                    },
                                    onClick = onOpenFxa,
                                )
                            }
                        }
                    }
                    // ———— 口令与备份配置：默认加密口令 + 自动推送同步 + 备份内容 ————
                    item { SmallTitle(text = tr("口令与备份配置")) }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            ArrowPreference(
                                title = tr("默认加密口令"),
                                summary = if (defaultPwSet) tr("已设置，备份时不再询问") else tr("未设置，每次备份都要输入"),
                                enabled = !working,
                                onClick = {
                                    requireAuth(tr("查看 / 修改默认口令")) {
                                        defaultPwInitial = BackupAuto.password(context).orEmpty()
                                        showDefaultPw = true
                                    }
                                },
                            )
                            AppDropdownPreference(
                                backdrop = sheetBackdrop,
                                title = tr("自动推送同步"),
                                summary = if (autoPushReady) null else tr("需先设默认口令并配置云同步"),
                                items = BackupAuto.INTERVAL_LABELS,
                                selectedIndex = autoMode.coerceIn(0, 3),
                                onSelectedIndexChange = {
                                    autoMode = it
                                    BackupAuto.setMode(context, it)
                                },
                                enabled = autoPushReady,
                            )
                            // 备份内容：一行入口，点开弹窗启停各类内容（所有备份方式共用）
                            ArrowPreference(
                                title = tr("备份内容"),
                                summary = if (hasContent) {
                                    tr("已选 {0} / {1} 类", effectiveCategories.size, BackupCategory.entries.size)
                                } else {
                                    tr("未选择，选择后才能备份")
                                },
                                enabled = !working,
                                onClick = { showCategoryDialog = true },
                            )
                        }
                    }
                    item { Note(tr("启动应用时按间隔执行，备份「备份内容」里选中的类别，推送到当前连接的 WebDAV / Git，以及列表里开启「一并同步」的其他项。")) }

                    // ———— 云同步：WebDAV / Git 左右切换 ————
                    item { SmallTitle(text = tr("WebDAV/Git同步")) }
                    // WebDAV / Git 分栏：在卡片外、标题正下方
                    item {
                        GlassTabRow(
                            tabs = listOf("WebDAV", "Git"),
                            selectedIndex = syncTab,
                            onSelect = { syncTab = it },
                            backdrop = null,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    item {
                      Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                      ) {
                        AnimatedContent(
                            targetState = syncTab,
                            transitionSpec = {
                                val dir = if (targetState > initialState) 1 else -1
                                (slideInHorizontally { full -> full / 4 * dir } + fadeIn()) togetherWith
                                    (slideOutHorizontally { full -> -full / 4 * dir } + fadeOut())
                            },
                            label = "syncTab",
                        ) { tab ->
                            Column {
                                if (tab == TAB_WEBDAV) {
                                    val cfg = webdavConfig
                                    val task = webdavTask
                                    Column {
                                        ArrowPreference(
                                            title = tr("服务器"),
                                            summary = cfg?.let { WebDavNames.hostLabel(it.url) } ?: tr("未配置，点击添加"),
                                            enabled = !working,
                                            onClick = {
                                                webdavConfigError = null
                                                if (webdavProfiles.isEmpty()) {
                                                    webdavEditId = null
                                                    webdavDialogInitial = null
                                                    webdavFromSheet = false
                                                    showWebDavConfig = true
                                                } else {
                                                    showWebDavSheet = true
                                                }
                                            },
                                        )
                                        ActionPair(
                                            status = when {
                                                task != null && exportTarget != ExportTarget.WEBDAV -> task
                                                cfg == null -> null
                                                lastUpload > 0 -> tr("上次上传 ") + formatTime(lastUpload)
                                                else -> tr("尚未上传")
                                            },
                                            primary = if (task != null && exportTarget == ExportTarget.WEBDAV) task else tr("上传备份"),
                                            primaryEnabled = cfg != null && !working && hasContent,
                                            onPrimary = { startExport(ExportTarget.WEBDAV) },
                                            secondary = tr("从服务器恢复"),
                                            secondaryEnabled = cfg != null && !working,
                                            onSecondary = { openHistory(ExportTarget.WEBDAV) },
                                        )
                                    }
                                } else {
                                    val cfg = gitConfig
                                    val task = gitTask
                                    Column {
                                        ArrowPreference(
                                            title = tr("仓库"),
                                            summary = cfg?.let { GitNames.repoLabel(it.url) + if (it.useSsh) " · Deploy key" else "" } ?: tr("未配置，点击添加"),
                                            enabled = !working,
                                            onClick = {
                                                gitConfigError = null
                                                if (gitProfiles.isEmpty()) {
                                                    gitEditId = null
                                                    gitDialogInitial = null
                                                    gitFromSheet = false
                                                    showGitConfig = true
                                                } else {
                                                    showGitSheet = true
                                                }
                                            },
                                        )
                                        ActionPair(
                                            status = when {
                                                task != null && exportTarget != ExportTarget.GIT -> task
                                                cfg == null -> null
                                                lastPush > 0 -> tr("上次推送 ") + formatTime(lastPush)
                                                else -> tr("尚未推送")
                                            },
                                            primary = if (task != null && exportTarget == ExportTarget.GIT) task else tr("推送备份"),
                                            primaryEnabled = cfg != null && !working && hasContent,
                                            onPrimary = { startExport(ExportTarget.GIT) },
                                            secondary = tr("从仓库恢复"),
                                            secondaryEnabled = cfg != null && !working,
                                            onSecondary = { openHistory(ExportTarget.GIT) },
                                        )
                                    }
                                }
                            }
                        }
                      }
                    }
                    item {
                        if (syncTab == TAB_WEBDAV) {
                            Note(tr("存于服务器 {0} 文件夹，保留最近 {1} 份；账号加密存本机。", WebDavNames.REMOTE_DIR, WebDavNames.KEEP_BACKUPS))
                        } else {
                            Note(tr("仅保留最近 {0} 个提交，旧的会强制推送裁掉；建议私有仓库。", GitNames.HISTORY_KEEP))
                        }
                    }

                    // ———— 本地备份与恢复 ————
                    item { SmallTitle(text = tr("本地备份与恢复")) }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            // 两行文字条目（不再是并排的两个按钮）
                            Column {
                                ArrowPreference(
                                    title = tr("导出备份"),
                                    summary = if (busy) tr("正在加密并导出…") else tr("加密后保存为 .{0} 文件", BackupFormat.EXTENSION),
                                    enabled = !working && hasContent,
                                    onClick = { startExport(ExportTarget.FILE) },
                                )
                                ArrowPreference(
                                    title = tr("从文件恢复"),
                                    summary = if (restoring) tr("正在解密并校验…") else tr("选择备份文件，解密后恢复"),
                                    enabled = !working,
                                    onClick = { requireAuth(tr("从本地备份文件恢复")) { openLauncher.launch(arrayOf("*/*")) } },
                                )
                            }
                        }
                    }
                    item { Note(tr("恢复只覆盖备份里有的内容，恢复前可再勾选；完成后自动重启。")) }

                    item { SmallTitle(text = tr("加密方案")) }
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
                    item { Note(tr("口令不会保存，忘记后无法解密。")) }
                }
            }

            // ———— WebDAV：已连接服务器 + 添加新配置 ————
            BackupChunk {
                CategoryDialog(
                    show = showCategoryDialog,
                    backdrop = sheetBackdrop,
                    selected = selectedCategories,
                    onToggle = { cat, on ->
                        val next = if (on) selectedCategories + cat else selectedCategories - cat
                        selectedCategories = next
                        BackupAuto.setCategories(context, next)
                    },
                    onDismiss = { showCategoryDialog = false },
                )

                ServerSheet(
                    show = showWebDavSheet,
                    title = tr("WebDAV 服务器"),
                    addTitle = tr("添加新配置"),
                    addSummary = tr("连接另一台 WebDAV 服务器"),
                    rows = webdavProfiles.map {
                        ProfileRow(
                            it.id,
                            WebDavNames.hostLabel(it.config.url),
                            it.config.username.ifEmpty { tr("匿名") },
                            it.syncAlso,
                        )
                    },
                    activeId = webdavActiveId,
                    backdrop = sheetBackdrop,
                    onDismiss = { showWebDavSheet = false },
                    onEdit = { id ->
                        showWebDavSheet = false
                        webdavEditId = id
                        webdavDialogInitial = webdavProfiles.firstOrNull { it.id == id }?.config
                        webdavFromSheet = true
                        webdavConfigError = null
                        showWebDavConfig = true
                    },
                    onUse = { id ->
                        WebDavStore.select(context, id)
                        reloadWebDav()
                    },
                    onToggleSync = { id, on ->
                        WebDavStore.setSyncAlso(context, id, on)
                        reloadWebDav()
                    },
                    onAdd = {
                        showWebDavSheet = false
                        webdavEditId = null
                        webdavDialogInitial = null
                        webdavFromSheet = true
                        webdavConfigError = null
                        showWebDavConfig = true
                    },
                )

                ServerSheet(
                    show = showGitSheet,
                    title = tr("Git 仓库"),
                    addTitle = tr("添加新配置"),
                    addSummary = tr("连接另一个 Git 仓库"),
                    rows = gitProfiles.map {
                        ProfileRow(
                            it.id,
                            GitNames.repoLabel(it.config.url),
                            (if (it.config.useSsh) "Deploy key" else tr("令牌")) +
                                " · " + it.config.branch.ifEmpty { tr("默认分支") },
                            it.syncAlso,
                        )
                    },
                    activeId = gitActiveId,
                    backdrop = sheetBackdrop,
                    onDismiss = { showGitSheet = false },
                    onEdit = { id ->
                        showGitSheet = false
                        gitEditId = id
                        gitDialogInitial = gitProfiles.firstOrNull { it.id == id }?.config
                        gitFromSheet = true
                        gitConfigError = null
                        showGitConfig = true
                    },
                    onUse = { id ->
                        GitStore.select(context, id)
                        reloadGit()
                    },
                    onToggleSync = { id, on ->
                        GitStore.setSyncAlso(context, id, on)
                        reloadGit()
                    },
                    onAdd = {
                        showGitSheet = false
                        gitEditId = null
                        gitDialogInitial = null
                        gitFromSheet = true
                        gitConfigError = null
                        showGitConfig = true
                    },
                )
            }

            BackupChunk {
                DefaultPasswordDialog(
                    show = showDefaultPw,
                    isSet = defaultPwSet,
                    initial = defaultPwInitial,
                    backdrop = sheetBackdrop,
                    onDismiss = {
                        showDefaultPw = false
                        defaultPwInitial = ""
                    },
                    onSave = { pw ->
                        val r = runCatching { BackupAuto.setPassword(context, pw) }
                        if (r.isSuccess) {
                            defaultPwSet = true
                            showDefaultPw = false
                            defaultPwInitial = ""
                            Toast.makeText(context, tr("默认口令已保存"), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, failText(tr("保存失败"), r.exceptionOrNull()), Toast.LENGTH_LONG).show()
                        }
                    },
                    onClear = {
                        BackupAuto.clearPassword(context)
                        defaultPwSet = false
                        autoMode = 0
                        showDefaultPw = false
                        defaultPwInitial = ""
                    },
                )

                PasswordDialog(
                    show = showPasswordDialog,
                    backdrop = sheetBackdrop,
                    onDismiss = { showPasswordDialog = false },
                    confirmText = when (exportTarget) {
                        ExportTarget.FILE -> tr("导出")
                        ExportTarget.WEBDAV -> tr("上传")
                        ExportTarget.GIT -> tr("推送")
                    },
                    onConfirm = { password ->
                        showPasswordDialog = false
                        performExport(password)
                    },
                )
            }

            BackupChunk {
                GitConfigDialog(
                    show = showGitConfig,
                    initial = gitDialogInitial,
                    testing = gitTesting,
                    error = gitConfigError,
                    backdrop = sheetBackdrop,
                    importedKey = gitImportedKey,
                    onImportedKeyConsumed = { gitImportedKey = null },
                    onPickKey = { keyPicker.launch(arrayOf("*/*")) },
                    onDismiss = {
                        if (!gitTesting) {
                            showGitConfig = false
                            if (gitFromSheet && gitProfiles.isNotEmpty()) showGitSheet = true
                        }
                    },
                    onSave = { cfg ->
                        gitTesting = true
                        gitConfigError = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    GitSyncClient(context, cfg).test()
                                    GitStore.save(context, cfg, gitEditId)
                                }
                            }
                            gitTesting = false
                            if (result.isSuccess) {
                                reloadGit()
                                showGitConfig = false
                                Toast.makeText(context, tr("仓库已连接并保存"), Toast.LENGTH_SHORT).show()
                            } else {
                                gitConfigError = result.exceptionOrNull()?.message ?: tr("未知错误")
                            }
                        }
                    },
                    onDelete = {
                        gitEditId?.let { GitStore.remove(context, it) }
                        reloadGit()
                        showGitConfig = false
                        if (gitFromSheet && gitProfiles.isNotEmpty()) showGitSheet = true
                    },
                )

                WebDavConfigDialog(
                    show = showWebDavConfig,
                    initial = webdavDialogInitial,
                    testing = webdavTesting,
                    error = webdavConfigError,
                    backdrop = sheetBackdrop,
                    onDismiss = {
                        if (!webdavTesting) {
                            showWebDavConfig = false
                            if (webdavFromSheet && webdavProfiles.isNotEmpty()) showWebDavSheet = true
                        }
                    },
                    onSave = { cfg ->
                        webdavTesting = true
                        webdavConfigError = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    WebDavClient(cfg).test()
                                    WebDavStore.save(context, cfg, webdavEditId)
                                }
                            }
                            webdavTesting = false
                            if (result.isSuccess) {
                                reloadWebDav()
                                showWebDavConfig = false
                                Toast.makeText(context, tr("服务器已连接并保存"), Toast.LENGTH_SHORT).show()
                            } else {
                                webdavConfigError = result.exceptionOrNull()?.message ?: tr("未知错误")
                            }
                        }
                    },
                    onDelete = {
                        webdavEditId?.let { WebDavStore.remove(context, it) }
                        reloadWebDav()
                        showWebDavConfig = false
                        if (webdavFromSheet && webdavProfiles.isNotEmpty()) showWebDavSheet = true
                    },
                )
            }

            BackupChunk {
                HistoryPickerDialog(
                    show = showHistory,
                    summary = if (historySource == ExportTarget.GIT) tr("仓库里的历史提交") else tr("服务器上的历史备份"),
                    items = historyItems.map { historyLabel(it, it === historyItems.firstOrNull()) },
                    selectedIndex = historyIndex,
                    onSelect = { historyIndex = it },
                    backdrop = sheetBackdrop,
                    onDismiss = { showHistory = false },
                    onConfirm = { restoreSelectedHistory() },
                )

                RestorePasswordDialog(
                    show = showRestorePassword,
                    backdrop = sheetBackdrop,
                    onDismiss = {
                        showRestorePassword = false
                        restoreUri = null
                        restoreBytes = null
                    },
                    onConfirm = { password ->
                        showRestorePassword = false
                        runRestore(password, fromDefault = false)
                    },
                )

                val plan = restorePlan
                RestoreConfirmDialog(
                    show = plan != null,
                    plan = plan,
                    selected = restoreSelected,
                    onToggle = { cat, on -> restoreSelected = if (on) restoreSelected + cat else restoreSelected - cat },
                    backdrop = sheetBackdrop,
                    onDismiss = { if (!restoring) restorePlan = null },
                    onConfirm = {
                        val p = restorePlan ?: return@RestoreConfirmDialog
                        restoring = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { BackupManager.applyRestore(context, p, restoreSelected) } }
                            if (result.isSuccess) {
                                Toast.makeText(context, tr("恢复完成，正在重启…"), Toast.LENGTH_SHORT).show()
                                delay(600)
                                BackupManager.restartApp(context)
                            } else {
                                restoring = false
                                restorePlan = null
                                Toast.makeText(
                                    context,
                                    failText(tr("恢复失败"), result.exceptionOrNull()) + tr("，建议重启后检查数据"),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                )
            }
        }
    }
}

/* ------------------------------ 通用小部件 ------------------------------ */

/**
 * 把一段 composable 内容放进独立的 lambda 方法里（不产生任何布局节点）。
 * 用来拆分 BackupScreen 里过大的 Scaffold 内容 lambda，避免 Kotlin 编译器生成超大方法而 OOM。
 */
@Composable
private fun BackupChunk(content: @Composable () -> Unit) {
    content()
}


/** 卡片下方的灰色说明。 */
@Composable
private fun Note(text: String, horizontal: androidx.compose.ui.unit.Dp = 28.dp) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = horizontal, vertical = 8.dp),
    )
}

/** sheet 里的一行：标题 + 副标题，可带右侧内容。 */
@Composable
private fun SheetRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

/**
 * 服务器 / 仓库 sheet：顶部是已连接过的配置（点一行编辑，右侧「使用」切换当前），下方「添加新配置」。
 */
@Composable
private fun ServerSheet(
    show: Boolean,
    title: String,
    addTitle: String,
    addSummary: String,
    rows: List<ProfileRow>,
    activeId: String?,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
    onUse: (String) -> Unit,
    onToggleSync: (String, Boolean) -> Unit,
    onAdd: () -> Unit,
) {
    AppBottomSheet(
        show = show,
        title = title,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            if (rows.isNotEmpty()) {
                SheetLabel(tr("已连接"))
                rows.forEach { row ->
                    val active = row.id == activeId
                    SheetRow(
                        title = row.title,
                        summary = row.subtitle,
                        onClick = { onEdit(row.id) },
                        leading = {
                            // 当前连接的显示绿点；其他行留同宽空位，标题对齐
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (active) Color(0xFF34C759) else Color.Transparent),
                            )
                        },
                        trailing = {
                            // 固定行高：当前行与其他行高度一致；文字一律不换行
                            Row(
                                modifier = Modifier.height(48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (active) {
                                    Text(
                                        text = tr("当前"),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                    )
                                } else {
                                    // 开关在上，「一并同步」在其下方，紧跟地址之后；「使用」在最右侧
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        AppSwitch(checked = row.syncAlso, onCheckedChange = { onToggleSync(row.id, it) })
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = tr("一并同步"),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    Text(
                                        text = tr("使用"),
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onUse(row.id) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        },
                    )
                }
                Note(tr("自动推送时，除当前连接的以外，开启「一并同步」的也会同步。"), horizontal = 4.dp)
            }
            SheetLabel(tr("新增"))
            SheetRow(title = addTitle, summary = addSummary, onClick = onAdd)
        }
    }
}

/** 弹窗里的输入区：字段多时可滚动，按钮留在外面始终可见。 */
@Composable
private fun DialogFields(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun FieldHint(text: String?) {
    if (text != null) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

/** 弹窗底部按钮：取消 + 主操作；[onDelete] 不为空时下方多一个「删除此配置」。 */
@Composable
private fun DialogActions(
    confirmText: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelEnabled: Boolean = true,
    confirmRole: GlassDialogButtonRole = GlassDialogButtonRole.Primary,
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = true,
    deleteText: String = tr("删除此配置"),
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppDialogButton(onClick = onCancel, enabled = cancelEnabled, modifier = Modifier.weight(1f)) {
                Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
            }
            AppDialogButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = Modifier.weight(1f),
                role = confirmRole,
            ) {
                Text(text = confirmText, style = MiuixTheme.textStyles.button)
            }
        }
        if (onDelete != null) {
            AppDialogButton(
                onClick = onDelete,
                enabled = deleteEnabled,
                modifier = Modifier.fillMaxWidth(),
                role = GlassDialogButtonRole.Destructive,
            ) {
                Text(text = deleteText, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/* ------------------------------ 弹窗 ------------------------------ */

/** 备份内容：每类一行开关，点开关即启停，改动立即保存。 */
@Composable
private fun CategoryDialog(
    show: Boolean,
    backdrop: Backdrop?,
    selected: Set<BackupCategory>,
    onToggle: (BackupCategory, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AppGlassDialog(
        show = show,
        title = tr("备份内容"),
        summary = tr("所有备份方式共用，改动立即生效") +
            if (BackupCategory.COOKIES in selected || BackupCategory.PASSWORDS in selected || BackupCategory.FXA in selected) tr("\n密码、Cookie 和 Firefox 帐户登录状态含登录凭据，请妥善保管备份文件与口令。") else "",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                BackupCategory.entries.forEach { cat ->
                    AppSwitchPreference(
                        checked = cat in selected,
                        onCheckedChange = { on -> onToggle(cat, on) },
                        title = cat.title,
                        summary = cat.summary,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                AppDialogButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = tr("完成"), style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/** 历史备份选择：抽象层下拉菜单选一份，点「恢复此份」后下载并进入口令 / 确认流程。 */
@Composable
private fun HistoryPickerDialog(
    show: Boolean,
    summary: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppGlassDialog(
        show = show,
        title = tr("选择要恢复的备份"),
        summary = summary,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AppDropdownPreference(
                backdrop = backdrop,
                title = tr("历史备份"),
                items = items,
                selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                onSelectedIndexChange = onSelect,
                enabled = items.isNotEmpty(),
                insideMargin = PaddingValues(vertical = 12.dp),
            )
            DialogActions(
                confirmText = tr("恢复此份"),
                confirmEnabled = items.isNotEmpty(),
                onConfirm = onConfirm,
                onCancel = onDismiss,
            )
        }
    }
}

@Composable
private fun PasswordDialog(
    show: Boolean,
    backdrop: Backdrop?,
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
        title = tr("设置备份口令"),
        summary = tr("仅用于加密，忘记后无法找回"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DialogFields {
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("口令（至少 {0} 位）", MIN_PASSWORD_LEN),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                TextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("再次输入"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            FieldHint(if (tooShort) tr("口令至少 {0} 位", MIN_PASSWORD_LEN) else if (mismatch) tr("两次输入不一致") else null)
            DialogActions(
                confirmText = confirmText,
                confirmEnabled = valid,
                onConfirm = { onConfirm(password.toCharArray()) },
                onCancel = onDismiss,
            )
        }
    }
}

/** 默认加密口令弹窗：备份页与首次引导共用。 */
@Composable
internal fun DefaultPasswordDialog(
    show: Boolean,
    isSet: Boolean,
    initial: String,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var password by remember(show) { mutableStateOf(initial) }
    var confirm by remember(show) { mutableStateOf(initial) }
    var visible by remember(show) { mutableStateOf(false) }
    val transform = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LEN
    val mismatch = confirm.isNotEmpty() && confirm != password
    val valid = password.length >= MIN_PASSWORD_LEN && password == confirm

    AppGlassDialog(
        show = show,
        title = tr("默认加密口令"),
        summary = if (isSet) tr("输入新口令可替换；加密存本机，忘记后无法找回备份") else tr("加密存本机，忘记后无法找回备份"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DialogFields {
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("口令（至少 {0} 位）", MIN_PASSWORD_LEN),
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                TextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("再次输入"),
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(
                    text = if (visible) tr("隐藏口令") else tr("显示口令"),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { visible = !visible }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            FieldHint(if (tooShort) tr("口令至少 {0} 位", MIN_PASSWORD_LEN) else if (mismatch) tr("两次输入不一致") else null)
            DialogActions(
                confirmText = tr("保存"),
                confirmEnabled = valid,
                onConfirm = { onSave(password) },
                onCancel = onDismiss,
                onDelete = if (isSet) onClear else null,
                deleteText = tr("清除默认口令"),
            )
        }
    }
}

@Composable
private fun RestorePasswordDialog(
    show: Boolean,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember(show) { mutableStateOf("") }

    AppGlassDialog(
        show = show,
        title = tr("输入备份口令"),
        summary = tr("导出这份备份时设置的口令"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = tr("口令"),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            DialogActions(
                confirmText = tr("解密"),
                confirmEnabled = password.isNotEmpty(),
                onConfirm = { onConfirm(password.toCharArray()) },
                onCancel = onDismiss,
            )
        }
    }
}

@Composable
private fun RestoreConfirmDialog(
    show: Boolean,
    plan: BackupManager.RestorePlan?,
    selected: Set<BackupCategory>,
    onToggle: (BackupCategory, Boolean) -> Unit,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val summary = if (plan == null) {
        ""
    } else {
        buildString {
            append(formatTime(plan.createdAt))
            if (plan.versionName.isNotBlank()) append(" · v").append(plan.versionName.removePrefix("v"))
            append(" · ").append(plan.suiteName)
        }
    }
    val unavailableSet = plan?.unavailable.orEmpty()
    val chosen = plan?.categories.orEmpty().filter { it in selected && it !in unavailableSet }
    // Cookie 跨内核恢复（Gecko 备份 → WebView，或反过来）：字段一致可以恢复，但 SameSite 等细节两个内核处理略有差别
    val appContext = LocalContext.current
    val cookieEngineLabel = { engine: String -> if (engine == BackupManager.COOKIE_ENGINE_WEBVIEW) "WebView" else "Gecko" }
    val cookieCrossNote = plan?.cookieEngine
        ?.takeIf { BackupCategory.COOKIES in selected && it != BackupManager.currentCookieEngine(appContext) }
        ?.let {
            tr("Cookie 来自 {0} 内核，将恢复到当前的 {1} 内核；", cookieEngineLabel(it), cookieEngineLabel(BackupManager.currentCookieEngine(appContext))) +
                tr("SameSite 等细节两个内核处理略有差别，个别站点可能需要重新登录。")
        }
    AppGlassDialog(
        show = show,
        title = tr("恢复这份备份？"),
        summary = summary,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = tr("备份里有以下内容，勾选的会覆盖当前对应的数据；没有勾选 / 备份里没有的保持原样。"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                plan?.categories.orEmpty().forEach { cat ->
                    val unavailable = cat in unavailableSet
                    AppSwitchPreference(
                        checked = !unavailable && cat in selected,
                        onCheckedChange = { onToggle(cat, it) },
                        title = cat.title,
                        summary = when {
                            unavailable -> tr("本机不支持，无法恢复")
                            cat == BackupCategory.PASSWORDS -> tr("与本机合并，不删除本机已有的密码")
                            cat == BackupCategory.FXA -> tr("覆盖本机的 Firefox 帐户登录状态；原设备若还在用，两台会共用同一设备身份")
                            else -> cat.summary
                        },
                        enabled = !unavailable,
                    )
                }
            }
            if (cookieCrossNote != null) {
                Text(
                    text = cookieCrossNote,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                text = tr("覆盖后无法撤销，完成后自动重启。"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            DialogActions(
                confirmText = if (chosen.isEmpty()) tr("恢复并重启") else tr("恢复 {0} 项并重启", chosen.size),
                confirmEnabled = chosen.isNotEmpty(),
                onConfirm = onConfirm,
                onCancel = onDismiss,
                confirmRole = GlassDialogButtonRole.Destructive,
            )
        }
    }
}

/** 卡片里的「状态行 + 左右两个操作按钮」：主操作（上传 / 推送 / 导出）在左，恢复在右。 */
@Composable
private fun ActionPair(
    status: String?,
    primary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondary: String,
    secondaryEnabled: Boolean,
    onSecondary: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 4.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = topPadding, bottom = 16.dp),
    ) {
        if (status != null) {
            Text(
                text = status,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppDialogButton(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Primary,
                inPage = true,
            ) {
                Text(text = primary, style = MiuixTheme.textStyles.button, maxLines = 1)
            }
            AppDialogButton(onClick = onSecondary, enabled = secondaryEnabled, modifier = Modifier.weight(1f), inPage = true) {
                Text(text = secondary, style = MiuixTheme.textStyles.button, maxLines = 1)
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
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
    onDelete: () -> Unit,
) {
    var url by remember(show) { mutableStateOf(initial?.url.orEmpty()) }
    var user by remember(show) { mutableStateOf(initial?.username.orEmpty()) }
    var pass by remember(show) { mutableStateOf(initial?.password.orEmpty()) }
    val normalized = WebDavNames.normalizeBaseUrl(url)

    AppGlassDialog(
        show = show,
        title = if (initial != null) tr("编辑服务器") else tr("添加服务器"),
        summary = tr("备份存于 {0} 文件夹，密码加密存本机", WebDavNames.REMOTE_DIR),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DialogFields {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("服务器地址（https://…）"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                TextField(
                    value = user,
                    onValueChange = { user = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("用户名"),
                    singleLine = true,
                )
                TextField(
                    value = pass,
                    onValueChange = { pass = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("密码"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            FieldHint(
                error ?: if (url.isNotBlank() && normalized == null) tr("地址需以 http:// 或 https:// 开头") else null,
            )
            DialogActions(
                confirmText = if (testing) tr("连接中…") else tr("测试并保存"),
                confirmEnabled = normalized != null && !testing,
                onConfirm = { normalized?.let { onSave(WebDavConfig(it, user.trim(), pass)) } },
                onCancel = onDismiss,
                cancelEnabled = !testing,
                onDelete = if (initial != null) onDelete else null,
                deleteEnabled = !testing,
            )
        }
    }
}

@Composable
private fun GitConfigDialog(
    show: Boolean,
    initial: GitConfig?,
    testing: Boolean,
    error: String?,
    backdrop: Backdrop?,
    importedKey: String?,
    onImportedKeyConsumed: () -> Unit,
    onPickKey: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (GitConfig) -> Unit,
    onDelete: () -> Unit,
) {
    var useKey by remember(show) { mutableStateOf(initial?.useSsh == true) }
    var url by remember(show) { mutableStateOf(initial?.url.orEmpty()) }
    var user by remember(show) { mutableStateOf(initial?.username.orEmpty()) }
    var token by remember(show) { mutableStateOf(initial?.token.orEmpty()) }
    var key by remember(show) { mutableStateOf(initial?.deployKey.orEmpty()) }
    var keyPass by remember(show) { mutableStateOf(initial?.keyPassphrase.orEmpty()) }
    var branch by remember(show) { mutableStateOf(initial?.branch.orEmpty()) }

    LaunchedEffect(importedKey) {
        if (importedKey != null) {
            key = importedKey
            useKey = true
            onImportedKeyConsumed()
        }
    }

    val normalizedUrl = GitNames.normalizeAddress(url, useKey)
    val normalizedBranch = GitNames.normalizeBranch(branch)
    val normalizedKey = if (useKey) GitNames.normalizePrivateKey(key) else null
    val valid = normalizedUrl != null && normalizedBranch != null && (!useKey || normalizedKey != null)

    AppGlassDialog(
        show = show,
        title = if (initial != null) tr("编辑仓库") else tr("添加仓库"),
        summary = if (useKey) tr("Deploy key 需写权限，加密存本机") else tr("令牌填在下方，勿写进地址"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            GlassTabRow(
                tabs = listOf(tr("令牌"), "Deploy key"),
                selectedIndex = if (useKey) 1 else 0,
                onSelect = { if (!testing) useKey = it == 1 },
                backdrop = null,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            DialogFields {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = if (useKey) tr("仓库地址（git@主机:用户/仓库.git）") else tr("仓库地址（https://…）"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (useKey) {
                    TextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("私钥（粘贴 -----BEGIN … 全文）"),
                        minLines = 2,
                        maxLines = 3,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    AppDialogButton(
                        onClick = onPickKey,
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = tr("从文件导入私钥"), style = MiuixTheme.textStyles.button)
                    }
                    TextField(
                        value = keyPass,
                        onValueChange = { keyPass = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("私钥口令（没有则留空）"),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                } else {
                    TextField(
                        value = user,
                        onValueChange = { user = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("用户名（可选）"),
                        singleLine = true,
                    )
                    TextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("访问令牌 / 密码"),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                TextField(
                    value = branch,
                    onValueChange = { branch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("分支（留空用默认）"),
                    singleLine = true,
                )
            }
            FieldHint(
                when {
                    error != null -> error
                    url.isNotBlank() && normalizedUrl == null ->
                        if (useKey) tr("SSH 地址形如 git@主机:用户/仓库.git") else tr("地址需为 http(s)://主机/路径，且不含账号密码")
                    useKey && key.isNotBlank() && normalizedKey == null -> tr("私钥格式不对：需要 OpenSSH / PEM 文本")
                    normalizedBranch == null -> tr("分支名不合法")
                    else -> null
                },
            )
            DialogActions(
                confirmText = if (testing) tr("连接中…") else tr("测试并保存"),
                confirmEnabled = valid && !testing,
                onConfirm = {
                    if (normalizedUrl != null && normalizedBranch != null) {
                        onSave(
                            if (useKey && normalizedKey != null) {
                                GitConfig(normalizedUrl, "", "", normalizedBranch, normalizedKey, keyPass)
                            } else {
                                GitConfig(normalizedUrl, user.trim(), token, normalizedBranch)
                            },
                        )
                    }
                },
                onCancel = onDismiss,
                cancelEnabled = !testing,
                onDelete = if (initial != null) onDelete else null,
                deleteEnabled = !testing,
            )
        }
    }
}
