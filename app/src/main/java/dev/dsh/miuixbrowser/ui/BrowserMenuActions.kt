package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.browser.MenuSyncState
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.backup.BackupAuto
import dev.dsh.miuixbrowser.browser.AppRoute
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.browser.ShortcutHelper
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.Favorite
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtensionStore
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.util.hostOf
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.icon.extended.Home
import dev.dsh.miuixbrowser.ui.icons.Settings
import top.yukonga.miuix.kmp.icon.glass.Refresh
import dev.dsh.miuixbrowser.data.FavoritesStore
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.platform.ClipboardManager


/** 脚本快捷菜单：查询当前页面运行的脚本 + 它们注册的快捷命令 */
internal fun BrowserUiState.refreshScriptSheet(state: BrowserState) {
    val t = state.activeTab
    t.queryRunningScriptIds { ids ->
        val byId = t.userScripts.associateBy { it.id }
        runningScripts = ids.map { id ->
            val us = byId[id]
            RunningScript(
                id = id,
                name = us?.name?.ifBlank { null } ?: tr("未命名脚本"),
                icon = us?.icon.orEmpty(),
                iconBase = us?.sourceUrl.orEmpty(),
                iconFallbackHost = us?.let { UserscriptEngine.fallbackIconHost(UserscriptEngine.parse(it.code)) }.orEmpty(),
            )
        }
    }
    t.queryMenuCommands { scriptMenuItems = it }
}

