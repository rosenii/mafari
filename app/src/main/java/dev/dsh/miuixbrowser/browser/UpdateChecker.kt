package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.BuildConfig
import dev.dsh.miuixbrowser.download.DownloadState
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.net.GitHubAccelerate
import dev.dsh.miuixbrowser.ui.AppBottomSheet
import dev.dsh.miuixbrowser.ui.AppDialogButton
import dev.dsh.miuixbrowser.ui.AppRelease
import dev.dsh.miuixbrowser.ui.MarkdownText
import dev.dsh.miuixbrowser.ui.UpdateChannel
import dev.dsh.miuixbrowser.ui.ciBuildOf
import dev.dsh.miuixbrowser.ui.fetchLatestRelease
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 启动时自动检查更新的进程级状态：同一进程只检查一次（旋转屏幕等重组不会重复请求）。 */
object UpdateChecker {
    @Volatile
    var checkedThisProcess = false

    /** 有可提示的新版本则返回，否则 null（网络失败、已是最新、已被用户忽略都返回 null）。 */
    fun findPending(context: Context, ignored: String, channel: Int): AppRelease? {
        val rel = fetchLatestRelease(channel) ?: return null
        if (!isUpdateAvailable(context, rel, channel)) return null
        if (rel.version.removePrefix("v") == ignored.removePrefix("v")) return null
        return rel
    }

