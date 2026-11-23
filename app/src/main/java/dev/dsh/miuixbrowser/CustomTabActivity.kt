package dev.dsh.miuixbrowser

import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.dsh.miuixbrowser.ui.CustomTabBar
import dev.dsh.miuixbrowser.util.hostOf

/**
 * 自定义标签页（Custom Tab）：其他应用里点链接时，在调用方的任务里直接盖一层轻量的网页窗口，
 * 返回 / 点左边圆钮就回到原来的应用，不跳转到 Mafari 主界面。由 [LinkDispatchActivity] 启动。
 *
 * 复用轻应用 [WebAppActivity] 的 WebView 壳（共用 Cookie / 代理 / 去广告 / 深浅色），区别：
 * - 底部是和浏览器一样的悬浮三段式栏（[CustomTabBar]）：左 = 返回、中 = 地址栏、右 = 在 Mafari 中打开；
 * - 任何站点的跳转都留在窗口内（不会弹到浏览器），不做顶部沉浸收起，也不改调用方任务的标题 / 图标。
 */
class CustomTabActivity : WebAppActivity() {

    override val customTabMode: Boolean get() = true

    override fun isCrossSite(host: String): Boolean = false

    private var barTitle by mutableStateOf("")
    private var barUrl by mutableStateOf("")
    private var barLoading by mutableStateOf(false)

    override fun onWebViewReady() {
        barUrl = startUrl
        val bar = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val settings = appContainer.settings
                // 地址栏显示方式跟随设置（0 = 网站标题 1 = 主域名 2 = 完整网址），与浏览器一致
                val shown = when (settings.addressDisplay) {
                    0 -> barTitle.ifBlank { barUrl }
                    1 -> hostOf(barUrl)
                    else -> barUrl
                }
                CustomTabBar(
                    settings = settings,
                    address = shown,
                    loading = barLoading,
                    // 走和系统返回键同一条路径：先退全屏视频，再网页后退，没有历史就关闭
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenInMafari = {
                        openInMafari(Uri.parse(webView.url ?: startUrl))
                        finish()
                    },
                    onRefresh = { webView.reload() },
                    // 网页在栏上面结束（栏高度含手势条留白）
                    onHeightChanged = { h ->
                        val lp = webView.layoutParams as FrameLayout.LayoutParams
                        if (lp.bottomMargin != h) {
                            lp.bottomMargin = h
                            webView.layoutParams = lp
                        }
                    },
                )
            }
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
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
