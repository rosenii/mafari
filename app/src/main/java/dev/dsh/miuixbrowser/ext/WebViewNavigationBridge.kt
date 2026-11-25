/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.Navigation
import androidx.webkit.NavigationListener
import androidx.webkit.NavigationRedirectParameters
import androidx.webkit.NavigationState
import androidx.webkit.Page
import androidx.webkit.PageState
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.engine.webview.WebViewPerf
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * [WebViewNavigationBridge] 把主框架导航事件交给拓展子系统的出口。ExtensionSystem 实现它：
 * 同时驱动 webNavigation（committed / DOMContentLoaded / completed）和 webRequest（主文档真实响应）。
 */
interface NavigationSink {
    /** 服务端重定向：[fromUrl] → [toUrl]，[statusCode] / [headers] 是重定向响应本身的（不支持时为 null）。 */
    fun redirected(tabId: Int, fromUrl: String, toUrl: String, statusCode: Int?, headers: JSONArray?)

    /** 主文档提交（不含同文档导航、错误页）。[statusCode] / [headers] 是最终响应的。 */
    fun committed(
        tabId: Int, url: String, transitionType: String, qualifiers: List<String>,
        statusCode: Int?, headers: JSONArray?,
    )

    fun domContentLoaded(tabId: Int, url: String)

    /** window.load。 */
    fun loaded(tabId: Int, url: String, statusCode: Int?, headers: JSONArray?)
}

/** 不依赖 androidx.webkit 的纯映射逻辑，单独拿出来方便单测。 */
object NavigationMapping {
    fun transitionType(isReload: Boolean, wasInitiatedByPage: Boolean): String = when {
        isReload -> "reload"
        wasInitiatedByPage -> "link"
        else -> "typed"
    }

    fun qualifiers(isHistory: Boolean, serverRedirected: Boolean): List<String> = buildList {
        if (isHistory) add("forward_back")
        if (serverRedirected) add("server_redirect")
    }

    /** 状态码 ≤ 0 视为未知（特性不支持 / 导航没有 HTTP 响应）。 */
    fun statusOrNull(code: Int): Int? = code.takeIf { it > 0 }

    /**
     * 响应头 → Firefox 的 `[{name, value}]`。androidx.webkit 的头集合类型随版本变化（Map<String,String> 或
     * Map<String,List<String>>），这里按运行时形状处理：值是集合就每个元素一条，否则一条。
     */
    fun headersJson(raw: Any?): JSONArray? {
        val map = raw as? Map<*, *> ?: return null
        val out = JSONArray()
        for ((k, v) in map) {
            val name = k as? String ?: continue
            when (v) {
                is Iterable<*> -> v.forEach { item -> item?.let { out.put(JSONObject().put("name", name).put("value", it.toString())) } }
                null -> Unit
                else -> out.put(JSONObject().put("name", name).put("value", v.toString()))
            }
        }
        return out
    }
}

/**
 * 用 androidx.webkit 的 NavigationListener（1.16 起稳定，1.19 起带 NavigationState 快照与响应头）取代 WebViewClient 回调的近似。
 *
 * 分工：
 * - **仍由 WebViewClient 路径负责**：onBeforeNavigate（WebView 没有「同文档导航之外的开始」信号，NavigationListener 的
 *   onNavigationStarted 连 pushState 这类同文档导航也会触发，直接用会给扩展发大量假的 beforeNavigate）、
 *   onErrorOccurred、onHistoryStateUpdated / onReferenceFragmentUpdated。
 * - **由本桥负责**：onCommitted（带真实 transitionType / qualifiers）、onDOMContentLoaded、onCompleted、
 *   webRequest 主文档的 onBeforeRedirect / onHeadersReceived / onResponseStarted / onCompleted（带真实状态码与响应头）。
 *   这些标签上 WebViewClient 的对应近似事件由 ExtensionSystem 丢弃，避免同一次导航发两遍。
 *
 * 两档支持：
 * - 档 1（NAVIGATION_LISTENER_NAVIGATION_STATE）：走 NavigationState 重载，带状态码和响应头；
 * - 档 2（只有 NAVIGATION_LISTENER）：走已废弃的 Navigation / Page 重载，仍有真实 transitionType / qualifiers /
 *   DOMContentLoaded / load 和状态码，但拿不到响应头与重定向响应；
 * - 都不支持时 [attach] 返回 false，调用方保持旧路径。所有回调在主线程。
 */
