package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.engine.gecko.GeckoReader
import dev.dsh.miuixbrowser.i18n.tr
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 标签页的阅读模式（从 BrowserTab 拆出）：两种内核共用 assets/mafari_reader.js，
 * WebView 由这里定时检测 / 进入 / 退出，Gecko 由内容脚本经 [GeckoReader] 回报状态。
 * 对界面可见的 readerable / readerActive 通过 [BrowserTab] 的同名只读属性透出。
 */
internal class TabReader(private val tab: BrowserTab) {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 当前页面能进入阅读模式（地址栏最左侧显示阅读图标）。每次导航开始清零，页面加载后重新检测 */
    var readerable by mutableStateOf(false)
        private set

    /** 正处于阅读模式（图标点亮） */
    var readerActive by mutableStateOf(false)
        private set

    /** 进入阅读模式时的页面地址：单页应用换了地址就自动退出 */
    private var readerUrl = ""

    /** 最近一次检测对应的地址（WebView：避免同一地址重复清零） */
    private var readerDetectUrl = ""

    /** 检测已重试的次数（页面内容晚渲染时多测几次） */
    private var readerAttempt = 0

    /** 每次导航 +1：丢弃上一个文档遗留的异步回调 */
    private var readerGeneration = 0

    /** Gecko：已向内容脚本发出 enter、等它回报结果（用来在提取失败时给用户提示） */
    private var readerEnterPending = false

    /** Gecko：当前页面里内容脚本的原生端口（页面离开 / 重新加载后由 [BrowserTab.geckoReaderGone] 清掉） */
    @Volatile private var readerPort: org.mozilla.geckoview.WebExtension.Port? = null

    private val readerDetectRunnable = Runnable { runReaderDetect() }

    /** 新文档开始加载：阅读模式状态随旧文档一起失效 */
    fun reset() {
        handler.removeCallbacks(readerDetectRunnable)
        readerEnterPending = false
        readerGeneration++
        readerAttempt = 0
        readerDetectUrl = ""
        readerUrl = ""
        readerable = false
        readerActive = false
    }

    /** WebView：延迟检测（页面刚结束加载时正文可能还在渲染） */
    private fun scheduleReaderDetect(delayMs: Long) {
        if (tab.isGecko || tab.closed || tab.destroyed) return
        handler.removeCallbacks(readerDetectRunnable)
        handler.postDelayed(readerDetectRunnable, delayMs)
    }

    private fun runReaderDetect() {
        if (tab.isGecko || tab.closed || tab.destroyed || tab.parked || readerActive) return
        val u = tab.url
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return
        readerDetectUrl = u
        val gen = readerGeneration
        tab.evaluateJs(ReaderMode.detectScript(ReaderMode.source(tab.appContext))) { raw ->
            if (gen != readerGeneration || tab.closed || tab.destroyed || readerActive) return@evaluateJs
            val ok = raw == "true"
            readerable = ok
            if (!ok && readerAttempt < 2) {
                readerAttempt++
                scheduleReaderDetect(if (readerAttempt == 1) 1500L else 3500L)
            }
        }
    }

    /** WebView 页面加载结束 / 单页应用换了地址：重新检测；阅读中换了地址则先退出 */
    fun onWebPageSettled(newUrl: String?, delayMs: Long) {
        if (tab.isGecko || newUrl.isNullOrEmpty()) return
        if (readerActive) {
            if (readerUrl.isNotEmpty() && newUrl != readerUrl) exitReader()
            else return
        }
        if (newUrl == readerDetectUrl && readerable) return
        // 地址变了：上一个地址的检测结果作废，图标先收起，等新结果
        if (newUrl != readerDetectUrl) readerable = false
        readerAttempt = 0
        scheduleReaderDetect(delayMs)
    }

    /**
     * 地址栏阅读图标的点击：未进入 → 进入；已进入 → 退出。[dark] 是当前应用的深浅色，阅读层按它配色。
     */
    fun toggleReader(dark: Boolean) {
        if (readerActive) {
            exitReader()
            return
        }
        if (!readerable || tab.closed || tab.destroyed) return
        if (tab.isGecko) {
            val port = readerPort
            if (port == null) {
                android.widget.Toast.makeText(tab.appContext, tr("阅读模式尚未就绪，请稍后再试"), android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // 结果由内容脚本回报（geckoReaderState）：active=true 时图标才点亮；提取失败则 readerable 会被置回 false
            readerEnterPending = true
            GeckoReader.send(port, org.json.JSONObject().put("t", "enter").put("dark", dark))
            return
        }
        tab.evaluateJs(ReaderMode.enterScript(ReaderMode.source(tab.appContext), dark)) { raw ->
            if (tab.closed || tab.destroyed) return@evaluateJs
            if (raw == "true") {
                readerActive = true
                readerUrl = tab.url
            } else {
                readerable = false
                android.widget.Toast.makeText(tab.appContext, tr("无法提取这个页面的正文"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 退出阅读模式，回到原页面（滚动位置还原） */
    fun exitReader() {
        if (!readerActive) return
        readerActive = false
        readerUrl = ""
        if (tab.isGecko) {
            readerPort?.let { GeckoReader.send(it, org.json.JSONObject().put("t", "exit")) }
        } else {
            tab.evaluateJs(ReaderMode.EXIT_SCRIPT, null)
        }
    }

    /** Gecko：内容脚本回报状态（主线程）。active 为 false 且 readerable 为 false 时图标消失 */
    fun geckoState(port: org.mozilla.geckoview.WebExtension.Port, canRead: Boolean, active: Boolean) {
        if (tab.closed || tab.destroyed) return
        readerPort = port
        if (readerEnterPending) {
            readerEnterPending = false
            if (!active) {
                android.widget.Toast.makeText(tab.appContext, tr("无法提取这个页面的正文"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        readerActive = active
        readerable = canRead || active
        if (active) readerUrl = tab.url
    }

    /** Gecko：内容脚本断开（页面离开 / 重新加载）。只处理当前端口，旧页面迟到的断开不能清掉新页面的状态 */
    fun geckoGone(port: org.mozilla.geckoview.WebExtension.Port) {
        if (port !== readerPort) return
        readerPort = null
        readerable = false
        readerActive = false
    }
}
