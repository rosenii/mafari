/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/* 壁纸解码 / 模糊缓存的组合入口（从 BrowserScreen 拆出） */

/** 从 content:// 读壁纸 */
@Composable
internal fun rememberBlurredWallpaper(uri: String, enabled: Boolean, version: Int): ImageBitmap? {
    val context = LocalContext.current
    // 深浅色各一份缓存：切换明暗时换图（浅色更亮、深色更深）
    val dark = isAppDarkTheme()
    // 首帧同步读缓存（进程内命中免费，否则解码磁盘上那张 360px 小图）：命中时第一帧就是模糊图
    val first = remember(uri, enabled, version, dark) {
        if (enabled && uri.isNotBlank()) WallpaperBlurCache.peek(context, uri, dark)?.asImageBitmap() else null
    }
    val image by produceState<ImageBitmap?>(initialValue = first, uri, enabled, version, dark) {
        // 换壁纸 / 开关变化时先清掉上一张的旧图，缓存没命中才去 IO 线程生成
        value = first
        if (first == null && enabled && uri.isNotBlank()) {
            value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                WallpaperBlurCache.getOrCreate(context, uri, dark)?.asImageBitmap()
            }
        }
    }
    return image
}

/** [enabled] 为 false 时不解码（模糊图已就绪、屏幕上用不到整图）。 */
@Composable
internal fun rememberWallpaper(uri: String, version: Int = 0, enabled: Boolean = true): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, uri, version, enabled) {
        value = if (!enabled || uri.isBlank()) {
            null
        } else {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 只解到屏幕所需的尺寸（原先是整张原图：4000×3000 的照片要占 ~48MB 并整张上传 GPU），并带进程内缓存
                val dm = context.resources.displayMetrics
                WallpaperImage.load(context, uri, version, dm.widthPixels, dm.heightPixels)?.asImageBitmap()
            }
        }
    }
    return image
}
