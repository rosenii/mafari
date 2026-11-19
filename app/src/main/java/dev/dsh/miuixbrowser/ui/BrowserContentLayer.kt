package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.browser.AppRoute
import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.Favorite
import dev.dsh.miuixbrowser.engine.gecko.showWebViewUnsupportedToast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.dsh.miuixbrowser.data.FavoritesStore
import dev.dsh.miuixbrowser.data.HistoryStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.LayerBackdrop as MiuixLayerBackdrop


/** 网页内容区：WebView / GeckoView 宿主 + 新建标签页（首页）+ 下拉刷新 + 加载进度条（从 BrowserScreen 拆出）。
 *  [animatedTopPadding] 是读取函数而不是值：动画每帧都变，只允许在布局阶段读取。 */
@Composable
internal fun BrowserUiState.BrowserContentLayer(
    settings: AppSettings,
    state: BrowserState,
    tab: BrowserTab,
    context: android.content.Context,
    favorites: FavoritesStore,
    history: HistoryStore,
    homeFavEntries: List<Pair<Int, Favorite>>,
    homeGroups: List<HomeGroup>,
    wallpaperBitmap: ImageBitmap?,
    wallpaperBackdrop: MiuixLayerBackdrop,
    wallpaperLiquidBackdrop: com.kyant.backdrop.Backdrop,
    startScroll: ScrollState,
    barOnTop: Boolean,
    quickRunner: MenuActionRunner,
    nativeInset: Boolean,
    contentTopPx: Density.() -> Int,
    animatedTopPadding: () -> Dp,
    pushPage: (AppRoute) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 全屏沉浸（decorFitsSystemWindows=false）下系统不会替 WebView / GeckoView 缩小窗口，
                // 键盘会直接盖住屏幕底部的网页输入框。这里让网页区域避开键盘：
                // 区域变矮后页面会把获得焦点的输入框滚到可见处。
                // 地址栏输入时不收缩，避免每次唤起键盘都让网页整体重排。
                // 注意：不能用 imePadding()——它每帧跟着键盘动画变化，Gecko 的 Surface 会在
                // 动画期间被连续重排（窗口抖动）。这里用动画「目标」高度，只在动画开始时变一次。
                .then(if (editing) Modifier else Modifier.windowInsetsPadding(rememberImeTargetInsets()))
                .topInsetPx { if (nativeInset) 0 else contentTopPx() },
        ) {
            key(tab.id) {
                AndroidView(
                    factory = { _ ->
                        // AndroidView.factory 接收的是 Context，而不是 View。
                        // 这里返回已经由 BrowserTab 持有的内容视图（WebView 或 GeckoView）。
                        tab.contentView.apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            // Insets 只在 View 首次附着时请求一次。不要在每次 Compose
                            // factory/update 中重复 requestLayout，避免打断 Gecko 的帧提交。
                            if (isAttachedToWindow) {
                                androidx.core.view.ViewCompat.requestApplyInsets(this)
                            } else {
                                val target = this
                                addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                                    override fun onViewAttachedToWindow(v: android.view.View) {
                                        v.removeOnAttachStateChangeListener(this)
                                        androidx.core.view.ViewCompat.requestApplyInsets(target)
                                    }
                                    override fun onViewDetachedFromWindow(v: android.view.View) = Unit
                                })
                            }
                        }
                    },
                    update = { view ->
                        // 主页盖在 WebView 上面，但 WebView 仍保留着刚才网页的画面。
                        // 主页开启 MIUIX 背景采集后，这帧会被录进 layerBackdrop 再送进 RuntimeShader，
                        // 点主页按钮时会原生崩溃。主页状态下把 WebView 设为 INVISIBLE（不销毁，
                        // 返回时仍能还原网页），这样采集层里只有 Compose 内容。
                        // 这里读取了 tab.url，主页/网页切换时 update 会重新执行。
                        val wantedVisibility = if (tab.url.isEmpty()) {
                            android.view.View.INVISIBLE
                        } else {
                            android.view.View.VISIBLE
                        }
                        if (view.visibility != wantedVisibility) view.visibility = wantedVisibility
                        // AndroidView 自己负责尺寸/布局同步。这里仅同步可见性，禁止每次
                        // Compose 重组都 post requestApplyInsets + requestLayout。
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (nativeInset) {
                                Modifier.offset {
                                    IntOffset(0, animatedTopPadding().coerceAtLeast(0.dp).roundToPx())
                                }
                            } else {
                                Modifier
                            },
                        )
                        .onSizeChanged { size ->
                            // Compose 的尺寸才是当前沉浸式内容区域的真实可用尺寸。
                            // 尺寸有效后让 WebView 重新布局，再通知页面重新计算响应式布局。
                            if (size.width > 0 && size.height > 0) tab.onViewportChanged()
                        },
                )
            }
            if (tab.url.isEmpty()) {
                if (wallpaperBitmap == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.surface),
                    )
                }
                StartPage(
                    scrollState = startScroll,
                    wallpaper = wallpaperBitmap,
                    wallpaperBackdrop = wallpaperBackdrop,
                    blurEnabled = settings.blurEnabled,
                    favorites = homeFavEntries.map { it.second },
                    showLogo = settings.homeShowLogo,
                    barOnTop = barOnTop,
                    showFavorites = settings.homeShowFavorites,
                    favRows = settings.homeFavRows,
                    favGrid = settings.homeFavGrid,
                    onFavGridChange = { settings.updateHomeFavGrid(it) },
                    onOpenBookmarks = { pushPage(AppRoute.Library(0)) },
                    showPrivacy = settings.homeShowPrivacy,
                    geckoMode = tab.isGecko,
                    showRecent = settings.homeShowRecent && !tab.incognito,
                    showDownloads = settings.homeShowDownloads,
                    liquidBackdrop = if (settings.liquidGlass && wallpaperBitmap != null) wallpaperLiquidBackdrop else null,
                    order = settings.homeOrder,
                    onReorder = { settings.updateHomeOrder(it) },
                    recent = history.recent,
                    onOpenDownloads = { pushPage(AppRoute.Downloads) },
                    onHide = { kind ->
                        settings.updateHomeShow(kind, false)
                        android.widget.Toast.makeText(
                            context, tr("已隐藏，可在 设置 → 布局 → 定制主页 中恢复"), android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    editing = favEditing,
                    onOpen = { state.openOrReuse(it, tab.incognito, fromStartPage = true) },
                    onToggleEdit = { favEditing = !favEditing },
                    // 首页只显示「个人收藏」分组：回调里的下标是首页列表里的位置，要换算回总列表的下标
                    onRemove = { pos -> homeFavEntries.getOrNull(pos)?.let { favorites.remove(it.first) } },
                    onMove = { from, to ->
                        val a = homeFavEntries.getOrNull(from)?.first
                        val b = homeFavEntries.getOrNull(to)?.first
                        if (a != null && b != null) favorites.move(a, b)
                    },
                    onEdit = { pos ->
                        editingFavIndex = homeFavEntries.getOrNull(pos)?.first ?: -1
                        showFavEditor = editingFavIndex >= 0
                    },
                    onAdd = {
                        editingFavIndex = -1
                        showFavEditor = true
                    },
                    showGroups = settings.homeShowGroups,
                    groups = homeGroups,
                    groupRows = settings.homeGroupRows,
                    groupEditing = groupEditing,
                    // 点击分组磁贴：不再跳转，在图标下方弹出书签窗口
                    onGroupOpen = { path, anchor ->
                        groupPopupPath = path
                        groupPopupAnchor = anchor
                        showGroupPopup = true
                    },
                    onGroupToggleEdit = { groupEditing = !groupEditing },
                    // 写回时只保留还存在的分组（顺带清掉已失效的 id）
                    onGroupRemove = { pos ->
                        favorites.updateHomeGroupIds(homeGroups.filterIndexed { i, _ -> i != pos }.map { it.id })
                    },
                    onGroupMove = { from, to ->
                        val ids = homeGroups.map { it.id }.toMutableList()
                        if (from in ids.indices && to in ids.indices) {
                            ids.add(to, ids.removeAt(from))
                            favorites.updateHomeGroupIds(ids)
                        }
                    },
                    onGroupAdd = { showGroupPicker = true },
                    showQuick = settings.homeShowQuick,
                    quickActions = settings.homeQuickItems,
                    quickRows = settings.homeQuickRows,
                    quickEditing = quickEditing,
                    onQuickAction = { action ->
                        // 拓展只在 Gecko 内核可用：WebView 下只提示
                        if (action == MenuAction.GeckoExtensions && !settings.geckoMode) {
                            showWebViewUnsupportedToast(context)
                        } else {
                            quickRunner.run(action)
                        }
                    },
                    onQuickToggleEdit = { quickEditing = !quickEditing },
                    // 回调里的下标是可见部分的位置，与完整列表的前缀一致
                    onQuickRemove = { pos ->
                        settings.updateHomeQuickItems(settings.homeQuickItems.filterIndexed { i, _ -> i != pos })
                    },
                    onQuickMove = { from, to ->
                        val list = settings.homeQuickItems.toMutableList()
                        if (from in list.indices && to in list.indices) {
                            list.add(to, list.removeAt(from))
                            settings.updateHomeQuickItems(list)
                        }
                    },
                    onQuickAdd = { showQuickPicker = true },
                    pinBottom = settings.homePinBottom,
                    onPinBottomChange = { settings.updateHomePinBottom(it) },
                )
            }
        }
        PullSpinnerHost(tab)
        if (tab.loading) {
            ProgressLine(
                progress = { tab.progress },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .topInsetPx(contentTopPx),
            )
        }
    }
}
