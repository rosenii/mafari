package dev.dsh.miuixbrowser

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {

    /** 从其他应用点链接进来时要打开的地址 */
    private val incomingUrl = mutableStateOf<String?>(null)

    /** 点下载通知进来时要求打开下载页 */
    private val openDownloads = mutableStateOf(false)

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        private const val SYNC_WARMUP_DELAY_MS = 3_000L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GmNotifications.consumeClick(intent)
        consumeOpenDownloads(intent)
        consumeViewIntent(intent)
    }

    private fun consumeOpenDownloads(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
            openDownloads.value = true
            intent.removeExtra(EXTRA_OPEN_DOWNLOADS)
        }
    }

    private fun consumeViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val scheme = data.scheme?.lowercase() ?: return
        if (scheme != "http" && scheme != "https") return
        // 桌面快捷方式（Gecko）：固定在应用内打开，不受「链接打开方式」影响
        if (intent.getBooleanExtra(ShortcutHelper.EXTRA_FROM_SHORTCUT, false)) {
            intent.removeExtra(ShortcutHelper.EXTRA_FROM_SHORTCUT)
            incomingUrl.value = data.toString()
            return
        }
        val settings = AppSettings(this)
        when (settings.linkOpenMode) {
            1 -> openWithCustomTab(data)
            2 -> forwardToOtherBrowser(data)
            else -> incomingUrl.value = data.toString()
        }
    }

    /** Chrome 自定义标签页 */
    private fun openWithCustomTab(uri: Uri) {
        runCatching {
            val intent = androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            intent.launchUrl(this, uri)
        }.onFailure { incomingUrl.value = uri.toString() }
    }

    /** 设置里选了「交给其他浏览器」时，弹出选择器并排除自己 */
    private fun forwardToOtherBrowser(uri: Uri) {
        runCatching {
            val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            val chooser = Intent.createChooser(view, "打开链接").apply {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(this@MainActivity, MainActivity::class.java)),
                )
            }
            startActivity(chooser)
        }
    }

    /** 图标切换推迟到离开应用时再生效，避免正在前台的入口被停用导致界面被系统关掉 */
    // Gecko 前后台切换：退到后台前抓帧、停止后盖图、切回后撤图，避免切回应用时网页黑几秒
    override fun onPause() {
        if (EngineSupport.GECKO_BUNDLED) GeckoViewLifecycle.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (EngineSupport.GECKO_BUNDLED) {
            GeckoViewLifecycle.onResume()
            GeckoDebug.refreshSystemFont()
        }
    }

    // 系统内存吃紧：收紧后台 Gecko 引擎的数量（回收的标签再切回时从库里恢复）
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 10 = RUNNING_LOW，15 = RUNNING_CRITICAL，>= 40 = 应用进了后台 LRU 列表；20（界面不可见）每次退后台都会来，不算
        if (EngineSupport.GECKO_BUNDLED && (level == 10 || level == 15 || level >= 40)) BrowserState.trimCurrent()
    }

    override fun onStop() {
        if (EngineSupport.GECKO_BUNDLED) GeckoViewLifecycle.onStop()
        super.onStop()
        FavoritesStore.flushCurrent()
        ExtensionStore.flushCurrent()
        BrowserState.flushCurrent()
        // 退到后台时按「选定图标 + 当前深浅色」同步桌面图标
        AppIconManager.sync(this)
    }

    // 系统深浅色切换（显示模式为跟随系统时）：应用在后台就立刻换图标；
    // 在前台则等退出时再换（onStop），避免使用中桌面图标闪动。
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            AppIconManager.sync(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        prewarmEncryptedDb()
        // 系统重建 Activity（savedInstanceState != null）时 intent 还是当初那条，不能再重复打开一次链接
        if (savedInstanceState == null) {
            consumeOpenDownloads(intent)
            consumeViewIntent(intent)
        }
        if (EngineSupport.GECKO_BUNDLED) GeckoDebug.load(this)
        dev.dsh.miuixbrowser.Diag.init(this)
        // 拓展安装确认框需要 Activity；弱引用，避免静态持有 Activity
        if (EngineSupport.GECKO_BUNDLED) {
            val self = java.lang.ref.WeakReference(this)
            GeckoExtensionStore.activityProvider = { self.get() }
        }
        dev.dsh.miuixbrowser.Diag.log("MainActivity.onCreate")
        AdBlock.init(this)
        // Firefox 同步不占冷启动：onCreate 里什么都不做，首帧画出后再延迟几秒在后台预热（仅「上次已知已登录」才初始化，
        // 没登录过的用户零开销）；打开「Firefox帐户同步」页（FxaScreen）/ 系统分享面板发标签页（SendTabActivity）时仍会按需 init
        CacheCleaner.runOnColdStart(this)
        CookieControl.runOnColdStart(this, AppSettings(this))
        // Gecko 运行时提前创建：必须在上面的缓存清理 / Cookie 清理之后（顺序与原来一致），但不必等到 BrowserState 组合时才建，
        // 这样 Gecko 自己的启动线程能和后面的 setContent / 标签恢复 / 加密库预热并行跑
        if (EngineSupport.GECKO_BUNDLED && EngineSupport.defaultIsGecko(this)) runCatching { GeckoRuntimeHolder.get(this) }
        // 下载列表（读库 + 清理已完成任务的临时目录）不是首屏必需：首帧之后再从磁盘恢复。
        // Downloader.start / 下载通知广播都会自己 init（幂等），不会因为晚一点而漏掉。
        window.decorView.postDelayed({ Downloader.init(applicationContext) }, 300L)

        // 全屏沉浸：内容画到状态栏和手势小白条下面，小白条保留可见。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        scheduleSyncWarmUp()

        setContent {
            val context = LocalContext.current
            val settings = remember { AppSettings(context) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.themeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            val mode = when (settings.themeMode) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            val themeController = remember(mode) { ThemeController(mode) }
            MiuixTheme(controller = themeController) {
                CompositionLocalProvider(LocalBlurMode provides settings.blurMode) {
                    BrowserScreen(
                        settings = settings,
                        initialUrl = incomingUrl.value,
                        onInitialUrlConsumed = { incomingUrl.value = null },
                        openDownloads = openDownloads.value,
                        onOpenDownloadsConsumed = { openDownloads.value = false },
                    )
                }
            }
        }
    }

    /**
     * 冷启动预热加密库：打开它要经过 Keystore 解包密钥、SQLCipher 派生密钥（PBKDF2）和十几张表的建表 / 迁移，
     * 以前第一次是在主线程（Downloader.init → HistoryDb.get）里同步做完，setContent 之前整个界面都是白屏。
     * 现在放到后台线程，与 onCreate 其余初始化并行；主线程之后真正要用库（标签恢复 / 书签 / 脚本）时，
     * HistoryDb.get 内部有锁，最多等剩下的那一小段。打开失败由 HistoryDb 自己降级处理。
     */
    private fun prewarmEncryptedDb() {
        val app = applicationContext
        Thread({
            val t0 = android.os.SystemClock.elapsedRealtime()
            runCatching { HistoryDb.get(app) }
            Diag.log("prewarm: encrypted db ready in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        }, "mafari-prewarm-db").apply { isDaemon = true }.start()
    }

    /**
     * 首帧之后再等 [SYNC_WARMUP_DELAY_MS]，然后在后台线程预热 Firefox 同步（登录状态、自动同步、发送标签页都依赖它就绪）。
     * decorView.post 保证排在首次布局 / 绘制之后；延迟让页面加载、标签恢复先跑完。init 幂等，Activity 重建不会重复初始化。
     */
    private fun scheduleSyncWarmUp() {
        if (!EngineSupport.SYNC_BUNDLED) return
        val app = applicationContext
        window.decorView.post {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { FxaSync.warmUp(app) },
                SYNC_WARMUP_DELAY_MS,
            )
        }
    }

    /** 把未捕获异常写到应用外部目录，方便没有 adb 时排查。 */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = externalMediaDirs.firstOrNull() ?: getExternalFilesDir(null) ?: filesDir
                dir.mkdirs()
                val file = java.io.File(dir, "crash.txt")
                file.writeText(
                    buildString {
                        appendLine("time=" + java.util.Date())
                        appendLine("thread=" + thread.name)
                        appendLine(error.toString())
                        error.stackTraceToString().lines().take(80).forEach { appendLine(it) }
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
