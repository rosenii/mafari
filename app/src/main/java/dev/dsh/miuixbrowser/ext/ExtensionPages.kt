package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import java.io.File

/** 从拓展虚拟 origin 取到的一份资源：[file] 与 [bytes] 二选一 */
class ExtensionResource(val mime: String, val file: File?, val bytes: ByteArray?)

/** 后台页入口：[path] 是相对拓展根的路径（可能是生成的虚拟页） */
class BackgroundEntry(val path: String)

/**
 * 拓展页面（popup / options / 后台页）的文件服务与后台页生成。纯 JVM：Android 侧的 WebViewAssetLoader 只是薄薄一层转调。
 *
 * 后台页：
 * - MV2 `background.page`：直接用它；`background.scripts`：生成一个按顺序引入这些脚本的页面；
 * - MV3 `service_worker`：WebView 对 Service Worker 支持有限，所以当普通脚本在隐藏页面里跑（有 `"type":"module"` 就按模块加载），
 *   并提供一个同步 XHR 实现的 importScripts。事件驱动 / 休眠语义不模拟：脚本一直活着。
 *
 * 已知限制：manifest 里的 content_security_policy 目前不执行（响应不带 CSP 头），importScripts 垫片依赖 eval。
 */
object ExtensionPages {
    const val GENERATED_BACKGROUND = "_generated_background_page.html"
    const val SW_SHIM = "_mafari/sw_shim.js"

    private const val SW_SHIM_JS = """(function () {
  self.importScripts = function () {
    for (var i = 0; i < arguments.length; i++) {
      var url = new URL(arguments[i], self.location.href).href;
      var x = new XMLHttpRequest();
      x.open('GET', url, false);
      x.send();
      if (x.status < 200 || x.status >= 300) throw new Error('importScripts failed: ' + arguments[i]);
      (0, eval)(x.responseText + '\n//# sourceURL=' + url);
    }
  };
})();
"""

    /** 没有后台时返回 null */
    fun backgroundEntry(ext: InstalledExtension): BackgroundEntry? {
        val bg = ext.spec.background ?: return null
        bg.page?.let { page -> return BackgroundEntry(clean(page) ?: return null) }
        if (bg.scripts.isNotEmpty() || bg.serviceWorker != null) return BackgroundEntry(GENERATED_BACKGROUND)
        return null
    }

    /**
     * 网页（标签页里的页面，不是拓展自己）请求拓展资源：只有 web_accessible_resources 放行的才给，
     * 否则返回 null。[pageUrl] 必须取自浏览器自己记录的标签网址，不能用页面自报的。
     */
    fun openWebAccessible(ext: InstalledExtension, rawPath: String, pageUrl: String?): ExtensionResource? {
        if (!isWebAccessible(ext, rawPath, pageUrl)) return null
        return open(ext, rawPath)
    }

    /** 取一份资源；路径不合法、越出拓展目录、不存在时返回 null */
    fun open(ext: InstalledExtension, rawPath: String): ExtensionResource? {
        val path = clean(rawPath) ?: return null
        when (path) {
            GENERATED_BACKGROUND -> return generatedBackground(ext)?.let { html(it) }
            SW_SHIM -> return ExtensionResource("text/javascript", null, SW_SHIM_JS.toByteArray(Charsets.UTF_8))
        }
        return try {
            val root = ext.rootDir.canonicalFile
            val f = File(root, path).canonicalFile
            if (f.path.startsWith(root.path + File.separator) && f.isFile) {
                ExtensionResource(mimeTypeFor(path), f, null)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun generatedBackground(ext: InstalledExtension): String? {
        val bg = ext.spec.background ?: return null
        val sb = StringBuilder("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"></head><body>\n")
        val sw = bg.serviceWorker
        if (sw != null && bg.scripts.isEmpty()) {
            val module = isModuleWorker(ext)
            if (!module) sb.append("<script src=\"/").append(attr(SW_SHIM)).append("\"></script>\n")
            sb.append("<script").append(if (module) " type=\"module\"" else "")
                .append(" src=\"/").append(attr(clean(sw) ?: return null)).append("\"></script>\n")
        } else {
            for (s in bg.scripts) sb.append("<script src=\"/").append(attr(clean(s) ?: continue)).append("\"></script>\n")
        }
        return sb.append("</body></html>\n").toString()
    }

    private fun isModuleWorker(ext: InstalledExtension): Boolean = try {
        JSONObject(File(ext.rootDir, "manifest.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
            .optJSONObject("background")?.optString("type") == "module"
    } catch (_: Exception) {
        false
    }

    private fun html(s: String) = ExtensionResource("text/html", null, s.toByteArray(Charsets.UTF_8))

    /** 去掉前导 `/`、查询串与片段，再做安全路径检查 */
    private fun clean(raw: String): String? {
        val noQuery = raw.substringBefore('#').substringBefore('?')
        return PackageUnpacker.safeRelativePath(noQuery.trimStart('/'))
    }

    private fun attr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "webmanifest" -> "application/json"
        "map" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "txt" -> "text/plain"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

    /**
     * 网页（非拓展自己）能否读取拓展的某个资源。MV2 写法（matches 为空）对所有页面开放；
     * MV3 写法要求请求页面的 URL 落在该条的 matches 里。[pageUrl] 取自可信的请求来源，不是页面自报的。
     */
    fun isWebAccessible(ext: InstalledExtension, rawPath: String, pageUrl: String?): Boolean {
        val path = clean(rawPath) ?: return false
        return ext.spec.webAccessibleResources.any { entry ->
            val allowedPage = entry.matches.isEmpty() || (pageUrl != null && MatchPattern.anyMatches(entry.matches, pageUrl))
            allowedPage && entry.resources.any { globMatches(it, path) }
        }
    }

    internal fun globMatches(glob: String, path: String): Boolean {
        val g = glob.trimStart('/')
        val re = Regex(g.split('*').joinToString(".*") { Regex.escape(it) })
        return re.matches(path)
    }
}
