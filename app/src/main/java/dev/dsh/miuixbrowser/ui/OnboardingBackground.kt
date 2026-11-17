package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import top.yukonga.miuix.kmp.material.RuntimeShader
import top.yukonga.miuix.kmp.material.asBrush
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * 用户引导页的 HyperOS 式动态背景：4 个色点在整屏范围内缓慢游走、混合，再叠一层极细的抗色带噪点，
 * 看上去就是一整屏被大半径模糊过的流动色彩（着色器本身就是软边渐变，不需要再对它做一次模糊）。
 *
 * 着色器与配色改自 miuix 示例里的 BgEffect（OS3 手机配色，Apache-2.0），整理成只供引导页使用的一份：
 * 去掉了平板 / OS2 配色，绘制范围固定为全屏。
 *
 * 引导页的玻璃卡片用 miuix-glass 采样这一层（见 OnboardingScreen 里的 layerBackdrop），所以卡片后面也是流动的色彩。
 * 不支持 RuntimeShader（minSdk 33 基本不会发生）时退回主题底色。
 */

private const val OS3_BG_FRAG = """
    uniform vec2 uResolution;
    uniform float uAnimTime;
    uniform vec4 uBound;
    uniform float uTranslateY;
    uniform vec3 uPoints[4];
    uniform vec2 uPointsAnim[4];
    uniform vec4 uColors[4];
    uniform float uAlphaMulti;
    uniform float uNoiseScale;
    uniform float uPointRadiusMulti;
    uniform float uSaturateOffset;
    uniform float uLightOffset;

    vec3 rgb2hsv(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }

    vec3 hsv2rgb(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }

    float hash(vec2 p) {
        vec3 p3 = fract(vec3(p.xyx) * 0.13);
        p3 += dot(p3, p3.yzx + 3.333);
        return fract((p3.x + p3.y) * p3.z);
    }

    float perlin(vec2 x) {
        vec2 i = floor(x); vec2 f = fract(x);

        float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
        float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));

        vec2 u = f * f * (3.0 - 2.0 * f);
        return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
    }

    float gradientNoise(in vec2 uv) {
        return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
    }

    vec4 main(vec2 fragCoord){
        vec2 vUv = fragCoord/uResolution;
        vUv.y = 1.0-vUv.y;
        vec2 uv = vUv;
        uv -= vec2(0., uTranslateY);
        uv.xy -= uBound.xy;
        uv.xy /= uBound.zw;

        vec4 color = vec4(0.0);
        float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));

        for (int i = 0; i < 4; i++){
            vec4 pointColor = uColors[i];
            pointColor.rgb *= pointColor.a;
            vec2 point = uPointsAnim[i];
            float rad = uPoints[i].z * uPointRadiusMulti;

            float d = distance(uv, point);
            float pct = smoothstep(rad, 0., d);
            color.rgb = mix(color.rgb, pointColor.rgb, pct);
            color.a = mix(color.a, pointColor.a, pct);
        }

        float oppositeNoise = smoothstep(0., 1., noiseValue);
        color.rgb /= color.a;
        vec3 hsv = rgb2hsv(color.rgb);
        hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
        color.rgb = hsv2rgb(hsv);
        color.rgb += oppositeNoise * uLightOffset;

        color.a = clamp(color.a, 0., 1.);
        color.a *= uAlphaMulti;

        color += (10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0);
        return vec4(color.rgb * color.a, color.a);
    }
"""

private class BgPreset(
    val points: FloatArray,
    val colors1: FloatArray,
    val colors2: FloatArray,
    val colors3: FloatArray,
    val colorPeriodSec: Float,
    val lightOffset: Float,
    val saturateOffset: Float,
    val pointOffset: Float,
)

private val PRESET_LIGHT = BgPreset(
    points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
    colors1 = floatArrayOf(1.0f, 0.9f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f, 0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f),
    colors2 = floatArrayOf(0.58f, 0.74f, 1.0f, 1.0f, 1.0f, 0.9f, 0.93f, 1.0f, 0.74f, 0.76f, 1.0f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f),
    colors3 = floatArrayOf(0.98f, 0.86f, 0.9f, 1.0f, 0.6f, 0.73f, 0.98f, 1.0f, 0.92f, 0.93f, 1.0f, 1.0f, 0.56f, 0.69f, 1.0f, 1.0f),
    colorPeriodSec = 5.0f,
    lightOffset = 0.1f,
    saturateOffset = 0.2f,
    pointOffset = 0.2f,
)

