package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.RequestLog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Spacer
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 菜单 → 请求：本页（当前标签）发出的请求，按请求域名汇总；一键屏蔽 / 放行，屏蔽后对之后的请求立即生效（已加载的内容刷新页面后才会变化）。
 * 只记内存里的域名和次数，不记完整 URL；换页 / 关标签清空，无痕标签不记录。
 */
@Composable
fun RequestLogScreen(log: RequestLog, onBack: () -> Unit, blurEnabled: Boolean = true, isGecko: Boolean = false) {
    val context = LocalContext.current

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    // 请求线程在后台写，界面定时取快照（面板开着才轮询）
    var rows by remember { mutableStateOf(log.snapshot()) }
    LaunchedEffect(log) {
        while (true) {
            rows = log.snapshot()
            delay(700)
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = "本页请求",
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = {
                        LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .edgeHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            AppSwitchPreference(
                                title = "记录请求",
                                summary = "开启后统计页面请求的域名与次数；关闭时不统计，对页面加载没有额外开销（手动屏蔽的域名仍然生效）。开启后刷新页面才有数据",
                                checked = AdBlock.requestLogEnabled,
                                onCheckedChange = {
                                    AdBlock.updateRequestLog(it)
                                    if (!it) log.clear()
                                },
                            )
                        }
                    }
                    item {
                        Text(
                            text = if (!AdBlock.requestLogEnabled) "统计已关闭，下方是你手动屏蔽 / 放行的域名"
                            else if (!isGecko && !AdBlock.enabled) "去广告总开关已关闭：只统计，不会拦截任何请求"
                            else if (isGecko) "共 ${rows.size} 个域名，${rows.sumOf { it.total }} 次请求。屏蔽立即对之后的请求生效；标「Gecko 防护」的是浏览器自带保护拦的，在「Gecko 高级保护」里调整"
                            else "共 ${rows.size} 个域名，${rows.sumOf { it.total }} 次请求。屏蔽 / 放行对之后的请求立即生效，已加载的内容刷新页面后才会变化",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                    if (!AdBlock.requestLogEnabled) {
                        // 关着时不展示数据（也没有数据）：下面只剩「我屏蔽 / 放行的域名」
                    } else if (rows.isEmpty()) {
                        item {
                            Text(
                                text = "暂无请求。刷新页面或打开一个网页后再来看（无痕标签不记录）",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                            )
                        }
                    } else {
                        item { SmallTitle(text = "本页域名") }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                Column {
                                    rows.forEach { row ->
                                        RequestRow(row, isGecko) { msg -> toast(msg) }
                                    }
                                }
                            }
                        }
                    }

                    val blocked = AdBlock.userBlocked.toList()
                    val allowed = AdBlock.userAllowed.toList()
                    if (blocked.isNotEmpty()) {
                        item { SmallTitle(text = "我屏蔽的域名（含子域名）") }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                Column {
                                    blocked.forEach { h ->
                                        ManagedHostRow(h, "移除") { AdBlock.removeUserBlock(h); toast("已移除屏蔽") }
                                    }
                                }
                            }
                        }
                    }
                    if (allowed.isNotEmpty()) {
                        item { SmallTitle(text = "我放行的域名（含子域名）") }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                Column {
                                    allowed.forEach { h ->
                                        ManagedHostRow(h, "移除") { AdBlock.removeUserAllow(h); toast("已移除放行") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestRow(row: RequestLog.Row, isGecko: Boolean, toast: (String) -> Unit) {
    // 状态以当前设置为准（手动屏蔽 / 放行可能命中它的父域名），再看本页是否被规则拦过
    val userBlock = AdBlock.userBlockEntry(row.host)
    val userAllow = AdBlock.userAllowEntry(row.host)
    val (status, actionText) = when {
        userBlock != null -> "已屏蔽（手动" + (if (userBlock != row.host) "：$userBlock" else "") + "）" to "取消屏蔽"
        userAllow != null -> "已放行（手动" + (if (userAllow != row.host) "：$userAllow" else "") + "）" to "取消放行"
        // Gecko 自带防护拦的：只展示，本应用无法放行
        row.blocked > 0 && row.reason == AdBlock.R_BROWSER -> "已拦截（Gecko 防护）" to ""
        // 手动屏蔽已被移除后，这一行留着的旧拦截记录不再算「已拦截」
        row.blocked > 0 && row.reason != AdBlock.R_USER -> "已拦截（" + (if (row.reason == AdBlock.R_TRACKER) "跟踪域名" else "规则") + "）" to "放行"
        else -> "已放行" to "屏蔽"
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
                text = "${row.total} 次" + (if (row.blocked > 0) "，拦截 ${row.blocked} 次" else "") + " · $status",
                fontSize = 12.sp,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionText.isNotEmpty()) Spacer(Modifier.width(8.dp))
        if (actionText.isNotEmpty()) AppTextButton(
            text = actionText,
            onClick = {
                when {
                    userBlock != null -> { AdBlock.removeUserBlock(userBlock); toast("已取消屏蔽，刷新页面生效") }
                    userAllow != null -> { AdBlock.removeUserAllow(userAllow); toast("已取消放行") }
                    row.blocked > 0 -> { AdBlock.allowRequestHost(row.host); toast("已放行 ${row.host}，刷新页面生效") }
                    else -> { AdBlock.blockHost(row.host); toast("已屏蔽 ${row.host}，刷新页面生效") }
                }
            },
            primary = actionText == "屏蔽",
            inPage = true,
            minHeight = 30.dp,
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
        AppTextButton(text = actionText, onClick = onClick, inPage = true, minHeight = 30.dp)
    }
}
