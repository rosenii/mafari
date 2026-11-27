/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

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

    /** 扫一扫：四个角括号 + 中间一条扫描线。 */
    val Scan: ImageVector by lazy {
        stroked(
            "Scan",
            "M4,8V6.5A2.5,2.5 0 0 1 6.5,4H8",
            "M16,4H17.5A2.5,2.5 0 0 1 20,6.5V8",
            "M20,16V17.5A2.5,2.5 0 0 1 17.5,20H16",
            "M8,20H6.5A2.5,2.5 0 0 1 4,17.5V16",
            "M4,12H20",
            width = 2.0f,
        )
    }

    /** 书架：翻开的书（左右两页 + 中缝）。 */
    val Bookshelf: ImageVector by lazy {
        stroked(
            "Bookshelf",
            "M12,6.5C10.5,5.2 8,4.5 4,4.5V18.5C8,18.5 10.5,19.2 12,20.5C13.5,19.2 16,18.5 20,18.5V4.5C16,4.5 13.5,5.2 12,6.5Z",
            "M12,6.5V20.5",
            width = 2.0f,
        )
    }

    /** 阅读模式：四行文字（末行较短），类似 Safari 的阅读器图标。 */
    val Reader: ImageVector by lazy {
        stroked(
            "Reader",
            "M4.5,6H19.5",
            "M4.5,10.5H19.5",
            "M4.5,15H19.5",
            "M4.5,19.5H12.5",
            width = 2.0f,
        )
    }

    /** 复制：前方圆角方块 + 后方露出的 L 形轮廓。 */
    val Copy: ImageVector by lazy {
        stroked(
            "Copy",
            "M10.5,9H18.5A2.5,2.5 0 0 1 21,11.5V18.5A2.5,2.5 0 0 1 18.5,21H10.5A2.5,2.5 0 0 1 8,18.5V11.5A2.5,2.5 0 0 1 10.5,9Z",
            "M15,9V5.5A2.5,2.5 0 0 0 12.5,3H5.5A2.5,2.5 0 0 0 3,5.5V12.5A2.5,2.5 0 0 0 5.5,15H8",
        )
    }

    /** 睁眼（密码可见）。 */
    val Eye: ImageVector by lazy {
        stroked(
            "Eye",
            "M2,12C4.4,7.6 8,5.5 12,5.5C16,5.5 19.6,7.6 22,12C19.6,16.4 16,18.5 12,18.5C8,18.5 4.4,16.4 2,12Z",
            "M15,12A3,3 0 1 1 9,12A3,3 0 1 1 15,12Z",
        )
    }

    /** 闭眼（密码已隐藏）：眼睛加一道斜线。 */
    val EyeOff: ImageVector by lazy {
        stroked(
            "EyeOff",
            "M2,12C4.4,7.6 8,5.5 12,5.5C16,5.5 19.6,7.6 22,12C19.6,16.4 16,18.5 12,18.5C8,18.5 4.4,16.4 2,12Z",
            "M15,12A3,3 0 1 1 9,12A3,3 0 1 1 15,12Z",
            "M4,4L20,20",
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

    /** 图钉（置顶）：上宽下窄的钉头 + 一根针，描边风格；使用时可旋转 45° 做成斜插的图钉。 */
    val Pin: ImageVector by lazy {
        stroked(
            "Pin",
            "M12,17V22",
            "M9,10.76A2,2 0 0 1 7.89,12.55L6.11,13.45A2,2 0 0 0 5,15.24V16A1,1 0 0 0 6,17H18A1,1 0 0 0 19,16V15.24A2,2 0 0 0 17.89,13.45L16.11,12.55A2,2 0 0 1 15,10.76V7A1,1 0 0 1 16,6A2,2 0 0 0 16,2H8A2,2 0 0 0 8,6A1,1 0 0 1 9,7Z",
            width = 2.0f,
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

    /** 账户：圆形头像轮廓（头 + 肩），默认头像用它（更多菜单的「账户」）。 */
    val Account: ImageVector by lazy {
        stroked(
            "Account",
            "M12,2.8A9.2,9.2 0 1 0 12,21.2A9.2,9.2 0 1 0 12,2.8Z",
            "M12,12.4A3,3 0 1 0 12,6.4A3,3 0 0 0 12,12.4Z",
            "M6.4,18.2C7.2,15.9 9.4,14.6 12,14.6C14.6,14.6 16.8,15.9 17.6,18.2",
        )
    }

    /** 网络（🌐）：圆形地球轮廓 + 赤道 + 经线椭圆 + 上下两道纬线，「网络请求」用。 */
    val Globe: ImageVector by lazy {
        stroked(
            "Globe",
            "M12,2.8A9.2,9.2 0 1 0 12,21.2A9.2,9.2 0 1 0 12,2.8Z",
            "M12,2.8C14.4,5.2 15.6,8.4 15.6,12C15.6,15.6 14.4,18.8 12,21.2C9.6,18.8 8.4,15.6 8.4,12C8.4,8.4 9.6,5.2 12,2.8Z",
            "M2.8,12H21.2",
            "M4.4,7.4C8,9 16,9 19.6,7.4",
            "M4.4,16.6C8,15 16,15 19.6,16.6",
            width = 1.8f,
        )
    }

    /** 拓展（WebExtension）：拼图块。形状取自 Lucide（ISC 许可）的 puzzle。 */
    val Puzzle: ImageVector by lazy {
        // 路径解析失败时退回脚本图标：枚举初始化会读它，不能让一个图标拖垮启动
        runCatching { puzzle() }.getOrElse { Script }
    }

    private fun puzzle(): ImageVector =
        stroked(
            "Puzzle",
            "M19.439 7.85c-.049.322.059.648.289.878l1.568 1.568c.47.47.706 1.087.706 1.704s-.235 1.233-.706 1.704l-1.611 1.611a.98.98 0 0 1-.837.276c-.47-.07-.802-.48-.968-.925a2.501 2.501 0 1 0-3.214 3.214c.446.166.855.497.925.968a.979.979 0 0 1-.276.837l-1.61 1.61a2.404 2.404 0 0 1-1.705.707 2.402 2.402 0 0 1-1.704-.706l-1.568-1.568a1.026 1.026 0 0 0-.877-.29c-.493.074-.84.504-1.02.968a2.5 2.5 0 1 1-3.237-3.237c.464-.18.894-.527.967-1.02a1.026 1.026 0 0 0-.289-.877l-1.568-1.568A2.402 2.402 0 0 1 1.998 12c0-.617.236-1.234.706-1.704L4.23 8.77c.24-.24.581-.353.917-.303.515.077.877.528 1.073 1.01a2.5 2.5 0 1 0 3.259-3.259c-.482-.196-.933-.558-1.01-1.073-.05-.336.062-.676.303-.917l1.525-1.525A2.402 2.402 0 0 1 12 1.998c.617 0 1.234.236 1.704.706l1.568 1.568c.23.23.556.338.877.29.493-.074.84-.504 1.02-.968a2.5 2.5 0 1 1 3.237 3.237c-.464.18-.894.527-.967 1.02Z",
        )

    /** 截屏：相机轮廓 + 镜头圈。 */
    val Screenshot: ImageVector by lazy {
        stroked(
            "Screenshot",
            "M4,8H7L8.5,6H15.5L17,8H20A1,1 0 0 1 21,9V18A1,1 0 0 1 20,19H4A1,1 0 0 1 3,18V9A1,1 0 0 1 4,8Z",
            "M12,10.5A3,3 0 1 0 12,16.5A3,3 0 1 0 12,10.5Z",
        )
    }

    /** 清晰度：圆角矩形里一个 HD。 */
    val Quality: ImageVector by lazy {
        stroked(
            "Quality",
            "M4,6H20A1,1 0 0 1 21,7V17A1,1 0 0 1 20,18H4A1,1 0 0 1 3,17V7A1,1 0 0 1 4,6Z",
            "M7,10V14",
            "M7,12H10",
            "M10,10V14",
            "M13,10H14.5A1.5,1.5 0 0 1 16,11.5V12.5A1.5,1.5 0 0 1 14.5,14H13Z",
        )
    }

    /** 视频源：叠放的两层方块（多个来源可切换）。 */
    val Source: ImageVector by lazy {
        stroked(
            "Source",
            "M12,3L21,8L12,13L3,8Z",
            "M3,12L12,17L21,12",
            "M3,16L12,21L21,16",
        )
    }

    /** 外部播放：方框 + 右上角跳出的箭头。 */
    val External: ImageVector by lazy {
        stroked(
            "External",
            "M14,4H20V10",
            "M20,4L11,13",
            "M18,14V18A2,2 0 0 1 16,20H6A2,2 0 0 1 4,18V8A2,2 0 0 1 6,6H10",
        )
    }

    /** 倍速：仪表盘半圆 + 指针。 */
    val Speed: ImageVector by lazy {
        stroked(
            "Speed",
            "M4.5,17A8.5,8.5 0 1 1 19.5,17",
            "M12,14L15.5,9.5",
            "M12,14H12.01",
        )
    }

    /** 屏幕镜像：显示器 + 左下角信号弧线。 */
    val ScreenMirror: ImageVector by lazy {
        stroked(
            "ScreenMirror",
            "M5,4H19A2,2 0 0 1 21,6V14A2,2 0 0 1 19,16H5A2,2 0 0 1 3,14V6A2,2 0 0 1 5,4Z",
            "M8,20H16",
            "M12,16V20",
            "M7,13H7.01",
            "M7,10.5A2.5,2.5 0 0 1 9.5,13",
            "M7,8A5,5 0 0 1 12,13",
        )
    }

    /** 外部打开：方框 + 右上外跳箭头。 */
    val OpenExternal: ImageVector by lazy {
        stroked(
            "OpenExternal",
            "M18,13.5V18A2,2 0 0 1 16,20H6A2,2 0 0 1 4,18V8A2,2 0 0 1 6,6H10.5",
            "M14,4H20V10",
            "M20,4L11,13",
        )
    }

    /** 电视：圆角屏幕 + 顶部两根天线。 */
    val Tv: ImageVector by lazy {
        stroked(
            "Tv",
            "M5,7H19A2,2 0 0 1 21,9V18A2,2 0 0 1 19,20H5A2,2 0 0 1 3,18V9A2,2 0 0 1 5,7Z",
            "M8,3L12,7L16,3",
        )
    }

    /** 清理缓存：垃圾桶。 */
    val CleanCache: ImageVector by lazy {
        stroked(
            "CleanCache",
            "M4,7H20",
            "M9,7V4.8A1,1 0 0 1 10,3.8H14A1,1 0 0 1 15,4.8V7",
            "M6,7L6.9,19.4A1.6,1.6 0 0 0 8.5,21H15.5A1.6,1.6 0 0 0 17.1,19.4L18,7",
            "M10,11V17",
            "M14,11V17",
        )
    }

    /** 同步：云 + 向上箭头（一键上传备份）。 */
    val SyncCloud: ImageVector by lazy {
        stroked(
            "SyncCloud",
            "M7.5,18.5H17A4,4 0 0 0 17.6,10.6A5.6,5.6 0 0 0 6.9,9.4A4.6,4.6 0 0 0 7.5,18.5Z",
            "M12,16V11",
            "M9.6,13.4L12,11L14.4,13.4",
        )
    }

    /** 去广告：盾牌 + 叉。 */
    val AdShield: ImageVector by lazy {
        stroked(
            "AdShield",
            "M12,3L19.5,6V11.5C19.5,16 16.5,19.5 12,21C7.5,19.5 4.5,16 4.5,11.5V6Z",
            "M9.3,9.8L14.7,15.2",
            "M14.7,9.8L9.3,15.2",
        )
    }

    /** 排序手柄：两列三排圆点（拖动把手）。圆点用零长度的圆头线段画，线宽即点径。 */
    val DragGrip: ImageVector by lazy {
        stroked(
            "DragGrip",
            "M9,6.5H9.01", "M15,6.5H15.01",
            "M9,12H9.01", "M15,12H15.01",
            "M9,17.5H9.01", "M15,17.5H15.01",
            width = 3.2f,
        )
    }
}
