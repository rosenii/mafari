/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.download.Downloader
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.foundation.Image

/* BrowserScreen 用到的小型数据类型与纯函数（从 BrowserScreen 拆出） */
internal enum class Sheet { None, Tabs, Menu, Link, Image, Element }

internal class WebPermPrompt(val origin: String, val what: String, val permKey: String?, val decide: (Boolean) -> Unit)

internal data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val fileName: String,
    val mime: String?,
    /** 来源页（Referer）与是否带 WebView 的 Cookie：登录态 / 防盗链站点 */
    val referer: String? = null,
    val withCookies: Boolean = false,
    val geckoCookies: Boolean = false,
    /** blob / data URL 已抽成 data URI，确认后直接落盘，不再走 HTTP 下载器 */
    val dataUri: String? = null,
    /** 大 blob 分片写入的缓存临时文件路径，确认后拷到 Download/mafari */
    val tempPath: String? = null,
    /** GM_download 要结果：取消 / 被顶替 → (false, ...)；确认后交给 Downloader 在终态回调 */
    val onResult: ((ok: Boolean, error: String?) -> Unit)? = null,
)

/**
 * 把网页 accept 里的类型（MIME / 通配 / 扩展名）整理成系统选择器能用的 MIME 列表。
 * 返回空列表 = 不加类型过滤（任意类型）。
 * 此前把未知扩展名直接去掉点当 MIME（如 ".zip" → "zip"），非法 MIME 会让部分机型的
 * 文件选择器把所有文件灰掉、无法选择。
 */
internal fun resolvePickerMimeTypes(types: List<String>): List<String> {
    if (types.isEmpty()) return emptyList()
    val extra = mapOf(
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "mkv" to "video/x-matroska", "md" to "text/markdown", "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar", "apk" to "application/vnd.android.package-archive",
        "epub" to "application/epub+zip", "json" to "application/json", "zip" to "application/zip",
    )
    val mimeRe = Regex("^[a-z0-9!#$&^_.+-]+/([a-z0-9!#$&^_.+-]+|\\*)$")
    val out = LinkedHashSet<String>()
    for (raw in types) {
        val t = raw.lowercase()
        val mime = if (t.startsWith(".")) {
            val ext = t.removePrefix(".")
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: extra[ext]
        } else t
        // 解析不了 / 不合法 / 本身就是全通配：整体不过滤
        if (mime == null || mime == "*/*" || !mimeRe.matches(mime)) return emptyList()
        out.add(mime)
    }
    return out.toList()
}

/** 网页 <input type=file capture>：文件 / 拍照 / 录像 选择 */
internal class FileChooserChoice(
    val params: WebChromeClient.FileChooserParams,
    val callback: ValueCallback<Array<Uri>>,
)
