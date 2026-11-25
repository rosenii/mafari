/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

/**
 * 「自定义材质参数」弹窗用的通用描述：柔光玻璃（[GlassParams]）和液态玻璃（[LiquidParams]）共用同一个弹窗，
 * 各自只需要提供「模板列表 + 滑条字段 + 开关项」。纯数据，不依赖 Compose。
 */
enum class GlassFormat { INT, F1, F2, DP3, X1, X2 }

/** 一个可调参数：[group] / [name] 是简体中文键（界面里走 tr()），[min]..[max] 为滑条范围，[step] 为取整步长。 */
class ParamField<P>(
    val group: String,
    val name: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val format: GlassFormat,
    val get: (P) -> Float,
    val set: (P, Float) -> P,
) {
    fun show(v: Float): String = when (format) {
        GlassFormat.INT -> v.toInt().toString()
        GlassFormat.F1 -> String.format(java.util.Locale.US, "%.1f", v)
        GlassFormat.F2 -> String.format(java.util.Locale.US, "%.2f", v)
        GlassFormat.DP3 -> String.format(java.util.Locale.US, "%.1fdp", v / 3f)
        GlassFormat.X1 -> String.format(java.util.Locale.US, "×%.1f", v)
        GlassFormat.X2 -> String.format(java.util.Locale.US, "×%.2f", v)
    }
}

/** 一个开关项（布尔参数），在弹窗里显示成带副标题的开关行。 */
class ParamToggle<P>(
    val group: String,
    val name: String,
    val summary: String,
    val get: (P) -> Boolean,
    val set: (P, Boolean) -> P,
)

/** 一个模板：名称、说明和整套参数。 */
class ParamPreset<P>(val name: String, val desc: String, val params: P)
