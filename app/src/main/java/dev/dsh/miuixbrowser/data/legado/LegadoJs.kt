/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data.legado

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
    fun htmlFormat(s: String): String = dev.dsh.miuixbrowser.data.BookContent.htmlToParagraphs(s).joinToString("\n")

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

    // ---- 不支持
    fun startBrowser(url: String, title: String): Nothing = throw UnsupportedOperationException("startBrowser")

    fun startBrowserAwait(url: String, title: String): Nothing = throw UnsupportedOperationException("startBrowserAwait")

    fun webView(html: String?, url: String?, js: String?): Nothing = throw UnsupportedOperationException("webView")

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
