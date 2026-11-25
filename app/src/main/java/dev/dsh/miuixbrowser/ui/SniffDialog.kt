package dev.dsh.miuixbrowser.ui

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.sniff.MediaSniff
import dev.dsh.miuixbrowser.sniff.SniffedMedia
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 点地址栏媒体图标打开的「发现视频」列表（NATIVE_PLAYER.md 阶段四；不会主动弹出）：列出本页发现的视频地址，点一条就用原生播放器打开，
 * 其余地址作为可切换的源一起交给播放页。对话框走材质抽象层，和其它应用内弹窗一致。
 *
 * [items] 为空时（页面已换）自动收起；收起动画期间保留最后一次的内容，避免列表先消失。
 */
@Composable
internal fun SniffDialog(
    show: Boolean,
    items: List<SniffedMedia>,
    backdrop: Backdrop?,
    onPlay: (SniffedMedia) -> Unit,
    onDismiss: () -> Unit,
) {
    val keep = remember { arrayOf<List<SniffedMedia>>(emptyList()) }
    if (items.isNotEmpty()) keep[0] = items
    val shown = keep[0]
    AppGlassDialog(
        show = show && items.isNotEmpty(),
        title = tr("发现视频"),
        summary = tr("用原生播放器打开，支持画中画和后台播放"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                shown.forEach { m -> SniffRow(m, onClick = { onPlay(m) }) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = tr("忽略"), style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun SniffRow(m: SniffedMedia, onClick: () -> Unit) {
    val context = LocalContext.current
    val detail = buildString {
        append(m.type.uppercase())
        append(" · ")
        append(m.host)
        if (m.size > 0) append(" · ").append(Formatter.formatFileSize(context, m.size))
        // 时长：页面 <video> 报的或后台探出来的；还没出结果就先不显示，出来后这一行自动补上
        if (m.durationMs == MediaSniff.DURATION_LIVE) append(" · ").append(tr("直播"))
        else MediaSniff.formatDuration(m.durationMs)?.let { append(" · ").append(it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleClip(12.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            imageVector = AppIcons.Tv,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = MediaSniff.displayName(m),
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
