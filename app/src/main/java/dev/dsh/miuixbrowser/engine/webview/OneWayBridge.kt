package dev.dsh.miuixbrowser.engine.webview

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.util.Diag

/**
 * 单向消息桥（页面 → 原生，没有返回值），基于 WebMessageListener，用来替换同类的 addJavascriptInterface。
 *
 * 和 addJavascriptInterface 的区别：消息由框架给出来源（sourceOrigin / isMainFrame），
 * 这里只受理主框架、http(s) 来源、字符串消息、长度有上限；子框架（广告 iframe 等）发的一律丢弃。
 * 页面里的对象只有 `postMessage(string)` 一个方法，不再把 Java 对象的方法面暴露给每个框架。
 *
 * 只适合不需要同步返回值的桥。需要同步返回值的（MafariAdblock.css / MafariFp.fp / MafariBlob.begin）不能用它。
 * 必须在首次加载网页之前安装（对象只出现在之后创建的文档里）。
 */
object OneWayBridge {

    /** 系统 WebView 是否支持 WebMessageListener。 */
    fun supported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)

    /**
     * 安装名为 [name] 的桥；[onMessage] 在主线程收到通过校验的消息文本。
     * 不支持或安装失败返回 false，调用方退回旧的 addJavascriptInterface。
     */
    fun install(webView: WebView, name: String, maxChars: Int, onMessage: (String) -> Unit): Boolean {
        if (!supported()) return false
        return runCatching {
            // "*" 只是让对象注入到所有框架；真正的来源校验在 [accept]
            WebViewCompat.addWebMessageListener(webView, name, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                val text = accept(message.type == WebMessageCompat.TYPE_STRING, message.data, sourceOrigin.toString(), isMainFrame, maxChars)
                if (text != null) onMessage(text)
            }
            true
        }.onFailure { Diag.log("one-way bridge $name install failed ${it.message}") }.getOrDefault(false)
    }

    /** 与 Android 无关的校验：通过返回消息文本，否则 null。 */
    internal fun accept(isString: Boolean, data: String?, sourceOrigin: String, isMainFrame: Boolean, maxChars: Int): String? {
        if (!isMainFrame || !isString) return null
        if (WebOrigin.parse(sourceOrigin) == null) return null
        val text = data ?: return null
        return if (text.length > maxChars) null else text
    }
}
