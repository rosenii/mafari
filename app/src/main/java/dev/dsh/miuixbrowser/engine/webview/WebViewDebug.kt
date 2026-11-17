package dev.dsh.miuixbrowser.engine.webview

import dev.dsh.miuixbrowser.i18n.tr

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.util.Diag

/**
 * WebView 调试开关（设置 → 高级 → WebView 调试，仅 WebView 内核下显示）。
 *
 * 全部默认关闭：调试能力不该在日常使用时白白消耗性能。
 * - 运行日志：与 Gecko 调试页共用同一个开关（[Diag]），写 Android/media/<包名>/diag.txt
 * - [consoleLog]：把网页 console.* / JS 报错写进运行日志（需先开运行日志）
 * - [pageLog]：记录页面开始 / 完成加载、加载失败、HTTP 4xx/5xx 的子资源（需先开运行日志）
 * - [remoteDebug]：允许桌面 Chrome 通过 chrome://inspect 经 USB 检查页面。WebView.setWebContentsDebuggingEnabled
 *   是进程级静态开关，立即生效，对已有和新建的 WebView 都有效
 */
object WebViewDebug {

    private const val FILE = "mafari_webview_debug"
    private var prefs: SharedPreferences? = null

    var consoleLog by mutableStateOf(false); private set
    var pageLog by mutableStateOf(false); private set
    var remoteDebug by mutableStateOf(false); private set

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        consoleLog = p.getBoolean("console_log", false)
        pageLog = p.getBoolean("page_log", false)
        remoteDebug = p.getBoolean("remote_debug", false)
        if (remoteDebug) applyRemoteDebug(true)
    }

    private inline fun save(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
    }

    fun updateConsoleLog(v: Boolean) {
        consoleLog = v; save { putBoolean("console_log", v) }
    }

    fun updatePageLog(v: Boolean) {
        pageLog = v; save { putBoolean("page_log", v) }
    }

    fun updateRemoteDebug(v: Boolean) {
        remoteDebug = v; save { putBoolean("remote_debug", v) }
        applyRemoteDebug(v)
    }

    private fun applyRemoteDebug(v: Boolean) {
        runCatching { WebView.setWebContentsDebuggingEnabled(v) }
    }

    /* ---------- 日志钩子：关着时只是一次布尔判断，不产生任何开销 ---------- */

    fun logConsole(m: ConsoleMessage) {
        if (!consoleLog || !Diag.enabled) return
        val src = m.sourceId().orEmpty().substringAfterLast('/').take(60)
        Diag.log("console.${m.messageLevel().name.lowercase()} [$src:${m.lineNumber()}] ${m.message().take(500)}")
    }

    fun logPage(event: String, url: String?) {
        if (!pageLog || !Diag.enabled) return
        Diag.log("page $event ${url.orEmpty().take(200)}")
    }

    fun logLoadError(request: WebResourceRequest, error: WebResourceError) {
        if (!pageLog || !Diag.enabled) return
        val kind = if (request.isForMainFrame) "main" else "sub"
        Diag.log("load error($kind) ${error.errorCode} ${error.description} ${request.url.toString().take(200)}")
    }

    fun logHttpError(request: WebResourceRequest, response: WebResourceResponse) {
        if (!pageLog || !Diag.enabled) return
        val kind = if (request.isForMainFrame) "main" else "sub"
        Diag.log("http ${response.statusCode}($kind) ${request.method} ${request.url.toString().take(200)}")
    }

    /* ---------- 信息 ---------- */

    fun infoLines(context: Context): List<Pair<String, String>> {
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val ua = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
        fun feature(name: String) = if (WebViewFeature.isFeatureSupported(name)) tr("支持") else tr("不支持")
        return listOf(
            "WebView" to (pkg?.let { "${it.packageName} ${it.versionName}" } ?: tr("未知")),
            tr("系统") to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· ${Build.MANUFACTURER} ${Build.MODEL}",
            tr("默认 UA") to ua.ifBlank { tr("未知") },
            tr("文档开始脚本") to feature(WebViewFeature.DOCUMENT_START_SCRIPT),
            "Passkey" to feature(WebViewFeature.WEB_AUTHENTICATION),
            tr("代理覆盖") to feature(WebViewFeature.PROXY_OVERRIDE),
            tr("智能深色") to feature(WebViewFeature.ALGORITHMIC_DARKENING),
            tr("运行日志") to if (Diag.enabled) tr("已开启") else tr("关闭"),
            tr("日志文件") to Diag.logFile(context).let { f ->
                if (f.exists()) "${f.absolutePath}（${android.text.format.Formatter.formatFileSize(context, f.length())}）"
                else f.absolutePath
            },
        )
    }

    /** 清 WebView 网页缓存（HTTP 缓存），不动 Cookie / 站点存储；须在主线程调用 */
    fun clearWebCache(context: Context) {
        runCatching { WebView(context.applicationContext).apply { clearCache(true); destroy() } }
    }
}
