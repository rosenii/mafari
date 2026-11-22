package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.browser.EngineKind
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtensionStore
import dev.dsh.miuixbrowser.engine.gecko.GeckoSitePerms
import dev.dsh.miuixbrowser.net.WebViewProxy
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.glass.GlassDialog
import dev.dsh.miuixbrowser.data.HistoryStore
import dev.dsh.miuixbrowser.script.ExtensionStore
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedback


/** 把当前标签页的回调（长按 / 下载 / 文件选择 / 全屏 / 权限 / 登录）与隐私、主题、代理设置绑定到界面状态；
 *  从 BrowserScreen 拆出，逻辑未改。要在 rememberBrowserLaunchers 之后调用。 */
@Composable
internal fun BrowserUiState.BindTabCallbacks(
    settings: AppSettings,
    context: android.content.Context,
    tab: BrowserTab,
    launchers: BrowserLaunchers,
    scope: CoroutineScope,
    haptic: HapticFeedback,
    extensions: ExtensionStore,
    history: HistoryStore,
) {
    DisposableEffect(tab.id) {
        onDispose {
            tab.onLongPressLink = null
            tab.onShowFileChooser = { _, callback -> callback.onReceiveValue(null) }
            tab.onShowCustomView = null
            tab.onHideCustomView = null
            tab.onPermissionPrompt = null
            tab.requestAndroidPermissions = null
            tab.onBlobReady = null
            tab.onBlobError = null
            tab.onBlobStart = null
            fileChooserChoice = null
            customView = null
            // 未决的权限请求按拒绝收尾，否则 WebView 回调 / 重定向待决标记会一直悬空
            webPermPrompt?.decide?.invoke(false)
            webPermPrompt = null
            // 登录弹窗同理：按「不保存 / 不填」收尾，Gecko 的 prompt 才不会悬空
            tab.onLoginPrompt = null
            loginPrompt?.onDismiss?.invoke()
            loginPrompt = null
        }
    }

    LaunchedEffect(tab.id) {
        tab.onLoginPrompt = { req ->
            // 同一时刻只显示一个：被顶掉的按取消处理
            loginPrompt?.onDismiss?.invoke()
            loginPrompt = req
        }
        tab.onPermissionPrompt = { origin, what, permKey, decide ->
            // 同一时刻只显示一个弹窗：被顶掉的请求按拒绝处理，避免 WebView 回调悬空
            webPermPrompt?.decide?.invoke(false)
            webPermPrompt = WebPermPrompt(origin, what, permKey, decide)
        }
        tab.requestAndroidPermissions = { perms, done ->
            androidPermDone = done
            launchers.androidPermLauncher.launch(perms)
        }
    }

    LaunchedEffect(
        tab.id,
        tab.incognito,
        settings.cookieMode,
        settings.sendDnt,
        settings.webrtcDisabled,
        settings.clipboardPermission,
        settings.mediaAutoplay,
        settings.cameraPermission,
        settings.micPermission,
        settings.locationPermission,
        settings.notificationPermission,
        settings.redirectPermission,
        settings.externalAppPermission,
        settings.blockTrackingScripts,
        AdBlock.fpCanvas,
        AdBlock.fpWebgl,
        AdBlock.fpAudio,
        AdBlock.fpFonts,
        GeckoSitePerms.exceptions.toMap(), // 例外网站变化 → 重新生成 WebView 的隐私脚本
    ) {
        tab.applyPrivacy(
            cookieMode = settings.cookieMode,
            sendDnt = settings.sendDnt,
            webrtcDisabled = settings.webrtcDisabled,
            clipboardMode = settings.clipboardPermission,
            mediaMode = settings.mediaAutoplay,
            cameraMode = settings.cameraPermission,
            micMode = settings.micPermission,
            locationMode = settings.locationPermission,
            notificationMode = settings.notificationPermission,
            redirectMode = settings.redirectPermission,
            externalAppMode = settings.externalAppPermission,
            blockTrackers = settings.blockTrackingScripts,
            fingerprintProtect = AdBlock.fpCanvas || AdBlock.fpWebgl || AdBlock.fpAudio || AdBlock.fpFonts,
        )
    }

    // 显示模式深浅色 → WebView 背景 + 官方 algorithmic darkening（跟随设置/系统）
    val appDark = isAppDarkTheme()
    LaunchedEffect(tab.id, appDark) {
        tab.applyDisplayTheme(appDark)
    }

    // 代理（ProxyController）：设置变化或进入浏览页时应用到全部 WebView
    LaunchedEffect(
        settings.proxyEnabled,
        settings.proxyType,
        settings.proxyHost,
        settings.proxyPort,
        settings.browserEngine, // 从 Gecko 切到 WebView 后要把代理补应用到 WebView
    ) {
        // Gecko 默认且没建过 WebView：只把规则推给 Gecko，不去拿 WebView 的 ProxyController（会加载 Chromium）
        WebViewProxy.applyFrom(settings, webView = !settings.geckoMode || EngineSupport.webViewTouched)
    }

    // WebView 不会把滚动事件抛给 Compose，这里轻量轮询页面滚动位置：
    // 向下滚超过一小段 → 收起底栏；向上滚或回到顶部 → 展开。
    LaunchedEffect(tab.id, extensions.items.toList()) {
        tab.userScripts = extensions.items.toList()
        tab.onVisited = { url, title -> if (!tab.incognito) history.record(url, title) }
    }

    // 文件选择：非 capture 直接拉系统选择器；单一类型 capture 直接开相机；
    // 混合类型 capture 由下方 GlassDialog 让用户选。
    LaunchedEffect(fileChooserChoice) {
        val choice = fileChooserChoice ?: return@LaunchedEffect
        val params = choice.params
        val accepts = params.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
        val wantsImage = accepts.contains("image") || accepts.isBlank()
        val wantsVideo = accepts.contains("video")
        val capture = params.isCaptureEnabled
        when {
            !capture -> launchers.launchFilePicker(choice)
            wantsImage && !wantsVideo -> launchers.launchCamera(choice, video = false)
            wantsVideo && !wantsImage -> launchers.launchCamera(choice, video = true)
            // 混合 + capture：等用户在弹层里选
        }
    }

    LaunchedEffect(tab.id, settings.searchTemplate) {
        tab.searchTemplate = settings.searchTemplate
        dev.dsh.miuixbrowser.engine.webview.WebViewSpeculative.searchTemplate = settings.searchTemplate
        tab.onPullRefresh = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            tab.refresh()
        }
        tab.onUserScriptLink = { url -> pendingScript = url }
        tab.onExtensionLink = { link ->
            // 拓展只属于 Gecko：WebView 标签不识别、不安装拓展链接（无内核版里连 GeckoExtensionStore 都不碰）
            val store = if (EngineSupport.GECKO_BUNDLED && tab.isGecko) GeckoExtensionStore else null
            if (store != null && store.beginLinkInstall(link)) {
                android.widget.Toast.makeText(context, tr("正在安装拓展…"), android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    try {
                        store.install(context, link)
                            .onSuccess {
                                android.widget.Toast.makeText(context, tr("已添加 {0}", it.name), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                if (it.message != tr("已取消安装")) {
                                    android.widget.Toast.makeText(context, it.message ?: tr("安装失败"), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                    } finally {
                        store.endLinkInstall(link)
                    }
                }
            }
        }
        tab.onDownload = { url, ua, disposition, mime ->
            // blob/data 由 BrowserState 抽取后走 onBlobReady
            if (!url.startsWith("blob:", ignoreCase = true) && !url.startsWith("data:", ignoreCase = true)) {
                pendingDownload = DownloadRequest(
                    url = url,
                    userAgent = ua,
                    fileName = Downloader.fileNameFrom(url, disposition),
                    mime = mime,
                    referer = tab.url,
                    // 无痕标签不带 Cookie（WebView 共用 CookieManager、Gecko 扩展读不到私有 Cookie 罐，带了就是串号）
                    withCookies = !tab.incognito,
                    geckoCookies = tab.engineKind == EngineKind.GECKO,
                )
            }
        }
        tab.onGmDownload = { url, name, onResult ->
            pendingDownload?.onResult?.invoke(false, "superseded")
            pendingDownload = DownloadRequest(
                url = url,
                userAgent = if (tab.engineKind == EngineKind.WEBVIEW) {
                    runCatching { tab.webView.settings.userAgentString }.getOrNull()
                } else {
                    null
                },
                fileName = Downloader.fileNameFrom(url, if (name.isBlank()) null else "attachment; filename=\"$name\""),
                mime = null,
                referer = tab.url,
                withCookies = !tab.incognito,
                geckoCookies = tab.engineKind == EngineKind.GECKO,
                onResult = onResult,
            )
        }
        tab.onBlobStart = {
            android.widget.Toast.makeText(context, tr("正在读取文件…"), android.widget.Toast.LENGTH_SHORT).show()
        }
        tab.onBlobReady = { dataUri, fileName, mime, tempPath ->
            // 上一个还没确认的 blob 被新的顶替：先删它的缓存文件，避免泄漏
            pendingDownload?.tempPath?.let { p -> runCatching { java.io.File(p).delete() } }
            pendingDownload = DownloadRequest(
                url = "",
                userAgent = null,
                fileName = fileName,
                mime = mime,
                dataUri = dataUri,
                tempPath = tempPath,
            )
        }
        tab.onBlobError = { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        tab.onShowFileChooser = { params, callback ->
            fileChooserChoice = FileChooserChoice(params, callback)
        }
        tab.onShowCustomView = { view, _ ->
            customView = view
            // 横屏在 BrowserTab.lockLandscape 已处理；这里确保容器铺满
        }
        tab.onHideCustomView = {
            customView = null
        }
    }
}
