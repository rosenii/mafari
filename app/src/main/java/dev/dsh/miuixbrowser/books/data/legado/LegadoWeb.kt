/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data.legado

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 书源脚本里 `java.startBrowserAwait / java.webView` 用的不可见 WebView。
 * 必须从非主线程调用（书源引擎本来就跑在 IO 线程）：WebView 在主线程创建、加载，调用线程阻塞等结果。
 * 页面加载完后会再等一会儿，等 JS 渲染 / “正在检查浏览器” 之类的中间页过去。
 */
internal object LegadoWeb {

    class Result(val url: String, val result: String)

    @Volatile var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    private const val LOAD_TIMEOUT_MS = 25_000L
    private const val SETTLE_MS = 800L
    private const val CHALLENGE_WAIT_MS = 8_000L
    private val CHALLENGE = Regex("just a moment|cf-browser-verification|checking your browser|请稍候|正在检查", RegexOption.IGNORE_CASE)

    fun userAgent(): String = appContext?.let { c -> runCatching { WebSettings.getDefaultUserAgent(c) }.getOrNull() }.orEmpty()

    /**
     * 加载 [html]（非空时，[url] 作基准地址）或 [url]；[js] 为空返回整页 HTML，否则返回该脚本的求值结果。
     * [cookies] 是书源 Cookie 罐里该地址的 `Cookie:` 头，先写进 WebView 的 CookieManager。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun load(url: String?, html: String?, js: String?, cookies: String?): Result {
        val ctx = appContext ?: throw UnsupportedOperationException("webView（应用尚未初始化）")
        check(Looper.myLooper() != Looper.getMainLooper()) { "webView 不能在主线程调用" }
        val latch = CountDownLatch(1)
        var out: Result? = null
        var error: Throwable? = null
        var web: WebView? = null

        fun finish(r: Result?, e: Throwable?) {
            if (latch.count == 0L) return
            out = r
            error = e
            latch.countDown()
        }

        main.post {
            try {
                val wv = WebView(ctx)
                web = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.blockNetworkImage = true
                if (url != null && !cookies.isNullOrBlank()) {
                    cookies.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { CookieManager.getInstance().setCookie(url, it) }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finished: String?) {
                        main.postDelayed({ collect(view, js, finished ?: url.orEmpty(), System.currentTimeMillis(), ::finish) }, SETTLE_MS)
                    }
                }
                if (html != null) wv.loadDataWithBaseURL(url, html, "text/html", "utf-8", null) else wv.loadUrl(url!!)
            } catch (e: Throwable) {
                finish(null, e)
            }
        }
        val ok = latch.await(LOAD_TIMEOUT_MS + CHALLENGE_WAIT_MS, TimeUnit.MILLISECONDS)
        main.post { web?.let { runCatching { it.stopLoading(); it.destroy() } } }
        if (!ok) throw java.io.IOException("webView 加载超时")
        error?.let { throw it }
        return out ?: throw java.io.IOException("webView 没有返回结果")
    }

    /** 取页面 / 脚本结果；还停在人机检查中间页时每 0.5 秒重试，最多再等 [CHALLENGE_WAIT_MS]。 */
    private fun collect(view: WebView, js: String?, url: String, startedAt: Long, done: (Result?, Throwable?) -> Unit) {
        val script = if (js.isNullOrBlank()) "document.documentElement.outerHTML" else js
        view.evaluateJavascript(script) { raw ->
            val text = decode(raw)
            val waiting = js.isNullOrBlank() && CHALLENGE.containsMatchIn(text.take(4000)) &&
                System.currentTimeMillis() - startedAt < CHALLENGE_WAIT_MS
            if (waiting) {
                main.postDelayed({ collect(view, js, url, startedAt, done) }, 500)
            } else {
                done(Result(view.url ?: url, text), null)
            }
        }
    }

    /** evaluateJavascript 回调给的是 JSON 文本（字符串带引号和转义）。 */
    private fun decode(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue()?.toString().orEmpty() }.getOrDefault(raw)
    }
}
