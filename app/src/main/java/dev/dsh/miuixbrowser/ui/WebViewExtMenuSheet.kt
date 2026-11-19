package dev.dsh.miuixbrowser.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.miuixbrowser.ext.ExtensionPageHost
import dev.dsh.miuixbrowser.ext.ExtensionSystem
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val WebExtRunningGreen = Color(0xFF34C759)

/**
 * WebView WebExtension 快捷菜单：交互与 GeckoExtMenuSheet 对齐。
 * 展示当前内核可运行的已安装拓展；点击带 action.default_popup 的拓展时，
 * 在同一底部 Sheet 中承载拓展自己的 popup WebView。
 */
@Composable
internal fun WebViewExtMenuSheet(
    show: Boolean,
    backdrop: Backdrop?,
    system: ExtensionSystem,
    incognito: Boolean,
    onChanged: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backend = remember(system) { WebViewExtBackend(system, onChanged) }
    var refreshKey by remember { mutableStateOf(0) }
    var popupPage by remember { mutableStateOf<ExtensionPageHost.ExtensionPage?>(null) }
    var popupTitle by remember { mutableStateOf<String?>(null) }
    var bulkBusy by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) } // 防止连点开出多个 popup WebView

    LaunchedEffect(show, refreshKey) {
        if (show) {
            backend.bind(context)
            runCatching { backend.refresh(context) }
        }
    }

    val all = backend.items
    val running = if (incognito) emptyList() else all.filter { it.enabled }
    val allOn = running.isNotEmpty()

    fun closePopup() {
        val old = popupPage
        popupPage = null
        popupTitle = null
        old?.destroy()
    }

    LaunchedEffect(show) {
        if (!show) closePopup()
    }

    AppBottomSheet(
        show = show || popupPage != null,
        title = popupTitle,
        backdrop = backdrop,
        onDismissRequest = {
            if (popupPage != null) closePopup() else onDismiss()
        },
        scrollContent = true,
    ) {
        val insets = LocalBottomSheetInsets.current
        val page = popupPage
        if (page != null) {
            AndroidView(
                factory = { page.webView.apply { onResume(); resumeTimers() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = insets.top)
                    .height(460.dp)
                    .padding(bottom = insets.bottom),
            )
            return@AppBottomSheet
        }

        Column(Modifier.fillMaxWidth().padding(top = insets.top)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tr("拓展快捷菜单"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape).background(
                                if (running.isNotEmpty()) WebExtRunningGreen
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (running.isNotEmpty()) tr("{0}个拓展运行中", running.size)
                            else tr("没有运行中的拓展"),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (all.isNotEmpty() && !incognito) {
                    AppSwitch(
                        checked = allOn,
                        enabled = !bulkBusy,
                        onCheckedChange = { enabled ->
                            bulkBusy = true
                            scope.launch {
                                try {
                                    all.filter { it.enabled != enabled }.forEach { item ->
                                        backend.setEnabled(context, item.id, enabled)
                                    }
                                } finally {
                                    bulkBusy = false
                                    refreshKey++
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.size(8.dp))

            when {
                incognito -> Text(
                    text = tr("无痕浏览暂不运行 WebView 拓展。"),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)
                        .padding(bottom = insets.bottom),
                )
                all.isEmpty() -> Text(
                    text = if (backend.loading) tr("正在读取…")
                    else tr("还没有安装拓展。长按菜单里的「拓展」图标可进入拓展页安装。"),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)
                        .padding(bottom = insets.bottom),
                )
                else -> Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        .heightIn(max = 380.dp + insets.bottom)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = insets.bottom + 16.dp),
                ) {
                    all.forEach { item ->
                        val ext = system.repo.get(item.id)
                        val popup = ext?.spec?.action?.popup?.takeIf { it.isNotBlank() }
                        Row(
                            modifier = Modifier.fillMaxWidth().squircleClip(14.dp)
                                .alpha(if (item.enabled) 1f else 0.45f)
                                .clickable(enabled = item.enabled && !incognito && !opening) {
                                    if (popup == null || ext == null) {
                                        Toast.makeText(context, tr("该拓展没有快捷菜单"), Toast.LENGTH_SHORT).show()
                                    } else {
                                        closePopup()
                                        opening = true
                                        scope.launch {
                                            try {
                                                // 点击就是用户手势：先对当前标签授予 activeTab，popup 一加载就能 tabs.query / 注入脚本
                                                runCatching { system.grantActiveTab(ext.spec.id) }
                                                val created = runCatching {
                                                    system.pages.createPage(ext, ExtensionPageHost.PageKind.POPUP, popup)
                                                }.getOrNull()
                                                if (created == null) {
                                                    Toast.makeText(context, tr("无法打开拓展快捷菜单"), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    created.onCloseRequested = { closePopup() } // window.close()
                                                    popupTitle = item.name
                                                    popupPage = created
                                                }
                                            } finally {
                                                opening = false
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = backend.icon(item.id)
                            if (icon != null) {
                                Image(icon, contentDescription = null, modifier = Modifier.size(30.dp).squircleClip(8.dp))
                            } else {
                                Box(
                                    Modifier.size(30.dp).squircleClip(8.dp)
                                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        !item.enabled -> tr("已停用")
                                        popup == null -> tr("没有快捷菜单")
                                        else -> tr("点击打开功能菜单")
                                    },
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            AppSwitch(
                                checked = item.enabled,
                                enabled = !bulkBusy,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        backend.setEnabled(context, item.id, enabled)
                                        refreshKey++
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
