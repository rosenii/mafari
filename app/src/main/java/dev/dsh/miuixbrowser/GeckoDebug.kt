package dev.dsh.miuixbrowser

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Gecko 调试 / 性能开关（设置 → 高级 → Gecko 调试）。
 *
 * 除 [highRefresh] 外全部默认关闭：调试能力不该在日常使用时白白消耗性能。
 * - [remoteDebug]：USB 远程调试（桌面 Firefox about:debugging 连接），写入 GeckoRuntimeSettings，立即生效
 * - [aboutConfig]：允许打开 about:config，写入 GeckoRuntimeSettings，立即生效
 * - [profiler]：WebRender 性能叠层（gfx.webrender.debug.profiler，nightly 偏好名可能变；不存在时无副作用）
 * - [diagLog]：写 diag.txt 运行日志（含 SurfaceView 创建 / 销毁），同时作用于 [Diag]
 * - [highRefresh]：给 Gecko 的 SurfaceView 声明高刷新率，避免系统因 UI 线程没有帧而把窗口降到低刷新率
 * - [fixedInset]：Gecko 网页上方的状态栏留白保持固定。留白随底栏收起而变化会让 GeckoView 在滑动途中重新布局 / 调整视口，是掉帧来源
 */
object GeckoDebug {

    private const val FILE = "mafari_gecko_debug"
    private var prefs: SharedPreferences? = null

    var remoteDebug by mutableStateOf(false); private set
    var aboutConfig by mutableStateOf(false); private set
    var profiler by mutableStateOf(false); private set
    var diagLog by mutableStateOf(false); private set
    var highRefresh by mutableStateOf(true); private set
    var fixedInset by mutableStateOf(true); private set

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        remoteDebug = p.getBoolean("remote_debug", false)
        aboutConfig = p.getBoolean("about_config", false)
        profiler = p.getBoolean("profiler", false)
        diagLog = p.getBoolean("diag", false)
        highRefresh = p.getBoolean("high_refresh", true)
        fixedInset = p.getBoolean("fixed_inset", true)
    }

    private inline fun save(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
    }

    fun updateRemoteDebug(v: Boolean) {
        remoteDebug = v; save { putBoolean("remote_debug", v) }
        runCatching { GeckoRuntimeHolder.peek()?.settings?.setRemoteDebuggingEnabled(v) }
    }

    fun updateAboutConfig(v: Boolean) {
        aboutConfig = v; save { putBoolean("about_config", v) }
        runCatching { GeckoRuntimeHolder.peek()?.settings?.setAboutConfigEnabled(v) }
    }

    fun updateProfiler(v: Boolean) {
        profiler = v; save { putBoolean("profiler", v) }
        GeckoPrefs.set(PROFILER_PREF, if (v) true else GeckoPrefs.CLEAR)
    }

    fun updateDiagLog(context: Context, v: Boolean) {
        diagLog = v; save { putBoolean("diag", v) }
        Diag.setEnabled(context, v)
    }

    fun updateHighRefresh(v: Boolean) {
        highRefresh = v; save { putBoolean("high_refresh", v) }
        GeckoViewLifecycle.applyFrameRate()
    }

    fun updateFixedInset(v: Boolean) {
        fixedInset = v; save { putBoolean("fixed_inset", v) }
    }

    /** Gecko 运行时创建后调用：把需要写进 about:config 的调试项补上（只在开启时写，关闭时什么都不碰） */
    fun pushPrefs() {
        if (profiler) GeckoPrefs.set(PROFILER_PREF, true)
    }

    /** 设备支持的最高刷新率（Hz）；取不到返回 0（= 不声明偏好） */
    fun maxRefreshRate(context: Context): Float = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        d.supportedModes.maxOfOrNull { it.refreshRate } ?: d.refreshRate
    }.getOrDefault(0f)

    /** 设置页展示用：Gecko 版本 / 渲染后端 / 刷新率 */
    fun infoSummary(context: Context): String {
        val ver = runCatching {
            val c = Class.forName("org.mozilla.geckoview.BuildConfig")
            c.getField("MOZ_APP_VERSION").get(null).toString() + " (" + c.getField("MOZ_APP_BUILDID").get(null) + ")"
        }.getOrDefault("未知")
        val hz = maxRefreshRate(context)
        return "GeckoView $ver · SurfaceView 后端 · 屏幕最高 ${if (hz > 0f) "%.0f".format(hz) else "?"} Hz"
    }

    private const val PROFILER_PREF = "gfx.webrender.debug.profiler"
}
