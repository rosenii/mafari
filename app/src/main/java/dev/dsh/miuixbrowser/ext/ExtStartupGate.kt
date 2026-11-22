package dev.dsh.miuixbrowser.ext

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.dsh.miuixbrowser.StartupCoordinator
import dev.dsh.miuixbrowser.appContainer
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.util.Diag
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView 拓展的「晚加载」闸门：冷启动期间一律不碰拓展——不预热、不读仓库、不打包 content script、
 * 不给标签装配、请求 / 导航热路径也跳过（[dev.dsh.miuixbrowser.AppContainer.webExtensionsOrNull] 在闸门开启前返回 null）。
 *
 * 开闸时机（先到先得，见 [scheduleOpen]）：
 *  - 第一个网页加载完成（[BrowserTab] 的 onPageFinished）；
 *  - 界面出来后：当前是主页（没有网址）→ 短暂延迟；当前要加载网页 → 等它加载，最多等 [StartupCoordinator] 里定的时间；
 *  - 兜底：第一个标签请求装配拓展后 [HARD_TIMEOUT_MS] 仍没开闸，强制开。
 *
 * 开闸后：后台线程完成拓展预热（读仓库 + shim + 各拓展 content script 打包，主线程不再同步读盘）→ 主线程给已有标签补装拓展
 * （[runWhenReady] 登记的任务）→ 错开拉起各拓展的后台页。此后创建的标签直接同步装配（缓存已命中）。
 */
internal object ExtStartupGate {

    /** 第一个标签要装拓展、但迟迟没人开闸时的强制开闸时间。 */
    private const val HARD_TIMEOUT_MS = 6_000L

    /** 开闸前等主线程空闲的上限：一直不空闲（持续动画 / 滚动）就到点强行开。 */
    private const val IDLE_TIMEOUT_MS = 2_000L

    /** 拓展是否已就绪（预热完成、已有标签已补装）。就绪前拓展系统在热路径上视为不存在。 */
    @Volatile var ready = false
        private set

    /** [ready] 的 Compose 镜像：界面里要等拓展就绪才创建 / 组合的部分观察它（只在主线程写）。 */
    val readyState = androidx.compose.runtime.mutableStateOf(false)

    private val opened = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** 等拓展就绪再执行的任务（主线程访问）。 */
    private val waiters = ArrayList<() -> Unit>()
    private var hardTimeoutScheduled = false

    /** 就绪后在主线程执行 [task]；已就绪则立刻执行。主线程调用。 */
    fun runWhenReady(app: Context, task: () -> Unit) {
        if (ready) { task(); return }
        waiters.add(task)
        if (!hardTimeoutScheduled) {
            hardTimeoutScheduled = true
            main.postDelayed({ open(app, "timeout") }, HARD_TIMEOUT_MS)
        }
    }

    /** 过 [delayMs] 再等主线程空闲，然后开闸。可重复调用，已开闸则什么都不做。主线程调用。 */
    fun scheduleOpen(app: Context, delayMs: Long, reason: String) {
        if (opened.get()) return
        main.postDelayed({
            if (opened.get()) return@postDelayed
            val fired = AtomicBoolean(false)
            val go = { if (fired.compareAndSet(false, true)) open(app, reason) }
            Looper.myQueue().addIdleHandler { go(); false }
            main.postDelayed({ go() }, IDLE_TIMEOUT_MS)
        }, delayMs)
    }

    /** 立刻开闸（幂等）。可在任意线程调用。 */
    fun open(app: Context, reason: String) {
        if (!opened.compareAndSet(false, true)) return
        val appCtx = app.applicationContext
        Diag.log("ext: late load starts ($reason)")
        // Gecko 默认且没建过 WebView / 网页增强关着：没有拓展要预热，直接放行（之后再打开网页增强时按需同步装配）
        if (!EngineSupport.webViewNeeded(appCtx) || !EngineSupport.pageEnhancementsOn(appCtx)) {
            main.post { finish(appCtx) }
            return
        }
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            runCatching {
                // 略低于默认优先级（不进后台 cgroup，免得被饿死）：别和首屏渲染 / 页面加载抢核
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
                appCtx.appContainer.webExtensions.prewarm(injection = !EngineSupport.defaultIsGecko(appCtx))
            }.onFailure { Diag.log("ext: late prewarm failed ${it.javaClass.simpleName}: ${it.message}") }
            Diag.log("ext: prewarm done in ${SystemClock.elapsedRealtime() - t0}ms (background thread)")
            main.post { finish(appCtx) }
        }, "mafari-ext-late").apply { isDaemon = true }.start()
    }

    private fun finish(app: Context) {
        ready = true
        readyState.value = true
        val tasks = ArrayList(waiters)
        waiters.clear()
        val t0 = SystemClock.elapsedRealtime()
        for (t in tasks) runCatching { t() }
        Diag.log("ext: applied to ${tasks.size} deferred tab(s) in ${SystemClock.elapsedRealtime() - t0}ms")
        StartupCoordinator.startExtensionBackgroundsWhenReady(app)
    }
}
