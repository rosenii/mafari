package dev.dsh.miuixbrowser.engine.webview

import android.webkit.WebView
import androidx.webkit.HttpCache
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.util.Diag

/**
 * 省流量 / 控缓存两项 androidx.webkit 能力（WebView 内核）：
 *
 * 1. [disableFaviconDownload]：`WebSettingsCompat.setDownloadFaviconsEnabled(false)`。应用的图标全部走自己的
 *    FaviconLoader（带磁盘缓存与退避），不读 WebView 的 onReceivedIcon / WebView.favicon，
 *    WebView 每次导航自己再下一遍图标是纯浪费。
 * 2. 网页缓存上限：`Profile#getHttpCache`（1.17+）。注意它管的是「配额」：能读 / 设上限，
 *    **不能清空、也读不到当前占用**，清缓存仍走 `WebView.clearCache`（见 CacheCleaner）。
 *    配额由 WebView 持久化，跨重启有效，所以只在用户改选时调用，不需要启动时重放。
 *    官方提醒：调小可能触发后台淘汰；Default Profile 被多个库共用时不要乱设 —— 本应用所有标签都在 Default Profile 上，
 *    且没有其它库共用，所以可以用。
 */
object WebViewNetSavings {

    /** 新建 WebView 时调用一次。不支持的旧系统 WebView 静默跳过。 */
    fun disableFaviconDownload(webView: WebView) {
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOWNLOAD_FAVICONS_ENABLED)) {
                WebSettingsCompat.setDownloadFaviconsEnabled(webView.settings, false)
            }
        }.onFailure { Diag.log("favicon download switch failed ${it.message}") }
    }

    /* ---------------------------- HTTP 缓存配额 ---------------------------- */

    /** 下拉里除「自动」外的档位（MB）。 */
    val QUOTA_CHOICES_MB = listOf(64, 128, 256, 512)

    private fun mb(n: Int) = n * 1024L * 1024L

    fun isCacheQuotaSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.HTTP_CACHE_MANAGER)
    }.getOrDefault(false)

    private fun httpCache(): HttpCache? = runCatching {
        if (!isCacheQuotaSupported()) null
        else ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME).httpCache
    }.getOrNull()

    /** 当前自动配额（字节），读不到返回 null。主线程。 */
    fun defaultQuotaBytes(): Long? = runCatching { httpCache()?.defaultQuotaBytes }.getOrNull()

    /** 下拉当前选中项：0 = 自动，1.. 对应 [QUOTA_CHOICES_MB]。主线程。 */
    fun selectedIndex(): Int {
        val cache = httpCache() ?: return 0
        return runCatching {
            if (cache.isUsingDefaultQuota) 0 else 1 + QuotaMath.nearestChoice(cache.quotaBytes, QUOTA_CHOICES_MB)
        }.getOrDefault(0)
    }

    /** 选了第 [index] 项：0 = 自动，1.. = 固定上限。返回是否生效。主线程。 */
    fun select(index: Int): Boolean {
        val cache = httpCache() ?: return false
        return runCatching {
            if (index <= 0) cache.useDefaultQuota() else cache.setQuotaBytes(mb(QUOTA_CHOICES_MB[index - 1]))
            true
        }.onFailure { Diag.log("http cache quota failed ${it.message}") }.getOrDefault(false)
    }
}

/** 不依赖 Android 的换算，方便单测。 */
object QuotaMath {
    /** 配额可能被 WebView 夹到上下限，所以按「最接近」找档位，而不是要求完全相等。 */
    fun nearestChoice(quotaBytes: Long, choicesMb: List<Int>): Int {
        var best = 0
        var bestDiff = Long.MAX_VALUE
        choicesMb.forEachIndexed { i, m ->
            val diff = kotlin.math.abs(quotaBytes - m * 1024L * 1024L)
            if (diff < bestDiff) { bestDiff = diff; best = i }
        }
        return best
    }
}
