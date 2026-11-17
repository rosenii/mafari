package dev.dsh.miuixbrowser.ui

import androidx.compose.runtime.Composable
import dev.dsh.miuixbrowser.data.BLUR_MIUIX
import top.yukonga.miuix.kmp.glass.GlassSurfaceShading
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassStyle
import top.yukonga.miuix.kmp.material.GlassStyles

/**
 * 两种 miuix-glass 质感：
 *
 * - 柔光玻璃（BLUR_MIUIX）：打开折射和边缘光（shading），用最透、边缘高光最强的 Bionic 样式，更像真玻璃，
 *   代价是每块玻璃多跑一遍折射着色器。
 * - 磨砂（BLUR_FROST）：上游默认的 shading = false，只有模糊 + 着色，没有折射，最稳最省。
 *
 * 应用里自己画的玻璃（底栏、菜单面板、引导页）用 [shading] / [style]；
 * miuix 的弹窗 / 对话框 / 抽屉读的是上游的全局开关，由 [update] 同步。
 */
object GlassLook {
    private val bionic: GlassStyle get() = GlassStyles.BionicMediumRegular

    /** 当前模式是否开折射。必须在 Composable 里调，跟随 LocalBlurMode。 */
    @Composable
    fun shading(): Boolean = LocalBlurMode.current == BLUR_MIUIX

    /** 当前模式的样式。 */
    @Composable
    fun style(isDark: Boolean): GlassStyle = if (shading()) bionic else GlassDefaults.style(isDark)

    /** 模式变化时同步给 miuix 的弹窗 / 对话框 / 抽屉（普通字段赋值，幂等，可在组合中调用）。 */
    fun update(blurMode: Int) {
        val on = blurMode == BLUR_MIUIX
        GlassSurfaceShading.enabled = on
        GlassSurfaceShading.lightStyle = if (on) bionic else GlassDefaults.style(false)
        GlassSurfaceShading.darkStyle = if (on) bionic else GlassDefaults.style(true)
    }
}
