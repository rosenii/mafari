package dev.dsh.miuixbrowser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.util.UUID

/**
 * GeckoRuntime 进程内只能有一个：第一次创建 Gecko 标签时才惰性初始化（不用 Gecko 的人完全没有开销）。
 */
object GeckoRuntimeHolder {

    @Volatile
    private var runtime: GeckoRuntime? = null

    /** GeckoView nightly 只打了 arm64-v8a，其它 ABI 的设备只能用 WebView */
    fun supported(): Boolean = EngineSupport.geckoAvailable()

    /** 已创建则返回，不会为了「看一眼」而启动 Gecko */
    fun peek(): GeckoRuntime? = runtime

    /** 必须在主线程调用 */
    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            GeckoDebug.load(context)
            val builder = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .aboutConfigEnabled(GeckoDebug.aboutConfig)
                .remoteDebuggingEnabled(GeckoDebug.remoteDebug)
            GeckoDebug.applyIsolation(builder)
            // Gecko 只在启动时枚举字体：赶在运行时创建前，把系统字体文件的软链接放进 profile/fonts
            GeckoDebug.prepareSystemFont()
            val settings = builder.build()
            val created = GeckoRuntime.create(context.applicationContext, settings)
            runtime = created
            // 「Gecko 高级保护」里的设置（ETP 档位 / 安全 DNS…）+ 之前记下的 about:config 偏好，此刻统一写入
            GeckoProtection.load(context)
            GeckoProtection.pushAll()
            CookieControl.pushGecko(context)
            GeckoSitePerms.load(context)
            GeckoSitePerms.pushAutoplay()
            GeckoLogins.install(created, context) // 网页登录的存储端（密码库）+ 自动填充开关
            runCatching { created.setWebNotificationDelegate(GeckoWebNotifications(context.applicationContext)) }
            GeckoDebug.pushPrefs()
            FloccusPatcher.applyOnStartup(context) // 装过改包版 Floccus：保持签名校验关闭
            GeckoPrefs.flush()
            CookieControl.runPendingGeckoColdClear()
            return created
        }
    }

    /** 清 Gecko 的网络 / 图片缓存（只有 Gecko 已经启动过才有事可做） */
    fun clearCaches() {
        val rt = runtime ?: return
        runCatching {
            rt.storageController.clearData(
                StorageController.ClearFlags.NETWORK_CACHE or StorageController.ClearFlags.IMAGE_CACHE,
            )
        }
    }
}

/** 创建一个未打开的 session（普通标签自己 open；拓展 tabs.create 的由 Gecko 负责 open） */
fun newGeckoSession(privateMode: Boolean = false): GeckoSession =
    GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(privateMode).build())

/**
 * 一个标签页的 Gecko 内核：一个 [GeckoSession] + 一个 [GeckoView]。
 *
 * 只负责「把 Gecko 的回调翻译成 [BrowserTab] 已有的状态」，标签页 / 历史 / 书签 / 地址栏
 * 等上层逻辑全部仍由 BrowserTab / BrowserState 提供，所以两种内核共用同一套界面。
 */
