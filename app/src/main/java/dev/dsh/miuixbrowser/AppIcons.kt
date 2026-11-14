package dev.dsh.miuixbrowser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 自绘 SVG 图标：24×24 视口，描边风格，与 miuix 图标同尺寸，颜色由 Icon 的 tint 决定。 */
object AppIcons {

    private fun stroked(name: String, vararg paths: String, width: Float = 1.9f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** iOS 风格标签页：前方圆角方块 + 后方露出一个 L 形轮廓。 */
    val Tabs: ImageVector by lazy {
        stroked(
            "Tabs",
            "M11.8,8.8H17.8A3,3 0 0 1 20.8,11.8V17.8A3,3 0 0 1 17.8,20.8H11.8A3,3 0 0 1 8.8,17.8V11.8A3,3 0 0 1 11.8,8.8Z",
            "M6.2,15.2A3,3 0 0 1 3.2,12.2V6.2A3,3 0 0 1 6.2,3.2H12.2A3,3 0 0 1 15.2,6.2V6.9",
        )
    }

    /** 刷新：不闭合圆圈（缺口在右上）+ 顺时针箭头，类似 iOS 的 arrow.clockwise。 */
    val Refresh: ImageVector by lazy {
        stroked(
            "Refresh",
            "M17.3,6.7A7.5,7.5 0 1 1 12,4.5",
            "M9,1.9L12,4.5L9,7.1",
            width = 2.0f,
        )
    }

    /** 加密安全锁：圆角锁身 + 锁梁 + 锁孔。图形撑满 24 视口（约 3~21），视觉大小与其他顶栏图标一致。 */
    val Lock: ImageVector by lazy {
        stroked(
            "Lock",
            "M6.8,10V7.8A5.2,5.2 0 0 1 17.2,7.8V10",
            "M5.5,10H18.5A2.5,2.5 0 0 1 21,12.5V18.5A2.5,2.5 0 0 1 18.5,21H5.5A2.5,2.5 0 0 1 3,18.5V12.5A2.5,2.5 0 0 1 5.5,10Z",
            "M12,14.2V17.2",
        )
    }

    /** 书签：上端平、下端 V 形缺口的书签轮廓。 */
    val Bookmark: ImageVector by lazy {
        stroked(
            "Bookmark",
            "M6.5,4.5A1.5,1.5 0 0 1 8,3H16A1.5,1.5 0 0 1 17.5,4.5V20.2L12,16.4L6.5,20.2Z",
        )
    }

    /** 文件夹：带标签耳朵的圆角轮廓（书签分组用）。 */
    val Folder: ImageVector by lazy {
        stroked(
            "Folder",
            "M3.5,7.5A2,2 0 0 1 5.5,5.5H9.3L11.3,8H18.5A2,2 0 0 1 20.5,10V17.5A2,2 0 0 1 18.5,19.5H5.5A2,2 0 0 1 3.5,17.5Z",
        )
    }

    /** 脚本：尖括号 + 斜杠。 */
    val Script: ImageVector by lazy {
        stroked(
            "Script",
            "M8.5,7.5L3.5,12L8.5,16.5",
            "M15.5,7.5L20.5,12L15.5,16.5",
            "M13.6,5L10.4,19",
        )
    }

    /** 添加到桌面：圆角方框 + 加号（[+]）。 */
    val AddToDesktop: ImageVector by lazy {
        stroked(
            "AddToDesktop",
            "M4,6.5A2.5,2.5 0 0 1 6.5,4H17.5A2.5,2.5 0 0 1 20,6.5V17.5A2.5,2.5 0 0 1 17.5,20H6.5A2.5,2.5 0 0 1 4,17.5Z",
            "M12,8V16",
            "M8,12H16",
        )
    }
}
