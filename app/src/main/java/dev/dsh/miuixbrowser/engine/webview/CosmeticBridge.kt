/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.webview

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.util.Diag

/**
 * 元素隐藏（去广告）的页面 → 原生桥，基于 WebMessageListener，取代需要同步返回值的 addJavascriptInterface（[dev.dsh.miuixbrowser.adblock.AdBlockBridge]）。
 *
 * 和旧桥的区别：
 *  - 页面里只有 postMessage / onmessage，不再把 Java 对象的方法面暴露给每个框架；
 *  - 本框架的 host 由系统给出的来源（sourceOrigin）决定，页面谎报不了；页面只能提供「顶层站点」（用来判断站点白名单），
 *    且只受理形如域名的字符串、有长度上限；
 *  - 只受理 http(s) 来源的字符串消息；来源是 about:blank / srcdoc 之类不透明来源的框架拿不到任何 CSS（旧脚本本来也在 hostname 为空时直接返回）；
 *  - 异步：页面发出请求，原生回一条消息，CSS 到达后再插入（一次消息往返，通常在首次绘制之前）。
 *
 * 必须在首次加载网页之前安装；系统 WebView 不支持 WebMessageListener 时返回 false，调用方退回旧桥。
 */
object CosmeticBridge {

    const val NAME = "MafariAdblock"

    /** 域名最长 253 字符；IPv6 带方括号再留余量 */
    internal const val MAX_TOP_HOST_CHARS = 260

    fun install(webView: WebView, allowed: () -> Boolean): Boolean {
        if (!OneWayBridge.supported()) return false
        return runCatching {
            WebViewCompat.addWebMessageListener(webView, NAME, setOf("*")) { _, message, sourceOrigin, _, replyProxy ->
                val css = respond(
                    message.type == WebMessageCompat.TYPE_STRING,
                    message.data,
                    sourceOrigin.toString(),
                    allowed(),
                )
                if (!css.isNullOrEmpty()) runCatching { replyProxy.postMessage(css) }
            }
            true
        }.onFailure { Diag.log("cosmetic bridge install failed ${it.message}") }.getOrDefault(false)
    }

    private fun respond(isString: Boolean, data: String?, sourceOrigin: String, allowed: Boolean): String? {
        if (!allowed) return null
        // 来源必须是 http(s)；host 取系统给出的来源，不取页面自报的
        val originHost = WebOrigin.parse(sourceOrigin)?.host ?: return null
        val (host, top) = parseRequest(isString, data, originHost) ?: return null
        return AdBlock.cosmeticCss(host, top)
    }

    /** 与 Android 无关的校验：[originHost] 是已校验过的来源 host。通过返回 (本框架 host, 顶层站点 host)，否则 null。 */
    internal fun parseRequest(isString: Boolean, data: String?, originHost: String?): Pair<String, String>? {
        if (!isString || originHost.isNullOrEmpty()) return null
        val top = data?.trim()?.lowercase().orEmpty()
        val topOk = top.isNotEmpty() && top.length <= MAX_TOP_HOST_CHARS &&
            top.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '[' || it == ']' || it == ':' }
        return originHost to (if (topOk) top else originHost)
    }
}
