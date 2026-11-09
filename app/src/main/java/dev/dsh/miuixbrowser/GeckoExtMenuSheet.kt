package dev.dsh.miuixbrowser

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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
import org.mozilla.geckoview.GeckoView
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val RunningGreen = Color(0xFF34C759)

/**
 * 拓展快捷菜单（底部 Sheet，对应 WebView 下的「脚本快捷菜单」）。
 * 列出正在运行（已启用）的拓展；点某一项会点击它的工具栏按钮，拓展的弹出页（功能菜单）
 * 随即在第二层 sheet 里显示。
 */
@Composable
fun GeckoExtMenuSheet(
    show: Boolean,
    backdrop: Backdrop?,
    activeSession: org.mozilla.geckoview.GeckoSession? = null,
    onOpenUrl: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = GeckoExtensionStore

    // 弹出页里的网页链接 / 拓展要开的页面：在浏览器新标签里打开，并收起菜单
    androidx.compose.runtime.SideEffect {
        store.openUrl = { url ->
            onOpenUrl(url)
            onDismiss()
        }
    }
    LaunchedEffect(show, activeSession) {
        store.activeSession = activeSession
        store.markActiveTab()
        if (show) runCatching { store.refresh(context) }
    }

    val scope = rememberCoroutineScope()
    val all = store.items.toList()
    val running = all.filter { it.enabled }
    // 总开关：全部已启用才算「开」；切换时对所有拓展一起启用 / 停用
    val allOn = all.isNotEmpty() && running.size == all.size
    var bulkBusy by remember { mutableStateOf(false) }
    val popup = store.popup.value

    // 点了拓展按钮后，若一小段时间内 Gecko 没要求弹出页（按钮只是触发拓展的 onClicked），就收起菜单
    var clickTick by remember { mutableIntStateOf(0) }
    var lastClickedId by remember { mutableStateOf("") }
    LaunchedEffect(clickTick) {
        if (clickTick == 0) return@LaunchedEffect
        delay(700)
        if (store.popup.value == null) {
            // Gecko 没回调弹出页：自己加载 manifest 里的 default_popup；没有弹出页才收起菜单
            val ok = runCatching { store.openPopupFallback(context, lastClickedId) }.getOrDefault(false)
            if (!ok && store.popup.value == null) onDismiss()
        }
    }
    // 弹出页关闭后（或菜单本身被关）清掉残留的弹出页
    LaunchedEffect(show) {
        if (!show) store.closePopup()
    }

    // 只用一层 sheet：列表与弹出页在同一层里切换（两层 sheet 同时切换时，后一层可能被宿主吞掉）
    AppBottomSheet(
        show = show || popup != null,
        title = popup?.name,
        backdrop = backdrop,
        onDismissRequest = {
            if (store.popup.value != null) store.closePopup()
            onDismiss()
        },
    ) {
        if (popup != null) {
            AndroidView(
                factory = { ctx ->
                    GeckoView(ctx).apply {
                        // 使用 GeckoView 默认 SurfaceView 后端；避免 setViewBackend() 重建内部 View。
                        setSession(popup.session)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .navigationBarsPadding(),
            )
            return@AppBottomSheet
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "拓展快捷菜单",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (running.isNotEmpty()) RunningGreen
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (running.isNotEmpty()) "${running.size}个拓展运行中" else "没有运行中的拓展",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (all.isNotEmpty()) {
                    AppSwitch(
                        checked = allOn,
                        enabled = !bulkBusy,
                        onCheckedChange = { on ->
                            bulkBusy = true
                            scope.launch {
                                try {
                                    all.filter { it.enabled != on }.forEach { store.setEnabled(context, it, on) }
                                } finally {
                                    bulkBusy = false
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.size(8.dp))

            if (all.isEmpty()) {
                Text(
                    text = if (store.loading.value) "正在读取…" else "还没有安装拓展。长按菜单里的「拓展」图标可进入拓展页安装。",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    all.forEach { item ->
                        val action = store.actions[item.id]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .alpha(if (item.enabled) 1f else 0.45f)
                                .clickable(enabled = item.enabled) {
                                    if (store.clickAction(item.id)) {
                                        lastClickedId = item.id
                                        clickTick++
                                    } else {
                                        Toast.makeText(context, "该拓展没有快捷菜单", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val bmp = store.icons[item.id]
                            val shape = RoundedCornerShape(8.dp)
                            if (bmp != null) {
                                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(30.dp).clip(shape))
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(shape)
                                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        !item.enabled -> "已停用"
                                        action == null -> "没有快捷菜单"
                                        action.badge.isNotBlank() -> "角标 ${action.badge}"
                                        action.title.isNotBlank() && action.title != item.name -> action.title
                                        else -> "点击打开功能菜单"
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
                                onCheckedChange = { on ->
                                    scope.launch { store.setEnabled(context, item, on) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
