/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

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
 *   并先加载垫片：同步 XHR 实现的 importScripts、ServiceWorkerGlobalScope 的形状（registration / clients / skipWaiting）、
 *   页面 load 后触发 install → activate。休眠、fetch 拦截不模拟：脚本一直活着。
 *
 * 已知限制：manifest 里的 content_security_policy 目前不执行（响应不带 CSP 头），importScripts 垫片依赖 eval。
 */
object ExtensionPages {
    const val GENERATED_BACKGROUND = "_generated_background_page.html"
    const val SW_SHIM = "_mafari/sw_shim.js"

    private const val SW_SHIM_JS = """(function () {
  var g = self;
  var P = Promise;
  function def(k, v) {
    try { if (!(k in g)) Object.defineProperty(g, k, { value: v, configurable: true, writable: true, enumerable: false }); } catch (e) { }
  }
  function err(m) { try { console.error('[mafari] ' + m); } catch (e) { } }

  // importScripts：同步 XHR + 全局 eval（隐藏页面里没有真正的 worker 作用域）
  g.importScripts = function () {
    for (var i = 0; i < arguments.length; i++) {
      var url = new URL(arguments[i], g.location.href).href;
      var x = new XMLHttpRequest();
      x.open('GET', url, false);
      x.send();
      if (x.status < 200 || x.status >= 300) throw new Error('importScripts failed: ' + arguments[i]);
      (0, eval)(x.responseText + '\n//# sourceURL=' + url);
    }
  };

  // ServiceWorkerGlobalScope 的形状：让 self instanceof ServiceWorkerGlobalScope / self.registration / self.clients 等探测通过。
  // 休眠、fetch 拦截、clients 窗口管理不模拟：脚本一直活着，clients.matchAll 返回空。
  function SWGS() { }
  try { Object.defineProperty(SWGS, Symbol.hasInstance, { value: function (o) { return o === g; } }); } catch (e) { }
  def('ServiceWorkerGlobalScope', SWGS);
  var sw = {
    state: 'activated',
    scriptURL: String(g.location.href),
    addEventListener: function () { },
    removeEventListener: function () { },
    postMessage: function () { }
  };
  def('serviceWorker', sw);
  def('registration', {
    scope: g.location.origin + '/',
    active: sw, installing: null, waiting: null,
    update: function () { return P.resolve(); },
    unregister: function () { return P.resolve(false); },
    addEventListener: function () { },
    removeEventListener: function () { },
    navigationPreload: {
      enable: function () { return P.resolve(); }, disable: function () { return P.resolve(); },
      setHeaderValue: function () { return P.resolve(); }, getState: function () { return P.resolve({ enabled: false, headerValue: 'true' }); }
    },
    showNotification: function () { return P.reject(new Error('registration.showNotification is not supported by Mafari WebView')); },
    getNotifications: function () { return P.resolve([]); }
  });
  def('skipWaiting', function () { return P.resolve(); });
  def('clients', {
    claim: function () { return P.resolve(); },
    matchAll: function () { return P.resolve([]); },
    get: function () { return P.resolve(undefined); },
    openWindow: function () { return P.reject(new Error('clients.openWindow is not supported by Mafari WebView')); }
  });

  // install / activate：脚本顶层注册的监听器记下来，页面 load 之后依次触发（等 waitUntil）。其它事件原样交给浏览器。
  var swListeners = { install: [], activate: [] };
  var nativeAdd = g.addEventListener.bind(g);
  g.addEventListener = function (type, fn, opts) {
    if ((type === 'install' || type === 'activate') && fn) { swListeners[type].push(fn); return; }
    return nativeAdd(type, fn, opts);
  };
  function fire(type) {
    var waits = [];
    var ev = new Event(type);
    ev.waitUntil = function (p) { waits.push(P.resolve(p)); };
    var fns = swListeners[type].slice();
    var h = g['on' + type];
    if (typeof h === 'function') fns.push(h);
    fns.forEach(function (f) {
      try {
        if (typeof f === 'function') f.call(g, ev);
        else if (f && typeof f.handleEvent === 'function') f.handleEvent(ev);
      } catch (e) { err('sw ' + type + ' listener threw: ' + (e && e.stack || e)); }
    });
    return P.all(waits).catch(function (e) { err('sw ' + type + ' waitUntil rejected: ' + (e && e.stack || e)); });
  }
  var booted = false;
  function boot() {
    if (booted) return;
    booted = true;
    fire('install').then(function () { return fire('activate'); });
  }
  if (document.readyState === 'complete') setTimeout(boot, 0);
  else nativeAdd('load', function () { setTimeout(boot, 0); });
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
            // 模块 worker 里没有 importScripts，但 ServiceWorkerGlobalScope 的形状 / install、activate 事件同样需要，所以都加载垫片
            sb.append("<script src=\"/").append(attr(SW_SHIM)).append("\"></script>\n")
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
