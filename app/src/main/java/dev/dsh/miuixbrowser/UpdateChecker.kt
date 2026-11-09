package dev.dsh.miuixbrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialogButton
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

    fun download(context: Context, rel: AppRelease, openInApp: ((String) -> Unit)? = null) {
        val apk = rel.apkUrl
        if (apk.isNullOrBlank()) {
            // 无 APK：优先在应用内浏览页打开发布页，回退系统浏览器
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
            return
        }
        Toast.makeText(context, "正在选择下载线路…", Toast.LENGTH_SHORT).show()
        // 直连 + GitHub 加速并发探测，连通者优先；探测在后台，回主线程再入下载队列
        Thread {
            val url = GitHubAccelerate.pickDownloadUrl(apk)
            android.os.Handler(context.mainLooper).post {
                Downloader.start(
                    context = context,
                    url = url,
                    userAgent = null,
                    fileName = "mafari-${rel.version.removePrefix("v")}.apk",
                    mimeType = "application/vnd.android.package-archive",
                )
                Toast.makeText(context, "已开始下载，完成后可在下载页安装", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}

/**
 * 是否有可更新的版本（启动自动检查与「检查更新」按钮共用）：
 * - stable / dev：按版本号逐段比较；
 * - gecko 预发布：tag 是 CI 构建号（gecko-123），不能和 v99.x 的版本号比（构建号一过 99 就永远「更新」）。
 *   本机有构建号（BuildConfig.GECKO_BUILD）时比构建号；老包没有构建号时，看安装时间是否晚于该预发布的发布时间。
 */
fun isUpdateAvailable(context: Context, rel: AppRelease, channel: Int): Boolean {
    if (channel != 2) return isNewerVersion(rel.version, BuildConfig.VERSION_NAME)
    val remote = geckoBuildOf(rel.version)
    if (remote == 0) return false
    if (BuildConfig.GECKO_BUILD > 0) return remote > BuildConfig.GECKO_BUILD
    if (rel.publishedAt == 0L) return false
    val installedAt = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
    return rel.publishedAt > installedAt
}

/** 「已是最新版本」提示里显示的当前版本 */
fun currentVersionLabel(channel: Int): String =
    if (channel == 2 && BuildConfig.GECKO_BUILD > 0) "gecko-${BuildConfig.GECKO_BUILD}"
    else BuildConfig.VERSION_NAME.removePrefix("v")

/**
 * 版本比较：按数字段逐段比较（v1.3.10 > v1.3.9）。
 * 数字完全相同时，本地若是 dev/alpha/beta/rc 预览版而远端是正式版，视为远端更新。
 */
fun isNewerVersion(remote: String, local: String): Boolean {
    fun nums(v: String) = Regex("\\d+").findAll(v).map { it.value.toLongOrNull() ?: 0L }.toList()
    val r = nums(remote)
    val l = nums(local)
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0L }
        val b = l.getOrElse(i) { 0L }
        if (a != b) return a > b
    }
    fun pre(v: String) = Regex("(dev|alpha|beta|rc|snapshot)", RegexOption.IGNORE_CASE).containsMatchIn(v)
    return pre(local) && !pre(remote)
}

/** 发现新版本的底部弹窗：下载 / 忽略此版本。下滑关闭等同“稍后再说”，下次启动仍会提示。 */
@Composable
fun UpdateSheet(
    show: Boolean,
    release: AppRelease?,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onIgnore: (AppRelease) -> Unit,
    onDownload: (AppRelease) -> Unit,
) {
    AppBottomSheet(
        show = show,
        title = "发现新版本",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        if (release == null) return@AppBottomSheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = (if (geckoBuildOf(release.version) > 0 && BuildConfig.GECKO_BUILD > 0) {
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
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = release.notes,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = { onIgnore(release) }, modifier = Modifier.weight(1f)) {
                    Text(text = "忽略此版本", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { onDownload(release) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(
                        text = if (release.apkUrl.isNullOrBlank()) "查看更新" else "下载",
                        style = MiuixTheme.textStyles.button,
                    )
                }
            }
        }
    }
}