private val PRESET_DARK = BgPreset(
    points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
    colors1 = floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0.0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
    colors2 = floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0.0f, 0.2f, 0.78f, 0.5f),
    colors3 = floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f),
    colorPeriodSec = 8.0f,
    lightOffset = 0.0f,
    saturateOffset = 0.17f,
    pointOffset = 0.4f,
)

/** 把一份配色 / 时间喂给着色器；缓冲区复用，逐帧只做数组写入，不产生新对象。 */
private class BgPainter {
    private val shader = RuntimeShader(OS3_BG_FRAG).also {
        it.setFloatUniform("uTranslateY", 0f)
        it.setFloatUniform("uNoiseScale", 1.5f)
        it.setFloatUniform("uPointRadiusMulti", 1f)
        it.setFloatUniform("uAlphaMulti", 1f)
        // 整屏绘制：色点坐标系铺满整个区域
        it.setFloatUniform("uBound", floatArrayOf(0f, 0f, 1f, 1f))
    }
    val brush = shader.asBrush()

    private val resolution = FloatArray(2)
    private val colors = FloatArray(16)
    private val pointsAnim = FloatArray(8)
    private var preset: BgPreset? = null

    fun update(width: Float, height: Float, preset: BgPreset, time: Float) {
        if (resolution[0] != width || resolution[1] != height) {
            resolution[0] = width
            resolution[1] = height
            shader.setFloatUniform("uResolution", resolution)
        }
        if (this.preset !== preset) {
            this.preset = preset
            shader.setFloatUniform("uPoints", preset.points)
            shader.setFloatUniform("uLightOffset", preset.lightOffset)
            shader.setFloatUniform("uSaturateOffset", preset.saturateOffset)
        }
        shader.setFloatUniform("uAnimTime", time)

        // 色点游走（与 miuix BgEffectPainter 同一条轨迹）
        for (i in 0 until 4) {
            val srcX = preset.points[i * 3]
            val srcY = preset.points[i * 3 + 1]
            val x = srcX + sin(time + srcY) * preset.pointOffset
            val y = srcY + cos(time + x) * preset.pointOffset
            pointsAnim[i * 2] = x
            pointsAnim[i * 2 + 1] = y
        }
        shader.setFloatUniform("uPointsAnim", pointsAnim)

        // 配色在三组颜色之间循环渐变（顺序 2 → 1 → 2 → 3 → 2 …），用 smoothstep 缓动
        val stage = time / preset.colorPeriodSec
        val base = floor(stage).toInt()
        val t = stage - base
        val f = t * t * (3f - 2f * t)
        val from = colorsOf(preset, base)
        val to = colorsOf(preset, base + 1)
        for (i in 0 until 16) colors[i] = from[i] + (to[i] - from[i]) * f
        shader.setFloatUniform("uColors", colors)
    }

    private fun colorsOf(p: BgPreset, index: Int): FloatArray = when (index.mod(4)) {
        1 -> p.colors1
        3 -> p.colors3
        else -> p.colors2
    }
}

/** 动画帧间隔下限：约 40fps。整屏着色器每帧都要重算，限一下帧率省电，肉眼看不出区别（色点本来就在缓慢移动）。 */
private const val MIN_FRAME_NANOS = 25_000_000L

/**
 * 引导页背景。[modifier] 里放 layerBackdrop 时，要把它放在本函数的 modifier 上，这样录进去的是流动的色彩层。
 */
@Composable
internal fun OnboardingBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val surface = MiuixTheme.colorScheme.surface
    if (!remember { isRuntimeShaderSupported() }) {
        Box(modifier = modifier.background(surface), content = content)
        return
    }
    val dark = isAppDarkTheme()
    val preset = if (dark) PRESET_DARK else PRESET_LIGHT
    val painter = remember { BgPainter() }
    var time by remember { mutableFloatStateOf(0f) }

    // 页面不在前台时 withFrameNanos 不会回调，动画自然暂停
    LaunchedEffect(Unit) {
        val origin = withFrameNanos { it }
        var last = origin
        while (true) {
            val now = withFrameNanos { it }
            if (now - last < MIN_FRAME_NANOS) continue
            last = now
            time = (now - origin) / 1_000_000_000f
        }
    }

    Box(
        modifier = modifier.drawBehind {
            // time 只在绘制阶段读取：每帧只重绘这一层，不会让整个引导页重组
            painter.update(size.width, size.height, preset, time)
            drawRect(surface)
            drawRect(painter.brush)
        },
        content = content,
    )
}
