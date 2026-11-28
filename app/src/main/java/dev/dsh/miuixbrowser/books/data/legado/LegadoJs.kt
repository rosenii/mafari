/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data.legado

import dev.dsh.miuixbrowser.i18n.tr
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrapFactory
import org.mozilla.javascript.Wrapper
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/** 脚本跑得太久（死循环）时抛出。继承 Error：Rhino 不会把它当成 JS 异常交给脚本的 try/catch 吞掉。 */
class LegadoJsTimeout : Error("脚本执行超时")

/**
 * 执行书源里的 `@js:` / `<js>` / `{{ }}`。Rhino 在 Android 上只能走解释器（optimizationLevel = -1）。
 * 每次求值新建一个全局作用域（不共享状态，多线程安全），注入 Legado 书源常用的对象：
 * `java`（[LegadoJavaBridge]）、`cookie`、`source`、`cache`，以及 `result` / `baseUrl` / `key` / `page` 等变量。
 * `Packages.java.*` / `Packages.android.*` 照常可用（书源会直接 `Packages.java.lang.Thread.sleep`、`Packages.android.graphics.BitmapFactory`）。
 */
internal class LegadoJs(private val engine: LegadoEngine) {

    fun eval(script: String, vars: Map<String, Any?>): Any? {
        val cx = Factory.enterContext()
        try {
            cx.putThreadLocal(START_KEY, System.currentTimeMillis())
            cx.applicationClassLoader = LegadoJs::class.java.classLoader
            val scope = cx.initStandardObjects()
            val sourceBridge = LegadoSourceBridge(engine)
            bind(scope, "java", LegadoJavaBridge(engine))
            bind(scope, "cookie", LegadoCookieBridge(engine))
            bind(scope, "source", sourceBridge)
            bind(scope, "cache", sourceBridge)
            for ((k, v) in DEFAULT_VARS) if (k !in vars) bind(scope, k, v)
            if ("book" !in vars) bind(scope, "book", LegadoBook("", "", ""))
            if ("chapter" !in vars) bind(scope, "chapter", LegadoChapter(vars["title"]?.toString().orEmpty(), ""))
            for ((k, v) in vars) bind(scope, k, v)
            engine.source.jsLib?.takeIf { it.isNotBlank() }?.let { cx.evaluateString(scope, it, "jsLib", 1, null) }
            return toKotlin(cx.evaluateString(scope, script, "rule", 1, null), cx, scope)
        } catch (e: RhinoException) {
            throw LegadoException(tr("书源脚本出错：{0}", e.details() + "（第 ${e.lineNumber()} 行）"), e)
        } finally {
            Context.exit()
        }
    }

    private fun bind(scope: Scriptable, name: String, value: Any?) {
        ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope))
    }

    /** JS 值 → Kotlin：数组 / java.util.List → List；数字整数值去 ".0"；对象 → JSON 文本；undefined / null → null。 */
    private fun toKotlin(v: Any?, cx: Context, scope: Scriptable): Any? = when {
        v == null || v === Undefined.instance -> null
        v is Wrapper -> toKotlin(v.unwrap(), cx, scope)
        v is CharSequence -> v.toString()
        v is Double -> if (!v.isNaN() && !v.isInfinite() && v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong() else v
        v is Number || v is Boolean -> v
        v is List<*> -> v.map { toKotlin(it, cx, scope) }
        v is Scriptable -> NativeJSON.stringify(cx, scope, v, null, null)?.toString()
        else -> v
    }

    private object Factory : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 50_000
            cx.wrapFactory = NoPrimitiveWrap
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val start = cx.getThreadLocal(START_KEY) as? Long ?: return
            if (System.currentTimeMillis() - start > TIMEOUT_MS) throw LegadoJsTimeout()
        }
    }

    /** Java 方法返回的 String / Number 直接当 JS 原始值（默认会包成 NativeJavaObject，`x.length` 之类就不对了）。 */
    private object NoPrimitiveWrap : WrapFactory() {
        init {
            isJavaPrimitiveWrap = false
        }
    }

    private companion object {
        /** Legado 脚本里始终存在的变量（没有上下文时给空值 / 第 1 页，免得出现 “xxx 未定义”）。 */
        val DEFAULT_VARS: Map<String, Any?> = mapOf(
            "page" to 1,
            "key" to "",
            "result" to null,
            "baseUrl" to "",
            "title" to "",
            "src" to "",
            "nextChapterUrl" to "",
        )
        const val START_KEY = "legado.js.start"
        const val TIMEOUT_MS = 90_000L
    }
}

/**
 * 书源脚本里的 `java` 对象（Legado 的 JsExtensions 子集）。方法必须是 public 且不用默认参数：Rhino 按反射找方法。
 * 不支持的（WebView、登录界面等）会抛出明确的错误，由书源自己的 try/catch 处理。
 */
class LegadoJavaBridge(private val engine: LegadoEngine) {

