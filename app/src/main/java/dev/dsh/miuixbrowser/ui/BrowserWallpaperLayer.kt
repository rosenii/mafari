/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.browser.BrowserTab
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.Image
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.ui.graphics.ImageBitmap
import top.yukonga.miuix.kmp.blur.LayerBackdrop as MiuixLayerBackdrop


/** 首页壁纸：铺满全屏、盖住状态栏，同时是首页玻璃卡片 / 底栏 / 弹层的取样层（从 BrowserScreen 拆出）。
 *  [liquidRecord] 是 Kyant 液态玻璃的录制 modifier（调用方按设置决定是否录制）。 */
@Composable
internal fun BrowserWallpaperLayer(
    tab: BrowserTab,
    wallpaperBitmap: ImageBitmap?,
    wallpaperFromCache: Boolean,
    blurredWallpaper: ImageBitmap?,
    wallpaperLayout: WallpaperLayout,
    wallpaperBackdrop: MiuixLayerBackdrop,
    liquidRecord: Modifier,
) {
    if (tab.url.isEmpty() && wallpaperBitmap != null) {
        val wallpaperImage = wallpaperBitmap
        if (wallpaperImage != null) {
            // 取样层就是用户看到的那张壁纸（+压暗）：开启「壁纸模糊」时直接画磁盘缓存里已模糊好的图，
            // 所有材质（柔光 / 液态玻璃）的首页卡片、底栏、弹层都从这一层取色，取色与所见一致，
            // 不再另盖一层显示用的模糊图。缓存还没读出来时先显示未模糊原图，不做实时模糊，避免卡片闪动。
            val shownWallpaper = if (wallpaperFromCache && blurredWallpaper != null) blurredWallpaper else wallpaperImage
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        wallpaperLayout.origin = it.positionInRoot()
                        wallpaperLayout.size = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())
                    }
                    .layerBackdrop(wallpaperBackdrop)
                    // Kyant 取样层只在液态玻璃 + 模糊开启时才有人读（见 StartPage 的 liquidBackdrop 与 homeGlass），否则不录制
                    .then(liquidRecord),
            ) {
                Image(
                    bitmap = shownWallpaper,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize(),
                )
                // 压暗层：缓存图里已烘进，只有显示未模糊原图（模糊关闭 / 缓存未就绪）时才另盖
                if (!wallpaperFromCache) {
                    Box(modifier = Modifier.fillMaxSize().background(WallpaperBlurCache.scrimColor(isAppDarkTheme())))
                }
            }
        }
    }
}
