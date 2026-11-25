/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.sync.fxa.FxaUi
import dev.dsh.miuixbrowser.util.hostOf
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 展开后最多直接列出多少个，其余提示去 Firefox 账户同步中心查看。 */
internal const val SYNCED_TABS_PREVIEW_LIMIT = 10

internal class SyncedTabItem(val title: String, val url: String)

/**
 * 其他设备的标签页（仅 http / https、按网址去重、保持设备顺序）。
 * 普通 lite（没有 Firefox 同步）/ 未登录 / 没有其他设备的标签页时返回空列表，调用方据此隐藏整个框。
 * 读取的是 Compose 状态，同步完成后会自动重组。
 */
internal fun syncedTabsFromOtherDevices(): List<SyncedTabItem> {
    if (!EngineSupport.SYNC_BUNDLED) return emptyList()
    if (FxaSync.ui !is FxaUi.SignedIn) return emptyList()
    val seen = HashSet<String>()
    val out = ArrayList<SyncedTabItem>()
    for (device in FxaSync.remoteDevices) {
        for (t in device.tabs) {
            if (!t.url.startsWith("http", ignoreCase = true)) continue
            if (seen.add(t.url)) out += SyncedTabItem(t.title.ifBlank { t.url }, t.url)
        }
    }
    return out
}

/** 重新读取其他设备的标签页（本地同步库，不联网）；没有同步的安装包什么都不做。 */
internal fun syncedTabsReload() {
    if (EngineSupport.SYNC_BUNDLED) FxaSync.reloadRemoteTabs()
}

/**
 * 标签列表顶部的「xx 个来自其他设备的标签页」圆角框。
 * 放在标签列表（LazyColumn）的第一项里，随列表一起滚动，所以不改变 sheet 头部，
 * 头部的渐进模糊带照常处理滚到它下面的内容。点击展开，默认列出前 [SYNCED_TABS_PREVIEW_LIMIT] 个。
 */
@Composable
internal fun SyncedTabsBanner(
    tabs: List<SyncedTabItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (SyncedTabItem) -> Unit,
    onOpenSyncCenter: () -> Unit,
    modifier: Modifier = Modifier,
    /** 标签卡片标题行同款模糊材质的采样源；为 null（关闭模糊 / 无采样）时退回半透明纯色底，和卡片标题行一致 */
    blurBackdrop: LayerBackdrop? = null,
) {
    val shown = tabs.take(SYNCED_TABS_PREVIEW_LIMIT)
    val rest = tabs.size - shown.size
    // 左箭头：收起时转成朝右，展开时朝下
    val rotation by animateFloatAsState(if (expanded) 270f else 180f, label = "syncedTabsArrow")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleClip(16.dp)
            // 与标签卡片的标题行同一套材质（见 TabCard）：玻璃模糊底，没有采样源时退回半透明纯色
            .then(
                if (blurBackdrop != null) {
                    Modifier.glassPanel(
                        backdrop = blurBackdrop,
                        shape = GlassShape(0.dp),
                        style = GlassLook.style(isAppDarkTheme()),
                        material = GlassLook.material(isAppDarkTheme()),
                        stroke = GlassEdgeStrokes.forTheme(isAppDarkTheme()),
                        shading = GlassLook.shading(),
                    )
                } else {
                    Modifier.background(MiuixTheme.colorScheme.surface.copy(alpha = 0.55f))
                },
            )
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("{0} 个来自其他设备的标签页", tabs.size),
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = MiuixIcons.Glass.Back,
                contentDescription = if (expanded) tr("收起") else tr("展开"),
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.Top) {
                shown.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(item) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = item.title,
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = hostOf(item.url).ifBlank { item.url },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rest > 0) {
                    Text(
                        text = tr("还有 {0} 个未显示，请前往 Firefox 账户同步中心查看", rest),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenSyncCenter)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
