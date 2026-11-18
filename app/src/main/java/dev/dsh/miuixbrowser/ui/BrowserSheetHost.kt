package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.browser.MenuSyncState
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.browser.AppRoute
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.download.DownloadState
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.browser.EngineKind
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.browser.MenuOrderStore
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.Favorite
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtensionStore
import dev.dsh.miuixbrowser.engine.gecko.showWebViewUnsupportedToast
import dev.dsh.miuixbrowser.util.hostOf
import android.content.Intent
import android.net.Uri

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Image
import androidx.compose.ui.text.AnnotatedString

import kotlin.math.max
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** 底部弹层：标签页 / 更多菜单 / 链接 / 图片 / 页面元素（保持组合直到离场动画结束）。 */
@Composable
internal fun BrowserUiState.BrowserSheetHost(
    settings: AppSettings,
    context: android.content.Context,
    state: BrowserState,
    menuOrder: MenuOrderStore,
    liquidBackdrop: com.kyant.backdrop.Backdrop,
    glassAvailable: Boolean,
    glassBackdrop: LayerBackdrop,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    tab: BrowserTab,
    tabsListState: androidx.compose.foundation.lazy.LazyListState,
    pushPage: (AppRoute) -> Unit,
    runMenuAction: (MenuAction) -> Unit,
) {
        val activeSheet = sheet
        if (activeSheet != Sheet.None) {
            lastActiveSheet = activeSheet
        }
        LaunchedEffect(activeSheet) {
            if (activeSheet != Sheet.None) sheetHostActive = true
        }
BrowserChunk {
            AppBottomSheet(
                show = activeSheet != Sheet.None,
                title = when (lastActiveSheet) {
                    Sheet.Link -> hostOf(linkUrl)
                    Sheet.Image -> tr("图片")
                    Sheet.Element -> tr("页面元素")
                    Sheet.Menu -> null // 更多菜单不显示标题
                    Sheet.Tabs -> null // 标签页自定义头部（渐进模糊 + 添加/删除）
                    else -> null
                },
                // 头部同一行：新建 | 标签页/无痕浏览页 切换 | 删除（整行放在 startAction，自己铺满宽度）
                startAction = if (lastActiveSheet == Sheet.Tabs) {
                    {
                        TabsSheetHeader(
                            state = state,
                            tabsPage = tabsPage,
                            onTabsPageChange = { tabsPage = it },
                            liquid = if (glassAvailable && settings.blurEnabled) liquidBackdrop else null,
                            onClose = { sheet = Sheet.None },
                            onClearAll = { showClearTabsConfirm = true },
                        )
                    }
                } else {
                    null
                },
                endAction = null,
                // 标签列表 / 更多菜单自带滚动容器，自己把头部和导航栏高度做成内边距（铺到头部和导航栏下面再渐隐）
                scrollContent = true,
                // 所有弹层统一采样页面模糊。卡顿的根因不在弹层自己的模糊，而在页面每帧被重复回放，
                // 已由上方的离屏缓存 / 整页冻结解决，所以更多菜单、标签列表可以恢复模糊。
                backdrop = if (glassAvailable && settings.blurEnabled) glassBackdrop else null,
                // 更多菜单 / 标签列表在有模糊采样时不压暗；Gecko 网页态（无法采样）没有模糊，必须压暗以区分层次
                enableWindowDim = (lastActiveSheet != Sheet.Menu && lastActiveSheet != Sheet.Tabs) ||
                    !(glassAvailable && settings.blurEnabled),
                onDismissRequest = { sheet = Sheet.None },
                onDismissFinished = { if (sheet == Sheet.None) sheetHostActive = false },
            ) {
                val sheetInsets = LocalBottomSheetInsets.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            // 链接 / 图片 / 元素菜单不滚动：整体推到头部下方、导航栏上方；标签列表和更多菜单在各自的滚动容器里处理
                            if (lastActiveSheet == Sheet.Tabs || lastActiveSheet == Sheet.Menu) {
                                Modifier
                            } else {
                                Modifier.padding(top = sheetInsets.top, bottom = sheetInsets.bottom + 8.dp)
                            },
                        ),
                ) {
                when (lastActiveSheet) {
                Sheet.Tabs -> TabsSheetBody(
                    state = state,
                    settings = settings,
                    tabsPage = tabsPage,
                    listState = tabsListState,
                    blurBackdrop = if (settings.blurEnabled && glassAvailable) glassBackdrop else null,
                    onClose = { sheet = Sheet.None },
                )

                Sheet.Menu -> {
                    // 「更多」菜单最高占屏幕 44%，超出（如编辑排序时展开隐藏区）可滚动
                    val menuMaxH = (LocalConfiguration.current.screenHeightDp * 0.44f).dp
                    Box(
                        modifier = Modifier
                            .heightIn(max = menuMaxH + sheetInsets.top + sheetInsets.bottom)
                            .verticalScroll(rememberScrollState())
                            // 先滚动后加内边距：内边距在滚动内容里面，菜单能滚到头部和导航栏下面
                            .padding(top = sheetInsets.top, bottom = sheetInsets.bottom + 8.dp),
                    ) {
                    MenuGrid(
                        labelFor = { action ->
                            if (action == MenuAction.Desktop) {
                                if (tab.desktopMode) tr("移动版") else tr("桌面版")
                            } else if (action == MenuAction.Sync && MenuSyncState.syncing) {
                                tr("同步中")
                            } else {
                                action.label
                            }
                        },
                        badgeFor = { action ->
                            when (action) {
                                MenuAction.Downloads ->
                                    Downloader.tasks.count { it.state == DownloadState.RUNNING }
                                MenuAction.ScriptMenu -> scriptRunCount
                                // 本页拦截数：菜单打开 / 重组时读一次计数器，不轮询
                                MenuAction.AdBlock -> if (AdBlock.enabled && !tab.isGecko) tab.pageAdBlocked.get() else 0
                                MenuAction.GeckoExtensions -> if (settings.geckoMode) {
                                    GeckoExtensionStore.items.count {
                                        it.enabled && (!tab.incognito || it.allowedInPrivate)
                                    }
                                } else {
                                    0
                                }
                                else -> 0
                            }
                        },
                        // 置灰：仅 Gecko 专属功能在 WebView 下不可用（脚本在两种内核下都可用）
                        lockedFor = { action ->
                            when (action) {
                                // 拓展只在 Gecko 内核可用：WebView 下置灰，点击提示不可用
                                MenuAction.GeckoExtensions -> !settings.geckoMode
                                // 去广告依赖 WebView 的请求拦截：Gecko 标签下置灰
                                MenuAction.AdBlock -> tab.isGecko
                                else -> false
                            }
                        },
                        order = menuOrder.order,
                        hidden = menuOrder.hidden,
                        onAction = { action ->
                            // 同步 / 去广告 / 清理缓存：菜单保持打开，图标和文字就地变化
                            if (action != MenuAction.Sync && action != MenuAction.AdBlock && action != MenuAction.CleanCache) sheet = Sheet.None
                            runMenuAction(action)
                        },
                        onMove = { action, toHidden, toIndex -> menuOrder.place(action, toHidden, toIndex) },
                        // 长按 0.65 秒（图标不动）：直接进入管理页；单击保持原有操作
                        longPressActions = setOf(MenuAction.ScriptMenu, MenuAction.Favorite, MenuAction.Bookmarks, MenuAction.GeckoExtensions),
                        onLongPressAction = { action ->
                            when (action) {
                                MenuAction.ScriptMenu -> {
                                    sheet = Sheet.None
                                    pushPage(AppRoute.Extensions)
                                }
                                MenuAction.Favorite -> {
                                    sheet = Sheet.None
                                    pushPage(AppRoute.Library(0))
                                }
                                // 长按「书签」：弹出新建书签弹窗，自动填入当前页标题和网址
                                MenuAction.Bookmarks -> {
                                    sheet = Sheet.None
                                    bookmarkPrefillName = tab.title.ifBlank { hostOf(tab.url) }
                                    bookmarkPrefillUrl = tab.url
                                    showBookmarkCreate = true
                                }
                                MenuAction.GeckoExtensions ->
                                    if (!settings.geckoMode) {
                                        showWebViewUnsupportedToast(context)
                                    } else {
                                        sheet = Sheet.None
                                        pushPage(AppRoute.GeckoExtensions)
                                    }
                                else -> Unit
                            }
                        },
                        incognitoActive = tab.incognito,
                        requestsActive = AdBlock.requestLogEnabled,
                        adBlockActive = AdBlock.enabled && !tab.isGecko,
                        syncActive = MenuSyncState.syncing,
                        scriptsActive = settings.scriptsEnabled,
                        extensionsActive = settings.geckoMode &&
                            GeckoExtensionStore.items.any { it.enabled && (!tab.incognito || it.allowedInPrivate) },
                    )
                    }
                }

                Sheet.Link -> {
                    SheetRow(title = tr("打开链接")) {
                        sheet = Sheet.None
                        tab.loadUrl(linkUrl)
                    }
                    SheetRow(title = tr("在新标签页打开")) {
                        sheet = Sheet.None
                        state.newTab(linkUrl, tab.incognito, fromLink = true)
                    }
                    SheetRow(title = tr("复制链接")) {
                        sheet = Sheet.None
                        clipboard.setText(AnnotatedString(linkUrl))
                    }
                    SheetRow(title = tr("在外部浏览器打开")) {
                        sheet = Sheet.None
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))) }
                    }
                    // 标记广告依赖 WebView 的页面内 JS 取元素：Gecko 下不提供
                    if (settings.pageEnhancements && !tab.isGecko) {
                        SheetRow(
                            title = tr("标记为广告"),
                            subtitle = tr("选中这个广告位，可记住规则下次自动隐藏"),
                        ) {
                            sheet = Sheet.None
                            tab.markAdAtTouch {
                                android.widget.Toast.makeText(context, tr("没找到可标记的元素"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                Sheet.Image -> {
                    val inlineImage = imageUrl.startsWith("data:", ignoreCase = true)
                    val blobImage = imageUrl.startsWith("blob:", ignoreCase = true)
                    SheetRow(
                        title = tr("保存图片"),
                        subtitle = when {
                            inlineImage -> tr("内嵌图片")
                            blobImage -> tr("页面内图片")
                            else -> hostOf(imageUrl)
                        },
                    ) {
                        sheet = Sheet.None
                        when {
                            inlineImage -> {
                                val name = "image_" + System.currentTimeMillis() + ".png"
                                val ok = Downloader.saveDataUri(context, imageUrl, name)
                                android.widget.Toast.makeText(
                                    context,
                                    if (ok) tr("已保存到 Download/mafari") else tr("保存失败"),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                            blobImage -> tab.extractBlob(imageUrl, null, null, "image/*")
                            else -> pendingDownload = DownloadRequest(
                                url = imageUrl,
                                userAgent = if (tab.engineKind == EngineKind.WEBVIEW) {
                                    runCatching { tab.webView.settings.userAgentString }.getOrNull()
                                } else {
                                    null
                                },
                                fileName = Downloader.fileNameFrom(imageUrl, null),
                                mime = null,
                                referer = tab.url,
                                withCookies = !tab.incognito,
                                geckoCookies = tab.engineKind == EngineKind.GECKO,
                            )
                        }
                    }
                    if (!inlineImage && !blobImage) {
                        SheetRow(title = tr("复制图片地址")) {
                            sheet = Sheet.None
                            clipboard.setText(AnnotatedString(imageUrl))
                        }
                        SheetRow(title = tr("在新标签页打开")) {
                            sheet = Sheet.None
                            state.newTab(imageUrl, tab.incognito, fromLink = true)
                        }
                    }
                    // 标记广告依赖 WebView 的页面内 JS 取元素：Gecko 下不提供
                    if (settings.pageEnhancements && !tab.isGecko) {
                        SheetRow(
                            title = tr("标记为广告"),
                            subtitle = tr("选中这个广告位，可记住规则下次自动隐藏"),
                        ) {
                            sheet = Sheet.None
                            tab.markAdAtTouch {
                                android.widget.Toast.makeText(context, tr("没找到可标记的元素"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                Sheet.Element -> {
                    val hit = elementHit
                    if (hit != null) {
                        if (hit.imageUrl.isNotBlank()) {
                            SheetRow(title = tr("保存图片"), subtitle = hostOf(hit.imageUrl)) {
                                sheet = Sheet.None
                                pendingDownload = DownloadRequest(
                                    url = hit.imageUrl,
                                    userAgent = if (tab.engineKind == EngineKind.WEBVIEW) {
                                        runCatching { tab.webView.settings.userAgentString }.getOrNull()
                                    } else {
                                        null
                                    },
                                    fileName = Downloader.fileNameFrom(hit.imageUrl, null),
                                    mime = null,
                                    referer = tab.url,
                                    withCookies = !tab.incognito,
                                    geckoCookies = tab.engineKind == EngineKind.GECKO,
                                )
                            }
                        }
                        SheetRow(
                            title = tr("标记为广告并移除"),
                            subtitle = tr("记住这条规则，下次访问该站点自动隐藏"),
                        ) {
                            sheet = Sheet.None
                            if (!AdBlock.addUserCosmetic(hostOf(tab.url), hit.selector)) {
                                // 选择器不安全 / 站点名不合法时规则没有保存，不能让人以为下次会自动隐藏
                                android.widget.Toast.makeText(context, tr("这个元素无法生成规则，本次仅移除"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            tab.evaluateJs(removeElementJs(hit.selector))
                        }
                        SheetRow(title = tr("仅移除该元素")) {
                            sheet = Sheet.None
                            tab.evaluateJs(removeElementJs(hit.selector))
                        }
                    }
                }

                Sheet.None -> Unit
                }
                }
            }
}
}