    /** 没有 APK 资产：优先在应用内浏览页打开发布页，回退系统浏览器 */
    fun openReleasePage(context: Context, rel: AppRelease, openInApp: ((String) -> Unit)?) {
        val page = rel.pageUrl
        if (openInApp != null && page.isNotBlank()) {
            openInApp(page)
        } else {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

enum class UpdatePhase { IDLE, PICKING, DOWNLOADING, DONE, FAILED }

/**
 * 应用更新的下载 / 安装状态（进程级，启动提示与「关于」页共用）：
 * 选线路 → 内置下载器下载（进度直接显示在更新弹窗里）→ 下载完成 → 唤起系统安装器。
 * 同一时间只跟踪一个版本；弹窗收起不影响下载，取消会删掉未完成的文件。
 */
object UpdateDownload {
    var phase by mutableStateOf(UpdatePhase.IDLE)
        private set

    /** 正在 / 已下载的版本（release.version）；弹窗据此判断状态是否属于自己展示的版本 */
    var version by mutableStateOf("")
        private set

    var taskId by mutableLongStateOf(0L)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 谁发起的下载（"launch" 启动提示 / "about" 关于页）；下载完成时由发起方把收起的弹窗重新弹出 */
    var origin by mutableStateOf("")
        private set

    private var apkUri: Uri? = null

    /** 每次开始 / 取消都 +1，用来丢弃过期的回调（选线路线程、下载完成回调） */
    private var generation = 0

    private const val APK_MIME = "application/vnd.android.package-archive"

    fun start(context: Context, rel: AppRelease, origin: String, openInApp: ((String) -> Unit)? = null) {
        val apk = rel.apkUrl
        if (apk.isNullOrBlank()) {
            UpdateChecker.openReleasePage(context, rel, openInApp)
            return
        }
        if (phase == UpdatePhase.PICKING || phase == UpdatePhase.DOWNLOADING) return
        val appCtx = context.applicationContext
        val gen = ++generation
        this.origin = origin
        version = rel.version
        error = null
        apkUri = null
        taskId = 0L
        phase = UpdatePhase.PICKING
        val fileName = apk.substringBefore('?').substringAfterLast('/')
            .ifBlank { "mafari-${rel.version.removePrefix("v")}.apk" }
        // 直连 + GitHub 加速并发探测，连通者优先；探测在后台，回主线程再入下载队列
        Thread {
            val url = runCatching { GitHubAccelerate.pickDownloadUrl(apk) }.getOrDefault(apk)
            Handler(Looper.getMainLooper()).post {
                if (gen != generation || phase != UpdatePhase.PICKING) return@post // 选线路期间被取消
                taskId = Downloader.startForUpdate(appCtx, url, fileName, APK_MIME) { ok, err ->
                    onFinished(appCtx, gen, ok, err)
                }
                phase = UpdatePhase.DOWNLOADING
            }
        }.start()
    }

    private fun onFinished(context: Context, gen: Int, ok: Boolean, err: String?) {
        if (gen != generation || phase != UpdatePhase.DOWNLOADING) return
        if (ok) {
            apkUri = Downloader.tasks.firstOrNull { it.id == taskId }?.uri
            if (apkUri == null) {
                error = tr("找不到下载好的安装包")
                phase = UpdatePhase.FAILED
            } else {
                phase = UpdatePhase.DONE
                Toast.makeText(context, tr("更新已下载完成，点击安装"), Toast.LENGTH_SHORT).show()
            }
        } else if (err == "cancelled") {
            // 在下载页里删掉了任务
            phase = UpdatePhase.IDLE
        } else {
            error = err ?: tr("未知错误")
            phase = UpdatePhase.FAILED
        }
    }

    /** 取消下载（选线路 / 下载中 / 失败后）：删掉任务和未完成的文件，回到「下载」状态 */
    fun cancel(context: Context) {
        generation++
        val id = taskId
        if (id != 0L && phase != UpdatePhase.DONE) Downloader.delete(context.applicationContext, id)
        taskId = 0L
        error = null
        apkUri = null
        phase = UpdatePhase.IDLE
    }

    /** 直接唤起系统默认安装器打开安装包；安装来源授权由系统安装器自己处理 */
    fun install(context: Context) {
        val uri = apkUri
        val readable = uri != null && runCatching { context.contentResolver.openInputStream(uri)?.close() }.isSuccess
        if (uri == null || !readable) {
            // 安装包被删了（比如在下载页里删除）
            Toast.makeText(context, tr("安装包已不存在，请重新下载"), Toast.LENGTH_SHORT).show()
            apkUri = null
            taskId = 0L
            phase = UpdatePhase.IDLE
            return
        }
        // 直接把安装包交给系统安装器：未授权「安装未知应用」时，系统安装器会自己弹出授权提示，
        // 不需要先跳到本应用的授权设置页
        val ok = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!ok) Toast.makeText(context, tr("无法唤起系统安装器，请在下载页打开安装包"), Toast.LENGTH_LONG).show()
    }
}

/**
 * 是否有可更新的版本（启动自动检查与「检查更新」按钮共用）：
 * - 通道变体与已装变体不同（含 lite ⇄ sync_lite）：一律视为有更新（切换变体）；
 * - 正式发布（lite / gecko 通道，以及 canary 里选到的正式 release）：按版本号逐段比较；
 * - CI 构建预发布（tag 是 gecko-123，不能和 v99.x 的版本号比）：
 *   本机有构建号（BuildConfig.GECKO_BUILD）时比构建号；老包没有构建号时，看安装时间是否晚于该预发布的发布时间。
 */
fun isUpdateAvailable(context: Context, rel: AppRelease, channel: Int): Boolean {
    // 通道变体与已装变体不同（lite / sync_lite / gecko 互相切换）：版本号相同也推送，用于切换变体
    if (UpdateChannel.flavorOf(channel) != UpdateChannel.installedFlavor()) return true
    val remote = ciBuildOf(rel.version)
    if (remote == 0) return isNewerVersion(rel.version, BuildConfig.VERSION_NAME)
    if (BuildConfig.GECKO_BUILD > 0) return remote > BuildConfig.GECKO_BUILD
    if (rel.publishedAt == 0L) return false
    val installedAt = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
    return rel.publishedAt > installedAt
}

/** 「已是最新版本」提示里显示的当前版本 */
fun currentVersionLabel(@Suppress("UNUSED_PARAMETER") channel: Int): String =
    if (BuildConfig.GECKO_BUILD > 0) "gecko-${BuildConfig.GECKO_BUILD}"
    else BuildConfig.VERSION_NAME.removePrefix("v")

/**
 * 版本比较：只比 `-` 之前的数字段，逐段比较（v1.3.10 > v1.3.9）；后缀（-canary 等）里的数字不参与。
 * 数字完全相同时，本地若是 canary / dev / alpha / beta / rc 预览版而远端是正式版，视为远端更新；
 * 两边都是预览版、数字相同则不算更新（要推送新的 canary，请先提高版本名）。
 */
fun isNewerVersion(remote: String, local: String): Boolean {
    fun nums(v: String) = Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toLongOrNull() ?: 0L }.toList()
    val r = nums(remote)
    val l = nums(local)
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0L }
        val b = l.getOrElse(i) { 0L }
        if (a != b) return a > b
    }
    fun pre(v: String) = Regex("(dev|alpha|beta|rc|snapshot|canary)", RegexOption.IGNORE_CASE).containsMatchIn(v)
    return pre(local) && !pre(remote)
}

