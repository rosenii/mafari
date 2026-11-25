/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

/**
 * 柔光玻璃（BLUR_MIUIX）的全部可调参数。纯数据，不依赖 Compose；
 * 由 ui/GlassLook.kt 的 [GlassParams] → GlassStyle 转换落到 miuix-glass 上。
 * 浅色 / 深色主题共用同一套数值，只有玻璃底色（浅色偏白、深色偏黑）随主题自动切换。
 */
data class GlassParams(
    // 模糊
    val blurSmall: Float,      // 背景模糊半径（÷3 = dp，越小背景越清楚）
    val blurBig: Float,        // 取色用的大范围模糊
    // 折射
    val edgeWidth: Float,      // 折射边缘带宽度
    val edgeThickness: Float,  // 玻璃厚度，决定边缘折射位移
    val ior: Float,            // 折射率，1 不弯折
    // 光影
    val lightIntensity: Float, // 主光强度
    val lightOpposite: Float,  // 反向光强度
    val reflectStrength: Float,// 边缘反射混入量
    val reflectLighten: Float, // 边缘反射提亮
    // 色彩
    val saturation: Float,     // 饱和度
    val brightness: Float,     // 亮度偏移（负值压暗）
    val darker: Float,         // 暗部压缩
    val amount: Float,         // 亮度曲线混入量
    val bgSaturation: Float,   // 大范围模糊取色的饱和度
    val bgBrightness: Float,   // 大范围模糊取色的亮度偏移
    // 玻璃体
    val tintStrength: Float,   // 底色（浅色偏白 / 深色偏黑）混入强度
    val colorWhite: Float,     // 向白色靠拢的量
    val colorMix: Float,       // 成品玻璃色替换折射背景的量（越大越不透）
    val colorPow: Float,       // 最终 gamma，>1 变暗，<1 提亮
    // 材质
    val frostMaterial: Boolean, // 是否叠加磨砂同款的白色材质（会让玻璃更雾）
    // 默认值
    val upstreamDefaults: Boolean = false, // true = 菜单 / 弹窗 / 底栏 / 按钮等全部用 miuix 上游各自的默认样式和材质，上面的数值不生效
) {
    fun encode(): String = buildString {
        FIELDS.forEachIndexed { i, f -> if (i > 0) append(','); append(f.get(this@GlassParams)) }
        append(',').append(if (frostMaterial) 1 else 0)
        append(',').append(if (upstreamDefaults) 1 else 0)
    }

    companion object {
        val FIELDS: List<GlassField> = listOf(
            GlassField("模糊", "背景模糊", 6f, 120f, 1f, GlassFormat.DP3, { it.blurSmall }, { p, v -> p.copy(blurSmall = v) }),
            GlassField("模糊", "取色模糊", 40f, 500f, 10f, GlassFormat.INT, { it.blurBig }, { p, v -> p.copy(blurBig = v) }),
            GlassField("折射", "边缘宽度", 20f, 120f, 1f, GlassFormat.INT, { it.edgeWidth }, { p, v -> p.copy(edgeWidth = v) }),
            GlassField("折射", "玻璃厚度", 20f, 200f, 1f, GlassFormat.INT, { it.edgeThickness }, { p, v -> p.copy(edgeThickness = v) }),
            GlassField("折射", "折射率", 1f, 6f, 0.1f, GlassFormat.F1, { it.ior }, { p, v -> p.copy(ior = v) }),
            GlassField("光影", "主光强度", 0f, 4f, 0.1f, GlassFormat.F1, { it.lightIntensity }, { p, v -> p.copy(lightIntensity = v) }),
            GlassField("光影", "反向光强度", 0f, 3f, 0.1f, GlassFormat.F1, { it.lightOpposite }, { p, v -> p.copy(lightOpposite = v) }),
            GlassField("光影", "边缘反射", 0f, 1.5f, 0.05f, GlassFormat.F2, { it.reflectStrength }, { p, v -> p.copy(reflectStrength = v) }),
            GlassField("光影", "反射提亮", 0f, 2f, 0.05f, GlassFormat.F2, { it.reflectLighten }, { p, v -> p.copy(reflectLighten = v) }),
            GlassField("色彩", "饱和度", 0.5f, 3f, 0.1f, GlassFormat.F1, { it.saturation }, { p, v -> p.copy(saturation = v) }),
            GlassField("色彩", "亮度", -0.3f, 0.3f, 0.01f, GlassFormat.F2, { it.brightness }, { p, v -> p.copy(brightness = v) }),
            GlassField("色彩", "暗部压缩", 0f, 0.4f, 0.01f, GlassFormat.F2, { it.darker }, { p, v -> p.copy(darker = v) }),
            GlassField("色彩", "曲线混入", 0f, 1f, 0.05f, GlassFormat.F2, { it.amount }, { p, v -> p.copy(amount = v) }),
            GlassField("色彩", "取色饱和度", 0f, 3f, 0.1f, GlassFormat.F1, { it.bgSaturation }, { p, v -> p.copy(bgSaturation = v) }),
            GlassField("色彩", "取色亮度", -0.3f, 0.3f, 0.01f, GlassFormat.F2, { it.bgBrightness }, { p, v -> p.copy(bgBrightness = v) }),
            GlassField("玻璃体", "底色强度", 0f, 1f, 0.01f, GlassFormat.F2, { it.tintStrength }, { p, v -> p.copy(tintStrength = v) }),
            GlassField("玻璃体", "泛白", 0f, 0.6f, 0.01f, GlassFormat.F2, { it.colorWhite }, { p, v -> p.copy(colorWhite = v) }),
            GlassField("玻璃体", "遮盖度", 0f, 1f, 0.01f, GlassFormat.F2, { it.colorMix }, { p, v -> p.copy(colorMix = v) }),
            GlassField("玻璃体", "伽马", 0.7f, 1.5f, 0.01f, GlassFormat.F2, { it.colorPow }, { p, v -> p.copy(colorPow = v) }),
        )

        /** 开关项（设置弹窗「材质」分组）。 */
        val TOGGLES: List<ParamToggle<GlassParams>> = listOf(
            ParamToggle<GlassParams>(
                "材质", "叠加磨砂白色材质", "开启后玻璃更雾、更不透，背景模糊跟「背景模糊」滑条走",
                { it.frostMaterial }, { p, v -> p.copy(frostMaterial = v) },
            ),
        )

        /** 解析 [encode] 的结果；个数不对 / 有非数字返回 null，每项都夹回合法范围。 */
        fun decode(s: String?): GlassParams? {
            val parts = s?.split(',') ?: return null
            // 旧版少最后一项 upstreamDefaults（按 false 处理）
            if (parts.size != FIELDS.size + 1 && parts.size != FIELDS.size + 2) return null
            var p = Default
            FIELDS.forEachIndexed { i, f ->
                val v = parts[i].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
                p = f.set(p, v.coerceIn(f.min, f.max))
            }
            return p.copy(
                frostMaterial = parts[FIELDS.size].trim() == "1",
                upstreamDefaults = parts.getOrNull(FIELDS.size + 1)?.trim() == "1",
            )
        }

        val Default: GlassParams get() = GlassPresets.ALL[0].params
    }
}

