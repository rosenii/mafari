/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import android.content.Context
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.util.Diag

/**
 * 标签页的 blob / data URL 抽取（从 BrowserTab 拆出）：持有分片会话和 JS 桥，
 * 结果仍通过 [BrowserTab] 上的 onBlobReady / onBlobStart / onBlobError 回调交给界面。
 */
internal class TabBlob(private val tab: BrowserTab) {

    /**
     * blob 分片接收：evaluateJavascript 返回值有大小上限，大文件必须走 JS→桥接分片。
     *
     * 每次 [extractBlob] 在原生侧创建一个独立会话（一次性 token + 独立缓存文件）：
     * - 桥接方法全部要求 token，网页自己调用 MafariBlob 拿不到 token，无法伪造下载或写缓存文件；
     * - 多个 blob 并发抽取互不干扰；
     * - 写入出错会记入会话，end 时校验总字节数，残缺文件不会被当成成功。
     */
    private class BlobSession(
        val file: java.io.File,
        val stream: java.io.FileOutputStream,
        var name: String,
        var mime: String?,
    ) {
        /** 文件名来自页面（download 属性 / File.name）：保存时不得再改名 */
        var keepName = false
        var written = 0L
        var failed = false
        var finished = false
    }

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, BlobSession>()

    fun discard(token: String) {
        val session = sessions.remove(token) ?: return
        synchronized(session) {
            runCatching { session.stream.close() }
            runCatching { session.file.delete() }
        }
    }

    fun discardAll() {
        sessions.keys.toList().forEach { discard(it) }
    }

    private fun reportError(message: String) {
        tab.webView.post { tab.onBlobError?.invoke(message) }
    }

    /** 以 "MafariBlob" 名挂到 WebView 的 JS 桥 */
    val bridge = object {
        /** 返回 false 表示原生侧已拒绝（token 无效 / 空间不足），JS 端应直接停止。 */
        @android.webkit.JavascriptInterface
        fun begin(token: String, mime: String, size: Double, pageName: String): Boolean {
            val session = sessions[token] ?: return false
            if (size > 0 && size > tab.appContext.cacheDir.usableSpace - MIN_FREE_BYTES) {
                discard(token)
                reportError(tr("存储空间不足，无法读取该文件"))
                return false
            }
            if (mime.isNotBlank()) session.mime = mime
            if (pageName.isNotBlank()) {
                session.name = Downloader.originalName(pageName)
                session.keepName = true
            }
            if (size >= LARGE_BLOB_NOTICE_BYTES) {
                val label = session.name
                tab.webView.post { tab.onBlobStart?.invoke(label) }
            }
            return true
        }

        @android.webkit.JavascriptInterface
        fun appendChunk(token: String, b64: String) {
            val session = sessions[token] ?: return
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                synchronized(session) {
                    if (session.finished || session.failed) return
                    session.stream.write(bytes)
                    session.written += bytes.size
                }
            } catch (e: Exception) {
                session.failed = true
                Diag.log("blob append failed: ${e.message}")
            }
        }

        @android.webkit.JavascriptInterface
        fun end(token: String, total: Double) {
            val session = sessions.remove(token) ?: return
            val ok: Boolean
            synchronized(session) {
                session.finished = true
                runCatching { session.stream.flush(); session.stream.close() }
                ok = !session.failed && session.written > 0L &&
                    (total < 0 || session.written == total.toLong()) &&
                    session.file.exists() && session.file.length() == session.written
            }
            if (!ok) {
                runCatching { session.file.delete() }
                reportError(tr("文件读取不完整或写入失败"))
                return
            }
            val name = if (session.keepName) session.name else Downloader.withExtension(session.name, session.mime)
            val mime = session.mime
            val path = session.file.absolutePath
            tab.webView.post { tab.onBlobReady?.invoke(null, name, mime, path) }
        }

