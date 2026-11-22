package dev.dsh.miuixbrowser.ui

import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.browser.ScriptMenuCommand
import dev.dsh.miuixbrowser.browser.rememberRemoteIcon
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.File
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 当前页面正在运行的一个脚本（id 与脚本注册的菜单命令 id 前缀对应） */
data class RunningScript(
    val id: String,
    val name: String,
    val icon: String,
    val iconBase: String = "",
    val iconFallbackHost: String = "",
)

private val OnlineGreen = Color(0xFF34C759)
private val NO_MENU_TEXT: String get() = tr("脚本未注册快捷菜单")

/**
 * 脚本快捷菜单（底部 Sheet）。
 * - 标题 + 绿点副标题「N个脚本运行中 · 运行环境（隔离世界 / 页面内）」，右上角一键启停开关
 * - 下方为当前页面运行的脚本：图标 + 标题（过长滚动）+ 下拉框，下拉里是该脚本注册的快捷命令
 * - 一键关闭后脚本列表整体置灰、不可操作
 */
@Composable
fun ScriptMenuSheet(
    show: Boolean,
    backdrop: Backdrop?,
    scripts: List<RunningScript>,
    bridgeMode: dev.dsh.miuixbrowser.script.ScriptBridgeMode,
    commands: List<ScriptMenuCommand>,
    scriptsEnabled: Boolean,
    onScriptsEnabledChange: (Boolean) -> Unit,
    onRunCommand: (ScriptMenuCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(
        show = show,
        title = null,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        scrollContent = true,
    ) {
        val insets = LocalBottomSheetInsets.current
        // 头部（拖动条）下方留白；列表自己的底部内边距包含导航栏高度，滚动内容铺到导航栏下面再渐隐
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = insets.top),
        ) {
            // ---- 头部：标题 + 副标题 | 一键启停 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("脚本快捷菜单"),
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
                                    if (scriptsEnabled) OnlineGreen
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        val inPage = bridgeMode == dev.dsh.miuixbrowser.script.ScriptBridgeMode.IN_PAGE
                        Text(
                            text = if (scriptsEnabled) {
                                // 运行环境：隔离世界通道（中继 + 私有通道）/ 页面内桥（MafariGM 挂在页面世界）
                                val env = if (inPage) tr("页面内桥") else tr("隔离世界通道")
                                tr("{0}个脚本运行中", scripts.size) + " · " + env
                            } else tr("脚本已停用"),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        // 页面内桥：页面里的 JS 能直接调到原生桥，点红色感叹号看风险提示
                        if (scriptsEnabled && inPage) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF3B30))
                                    .clickable {
                                        android.widget.Toast.makeText(
                                            context,
                                            tr("页面内桥不安全：网页可直接调用脚本原生接口，建议更新 WebView 版本"),
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "!",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
                AppSwitch(checked = scriptsEnabled, onCheckedChange = onScriptsEnabledChange)
            }

            Spacer(Modifier.size(8.dp))

            // ---- 脚本列表 ----
            if (scripts.isEmpty()) {
                Text(
                    text = if (scriptsEnabled) tr("当前页面没有运行中的脚本") else tr("开启后将在刷新页面时运行脚本"),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                        .padding(bottom = insets.bottom),
                )
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .heightIn(max = 380.dp + insets.bottom),
                ) {
                    // 标题区宽度：整行减去 外边距 / 图标 / 箭头，保证 marquee 有确定宽度
                    val nameWidth = (maxWidth - 24.dp - 30.dp - 8.dp - 44.dp).coerceAtLeast(80.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (scriptsEnabled) 1f else 0.4f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = insets.bottom + 16.dp),
                    ) {
                        scripts.forEach { script ->
                            val own = commands.filter { it.id.startsWith(script.id + ":") }
                            ScriptRow(
                                script = script,
                                commands = own,
                                enabled = scriptsEnabled,
                                nameWidth = nameWidth,
                                backdrop = backdrop,
                                onRunCommand = onRunCommand,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptRow(
    script: RunningScript,
    commands: List<ScriptMenuCommand>,
    enabled: Boolean,
    nameWidth: androidx.compose.ui.unit.Dp,
    backdrop: Backdrop?,
    onRunCommand: (ScriptMenuCommand) -> Unit,
) {
    val items = if (commands.isEmpty()) listOf(NO_MENU_TEXT) else commands.map { it.title }
    // 标题只滚动一遍；触摸该条目时换 key 让 marquee 重新滚动一遍
    var marqueeRestart by remember { mutableIntStateOf(0) }
    AppDropdownPreference(
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                marqueeRestart++
            }
        },
        marqueeItems = true,
        backdrop = backdrop,
        title = "",
        items = items,
        selectedIndex = -1,
        showValue = false,
        enabled = enabled,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        startAction = {
            Row(
                modifier = Modifier.width(nameWidth + 30.dp + 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScriptSheetIcon(script.icon, script.iconBase, script.iconFallbackHost)
                key(marqueeRestart) {
                    Text(
                        text = script.name,
                        fontSize = 15.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .width(nameWidth)
                            .basicMarquee(iterations = 1),
                    )
                }
            }
        },
        onSelectedIndexChange = { index ->
            commands.getOrNull(index)?.let(onRunCommand)
        },
    )
}

@Composable
private fun ScriptSheetIcon(url: String, baseUrl: String, fallbackHost: String) {
    val icon = rememberRemoteIcon(url, baseUrl, fallbackHost)
    Box(
        modifier = Modifier
            .size(30.dp)
            .squircleClip(9.dp)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = MiuixIcons.Glass.File,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
