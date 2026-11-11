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
        GeckoViewLifecycle.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        GeckoViewLifecycle.onResume()
        GeckoDebug.refreshSystemFont()
    }

    override fun onStop() {
        GeckoViewLifecycle.onStop()
        super.onStop()
        val wanted = AppSettings(this).appIcon
        if (AppIconManager.current(this) != wanted) AppIconManager.apply(this, wanted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        // 系统重建 Activity（savedInstanceState != null）时 intent 还是当初那条，不能再重复打开一次链接
        if (savedInstanceState == null) {
            consumeOpenDownloads(intent)
            consumeViewIntent(intent)
        }
        GeckoDebug.load(this)
        dev.dsh.miuixbrowser.Diag.init(this)
        // 拓展安装确认框需要 Activity；弱引用，避免静态持有 Activity
        val self = java.lang.ref.WeakReference(this)
        GeckoExtensionStore.activityProvider = { self.get() }
        dev.dsh.miuixbrowser.Diag.log("MainActivity.onCreate")
        AdBlock.init(this)
        CacheCleaner.runOnColdStart(this)
        // 从磁盘恢复下载任务列表（临时数据在 filesDir/downloads，不受清缓存影响）
        Downloader.init(this)
        CookieControl.runOnColdStart(this, AppSettings(this))

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
