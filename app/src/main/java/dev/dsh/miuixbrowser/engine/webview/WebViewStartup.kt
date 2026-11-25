package dev.dsh.miuixbrowser.engine.webview

import android.content.Context
import android.os.SystemClock
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewStartUpConfig
import dev.dsh.miuixbrowser.util.Diag
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WebView 异步预热（androidx.webkit 1.16+ 的 `WebViewCompat.startUpWebView`）。
 *
 * 第一个 `WebView(context)` 或 `CookieManager.getInstance()` 会在主线程同步加载 WebView 提供方（Chromium）：
 * 加载 so、初始化浏览器进程等，几百毫秒。`startUpWebView` 把其中能放后台的部分挪到后台线程，
 * 主线程上必须做的部分切成小块，不再是首次创建 WebView 时的一整段卡顿。
 *
 * 接线：
 *  - [begin]：Application.onCreate 里尽早调用（只在主进程、默认内核是 WebView 时）；
 *  - [awaitDone]：冷启动的「库就绪闸门」里等它（和加密库预热并行，几乎不增加等待）；
 *    闸门放行之后才组合 BrowserScreen、才会创建第一个 [dev.dsh.miuixbrowser.browser.BrowserTab] 的 WebView。
 *    等不到（超时 / 系统太旧不支持）就照旧放行，WebView 构造时自己同步加载，行为和以前一样。
 */
object WebViewStartup {

    /** 闸门里最多等多久：正常预热远比这短，超过说明这台设备上它帮不上忙，别拖慢首屏。 */
    const val GATE_WAIT_MS = 1_500L

    private val done = CountDownLatch(1)

    @Volatile var started = false
        private set

    /** 预热是否已经结束（成功、失败或不支持都算结束）。 */
    fun isDone(): Boolean = done.count == 0L

    fun begin(context: Context) {
        if (started) return
        started = true
        val t0 = SystemClock.elapsedRealtime()
        val ok = runCatching<Unit> {
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "mafari-webview-startup").apply { isDaemon = true }
            }
            val config = WebViewStartUpConfig.Builder(executor)
                // 只做后台线程能做的部分；主线程部分留给第一个 WebView 创建时（设为 true 时实测回调始终不来，且会和首帧抢主线程）
                .setShouldRunUiThreadStartUpTasks(false)
                .build()
            // 1.19 起 startUpWebView 有两个单参回调重载（WebViewStartUpCallback / WebViewOutcomeReceiver），
            // 直接传 Kotlin lambda 会 overload resolution ambiguity；显式 SAM 构造固定到 StartUpCallback。
            WebViewCompat.startUpWebView(context.applicationContext, config, WebViewCompat.WebViewStartUpCallback {
                Diag.log("webview startup: done in ${SystemClock.elapsedRealtime() - t0}ms")
                done.countDown()
                executor.shutdown()
            })
        }.onFailure {
            // 系统 WebView 太旧（没有这个接口）等：放弃预热，WebView 首次创建时照旧同步加载
            Diag.log("webview startup: unavailable (${it.javaClass.simpleName}: ${it.message})")
        }.isSuccess
        if (!ok) done.countDown()
    }

    /** 阻塞等预热结束（最多 [timeoutMs]）。只能在非主线程调用。 */
    fun awaitDone(timeoutMs: Long = GATE_WAIT_MS) {
        if (isDone()) return
        val finished = runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            // 超时：这台设备上预热帮不上忙。标记结束，免得 Activity 重建时闸门每次都再等一轮
            Diag.log("webview startup: not finished within ${timeoutMs}ms, opening gate anyway")
            done.countDown()
        }
    }
}
