package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.browser.PasswordCsv
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 选择页里的一行。[appLabel] 非空 = 这条是该 App 自己保存的（origin 是 androidapp://），头像用 App 名首字、副标题显示 App 名。 */
internal class PickRow(val guid: String, val origin: String, val username: String, val appLabel: String?)

/**
 * 原生 App 里「选择账号…」的对话框（[dev.dsh.miuixbrowser.AutofillPickActivity] 承载）。
 * 和浏览器内的登录弹窗同一套材质（[AppGlassDialog] / [AppDialogButton]），跟随「模糊效果」与深浅色设置；
 * 账号行的样式与保存密码弹窗里的 [AccountCard] 一致（半透明底 + 站点头像 + 用户名 / 站点）。
 * 承载页是透明 Activity，没有可采样的页面背景，所以 backdrop 为 null，玻璃材质自动退回实色底。
 */
@Composable
internal fun AutofillPickDialog(
    show: Boolean,
    targetLabel: String,
    rows: List<PickRow>,
    onPick: (PickRow) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        AppGlassDialog(
            show = show,
            title = "选择账号",
            summary = "用于「$targetLabel」",
            backdrop = null,
            onDismissRequest = onDismiss,
            onDismissFinished = onDismissFinished,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.guid }) { row -> PickAccountRow(row, onClick = { onPick(row) }) }
                }
                Text(
                    text = "选定后，之后在此应用里会直接列出这个账号",
                    modifier = Modifier.padding(top = 10.dp, bottom = 14.dp, start = 2.dp),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                AppDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
private fun PickAccountRow(row: PickRow, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(base.copy(alpha = if (pressed) 0.12f else 0.06f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.appLabel == null) {
            SiteAvatar(origin = row.origin, size = 40.dp)
        } else {
            // App 自己的账号：没有网站图标可取（也不该拿包名去取 favicon），用 App 名首字
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(base.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.appLabel.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = row.username.ifEmpty { "（无用户名）" },
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.appLabel ?: PasswordCsv.displayHost(row.origin),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
