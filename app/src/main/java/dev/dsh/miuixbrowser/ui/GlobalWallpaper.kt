package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「壁纸 → 全局应用」：壁纸同时作为二级页面（设置 / 下载 / 书签历史等）的背景。
 * [scrim] 为 true 表示 [bitmap] 是未压暗的原图（模糊缓存里的图已烘进压暗），绘制时要另盖一层。
 */
@Immutable
internal class GlobalWallpaper(val bitmap: ImageBitmap, val scrim: Boolean)

/** 非 null 表示二级页面应该用壁纸当背景；开关关闭或没设置壁纸时为 null。 */
internal val LocalGlobalWallpaper = staticCompositionLocalOf<GlobalWallpaper?> { null }

/**
 * 二级页面的整页背景：有全局壁纸时按 Crop 铺满画壁纸，否则是主题 surface 色。
 * 放在 layerBackdrop 之前 / 之内的页面内容层上，玻璃顶栏与控件的取样源就会是壁纸。
 */
@Composable
internal fun Modifier.pageBackground(): Modifier {
    val wallpaper = LocalGlobalWallpaper.current
    val surface = MiuixTheme.colorScheme.surface
    if (wallpaper == null) return background(surface)
    return drawBehind {
        drawRect(surface)
        val bitmap = wallpaper.bitmap
        if (bitmap.width > 0 && bitmap.height > 0 && size.width > 0f && size.height > 0f) {
            val scale = max(size.width / bitmap.width, size.height / bitmap.height)
            val dw = (bitmap.width * scale).roundToInt()
            val dh = (bitmap.height * scale).roundToInt()
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(((size.width - dw) / 2f).roundToInt(), ((size.height - dh) / 2f).roundToInt()),
                dstSize = IntSize(dw, dh),
                filterQuality = FilterQuality.Medium,
            )
            if (wallpaper.scrim) drawRect(Color.Black.copy(alpha = WallpaperBlurCache.SCRIM_ALPHA))
        }
    }
}
