package dev.dsh.miuixbrowser

import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.dsh.miuixbrowser.i18n.tr

/**
 * 自定义标签页（Custom Tab）：其他应用里点链接时，在调用方的任务里直接盖一层轻量的网页窗口，
 * 返回 / 点 × 就回到原来的应用，不跳转到 Mafari 主界面。由 [LinkDispatchActivity] 在「外部链接打开方式 = 自定义标签页」时启动。
 *
 * 复用轻应用 [WebAppActivity] 的 WebView 壳（共用 Cookie / 代理 / 去广告 / 深浅色），区别：
 * - 顶部多一条工具栏：× 关闭、标题 + 域名、↗ 在 Mafari 里打开；
 * - 任何站点的跳转都留在窗口内（不会弹到浏览器），不做顶部沉浸收起，也不改调用方任务的标题 / 图标。
 */
class CustomTabActivity : WebAppActivity() {

    override val customTabMode: Boolean get() = true

    override fun isCrossSite(host: String): Boolean = false

    private var titleView: TextView? = null
    private var hostView: TextView? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onWebViewReady() {
        val bar = dp(48)
        val fg = if (dark) 0xFFEDEDED.toInt() else 0xFF1A1A1A.toInt()
        val sub = if (dark) 0xFF9A9A9A.toInt() else 0xFF6B6B6B.toInt()

        fun icon(text: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            contentDescription = desc
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(bar, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { onClick() }
        }

        val title = TextView(this).apply {
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val host = TextView(this).apply {
            setTextColor(sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        titleView = title
        hostView = host
        host.text = Uri.parse(startUrl).host.orEmpty()
        title.text = host.text

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(defaultBg)
            addView(icon("\u2715", tr("关闭")) { finish() })
            addView(center, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                icon("\u2197", tr("在 Mafari 中打开")) {
                    openInMafari(Uri.parse(webView.url ?: startUrl))
                    finish()
                },
            )
        }
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar, Gravity.TOP))

        // 网页、进度条、下拉刷新指示都让到工具栏下面
        (webView.layoutParams as FrameLayout.LayoutParams).topMargin = bar
        (progress.layoutParams as FrameLayout.LayoutParams).topMargin = bar
        (refreshSpinner.layoutParams as FrameLayout.LayoutParams).topMargin += bar
        progress.bringToFront()
        refreshSpinner.bringToFront()
    }

    override fun onPageInfo(title: String?, url: String?) {
        runOnUiThread {
            if (!title.isNullOrBlank()) titleView?.text = title
            val h = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
            if (h.isNotBlank()) {
                hostView?.text = h
                if (titleView?.text.isNullOrBlank()) titleView?.text = h
            }
        }
    }
}
