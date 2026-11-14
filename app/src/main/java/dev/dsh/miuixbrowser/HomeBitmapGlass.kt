package dev.dsh.miuixbrowser

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.glass.GlassShadows
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.glassShadow
import kotlin.math.max

/**
 * 首页卡片的「取巧玻璃」总开关：壁纸已是磁盘缓存里的预模糊图时，卡片底板直接把这张小图按卡片位置
 * 贴上去再叠一层半透明色，不再为每张卡片录制壁纸层 + 跑 RuntimeShader（noiseDither + 三层混合）。
 * 想退回 miuix 玻璃材质把它改成 false 即可。
 */
internal const val HOME_CARD_BITMAP_GLASS = true

/** 壁纸显示层在根坐标里的位置与尺寸（由壁纸层自己上报；只在绘制阶段读取，不触发重组）。 */
@Stable
internal class WallpaperLayout {
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(Size.Zero)
}

/** 预模糊壁纸图（已含压暗）与它的显示布局。 */
internal class WallpaperSource(val layout: WallpaperLayout, val image: ImageBitmap)

/** 非 null 表示首页取样源是预模糊壁纸，卡片可以走 [wallpaperBitmapGlass]。 */
internal val LocalWallpaperSource = compositionLocalOf<WallpaperSource?> { null }

/**
 * 直接用预模糊壁纸画卡片底：按「壁纸 ContentScale.Crop 铺满」的同一套换算，把整张小图平移 / 缩放到
 * 卡片所在位置，用圆角路径裁剪，再叠半透明色和描边。位置在绘制阶段读取，滚动时随之更新；
 * 整张小图只有约 360px，裁剪后 GPU 只填卡片范围，没有离屏层。
 */
@Composable
internal fun Modifier.wallpaperBitmapGlass(source: WallpaperSource, radius: Dp, dark: Boolean): Modifier {
    var cardPos by remember { mutableStateOf(Offset.Zero) }
    val fill = if (dark) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.24f)
    val line = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.50f)
    return this
        .glassShadow(GlassShape(radius), GlassShapesShadow)
        .onGloballyPositioned { cardPos = it.positionInRoot() }
        .drawBehind {
            val lay = source.layout
            val img = source.image
            if (lay.size.width <= 0f || lay.size.height <= 0f || img.width <= 0 || img.height <= 0) return@drawBehind
            val r = radius.toPx()
            val path = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
            }
            val sc = max(lay.size.width / img.width, lay.size.height / img.height)
            val tx = lay.origin.x - cardPos.x + (lay.size.width - img.width * sc) / 2f
            val ty = lay.origin.y - cardPos.y + (lay.size.height - img.height * sc) / 2f
            clipPath(path) {
                withTransform({
                    translate(tx, ty)
                    scale(sc, sc, Offset.Zero)
                }) {
                    drawImage(img, filterQuality = FilterQuality.Medium)
                }
                drawRect(fill)
            }
            val w = 0.8.dp.toPx()
            drawRoundRect(
                color = line,
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(r - w / 2f, r - w / 2f),
                style = Stroke(w),
            )
        }
}

private val GlassShapesShadow = GlassShadows.Regular
