package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.R
import dev.dsh.miuixbrowser.SendTabActivity
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.sync.fxa.FxaUi
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.blur.Backdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 调起系统分享面板（纯文本链接）。已登录 Firefox 帐户时，在 Android 14+ 的分享面板顶部操作行里
 * 加一个「发送到设备」（和 Firefox 一样，EXTRA_CHOOSER_CUSTOM_ACTIONS），点它由 [SendTabActivity] 选设备发送。
 */
fun shareUrlSystem(context: Context, url: String, title: String = "") {
    if (url.isBlank()) return
    runCatching {
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            },
            tr("分享链接"),
        )
        if (android.os.Build.VERSION.SDK_INT >= 34 && canSendTab()) {
            runCatching {
                val pi = android.app.PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, SendTabActivity::class.java)
                        .putExtra(SendTabActivity.EXTRA_URL, url)
                        .putExtra(SendTabActivity.EXTRA_TITLE, title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val action = android.service.chooser.ChooserAction.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_send_device),
                    tr("发送到设备"),
                    pi,
                ).build()
                chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(action))
            }
        }
        context.startActivity(chooser)
    }
}

/** Android 13 的分享面板没有自定义操作：改由应用内对话框先选「发到其他设备」或「系统分享」。 */
fun needsInAppShareDialog(): Boolean = android.os.Build.VERSION.SDK_INT < 34 && canSendTab()

/** 已登录 Firefox 帐户（且本包带同步）时，分享要先问「发到哪」：其他设备（Send Tab）或系统分享。 */
fun canSendTab(): Boolean = EngineSupport.SYNC_BUNDLED && FxaSync.ui is FxaUi.SignedIn

/**
 * 分享对话框：列出同一 Firefox 帐户下的其他设备，点一下把当前页发过去（Firefox「发送标签页」）；
 * 底部「更多分享方式」走系统分享面板。
 */
@Composable
fun ShareDialog(
    show: Boolean,
    title: String,
    url: String,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(show) { if (show) FxaSync.refreshDevices() }
    AppGlassDialog(
        show = show,
        title = tr("分享"),
        summary = title.ifBlank { url },
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            val devices = FxaSync.devices
            Text(
                text = tr("发送到其他设备"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (devices.isEmpty()) {
                Text(
                    text = tr("没有其他设备：请在其他设备上登录同一个 Firefox 帐户"),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    devices.forEach { d ->
                        Text(
                            text = d.name,
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    scope.launch {
                                        val ok = FxaSync.sendTabResult(d, title, url)
                                        Toast.makeText(
                                            context,
                                            if (ok) tr("已发送到 {0}", d.name) else tr("发送失败"),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { onDismiss(); shareUrlSystem(context, url, title) },
                    modifier = Modifier.weight(1f),
                    role = top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary,
                ) {
                    Text(text = tr("更多分享方式"), style = MiuixTheme.textStyles.button, maxLines = 1)
                }
            }
        }
    }
}
