package dev.dsh.miuixbrowser.ext

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 拓展页面（后台 / popup / options / offscreen）的存储隔离：每个拓展一个 androidx.webkit 原生 `Profile`
 * （`WebViewCompat.setProfile`，特性 MULTI_PROFILE），cookie、localStorage、IndexedDB、Cache、Service Worker 都在该 Profile 里，
 * 与标签页（默认 Profile）互不可见，拓展之间也互不可见。
 *
 * 取舍：拓展页面里 `fetch(..., {credentials: "include"})` 读不到标签页的站点 cookie（这正是隔离的目的）；
 * 需要站点 cookie 的拓展应走 `cookies` API（它读的是默认 Profile 的 CookieManager，不受影响）。
 * 系统 WebView 不支持 MULTI_PROFILE 时 [apply] 返回 false，页面留在默认 Profile（与此前行为一致）。
 */
internal object ExtensionProfiles {
    private const val PREFIX = "mafari-ext-"

    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /** Profile 名只留字母数字、下划线、连字符，避免拓展 id 里的特殊字符 */
    fun nameFor(extId: String): String = PREFIX + extId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /**
     * 必须在该 WebView 做任何其他事（加载、evaluateJavascript、getProfile）之前、主线程调用。
     * @return true = 已切到该拓展的 Profile
     */
    fun apply(webView: WebView, extId: String): Boolean {
        if (!isSupported()) return false
        return try {
            WebViewCompat.setProfile(webView, nameFor(extId))
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    /** 卸载拓展时删掉它的 Profile 与全部存储；仍有 WebView 在用时会失败，忽略（下次启动不会再被使用）。 */
    fun delete(extId: String) {
        if (!isSupported()) return
        runCatching { ProfileStore.getInstance().deleteProfile(nameFor(extId)) }
    }
}
