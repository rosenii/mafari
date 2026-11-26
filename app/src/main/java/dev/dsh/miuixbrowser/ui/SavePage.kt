/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.content.Context
import android.widget.Toast
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.EngineKind
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONTokener

/** 把网页标题变成可作文件名的字符串。 */
internal fun pageFileName(title: String, url: String): String {
    val base = title.ifBlank { runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty() }
        .ifBlank { "page" }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().take(80).ifBlank { "page" }
    return "$base.html"
}

/** 给 HTML 补 <base>，保存后离线打开时相对路径的图片 / 样式仍指向原站。 */
internal fun withBaseHref(html: String, url: String): String {
    if (Regex("<base\\s", RegexOption.IGNORE_CASE).containsMatchIn(html)) return html
    val tag = "<base href=\"" + url.replace("\"", "&quot;") + "\">"
    val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
    return if (head != null) html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
    else tag + html
}

/**
 * 长按菜单「下载」：自动把当前网页下载到本地 Download/mafari。
 * WebView：取渲染后的 DOM 存成 .html；Gecko 没有页内 JS 通道，改用内置下载器重新拉取该网址。
 */
internal fun saveCurrentPage(context: Context, tab: BrowserTab) {
    val url = tab.url
    if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
        Toast.makeText(context, tr("当前页面无法下载"), Toast.LENGTH_SHORT).show()
        return
    }
    val name = pageFileName(tab.title, url)
    if (tab.engineKind != EngineKind.WEBVIEW) {
        Downloader.start(context, url, null, name, "text/html", withCookies = true, geckoCookies = true)
        Toast.makeText(context, tr("开始下载网页"), Toast.LENGTH_SHORT).show()
        return
    }
    tab.evaluateJs(
        "(function(){var d=document.doctype;" +
            "var t=d?'<!DOCTYPE '+d.name+(d.publicId?' PUBLIC \"'+d.publicId+'\"':'')+(d.systemId?' \"'+d.systemId+'\"':'')+'>\\n':'';" +
            "return t+document.documentElement.outerHTML;})()",
    ) { raw ->
        val html = runCatching { JSONTokener(raw ?: "").nextValue() as? String }.getOrNull()
        val ok = !html.isNullOrEmpty() &&
            Downloader.saveBytes(context, withBaseHref(html, url).toByteArray(Charsets.UTF_8), name, "text/html")
        Toast.makeText(
            context,
            if (ok) tr("已保存到 Download/mafari") else tr("保存失败"),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
