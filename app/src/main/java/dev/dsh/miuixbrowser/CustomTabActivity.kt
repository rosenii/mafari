/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.dsh.miuixbrowser.ui.CustomTabScreen
import dev.dsh.miuixbrowser.util.hostOf

/**
 * 自定义标签页（Custom Tab）：其他应用里点链接时，在调用方的任务里直接盖一层轻量的网页窗口，
 * 返回 / 点左边圆钮就回到原来的应用，不跳转到 Mafari 主界面。由 [LinkDispatchActivity] 启动。
 *
 * 复用轻应用 [WebAppActivity] 的 WebView 壳（共用 Cookie / 代理 / 去广告 / 深浅色），区别：
 * - 底部是和浏览器一样的悬浮三段式栏（[CustomTabScreen]，高级材质取样网页）：左 = 返回、中 = 地址栏、右 = 在 Mafari 中打开；
 * - 任何站点的跳转都留在窗口内（不会弹到浏览器），沉浸式浏览与主界面一致（网页铺到状态栏 / 手势条下，滚动时状态栏留白与底栏收起），也不改调用方任务的标题 / 图标。
 */
class CustomTabActivity : WebAppActivity() {

    override val customTabMode: Boolean get() = true

    override fun isCrossSite(host: String): Boolean = false

    private var barTitle by mutableStateOf("")
    private var barUrl by mutableStateOf("")
    private var barLoading by mutableStateOf(false)
    private var topPadPx by mutableIntStateOf(0)
    private var barCompact by mutableStateOf(false)

    override fun onTopPadChanged(px: Int) { topPadPx = px }

    override fun onImmersiveCollapsed(collapsed: Boolean) { barCompact = collapsed }

    override fun onWebViewReady() {
        barUrl = startUrl
        // 显式开启 WebView 硬件层（与主界面 setupWebView 一致）：WebView 被整页采集层录制并送入 RuntimeShader 取样时，
        // 软件 / 自动层会整页闪烁；进 GPU 离屏层后底栏 / 状态栏的高级材质才能稳定取样网页
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        // 网页和底栏放进同一棵 Compose 树：WebView 在整页采集层里，底栏的玻璃才能取样到网页（高级材质）；
        // 网页铺到手势条下面，状态栏留白 / 底栏收起都由 Compose 控制（沉浸式）。插在最底层，下拉刷新指示器等仍在上面
        (webView.parent as? ViewGroup)?.removeView(webView)
        val screen = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val settings = appContainer.settings
                // 地址栏显示方式跟随设置（0 = 网站标题 1 = 主域名 2 = 完整网址），与浏览器一致
                val shown = when (settings.addressDisplay) {
                    0 -> barTitle.ifBlank { barUrl }
                    1 -> hostOf(barUrl)
                    else -> barUrl
                }
                CustomTabScreen(
                    settings = settings,
                    webView = webView,
                    pageBg = defaultBg,
                    topPadPx = topPadPx,
                    compact = barCompact,
                    address = shown,
                    loading = barLoading,
                    // 走和系统返回键同一条路径：先退全屏视频，再网页后退，没有历史就关闭
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenInMafari = {
                        openInMafari(Uri.parse(webView.url ?: startUrl))
                        finish()
                    },
                    onRefresh = { webView.reload() },
                    onExpand = { barCompact = false },
                )
            }
        }
        root.addView(screen, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onPageInfo(title: String?, url: String?) {
        runOnUiThread {
            if (!url.isNullOrBlank() && url != barUrl) {
                barUrl = url
                // 换了页面：旧标题作废，等新标题到来
                if (title == null) barTitle = ""
            }
            if (!title.isNullOrBlank()) barTitle = title
        }
    }

    override fun onLoadingChanged(loading: Boolean) {
        runOnUiThread { barLoading = loading }
    }
}
