package dev.dsh.miuixbrowser

import android.content.Context
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Firefox 账号登录页的 Gecko 版：默认内核是 Gecko 时用它代替 WebView（见 [FxaScreen]）。
 *
 * 用单独的无痕 session：登录用的 Mozilla 账号 Cookie 不会留在 Gecko 的浏览资料里；
 * 回调地址由 [FxaSync.interceptRedirect] 在 onLoadRequest 里截获。
 * 只在 [EngineSupport.defaultIsGecko] 为 true 时才会构造（lite / 无 Gecko 设备不会加载这个类）。
 */
internal class FxaGeckoLogin(context: Context, url: String, backgroundColor: Int, canBackChanged: (Boolean) -> Unit) {
    private val session: GeckoSession = newGeckoSession(privateMode = true)

    val view: GeckoView = GeckoView(context)

    init {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? =
                if (FxaSync.interceptRedirect(request.uri)) GeckoResult.fromValue(AllowOrDeny.DENY) else null

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) = canBackChanged(canGoBack)
        }
        session.open(GeckoRuntimeHolder.get(context))
        // 第一次绘制前盖一层主题底色，避免白闪
        view.coverUntilFirstPaint(backgroundColor)
        view.setSession(session)
        session.loadUri(url)
    }

    fun goBack() = session.goBack()

    fun close() {
        runCatching { view.releaseSession() }
        runCatching { session.close() }
    }
}