/** 菜单功能的执行：底部「更多」菜单和首页「快捷菜单」共用（从 BrowserScreen 拆出，逻辑未改） */
internal fun BrowserUiState.performMenuAction(
    action: MenuAction,
    state: BrowserState,
    favorites: FavoritesStore,
    context: android.content.Context,
    scope: CoroutineScope,
    clipboard: ClipboardManager,
    launchers: BrowserLaunchers,
    pushPage: (AppRoute) -> Unit,
) {
    val tab = state.activeTab
    when (action) {
        MenuAction.NewTab -> state.newTab(incognito = false)
        MenuAction.Incognito -> state.toggleIncognito()
        MenuAction.Desktop -> tab.toggleDesktopMode(!tab.desktopMode)
        MenuAction.Find -> showFind = true
        // 页内翻译：弹出翻译面板（选翻译源 / 目标语言，开始 / 还原 / 切换原文）
        MenuAction.Translate -> if (tab.url.isNotBlank()) translateSheetShow = true
        MenuAction.Favorite -> if (tab.url.isNotBlank()) {
            val favName = tab.title.ifBlank { hostOf(tab.url) }
            val msg = if (favorites.exists(favName, tab.url)) {
                tr("已添加此书签")
            } else {
                favorites.add(favName, tab.url)
                tr("添加书签成功")
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
        MenuAction.Bookmarks -> pushPage(AppRoute.Library(0))
        MenuAction.Share -> if (tab.url.isNotBlank()) {
            if (needsInAppShareDialog()) showShareDialog = true else shareUrlSystem(context, tab.url, tab.title)
        }
        MenuAction.Refresh -> tab.reload()
        MenuAction.CopyLink -> if (tab.url.isNotBlank()) {
            clipboard.setText(AnnotatedString(tab.url))
        }
        MenuAction.External -> if (tab.url.isNotBlank()) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url)))
            }
        }
        MenuAction.AddToDesktop -> ShortcutHelper.addToDesktop(context, scope, tab)
        MenuAction.Home -> state.goHome(tab)
        MenuAction.Downloads -> pushPage(AppRoute.Downloads)
        MenuAction.Scan -> launchers.launchScan()
        // 单击：拓展快捷菜单；长按（见 onLongPressAction）：进入拓展页
        MenuAction.GeckoExtensions -> geckoExtSheetShow = true
        MenuAction.ScriptMenu -> {
            scriptSheetShow = true
            refreshScriptSheet(state)
        }
        MenuAction.History -> pushPage(AppRoute.Library(1))
        MenuAction.Requests -> requestSheetShow = true
        MenuAction.Settings -> pushPage(AppRoute.Settings)
        MenuAction.Account -> if (EngineSupport.SYNC_BUNDLED) pushPage(AppRoute.FxaPage)
        MenuAction.CloseTab -> state.closeTab(state.activeIndex)
        MenuAction.CleanCache -> scope.launch {
            val freed = runCatching { dev.dsh.miuixbrowser.browser.CacheCleaner.cleanAll(context) }.getOrDefault(0L)
            android.widget.Toast.makeText(
                context,
                if (freed > 0) tr("已清理 ") + android.text.format.Formatter.formatFileSize(context, freed) else tr("没有可清理的缓存"),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        MenuAction.Sync -> if (!MenuSyncState.syncing) {
            MenuSyncState.syncing = true
            scope.launch {
                val r = runCatching { BackupAuto.pushAllNow(context) }.getOrNull()
                MenuSyncState.syncing = false
                val msg = when {
                    r == null -> tr("同步失败")
                    r.error != null -> r.error ?: ""
                    r.failed == 0 -> tr("同步完成，已上传到 {0} 个目标", r.ok)
                    r.ok == 0 -> tr("同步失败（{0} 个目标上传失败）", r.failed)
                    else -> tr("同步完成：{0} 个成功，{1} 个失败", r.ok, r.failed)
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // 去广告开关：只有 WebView 内核有（Gecko 标签下菜单里是置灰的）
        MenuAction.AdBlock -> if (!tab.isGecko) {
            AdBlock.updateEnabled(!AdBlock.enabled)
            android.widget.Toast.makeText(context, if (AdBlock.enabled) tr("去广告已开启") else tr("去广告已关闭"), android.widget.Toast.LENGTH_SHORT).show()
        } else {
            dev.dsh.miuixbrowser.engine.gecko.showGeckoUnsupportedToast(context)
        }
    }
}

/** 脚本相关的副作用：总开关 / 启用范围同步到所有标签，脚本菜单与地址栏脚本图标的运行数查询（从 BrowserScreen 拆出） */
@Composable
internal fun BrowserUiState.ScriptStateEffects(
    settings: AppSettings,
    state: BrowserState,
    context: android.content.Context,
) {
    val tab = state.activeTab
    // 脚本总开关 → 所有标签页
    LaunchedEffect(settings.scriptsEnabled, state.tabs.size) {
        state.tabs.forEach { it.scriptsEnabled = settings.scriptsEnabled }
    }

    // 启用范围（无痕）→ 所有标签页
    LaunchedEffect(settings.scriptsInIncognito, settings.adblockInIncognito, state.tabs.size) {
        state.tabs.forEach {
            it.scriptsInIncognito = settings.scriptsInIncognito
            it.adblockInIncognito = settings.adblockInIncognito
        }
    }

    LaunchedEffect(settings.sameSiteNewTab, state.tabs.size) {
        state.tabs.forEach { it.sameSiteNewTab = settings.sameSiteNewTab }
    }

    // Sheet 打开期间，页面（重新）加载完成后刷新脚本列表；停用时保留旧列表（仅置灰）
    // 页面加载完成后先等一下再取，之后每 1.5 秒复查一次（document-idle 的脚本、延迟注册的菜单命令可能比首次查询晚出现）；
    // 脚本被停用时刷新后页面里不会再有运行中的脚本，列表同步清空
    LaunchedEffect(scriptSheetShow, tab.loading, settings.scriptsEnabled) {
        if (!scriptSheetShow) return@LaunchedEffect
        if (!settings.scriptsEnabled) {
            runningScripts = emptyList()
            scriptMenuItems = emptyList()
        } else if (!tab.loading) {
            kotlinx.coroutines.delay(500)
            // 脚本菜单命令没有「注册完成」事件可订阅，不能纯事件驱动；改成递增退避：
            // 刚加载完 document-idle 脚本 / 延迟注册命令最容易出现，先密后疏（0.5s → 1.5s → 3s → 5s），
            // 之后保持 5 秒一次，菜单关闭或页面重新加载时本协程被取消。
            var gap = 500L
            while (true) {
                refreshScriptSheet(state)
                kotlinx.coroutines.delay(gap)
                gap = when {
                    gap < 1500L -> 1500L
                    gap < 3000L -> 3000L
                    else -> 5000L
                }
            }
        }
    }

    // 地址栏脚本图标：页面加载完成后查询当前页运行的脚本数。document-idle 的脚本比首次查询出现得晚，
    // 所以加载完后只在 0.6s / 2.6s 各查一次（事件驱动，不轮询）；开始加载就先清掉，避免上一页的图标留在新页面上
    val scriptIconTab = state.activeTab
    LaunchedEffect(scriptIconTab.id, scriptIconTab.url, scriptIconTab.loading, settings.scriptsEnabled) {
        if (!settings.scriptsEnabled || scriptIconTab.url.isEmpty() || scriptIconTab.loading) {
            addressScriptCount = 0
            return@LaunchedEffect
        }
        for (gap in longArrayOf(600L, 2000L)) {
            kotlinx.coroutines.delay(gap)
            scriptIconTab.queryRunningScripts { n -> if (state.activeTab === scriptIconTab) addressScriptCount = n }
        }
    }

    // 打开更多菜单时刷新「当前页面运行的脚本数」
    LaunchedEffect(sheet, state.activeIndex) {
        if (sheet == Sheet.Menu) {
            scriptRunCount = 0
            state.activeTab.queryRunningScripts { scriptRunCount = it }
            // 「拓展」角标：刷新已启用的拓展数
            if (settings.geckoMode) runCatching { GeckoExtensionStore.refresh(context) }
        }
    }
}
