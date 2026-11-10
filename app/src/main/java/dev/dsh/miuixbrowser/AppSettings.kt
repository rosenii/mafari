package dev.dsh.miuixbrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 下载页可选的线程数 */
val DOWNLOAD_THREAD_OPTIONS = listOf(4, 8, 16, 32, 64)

/** 下载页「多线程阈值」档位（MB）：10 / 50 / 100 / 200 / 500 / 1G */
val DOWNLOAD_MIN_MB_OPTIONS = listOf(10, 50, 100, 200, 500, 1024)

/** 阈值档位的显示文字 */
fun downloadThresholdLabel(mb: Int): String = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

/** 浏览器内核 */
const val ENGINE_WEBVIEW = 0
const val ENGINE_GECKO = 1

/** 模糊效果模式 */
const val BLUR_MIUIX = 0
const val BLUR_LIQUID = 1
const val BLUR_OFF = 2

/** 首页内容块 */
const val HOME_FAVORITES = 0
const val HOME_PRIVACY = 1
const val HOME_RECENT = 2
const val HOME_DOWNLOADS = 3

/** 解析首页内容块顺序；缺失 / 非法项补齐，默认 收藏 → 隐私报告 → 最近浏览 → 下载项。 */
fun parseHomeOrder(raw: String?): List<Int> {
    val all = listOf(HOME_FAVORITES, HOME_PRIVACY, HOME_RECENT, HOME_DOWNLOADS)
    val parsed = raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        .filter { it in all }.distinct()
    return parsed + all.filter { it !in parsed }
}