class GeckoTabEngine(
    private val context: Context,
    privateMode: Boolean,
    private val tab: BrowserTab,
    /**
     * 拓展 tabs.create 时由 [GeckoExtensionStore] 先建好并交给 Gecko 的 session。
     * 这种 session 的 open() 由 Gecko 负责（回调还没返回时不能自己 open），所以这里只等它打开后再挂到视图上。
     */
    adopted: GeckoSession? = null,
) {
    private val runtime: GeckoRuntime = GeckoRuntimeHolder.get(context)

    private val isAdopted = adopted != null

    val session: GeckoSession = adopted ?: newGeckoSession(privateMode)

    val view: GeckoView = GeckoView(context)

    var canGoBack = false
        private set
    var canGoForward = false
        private set

    /** 当前页在会话历史里的下标（-1 = 还没有历史） */
    var historyIndex = -1
        private set

    /**
     * Gecko 的 onLocationChange 与 onHistoryStateChange 是两条独立回调：页面提交后，
     * historyIndex 可能还停在上一页的值。提交已发生而下标还没更新时视为「过期」，
     * 返回逻辑不能拿它去判断「已经在第一页」（否则会提前掉回主页）。
     */
    /** 从主页重新加载新页面前清空会话历史：新页面是这个标签的「第一页」，下面不再垫着旧历史 */
    fun purgeHistory() {
        historyIndex = -1
        runCatching { session.purgeHistory() }
    }

    private var lastLocationAt = 0L
    private var lastHistoryAt = 0L
    val historyStale: Boolean
        get() = lastLocationAt > lastHistoryAt && SystemClock.uptimeMillis() - lastLocationAt < 800L

    /** 页面滚动位置：供底栏自动收起 / 下拉刷新判断 */
    var scrollY = 0
        private set

    var pageTitle = ""
        private set
    var inFullScreen = false
        private set

    private var lastUrl = ""
    private var lastTouchAt = 0L
    private var lastDirectLoadAt = 0L

    init {
        GeckoViewLifecycle.register(this)
        installDelegates()
        GeckoGm.ensureInstalled(context, tab.userScripts, tab.gmScriptsEnabled())
        GeckoGm.attachSession(session, tab)
        if (!isAdopted) session.open(runtime)
        // 拓展按钮的弹出页请求按标签页 session 路由：给这个 session 挂上拓展的 ActionDelegate / TabDelegate
        GeckoExtensionStore.registerSession(session)
        // 使用 GeckoView 默认渲染后端（SurfaceView）。不要在 session.open() 后调用
        // setViewBackend()：该方法会销毁并重建内部渲染 View，可能造成 Surface 生命周期错位。
        // 网页区域不需要被毛玻璃采样，因此无需 TextureView。
        if (isAdopted) attachAdoptedSession() else view.setSession(session)
        // 网页输入框唤出系统自动填充（Google 密码管理器等）
        GeckoAutofill.enable(view, session)
        installTouchListener()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = applyFrameRateHint()
            override fun onViewDetachedFromWindow(v: View) {}
        })
        applyFrameRateHint()
    }

    // ---------------------------------------------------------------- 原生沉浸
    //
    // GeckoView 保持整块内容区大小不变，状态栏留白由界面层「平移」出来；页面底部被推出屏幕的那一截
    // 通过 setVerticalClipping 告诉 Gecko，底部固定元素才不会被裁掉。setDynamicToolbarMaxHeight 声明可收起区域的最大高度。
    // 先设最大高度、再设裁剪量（Gecko 在改最大高度时会把裁剪量清零）。必须在主线程。

    private var immersiveMax = 0
    private var immersiveClip = 0

    fun applyImmersive(maxPx: Int, clipPx: Int) {
        runCatching {
            if (maxPx != immersiveMax) {
                immersiveMax = maxPx
                immersiveClip = -1
                view.setDynamicToolbarMaxHeight(maxPx)
            }
            val clip = clipPx.coerceIn(0, maxPx)
            if (clip != immersiveClip) {
                immersiveClip = clip
                view.setVerticalClipping(clip)
            }
        }
    }

    // ---------------------------------------------------------------- SurfaceView 帧率声明 / 诊断
    //
    // SurfaceView 的内容由 Gecko 的合成器直接送显，UI 线程在滑动时几乎不出帧；部分系统（自适应刷新率）
    // 会因此把窗口压到低刷新率，表现成「滑动掉帧」。给 Surface 声明期望帧率，让系统按屏幕最高刷新率合成。
    // 同时在「Gecko 调试 → 运行日志」开启时记录 Surface 的创建 / 变化 / 销毁，方便区分
    // 「Gecko 没画」和「画了但系统没显示」。

    private var surfaceCallback: SurfaceHolder.Callback? = null

    private fun findSurfaceView(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findSurfaceView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun hintFrameRate(surface: Surface) {
        if (!surface.isValid) return
        val rate = if (GeckoDebug.highRefresh) GeckoDebug.maxRefreshRate(context) else 0f
        runCatching { surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    /** 挂上 Surface 回调（只挂一次）并立即声明一次帧率；设置里切换开关时由 [GeckoViewLifecycle.applyFrameRate] 重新调用 */
    fun applyFrameRateHint() {
        val sv = findSurfaceView(view) ?: return
        if (surfaceCallback == null) {
            val cb = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (Diag.enabled) Diag.log("gecko surfaceCreated")
                    hintFrameRate(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (Diag.enabled) Diag.log("gecko surfaceChanged ${width}x$height")
                    hintFrameRate(holder.surface)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (Diag.enabled) Diag.log("gecko surfaceDestroyed")
                }
            }
            surfaceCallback = cb
            sv.holder.addCallback(cb)
        }
        hintFrameRate(sv.holder.surface)
    }

    // ---------------------------------------------------------------- 基础操作

    fun loadUri(uri: String) {
        lastDirectLoadAt = SystemClock.uptimeMillis()
        // 油猴脚本经内置扩展注册，注册是异步的：脚本还没生效时把首次导航推迟到「已同步」（最多 2s）
        GeckoGm.whenReady { if (!tab.closed) session.loadUri(uri) }
    }

    /**
     * 拓展建的标签：等 Gecko 把 session 打开后再挂到 GeckoView 上；
     * 超时仍未打开（旧版 GeckoView 不替我们 open）就自己打开。
     */
    private fun attachAdoptedSession() {
        val deadline = SystemClock.uptimeMillis() + 2_000
        lateinit var step: Runnable
        step = Runnable {
            if (tab.closed) return@Runnable
            val open = runCatching { session.isOpen }.getOrDefault(false)
            if (!open && SystemClock.uptimeMillis() < deadline) {
                view.postDelayed(step, 25)
                return@Runnable
            }
            if (!open) runCatching { session.open(runtime) }.onFailure { Diag.log("gecko: open adopted session failed: $it") }
            runCatching { view.setSession(session) }.onFailure { Diag.log("gecko: setSession(adopted) failed: $it") }
            GeckoAutofill.enable(view, session)
            // session 一旦可用就把「当前标签」告诉拓展（tabs.query / 安装页关闭等依赖它）
            GeckoExtensionStore.markActiveTab()
        }
        view.post(step)
    }

    /**
     * 拓展 tabs.create({url}) 通常由 Gecko 自己加载 url；个别版本不加载，这里兜底一次。
     * 只在 Gecko 没有开始导航时才加载，避免同一个地址加载两次（比如油猴的安装页只能读取一次数据）。
     */
    fun ensureLoaded(uri: String) {
        if (uri.isBlank() || uri == "about:blank") return
        view.postDelayed({
            if (tab.closed) return@postDelayed
            val cur = lastUrl
            if (cur.isEmpty() || cur == "about:blank") {
                Diag.log("gecko: extension tab was not loaded by Gecko, loading $uri")
                loadUri(uri)
            }
        }, 1_000)
    }

    // ---------------------------------------------------------------- 会话状态（前进后退栈 + 滚动位置）
    //
    // Gecko 每次历史 / 滚动变化都会回调 onSessionStateChange，这里只记下最新的一份引用，不在回调里序列化；
    // 真要保存（退到后台 / 回收引擎）时才 toString()。恢复用 SessionState.fromString + session.restoreState。

    @Volatile
    private var latestState: GeckoSession.SessionState? = null
    private var stateDirty = false

    /** 当前会话状态的字符串形式；还没收到过状态、或体积异常大（超过 [MAX_STATE_CHARS]）返回 null */
    fun serializedState(): String? =
        runCatching { latestState?.toString() }.getOrNull()?.takeIf { it.isNotEmpty() && it.length <= MAX_STATE_CHARS }

    /** 自上次取走后状态有变化才返回（并清掉脏标记），没变化返回 null：退到后台时只写变了的标签 */
    fun takeDirtyState(): String? {
        if (!stateDirty) return null
        stateDirty = false
        return serializedState()
    }

    /**
     * 用保存的状态恢复会话（回到当时的历史栈和滚动位置，当前页会重新加载）。
     * 返回 false = 状态字符串解析不了，调用方改为按地址加载。与 [loadUri] 一样等油猴脚本注册完再动。
     */
    fun restoreState(serialized: String): Boolean {
        val st = runCatching { GeckoSession.SessionState.fromString(serialized) }.getOrNull() ?: return false
        lastDirectLoadAt = SystemClock.uptimeMillis()
        GeckoGm.whenReady {
            if (tab.closed) return@whenReady
            lastDirectLoadAt = SystemClock.uptimeMillis()
            runCatching { session.restoreState(st) }
                .onFailure { Diag.log("gecko: restoreState failed: ${it.message}") }
        }
        return true
    }

    fun reload() = session.reload()

    fun stop() = session.stop()

    fun goBack() = session.goBack()

    fun goForward() = session.goForward()

    fun currentUrl(): String = lastUrl

    @Volatile
    private var sessionActive = true

    fun setActive(active: Boolean) {
        sessionActive = active
        runCatching { session.setActive(active) }
    }

    // ---------------------------------------------------------------- 前后台切换：防止切回应用时网页黑屏
    //
    // 现在是 SurfaceView 后端：下面的截图 / 盖图逻辑只对 TextureView 生效，SurfaceView 下 findTextureView 为 null 自然跳过；
    // SurfaceView 的恢复靠 onAppForeground 里的 setActive / setFocused（Surface 由系统在后台销毁、回前台重建）。
    // 旧：GeckoView 用 TextureView 后端（为了能被毛玻璃采样）。应用退到后台后，TextureView 的缓冲会被系统回收，
    // 切回来时 Gecko 要重新建合成面再画一帧，这几秒里显示的是黑底。
    // 做法：退到后台前把当前画面抓成一张小图 → 完全停止（onStop）后盖在 GeckoView 上 →
    // 切回来后轻推一下 Gecko 重绘，等 TextureView 里重新有内容了（或超时）再撤掉这张图。

    private var frozenBitmap: Bitmap? = null
    private var freezeOverlay: ImageView? = null
    private var freezeRemoval: Runnable? = null

    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findTextureView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Activity.onPause：画面还在，趁现在抓一张（半分辨率，省内存） */
    fun captureFrameForBackground() {
        runCatching {
            clearFreeze()
            if (!view.isAttachedToWindow || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return
            val tv = findTextureView(view) ?: return
            if (!tv.isAvailable) return
            frozenBitmap = tv.getBitmap((view.width / 2).coerceAtLeast(1), (view.height / 2).coerceAtLeast(1))
        }
    }

    /** Activity.onStop：真的看不见了才盖图（分屏 / 画中画仅失焦时不会走到这里，不影响实时画面） */
    fun showFrozenFrame() {
        val bmp = frozenBitmap ?: return
        if (freezeOverlay != null) return
        runCatching {
            val iv = ImageView(context).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            view.addView(iv)
            freezeOverlay = iv
        }
    }

    /** Activity.onResume：切回前台 */
    fun onAppForeground() {
        if (freezeOverlay == null) {
            // 只是失焦又回来（没经过 onStop）：不需要盖图，丢掉抓到的图
            frozenBitmap?.let { runCatching { it.recycle() } }
            frozenBitmap = null
            // 切回前台时确保合成器恢复渲染，避免长时间后台后画面不刷新
            if (sessionActive) {
                runCatching { session.setActive(true) }
                runCatching { session.setFocused(true) }
            }
            return
        }
        // 轻推一下：恢复渲染 / 焦点，并让 TextureView 重新布局重绘
        if (sessionActive) {
            runCatching { session.setActive(true) }
            runCatching { session.setFocused(true) }
        }
        view.requestLayout()
        view.invalidate()
        scheduleFreezeRemoval()
    }

    private fun scheduleFreezeRemoval() {
        freezeRemoval?.let { view.removeCallbacks(it) }
        val start = SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (freezeOverlay == null) return
                val elapsed = SystemClock.uptimeMillis() - start
                // 至少盖 250ms 避免一闪；最多盖 5 秒，防止纯黑网页永远撤不掉
                if ((elapsed >= 250 && textureLooksPainted()) || elapsed >= 5000) {
                    clearFreeze()
                } else {
                    view.postDelayed(this, 100)
                }
            }
        }
        freezeRemoval = tick
        view.postDelayed(tick, 100)
    }

    /** TextureView 里重新有非黑内容 = Gecko 已经把页面画回来了 */
    private fun textureLooksPainted(): Boolean {
        val tv = findTextureView(view) ?: return true
        if (!tv.isAvailable) return false
        val bmp = runCatching { tv.getBitmap(16, 16) }.getOrNull() ?: return false
        return try {
            var painted = false
            loop@ for (y in 0 until bmp.height) {
                for (x in 0 until bmp.width) {
                    val p = bmp.getPixel(x, y)
                    if ((p ushr 24) != 0 && (p and 0xFFFFFF) != 0) {
                        painted = true
                        break@loop
                    }
                }
            }
            painted
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    private fun clearFreeze() {
        freezeRemoval?.let { view.removeCallbacks(it) }
        freezeRemoval = null
        freezeOverlay?.let { iv -> runCatching { view.removeView(iv) } }
        freezeOverlay = null
        frozenBitmap?.let { runCatching { it.recycle() } }
        frozenBitmap = null
    }

    fun exitFullScreen() {
        runCatching { session.exitFullScreen() }
    }

    /** 桌面模式 / 自定义 UA：Gecko 在会话设置里切换，不需要改脚本 */
    fun applyUserAgent(customUa: String, desktop: Boolean) {
        runCatching {
            val st = session.settings
            st.setUserAgentMode(
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
            )
            st.setViewportMode(
                if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
            )
            st.setUserAgentOverride(customUa.ifBlank { null })
        }
    }

    /** 深浅色：Gecko 的 prefers-color-scheme 是运行时级别的 */
    fun applyTheme(dark: Boolean) {
        runCatching {
            runtime.settings.setPreferredColorScheme(
                if (dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT,
            )
        }
    }

    /** Cookie 策略（运行时级别，官方 ContentBlocking API）：普通 / 无痕窗口一致 */
    fun applyCookieMode(mode: Int) {
        runCatching {
            val behavior = when (mode) {
                1 -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
                2 -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
                3 -> ContentBlocking.CookieBehavior.ACCEPT_NONE
                else -> ContentBlocking.CookieBehavior.ACCEPT_ALL
            }
            val cb = runtime.settings.contentBlocking
            cb.setCookieBehavior(behavior)
            cb.setCookieBehaviorPrivateMode(behavior)
        }
    }

    /** 隐私 / 跟踪：全部由「Gecko 高级保护」决定（[GeckoProtection]），这里只确保已加载并写入。 */
    fun applyPrivacy() {
        GeckoProtection.load(context)
        GeckoProtection.pushAll()
    }

    fun find(text: String) {
        runCatching { session.finder.find(text, 0) }
    }

    /** flags: 1 = 向后查找（FINDER_FIND_BACKWARDS），0 = 向前 */
    fun findNext(forward: Boolean) {
        runCatching { session.finder.find(null, if (forward) 0 else 1) }
    }

    fun clearFind() {
        runCatching { session.finder.clear() }
    }

    /** 截取当前画面（异步，回调在主线程） */
    fun capture(onBitmap: (Bitmap) -> Unit) {
        runCatching {
            view.capturePixels().accept { bitmap -> if (bitmap != null) onBitmap(bitmap) }
        }
    }

    fun destroy() {
        GeckoGm.detachSession(session)
        GeckoViewLifecycle.unregister(this)
        clearFreeze()
        runCatching { view.releaseSession() }
        runCatching { session.close() }
    }

    // ---------------------------------------------------------------- 回调翻译

    private fun installDelegates() {
        // 网页弹层：JS 对话框 / <select> / 认证 / 日期 / 文件选择
        session.promptDelegate = GeckoPromptHandler(tab)

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.geckoPageStart(url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.geckoPageStop(success)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.geckoProgress(progress)
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                latestState = sessionState
                stateDirty = true
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (url.isNullOrEmpty()) return
                lastUrl = url
                lastLocationAt = SystemClock.uptimeMillis()
                tab.geckoLocation(url)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoTabEngine.canGoBack = canGoBack
                tab.geckoNavChanged()
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                this@GeckoTabEngine.canGoForward = canGoForward
                tab.geckoNavChanged()
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                val target = request.uri
                if (FxaSync.interceptRedirect(target)) return GeckoResult.fromValue(AllowOrDeny.DENY)
                // .user.js：与 WebView 一致，拦下并弹出「导入脚本」
                if (isUserScriptUrl(target)) {
                    tab.geckoUserScriptLink(target)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                // 拓展安装包（.xpi / AMO 下载链接）：不当普通下载，直接弹安装确认
                if (isXpiUrl(target)) {
                    tab.geckoExtensionLink(target)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                val scheme = runCatching { Uri.parse(target).scheme?.lowercase() }.getOrNull()
                if (scheme == null || scheme in INTERNAL_SCHEMES) {
                    // 网页重定向权限：页面自己发起（无手势、非地址栏、非服务器 3xx）的跨站跳转
                    val direct = request.isDirectNavigation || SystemClock.uptimeMillis() - lastDirectLoadAt < 1_000
                    if ((scheme == "http" || scheme == "https") &&
                        tab.geckoInterceptRedirect(target, direct, request.hasUserGesture, request.isRedirect)
                    ) {
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
                // weixin:// alipays:// intent:// 等：交给系统。有用户手势 / 地址栏输入直接唤起；
                // 页面自己无手势地唤起其他应用，按「网页重定向」权限处理（询问 / 总是允许 / 总是拒绝，与 WebView 一致）
                val direct = request.isDirectNavigation || SystemClock.uptimeMillis() - lastDirectLoadAt < 1_000
                if (request.hasUserGesture || direct) {
                    tab.geckoLaunchExternal(Uri.parse(target))
                } else {
                    tab.geckoExternalRequest(Uri.parse(target))
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            // window.open / target=_blank：不真正创建新会话，只截获地址，由标签页逻辑开新标签
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val linkClick = SystemClock.uptimeMillis() - lastTouchAt < 2_000
                if (uri.isNotBlank() && uri != "about:blank") tab.geckoNewWindow(uri, linkClick)
                return null
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                tab.geckoLoadError("加载失败（错误 ${error.category}/${error.code}）")
                return null
            }
        }

        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onHistoryStateChange(
                session: GeckoSession,
                historyList: GeckoSession.HistoryDelegate.HistoryList,
            ) {
                historyIndex = historyList.currentIndex
                lastHistoryAt = SystemClock.uptimeMillis()
                tab.geckoNavChanged()
            }
        }

        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                this@GeckoTabEngine.scrollY = scrollY
                tab.scrollSignal.trySend(Unit)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                pageTitle = title.orEmpty()
                tab.geckoTitle(pageTitle)
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                val src = element.srcUri
                val link = element.linkUri
                when {
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && !src.isNullOrEmpty() ->
                        tab.onLongPressImage?.invoke(src)
                    !link.isNullOrEmpty() -> tab.onLongPressLink?.invoke(link)
                }
            }

            /** Gecko 判定「不是网页」的响应（下载 / 附件 / blob）都走这里 */
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val uri = response.uri
                val headers = response.headers
                val mime = headerOf(headers, "content-type")?.substringBefore(';')?.trim()?.ifBlank { null }
                val disposition = headerOf(headers, "content-disposition")
                when {
                    // 站点把 .user.js 以附件形式返回：改成「导入脚本」
                    isUserScriptUrl(uri) -> {
                        runCatching { response.body?.close() }
                        tab.geckoUserScriptLink(uri)
                    }
                    // 服务器按拓展包类型返回（重定向后的下载地址没有 .xpi 后缀也能识别）
                    isXpiUrl(uri) || mime.equals("application/x-xpinstall", ignoreCase = true) -> {
                        runCatching { response.body?.close() }
                        tab.geckoExtensionLink(uri)
                    }
                    // blob：HTTP 下载器拉不到，直接把 Gecko 给的响应体落到缓存文件
                    uri.startsWith("blob:", ignoreCase = true) -> spoolBody(response, uri, disposition, mime)
                    uri.startsWith("data:", ignoreCase = true) -> {
                        runCatching { response.body?.close() }
                        tab.geckoDataUrlDownload(uri, disposition, mime)
                    }
                    else -> {
                        // 带登录态的下载：Downloader 重新请求拿不到 Gecko 的 Cookie，所以体积不大的文件直接
                        // 消费 Gecko 给的响应体（落到缓存后走与 blob 相同的确认 / 保存流程）；
                        // 大文件或没有 Content-Length 的仍交给 Downloader（断点 / 进度 / 通知）
                        val length = headerOf(headers, "content-length")?.trim()?.toLongOrNull()
                        val http = uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)
                        if (http && response.body != null && length != null && length in 1..SPOOL_LIMIT) {
                            spoolBody(response, uri, disposition, mime)
                        } else {
                            runCatching { response.body?.close() }
                            tab.onDownload?.invoke(uri, null, disposition, mime)
                        }
                    }
                }
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                inFullScreen = fullScreen
                tab.geckoFullScreen(fullScreen)
            }

            override fun onCloseRequest(session: GeckoSession) {
                tab.geckoCloseRequest()
            }

            override fun onCrash(session: GeckoSession) {
                recover()
            }

            override fun onKill(session: GeckoSession) {
                recover()
            }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) {
                tab.geckoEnsureAndroid(permissions?.toList().orEmpty()) { ok ->
                    if (ok) callback.grant() else callback.reject()
                }
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int>? {
                val result = GeckoResult<Int>()
                val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                val answer = { ok: Boolean -> result.complete(if (ok) allow else deny) }
                val host = hostOf(perm.uri)
                val p = perm.permission
                when {
                    p == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ->
                        tab.geckoRequestLocation(host, answer)
                    p == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                        tab.geckoRequestSimple(host, GeckoSitePerms.NOTIFICATION, "向你发送通知", answer)
                    p == GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                        tab.geckoRequestSimple(host, GeckoSitePerms.STORAGE, "使用持久存储", answer)
                    p == GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
                        tab.geckoRequestSimple(host, GeckoSitePerms.DRM, "播放受 DRM 控制的内容", answer)
                    p == PERM_STORAGE_ACCESS -> {
                        val third = runCatching { perm.javaClass.getField("thirdPartyOrigin").get(perm) as? String }
                            .getOrNull()?.let { hostOf(it) }.orEmpty()
                        val what = if (third.isNotEmpty()) "使用 $third 的跨站 Cookie" else "使用跨站 Cookie"
                        tab.geckoRequestSimple(host, GeckoSitePerms.STORAGE_ACCESS, what, answer)
                    }
                    // 自动播放：由 media.autoplay.default 统一控制，这里只对个别站点级请求做一致回应
                    p == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                        answer(GeckoSitePerms.autoplay != 2)
                    p == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ->
                        answer(GeckoSitePerms.autoplay == 0)
                    // 较新的 nightly 才有的两项：用名字反射取常量，旧版本没有也不影响编译
                    p == PERM_LOCAL_DEVICE ->
                        tab.geckoRequestSimple(host, GeckoSitePerms.LOCAL_DEVICE, "访问你设备上的其他应用和服务", answer)
                    p == PERM_LOCAL_NETWORK ->
                        tab.geckoRequestSimple(host, GeckoSitePerms.LOCAL_NETWORK, "访问你本地网络中的设备", answer)
                    // 其余（VR 等）：本应用没有对应界面，一律拒绝
                    else -> answer(false)
                }
                return result
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                val wantCam = !video.isNullOrEmpty()
                val wantMic = !audio.isNullOrEmpty()
                tab.geckoRequestMedia(hostOf(uri), wantCam, wantMic) { ok ->
                    if (ok) {
                        callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                    } else {
                        callback.reject()
                    }
                }
            }
        }
    }

    /** 与 WebView 版一致：约 2.5cm 的拉动距离触发下拉刷新；同时记录最近一次按下时间（判定新窗口是否由点击触发） */
    private fun installTouchListener() {
        val pullThresholdPx = context.resources.displayMetrics.xdpi / 2.54f * 4f
        var downY = 0f
        var pulled = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchAt = SystemClock.uptimeMillis()
                    downY = event.y
                    pulled = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tab.url.isNotEmpty() && scrollY <= 0 && event.y > downY) {
                        pulled = event.y - downY
                        tab.geckoSetPull((pulled / pullThresholdPx).coerceIn(0f, 1f))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tab.url.isNotEmpty() && pulled > pullThresholdPx && scrollY <= 0) tab.geckoPullRefresh()
                    pulled = 0f
                    tab.geckoSetPull(0f)
                }
            }
            false
        }
    }

    /** 内容进程崩溃 / 被系统杀掉：重新打开会话并回到崩溃前的页面 */
    private fun recover() {
        val u = lastUrl
        view.post {
            runCatching {
                if (!session.isOpen) session.open(runtime)
                if (u.isNotBlank() && u != "about:blank") session.loadUri(u)
            }
        }
    }

    private fun spoolBody(response: WebResponse, uri: String, disposition: String?, mime: String?) {
        val body = response.body
        if (body == null) {
            tab.geckoBlobError("无法读取下载内容")
            return
        }
        val name = Downloader.fileNameFrom(uri, disposition)
        tab.geckoBlobStart(name)
        Thread {
            val file = File(context.cacheDir, "mafari_blob_${UUID.randomUUID()}.part")
            val ok = runCatching {
                body.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
            }.isSuccess && file.length() > 0L
            view.post {
                if (ok) {
                    tab.geckoBlobReady(Downloader.withExtension(name, mime), mime, file.absolutePath)
                } else {
                    runCatching { file.delete() }
                    tab.geckoBlobError("文件读取不完整或写入失败")
                }
            }
        }.start()
    }

    private fun headerOf(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private companion object {
        /** 响应体直接落盘的体积上限（带 Cookie 的小文件下载） */
        const val SPOOL_LIMIT = 64L * 1024 * 1024

        /** 会话状态字符串的长度上限：历史栈异常长的页面不存，回收 / 重启后退回「只按地址加载」 */
        const val MAX_STATE_CHARS = 400_000

        /** 这些协议由 Gecko 自己加载；其余（intent / weixin / alipays / mailto / tel …）交给系统 */
        val INTERNAL_SCHEMES = setOf(
            "http", "https", "about", "data", "file", "blob", "javascript",
            "resource", "view-source", "moz-extension", "ftp",
        )
    }
}

