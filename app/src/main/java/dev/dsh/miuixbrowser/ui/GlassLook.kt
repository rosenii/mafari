package dev.dsh.miuixbrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.dsh.miuixbrowser.data.BLUR_MIUIX
import dev.dsh.miuixbrowser.data.GlassParams
import top.yukonga.miuix.kmp.glass.GlassSurfaceShading
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.GlassColorBlendMode
import top.yukonga.miuix.kmp.material.GlassColorLayer
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassStyle
import top.yukonga.miuix.kmp.material.GlassStyles

/** 柔光玻璃的当前参数，由 MainActivity 等入口随 [dev.dsh.miuixbrowser.data.AppSettings.glassParams] 提供。 */
val LocalGlassParams = compositionLocalOf { GlassParams.Default }

/**
 * 两种 miuix-glass 质感：
 *
 * - 柔光玻璃（BLUR_MIUIX）：真玻璃感。默认不叠 puredThinGlass 那层白雾材质（material = null，
 *   主体就是「轻模糊的背景 + 样式」），样式里没有压暗项，折射更厚、边缘光更强。
 *   全部数值来自 [GlassParams]（设置里「自定义材质参数」可改，也有几套模板）。
 * - 磨砂（BLUR_FROST）：上游默认的 shading = false，只有 20dp 模糊 + 白色材质，没有折射，最稳最省。
 *
 * 应用里自己画的玻璃（底栏、菜单面板、引导页）用 [shading] / [style] / [material]；
 * miuix 的弹窗 / 对话框 / 抽屉读的是上游的全局开关，由 [update] 同步（它们自带材质，只换样式）。
 */
object GlassLook {
    /** 参数 → miuix 样式。以上游 BionicMediumRegular 为底，其余字段按 [p] 覆盖；burn 固定为 0（不烧色）。 */
    fun styleOf(p: GlassParams, dark: Boolean): GlassStyle {
        val b = GlassStyles.BionicMediumRegular
        return b.copy(
            blend = b.blend.copy(
                amount = p.amount,
                saturation = p.saturation,
                brightness = p.brightness,
                darker = p.darker,
            ),
            inner = b.inner.copy(
                tint = if (dark) Color(0.12f, 0.12f, 0.12f) else Color(1f, 1f, 1f),
                tintStrength = p.tintStrength,
                colorWhite = p.colorWhite,
                colorMix = p.colorMix,
                colorPow = p.colorPow,
            ),
            edge = b.edge.copy(width = p.edgeWidth, thickness = p.edgeThickness),
            reflect = b.reflect.copy(lighten = p.reflectLighten, strength = p.reflectStrength),
            light = b.light.copy(intensity = p.lightIntensity, oppositeIntensity = p.lightOpposite),
            refract = b.refract.copy(ior = p.ior),
            background = b.background.copy(saturation = p.bgSaturation, brightness = p.bgBrightness, burn = 0.0f),
            blur = b.blur.copy(small = p.blurSmall, big = p.blurBig),
        )
    }

    /** 当前模式是否开折射。必须在 Composable 里调，跟随 LocalBlurMode。 */
    @Composable
    fun shading(): Boolean = LocalBlurMode.current == BLUR_MIUIX

    /** 当前模式的样式。 */
    @Composable
    fun style(isDark: Boolean): GlassStyle {
        if (!shading()) return GlassDefaults.style(isDark)
        val p = LocalGlassParams.current
        return remember(p, isDark) { styleOf(p, isDark) }
    }

    /** 底栏 / 面板的材质：柔光默认不叠白雾（null = 用轻模糊的背景当主体），磨砂沿用 puredThinGlass。 */
    @Composable
    fun material(isDark: Boolean): GlassMaterial? {
        if (!shading()) return GlassMaterials.puredThinGlass(isDark)
        val p = LocalGlassParams.current
        if (!p.frostMaterial) return null
        // 材质自带的模糊半径会覆盖样式的，这里让它跟「背景模糊」滑条走
        return GlassMaterials.puredThinGlass(isDark).copy(blurRadius = (p.blurSmall / GlassDefaults.SourceDensity).dp)
    }

    /** 大块弹窗（sheet / 对话框 / 弹出菜单）的基础模糊（dp）。上游 dialogGlass 是 60dp，这里取约一半，再叠加「背景模糊」滑条。 */
    const val LARGE_BLUR_BASE_DP = 24f

    /** 大块弹窗的实际模糊半径 = 基础模糊 + 背景模糊滑条（÷3 = dp），所以最低也是 [LARGE_BLUR_BASE_DP]。 */
    fun largeBlurDp(p: GlassParams): Float = LARGE_BLUR_BASE_DP + p.blurSmall / GlassDefaults.SourceDensity

    /**
     * 大块弹窗的材质（柔光专用）：模糊不再与底栏对齐，在 [LARGE_BLUR_BASE_DP] 基础上随滑条加。
     * 未叠白雾 = 全透明材质（只带模糊半径）；叠白雾 = 磨砂同款材质，同样用这个半径。
     */
    @Composable
    fun largeMaterial(isDark: Boolean): GlassMaterial {
        val p = LocalGlassParams.current
        return remember(p, isDark) {
            val radius = largeBlurDp(p).dp
            if (p.frostMaterial) {
                GlassMaterials.puredThinGlass(isDark).copy(blurRadius = radius)
            } else {
                GlassMaterial(radius, GlassColorLayer(Color.Transparent, GlassColorBlendMode.SrcOver))
            }
        }
    }

    /**
     * 顶栏 / 小按钮等「必须传非空材质」的玻璃组件用的材质。
     * 柔光（非叠白雾）= 一层全透明材质，只带「背景模糊」滑条对应的模糊半径，效果等同底栏的 material = null；
     * 柔光叠白雾沿用 [material]；其余模式用上游顶栏按钮默认材质。
     */
    @Composable
    fun barMaterial(isDark: Boolean): GlassMaterial {
        if (!shading()) return GlassTopAppBarDefaults.buttonMaterial()
        material(isDark)?.let { return it }
        val p = LocalGlassParams.current
        return remember(p) {
            GlassMaterial(
                blurRadius = (p.blurSmall / GlassDefaults.SourceDensity).dp,
                first = GlassColorLayer(Color.Transparent, GlassColorBlendMode.SrcOver),
            )
        }
    }

    /** 顶栏按钮的样式：柔光走自定义参数，其余保持上游默认。 */
    @Composable
    fun barStyle(isDark: Boolean): GlassStyle = if (shading()) style(isDark) else GlassDefaults.Style

    /** 模式 / 参数变化时同步给 miuix 的弹窗 / 对话框 / 抽屉（普通字段赋值，幂等，可在组合中调用）。 */
    fun update(blurMode: Int, params: GlassParams) {
        val on = blurMode == BLUR_MIUIX
        GlassSurfaceShading.enabled = on
        GlassSurfaceShading.lightStyle = if (on) styleOf(params, false) else GlassDefaults.style(false)
        GlassSurfaceShading.darkStyle = if (on) styleOf(params, true) else GlassDefaults.style(true)
    }
}