object WebViewNavigationBridge {
    private fun supported(feature: String): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(feature) }.getOrDefault(false)

    /** 0 = 不支持；1 = 只有旧版 NavigationListener；2 = 带 NavigationState 快照。 */
    fun tier(): Int = when {
        !supported(WebViewFeature.NAVIGATION_LISTENER) -> 0
        supported(WebViewFeature.NAVIGATION_LISTENER_NAVIGATION_STATE) -> 2
        else -> 1
    }

    fun isSupported(): Boolean = tier() > 0

    private fun headersSupported(): Boolean = supported(WebViewFeature.NAVIGATION_GET_RESPONSE_HEADERS)

    fun attach(webView: WebView, tabId: Int, sink: NavigationSink): Boolean {
        val tier = tier()
        if (tier == 0) return false
        return try {
            val main = Handler(Looper.getMainLooper())
            WebViewCompat.addNavigationListener(webView, Executor { main.post(it) }, Listener(tabId, sink, tier == 2))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * [stateMode] 为真时只处理 NavigationState / PageState 重载，为假时只处理旧重载。
     * 两组都覆盖且互相屏蔽，避免同一次导航被处理两遍（新重载的默认实现会转调旧重载，这里都不调 super）。
     */
    @Suppress("OVERRIDE_DEPRECATION")
    private class Listener(
        private val tabId: Int,
        private val sink: NavigationSink,
        private val stateMode: Boolean,
    ) : NavigationListener {
        private class Flight(var url: String, var redirected: Boolean = false)

        /** 在途导航（主线程访问）。键用 Navigation 对象本身，文档要求不要用 NavigationState 做键。 */
        private val flights = HashMap<Navigation, Flight>()

        // 当前已提交的主文档；load / DOMContentLoaded 事件靠它对上地址
        private var committedUrl: String? = null
        private var status: Int? = null
        private var headers: JSONArray? = null
        private var domReported = false
        private var loadReported = false

        // ---- 档 1：NavigationState 重载 ----
        override fun onNavigationStarted(navigationState: NavigationState) {
            if (stateMode) started(navigationState.navigation)
        }

        override fun onNavigationRedirected(
            navigationState: NavigationState,
            navigationRedirectParameters: NavigationRedirectParameters,
        ) {
            if (!stateMode) return
            val withHeaders = headersSupported()
            val code = if (withHeaders) {
                runCatching { NavigationMapping.statusOrNull(navigationRedirectParameters.statusCode) }.getOrNull()
            } else null
            val hdrs = if (withHeaders) {
                runCatching { NavigationMapping.headersJson(navigationRedirectParameters.responseHeaders) }.getOrNull()
            } else null
            redirected(navigationState.navigation, code, hdrs)
        }

        override fun onNavigationCompleted(navigationState: NavigationState) {
            if (!stateMode) return
            val hdrs = if (headersSupported()) {
                runCatching { NavigationMapping.headersJson(navigationState.responseHeaders) }.getOrNull()
            } else null
            completed(
                navigationState.navigation,
                sameDocument = navigationState.isSameDocument,
                didCommit = navigationState.didCommit(),
                errorPage = navigationState.didCommitErrorPage(),
                code = NavigationMapping.statusOrNull(runCatching { navigationState.statusCode }.getOrDefault(0)),
                hdrs = hdrs,
            )
        }

        override fun onPageDomContentLoadedEvent(pageState: PageState) {
            if (stateMode) domContentLoaded()
        }

        override fun onPageLoadEvent(pageState: PageState) {
            if (stateMode) loaded()
        }

        // ---- 页面性能：FCP / LCP（1.16 起有 Page 重载，1.19.0-alpha01 起改为 PageState 重载，旧的标为废弃） ----
        // 两个重载都接，写入是「覆盖最新一条」，同一事件被两边各送一次也不会重复计数；都不调 super。
        // 事件可能被批量延迟送达，所以是记在「最近提交的主文档」上，不依赖它是否仍是前台页面。
        override fun onFirstContentfulPaintMillis(pageState: PageState, durationMillis: Long) {
            WebViewPerf.onFcp(tabId, durationMillis)
        }

        override fun onLargestContentfulPaintMillis(pageState: PageState, durationMillis: Long) {
            WebViewPerf.onLcp(tabId, durationMillis)
        }

        override fun onFirstContentfulPaintMillis(page: Page, durationMillis: Long) {
            WebViewPerf.onFcp(tabId, durationMillis)
        }

        override fun onLargestContentfulPaintMillis(page: Page, durationMillis: Long) {
            WebViewPerf.onLcp(tabId, durationMillis)
        }

        // ---- 档 2：旧的 Navigation / Page 重载（1.19.0-alpha02 起废弃，旧系统 WebView 仍只会调这些） ----
        override fun onNavigationStarted(navigation: Navigation) {
            if (!stateMode) started(navigation)
        }

        override fun onNavigationRedirected(navigation: Navigation) {
            if (!stateMode) redirected(navigation, null, null)
        }

        override fun onNavigationCompleted(navigation: Navigation) {
            if (stateMode) return
            completed(
                navigation,
                sameDocument = navigation.isSameDocument,
                didCommit = navigation.didCommit(),
                errorPage = navigation.didCommitErrorPage(),
                code = NavigationMapping.statusOrNull(runCatching { navigation.statusCode }.getOrDefault(0)),
                hdrs = null, // 旧版没有响应头
            )
        }

        override fun onPageDomContentLoadedEvent(page: Page) {
            if (!stateMode) domContentLoaded()
        }

        override fun onPageLoadEvent(page: Page) {
            if (!stateMode) loaded()
        }

        // ---- 两档共用的逻辑 ----
        private fun started(nav: Navigation) {
            flights[nav] = Flight(nav.url)
        }

        private fun redirected(nav: Navigation, code: Int?, hdrs: JSONArray?) {
            val to = nav.url
            val flight = flights.getOrPut(nav) { Flight(to) }
            val from = flight.url
            flight.url = to
            flight.redirected = true
            if (from == to) return
            sink.redirected(tabId, from, to, code, hdrs)
        }

        private fun completed(
            nav: Navigation, sameDocument: Boolean, didCommit: Boolean, errorPage: Boolean,
            code: Int?, hdrs: JSONArray?,
        ) {
            val flight = flights.remove(nav)
            // 同文档（pushState / 片段）由 doUpdateVisitedHistory 路径报告；没提交（204 / 下载 / 被取消）没有文档可言；
            // 错误页由 onReceivedError 路径报 onErrorOccurred，这里不能再报 committed。
            if (sameDocument || !didCommit || errorPage) {
                if (errorPage) committedUrl = null
                return
            }
            val url = nav.url
            committedUrl = url
            status = code
            headers = hdrs
            domReported = false
            loadReported = false
            WebViewPerf.begin(tabId, url)
            sink.committed(
                tabId, url,
                NavigationMapping.transitionType(nav.isReload, nav.wasInitiatedByPage()),
                NavigationMapping.qualifiers(nav.isHistory, flight?.redirected == true),
                code, hdrs,
            )
        }

        private fun domContentLoaded() {
            val url = committedUrl ?: return
            if (domReported) return
            domReported = true
            sink.domContentLoaded(tabId, url)
        }

        private fun loaded() {
            val url = committedUrl ?: return
            if (loadReported) return
            loadReported = true
            sink.loaded(tabId, url, status, headers)
        }
    }
}
