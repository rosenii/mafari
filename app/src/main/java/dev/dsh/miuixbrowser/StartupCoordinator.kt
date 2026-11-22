package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.RestoreMode
import dev.dsh.miuixbrowser.data.TabDb
import dev.dsh.miuixbrowser.engine.webview.IncognitoProfile
import dev.dsh.miuixbrowser.engine.webview.WebViewDebug
import dev.dsh.miuixbrowser.engine.webview.WebViewStartup
import dev.dsh.miuixbrowser.browser.AppShortcuts
import dev.dsh.miuixbrowser.browser.CacheCleaner
import dev.dsh.miuixbrowser.browser.CookieControl
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.browser.FaviconLoader
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoDebug
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtensionStore
import dev.dsh.miuixbrowser.engine.gecko.GeckoRuntimeHolder
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.util.Diag
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 冷启动编排：原先分散在 [MafariApp]、[MainActivity]、[StartupWarmup] 三处的启动步骤，按时间顺序集中在这里。
 *
 * 阶段（顺序即执行顺序，与拆分前一致）：
 *  1. [onProcessCreate]   Application.onCreate，只在主进程：诊断日志 → 加密库预热 → 图标缓存目录。
 *  2. [onActivityCreate]  MainActivity.onCreate（Intent 处理之后）：Gecko 调试开关 / 拓展弹框宿主 → 去广告规则 →
 *                         缓存与 Cookie 的冷启动清理 → Gecko 运行时提前创建 → 下载列表延后恢复。
 *  3. 库就绪闸门          [isContentReady] / [awaitContentReady]：库没好之前界面只画 [StartupSkeleton]，
 *                         好了之后才组合 BrowserScreen（它首次组合会同步读库）；WebView 异步预热
 *                         （[WebViewStartup]，阶段 1 起跑）也在这里等，保证第一个 WebView 在它之后才创建。
 *  4. [onContentShown]    界面真正出来之后：Firefox 同步预热（延迟几秒）。
 *
 * 具体「怎么预热加密库」仍在 [StartupWarmup]（只管库这一件事）。新增启动任务：先想清楚它属于哪个阶段，加在对应函数里。
 */
internal object StartupCoordinator {

    /** 首帧之后再等这么久才预热 Firefox 同步：让页面加载、标签恢复先跑完。 */
    private const val SYNC_WARMUP_DELAY_MS = 3_000L

    /** 首帧之后等这么久再编译去广告规则（约 0.5 秒的后台编译），让界面先出来。 */
    private const val ADBLOCK_COMPILE_DELAY_MS = 500L

    /** WebView 拓展的后台页是隐藏 WebView，创建有开销：首帧之后再起，别和首屏抢主线程。 */
    private const val EXT_BACKGROUND_DELAY_MS = 1_500L

    /** 多个拓展的后台页（各一个隐藏 WebView）依次错开拉起的间隔：别在主线程上挤成一次长卡顿。 */
    private const val EXT_BACKGROUND_STAGGER_MS = 200L

    /** 等主线程空闲再拉后台页；一直等不到空闲（持续动画 / 滚动）就到这个时间强行拉。 */
    private const val EXT_BACKGROUND_IDLE_TIMEOUT_MS = 3_000L

    /** 拓展预热里等 WebView 预热结束的上限：先等它，免得和它抢着加载 WebView 提供方；等不到也往下走。 */
    private const val EXT_PREWARM_WEBVIEW_WAIT_MS = 3_000L

    /** 清扫上次遗留的无痕 Profile：不急，排在拓展后台页之后。 */
    private const val SWEEP_INCOGNITO_DELAY_MS = 3_000L

    /** 长按图标的快捷方式在界面出来之后再发布。 */
    private const val SHORTCUTS_DELAY_MS = 2_000L

    /** 下载列表（读库 + 清理已完成任务的临时目录）不是首屏必需：首帧之后再恢复。 */
    private const val DOWNLOADS_INIT_DELAY_MS = 300L

    /**
     * 阶段 1。GeckoView 会拉起子进程（内容进程 / 隔离进程），它们也会走 Application.onCreate，
     * 但子进程不需要这些，所以调用方只在主进程里调。
     */
    fun onProcessCreate(app: Application) {
        Diag.init(app) // 先开诊断日志，预热线程里的分段耗时才记得下来
        Diag.log("MafariApp.onCreate")
        StartupWarmup.start(app)
        FaviconLoader.init(app) // 只定磁盘目录，修剪在后台
        // WebView 异步预热：默认内核是 WebView 时才做（Gecko 默认时首屏不创建 WebView，没必要）。须尽早，越早越能和加密库预热重叠
        if (!EngineSupport.defaultIsGecko(app)) WebViewStartup.begin(app)
        prewarmExtensions(app) // 须在 WebViewStartup.begin 之后（要看它是否已开始）
    }

