package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.script.CastPickerUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 脚本悬浮播放器「投屏」按钮弹出的「投屏到」面板。
 * 状态由 [dev.dsh.miuixbrowser.script.MediaHost.pickerUi] 提供；对话框与按钮走材质抽象层（AppGlassDialog / AppDialogButton），
 * 随「高级材质」三选一变化，和其它应用内弹窗一致。
 */
@Composable
internal fun CastPickerDialog(ui: CastPickerUi, backdrop: Backdrop?) {
    AppGlassDialog(
        show = ui.show,
        title = tr("投屏到"),
        backdrop = backdrop,
        onDismissRequest = { ui.cancel() },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (ui.scanning) tr("正在搜索…") else tr("附近设备"),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    val spin = rememberInfiniteTransition(label = "castRefresh")
                    val angle by spin.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                        label = "angle",
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !ui.scanning) { ui.refresh() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            imageVector = AppIcons.Refresh,
                            contentDescription = tr("重新搜索"),
                            modifier = Modifier.size(20.dp).rotate(if (ui.scanning) angle else 0f),
                            colorFilter = ColorFilter.tint(
                                if (ui.scanning) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                ui.devices.forEach { d ->
                    CastRow(text = d.name, onClick = { ui.pickDevice(d) })
                }
                if (ui.devices.isEmpty()) {
                    Text(
                        text = ui.status,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CastTile(
                        icon = AppIcons.ScreenMirror,
                        title = tr("屏幕镜像"),
                        hint = tr("系统投屏，投出整个画面"),
                        onClick = { ui.pickMirror() },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    CastTile(
                        icon = AppIcons.OpenExternal,
                        title = tr("外部打开"),
                        hint = tr("交给播放器或投屏 App"),
                        onClick = { ui.pickExternal() },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = { ui.cancel() }, modifier = Modifier.weight(1f)) {
                    Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun CastRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            imageVector = AppIcons.Tv,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}

/** 屏幕镜像 / 外部打开：并排的大圆角矩形，图标 + 标题 + 一行小字说明 */
@Composable
private fun CastTile(icon: ImageVector, title: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
        )
        Spacer(Modifier.height(6.dp))
        Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Text(
            text = hint,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
