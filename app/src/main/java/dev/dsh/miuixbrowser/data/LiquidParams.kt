/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

/**
 * 液态玻璃（BLUR_LIQUID，Kyant0/AndroidLiquidGlass）的全部可调参数。纯数据，不依赖 Compose。
 *
 * 各个表面（弹出菜单 / 对话框 / sheet / 顶栏按钮 / 底栏 / 主页卡片）原本的模糊、折射、叠色各不相同，
 * 所以这里的数值大多是「倍率」：1.0 = 保持该表面原来的样子，>1 加强，<1 减弱，
 * 这样一套参数就能同时调整所有液态表面，又不会把它们的差异抹平。浅色 / 深色主题共用同一套数值。
 *
 * 默认值（[LiquidPresets] 第 0 个）与改造前写死的外观完全一致。
 */
data class LiquidParams(
    // 模糊
    val blurScale: Float,        // 背景模糊倍率；0 = 不模糊
    // 折射
    val refractHeight: Float,    // 折射带宽度倍率（lens 的 refractionHeight）
    val refractAmount: Float,    // 折射强度倍率（lens 的 refractionAmount）；宽度或强度任一为 0 则不折射
    val depthEffect: Boolean,    // 折射带景深（边缘更立体）
    val chromaticAberration: Boolean, // 边缘色散（彩边）
    // 光影
    val highlightScale: Float,   // 边缘高光宽度倍率（只作用于原本就画高光的弹窗 / 菜单 / sheet / 深色主页卡片）
    // 色彩
    val vibrancy: Boolean,       // Kyant 的 vibrancy（提高背景饱和度）
    val saturation: Float,       // 在 vibrancy 之上再乘的饱和度，1 = 不变
    val brightness: Float,       // 亮度偏移（负值压暗），0 = 不变
    // 玻璃体
    val tintScale: Float,        // 叠色（白雾 / 容器色）浓度倍率；不含主色 / 危险色按钮的彩色叠层
) {
    /** 背景模糊半径（dp）：[base] 是该表面原来的半径。 */
    fun blurDp(base: Float): Float = base * blurScale

    /** 是否还有模糊可画（0 时直接跳过，避免对 0 半径做无谓的离屏）。 */
    val hasBlur: Boolean get() = blurScale > 0.01f

    /** 折射带宽度（dp） / 强度（dp）：[base] 是该表面原来的值。 */
    fun lensHeightDp(base: Float): Float = base * refractHeight
    fun lensAmountDp(base: Float): Float = base * refractAmount

    /** 折射是否生效：宽度或强度被拖到 0 时整个 lens 都不加。 */
    val hasLens: Boolean get() = refractHeight > 0.01f && refractAmount > 0.01f

    /** 是否需要在 vibrancy 之外再追加一层 colorControls；默认值下不追加，保证与改造前逐像素一致。 */
    val hasColorTweak: Boolean get() = saturation != 1f || brightness != 0f

    /** 叠色不透明度：[base] 是该表面原来的 alpha，结果夹回 0..1。 */
    fun tintAlpha(base: Float): Float = (base * tintScale).coerceIn(0f, 1f)

    fun encode(): String = buildString {
        FIELDS.forEachIndexed { i, f -> if (i > 0) append(','); append(f.get(this@LiquidParams)) }
        TOGGLES.forEach { t -> append(',').append(if (t.get(this@LiquidParams)) 1 else 0) }
    }

    companion object {
        val FIELDS: List<ParamField<LiquidParams>> = listOf(
            ParamField<LiquidParams>("模糊", "模糊强度", 0f, 4f, 0.1f, GlassFormat.X1, { it.blurScale }, { p, v -> p.copy(blurScale = v) }),
            ParamField<LiquidParams>("折射", "折射宽度", 0f, 2.5f, 0.05f, GlassFormat.X2, { it.refractHeight }, { p, v -> p.copy(refractHeight = v) }),
            ParamField<LiquidParams>("折射", "折射强度", 0f, 2.5f, 0.05f, GlassFormat.X2, { it.refractAmount }, { p, v -> p.copy(refractAmount = v) }),
            // 最小 0.1：高光宽度为 0 时 Kyant 的高光层没有意义，也避免传 0 宽度
            ParamField<LiquidParams>("光影", "边缘高光", 0.1f, 3f, 0.1f, GlassFormat.X1, { it.highlightScale }, { p, v -> p.copy(highlightScale = v) }),
            ParamField<LiquidParams>("色彩", "饱和度", 0.5f, 2.5f, 0.05f, GlassFormat.X2, { it.saturation }, { p, v -> p.copy(saturation = v) }),
            ParamField<LiquidParams>("色彩", "亮度", -0.3f, 0.3f, 0.01f, GlassFormat.F2, { it.brightness }, { p, v -> p.copy(brightness = v) }),
            ParamField<LiquidParams>("玻璃体", "叠色浓度", 0f, 2.5f, 0.05f, GlassFormat.X2, { it.tintScale }, { p, v -> p.copy(tintScale = v) }),
        )

        /** 开关项（设置弹窗「效果」分组）；顺序同 [encode] 的尾部。 */
        val TOGGLES: List<ParamToggle<LiquidParams>> = listOf(
            ParamToggle<LiquidParams>(
                "效果", "景深折射", "折射带带景深，边缘更立体；关闭后折射更平",
                { it.depthEffect }, { p, v -> p.copy(depthEffect = v) },
            ),
            ParamToggle<LiquidParams>(
                "效果", "边缘色散", "边缘出现细微彩边，更像真玻璃；关闭后更干净",
                { it.chromaticAberration }, { p, v -> p.copy(chromaticAberration = v) },
            ),
            ParamToggle<LiquidParams>(
                "效果", "增强背景饱和度", "提亮背景色彩（Kyant vibrancy）；关闭后保持背景原色",
                { it.vibrancy }, { p, v -> p.copy(vibrancy = v) },
            ),
        )

        /** 解析 [encode] 的结果；个数不对 / 有非数字返回 null，每项都夹回合法范围。 */
        fun decode(s: String?): LiquidParams? {
            val parts = s?.split(',') ?: return null
            if (parts.size != FIELDS.size + TOGGLES.size) return null
            var p = Default
            FIELDS.forEachIndexed { i, f ->
                val v = parts[i].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
                p = f.set(p, v.coerceIn(f.min, f.max))
            }
            TOGGLES.forEachIndexed { i, t ->
                p = t.set(p, parts[FIELDS.size + i].trim() == "1")
            }
            return p
        }

        val Default: LiquidParams get() = LiquidPresets.ALL[0].params
    }
}