    /**
     * 拓展系统预热：原先第一个 WebView 标签创建时才在主线程上同步做——建拓展系统（注册二十多个 API）、
     * 读仓库（逐个解析 manifest）、读 shim、读 / 打包各拓展的 content script，拓展越多首屏越慢。
     * 现在进程一创建就在后台线程做完，首个标签装配时全是缓存命中。线程优先级比默认略低（但不进后台 cgroup，
     * 免得被饿死、到首个标签创建时还没好），不和加密库 / WebView 预热抢关键路径。
     * 和 [StartupWarmup] 互不依赖（不碰加密库）。
     */
    private fun prewarmExtensions(app: Application) {
        // Gecko 默认：WebView 拓展系统一个都用不上（Gecko 拓展走 GeckoExtensionStore），连对象都不建、线程都不起。
        // 用户中途切到 WebView 时由 [onEngineSwitchedToWebView] 补上（建系统是懒的，首个 WebView 标签装配时同步做也行）
        if (EngineSupport.defaultIsGecko(app)) return
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
                val webViewTabs = !EngineSupport.defaultIsGecko(app)
                val system = app.appContainer.webExtensions
                if (webViewTabs && WebViewStartup.started) {
                    // 判断系统 WebView 支不支持隔离世界要碰 WebView 提供方：等 WebView 预热先把它加载好。
                    // 只轮询，不调 awaitDone —— 它超时会替冷启动闸门「宣布结束」，这里不该有这个副作用
                    while (!WebViewStartup.isDone() && SystemClock.elapsedRealtime() - t0 < EXT_PREWARM_WEBVIEW_WAIT_MS) {
                        Thread.sleep(25)
                    }
                }
                system.prewarm(injection = webViewTabs)
            }.onFailure { Diag.log("prewarm: web extensions failed ${it.javaClass.simpleName}: ${it.message}") }
            Diag.log("prewarm: web extensions ready in ${SystemClock.elapsedRealtime() - t0}ms")
        }, "mafari-prewarm-ext").apply { isDaemon = true }.start()
    }

    /** 阶段 2。须在主线程、Activity.onCreate 里调用。 */
    fun onActivityCreate(activity: Activity) {
        StartupWarmup.start(activity) // MafariApp 已在进程创建时启动过；幂等，这里兜底
        if (EngineSupport.GECKO_BUNDLED) GeckoDebug.load(activity)
        Diag.init(activity)
        // 拓展安装确认框需要 Activity；弱引用，避免静态持有 Activity
        if (EngineSupport.GECKO_BUNDLED) {
            val self = java.lang.ref.WeakReference(activity)
            GeckoExtensionStore.activityProvider = { self.get() }
        }
        Diag.log("MainActivity.onCreate")
        // 远程调试是进程级静态开关，须在创建任何 WebView 之前设置。Gecko 默认时不加载（远程调试开着会立刻拉起 WebView 提供方）；
        // 之后真要建 WebView 时由 [EngineSupport.markWebViewUsed] 在建之前补加载
        if (!EngineSupport.defaultIsGecko(activity)) {
            WebViewDebug.load(activity)
            dev.dsh.miuixbrowser.engine.webview.WebViewSpeculative.load(activity)
        }
        AdBlock.init(activity)
        // Firefox 同步不占冷启动：这里什么都不做，界面出来后由 [onContentShown] 延迟预热（仅「上次已知已登录」才初始化，
        // 没登录过的用户零开销）；打开「Firefox帐户同步」页（FxaScreen）/ 系统分享面板发标签页（SendTabActivity）时仍会按需 init
        CacheCleaner.runOnColdStart(activity)
        CookieControl.runOnColdStart(activity, activity.appContainer.settings)
        // Gecko 运行时提前创建：必须在上面的缓存清理 / Cookie 清理之后，但不必等到 BrowserState 组合时才建，
        // 这样 Gecko 自己的启动线程能和后面的 setContent / 标签恢复 / 加密库预热并行跑
        if (EngineSupport.GECKO_BUNDLED && EngineSupport.defaultIsGecko(activity)) runCatching { GeckoRuntimeHolder.get(activity) }
        // Downloader.start / 下载通知广播都会自己 init（幂等），不会因为晚一点而漏掉。
        activity.window.decorView.postDelayed({ Downloader.init(activity.applicationContext) }, DOWNLOADS_INIT_DELAY_MS)
    }

    /** 阶段 3：库已就绪（含迁移，或已确定打不开而降级）。界面重建时库早已好，直接组合，不闪占位。 */
    fun isContentReady(): Boolean = StartupWarmup.isDbReady() && (!WebViewStartup.started || WebViewStartup.isDone())

    /** 阶段 3：挂起直到库就绪（在 IO 线程等，不卡主线程）。 */
    suspend fun awaitContentReady(context: Context) {
        withContext(Dispatchers.IO) {
            StartupWarmup.awaitDb(context)
            // 第一个 WebView 在闸门放行后才会创建：等 WebView 预热结束（和库预热并行，通常早已好；有超时，等不到照旧放行）
            WebViewStartup.awaitDone()
            // 顺手把标签列表读好：BrowserState 初始化（主线程）就不必再读库，只管建界面要用的那一个标签
            runCatching {
                if (context.appContainer.settings.restoreTabsMode != RestoreMode.NONE) {
                    AppDatabase.get(context)?.let { TabDb.preload(it) }
                }
            }
        }
    }

    /** 已经拉起过 WebView 拓展后台页（切到 Gecko 时据此决定要不要关）。 */
    @Volatile private var extBackgroundsStarted = false

    private fun startExtensionBackgrounds(app: Context) {
        extBackgroundsStarted = true
        runCatching { app.appContainer.webExtensions.startAll(EXT_BACKGROUND_STAGGER_MS) }
            .onFailure { Diag.log("web extensions: start backgrounds failed ${it.message}") }
    }

    /**
     * 用户在设置里把默认内核切到 WebView（主线程）：补做 Gecko 默认时跳过的 WebView 侧工作——去广告规则编译、拓展后台页。
     * Gecko 运行时进程内无法单独卸载（shutdown 会连进程一起退出），所以它只是不再被新建标签使用，内存要到下次启动才完全释放。
     */
    fun onEngineSwitchedToWebView(context: Context) {
        val app = context.applicationContext
        AdBlock.startCompile() // 幂等
        if (!extBackgroundsStarted) startExtensionBackgrounds(app)
    }

    /**
     * 用户在设置里把默认内核切到 Gecko（主线程）：关掉 WebView 拓展的后台页（隐藏 WebView），WebView 侧不再有常驻开销。
     * 已打开的 WebView 标签保持可用，但它们的拓展后台页要到下次启动后才会回来；去广告规则已编译的部分留在内存里，没有新的编译 / 订阅更新。
     */
    fun onEngineSwitchedToGecko(context: Context) {
        if (!extBackgroundsStarted) return
        extBackgroundsStarted = false
        runCatching { context.applicationContext.appContainer.webExtensions.stopAll() }
            .onFailure { Diag.log("web extensions: stop backgrounds failed ${it.message}") }
    }

    /**
     * 阶段 4：界面真正出来之后，在后台线程预热 Firefox 同步（登录状态、自动同步、发送标签页都依赖它就绪）。
     * decorView.post 保证排在首次布局 / 绘制之后；init 幂等，Activity 重建不会重复初始化。
     */
    fun onContentShown(activity: Activity) {
        val app = activity.applicationContext
        // 去广告规则：界面出来后再编译（init 只读设置，不编译），不和冷启动抢 CPU
        activity.window.decorView.post {
            // 去广告只对 WebView 标签生效：Gecko 默认时不编译（省 CPU 与内存）；切到 WebView 时由 [onEngineSwitchedToWebView] 补
            Handler(Looper.getMainLooper()).postDelayed({
                if (EngineSupport.webViewNeeded(app)) AdBlock.startCompile()
            }, ADBLOCK_COMPILE_DELAY_MS)
        }
        // WebView 拓展的后台页：没有已启用的拓展就什么都不建；幂等（Activity 重建时已在运行的会跳过）。
        // 首帧后再等一会儿，然后等主线程空闲（首屏渲染 / 标签恢复期间不插队）才拉；多个拓展依次错开
        activity.window.decorView.post {
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                val fired = java.util.concurrent.atomic.AtomicBoolean(false)
                val startBackgrounds = {
                    // Gecko 默认：不拉 WebView 拓展的后台页（每个都是隐藏 WebView）
                    if (EngineSupport.webViewNeeded(app) && fired.compareAndSet(false, true)) {
                        startExtensionBackgrounds(app)
                    }
                }
                Looper.myQueue().addIdleHandler { startBackgrounds(); false }
                handler.postDelayed({ startBackgrounds() }, EXT_BACKGROUND_IDLE_TIMEOUT_MS) // 一直不空闲时的兜底
            }, EXT_BACKGROUND_DELAY_MS)
        }
        // 上次进程被杀留在磁盘上的无痕 Profile 数据：界面出来、WebView 预热结束之后再扫（本进程已用过无痕就跳过）
        activity.window.decorView.post {
            // sweepStale 要问系统 WebView 支不支持多 Profile，会加载 WebView 提供方：Gecko 默认时不扫
            // （旧数据留在磁盘上，下次真用到无痕 WebView 时 bind() 会先清掉）
            Handler(Looper.getMainLooper()).postDelayed({
                if (EngineSupport.webViewNeeded(app)) IncognitoProfile.sweepStale()
            }, SWEEP_INCOGNITO_DELAY_MS)
        }
        // 长按应用图标的快捷方式：界面出来后再发布，不占冷启动
        activity.window.decorView.post {
            Handler(Looper.getMainLooper()).postDelayed({ AppShortcuts.publish(app) }, SHORTCUTS_DELAY_MS)
        }
        if (!EngineSupport.SYNC_BUNDLED) return
        activity.window.decorView.post {
            Handler(Looper.getMainLooper()).postDelayed({ FxaSync.warmUp(app) }, SYNC_WARMUP_DELAY_MS)
        }
    }
}