/**
 * 发现新版本的底部弹窗：更新日志（Markdown）→ 点「下载」后进度直接显示在弹窗里：
 * 下载中按钮是「收起 / 取消下载」，下载完成变成「安装」。
 * 下滑关闭 / 收起 = 稍后再说（下载继续在后台进行）。
 *
 * @param onIgnore 启动提示里有「忽略此版本」；「关于」页里传 null，第一个按钮是「取消」。
 */
@Composable
fun UpdateSheet(
    show: Boolean,
    release: AppRelease?,
    backdrop: Backdrop?,
    origin: String,
    onDismiss: () -> Unit,
    onIgnore: ((AppRelease) -> Unit)? = null,
    openInApp: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val tracked = release != null && release.version == UpdateDownload.version
    val phase = if (tracked) UpdateDownload.phase else UpdatePhase.IDLE
    AppBottomSheet(
        show = show,
        title = if (phase == UpdatePhase.DONE) tr("更新已就绪") else tr("发现新版本"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        if (release == null) return@AppBottomSheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = (if (BuildConfig.GECKO_BUILD > 0) {
                    "gecko-${BuildConfig.GECKO_BUILD}"
                } else {
                    BuildConfig.VERSION_NAME
                }) + "  →  " + release.version,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(markdown = release.notes)
                }
            }

            // 下载进度（选线路 / 下载中）与失败原因
            if (phase == UpdatePhase.PICKING || phase == UpdatePhase.DOWNLOADING) {
                val task = Downloader.tasks.firstOrNull { it.id == UpdateDownload.taskId }
                val progress = if (phase == UpdatePhase.DOWNLOADING && task != null && task.total > 0) {
                    Downloader.progressOf(task)
                } else null
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = when {
                            phase == UpdatePhase.PICKING -> tr("正在选择下载线路…")
                            task?.state == DownloadState.PAUSED -> tr("已暂停（可在下载页继续）")
                            else -> Downloader.progressSizeText(task?.bytes ?: 0L, task?.total ?: 0L).ifBlank { tr("正在连接…") }
                        },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (progress != null) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else if (phase == UpdatePhase.FAILED) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = tr("下载失败：{0}", UpdateDownload.error ?: tr("未知错误")),
                    fontSize = 13.sp,
                    color = Color(0xFFE5484D),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (phase) {
                    UpdatePhase.PICKING, UpdatePhase.DOWNLOADING -> {
                        AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text(text = tr("收起"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = { UpdateDownload.cancel(context) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = tr("取消下载"), style = MiuixTheme.textStyles.button)
                        }
                    }
                    UpdatePhase.DONE -> {
                        AppDialogButton(
                            onClick = { UpdateDownload.install(context) },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Primary,
                        ) {
                            Text(text = tr("安装"), style = MiuixTheme.textStyles.button)
                        }
                    }
                    UpdatePhase.FAILED -> {
                        AppDialogButton(
                            onClick = {
                                UpdateDownload.cancel(context)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = tr("关闭"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                UpdateDownload.cancel(context)
                                UpdateDownload.start(context, release, origin, openInApp)
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Primary,
                        ) {
                            Text(text = tr("重试"), style = MiuixTheme.textStyles.button)
                        }
                    }
                    UpdatePhase.IDLE -> {
                        AppDialogButton(
                            onClick = { if (onIgnore != null) onIgnore(release) else onDismiss() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = if (onIgnore != null) tr("忽略此版本") else tr("取消"), style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                UpdateDownload.start(context, release, origin, openInApp)
                                // 没有安装包时只是打开发布页，弹窗收起
                                if (release.apkUrl.isNullOrBlank()) onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Primary,
                        ) {
                            Text(
                                text = if (release.apkUrl.isNullOrBlank()) tr("查看更新") else tr("下载"),
                                style = MiuixTheme.textStyles.button,
                            )
                        }
                    }
                }
            }
        }
    }
}