/**
 * 液态玻璃模板。第 0 个是默认（改造前的外观，全部倍率为 1）。
 * 下标会写进偏好；增删 / 改顺序要同步处理 AppSettings 里已保存的 liquid_preset。
 *
 * 模糊度：清透 < 强折射 < 柔和 < 白亮 < 雾面；
 * 玻璃感（折射 + 高光）：强折射 > 清透 > 默认 > 白亮 > 雾面 > 柔和。
 */
object LiquidPresets {
    /** 默认：各表面保持原来的数值。 */
    private val base = LiquidParams(
        blurScale = 1f,
        refractHeight = 1f, refractAmount = 1f, depthEffect = true, chromaticAberration = true,
        highlightScale = 1f,
        vibrancy = true, saturation = 1f, brightness = 0f,
        tintScale = 1f,
    )

    /** 清透：模糊低、叠色淡，背景清楚，折射和边缘光比默认更明显。 */
    private val clear = base.copy(
        blurScale = 0.4f,
        refractHeight = 1.05f, refractAmount = 1.2f,
        highlightScale = 1.3f,
        saturation = 1.1f,
        tintScale = 0.5f,
    )

    /** 强折射：几乎不模糊，折射最宽最强，边缘高光很重，最像一块厚玻璃。 */
    private val strong = base.copy(
        blurScale = 0.3f,
        refractHeight = 1.4f, refractAmount = 1.7f,
        highlightScale = 2.0f,
        saturation = 1.15f,
        tintScale = 0.35f,
    )

    /** 雾面：模糊高、叠色浓，折射收敛，文字可读性好。 */
    private val frosted = base.copy(
        blurScale = 3.2f,
        refractHeight = 0.75f, refractAmount = 0.6f,
        highlightScale = 0.8f,
        tintScale = 1.5f,
    )

    /** 白亮：中高模糊，提亮并加浓白雾，整体明亮，边缘仍有玻璃感。 */
    private val bright = base.copy(
        blurScale = 2.2f,
        refractHeight = 0.9f, refractAmount = 0.9f,
        highlightScale = 1.4f,
        saturation = 0.9f, brightness = 0.08f,
        tintScale = 1.9f,
    )

    /** 柔和：折射很轻、无色散无景深，更稳更省眼，适合文字多的页面。 */
    private val soft = base.copy(
        blurScale = 1.5f,
        refractHeight = 0.5f, refractAmount = 0.4f, depthEffect = false, chromaticAberration = false,
        highlightScale = 0.6f,
        tintScale = 1.2f,
    )

    const val INDEX_DEFAULT = 0
    const val INDEX_CLEAR = 1
    const val INDEX_STRONG = 2
    const val INDEX_FROSTED = 3
    const val INDEX_BRIGHT = 4
    const val INDEX_SOFT = 5

    val ALL: List<ParamPreset<LiquidParams>> = listOf(
        ParamPreset("液态默认", "Kyant 官方 catalog 的原味液态玻璃，各处保持原来的模糊、折射和叠色，下面的自定义参数回到初始值", base),
        ParamPreset("清透", "模糊低、叠色淡，背景清楚，折射和边缘光比默认更明显", clear),
        ParamPreset("强折射", "几乎不模糊，折射又宽又强，边缘高光很重，最像一块厚玻璃；浅色图标压在花哨内容上要留意可读性", strong),
        ParamPreset("雾面", "模糊高、叠色浓，折射收敛，文字可读性好", frosted),
        ParamPreset("白亮", "中高模糊，提亮并加浓白雾，整体明亮，边缘依然有玻璃感", bright),
        ParamPreset("柔和", "折射很轻，无色散无景深，更稳更省眼，适合文字多的页面", soft),
    )
}