typealias GlassField = ParamField<GlassParams>
typealias GlassPreset = ParamPreset<GlassParams>

/**
 * 柔光玻璃模板。第 0 个是默认（miuix 默认 = 全部用上游自己的样式和材质，数值不生效）。
 * 下标会写进偏好，改顺序 / 增删要在 AppSettings.glassPresetMigrated 里加迁移。
 *
 * 模糊度（背景模糊）：水晶 < 清透 < 雾霭 < 白亮；
 * 玻璃感（折射 + 边缘光）：水晶 > 清透 > 白亮 > 雾霭，晶边是中心通透、边缘特别重的另一条路线。
 */
object GlassPresets {
    /** 公共底子：轻模糊 + 折射 + 边缘光（晶边直接在它上面改，数值保持原样）。 */
    private val base = GlassParams(
        blurSmall = 24f, blurBig = 120f,
        edgeWidth = 70f, edgeThickness = 110f, ior = 3.5f,
        lightIntensity = 2.6f, lightOpposite = 1.2f, reflectStrength = 0.8f, reflectLighten = 1.0f,
        saturation = 1.6f, brightness = 0.06f, darker = 0f, amount = 0.2f,
        bgSaturation = 1.2f, bgBrightness = 0f,
        tintStrength = 0.12f, colorWhite = 0.1f, colorMix = 0.15f, colorPow = 1.0f,
        frostMaterial = false,
    )

