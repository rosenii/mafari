package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.ShortcutHelper
import dev.dsh.miuixbrowser.data.FavoritesStore
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoDebug
import dev.dsh.miuixbrowser.engine.gecko.GeckoViewLifecycle
import dev.dsh.miuixbrowser.script.ExtensionStore
import dev.dsh.miuixbrowser.ui.AppIconManager
import dev.dsh.miuixbrowser.ui.BrowserScreen
import dev.dsh.miuixbrowser.ui.LocalBlurMode
import dev.dsh.miuixbrowser.ui.StartupSkeleton
import dev.dsh.miuixbrowser.util.Diag
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {

    /** 局域网访问权限（Android 17 / targetSdk 37 起必需）：DLNA 投屏搜索前调用，结果回调在主线程 */
    private var localNetCallback: ((Boolean) -> Unit)? = null
    private val localNetLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val cb = localNetCallback
        localNetCallback = null
        cb?.invoke(ok)
    }

    fun withLocalNetworkPermission(onResult: (Boolean) -> Unit) {
        val perm = "android.permission.ACCESS_LOCAL_NETWORK"
        // 低于 Android 17 没有这个权限，也不拦截局域网访问
        if (Build.VERSION.SDK_INT < 37 || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        localNetCallback = onResult
        localNetLauncher.launch(perm)
    }

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
        // 桌面快捷方式（Gecko）：固定在应用内打开，不受「链接打开方式」影响
        if (intent.getBooleanExtra(ShortcutHelper.EXTRA_FROM_SHORTCUT, false)) {
            intent.removeExtra(ShortcutHelper.EXTRA_FROM_SHORTCUT)
            incomingUrl.value = data.toString()
            return
        }
        val settings = appContainer.settings
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

    // 画中画：按 Home / 切任务时，若脚本播放器正在全屏播放，进入系统画中画（见 script/MediaHost.kt）
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        dev.dsh.miuixbrowser.script.MediaHost.onUserLeaveHint(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        dev.dsh.miuixbrowser.script.MediaHost.onPipModeChanged(this, isInPictureInPictureMode)
    }

    override fun onStop() {
        // 刚进画中画时个别系统会先回调一次 onStop：这不是退到后台，别暂停。关闭画中画小窗（✕）走的是真正的 onStop，照常暂停
        val keepPlaying = dev.dsh.miuixbrowser.script.MediaHost.keepPlayingOnStop()
        if (!keepPlaying) {
            if (EngineSupport.GECKO_BUNDLED) GeckoViewLifecycle.onStop()
            // 退到后台：所有标签的媒体都暂停（两种内核）
            BrowserState.pauseMediaCurrent()
        }
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
        // 系统重建 Activity（savedInstanceState != null）时 intent 还是当初那条，不能再重复打开一次链接
        if (savedInstanceState == null) {
            consumeOpenDownloads(intent)
            consumeViewIntent(intent)
        }
        StartupCoordinator.onActivityCreate(this)

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
            val settings = remember { context.appContainer.settings }
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
                    // 首帧先画一个与主题底色一致的占位面：BrowserScreen 首次组合要同步读加密库（标签 / 书签 / 脚本），
                    // 库还没打开时直接组合会把主线程卡住 1~2 秒，系统启动画面（白屏）也就一直收不起来。
                    // 库在后台线程等好之后再组合 BrowserScreen；库早已就绪（如界面重建）则直接组合，不闪占位。
                    val contentReady = remember { mutableStateOf(StartupCoordinator.isContentReady()) }
                    if (contentReady.value) {
                        BrowserScreen(
                            settings = settings,
                            initialUrl = incomingUrl.value,
                            onInitialUrlConsumed = { incomingUrl.value = null },
                            openDownloads = openDownloads.value,
                            onOpenDownloadsConsumed = { openDownloads.value = false },
                        )
                    } else {
                        remember { Diag.log("placeholder composed (db not ready yet)") }
                        StartupSkeleton()
                    }
                    LaunchedEffect(Unit) {
                        if (!contentReady.value) {
                            StartupCoordinator.awaitContentReady(context)
                            contentReady.value = true
                            Diag.log("gate open: db ready, composing BrowserScreen")
                        }
                        // Firefox 同步预热排在界面真正出来之后（此前是首帧后 3 秒，现在首帧提前了，不能再和加密库打开抢资源）
                        StartupCoordinator.onContentShown(this@MainActivity)
                    }
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
