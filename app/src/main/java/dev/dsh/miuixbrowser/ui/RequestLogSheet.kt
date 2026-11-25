/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.RequestLog
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 菜单 → 网络请求（底部 Sheet）：本页（当前标签）发出的请求，按请求域名汇总；一键屏蔽 / 放行，
 * 屏蔽后对之后的请求立即生效（已加载的内容刷新页面后才会变化）。
 * 只记内存里的域名和次数，不记完整 URL；换页 / 关标签清空，无痕标签不记录。
 */
@Composable
fun RequestLogSheet(
    show: Boolean,
    backdrop: Backdrop?,
    log: RequestLog,
    isGecko: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // 请求线程在后台写，界面定时取快照（Sheet 开着才轮询）
    var rows by remember { mutableStateOf(log.snapshot()) }
    LaunchedEffect(show, log) {
        if (!show) return@LaunchedEffect
        while (true) {
            rows = log.snapshot()
            delay(700)
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    AppBottomSheet(
        show = show,
        title = tr("网络请求"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        scrollContent = true,
    ) {
        val insets = LocalBottomSheetInsets.current
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp + insets.top + insets.bottom),
            contentPadding = PaddingValues(top = insets.top, bottom = insets.bottom + 16.dp),
        ) {
            item {
                AppSwitchPreference(
                    title = tr("记录和管理网页请求"),
                    summary = tr("统计请求域名，可屏蔽 / 放行；开启后刷新生效"),
                    checked = AdBlock.requestLogEnabled,
                    onCheckedChange = {
                        AdBlock.updateRequestLog(it)
                        if (!it) log.clear()
                    },
                )
            }
            val hint = when {
                !AdBlock.requestLogEnabled -> tr("统计已关闭")
                !isGecko && !AdBlock.enabled -> tr("去广告已关闭，仅统计不拦截")
                rows.isNotEmpty() -> tr("{0} 个域名 · {1} 次请求", rows.size, rows.sumOf { it.total })
                else -> null
            }
            if (hint != null) {
                item {
                    Text(
                        text = hint,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
            if (!AdBlock.requestLogEnabled) {
                // 关着时不展示数据（也没有数据）：下面只剩「我屏蔽 / 放行的域名」
            } else if (rows.isEmpty()) {
                item {
                    Text(
                        text = tr("暂无请求，刷新页面后再看"),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            } else {
                item { SmallTitle(text = tr("本页域名")) }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column {
                            rows.forEachIndexed { i, row ->
                                if (i > 0) RowDivider()
                                RequestRow(row) { msg -> toast(msg) }
                            }
                        }
                    }
                }
            }

            val blocked = AdBlock.userBlocked.toList()
            val allowed = AdBlock.userAllowed.toList()
            if (blocked.isNotEmpty()) {
                item { SmallTitle(text = tr("我屏蔽的域名（含子域名）")) }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column {
                            blocked.forEachIndexed { i, h ->
                                if (i > 0) RowDivider()
                                ManagedHostRow(h, tr("移除")) { AdBlock.removeUserBlock(h); toast(tr("已移除屏蔽")) }
                            }
                        }
                    }
                }
            }
            if (allowed.isNotEmpty()) {
                item { SmallTitle(text = tr("我放行的域名（含子域名）")) }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column {
                            allowed.forEachIndexed { i, h ->
                                if (i > 0) RowDivider()
                                ManagedHostRow(h, tr("移除")) { AdBlock.removeUserAllow(h); toast(tr("已移除放行")) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestRow(row: RequestLog.Row, toast: (String) -> Unit) {
    // 状态以当前设置为准（手动屏蔽 / 放行可能命中它的父域名），再看本页是否被规则拦过
    val userBlock = AdBlock.userBlockEntry(row.host)
    val userAllow = AdBlock.userAllowEntry(row.host)
    val (status, actionText) = when {
        userBlock != null -> (if (userBlock != row.host) tr("已屏蔽（手动：{0}）", userBlock) else tr("已屏蔽（手动）")) to tr("取消屏蔽")
        userAllow != null -> (if (userAllow != row.host) tr("已放行（手动：{0}）", userAllow) else tr("已放行（手动）")) to tr("取消放行")
        // Gecko 自带防护拦的：只展示，本应用无法放行
        row.blocked > 0 && row.reason == AdBlock.R_BROWSER -> tr("已拦截（Gecko 防护）") to ""
        // 手动屏蔽已被移除后，这一行留着的旧拦截记录不再算「已拦截」
        row.blocked > 0 && row.reason != AdBlock.R_USER -> tr("已拦截（{0}）", if (row.reason == AdBlock.R_TRACKER) tr("跟踪域名") else tr("规则")) to tr("放行")
        else -> tr("已放行") to tr("屏蔽")
    }
    val statusColor = if (userBlock != null || (userAllow == null && row.blocked > 0)) {
        MiuixTheme.colorScheme.error
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.host,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tr("{0} 次", row.total) + (if (row.blocked > 0) tr("，拦截 {0} 次", row.blocked) else "") + " · $status",
                fontSize = 12.sp,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionText.isNotEmpty()) Spacer(Modifier.width(8.dp))
        if (actionText.isNotEmpty()) SheetRowButton(
            text = actionText,
            onClick = {
                when {
                    userBlock != null -> { AdBlock.removeUserBlock(userBlock); toast(tr("已取消屏蔽，刷新页面生效")) }
                    userAllow != null -> { AdBlock.removeUserAllow(userAllow); toast(tr("已取消放行")) }
                    row.blocked > 0 -> { AdBlock.allowRequestHost(row.host); toast(tr("已放行 {0}，刷新页面生效", row.host)) }
                    else -> { AdBlock.blockHost(row.host); toast(tr("已屏蔽 {0}，刷新页面生效", row.host)) }
                }
            },
            primary = actionText == tr("屏蔽"),
        )
    }
}

@Composable
private fun ManagedHostRow(host: String, actionText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = host,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SheetRowButton(text = actionText, onClick = onClick)
    }
}

/**
 * 行末的小操作按钮：走材质抽象层的 [AppDialogButton]，miuix-glass / 液态玻璃 / 关闭模糊三种材质各用各的实现。
 * Sheet 在页面采样层之外，所以不传 inPage（液态材质下是真玻璃）；固定宽度是因为液态按钮自己没有内在宽度。
 */
@Composable
private fun SheetRowButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    AppDialogButton(
        onClick = onClick,
        modifier = Modifier.width(104.dp),
        role = if (primary) GlassDialogButtonRole.Primary else GlassDialogButtonRole.Normal,
        cornerRadius = 12.dp,
        minHeight = 34.dp,
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button, fontSize = 13.sp, maxLines = 1)
    }
}

/** 列表行之间的细分隔线（Sheet 里没有卡片底色，靠它分行） */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}