    // ---- 网络
    fun ajax(url: Any?): String = engine.fetchForScript(url.toString(), null).body()

    fun get(url: String, headers: Any?): LegadoResponse = engine.fetchForScript(url, null, headersOf(headers))

    fun post(url: String, body: String, headers: Any?): LegadoResponse =
        engine.fetchForScript(url, body, headersOf(headers), method = "POST")

    fun connect(url: String): LegadoResponse = engine.fetchForScript(url, null)

    /** 下载原始字节到临时文件并返回路径（二进制别用 ajax：它会按文本解码、把数据弄坏）。 */
    fun downloadFile(url: String): String {
        val res = engine.fetchForScript(url, null)
        if (res.status >= 400) throw java.io.IOException("HTTP ${res.status}")
        val f = File.createTempFile("legado", ".bin", engine.tmpDir())
        f.writeBytes(res.bytes)
        return f.absolutePath
    }

    // ---- 文件（只允许读写本引擎的临时目录）
    fun readFile(path: String): ByteArray? = tmpFile(path)?.takeIf { it.isFile }?.readBytes()

    fun readTxtFile(path: String): String = tmpFile(path)?.takeIf { it.isFile }?.readText().orEmpty()

    fun deleteFile(path: String): Boolean = tmpFile(path)?.delete() ?: false

    private fun tmpFile(path: String): File? {
        val f = File(path).canonicalFile
        val root = engine.tmpDir().canonicalFile
        return if (f.path.startsWith(root.path + File.separator)) f else null
    }

    // ---- 编码 / 摘要
    fun base64Decode(s: String): String = String(base64DecodeToByteArray(s), Charsets.UTF_8)

    fun base64DecodeToByteArray(s: String): ByteArray = Base64.getMimeDecoder().decode(s)

