/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.effects.lens
import kotlin.math.max

/**
 * 首页 Mafari 标志：液态玻璃字。
 * - 有壁纸：字形内部显示字后面的那一块壁纸（轻微放大模拟折射、提亮加饱和），颜色随背景变化；
 * - 无壁纸：退回主题色的半透明玻璃渐变；
 * - 再叠：内侧左上高光 / 右下暗边、对角渐变镜面描边、字下柔和投影。
 * 字形遮罩用 saveLayer + BlendMode（SrcIn / DstOut）实现，不依赖 RuntimeShader。
 */
@Composable
internal fun GlassLogoText(text: String, fontSize: Int, wallpaper: ImageBitmap?) {
    val dark = isAppDarkTheme()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(fontSize) {
        TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Black, color = Color.Black)
    }
    val layout = remember(text, style) { measurer.measure(text, style) }
    val pad = with(density) { 14.dp.toPx() }
    val widthDp = with(density) { (layout.size.width + pad * 2).toDp() }
    val heightDp = with(density) { (layout.size.height + pad * 2).toDp() }
    val windowSize = LocalWindowInfo.current.containerSize
    var origin by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = Modifier
            .size(widthDp, heightDp)
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val textTopLeft = Offset(pad, pad)
        val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
        val d = this.density
        fun mask(offset: Offset = Offset.Zero) {
            drawText(layout, color = Color.Black, topLeft = textTopLeft + offset)
        }

        val gTop = textTopLeft.y
        val gBottom = textTopLeft.y + layout.size.height

        // 1. 字外投影：先画模糊投影，再把字形本身挖掉，只留字外的柔和阴影（玻璃本体是透的，不能让阴影透进字里）
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            val fw = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            fw.maskFilter = android.graphics.BlurMaskFilter(9f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
            fw.color = android.graphics.Color.argb(if (dark) 110 else 62, 0, 0, 0)
            fw.textSize = fontSize.sp.toPx()
            fw.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false)
            val baseline = textTopLeft.y - fw.fontMetrics.ascent
            canvas.nativeCanvas.drawText(text, textTopLeft.x, baseline + 4f * d, fw)
            drawText(layout, color = Color.Black, topLeft = textTopLeft, blendMode = BlendMode.DstOut)
            canvas.restore()
        }

        // 2. 玻璃本体：字形内显示背后的壁纸——放大折射、提亮、加饱和，再叠一层上亮下淡的磨砂白
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            if (wallpaper != null && windowSize.width > 0 && windowSize.height > 0) {
                val bw = wallpaper.width.toFloat()
                val bh = wallpaper.height.toFloat()
                val scale = max(windowSize.width / bw, windowSize.height / bh)
                val x0 = (windowSize.width - bw * scale) / 2f
                val y0 = (windowSize.height - bh * scale) / 2f
                val lens = 0.78f
                val w = size.width / scale * lens
                val h = size.height / scale * lens
                val cx = (origin.x + size.width / 2f - x0) / scale
                val cy = (origin.y + size.height / 2f - y0) / scale
                val srcX = (cx - w / 2f).toInt().coerceIn(0, wallpaper.width - 1)
                val srcY = (cy - h / 2f).toInt().coerceIn(0, wallpaper.height - 1)
                val srcW = w.toInt().coerceIn(1, wallpaper.width - srcX)
                val srcH = h.toInt().coerceIn(1, wallpaper.height - srcY)
                val boost = ColorMatrix().apply {
                    setToSaturation(1.7f)
                    this[0, 4] = 46f
                    this[1, 4] = 46f
                    this[2, 4] = 46f
                }
                drawImage(
                    image = wallpaper,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    colorFilter = ColorFilter.colorMatrix(boost),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    blendMode = BlendMode.SrcIn,
                )
            } else {
                // 无壁纸：用主题色做底
                drawRect(
                    color = if (dark) Color(0xFF5A6578) else Color(0xFFA9B8CE),
                    blendMode = BlendMode.SrcIn,
                )
            }
            // 磨砂白：整体提亮，顶部更亮，像光从上面打进玻璃
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.40f else 0.52f),
                        Color.White.copy(alpha = if (dark) 0.18f else 0.26f),
                        Color.White.copy(alpha = if (dark) 0.28f else 0.36f),
                    ),
                    startY = gTop,
                    endY = gBottom,
                ),
                blendMode = BlendMode.SrcAtop,
            )
            canvas.restore()
        }

        // 3. 内侧下缘暗影（玻璃厚度）：字形减去上移的字形，剩下底部窄带，渐变到透明
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            drawText(layout, color = Color.Black, topLeft = textTopLeft + Offset(-1.5f * d, -4f * d), blendMode = BlendMode.DstOut)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.40f else 0.26f)),
                    startY = gTop,
                    endY = gBottom,
                ),
                blendMode = BlendMode.SrcIn,
            )
            canvas.restore()
        }
        // 4. 内侧上缘高光：字形减去下移的字形，剩下顶部亮带，向下淡出
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            drawText(layout, color = Color.Black, topLeft = textTopLeft + Offset(1.5f * d, 3.5f * d), blendMode = BlendMode.DstOut)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.15f)),
                    startY = gTop,
                    endY = gBottom,
                ),
                blendMode = BlendMode.SrcIn,
            )
            canvas.restore()
        }
        // 5. 左右两侧的内侧边缘光：同样用「字形减去平移字形」得到，只在字形外缘，
        //    不描轮廓线，所以字体自带的重叠笔画（a / f / r）内部不会出现线条
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            drawText(layout, color = Color.Black, topLeft = textTopLeft + Offset(1.4f * d, 0f), blendMode = BlendMode.DstOut)
            drawRect(Color.White.copy(alpha = 0.5f), blendMode = BlendMode.SrcIn)
            canvas.restore()
        }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
            mask()
            drawText(layout, color = Color.Black, topLeft = textTopLeft + Offset(-1.4f * d, 0f), blendMode = BlendMode.DstOut)
            drawRect(Color.White.copy(alpha = 0.3f), blendMode = BlendMode.SrcIn)
            canvas.restore()
        }
    }
}
