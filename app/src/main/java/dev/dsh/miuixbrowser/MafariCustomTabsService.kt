package dev.dsh.miuixbrowser

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken

/**
 * Custom Tabs 服务端：让其他应用（用 androidx.browser 的 CustomTabsClient）把 Mafari 识别成「支持自定义标签页的浏览器」。
 * 没有这个服务时，应用会挑 Chrome 等别的浏览器去开自定义标签页，根本到不了 Mafari。
 * 这里只做最小实现：接受会话、不做预热 / 预加载 / postMessage；真正的页面由 [LinkDispatchActivity] → [CustomTabActivity] 展示。
 */
class MafariCustomTabsService : CustomTabsService() {

    override fun warmup(flags: Long): Boolean = true

    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean = true

    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: List<Bundle>?,
    ): Boolean = false

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null

    override fun updateVisuals(sessionToken: CustomTabsSessionToken, bundle: Bundle?): Boolean = false

    override fun requestPostMessageChannel(sessionToken: CustomTabsSessionToken, postMessageOrigin: Uri): Boolean = false

    override fun postMessage(sessionToken: CustomTabsSessionToken, message: String, extras: Bundle?): Int = CustomTabsService.RESULT_FAILURE_DISALLOWED

    override fun receiveFile(sessionToken: CustomTabsSessionToken, uri: Uri, purpose: Int, extras: Bundle?): Boolean = false
}
