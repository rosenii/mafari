/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui


import dev.dsh.miuixbrowser.browser.AppRoute
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.data.AppSettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.dsh.miuixbrowser.appContainer

import dev.dsh.miuixbrowser.ui.icons.Settings
import dev.dsh.miuixbrowser.download.DownloadsScreen
import dev.dsh.miuixbrowser.data.FavoritesStore
import dev.dsh.miuixbrowser.data.HistoryStore
import dev.dsh.miuixbrowser.script.ExtensionStore

/** 浏览页的二级页面（设置 / 关于 / 下载 / 扩展 / 书签历史等）需要的共享依赖。 */
internal class BrowserNav(
    val settings: AppSettings,
    val state: BrowserState,
    val favorites: FavoritesStore,
    val history: HistoryStore,
    val extensions: ExtensionStore,
    val pushPage: (AppRoute) -> Unit,
    val popPage: () -> Unit,
    val popToBrowser: () -> Unit,
    val openInBrowser: (String) -> Unit,
    /** 设置 → 关于 → 使用指南：回到浏览主界面并从第一步重放引导 */
    val onOpenGuide: () -> Unit,
) {
    val tab: BrowserTab get() = state.activeTab
}

@Composable
internal fun BrowserNav.SubPage(route: AppRoute) {
    when (route) {
        AppRoute.Settings -> {
            val appCtx = LocalContext.current.applicationContext
            SettingsScreen(
                settings = settings,
                onBack = { popPage() },
                onApplyUserAgent = { state.applyUserAgent(it) },
                onOpenDownloads = { pushPage(AppRoute.Downloads) },
                onOpenBookshelf = { pushPage(AppRoute.Bookshelf) },
                onOpenScripts = { pushPage(AppRoute.Extensions) },
                // 「拓展」页：按默认内核进对应的管理页（两个内核共用同一套界面）
                onOpenExtensions = { pushPage(if (settings.geckoMode) AppRoute.GeckoExtensions else AppRoute.WebViewExtensions) },
                onOpenGeckoProtection = { pushPage(AppRoute.GeckoProtectionPage) },
                onOpenGeckoDebug = { pushPage(AppRoute.GeckoDebugPage) },
                onOpenWebViewDebug = { pushPage(AppRoute.WebViewDebugPage) },
                onOpenAdBlock = { pushPage(AppRoute.AdBlockPage) },
                onOpenMediaSniff = { pushPage(AppRoute.MediaSniffPage) },
                onOpenCookies = { pushPage(AppRoute.CookiePage) },
                onOpenPasswords = { pushPage(AppRoute.PasswordPage) },
                onOpenPrivacyEnhance = { pushPage(AppRoute.PrivacyEnhancePage) },
                onOpenBookmarks = { pushPage(AppRoute.Library(0)) },
                onOpenBackup = { pushPage(AppRoute.BackupPage) },
                onOpenAbout = { pushPage(AppRoute.About) },
                onOpenGuide = onOpenGuide,
                onPageEnhancementsChange = {
                    extensions.setAllEnabled(it)
                    // 拓展 / 去广告随总开关启停（不改每个拓展自己的启用状态）；已打开标签的拓展注入同步撤销 / 重装
                    dev.dsh.miuixbrowser.StartupCoordinator.onPageEnhancementsChanged(appCtx, it)
                    state.tabs.forEach { t -> t.applyWebExtensions() }
                },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.About -> {
            AboutScreen(
                onBack = { popPage() },
                onOpenUrl = { openInBrowser(it) },
                blurEnabled = settings.blurEnabled,
                updateChannel = settings.updateChannel,
                onUpdateChannelChange = { settings.updateUpdateChannel(it) },
                onOpenPrivacyPolicy = { pushPage(AppRoute.PrivacyPolicyPage) },
            )
        }
        AppRoute.PrivacyPolicyPage -> {
            PrivacyPolicyScreen(
                onBack = { popPage() },
                onOpenUrl = { openInBrowser(it) },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.Downloads -> {
            DownloadsScreen(
                onBack = { popPage() },
                blurEnabled = settings.blurEnabled,
                multiThread = settings.downloadMultiThread,
                onMultiThreadChange = { settings.updateDownloadMultiThread(it) },
                threads = settings.downloadThreads,
                onThreadsChange = { settings.updateDownloadThreads(it) },
                multiThreadMinMb = settings.downloadMultiThreadMinMb,
                onMultiThreadMinMbChange = { settings.updateDownloadMultiThreadMinMb(it) },
                externalDownloader = settings.externalDownloader,
                onExternalDownloaderChange = { settings.updateExternalDownloader(it) },
                deleteLocal = settings.downloadDeleteLocal,
                onDeleteLocalChange = { settings.updateDownloadDeleteLocal(it) },
            )
        }
        AppRoute.Bookshelf -> {
            BookshelfScreen(
                settings = settings,
                onBack = { popPage() },
                onOpenBook = { pushPage(AppRoute.Reader(it)) },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.MediaSniffPage -> {
            MediaSniffScreen(
                settings = settings,
                onBack = { popPage() },
                // 总开关：一键启停 M3U8 媒体下载器脚本；其余（嗅探、自动画中画、自动悬浮播放、宿主能力）由 MediaFeatures 即时生效
                onMasterChange = { on -> extensions.setMediaScriptsEnabled(on) },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.AdBlockPage -> {
            AdBlockScreen(
                settings = settings,
                onBack = { popPage() },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.CookiePage -> {
            CookieScreen(
                settings = settings,
                currentUrl = tab.url,
                onBack = { popPage() },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.PasswordPage -> {
            PasswordScreen(
                onBack = { popPage() },
                onOpenFxa = { pushPage(AppRoute.FxaPage) },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.FxaPage -> {
            FxaScreen(
                onBack = { popPage() },
                currentTitle = tab.title,
                currentUrl = tab.url,
                onOpenUrl = { u -> state.newTab(u); popToBrowser() },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.BackupPage -> {
            BackupScreen(
                onBack = { popPage() },
                onOpenFxa = { pushPage(AppRoute.FxaPage) },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.PrivacyEnhancePage -> {
            PrivacyEnhanceScreen(
                settings = settings,
                onBack = { popPage() },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.Extensions -> {
            ExtensionsScreen(
                store = extensions,
                settings = settings,
                onBack = { popPage() },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.GeckoProtectionPage -> {
            GeckoProtectionScreen(
                onBack = { popPage() },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.WebViewDebugPage -> {
            WebViewDebugScreen(
                onBack = { popPage() },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.GeckoDebugPage -> {
            GeckoDebugScreen(
                onBack = { popPage() },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.GeckoExtensions -> {
            GeckoExtensionsScreen(
                onBack = { popPage() },
                // 拓展的「选项」页（moz-extension://…）在新标签里打开
                onOpenUrl = { url ->
                    popToBrowser()
                    state.newTab(target = url, incognito = false)
                },
                blurEnabled = settings.blurEnabled,
            )
        }
        AppRoute.WebViewExtensions -> {
            val system = LocalContext.current.appContainer.webExtensions
            WebViewExtensionsScreen(
                system = system,
                onBack = { popPage() },
                // 拓展集合变了：已打开的 WebView 标签重新装配 content script（对下一次页面加载生效）
                onChanged = { state.tabs.forEach { it.applyWebExtensions() } },
                blurEnabled = settings.blurEnabled,
            )
        }
        is AppRoute.Reader -> {
            ReaderScreen(
                bookId = route.bookId,
                onBack = { popPage() },
                settings = settings,
                blurEnabled = settings.blurEnabled,
            )
        }
        is AppRoute.Library -> {
            LibraryScreen(
                favorites = favorites,
                store = history,
                onBack = { popPage() },
                onOpen = { url ->
                    popToBrowser()
                    state.openOrReuse(url, tab.incognito, navigateIfDeepLink = true)
                },
                initialTab = route.tab,
                blurEnabled = settings.blurEnabled,
                initialGroup = route.group,
            )
        }
        else -> Unit
    }
}
