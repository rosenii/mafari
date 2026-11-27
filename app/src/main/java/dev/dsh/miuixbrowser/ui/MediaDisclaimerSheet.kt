/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「媒体嗅探与播放」免责声明弹窗（底部 sheet）。从此版本起，第一次打开该功能前必须确认：
 * 嗅探列表、设置页里打开总开关都走这里；确认结果记在 AppSettings.mediaDisclaimerAccepted。
 * [onAccept] 点「我已知晓」；[onDismiss] 点「取消」或下滑 / 点遮罩关闭（视为未确认）。
 */
@Composable
fun MediaDisclaimerSheet(
    show: Boolean,
    backdrop: Backdrop?,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(
        show = show,
        title = tr("媒体嗅探与播放声明"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Text(
                text = tr("我已知晓此软件的嗅探功能仅用于学习和研究，知晓本软件不提供嗅探国内主流视频平台的功能。使用特殊方式通过此软件获取其他平台网站影视媒体资源产生的法律责任与此软件无关，由我本人自行承担！"),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(20.dp))
            AppDialogButton(
                onClick = onAccept,
                role = GlassDialogButtonRole.Primary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = tr("我已知晓"), style = MiuixTheme.textStyles.button)
            }
            Spacer(Modifier.height(10.dp))
            AppDialogButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
            }
        }
    }
}