        @android.webkit.JavascriptInterface
        fun fail(token: String, msg: String) {
            if (!sessions.containsKey(token)) return
            discard(token)
            reportError(tr("无法读取 blob：{0}", msg))
        }
    }

    /**
     * 把 blob 分片写入缓存文件（不经 evaluateJavascript 返回大字符串）。
     * 取 Blob 顺序：createObjectURL 缓存（含同源 iframe）→ XHR → fetch。
     * 完成后回调 onBlobReady(tempPath=...)。
     */
    fun extract(
        url: String,
        userAgent: String? = null,
        disposition: String? = null,
        mime: String? = null,
    ) {
        val fileName = Downloader.fileNameFrom(url, disposition)
        val preferredMime = mime ?: "application/octet-stream"
        val token = java.util.UUID.randomUUID().toString()
        val file = java.io.File(tab.appContext.cacheDir, "mafari_blob_${token}.part")
        val stream = runCatching { java.io.FileOutputStream(file) }.getOrElse {
            reportError(tr("无法创建缓存文件"))
            return
        }
        sessions[token] = BlobSession(file, stream, fileName, mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" })
        // 分片 256KB 二进制 → base64 约 350KB，避开 JS 桥接/字符串上限
        val js = """
            (function(){
              var B = window.MafariBlob;
              var token = ${org.json.JSONObject.quote(token)};
              var url = ${org.json.JSONObject.quote(url)};
              var preferredMime = ${org.json.JSONObject.quote(preferredMime)};
              var CHUNK = 256 * 1024;
              if (!B) return;

              function findCached(u) {
                try {
                  if (window.__mafariBlobCache && window.__mafariBlobCache[u]) {
                    return window.__mafariBlobCache[u];
                  }
                } catch (e) {}
                try {
                  var list = document.querySelectorAll('iframe');
                  for (var i = 0; i < list.length; i++) {
                    try {
                      var w = list[i].contentWindow;
                      if (w && w.__mafariBlobCache && w.__mafariBlobCache[u]) {
                        return w.__mafariBlobCache[u];
                      }
                    } catch (e) {}
                  }
                } catch (e) {}
                return null;
              }

              function viaXhr(u) {
                return new Promise(function(resolve, reject) {
                  try {
                    var x = new XMLHttpRequest();
                    x.open('GET', u, true);
                    x.responseType = 'blob';
                    x.onload = function() {
                      if (x.response && x.response.size >= 0) resolve(x.response);
                      else reject(new Error('xhr empty'));
                    };
                    x.onerror = function() { reject(new Error('xhr error')); };
                    x.send();
                  } catch (e) { reject(e); }
                });
              }

              function viaFetch(u) {
                return fetch(u).then(function(r){ return r.blob(); });
              }

              function getBlob(u) {
                var c = findCached(u);
                if (c) return Promise.resolve(c);
                return viaXhr(u).catch(function(){ return viaFetch(u); });
              }

              function findName(u, b) {
                try {
                  if (window.__mafariBlobNames && window.__mafariBlobNames[u]) return window.__mafariBlobNames[u];
                } catch (e) {}
                try {
                  var list = document.querySelectorAll('iframe');
                  for (var i = 0; i < list.length; i++) {
                    try {
                      var w = list[i].contentWindow;
                      if (w && w.__mafariBlobNames && w.__mafariBlobNames[u]) return w.__mafariBlobNames[u];
                    } catch (e) {}
                  }
                } catch (e) {}
                try { if (b && b.name) return String(b.name); } catch (e) {}
                return '';
              }

              getBlob(url).then(function(b){
                if (!b || !b.size) throw new Error('empty blob');
                if (!B.begin(token, b.type || preferredMime, b.size, findName(url, b))) return;
                var i = 0;
                function next(){
                  if (i >= b.size) { B.end(token, b.size); return; }
                  var end = Math.min(i + CHUNK, b.size);
                  var slice = b.slice(i, end);
                  i = end;
                  return slice.arrayBuffer().then(function(buf){
                    var bytes = new Uint8Array(buf);
                    var bin = '';
                    var BL = 0x8000;
                    for (var j = 0; j < bytes.length; j += BL) {
                      bin += String.fromCharCode.apply(null, bytes.subarray(j, j + BL));
                    }
                    B.appendChunk(token, btoa(bin));
                    return next();
                  });
                }
                return next();
              }).catch(function(e){ B.fail(token, String(e && e.message || e)); });
            })()
        """.trimIndent()
        // 结果由 [bridge] 回调，这里不解析返回值
        tab.evaluateJs(js, null)
    }

    companion object {
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val LARGE_BLOB_NOTICE_BYTES = 8L * 1024 * 1024

        @Volatile
        private var staleFilesCleaned = false

        /** 进程内第一次创建标签页时，清掉上次进程被杀 / 页面中途跳转遗留的 blob 缓存文件 */
        fun cleanStaleFiles(context: Context) {
            if (staleFilesCleaned) return
            staleFilesCleaned = true
            // 扫缓存目录是磁盘 IO：放后台线程，别压在首个标签创建的主线程上
            val dir = context.applicationContext.cacheDir
            Thread({
                runCatching {
                    dir.listFiles { f -> f.name.startsWith("mafari_blob_") }?.forEach { it.delete() }
                }
            }, "mafari-blob-clean").apply { isDaemon = true }.start()
        }
    }
}
