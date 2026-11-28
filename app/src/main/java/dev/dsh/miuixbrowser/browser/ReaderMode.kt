/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import android.content.Context

/**
 * 阅读模式（两种内核共用同一份 assets/mafari_reader.js：检测 / 正文提取 / 渲染）。
 *
 * - WebView：本对象拼脚本，BrowserTab 用 evaluateJavascript 注入并读返回值；
 * - Gecko：同一份脚本作为内置拓展 mafari-reader-ext 的内容脚本，由 [GeckoReader] 经原生消息通道收发。
 *
 * 平台没有现成的阅读模式 API 可用：Android WebView 不提供；GeckoView 也没有公开的「可读性检测 / 阅读视图」接口
 * （Firefox 自己是靠内置拓展 + 特权页面实现的，嵌入方拿不到），所以两边都走「页内脚本」，Gecko 经内置拓展承载。
 */
object ReaderMode {
    const val ASSET = "mafari_reader.js"

    @Volatile private var cached: String? = null

    /** 脚本源码（进程内缓存；幂等，可重复注入） */
    fun source(context: Context): String = cached ?: context.applicationContext.assets.open(ASSET)
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
        .also { cached = it }

    /** 注入并检测：evaluateJavascript 的回调值为 "true" / "false" */
    fun detectScript(js: String): String = "$js\n;window.__mafariReader && window.__mafariReader.detect();"

    /** 注入并进入阅读模式：回调值 "true" 表示已进入，"false" 表示提取失败 */
    fun enterScript(js: String, dark: Boolean): String =
        "$js\n;window.__mafariReader ? window.__mafariReader.enter({dark:$dark}) : false;"

    /** 听书：取当前页面正文纯文本（优先用阅读模式提取的正文）。evaluateJavascript 返回 JSON 字符串，需要再解一次。 */
    fun textScript(js: String): String =
        "$js\n;(function(){try{if(window.__mafariReader&&window.__mafariReader.text)return window.__mafariReader.text();}catch(e){}try{return document.body?document.body.innerText:'';}catch(e){return '';}})();"

    /** 退出阅读模式（页面里没有脚本就什么也不做） */
    const val EXIT_SCRIPT = "(function(){try{if(window.__mafariReader){window.__mafariReader.exit();}return true;}catch(e){return false;}})();"
}
