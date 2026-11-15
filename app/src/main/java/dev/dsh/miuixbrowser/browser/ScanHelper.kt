package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.ScanActivity
import dev.dsh.miuixbrowser.util.toUrl
import android.content.Context
import android.content.Intent

/**
 * 「扫一扫」：应用内扫码页 [ScanActivity]（CameraX + ML Kit 内置版识别，不依赖 Google Play 服务、无需联网）。
 * GeckoView / WebView 都没有内置的扫码 API，两种内核共用这一套。
 */
object ScanHelper {

    /** 扫码结果的 extra 名：沿用 ZXing 的约定 */
    const val EXTRA_RESULT = "SCAN_RESULT"

    fun intent(context: Context): Intent = Intent(context, ScanActivity::class.java)

    /** 从扫码页回传的 Intent 里取文本 */
    fun extractText(data: Intent?): String? =
        data?.getStringExtra(EXTRA_RESULT)?.trim()?.takeIf { it.isNotEmpty() }

    sealed interface Target {
        /** 网址或搜索词：在新标签打开 */
        data class Open(val url: String) : Target

        /** 其他协议（wifi: / tel: / intent: / javascript: 等）：不执行，只复制 */
        data class CopyOnly(val text: String) : Target
    }

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(?!\\d)")

    /**
     * 扫到的内容怎么处理：http(s) 与「像网址的」内容直接打开，普通文字走搜索；
     * 其他协议一律不执行（避免二维码里藏 javascript: / intent: / file:），只复制到剪贴板。
     */
    fun resolve(text: String, searchTemplate: String): Target {
        val t = text.trim()
        val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") return Target.CopyOnly(t)
        return Target.Open(toUrl(t, searchTemplate))
    }
}
