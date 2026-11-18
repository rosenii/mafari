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

enum class GlassFormat { INT, F1, F2, DP3 }

/** 一个可调参数：[group] / [name] 是简体中文键（界面里走 tr()），[min]..[max] 为滑条范围，[step] 为取整步长。 */
class GlassField(
    val group: String,
    val name: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val format: GlassFormat,
    val get: (GlassParams) -> Float,
    val set: (GlassParams, Float) -> GlassParams,
) {
    fun show(v: Float): String = when (format) {
        GlassFormat.INT -> v.toInt().toString()
        GlassFormat.F1 -> String.format(java.util.Locale.US, "%.1f", v)
        GlassFormat.F2 -> String.format(java.util.Locale.US, "%.2f", v)
        GlassFormat.DP3 -> String.format(java.util.Locale.US, "%.1fdp", v / 3f)
    }
}

class GlassPreset(val name: String, val desc: String, val params: GlassParams)

/** 柔光玻璃模板。第 0 个是默认。 */
object GlassPresets {
    private val clear = GlassParams(
        blurSmall = 24f, blurBig = 120f,
        edgeWidth = 70f, edgeThickness = 110f, ior = 3.5f,
        lightIntensity = 2.6f, lightOpposite = 1.2f, reflectStrength = 0.8f, reflectLighten = 1.0f,
        saturation = 1.6f, brightness = 0.06f, darker = 0f, amount = 0.2f,
        bgSaturation = 1.2f, bgBrightness = 0f,
        tintStrength = 0.12f, colorWhite = 0.1f, colorMix = 0.15f, colorPow = 1.0f,
        frostMaterial = false,
    )

    val ALL: List<GlassPreset> = listOf(
        GlassPreset("清透", "轻模糊，背景清楚，带折射和边缘光（默认）", clear),
        GlassPreset(
            "水晶", "几乎不模糊，折射最强，最像真玻璃；浅色图标压在深色内容上要留意可读性",
            clear.copy(
                blurSmall = 12f, edgeWidth = 90f, edgeThickness = 140f, ior = 4.5f,
                lightIntensity = 3.0f, lightOpposite = 1.6f, reflectStrength = 1.0f, reflectLighten = 1.2f,
                saturation = 1.8f, brightness = 0.04f, tintStrength = 0.06f, colorWhite = 0.06f, colorMix = 0.1f,
            ),
        ),
        GlassPreset(
            "晶边", "中心通透，边缘折射和高光很重，轮廓分明",
            clear.copy(
                blurSmall = 18f, edgeWidth = 40f, edgeThickness = 170f, ior = 5.0f,
                lightIntensity = 3.4f, lightOpposite = 2.0f, reflectStrength = 1.1f, reflectLighten = 1.4f,
                colorMix = 0.12f,
            ),
        ),
        GlassPreset(
            "雾玻璃", "模糊更大、更偏白，仍有折射，文字可读性最好",
            clear.copy(
                blurSmall = 60f, blurBig = 200f, edgeWidth = 60f, edgeThickness = 90f, ior = 3.0f,
                lightIntensity = 2.2f, lightOpposite = 1.0f, saturation = 1.4f, brightness = 0.08f,
                tintStrength = 0.2f, colorWhite = 0.2f, colorMix = 0.3f,
            ),
        ),
        GlassPreset(
            "折射磨砂", "叠加磨砂同款白色材质，再加折射和边缘光（接近磨砂但有玻璃边）",
            clear.copy(
                blurSmall = 60f, edgeWidth = 70f, edgeThickness = 100f, ior = 3.2f,
                saturation = 1.4f, brightness = 0f, tintStrength = 0.1f, colorWhite = 0.05f, colorMix = 0.1f,
                frostMaterial = true,
            ),
        ),
        GlassPreset(
            "miuix 默认", "菜单、弹窗、底栏、按钮等全部使用 miuix 项目各自的默认样式和材质，下面的自定义参数不生效",
            clear.copy(upstreamDefaults = true),
        ),
    )
}