    fun base64Encode(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    fun md5Encode(s: String): String = hex(MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)))

    fun md5Encode16(s: String): String = md5Encode(s).substring(8, 24)

    fun encodeURI(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun encodeURI(s: String, charset: String): String = URLEncoder.encode(s, charset)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    // ---- 文本
    fun htmlFormat(s: String): String = dev.dsh.miuixbrowser.books.data.BookContent.htmlToParagraphs(s).joinToString("\n")

    fun t2s(s: String): String = s

    fun s2t(s: String): String = s

    fun randomUUID(): String = java.util.UUID.randomUUID().toString()

    fun timeFormat(time: Any?): String {
        val ms = (time as? Number)?.toLong() ?: time.toString().toLongOrNull() ?: return ""
        return java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }

    // ---- 提示 / 日志
    fun log(msg: Any?): Any? {
        engine.log(msg.toString())
        return msg
    }

    fun toast(msg: Any?) = engine.log("toast: $msg")

    fun longToast(msg: Any?) = engine.log("toast: $msg")

    // ---- 变量（书源级，存在内存里）
    fun put(key: String, value: String): String {
        engine.store[key] = value
        return value
    }

    fun get(key: String): String = engine.store[key].orEmpty()

    // ---- 加解密 / 摘要（Legado JsExtensions 里书源最常用的一批；hutool 的 SymmetricCrypto 用 [LegadoSymmetricCrypto] 代替）
    fun createSymmetricCrypto(transformation: String, key: Any?, iv: Any?): LegadoSymmetricCrypto =
        LegadoSymmetricCrypto(transformation, bytesOf(key), iv?.let { bytesOf(it) })

    fun createSymmetricCrypto(transformation: String, key: Any?): LegadoSymmetricCrypto =
        LegadoSymmetricCrypto(transformation, bytesOf(key), null)

    fun aesBase64DecodeToString(str: String, key: String, transformation: String, iv: String): String =
        String(LegadoSymmetricCrypto(transformation, key.toByteArray(), iv.toByteArray()).decrypt(str), Charsets.UTF_8)

    fun aesBase64DecodeToByteArray(str: String, key: String, transformation: String, iv: String): ByteArray =
        LegadoSymmetricCrypto(transformation, key.toByteArray(), iv.toByteArray()).decrypt(str)

    fun aesEncodeToBase64String(data: String, key: String, transformation: String, iv: String): String =
        LegadoSymmetricCrypto(transformation, key.toByteArray(), iv.toByteArray()).encryptBase64(data)

    fun digestHex(data: String, algorithm: String): String =
        hex(MessageDigest.getInstance(normAlgo(algorithm)).digest(data.toByteArray(Charsets.UTF_8)))

    fun digestBase64Str(data: String, algorithm: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance(normAlgo(algorithm)).digest(data.toByteArray(Charsets.UTF_8)))

    fun HMacHex(data: String, algorithm: String, key: String): String = hex(hmac(data, algorithm, key))

    fun HMacBase64(data: String, algorithm: String, key: String): String =
        Base64.getEncoder().encodeToString(hmac(data, algorithm, key))

    private fun hmac(data: String, algorithm: String, key: String): ByteArray {
        val name = algorithm.replace("-", "").let { if (it.startsWith("Hmac", true)) it else "Hmac$it" }
        val mac = javax.crypto.Mac.getInstance(name)
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), name))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun normAlgo(a: String): String = when (a.uppercase().replace("-", "")) {
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        else -> a
    }

    // ---- 十六进制 / 字节
    fun hexDecodeToString(s: String): String = String(hexDecodeToByteArray(s), Charsets.UTF_8)

    fun hexDecodeToByteArray(s: String): ByteArray = LegadoSymmetricCrypto.unhex(s)

    fun hexEncodeToString(s: String): String = hex(s.toByteArray(Charsets.UTF_8))

    fun base64Decode(s: String, charset: String): String = String(base64DecodeToByteArray(s), java.nio.charset.Charset.forName(charset))

    fun strToBytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun strToBytes(s: String, charset: String): ByteArray = s.toByteArray(java.nio.charset.Charset.forName(charset))

    fun bytesToStr(b: ByteArray): String = String(b, Charsets.UTF_8)

    fun bytesToStr(b: ByteArray, charset: String): String = String(b, java.nio.charset.Charset.forName(charset))

    fun utf8ToGbk(s: String): String = String(s.toByteArray(java.nio.charset.Charset.forName("GBK")), java.nio.charset.Charset.forName("GBK"))

    fun androidId(): String = "0000000000000000"

    fun getWebViewUA(): String = LegadoWeb.userAgent()

    /** 依次请求一批地址（Legado 是并发；这里串行，书源自己有访问频率限制）。 */
    fun ajaxAll(urls: Array<String>): Array<LegadoResponse> = Array(urls.size) { engine.fetchForScript(urls[it], null) }

    // ---- 浏览器：用不可见的 WebView 加载页面（没有人机交互界面，验证码要人点的页面做不了，但靠 JS 渲染 / 过 5 秒盾的页面可以）
    /** 打开网页让用户操作：这里没有独立的浏览器界面，只记录日志，脚本继续往下跑。 */
    fun startBrowser(url: String, title: String) {
        engine.log("startBrowser: $url")
    }

    /** 加载 [url] 并返回渲染后的页面（body 是 HTML，url 是最终地址）。 */
    fun startBrowserAwait(url: String, title: String): LegadoResponse = startBrowserAwait(url, title, true)

    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): LegadoResponse {
        val abs = LegadoAnalyzer.absUrl(engine.source.url, url)
        val r = LegadoWeb.load(abs, null, null, engine.jar.header(abs))
        return LegadoResponse(r.url, 200, r.result.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8", emptyMap(), "UTF-8")
    }

    /** 加载 [html]（为空则加载 [url]），执行 [js]（为空则返回整页 HTML）并返回结果文本。 */
    fun webView(html: String?, url: String?, js: String?): String {
        val base = url?.let { LegadoAnalyzer.absUrl(engine.source.url, it) }
        if (html.isNullOrEmpty() && base == null) throw IllegalArgumentException("webView: html 和 url 不能同时为空")
        return LegadoWeb.load(base, html, js, base?.let { engine.jar.header(it) }).result
    }

    private fun bytesOf(v: Any?): ByteArray = when (v) {
        is ByteArray -> v
        is CharSequence -> v.toString().toByteArray(Charsets.UTF_8)
        is List<*> -> ByteArray(v.size) { (v[it] as Number).toByte() }
        null -> ByteArray(0)
        else -> v.toString().toByteArray(Charsets.UTF_8)
    }

    private fun headersOf(h: Any?): Map<String, String> {
        val m = h as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in m) if (k != null && v != null) out[k.toString()] = v.toString()
        return out
    }
}

/** 书源脚本里的 `cookie` 对象：和书源自己的 Cookie 罐打通。 */
class LegadoCookieBridge(private val engine: LegadoEngine) {
    fun setCookie(url: String, cookie: String) = engine.jar.set(url, cookie)

    fun getCookie(url: String): String = engine.jar.header(url).orEmpty()

    fun getCookie(url: String, key: String): String {
        val all = engine.jar.header(url).orEmpty()
        return all.split(';').map { it.trim() }.firstOrNull { it.startsWith("$key=") }?.substringAfter('=').orEmpty()
    }

    fun removeCookie(url: String) = engine.jar.remove(url)

    fun replaceCookie(url: String, cookie: String) {
        engine.jar.remove(url)
        engine.jar.set(url, cookie)
    }
}

/** 书源脚本里的 `source` / `cache` 对象：`get / put` 变量，书源信息只读。 */
class LegadoSourceBridge(private val engine: LegadoEngine) {
    fun get(key: String): String = engine.store[key].orEmpty()

    fun put(key: String, value: String): String {
        engine.store[key] = value
        return value
    }

    fun getVariable(): String = engine.store["variable"].orEmpty()

    fun setVariable(v: String) {
        engine.store["variable"] = v
    }

    fun getKey(): String = engine.source.url

    fun getTag(): String = engine.source.name
}
