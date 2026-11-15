package dev.dsh.miuixbrowser.crypto

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.engine.webview.WebOrigin
import dev.dsh.miuixbrowser.util.Diag
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * 低频可信通道（页面里的对象名 MafariTrusted），基于 WebMessageListener。
 *
 * 角色：只承载低频、可以异步、且需要框架给出严格来源的操作——站点豁免请求、统计上报。
 * 它与指纹保护的配置下发**完全解耦**：配置走 origin-scoped 内联注入，这里不传任何配置、也不返回任何数据。
 *
 * 安全约束：
 * - 来源只信框架给的 sourceOrigin / isMainFrame，绝不读消息里自带的 host / origin 字段；
 * - 只接受 http(s) 来源、只接受字符串消息、长度有上限、op 在固定白名单内，其余一律丢弃；
 * - 没有任何回传：页面无法借此探测原生状态；
 * - 会修改用户设置的操作必须由用户在界面上确认，页面自己说了不算。
 *
 * 「权限申请」不走这里：网页的摄像头 / 麦克风 / 定位 / 通知本来就经 WebChromeClient 的标准回调，
 * 来源由框架给出并已有确认弹窗，不需要再造一条自定义通道。
 */
object TrustedChannel {

    const val NAME = "MafariTrusted"

    private const val MAX_MESSAGE_CHARS = 256
    private const val MAX_STATS_PER_REPORT = 100_000L

    /** 页面（主框架）可以发起的、需要界面配合的操作。 */
    interface Host {
        /** 页面请求把自己从指纹保护中豁免。实现必须让用户确认，且只能豁免 [origin] 自己。 */
        fun onFingerprintExemptRequested(origin: WebOrigin)
    }

    /**
     * 在首次加载网页之前调用（注入的对象只出现在之后加载的文档里）。
     * 必须运行时检查 WEB_MESSAGE_LISTENER；不支持时返回 false，指纹保护不受影响。
     */
    fun install(webView: WebView, host: Host): Boolean {
        if (!runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
                .getOrDefault(false)
        ) return false
        return runCatching {
            // 规则用 "*" 只是让对象注入到所有 frame；真正的来源校验在 dispatch 里按框架给的 sourceOrigin 做
            WebViewCompat.addWebMessageListener(webView, NAME, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                dispatch(host, message, sourceOrigin.toString(), isMainFrame)
            }
            true
        }.onFailure { Diag.log("trusted channel install failed ${it.message}") }.getOrDefault(false)
    }

    /** 与 Android 无关的分发逻辑，便于单独验证。 */
    internal fun dispatch(host: Host, message: WebMessageCompat, sourceOrigin: String, isMainFrame: Boolean) {
        if (!isMainFrame) return
        val origin = WebOrigin.parse(sourceOrigin) ?: return
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val text = message.data ?: return
        if (text.length > MAX_MESSAGE_CHARS) return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("op")) {
            "fp.stats" -> {
                val n = json.optLong("n", 0L).coerceIn(0L, MAX_STATS_PER_REPORT)
                if (n > 0) AdBlock.recordFingerprintReads(n)
            }
            "fp.exempt.request" -> host.onFingerprintExemptRequested(origin)
            else -> Unit
        }
    }
}
