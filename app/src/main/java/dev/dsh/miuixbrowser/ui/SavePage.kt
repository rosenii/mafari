/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.content.Context
import android.widget.Toast
import java.io.File
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.EngineKind
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONTokener

/** 把网页标题变成可作文件名的字符串（不含扩展名）。 */
internal fun pageFileName(title: String, url: String): String =
    title.ifBlank { runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty() }
        .ifBlank { "page" }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().take(80).ifBlank { "page" }

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
    val base = pageFileName(tab.title, url)
    if (tab.engineKind != EngineKind.WEBVIEW) {
        // Gecko 没有 saveWebArchive 的等价能力：退化成只保存 HTML（资源仍走原站），保证仍能离线打开文字
        Downloader.start(context, url, null, "$base.html", "text/html", withCookies = true, geckoCookies = true)
        Toast.makeText(
            context,
            tr("当前页面已离线保存至{0}", "/sdcard/Download/mafari/$base.html"),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    // WebView 的 MHTML 会把当前已加载的图片 / 样式 / 脚本一起收进同一个文件，离线打开最完整
    val temp = File(context.cacheDir, "mafari_page_${System.currentTimeMillis()}.mhtml")
    val done = java.util.concurrent.atomic.AtomicBoolean(false)
    fun finish(path: String?) {
        if (!done.compareAndSet(false, true)) return
        val file = path?.let { File(it) } ?: temp
        if (!file.isFile || file.length() == 0L) {
            Toast.makeText(context, tr("保存失败"), Toast.LENGTH_SHORT).show()
            runCatching { file.delete() }
            return
        }
        val name = "$base.mhtml"
        // 复制大 MHTML 到系统下载目录放后台，避免卡 UI
        Thread {
            val (ok, savedName) = Downloader.saveFileEx(context, file, name, "multipart/related")
            runCatching { file.delete() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (ok) {
                    Toast.makeText(
                        context,
                        tr("当前页面已离线保存至{0}", "/sdcard/Download/mafari/${savedName ?: name}"),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(context, tr("保存失败"), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    runCatching {
        tab.webView.saveWebArchive(temp.absolutePath, false) { path -> finish(path) }
    }.onFailure {
        finish(null)
    }
}
