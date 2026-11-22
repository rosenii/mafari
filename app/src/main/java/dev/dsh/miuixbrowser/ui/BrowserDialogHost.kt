package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.appContainer

import dev.dsh.miuixbrowser.browser.UpdateSheet
import dev.dsh.miuixbrowser.browser.HOME_QUICK_COLS
import dev.dsh.miuixbrowser.browser.quickMenuCandidates
import dev.dsh.miuixbrowser.browser.AppRoute
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.DefaultBrowser
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoNetLog
import dev.dsh.miuixbrowser.engine.gecko.GeckoSitePerms
import android.app.Activity
import android.os.Build
import android.content.Intent
import android.widget.FrameLayout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex

import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.BoxScope
import dev.dsh.miuixbrowser.data.FavoritesStore
import dev.dsh.miuixbrowser.script.ExtensionStore

/** 浏览页的各类对话框 / 弹窗：默认浏览器、下载确认、文件选择类型、权限、登录、脚本导入、分享、查找、书签编辑、分组等。 */
@Composable
internal fun BrowserUiState.BrowserDialogHost(
    settings: AppSettings,
    context: android.content.Context,
    state: BrowserState,
    favorites: FavoritesStore,
    homeGroups: List<HomeGroup>,
    scope: kotlinx.coroutines.CoroutineScope,
    liquidBackdrop: com.kyant.backdrop.Backdrop,
    extensions: ExtensionStore,
    defaultBrowserLauncher: androidx.activity.compose.ManagedActivityResultLauncher<android.content.Intent, androidx.activity.result.ActivityResult>,
    tab: BrowserTab,
    glassAvailable: Boolean,
    glassBackdrop: LayerBackdrop,
    pushPage: (AppRoute) -> Unit,
    openInBrowser: (String) -> Unit,
    launchers: BrowserLaunchers,
    /** LoginPromptDialog / GroupBookmarkPopup 是 BoxScope 扩展，要用调用处那个全屏 Box 的作用域 */
    box: BoxScope,
) {
BrowserChunk {
            AppGlassDialog(
                show = showDefaultBrowserPrompt,
                title = tr("设为默认浏览器"),
                summary = tr("设为系统默认浏览器后，其他应用里的网页链接会直接用 Mafari 打开。之后也可以在 设置 → 高级 里设置。"),
                backdrop = if (glassAvailable && settings.blurEnabled) glassBackdrop else null,
                onDismissRequest = {
                    showDefaultBrowserPrompt = false
                    settings.markDefaultBrowserPrompted()
                },
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppDialogButton(
                        onClick = {
                            showDefaultBrowserPrompt = false
                            settings.markDefaultBrowserPrompted()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            showDefaultBrowserPrompt = false
                            settings.markDefaultBrowserPrompted()
                            runCatching { defaultBrowserLauncher.launch(DefaultBrowser.requestIntent(context)) }
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Primary,
                    ) {
                        Text(text = tr("设为默认"), style = MiuixTheme.textStyles.button)
                    }
                }
            }

            UpdateSheet(
                show = updateRelease != null,
                release = updateRelease ?: lastUpdateRelease,
                backdrop = if (glassAvailable && settings.blurEnabled) glassBackdrop else null,
                origin = "launch",
                onDismiss = { updateRelease = null },
                onIgnore = {
                    settings.updateIgnoredUpdate(it.version)
                    updateRelease = null
                },
                openInApp = { openInBrowser(it) },
            )
}

        val downloadRequest = pendingDownload
BrowserChunk {
            AppGlassDialog(
                show = downloadRequest != null,
                title = tr("下载文件"),
                summary = downloadRequest?.let {
                    it.fileName + "\n" +
                        when {
                            it.tempPath != null -> tr("页面内文件（blob）")
                            it.dataUri != null -> tr("页面内文件（data）")
                            else -> tr("保存到 /sdcard/Download/mafari")
                        }
                },
                backdrop = if (glassAvailable) glassBackdrop else null,
                onDismissRequest = {
                    pendingDownload?.tempPath?.let { p -> runCatching { java.io.File(p).delete() } }
                    pendingDownload?.onResult?.invoke(false, "cancelled")
                    pendingDownload = null
                },
            ) {
                if (downloadRequest != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppDialogButton(
                                onClick = {
                                    downloadRequest.tempPath?.let { p -> runCatching { java.io.File(p).delete() } }
                                    downloadRequest.onResult?.invoke(false, "cancelled")
                                    pendingDownload = null
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                            }
                            AppDialogButton(
                                onClick = {
                                    val req = downloadRequest
                                    pendingDownload = null
                                    scope.launch {
                                        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            when {
                                                req.tempPath != null -> {
                                                    val f = java.io.File(req.tempPath)
                                                    val ok = f.exists() && Downloader.saveFile(
                                                        context,
                                                        f,
                                                        req.fileName,
                                                        req.mime,
                                                    )
                                                    runCatching { f.delete() }
                                                    ok
                                                }
                                                req.dataUri != null ->
                                                    Downloader.saveDataUri(context, req.dataUri, req.fileName)
                                                else -> {
                                                    Downloader.start(
                                                        context = context,
                                                        url = req.url,
                                                        userAgent = req.userAgent,
                                                        fileName = req.fileName,
                                                        mimeType = req.mime,
                                                        onComplete = req.onResult,
                                                        referer = req.referer,
                                                        withCookies = req.withCookies,
                                                        geckoCookies = req.geckoCookies,
                                                    )
                                                    true
                                                }
                                            }
                                        }
                                        if (req.tempPath != null || req.dataUri != null) {
                                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (saved) tr("已保存到 Download/mafari") else tr("保存失败"),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                role = GlassDialogButtonRole.Primary,
                            ) {
                                Text(text = tr("下载"), style = MiuixTheme.textStyles.button)
                            }
                        }
                    }
                }
            }
}

        // capture 混合类型：让用户选拍照 / 录像 / 文件
        // 注意：launchCamera / filePickerLauncher 依赖 fileChooserChoice 里的 callback，
        // 弹层里不要先置 null，由 launcher 回调时再清。
        val captureChoice = fileChooserChoice
BrowserChunk {
            AppGlassDialog(
                show = captureChoice != null && captureChoice.params.isCaptureEnabled &&
                    run {
                        val a = captureChoice.params.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
                        a.contains("image") && a.contains("video")
                    },
                title = tr("选择来源"),
                summary = tr("网页需要图片或视频"),
                backdrop = if (glassAvailable) glassBackdrop else null,
                onDismissRequest = {
                    captureChoice?.callback?.onReceiveValue(null)
                    fileChooserChoice = null
                },
            ) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppDialogButton(
                        onClick = {
                            if (captureChoice != null) launchers.launchCamera(captureChoice, video = false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        role = GlassDialogButtonRole.Primary,
                    ) {
                        Text(text = tr("拍照"), style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            if (captureChoice != null) launchers.launchCamera(captureChoice, video = true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = tr("录像"), style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            if (captureChoice != null) launchers.launchFilePicker(captureChoice)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = tr("从文件选择"), style = MiuixTheme.textStyles.button)
                    }
                }
            }
}

        // HTML5 视频全屏：沉浸播放，完全隐藏状态栏和底部导航条（边缘上滑可临时唤出），退出全屏时恢复
        val videoFullscreen = customView != null
BrowserChunk {
            DisposableEffect(videoFullscreen) {
                val window = (context as? Activity)?.window
                val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
                var oldCutout = -1
                if (videoFullscreen && window != null && controller != null) {
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        oldCutout = window.attributes.layoutInDisplayCutoutMode
                        val attrs = window.attributes
                        attrs.layoutInDisplayCutoutMode =
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        window.attributes = attrs
                    }
                }
                onDispose {
                    if (videoFullscreen && window != null && controller != null) {
                        controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                        controller.systemBarsBehavior =
                            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                        if (oldCutout >= 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            val attrs = window.attributes
                            attrs.layoutInDisplayCutoutMode = oldCutout
                            window.attributes = attrs
                        }
                    }
                }
            }

            // HTML5 视频全屏：把 WebView 给的 View 挂到全屏黑底容器
            if (customView != null) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { container ->
                        val cv = customView
                        if (cv != null) {
                            if (cv.parent !== container) {
                                (cv.parent as? android.view.ViewGroup)?.removeView(cv)
                                container.removeAllViews()
                                container.addView(
                                    cv,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                            }
                        } else {
                            container.removeAllViews()
                        }
                    },
                    onRelease = { container ->
                        container.removeAllViews()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(100f)
                        .background(Color.Black),
                )
            }

            if (EngineSupport.GECKO_BUNDLED) {
                GeckoExtMenuSheet(
                    show = geckoExtSheetShow,
                    backdrop = if (glassAvailable) glassBackdrop else null,
                    activeSession = state.activeTab.geckoSession,
                    onOpenUrl = { url -> state.newTab(url, tab.incognito) },
                    onDismiss = { geckoExtSheetShow = false },
                )
            }

            // 网页增强关着（或 Gecko 默认）：不组合 WebView 拓展的页面 / 菜单，也就不会创建拓展系统
            // 拓展晚加载：就绪前不组合（也就不创建拓展系统）
            val extReady = dev.dsh.miuixbrowser.ext.ExtStartupGate.readyState.value
            if (extReady && settings.pageEnhancements && !settings.geckoMode) run {
                val extSystem = context.appContainer.webExtensions
                var optionsRequest by remember { mutableStateOf<ExtPageRequest?>(null) }
                var pendingOptions by remember { mutableStateOf<ExtPageRequest?>(null) }
                // 先收起功能菜单 Sheet，再开拓展页 Sheet（两层 Sheet 同时切换时后一层会被宿主吞掉）
                androidx.compose.runtime.LaunchedEffect(pendingOptions) {
                    val req = pendingOptions ?: return@LaunchedEffect
                    webViewExtSheetShow = false
                    kotlinx.coroutines.delay(250)
                    optionsRequest = req
                    pendingOptions = null
                }
                DisposableEffect(extSystem) {
                    extSystem.pages.openOptionsHandler = { extId, path ->
                        val ext = extSystem.repo.get(extId)
                        if (ext != null && ext.enabled) {
                            pendingOptions = ExtPageRequest(extId, ext.spec.name, ExtPageRequest.Kind.OPTIONS, path)
                        }
                    }
                    onDispose { extSystem.pages.openOptionsHandler = null }
                }
                WebViewExtPageSheet(
                    system = extSystem,
                    request = optionsRequest,
                    backdrop = if (glassAvailable && settings.blurEnabled) glassBackdrop else null,
                    onDismiss = { optionsRequest = null },
                )
            }

            if (extReady && settings.pageEnhancements && !settings.geckoMode) WebViewExtMenuSheet(
                show = webViewExtSheetShow,
                backdrop = if (glassAvailable && settings.blurEnabled) glassBackdrop else null,
                system = context.appContainer.webExtensions,
                incognito = tab.incognito,
                onChanged = { state.tabs.forEach { it.applyWebExtensions() } },
                onDismiss = { webViewExtSheetShow = false },
            )

            TranslateSheet(
                show = translateSheetShow,
                backdrop = if (glassAvailable) glassBackdrop else null,
                tab = state.activeTab,
                settings = settings,
                onDismiss = { translateSheetShow = false },
            )

            ScriptMenuSheet(
                show = scriptSheetShow,
                backdrop = if (glassAvailable) glassBackdrop else null,
                scripts = runningScripts,
                bridgeMode = scriptBridgeMode,
                commands = scriptMenuItems,
                scriptsEnabled = settings.scriptsEnabled,
                onScriptsEnabledChange = { on ->
                    settings.updateScriptsEnabled(on)
                    state.tabs.forEach { it.scriptsEnabled = on }
                    // 旧列表对应的是切换前的页面，先清空，页面重载完成后由上面的刷新逻辑重新填充
                    runningScripts = emptyList()
                    scriptMenuItems = emptyList()
                    // 已注入的脚本无法撤销，刷新页面使开关生效（直接 reload：页面正在加载时 tab.reload() 只会停止加载）
                    runCatching { tab.refresh() }
                },
                onRunCommand = { cmd ->
                    scriptSheetShow = false
                    tab.runMenuCommand(cmd.id)
                },
                onDismiss = { scriptSheetShow = false },
            )
}

        // 网络请求（Sheet）：WebView 用标签自己的统计；Gecko 用内置扩展按页面 URL 上报的统计
        val reqTab = state.activeTab
        RequestLogSheet(
            show = requestSheetShow,
            backdrop = if (glassAvailable) glassBackdrop else null,
            log = if (reqTab.isGecko) remember(reqTab, reqTab.url) { GeckoNetLog.logFor(reqTab.url) } else reqTab.requestLog,
            isGecko = reqTab.isGecko,
            onDismiss = { requestSheetShow = false },
        )

        val permPrompt = webPermPrompt
        // 「记住此网站的选择」：只有 Gecko 的网站权限带 permKey，选了之后写进例外网站
        var rememberChoice by remember(permPrompt) { mutableStateOf(false) }
        val answerPrompt = { allow: Boolean ->
            val p = permPrompt
            webPermPrompt = null
            if (p != null) {
                if (rememberChoice && p.permKey != null) GeckoSitePerms.remember(p.origin, p.permKey, allow)
                p.decide(allow)
            }
        }
BrowserChunk {
            AppGlassDialog(
                show = permPrompt != null,
                title = tr("允许网站{0}？", permPrompt?.what ?: ""),
                summary = permPrompt?.origin,
                backdrop = if (glassAvailable) glassBackdrop else null,
                onDismissRequest = {
                    webPermPrompt = null
                    permPrompt?.decide?.invoke(false)
                },
            ) {
                if (permPrompt?.permKey != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberChoice = !rememberChoice }
                            .padding(bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tr("记住此网站的选择"),
                            modifier = Modifier.weight(1f),
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        AppSwitch(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppDialogButton(
                        onClick = { answerPrompt(false) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = tr("拒绝"), style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = { answerPrompt(true) },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Primary,
                    ) {
                        Text(text = tr("允许"), style = MiuixTheme.textStyles.button)
                    }
                }
            }

            // 脚本悬浮播放器的「投屏到」面板（状态在 MediaHost.pickerUi）
            CastPickerDialog(
                ui = dev.dsh.miuixbrowser.script.MediaHost.pickerUi,
                backdrop = if (glassAvailable) glassBackdrop else null,
            )

            with(box) {
                LoginPromptDialog(
                    prompt = loginPrompt,
                    backdrop = if (glassAvailable) glassBackdrop else null,
                    onClear = { loginPrompt = null },
                )
            }
}

        val scriptImportUrl = pendingScript
BrowserChunk {
            AppGlassDialog(
                show = scriptImportUrl != null,
                title = tr("导入脚本？"),
                summary = scriptImportUrl,
                backdrop = if (glassAvailable) glassBackdrop else null,
                onDismissRequest = { pendingScript = null },
            ) {
                if (scriptImportUrl != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppDialogButton(
                                onClick = { pendingScript = null },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                            }
                            AppDialogButton(
                                onClick = {
                                    val target = scriptImportUrl
                                    pendingScript = null
                                    scope.launch {
                                        val code = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            fetchRemoteScript(target)
                                        }
                                        if (code != null) {
                                            extensions.add("", code, sourceUrl = target)
                                            pushPage(AppRoute.Extensions)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                role = GlassDialogButtonRole.Primary,
                            ) {
                                Text(text = tr("导入"), style = MiuixTheme.textStyles.button)
                            }
                        }
                    }
                }
            }

            AppGlassDialog(
                show = showClearTabsConfirm,
                title = if (tabsPage == 1) tr("删除全部无痕浏览页？") else tr("删除全部标签页？"),
                summary = if (tabsPage == 1) {
                    tr("将关闭所有无痕浏览页，普通标签页不受影响。")
                } else {
                    tr("将关闭所有标签页，无痕浏览页不受影响，并新建一个空白标签。")
                },
                backdrop = if (glassAvailable && settings.blurEnabled) glassBackdrop else null,
                onDismissRequest = { showClearTabsConfirm = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppDialogButton(
                        onClick = { showClearTabsConfirm = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            state.closeAllOf(incognito = tabsPage == 1)
                            showClearTabsConfirm = false
                            sheet = Sheet.None
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Destructive,
                    ) {
                        Text(text = tr("全部删除"), style = MiuixTheme.textStyles.button)
                    }
                }
            }

            ShareDialog(
                show = showShareDialog,
                title = tab.title,
                url = tab.url,
                backdrop = if (glassAvailable && settings.blurEnabled) glassBackdrop else null,
                onDismiss = { showShareDialog = false },
            )

            FindDialog(
                show = showFind,
                backdrop = if (glassAvailable) glassBackdrop else null,
                onDismiss = {
                    tab.clearFind()
                    showFind = false
                },
                onQuery = { tab.findAll(it) },
                onPrev = { tab.findNext(false) },
                onNext = { tab.findNext(true) },
            )

            FavoriteEditorDialog(
                show = showBookmarkCreate,
                initial = null,
                groups = favorites.groups(),
                backdrop = if (glassAvailable) glassBackdrop else null,
                liquidBackdrop = if (glassAvailable && settings.blurEnabled) liquidBackdrop else null,
                prefillName = bookmarkPrefillName,
                prefillUrl = bookmarkPrefillUrl,
                onDismiss = { showBookmarkCreate = false },
                onConfirm = { name, url, group, pinned ->
                    favorites.add(name, url, group, pinned)
                    showBookmarkCreate = false
                    android.widget.Toast.makeText(context, tr("添加书签成功"), android.widget.Toast.LENGTH_SHORT).show()
                },
            )
}

BrowserChunk {
            FavoriteEditor(
                show = showFavEditor,
                initial = favorites.items.getOrNull(editingFavIndex),
                backdrop = if (glassAvailable) glassBackdrop else null,
                onDismiss = { showFavEditor = false },
                onConfirm = { name, url ->
                    if (editingFavIndex >= 0) {
                        favorites.update(editingFavIndex, name, url)
                    } else {
                        favorites.add(name, url)
                    }
                    showFavEditor = false
                },
            )

            with(box) {
                GroupBookmarkPopup(
                    show = showGroupPopup,
                    rootPath = groupPopupPath,
                    anchorBounds = groupPopupAnchor,
                    favorites = favorites,
                    // 排列方式存在加密库里：切换后立即落盘，重启后保持
                    gridMode = favorites.groupPopupGrid,
                    onGridModeChange = { favorites.updateGroupPopupGrid(it) },
                    backdrop = if (glassAvailable && settings.blurEnabled) glassBackdrop else null,
                    onDismiss = { showGroupPopup = false },
                    onOpenUrl = { url ->
                        showGroupPopup = false
                        state.openOrReuse(url, tab.incognito, fromStartPage = true)
                    },
                )
            }

            run {
                val cap = settings.homeQuickRows.coerceAtLeast(1) * HOME_QUICK_COLS
                val all = settings.homeQuickItems
                QuickMenuPickerDialog(
                    show = showQuickPicker,
                    candidates = quickMenuCandidates(),
                    pickedActions = all.take(cap).toSet(),
                    backdrop = if (glassAvailable) glassBackdrop else null,
                    onDismiss = { showQuickPicker = false },
                    onPick = { action ->
                        // 追加到可见部分的末尾；被行数挡住的旧项排在后面（去重）
                        settings.updateHomeQuickItems(all.take(cap) + action + all.drop(cap).filter { it != action })
                        showQuickPicker = false
                    },
                )
            }

            GroupPickerDialog(
                show = showGroupPicker,
                favorites = favorites,
                pickedIds = homeGroups.map { it.id },
                backdrop = if (glassAvailable) glassBackdrop else null,
                onDismiss = { showGroupPicker = false },
                onPick = { path ->
                    // 登记（已登记则返回原 id）拿到稳定 id，追加到末尾
                    val id = favorites.registerFolder(path)
                    favorites.updateHomeGroupIds(homeGroups.map { it.id } + id)
                    showGroupPicker = false
                },
            )
}
}