/** 标签页创建时直接读 SharedPreferences（不必构造整个 AppSettings） */
fun readBrowserEngine(context: Context): Int =
    context.getSharedPreferences("mafari_settings", Context.MODE_PRIVATE)
        .getInt("browser_engine", ENGINE_WEBVIEW)

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("mafari_settings", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    /** 每次设置壁纸 +1，用来让模糊缓存在同 uri 重选时也能重新生成。 */
    var wallpaperVersion by mutableStateOf(0)
        private set

    /** 模糊效果模式：0 = miuix-glass，1 = liquid glass（Kyant0/AndroidLiquidGlass），2 = 关闭模糊。旧版布尔开关会迁移过来。 */
    var blurMode by mutableStateOf(
        if (prefs.contains(KEY_BLUR_MODE)) {
            prefs.getInt(KEY_BLUR_MODE, BLUR_MIUIX).coerceIn(BLUR_MIUIX, BLUR_OFF)
        } else if (prefs.getBoolean(KEY_BLUR, true)) {
            BLUR_MIUIX
        } else {
            BLUR_OFF
        },
    )
        private set

    /** 是否有任何模糊（miuix-glass 或 liquid glass） */
    val blurEnabled: Boolean get() = blurMode != BLUR_OFF

    /** 是否使用 liquid glass 效果 */
    val liquidGlass: Boolean get() = blurMode == BLUR_LIQUID

    /** 首页内容显示：收藏 / 隐私报告默认显示，最近浏览 / 下载项默认隐藏。 */
    var homeShowFavorites by mutableStateOf(prefs.getBoolean(KEY_HOME_FAV, true))
        private set
    var homeShowPrivacy by mutableStateOf(prefs.getBoolean(KEY_HOME_PRIVACY, true))
        private set
    var homeShowRecent by mutableStateOf(prefs.getBoolean(KEY_HOME_RECENT, false))
        private set
    var homeShowDownloads by mutableStateOf(prefs.getBoolean(KEY_HOME_DOWNLOADS, false))
        private set

    /** 首页内容块的显示顺序（含隐藏项的位置），长按拖动后持久化。 */
    var homeOrder by mutableStateOf(parseHomeOrder(prefs.getString(KEY_HOME_ORDER, null)))
        private set

    fun updateHomeOrder(order: List<Int>) {
        val fixed = parseHomeOrder(order.joinToString(","))
        homeOrder = fixed
        prefs.edit().putString(KEY_HOME_ORDER, fixed.joinToString(",")).apply()
    }

    fun isHomeShown(kind: Int): Boolean = when (kind) {
        HOME_FAVORITES -> homeShowFavorites
        HOME_PRIVACY -> homeShowPrivacy
        HOME_RECENT -> homeShowRecent
        else -> homeShowDownloads
    }

    fun updateHomeShow(kind: Int, show: Boolean) {
        val e = prefs.edit()
        when (kind) {
            HOME_FAVORITES -> { homeShowFavorites = show; e.putBoolean(KEY_HOME_FAV, show) }
            HOME_PRIVACY -> { homeShowPrivacy = show; e.putBoolean(KEY_HOME_PRIVACY, show) }
            HOME_RECENT -> { homeShowRecent = show; e.putBoolean(KEY_HOME_RECENT, show) }
            HOME_DOWNLOADS -> { homeShowDownloads = show; e.putBoolean(KEY_HOME_DOWNLOADS, show) }
        }
        e.apply()
    }

    /** 用户点了“忽略此版本”的版本号；只有出现比它更新的版本才会再次提示 */
    var ignoredUpdate by mutableStateOf(prefs.getString(KEY_IGNORED_UPDATE, "") ?: "")
        private set

    fun updateIgnoredUpdate(version: String) {
        ignoredUpdate = version
        prefs.edit().putString(KEY_IGNORED_UPDATE, version).apply()
    }

    /** 更新分支：0=stable 1=dev 2=gecko（gecko 分支的预发布）。未设置过时跟随当前安装包 */
    var updateChannel by mutableStateOf(
        if (prefs.contains(KEY_UPDATE_CHANNEL)) {
            prefs.getInt(KEY_UPDATE_CHANNEL, 0).coerceIn(0, 2)
        } else if (BuildConfig.GECKO_BUILD > 0) {
            2
        } else if (BuildConfig.VERSION_NAME.contains("dev", ignoreCase = true)) {
            1
        } else {
            0
        },
    )
        private set

    fun updateUpdateChannel(channel: Int) {
        updateChannel = channel.coerceIn(0, 2)
        prefs.edit().putInt(KEY_UPDATE_CHANNEL, updateChannel).apply()
    }

    /**
     * 浏览器内核：0 = 系统 WebView，1 = GeckoView（Nightly）。
     * 只影响「之后新建」的标签页；已打开的标签保持创建时的内核，直到关闭。
     */
    var browserEngine by mutableStateOf(prefs.getInt(KEY_ENGINE, ENGINE_WEBVIEW).coerceIn(ENGINE_WEBVIEW, ENGINE_GECKO))
        private set

    /** 当前是否处于 Gecko 模式：选了 Gecko 且本机架构支持（不支持时新标签会自动退回 WebView） */
    val geckoMode: Boolean
        get() = browserEngine == ENGINE_GECKO && GeckoRuntimeHolder.supported()

    fun updateBrowserEngine(engine: Int) {
        browserEngine = engine.coerceIn(ENGINE_WEBVIEW, ENGINE_GECKO)
        prefs.edit().putInt(KEY_ENGINE, browserEngine).apply()
    }

    /** 切到 Gecko 前的状态快照：去广告 / DNT / 禁 WebRTC / 指纹保护（网页增强与脚本总开关两种内核通用，不动） */
    fun suspendForGecko() {
        if (!prefs.getBoolean("gk_saved", false)) {
            val fpOn = AdBlock.fpCanvas || AdBlock.fpWebgl || AdBlock.fpAudio || AdBlock.fpFonts
            prefs.edit().putBoolean("gk_saved", true)
                .putBoolean("gk_adblock", AdBlock.enabled).putBoolean("gk_dnt", sendDnt)
                .putBoolean("gk_webrtc", webrtcDisabled).putBoolean("gk_fp", fpOn).apply()
        }
        AdBlock.updateEnabled(false)
        updateSendDnt(false)
        updateWebrtcDisabled(false)
        AdBlock.setFingerprintMaster(false)
    }

    /** 切回 WebView：恢复为切换前的状态（没有快照则全部开启） */
    fun resumeFromGecko() {
        val has = prefs.getBoolean("gk_saved", false)
        fun b(k: String) = !has || prefs.getBoolean(k, true)
        AdBlock.updateEnabled(b("gk_adblock"))
        updateSendDnt(b("gk_dnt"))
        updateWebrtcDisabled(b("gk_webrtc"))
        AdBlock.setFingerprintMaster(b("gk_fp"))
        prefs.edit().remove("gk_saved").apply()
    }

    /** 下载线程数：4/8/16/32/64 */
    var downloadThreads by mutableStateOf(prefs.getInt(KEY_DL_THREADS, 4).let { if (it in DOWNLOAD_THREAD_OPTIONS) it else 4 })
        private set

    fun updateDownloadThreads(threads: Int) {
        downloadThreads = if (threads in DOWNLOAD_THREAD_OPTIONS) threads else 4
        Downloader.segments = downloadThreads
        prefs.edit().putInt(KEY_DL_THREADS, downloadThreads).apply()
    }

    /** 多线程阈值：文件超过该大小（MB）才使用多线程下载 */
    var downloadMultiThreadMinMb by mutableStateOf(
        prefs.getInt(KEY_DL_MIN_MB, 10).let { if (it in DOWNLOAD_MIN_MB_OPTIONS) it else 10 },
    )
        private set

    fun updateDownloadMultiThreadMinMb(mb: Int) {
        downloadMultiThreadMinMb = if (mb in DOWNLOAD_MIN_MB_OPTIONS) mb else 10
        Downloader.segmentMinBytes = downloadMultiThreadMinMb * 1024L * 1024L
        prefs.edit().putInt(KEY_DL_MIN_MB, downloadMultiThreadMinMb).apply()
    }

    init {
        // 启动时把已保存的下载配置推给下载器
        Downloader.segments = downloadThreads
        Downloader.segmentMinBytes = downloadMultiThreadMinMb * 1024L * 1024L
    }

    /** 应用图标：0=默认 1=多彩 2=立体 3=深色（对应 AppIconManager.ICONS） */
    var appIcon by mutableStateOf(prefs.getInt(KEY_APP_ICON, 0).coerceIn(0, 3))
        private set

    fun updateAppIcon(index: Int) {
        appIcon = index.coerceIn(0, 3)
        prefs.edit().putInt(KEY_APP_ICON, appIcon).apply()
    }

    /** 外部链接打开方式：0=应用内打开 1=交给其他浏览器（询问） */
    var linkOpenMode by mutableStateOf(prefs.getInt(KEY_LINK_MODE, 0))
        private set

    fun updateLinkOpenMode(mode: Int) {
        linkOpenMode = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LINK_MODE, linkOpenMode).apply()
    }

    /** 地址栏显示：0=网站标题 1=主域名 2=完整域名 */
    var addressDisplay by mutableStateOf(prefs.getInt(KEY_ADDR, 2))
        private set

    /** 标签面板样式：0=文字列表 1=卡片 */
    var tabsStyle by mutableStateOf(prefs.getInt(KEY_TABS, 0))
        private set

    fun updateAddressDisplay(mode: Int) {
        addressDisplay = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_ADDR, addressDisplay).apply()
    }

    fun updateTabsStyle(style: Int) {
        tabsStyle = style.coerceIn(0, 1)
        prefs.edit().putInt(KEY_TABS, tabsStyle).apply()
    }

    /** 搜索引擎：预设索引 + 自定义模板（%s 占位） */
    var searchIndex by mutableStateOf(prefs.getInt(KEY_SEARCH, 0))
        private set

    var customSearch by mutableStateOf(prefs.getString(KEY_SEARCH_CUSTOM, "").orEmpty())
        private set

    val searchTemplate: String
        get() = when {
            searchIndex < 0 -> customSearch.ifBlank { SEARCH_ENGINES[0].template }
            customSearch.isNotBlank() && searchIndex >= SEARCH_ENGINES.size -> customSearch
            else -> SEARCH_ENGINES.getOrNull(searchIndex)?.template ?: SEARCH_ENGINES[0].template
        }

    fun updateSearch(index: Int) {
        searchIndex = index
        prefs.edit().putInt(KEY_SEARCH, index).apply()
    }

    fun updateCustomSearch(template: String) {
        customSearch = template.trim()
        prefs.edit().putString(KEY_SEARCH_CUSTOM, customSearch).apply()
        if (customSearch.isNotBlank()) updateSearch(SEARCH_ENGINES.size)
    }

    /** 0=跟随系统 1=日间 2=夜间 */
    var themeMode by mutableStateOf(prefs.getInt(KEY_THEME, 0))
        private set

    /** 主页壁纸（content:// 或空） */
    var wallpaper by mutableStateOf(prefs.getString(KEY_WALL, "").orEmpty())
        private set

    /** 主页壁纸模糊 */
    var wallpaperBlur by mutableStateOf(prefs.getBoolean(KEY_WALL_BLUR, false))
        private set

    fun updateWallpaperBlur(enabled: Boolean) {
        wallpaperBlur = enabled
        prefs.edit().putBoolean(KEY_WALL_BLUR, enabled).apply()
    }

    /** 空串表示跟随系统 WebView 默认 UA */
    var userAgent by mutableStateOf(prefs.getString(KEY_UA, "").orEmpty())
        private set

    fun updateThemeMode(mode: Int) {
        themeMode = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_THEME, themeMode).apply()
    }

    fun updateWallpaper(uri: String) {
        wallpaper = uri
        // 换壁纸后旧的模糊缓存作废（同一个 uri 内容也可能变）
        WallpaperBlurCache.clear(appContext)
        wallpaperVersion++
        prefs.edit().putString(KEY_WALL, uri).apply()
    }

    fun updateBlurMode(mode: Int) {
        blurMode = mode.coerceIn(BLUR_MIUIX, BLUR_OFF)
        prefs.edit().putInt(KEY_BLUR_MODE, blurMode).apply()
    }

    /** 网页 window.open / target=_blank 打开的同站链接是否新开标签页（默认关：在当前标签打开） */
    var sameSiteNewTab by mutableStateOf(prefs.getBoolean(KEY_SAME_SITE_TAB, false))
        private set

    fun updateSameSiteNewTab(enabled: Boolean) {
        sameSiteNewTab = enabled
        prefs.edit().putBoolean(KEY_SAME_SITE_TAB, enabled).apply()
    }

    /** 网页增强：油猴脚本、去广告规则、长按标记广告 */
    var pageEnhancements by mutableStateOf(prefs.getBoolean(KEY_ENHANCE, true))
        private set

    fun updatePageEnhancements(enabled: Boolean) {
        pageEnhancements = enabled
        prefs.edit().putBoolean(KEY_ENHANCE, enabled).apply()
    }

    /** 启用范围：油猴脚本是否在无痕浏览 / 轻应用（桌面快捷方式）中运行 */
    var scriptsInIncognito by mutableStateOf(prefs.getBoolean(KEY_SCRIPTS_INCOGNITO, true))
        private set

    fun updateScriptsInIncognito(enabled: Boolean) {
        scriptsInIncognito = enabled
        prefs.edit().putBoolean(KEY_SCRIPTS_INCOGNITO, enabled).apply()
    }

    var scriptsInWebApp by mutableStateOf(prefs.getBoolean(KEY_SCRIPTS_WEBAPP, true))
        private set

    fun updateScriptsInWebApp(enabled: Boolean) {
        scriptsInWebApp = enabled
        prefs.edit().putBoolean(KEY_SCRIPTS_WEBAPP, enabled).apply()
    }

    /** 启用范围：去广告（请求拦截 + 元素隐藏）是否在无痕浏览 / 轻应用中生效 */
    var adblockInIncognito by mutableStateOf(prefs.getBoolean(KEY_ADBLOCK_INCOGNITO, true))
        private set

    fun updateAdblockInIncognito(enabled: Boolean) {
        adblockInIncognito = enabled
        prefs.edit().putBoolean(KEY_ADBLOCK_INCOGNITO, enabled).apply()
    }

    var adblockInWebApp by mutableStateOf(prefs.getBoolean(KEY_ADBLOCK_WEBAPP, true))
        private set

    fun updateAdblockInWebApp(enabled: Boolean) {
        adblockInWebApp = enabled
        prefs.edit().putBoolean(KEY_ADBLOCK_WEBAPP, enabled).apply()
    }

    /** 脚本一键启停：只影响油猴脚本，不影响去广告 / 长按标记 */
    var scriptsEnabled by mutableStateOf(prefs.getBoolean(KEY_SCRIPTS, true))
        private set

    fun updateScriptsEnabled(enabled: Boolean) {
        scriptsEnabled = enabled
        prefs.edit().putBoolean(KEY_SCRIPTS, enabled).apply()
    }

    fun updateUserAgent(value: String) {
        userAgent = value.trim()
        prefs.edit().putString(KEY_UA, userAgent).apply()
    }

    /** 发送 Do Not Track 请求头 */
    var sendDnt by mutableStateOf(prefs.getBoolean(KEY_DNT, false))
        private set

    fun updateSendDnt(enabled: Boolean) {
        sendDnt = enabled
        prefs.edit().putBoolean(KEY_DNT, enabled).apply()
    }

    /** 禁用 WebRTC（防 IP 泄露） */
    var webrtcDisabled by mutableStateOf(prefs.getBoolean(KEY_WEBRTC, false))
        private set

    fun updateWebrtcDisabled(enabled: Boolean) {
        webrtcDisabled = enabled
        prefs.edit().putBoolean(KEY_WEBRTC, enabled).apply()
    }

    /**
     * Cookie 接受策略（两个内核共用）：0 允许全部 / 1 拒绝跟踪者 / 2 拒绝第三方 / 3 拒绝全部。
     * 旧版「第三方 Cookie」开关（0 允许 / 1 拒绝）迁移为 0 / 2。
     */
    var cookieMode by mutableStateOf(
        if (prefs.contains(KEY_COOKIE_MODE)) prefs.getInt(KEY_COOKIE_MODE, 0).coerceIn(0, 3)
        else if (prefs.getInt(KEY_3P_COOKIE, 0) == 1) 2 else 0,
    )
        private set

    fun updateCookieMode(mode: Int) {
        cookieMode = mode.coerceIn(0, 3)
        prefs.edit().putInt(KEY_COOKIE_MODE, cookieMode).apply()
    }


    /** 启动时清理：0 不清理 / 1 清除 Cookie / 2 清除 Cookie 与网站存储 */
    var cookieClearOnLaunch by mutableStateOf(prefs.getInt(KEY_COOKIE_CLEAR, 0).coerceIn(0, 2))
        private set

    fun updateCookieClearOnLaunch(mode: Int) {
        cookieClearOnLaunch = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_COOKIE_CLEAR, cookieClearOnLaunch).apply()
    }

    /** 网页权限：摄像头 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var cameraPermission by mutableStateOf(prefs.getInt(KEY_CAM, 0))
        private set

    fun updateCameraPermission(mode: Int) {
        cameraPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_CAM, cameraPermission).apply()
    }

    /** 网页权限：麦克风 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var micPermission by mutableStateOf(prefs.getInt(KEY_MIC, 0))
        private set

    fun updateMicPermission(mode: Int) {
        micPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_MIC, micPermission).apply()
    }

    /** 网页权限：定位 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var locationPermission by mutableStateOf(prefs.getInt(KEY_LOC, 0))
        private set

    fun updateLocationPermission(mode: Int) {
        locationPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LOC, locationPermission).apply()
    }

    /** 网页权限：剪贴板读写 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var clipboardPermission by mutableStateOf(prefs.getInt(KEY_CLIP, 0))
        private set

    fun updateClipboardPermission(mode: Int) {
        clipboardPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_CLIP, clipboardPermission).apply()
    }

    /** 网页权限：网站媒体发声/自动播放 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var mediaAutoplay by mutableStateOf(prefs.getInt(KEY_MEDIA, 0))
        private set

    fun updateMediaAutoplay(mode: Int) {
        mediaAutoplay = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_MEDIA, mediaAutoplay).apply()
    }

    /** 网页权限：网站通知 0=每次使用时询问 1=总是允许 2=总是拒绝 */
    var notificationPermission by mutableStateOf(prefs.getInt(KEY_NOTIFY, 0))
        private set

    fun updateNotificationPermission(mode: Int) {
        notificationPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_NOTIFY, notificationPermission).apply()
    }

    /**
     * 网页权限：网页重定向 0=每次使用时询问 1=总是允许 2=总是拒绝
     * 指网页在没有用户操作的情况下自动跳转到其他站点（JS / meta refresh），以及自动唤起其他应用。
     */
    var redirectPermission by mutableStateOf(prefs.getInt(KEY_REDIRECT, 0))
        private set

    fun updateRedirectPermission(mode: Int) {
        redirectPermission = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_REDIRECT, redirectPermission).apply()
    }

    /** 启用「请勿追踪」类隐私增强：合并 DNT + 禁第三方跟踪脚本提示 */
    var blockTrackingScripts by mutableStateOf(prefs.getBoolean(KEY_TRACK, true))
        private set

    fun updateBlockTrackingScripts(enabled: Boolean) {
        blockTrackingScripts = enabled
        prefs.edit().putBoolean(KEY_TRACK, blockTrackingScripts).apply()
    }

    /** 代理开关（WebView ProxyController，进程内全部标签） */
    var proxyEnabled by mutableStateOf(prefs.getBoolean(KEY_PROXY_ON, false))
        private set

    /** 0 = HTTP，1 = SOCKS5 */
    var proxyType by mutableStateOf(prefs.getInt(KEY_PROXY_TYPE, 0).coerceIn(0, 1))
        private set

    var proxyHost by mutableStateOf(prefs.getString(KEY_PROXY_HOST, "").orEmpty())
        private set

    var proxyPort by mutableStateOf(prefs.getInt(KEY_PROXY_PORT, 0))
        private set

    /** 代理认证（HTTP 挑战时提交；SOCKS5 官方 ProxyConfig 不支持，由本机 Socks5Relay 代为向上游认证） */
    var proxyAuth by mutableStateOf(prefs.getBoolean(KEY_PROXY_AUTH, false))
        private set

    var proxyUser by mutableStateOf(prefs.getString(KEY_PROXY_USER, "").orEmpty())
        private set

    var proxyPass by mutableStateOf(prefs.getString(KEY_PROXY_PASS, "").orEmpty())
        private set

    fun updateProxy(
        enabled: Boolean,
        type: Int,
        host: String,
        port: Int,
        auth: Boolean = proxyAuth,
        user: String = proxyUser,
        pass: String = proxyPass,
    ) {
        proxyEnabled = enabled
        proxyType = type.coerceIn(0, 1)
        proxyHost = host.trim()
        proxyPort = port.coerceIn(0, 65535)
        proxyAuth = auth
        proxyUser = user
        proxyPass = pass
        prefs.edit()
            .putBoolean(KEY_PROXY_ON, proxyEnabled)
            .putInt(KEY_PROXY_TYPE, proxyType)
            .putString(KEY_PROXY_HOST, proxyHost)
            .putInt(KEY_PROXY_PORT, proxyPort)
            .putBoolean(KEY_PROXY_AUTH, proxyAuth)
            .putString(KEY_PROXY_USER, proxyUser)
            .putString(KEY_PROXY_PASS, proxyPass)
            .apply()
        WebViewProxy.applyFrom(this)
    }

    private companion object {
        const val KEY_BLUR = "blur"
        const val KEY_BLUR_MODE = "blur_mode"
        const val KEY_HOME_FAV = "home_show_favorites"
        const val KEY_HOME_PRIVACY = "home_show_privacy"
        const val KEY_HOME_RECENT = "home_show_recent"
        const val KEY_HOME_DOWNLOADS = "home_show_downloads"
        const val KEY_HOME_ORDER = "home_order"
        const val KEY_SAME_SITE_TAB = "same_site_new_tab"
        const val KEY_APP_ICON = "app_icon"
        const val KEY_IGNORED_UPDATE = "ignored_update"
        const val KEY_UPDATE_CHANNEL = "update_channel"
        const val KEY_ENGINE = "browser_engine"
        const val KEY_DL_THREADS = "download_threads"
        const val KEY_DL_MIN_MB = "download_multithread_min_mb"
        const val KEY_UA = "ua"
        const val KEY_THEME = "theme_mode"
        const val KEY_SEARCH = "search_index"
        const val KEY_ADDR = "address_display"
        const val KEY_LINK_MODE = "link_open_mode"
        const val KEY_TABS = "tabs_style"
        const val KEY_SEARCH_CUSTOM = "search_custom"
        const val KEY_WALL = "wallpaper"
        const val KEY_WALL_BLUR = "wallpaper_blur"
        const val KEY_ENHANCE = "page_enhancements"
        const val KEY_SCRIPTS = "scripts_enabled"
        const val KEY_SCRIPTS_INCOGNITO = "scripts_in_incognito"
        const val KEY_SCRIPTS_WEBAPP = "scripts_in_webapp"
        const val KEY_ADBLOCK_INCOGNITO = "adblock_in_incognito"
        const val KEY_ADBLOCK_WEBAPP = "adblock_in_webapp"
        const val KEY_DNT = "send_dnt"
        const val KEY_WEBRTC = "webrtc_disabled"
        const val KEY_3P_COOKIE = "third_party_cookie"
        const val KEY_COOKIE_MODE = "cookie_mode"
        const val KEY_COOKIE_CLEAR = "cookie_clear_on_launch"
        const val KEY_CAM = "perm_camera"
        const val KEY_MIC = "perm_mic"
        const val KEY_LOC = "perm_location"
        const val KEY_CLIP = "perm_clipboard"
        const val KEY_MEDIA = "perm_media_autoplay"
        const val KEY_NOTIFY = "perm_notification"
        const val KEY_REDIRECT = "perm_redirect"
        const val KEY_TRACK = "block_tracking"
        const val KEY_PROXY_ON = "proxy_enabled"
        const val KEY_PROXY_TYPE = "proxy_type"
        const val KEY_PROXY_HOST = "proxy_host"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_PROXY_AUTH = "proxy_auth"
        const val KEY_PROXY_USER = "proxy_user"
        const val KEY_PROXY_PASS = "proxy_pass"
    }
}

