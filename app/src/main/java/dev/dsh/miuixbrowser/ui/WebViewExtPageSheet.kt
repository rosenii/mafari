package dev.dsh.miuixbrowser.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.miuixbrowser.ext.ExtensionPageHost
import dev.dsh.miuixbrowser.ext.ExtensionSystem
import dev.dsh.miuixbrowser.i18n.tr
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets

/**
 * 在底部 Sheet 里承载某个 WebView 拓展自己的页面：功能菜单（action popup）或设置页（options）。
 * 拓展页面必须由 [ExtensionPageHost] 创建（带 browser / chrome 桥），所以不能当普通网页在标签里打开。
 *
 * [request] 为 null 时收起并销毁页面；Sheet 被关闭、页面 `window.close()` 或本组件离开组合时都会销毁页面。
 * 要放在 Scaffold 的内容里（miuix 的弹层宿主在那里）。
 */
@Composable
internal fun WebViewExtPageSheet(
    system: ExtensionSystem,
    request: ExtPageRequest?,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf<ExtensionPageHost.ExtensionPage?>(null) }
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ExtPageRequest.Kind.POPUP) }

    fun closePage() {
        val old = page
        page = null
        old?.destroy()
    }

    LaunchedEffect(request) {
        closePage()
        val r = request ?: return@LaunchedEffect
        val ext = system.repo.get(r.extId)
        if (ext == null || !ext.enabled) {
            Toast.makeText(context, tr("拓展未启用"), Toast.LENGTH_SHORT).show()
            onDismiss()
            return@LaunchedEffect
        }
        val path = when (r.kind) {
            ExtPageRequest.Kind.POPUP -> system.actionApi.state(ext).popup?.takeIf { it.isNotBlank() }
            ExtPageRequest.Kind.OPTIONS -> ext.spec.optionsPage?.takeIf { it.isNotBlank() }
        }
        if (path == null) {
            Toast.makeText(
                context,
                if (r.kind == ExtPageRequest.Kind.POPUP) tr("该拓展没有快捷菜单") else tr("该拓展没有设置页"),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
            return@LaunchedEffect
        }
        // 点击就是用户手势：先对当前标签授予 activeTab，popup 一加载就能 tabs.query / 注入脚本
        if (r.kind == ExtPageRequest.Kind.POPUP) runCatching { system.grantActiveTab(r.extId) }
        val pageKind = if (r.kind == ExtPageRequest.Kind.POPUP) ExtensionPageHost.PageKind.POPUP else ExtensionPageHost.PageKind.OPTIONS
        val created = runCatching { system.pages.createPage(ext, pageKind, path) }.getOrNull()
        if (created == null) {
            Toast.makeText(context, tr("无法打开拓展页面"), Toast.LENGTH_SHORT).show()
            onDismiss()
            return@LaunchedEffect
        }
        created.onCloseRequested = { onDismiss() } // window.close()
        title = r.name
        kind = r.kind
        page = created
    }

    DisposableEffect(Unit) { onDispose { page?.destroy() } }

    AppBottomSheet(
        show = request != null && page != null,
        title = title,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        scrollContent = true,
    ) {
        val insets = LocalBottomSheetInsets.current
        val p = page ?: return@AppBottomSheet
        AndroidView(
            factory = { p.webView.apply { onResume(); resumeTimers() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = insets.top)
                .height(if (kind == ExtPageRequest.Kind.OPTIONS) 560.dp else 460.dp)
                .padding(bottom = insets.bottom),
        )
    }
}
