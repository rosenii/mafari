/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import android.content.Context

/**
 * 页内翻译的脚本部分（两种内核共用同一份 assets/mafari_translate.js，见 [TabTranslate] / GeckoTranslate）。
 *
 * - WebView：本对象拼脚本，BrowserTab 用 evaluateJavascript 注入；
 * - Gecko：同一份脚本作为内置拓展 mafari-translate-ext 的内容脚本，由 GeckoTranslate 经原生消息通道收发。
 */
object TranslateMode {
    const val ASSET = "mafari_translate.js"

    @Volatile private var cached: String? = null

    /** 脚本源码（进程内缓存；幂等，可重复注入） */
    fun source(context: Context): String = cached ?: context.applicationContext.assets.open(ASSET)
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
        .also { cached = it }

    /** JS 字符串字面量（只用于语言代码这类短串，仍按规矩转义） */
    internal fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
        append('"')
    }

    /** 注入并开始翻译 */
    fun startScript(js: String, target: String): String =
        "$js\n;(function(){var t=window.__mafariTr;t.attachWebView();return t.start({target:${quote(target)}});})();"

    /** 注入并重试失败的批次 */
    fun retryScript(js: String): String =
        "$js\n;(function(){var t=window.__mafariTr;t.attachWebView();return t.retry();})();"

    const val ORIGINAL_SCRIPT = "(function(){try{return window.__mafariTr?window.__mafariTr.showOriginal():false;}catch(e){return false;}})();"
    const val TRANSLATION_SCRIPT = "(function(){try{return window.__mafariTr?window.__mafariTr.showTranslation():false;}catch(e){return false;}})();"
    const val STOP_SCRIPT = "(function(){try{return window.__mafariTr?window.__mafariTr.stop():false;}catch(e){return false;}})();"

    /** 原生 → 页面：把响应 JSON 交给页面脚本。JSON 里的 U+2028/2029 在旧 JS 里是换行，要转义。 */
    fun recvScript(json: String): String =
        "(function(){try{var t=window.__mafariTr;if(t)t.recv(${json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")});}catch(e){}})();"
}
