package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.data.UA_PRESETS
import dev.dsh.miuixbrowser.data.rememberFirstIcon
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.ui.icons.Settings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.glass.HorizontalSplit
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Phone
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* 浏览器页内弹窗（从 BrowserScreen 拆出）：UA 选择、页内查找 */

/** UA 选择：GlassBottomSheet 预设 + 自定义 */
@Composable
internal fun UaDialog(
    show: Boolean,
    initial: String,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var custom by remember(show) { mutableStateOf(initial) }
    AppBottomSheet(
        show = show,
        title = tr("UA 标识管理"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        // 导航栏留白由 AppBottomSheet 统一处理（沉浸 sheet），这里不再自己加
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            UA_PRESETS.forEach { preset ->
                val presetIcon = when (preset.name) {
                    tr("跟随系统") -> MiuixIcons.Glass.Settings
                    "Android Chrome" -> MiuixIcons.Glass.Phone
                    tr("桌面 Chrome") -> MiuixIcons.Glass.HorizontalSplit
                    "iPhone Safari" -> MiuixIcons.Glass.Phone
                    else -> MiuixIcons.Glass.Info
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onConfirm(preset.value) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val netIcon = rememberFirstIcon(preset.iconUrls)
                    if (netIcon != null) {
                        Image(
                            bitmap = netIcon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    } else {
                        Icon(
                            imageVector = presetIcon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).padding(1.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = preset.name,
                        fontSize = 15.sp,
                        color = if (preset.value == initial) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (preset.value == initial) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = MiuixIcons.Basic.Check,
                            contentDescription = tr("已选中"),
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = custom,
                onValueChange = { custom = it },
                modifier = Modifier.fillMaxWidth(),
                label = tr("UA 字符串"),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = { onConfirm(custom) },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = tr("使用"), style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/** 页内查找：GlassDialog */
@Composable
internal fun FindDialog(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    onQuery: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AppGlassDialog(
        show = show,
        title = tr("查找页内"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    onQuery(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = tr("查找内容"),
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = onPrev, modifier = Modifier.weight(1f)) {
                    Text(text = tr("上一个"), style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text(text = tr("下一个"), style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = tr("关闭"), style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}
