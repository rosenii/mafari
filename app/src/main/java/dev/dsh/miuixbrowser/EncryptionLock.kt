package dev.dsh.miuixbrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop as LiquidBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 使用加密套件保护数据的页面，右上角的「加密安全锁」按钮（有其他按钮时放在它们最左边）。
 * 点击弹出 [EncryptionInfoDialog]；弹窗放在页面根部，状态由页面持有。
 */
@Composable
fun EncryptionLockButton(onClick: () -> Unit, liquid: LiquidBackdrop?, topScrolled: Boolean) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = liquid,
        surfaceVisible = topScrolled,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Icon(
            imageVector = AppIcons.Lock,
            contentDescription = "加密保护",
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
    }
}

/** 精炼的加密说明：当前套件 / 密钥派生 / 数据加密 / 密钥保护，加上本页数据的具体保护方式 [scope]。 */
@Composable
fun EncryptionInfoDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop?,
    liquid: LiquidBackdrop?,
    scope: String,
) {
    val suite = CipherSuites.default
    AppGlassDialog(
        show = show,
        title = "加密保护中",
        summary = "本页数据已使用加密套件保护",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            InfoLine("加密套件", suite.shortName + "（后量子混合）")
            InfoLine("密钥派生", suite.kdfDescription.substringBefore(" →"))
            InfoLine("数据加密", "AES-256-GCM")
            InfoLine("密钥保护", "Android Keystore")
            InfoLine("本页", scope)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiquidGlassPillButton(
                    text = "知道了",
                    onClick = onDismiss,
                    backdrop = liquid,
                    modifier = Modifier.weight(1f),
                    role = LiquidRole.Primary,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
