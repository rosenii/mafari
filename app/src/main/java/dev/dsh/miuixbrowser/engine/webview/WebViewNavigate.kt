/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.webview

import android.webkit.WebView
import androidx.webkit.NavigationParameters
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 主框架加载的原生入口：优先 `WebViewCompat.navigate`（androidx.webkit ≥ 1.17，特性 WEBVIEW_NAVIGATE_EXPERIMENTAL_V1），
 * 它返回可被 NavigationListener 精确对上的 `Navigation`，能替换当前历史项（对应 Firefox `tabs.update({loadReplace})`），
 * 自定义头的处理也比 `loadUrl(url, headers)` 一致。系统 WebView 不支持、或不是 http(s) 地址（javascript: / data: / about: 等）时
 * 退回 `loadUrl`；此时无法替换历史项，[replace] 被忽略。
 *
 * 必须在主线程调用。
 */
internal object WebViewNavigate {
    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_NAVIGATE_EXPERIMENTAL_V1) }.getOrDefault(false)

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    fun load(webView: WebView, url: String, headers: Map<String, String> = emptyMap(), replace: Boolean = false) {
        // 地址栏 / 拓展手输的 file:// 同样只认本应用拷出的 HTML 副本，不让浏览器去读手机存储或应用私有数据
        if (url.startsWith("file:", ignoreCase = true) &&
            !dev.dsh.miuixbrowser.util.LocalHtml.isOpenedFileUrl(webView.context, url)
        ) return
        if (isWebUrl(url) && isSupported()) {
            try {
                val params = NavigationParameters.Builder()
                    .setShouldReplaceCurrentEntry(replace)
                    .apply { if (headers.isNotEmpty()) addAdditionalHeaders(headers) }
                    .build()
                WebViewCompat.navigate(webView, url, params)
                return
            } catch (_: IllegalArgumentException) {
                // 头名 / 值不符合 RFC 2616 等：交给 loadUrl 走原行为
            }
        }
        if (headers.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, headers)
    }
}
