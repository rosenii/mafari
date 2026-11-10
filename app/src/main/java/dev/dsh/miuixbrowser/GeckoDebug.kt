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
 * - [isolatedProcess]：Gecko 网页进程隔离（Android isolated process）。只在创建 Gecko 运行时读取，改动需重启应用。
 *   已知问题：隔离进程不允许 bindService，Java MediaCodec 解码器起不来 → 视频报「No video with supported format and MIME type found」
 *   （Mozilla bug 1810736 / 1988531）。所以默认关闭
 * - [nativeImmersive]：Gecko 原生沉浸。GeckoView 尺寸固定为整个内容区，状态栏留白靠「平移 + setVerticalClipping /
 *   setDynamicToolbarMaxHeight」实现，收起 / 展开时不再重新布局 / 调整视口；关闭则留白固定、不随滚动收起（最稳）
 */
object GeckoDebug {

    private const val FILE = "mafari_gecko_debug"
    private var prefs: SharedPreferences? = null

    var remoteDebug by mutableStateOf(false); private set
    var aboutConfig by mutableStateOf(false); private set
    var profiler by mutableStateOf(false); private set
    var diagLog by mutableStateOf(false); private set
    var highRefresh by mutableStateOf(true); private set
    var nativeImmersive by mutableStateOf(true); private set
    var isolatedProcess by mutableStateOf(false); private set

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        remoteDebug = p.getBoolean("remote_debug", false)
        aboutConfig = p.getBoolean("about_config", false)
        profiler = p.getBoolean("profiler", false)
        diagLog = p.getBoolean("diag", false)
        highRefresh = p.getBoolean("high_refresh", true)
        nativeImmersive = p.getBoolean("native_immersive", true)
        isolatedProcess = p.getBoolean("isolated_process", false)
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

    fun updateIsolatedProcess(v: Boolean) {
        isolatedProcess = v; save { putBoolean("isolated_process", v) }
    }

    /**
     * 把进程隔离开关写进 GeckoRuntimeSettings.Builder。nightly 的 Builder 是否提供该选项、叫什么，文档里查不到，
     * 所以按名字含 "solatedProcess"、单个 boolean 参数的方法反射查找；找不到就只记日志，不影响启动。
     */
    fun applyIsolation(builder: Any) {
        runCatching {
            val m = builder.javaClass.methods.firstOrNull {
                it.name.contains("solatedProcess", ignoreCase = true) &&
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE
            }
            if (m == null) {
                Diag.log("isolated process: GeckoRuntimeSettings.Builder 没有对应选项")
            } else {
                m.invoke(builder, isolatedProcess)
                Diag.log("isolated process: ${m.name}($isolatedProcess)")
            }
        }.onFailure { Diag.log("isolated process: apply failed ${it.cause?.message ?: it.message}") }
    }

    /** 当前运行时实际是否已启用进程隔离；取不到返回 null */
    fun runtimeIsolated(): Boolean? = runCatching {
        val s = GeckoRuntimeHolder.peek()?.settings ?: return@runCatching null
        s.javaClass.methods.firstOrNull { it.name == "getIsolatedProcessEnabled" && it.parameterTypes.isEmpty() }
            ?.invoke(s) as? Boolean
    }.getOrNull()

    fun updateNativeImmersive(v: Boolean) {
        nativeImmersive = v; save { putBoolean("native_immersive", v) }
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

    /** 调试页「渲染信息」：标签 → 值（点击整块复制） */
    fun infoLines(context: Context): List<Pair<String, String>> {
        val bc = runCatching { Class.forName("org.mozilla.geckoview.BuildConfig") }.getOrNull()
        fun field(name: String) = runCatching { bc?.getField(name)?.get(null)?.toString() }.getOrNull() ?: "未知"
        val hz = maxRefreshRate(context)
        val cur = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY).refreshRate
        }.getOrDefault(0f)
        return listOf(
            "GeckoView 版本" to field("MOZ_APP_VERSION"),
            "构建 ID" to field("MOZ_APP_BUILDID"),
            "渲染后端" to "SurfaceView（GeckoView 默认）",
            "屏幕刷新率" to "当前 ${"%.0f".format(cur)} Hz / 最高 ${"%.0f".format(hz)} Hz",
            "运行时" to if (GeckoRuntimeHolder.peek() != null) "已启动" else "未启动（打开 Gecko 标签后启动）",
            "进程隔离" to when (val r = runtimeIsolated()) {
                null -> if (isolatedProcess) "已设置开启（运行时未启动或不可读）" else "关闭"
                else -> (if (r) "运行中：开启" else "运行中：关闭") +
                    if (r != isolatedProcess) "；设置为${if (isolatedProcess) "开启" else "关闭"}，重启应用后生效" else ""
            },
            "运行日志" to if (Diag.enabled) "已开启" else "关闭",
        )
    }

    private const val PROFILER_PREF = "gfx.webrender.debug.profiler"
}