    /** 清透：模糊低，背景清楚，玻璃感强（折射、边缘光都明显）。 */
    private val clear = base.copy(
        blurSmall = 21f, blurBig = 110f,
        edgeWidth = 72f, edgeThickness = 125f, ior = 3.9f,
        lightIntensity = 2.9f, lightOpposite = 1.4f, reflectStrength = 0.9f, reflectLighten = 1.1f,
        saturation = 1.6f, brightness = 0.05f,
        tintStrength = 0.1f, colorWhite = 0.08f, colorMix = 0.12f,
    )

    /** 水晶：模糊最低，玻璃感最强，最像真玻璃。 */
    private val crystal = base.copy(
        blurSmall = 9f, blurBig = 90f,
        edgeWidth = 92f, edgeThickness = 150f, ior = 4.8f,
        lightIntensity = 3.2f, lightOpposite = 1.8f, reflectStrength = 1.1f, reflectLighten = 1.3f,
        saturation = 1.8f, brightness = 0.04f,
        tintStrength = 0.05f, colorWhite = 0.05f, colorMix = 0.08f,
    )

    /** 晶边：中心通透，边缘折射和高光很重（保持原样）。 */
    private val crystalEdge = base.copy(
        blurSmall = 18f, edgeWidth = 40f, edgeThickness = 170f, ior = 5.0f,
        lightIntensity = 3.4f, lightOpposite = 2.0f, reflectStrength = 1.1f, reflectLighten = 1.4f,
        colorMix = 0.12f,
    )

    /** 雾霭：模糊中等，玻璃感中等，偏白偏灰，文字可读性好。 */
    private val haze = base.copy(
        blurSmall = 42f, blurBig = 180f,
        edgeWidth = 65f, edgeThickness = 95f, ior = 3.0f,
        lightIntensity = 2.2f, lightOpposite = 1.0f, reflectStrength = 0.65f, reflectLighten = 0.8f,
        saturation = 1.3f, brightness = 0.07f, bgSaturation = 1.1f, bgBrightness = 0.02f,
        tintStrength = 0.16f, colorWhite = 0.14f, colorMix = 0.24f, colorPow = 0.98f,
    )

    /** 白亮：模糊高，叠磨砂白材质并提亮泛白，边缘玻璃效果依然明显。 */
    private val brightWhite = base.copy(
        blurSmall = 72f, blurBig = 220f,
        edgeWidth = 72f, edgeThickness = 125f, ior = 3.8f,
        lightIntensity = 3.0f, lightOpposite = 1.6f, reflectStrength = 1.0f, reflectLighten = 1.5f,
        saturation = 1.2f, brightness = 0.14f, bgSaturation = 1.1f, bgBrightness = 0.04f,
        tintStrength = 0.28f, colorWhite = 0.35f, colorMix = 0.4f, colorPow = 0.88f,
        frostMaterial = true,
    )

    const val INDEX_UPSTREAM = 0
    const val INDEX_CLEAR = 1
    const val INDEX_CRYSTAL = 2
    const val INDEX_CRYSTAL_EDGE = 3
    const val INDEX_HAZE = 4
    const val INDEX_BRIGHT = 5

    val ALL: List<GlassPreset> = listOf(
        GlassPreset(
            "miuix 默认", "菜单、弹窗、底栏、按钮等全部使用 miuix 项目各自的默认样式和材质，下面的自定义参数不生效",
            base.copy(upstreamDefaults = true),
        ),
        GlassPreset("清透", "模糊低，背景清楚，玻璃感强，折射和边缘光明显", clear),
        GlassPreset("水晶", "模糊最低，玻璃感最强，最像真玻璃；浅色图标压在深色内容上要留意可读性", crystal),
        GlassPreset("晶边", "中心通透，边缘折射和高光很重，轮廓分明", crystalEdge),
        GlassPreset("雾霭", "模糊和玻璃感都适中，偏白偏灰，文字可读性好", haze),
        GlassPreset("白亮", "模糊高，磨砂白材质加提亮泛白，整体明亮，边缘玻璃感依然明显", brightWhite),
    )
}