data class SearchEngine(val name: String, val template: String, val iconUrl: String = "")

val SEARCH_ENGINES = listOf(
    SearchEngine("百度", "https://www.baidu.com/s?wd=%s", "https://www.baidu.com/favicon.ico"),
    SearchEngine("必应", "https://www.bing.com/search?q=%s", "https://www.bing.com/favicon.ico"),
    SearchEngine("Google", "https://www.google.com/search?q=%s", "https://www.google.com/favicon.ico"),
    SearchEngine("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/favicon.ico"),
)

data class UaPreset(val name: String, val value: String, val iconUrls: List<String> = emptyList())

/** 常用 UA 预设 */
val UA_PRESETS = listOf(
    UaPreset("跟随系统", ""),
    UaPreset(
        "Android Chrome",
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        listOf(
            "https://www.android.com/favicon.ico",
            "https://www.google.com/s2/favicons?sz=128&domain_url=https://www.android.com/",
            "https://icons.duckduckgo.com/ip3/www.android.com.ico",
        ),
    ),
    UaPreset(
        "桌面 Chrome",
        DESKTOP_UA,
        listOf(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e1/Google_Chrome_icon_%28February_2022%29.svg/128px-Google_Chrome_icon_%28February_2022%29.svg.png",
            "https://www.google.com/chrome/static/images/favicons/favicon-96x96.png",
        ),
    ),
    UaPreset(
        "iPhone Safari",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
        listOf(
            "https://www.apple.com/favicon.ico",
            "https://www.google.com/s2/favicons?sz=128&domain_url=https://www.apple.com/",
            "https://icons.duckduckgo.com/ip3/www.apple.com.ico",
        ),
    ),
)

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