/**
 * 应用前后台切换时通知所有 Gecko 标签（弱引用，标签销毁后自动消失）。由 MainActivity 的 onPause / onStop / onResume 调用。
 */
object GeckoViewLifecycle {
    private val engines: MutableSet<GeckoTabEngine> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<GeckoTabEngine, Boolean>())

    fun register(engine: GeckoTabEngine) {
        synchronized(engines) { engines.add(engine) }
    }

    fun unregister(engine: GeckoTabEngine) {
        synchronized(engines) { engines.remove(engine) }
    }

    private fun snapshot(): List<GeckoTabEngine> = synchronized(engines) { engines.toList() }

    fun applyFrameRate() = snapshot().forEach { it.applyFrameRateHint() }

    fun onPause() = snapshot().forEach { it.captureFrameForBackground() }

    fun onStop() = snapshot().forEach { it.showFrozenFrame() }

    fun onResume() = snapshot().forEach { it.onAppForeground() }
}

/** GeckoView nightly 里较新的内容权限常量（跨站 Cookie / 设备应用 / 本地网络）（旧版本没有则为 -1，永远匹配不上） */
private fun permConst(name: String): Int =
    runCatching { GeckoSession.PermissionDelegate::class.java.getField(name).getInt(null) }.getOrDefault(-1)

private val PERM_STORAGE_ACCESS: Int by lazy { permConst("PERMISSION_STORAGE_ACCESS") }
private val PERM_LOCAL_DEVICE: Int by lazy { permConst("PERMISSION_LOCAL_DEVICE_ACCESS") }
private val PERM_LOCAL_NETWORK: Int by lazy { permConst("PERMISSION_LOCAL_NETWORK_ACCESS") }
