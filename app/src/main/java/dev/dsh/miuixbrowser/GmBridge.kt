package dev.dsh.miuixbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 用户脚本的原始能力桥：GM_setValue / GM_xmlhttpRequest / GM_notification / GM_openInTab 等。
 * 跨域请求由原生发起，绕开网页的 CORS 限制（与油猴一致）。
 *
 * 注意：addJavascriptInterface 会注入到所有框架，因此 xhr 采用「发起 + 轮询」两步，
 * 结果由发起请求的那个框架自己取走，而不是用 evaluateJavascript 往主框架推。
 */
class GmBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
    /** 主线程。[id] 由桥生成并回传给脚本，之后 [onCloseTab] 用它找回标签；宿主不支持追踪时可忽略 */
    private val onOpenTab: (id: String, url: String, active: Boolean) -> Unit,
    /** 主线程。[onResult] 非空时，宿主必须在下载结束 / 取消后恰好回调一次 */
    private val onDownload: (url: String, name: String, onResult: ((ok: Boolean, error: String?) -> Unit)?) -> Unit =
        { _, _, _ -> },
    private val onCloseTab: (id: String) -> Unit = {},
) {

    private val prefs = context.getSharedPreferences("userscript_gm", Context.MODE_PRIVATE)
    private val pool = Executors.newCachedThreadPool()
    private val mainHandler = android.os.Handler(context.mainLooper)

    private class Pending(val json: String, val at: Long = System.currentTimeMillis())

    private val results = ConcurrentHashMap<String, Pending>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val aborted = ConcurrentHashMap.newKeySet<String>()

    // ------------------------------------------------------------------ values

    @JavascriptInterface
    fun getValue(scriptId: String, key: String): String? = prefs.getString("$scriptId::$key", null)

    @JavascriptInterface
    fun setValue(scriptId: String, key: String, value: String) {
        prefs.edit().putString("$scriptId::$key", value).apply()
    }

    @JavascriptInterface
    fun deleteValue(scriptId: String, key: String) {
        prefs.edit().remove("$scriptId::$key").apply()
    }

    @JavascriptInterface
    fun listValues(scriptId: String): String {
        val prefix = "$scriptId::"
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        return JSONArray(keys).toString()
    }

    /** 批量读取（值变化监听轮询用）：{ name: rawString }，不存在的键不出现在结果里 */
    @JavascriptInterface
    fun getValues(scriptId: String, namesJson: String): String {
        val out = JSONObject()
        val names = runCatching { JSONArray(namesJson) }.getOrNull() ?: return out.toString()
        for (i in 0 until names.length()) {
            val name = names.optString(i)
            prefs.getString("$scriptId::$name", null)?.let { out.put(name, it) }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ GM_xmlhttpRequest

    @JavascriptInterface
    fun xhrStart(reqId: String, optionsJson: String, bodyBase64: String) {
        purgeStaleResults()
        pool.execute {
            val result = runCatching { runXhr(reqId, optionsJson, bodyBase64) }.getOrElse { e ->
                JSONObject().put("state", "error").put("error", e.message ?: "request failed").toString()
            }
            connections.remove(reqId)
            // v2 运行时在 abort 后就停止轮询，不会再来取结果：不存，免得堆积
            if (!aborted.remove(reqId)) results[reqId] = Pending(result)
        }
    }

    /** 还没好返回 null；好了返回 JSON 并清掉 */
    @JavascriptInterface
    fun xhrPoll(reqId: String): String? = results.remove(reqId)?.json

    @JavascriptInterface
    fun xhrAbort(reqId: String) {
        aborted.add(reqId)
        results.remove(reqId) // 结果恰好已出、尚未被取走
        connections[reqId]?.let { conn -> pool.execute { runCatching { conn.disconnect() } } }
        // 请求若已结束，xhrStart 收尾时不会再清这个标记：延迟清掉，避免常驻
        mainHandler.postDelayed({ if (!connections.containsKey(reqId)) aborted.remove(reqId) }, 60_000)
    }

    /** 无人认领（页面已跳转 / 脚本没轮询）的结果 60 秒后丢弃 */
    private fun purgeStaleResults() {
        val now = System.currentTimeMillis()
        results.entries.removeAll { now - it.value.at > 60_000 }
    }

    private fun hasHeader(headers: JSONObject, name: String): Boolean =
        headers.keys().asSequence().any { it.equals(name, ignoreCase = true) }

    private fun runXhr(reqId: String, optionsJson: String, bodyBase64: String): String {
        val opt = JSONObject(optionsJson)
        var method = opt.optString("method", "GET").ifBlank { "GET" }.uppercase()
        val headers = opt.optJSONObject("headers") ?: JSONObject()
        val timeoutMs = opt.optInt("timeout", 0).takeIf { it > 0 } ?: 30_000
        val anonymous = opt.optBoolean("anonymous", false)
        var body: ByteArray? = if (bodyBase64.isNotEmpty()) android.util.Base64.decode(bodyBase64, android.util.Base64.DEFAULT) else null
        var currentUrl = opt.getString("url")
        val cookieManager = CookieManager.getInstance()

        try {
            var redirects = 0
            while (true) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false // 手动跟随，才能处理 http→https 与 Cookie
                    useCaches = false
                }
                connections[reqId] = conn
                if (aborted.contains(reqId)) {
                    conn.disconnect()
                    return JSONObject().put("state", "abort").toString()
                }
                // 让系统自己处理压缩，不接受用户指定的 Accept-Encoding
                headers.keys().forEach { name ->
                    if (!name.equals("Accept-Encoding", true)) conn.setRequestProperty(name, headers.optString(name))
                }
                if (!hasHeader(headers, "User-Agent")) {
                    conn.setRequestProperty("User-Agent", opt.optString("ua", "Mozilla/5.0 Mafari"))
                }
                if (!hasHeader(headers, "Accept")) conn.setRequestProperty("Accept", "*/*")
                if (!hasHeader(headers, "Accept-Language")) {
                    conn.setRequestProperty("Accept-Language", opt.optString("lang", "en-US").ifBlank { "en-US" })
                }
                if (!anonymous && !hasHeader(headers, "Cookie")) {
                    cookieManager.getCookie(currentUrl)?.takeIf { it.isNotBlank() }?.let {
                        conn.setRequestProperty("Cookie", it)
                    }
                }
                val payload = body
                if (payload != null && payload.isNotEmpty() && method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(payload.size)
                    conn.outputStream.use { it.write(payload) }
                }

                val status = conn.responseCode
                if (!anonymous) {
                    conn.headerFields?.forEach { (name, values) ->
                        if (name != null && name.equals("Set-Cookie", true)) {
                            values.forEach { cookieManager.setCookie(currentUrl, it) }
                        }
                    }
                }
                val redirectMode = opt.optString("redirect", "follow")
                if (status in intArrayOf(301, 302, 303, 307, 308) && redirectMode == "error") {
                    conn.disconnect()
                    return JSONObject().put("state", "error").put("error", "redirect not allowed").toString()
                }
                if (status in intArrayOf(301, 302, 303, 307, 308) && redirects < 10 && redirectMode != "manual") {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        redirects++
                        currentUrl = URL(URL(currentUrl), location).toString()
                        if (status == 303 || ((status == 301 || status == 302) && method == "POST")) {
                            method = "GET"
                            body = null
                        }
                        conn.disconnect()
                        continue
                    }
                }

                val stream = if (status in 200..399) conn.inputStream else conn.errorStream
                val bytes = ByteArrayOutputStream()
                stream?.use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes.write(buf, 0, n)
                    }
                }
                val rawHeaders = StringBuilder()
                conn.headerFields?.forEach { (name, values) ->
                    if (name != null) values.forEach { rawHeaders.append(name).append(": ").append(it).append("\r\n") }
                }
                val out = JSONObject()
                    .put("state", "load")
                    .put("status", status)
                    .put("statusText", conn.responseMessage ?: "")
                    .put("finalUrl", currentUrl)
                    .put("headers", rawHeaders.toString())
                    .put("body", android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP))
                    .toString()
                conn.disconnect()
                return out
            }
        } catch (e: SocketTimeoutException) {
            return JSONObject().put("state", "timeout").put("error", "timeout").toString()
        }
    }

    // ------------------------------------------------------------------ 流式写文件（GM.openFileStream）
    // WebView 的 showSaveFilePicker 在部分环境不弹选择器；脚本可改用原生流式写入 Download/mafari，
    // 逐段写盘、不占内存，适合 M3U8 等大文件。只允许写入 Download/mafari，文件名会被净化。

    private class FileStream(val uri: android.net.Uri, val out: java.io.OutputStream)

    private val streams = ConcurrentHashMap<String, FileStream>()

    @JavascriptInterface
    fun streamOpen(name: String, mime: String): String = runCatching {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trim('.').take(120)
            .ifBlank { "download_${System.currentTimeMillis()}" }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/mafari")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("insert failed")
        val out = resolver.openOutputStream(uri, "w") ?: run { resolver.delete(uri, null, null); error("open failed") }
        val id = java.util.UUID.randomUUID().toString()
        streams[id] = FileStream(uri, out.buffered(256 * 1024))
        val actual = resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: safe
        JSONObject().put("id", id).put("name", actual).toString()
    }.getOrElse { JSONObject().put("error", it.message ?: "error").toString() }

    /** 返回空串表示成功，否则为错误信息 */
    @JavascriptInterface
    fun streamWrite(id: String, base64: String): String = runCatching {
        val s = streams[id] ?: error("stream closed")
        s.out.write(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        ""
    }.getOrElse { it.message ?: "write failed" }

    @JavascriptInterface
    fun streamClose(id: String): String = runCatching {
        val s = streams.remove(id) ?: error("stream closed")
        s.out.flush(); s.out.close()
        context.contentResolver.update(
            s.uri,
            android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) },
            null, null,
        )
        ""
    }.getOrElse { it.message ?: "close failed" }

    @JavascriptInterface
    fun streamAbort(id: String) {
        val s = streams.remove(id) ?: return
        runCatching { s.out.close() }
        runCatching { context.contentResolver.delete(s.uri, null, null) }
    }

    // ------------------------------------------------------------------ misc

    @JavascriptInterface
    fun toast(message: String) {
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isHttp(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    /** 旧接口：不回传 id（保留给仍调用单参数版本的老运行时 / 回退路径） */
    @JavascriptInterface
    fun openTab(url: String) {
        openTab(url, "{}")
    }

    /** v2：返回标签 id，之后可 [closeTab]。只允许 http(s)。 */
    @JavascriptInterface
    fun openTab(url: String, optionsJson: String): String {
        if (!isHttp(url)) return ""
        val active = runCatching { JSONObject(optionsJson).optBoolean("active", true) }.getOrDefault(true)
        val id = java.util.UUID.randomUUID().toString()
        mainHandler.post { onOpenTab(id, url, active) }
        return id
    }

    @JavascriptInterface
    fun closeTab(id: String) {
        mainHandler.post { onCloseTab(id) }
    }

    /** 旧接口：发起即返回，没有结果回调 */
    @JavascriptInterface
    fun download(url: String, name: String) {
        if (!isHttp(url)) return
        mainHandler.post { onDownload(url, name, null) }
    }

    /** v2：结果经 window.__mafariDlCallback_<cbId>(ok, err) 回传（仅主框架能收到，见 [callJs]） */
    @JavascriptInterface
    fun downloadAsync(url: String, name: String, cbId: String) {
        if (!isHttp(url) || !SAFE_ID.matches(cbId)) {
            callJs("__mafariDlCallback_$cbId", "false,'unsupported url'")
            return
        }
        mainHandler.post {
            var called = false
            onDownload(url, name) { ok, err ->
                if (!called) {
                    called = true
                    callJs(
                        "__mafariDlCallback_$cbId",
                        if (ok) "true" else "false," + JSONObject.quote(err ?: "download failed"),
                    )
                }
            }
        }
    }

    @JavascriptInterface
    fun copy(text: String) {
        mainHandler.post {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Mafari", text))
            }
        }
    }

    @JavascriptInterface
    fun copyHtml(html: String) {
        mainHandler.post {
            runCatching {
                val plain = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newHtmlText("Mafari", plain, html))
            }
        }
    }

    // ------------------------------------------------------------------ GM_notification

    /**
     * 回调经 window.__mafariNtfCallback_<id>(kind) 回传，kind = click | close | timeout。
     * 不能发通知（权限被拒）时抛异常：运行时会退回 toast，且不会伪造 ondone。
     */
    @JavascriptInterface
    fun notification(json: String, cbId: String) {
        if (!SAFE_ID.matches(cbId)) error("bad callback id")
        val o = JSONObject(json)
        GmNotifications.post(
            context = context,
            title = o.optString("title"),
            text = o.optString("text"),
            tag = o.optString("tag"),
            timeoutMs = o.optLong("timeout", 0L),
            silent = o.optBoolean("silent", false),
        ) { kind -> callJs("__mafariNtfCallback_$cbId", JSONObject.quote(kind)) }
    }

    // ------------------------------------------------------------------ JS 回调

    /**
     * 往「主框架」调一个一次性全局回调。addJavascriptInterface 对每个框架都有效，
     * 但 evaluateJavascript 只能进主框架：在 iframe 里跑的脚本拿不到下载 / 通知的结果回调
     * （xhr 不受影响，它走轮询）。
     */
    private fun callJs(fn: String, args: String) {
        if (!SAFE_ID.matches(fn)) return
        mainHandler.post {
            runCatching {
                webViewProvider()?.evaluateJavascript(
                    "(function(){try{var f=window.$fn;if(typeof f==='function')f($args)}catch(e){}})()",
                    null,
                )
            }
        }
    }

    @JavascriptInterface
    fun cookies(url: String): String =
        runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault("")

    @JavascriptInterface
    fun setCookie(url: String, cookie: String) {
        runCatching { CookieManager.getInstance().setCookie(url, cookie) }
    }

    /**
     * GM_cookie.list：CookieManager 只给 name=value（含 HttpOnly，但没有 path / 过期等属性），
     * 属性按 Cookie 请求头的语义填默认值，与脚本能拿到的信息一致。
     */
    @JavascriptInterface
    fun cookieList(url: String): String {
        val arr = JSONArray()
        if (!isHttp(url)) return arr.toString()
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        val secure = url.startsWith("https://", true)
        CookieManager.getInstance().getCookie(url).orEmpty().split(";").forEach { kv ->
            val pair = kv.trim()
            if (pair.isEmpty()) return@forEach
            val i = pair.indexOf('=')
            arr.put(
                JSONObject()
                    .put("name", if (i < 0) pair else pair.substring(0, i))
                    .put("value", if (i < 0) "" else pair.substring(i + 1))
                    .put("domain", host).put("hostOnly", true).put("path", "/")
                    .put("secure", secure).put("httpOnly", false).put("session", true),
            )
        }
        return arr.toString()
    }

    /** GM_cookie.set / delete（delete 以 expirationDate = 0 表示） */
    @JavascriptInterface
    fun cookieSet(json: String) {
        runCatching {
            val o = JSONObject(json)
            val url = o.getString("url")
            val name = o.getString("name")
            if (!isHttp(url) || name.isBlank() || name.any { it == ';' || it == '=' || it.isWhitespace() }) return
            val value = o.optString("value").replace(";", "%3B")
            val sb = StringBuilder("$name=$value")
            sb.append("; Path=").append(o.optString("path").ifBlank { "/" }.replace(";", ""))
            o.optString("domain").takeIf { it.isNotBlank() && it != "null" }?.let { sb.append("; Domain=").append(it.replace(";", "")) }
            if (o.optBoolean("secure", false)) sb.append("; Secure")
            if (o.optBoolean("httpOnly", false)) sb.append("; HttpOnly")
            o.optString("sameSite").takeIf { it in setOf("Strict", "Lax", "None") }?.let { sb.append("; SameSite=").append(it) }
            if (o.has("expirationDate") && !o.isNull("expirationDate")) {
                val exp = o.optLong("expirationDate", -1L)
                if (exp >= 0) {
                    val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
                    sb.append("; Expires=").append(fmt.format(java.util.Date(exp * 1000)))
                }
            }
            val cm = CookieManager.getInstance()
            cm.setCookie(url, sb.toString())
            cm.flush()
        }
    }

    private companion object {
        /** 页面能随便调桥：回调函数名只接受这个字符集，杜绝拼进 evaluateJavascript 的注入 */
        val SAFE_ID = Regex("[A-Za-z0-9_]{1,80}")
    }
}
