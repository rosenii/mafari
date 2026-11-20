package dev.dsh.miuixbrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.AdBlockBridge
import dev.dsh.miuixbrowser.adblock.RequestLog
import dev.dsh.miuixbrowser.crypto.TrustedChannel
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.DESKTOP_UA
import dev.dsh.miuixbrowser.data.PrivacyStats
import dev.dsh.miuixbrowser.data.TabDb
import dev.dsh.miuixbrowser.data.TabThumbs
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.GeckoTabOps
import dev.dsh.miuixbrowser.engine.TabEngineOps
import dev.dsh.miuixbrowser.engine.WebViewTabOps
import dev.dsh.miuixbrowser.engine.gecko.GeckoGm
import dev.dsh.miuixbrowser.engine.gecko.GeckoReader
import dev.dsh.miuixbrowser.engine.gecko.GeckoSitePerms
import dev.dsh.miuixbrowser.engine.gecko.GeckoTabEngine
import dev.dsh.miuixbrowser.engine.webview.FingerprintInjector
import dev.dsh.miuixbrowser.engine.webview.FpFallbackBridge
import dev.dsh.miuixbrowser.engine.webview.WebOrigin
import dev.dsh.miuixbrowser.engine.webview.WebViewDebug
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.net.WebViewProxy
import dev.dsh.miuixbrowser.script.GmBridge
import dev.dsh.miuixbrowser.script.ScriptResources
import dev.dsh.miuixbrowser.script.UserScript
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.ErrorPages
import dev.dsh.miuixbrowser.util.ErrorSpec
import dev.dsh.miuixbrowser.util.isSameSite
import dev.dsh.miuixbrowser.util.isUserScriptUrl
import dev.dsh.miuixbrowser.util.toUrl

// 标签页的网页重定向 / 弹窗 / 外部应用拦截（从 BrowserTab 拆出）：重定向与外部应用唤起的「询问 / 允许 / 拒绝」判定、
// window.open 与弹窗处理、后台标签的挂起提示。写成 [BrowserTab] 的扩展函数；模式开关等状态仍存放在 [BrowserTab] 里。

/** 后台标签没有弹窗载体时挂起的权限提示：切到该标签时再弹（见 [flushDeferredPrompt]） */
internal class DeferredPrompt(
    val origin: String,
    val what: String,
    val permKey: String?,
    val decide: (Boolean) -> Unit,
    val at: Long,
)

/**
 * 是否有权限弹窗正在等用户决定。超过 30 秒还没结果的视为过期（弹窗被切走标签 / 异常关闭而没回调时，
 * 否则 [redirectPromptPending] 会一直为 true，之后所有「打开其他应用 / 重定向」都被静默吞掉）。
 */
internal fun BrowserTab.promptBusy(): Boolean =
    redirectPromptPending && android.os.SystemClock.elapsedRealtime() - redirectPromptAt < 30_000L

/**
 * 网页重定向权限：只管「页面自己发起、没有用户手势」的主框架跳转——
 * JS（location / window.open）、meta refresh。
 * 自动唤起其他应用（intent:// 等）由独立的「打开其他应用」权限控制（[externalAppMode]），两者互不影响。
 * 用户点击、地址栏输入、前进后退，以及用户发起导航后的服务器 3xx 链都不受影响（唤起其他应用除外：它不看手势，一律走权限）。
 * 同站跳转（host 去掉 www. 后相同）视为页面内部导航，不拦截。
 * @return true 表示本次导航已被接管（拦截，用户同意后由 [onAllowed] 放行）
 */
internal fun BrowserTab.interceptRedirect(request: WebResourceRequest, external: Boolean, onAllowed: () -> Unit): Boolean {
    // 网页跳转只管主框架、非服务器 3xx；但「自动唤起其他应用」不受此限：
    // 隐藏 iframe 里的 weixin:// / intent:// 与服务器 302 到应用协议，同样是页面在无手势地拉起别的应用
    if (!external && (!request.isForMainFrame || request.isRedirect)) return false
    // 有用户手势：真实链接点击（触点下是 <a>）照常放行；
    // 点击后由脚本发起的跨站跳转（div onclick / location.href 等）也按重定向处理
    // 外部应用（external = true）不看手势：无论点击还是自动唤起，一律走「打开其他应用」权限
    // （页面上的「用微信打开」按钮也是页面自己发起的唤起，不等于用户确认要打开应用）
    if (request.hasGesture() && !external) {
        val hit = webView.hitTestResult
        val onAnchor = hit != null && !hit.extra.isNullOrEmpty() &&
            (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
        if (onAnchor || request.method != "GET") return false
    }
    // 首次加载 / 空白页上的「跳转」不算重定向；但唤起其他应用不能因为页面还没有 host 就放行
    // （新开标签 / 首次加载时服务器 302 到 weixin:// 正是这样绕过权限的）
    val from = pageHost.ifEmpty { runCatching { Uri.parse(url).host }.getOrNull().orEmpty() }
    if (from.isEmpty() && !external) return false
    val targetHost = request.url.host.orEmpty()
    // 同站（含子网站，如 m. / login. / www.）跳转视为页面内部导航，不拦截
    if (!external && targetHost.isNotEmpty() && isSameSite(targetHost, from)) return false
    // 总是允许：外部应用直接唤起；跨站跳转与「允许」一致，在新标签页打开（当前页不动）
    val mode = if (external) effExternalAppMode() else effRedirectMode()
    if (mode == 1) {
        if (external) return false
        onAllowed()
        return true
    }
    val label = if (external) tr("打开其他应用") else tr("跳转到 {0}", targetHost.ifEmpty { request.url.toString().take(60) })
    if (mode == 2) {
        notifyRedirectBlocked(external, targetHost)
        return true
    }
    if (promptBusy()) return true
    // 拒绝只是不执行这次跳转 / 唤起：这次导航在 shouldOverrideUrlLoading 里就已被截下，
    // 当前页面一直没有离开，所以这里什么都不用回退 —— 停留在网页，不能再 goBack 把页面「关掉」
    // 没有 host（空白页）时没法「记住此网站的选择」，不带 permKey
    val permKey = (if (external) GeckoSitePerms.EXTERNAL_APP else GeckoSitePerms.REDIRECT).takeIf { from.isNotEmpty() }
    askRedirect(from.ifEmpty { "about:blank" }, label, permKey) { allow ->
        if (allow) onAllowed() else notifyRedirectBlocked(external, targetHost)
    }
    return true
}

internal fun BrowserTab.askRedirect(origin: String, what: String, permKey: String? = null, onResult: (Boolean) -> Unit) {
    redirectPromptPending = true
    redirectPromptAt = android.os.SystemClock.elapsedRealtime()
    val decide = { allow: Boolean ->
        redirectPromptPending = false
        onResult(allow)
    }
    val prompt = onPermissionPrompt
    if (prompt != null) {
        prompt(origin, what, permKey, decide)
    } else {
        deferredPrompt = DeferredPrompt(origin, what, permKey, decide, android.os.SystemClock.elapsedRealtime())
    }
}

internal fun BrowserTab.flushDeferredPrompt() {
    val d = deferredPrompt ?: return
    val prompt = onPermissionPrompt ?: return
    deferredPrompt = null
    // 挂起太久（超过 1 分钟）的请求已无意义，按拒绝收尾
    if (android.os.SystemClock.elapsedRealtime() - d.at > 60_000) d.decide(false) else prompt(d.origin, d.what, d.permKey, d.decide)
}

/**
 * 唤起外部应用。支持 intent:// （含 S.browser_fallback_url）；没装对应应用时给出提示，
 * 有回退网址就在当前页打开回退网址，否则什么都不发生 —— 页面本身保持不动。
 */
internal fun BrowserTab.launchExternalApp(uri: Uri) {
    val raw = uri.toString()
    val isIntentScheme = uri.scheme.equals("intent", ignoreCase = true)
    val intent = runCatching {
        if (isIntentScheme) Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
    }.getOrNull()
    if (intent == null) {
        android.widget.Toast.makeText(appContext, tr("无法打开该链接"), android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    // 网页给出的 intent 不得指定组件 / selector，只允许按 BROWSABLE 解析，防止直指本应用或非导出组件
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val started = runCatching { appContext.startActivity(intent) }.isSuccess
    if (started) return
    val fallback = if (isIntentScheme) intent.getStringExtra("browser_fallback_url") else null
    if (!fallback.isNullOrBlank() && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
        mainHandler.post { loadUrl(fallback) }
    } else {
        android.widget.Toast.makeText(appContext, tr("未找到可打开的应用"), android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 允许重定向：目标在新标签页打开；原页面在导航被截下时就没有离开，无需回退 */
internal fun BrowserTab.openRedirectInNewTab(target: String) {
    val open = onOpenInNewTab
    if (open != null) open(target) else mainHandler.post { loadUrl(target) }
}

/**
 * window.open / target=_blank 创建的新窗口：不创建真正的窗口，只截获它要打开的地址，
 * 再套用「网页重定向」权限——同站或用户点击的真实链接直接开新标签，
 * 其余按 询问 / 总是允许 / 总是拒绝 处理。拒绝时原页面完全不受影响。
 */
internal fun BrowserTab.handleCreateWindow(view: WebView, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
    val hit = view.hitTestResult
    val linkClick = isUserGesture && hit != null && !hit.extra.isNullOrEmpty() &&
        (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
    val temp = WebView(appContext)
    temp.settings.javaScriptEnabled = true
    var handled = false
    fun capture(target: String?) {
        if (handled || target.isNullOrBlank() || target == "about:blank") return
        handled = true
        val uri = Uri.parse(target)
        temp.post {
            runCatching { temp.stopLoading(); temp.destroy() }
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            // window.open("weixin://dl/business/?t=…") 这类唤起微信小程序 / 支付宝的写法，不能直接丢掉
            if (isExternalAppScheme(scheme)) mainHandler.post { openExternalFromWindow(uri, isUserGesture) }
            return
        }
        handlePopupTarget(target, uri.host.orEmpty(), linkClick)
    }
    temp.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
            capture(request.url.toString())
            return true
        }

        override fun onPageStarted(v: WebView, pageUrl: String?, favicon: Bitmap?) {
            capture(pageUrl)
        }
    }
    // 超时仍没有地址（空白弹窗）就回收
    temp.postDelayed({
        if (!handled) {
            handled = true
            runCatching { temp.stopLoading(); temp.destroy() }
        }
    }, 8_000)
    transport.webView = temp
    resultMsg.sendToTarget()
    return true
}

internal fun BrowserTab.handlePopupTarget(target: String, targetHost: String, linkClick: Boolean) {
    val open = onOpenInNewTab ?: return
    val sameSite = pageHost.isNotEmpty() && targetHost.isNotEmpty() && isSameSite(targetHost, pageHost)
    if (sameSite || pageHost.isEmpty()) {
        // 同站新窗口链接：默认在当前标签打开，「同站链接新标签页打开」开关打开才新开标签
        if (sameSiteNewTab) open(target) else mainHandler.post { loadUrl(target) }
        return
    }
    // 用户亲手点的跨站 target=_blank 链接：就是要新窗口，直接新标签打开，不走重定向权限
    if (linkClick) {
        open(target)
        return
    }
    val popupMode = effRedirectMode()
    if (popupMode == 1) {
        open(target)
        return
    }
    if (popupMode == 2) {
        notifyRedirectBlocked(false, targetHost)
        return
    }
    if (promptBusy()) return
    askRedirect(pageHost, tr("跳转到 {0}", targetHost.ifEmpty { target.take(60) }), GeckoSitePerms.REDIRECT) { allow ->
        if (allow) open(target) else notifyRedirectBlocked(false, targetHost)
    }
}

/** 需要交给系统处理的应用协议（weixin / alipays / intent / mailto / tel …）；about / javascript / data / blob / file 不算 */
internal fun BrowserTab.isExternalAppScheme(scheme: String?): Boolean =
    !scheme.isNullOrEmpty() && scheme !in setOf(
        "http", "https", "about", "javascript", "data", "blob", "file", "view-source", "resource", "moz-extension",
    )

/**
 * 新窗口（window.open / target=_blank）或 Gecko 子框架里要唤起其他应用：
 * 总是允许 → 直接唤起；总是拒绝 → 提示已拦截；询问 → 弹窗，同意才唤起（不看用户手势，点击也要问）。
 */
internal fun BrowserTab.openExternalFromWindow(uri: Uri, userGesture: Boolean) {
    // 空白页（还没有 host）也要走权限：新开标签一加载就 window.open("weixin://…") 不能当成用户操作
    val from = pageHost.ifEmpty { runCatching { Uri.parse(url).host }.getOrNull().orEmpty() }
    val mode = effExternalAppMode()
    if (mode == 1) {
        launchExternalApp(uri)
        return
    }
    if (mode == 2) {
        notifyRedirectBlocked(true, "")
        return
    }
    if (promptBusy()) return
    askRedirect(from.ifEmpty { "about:blank" }, tr("打开其他应用"), GeckoSitePerms.EXTERNAL_APP.takeIf { from.isNotEmpty() }) { allow ->
        if (allow) launchExternalApp(uri) else notifyRedirectBlocked(true, "")
    }
}

internal fun BrowserTab.notifyRedirectBlocked(external: Boolean, host: String) {
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - lastRedirectToast < 2000) return
    lastRedirectToast = now
    val msg = if (external) tr("已拦截网页打开其他应用") else tr("已拦截网页重定向") + if (host.isNotEmpty()) tr("：{0}", host) else ""
    android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
}

/** WebView：当前页面生效的「网页重定向」模式（例外网站优先） */
internal fun BrowserTab.effRedirectMode(): Int =
    GeckoSitePerms.override(pageHost, GeckoSitePerms.REDIRECT) ?: redirectMode

/** WebView：当前页面生效的「打开其他应用」模式（例外网站优先） */
internal fun BrowserTab.effExternalAppMode(): Int =
    GeckoSitePerms.override(pageHost, GeckoSitePerms.EXTERNAL_APP) ?: externalAppMode

/** WebView：当前页面生效的「网站媒体发声」模式（例外网站优先）；1 = 允许自动出声 */
internal fun BrowserTab.effMediaMode(): Int =
    GeckoSitePerms.override(pageHost, GeckoSitePerms.MEDIA) ?: mediaModeGlobal

internal fun BrowserTab.applyMediaGesture() {
    val wv = webViewOrNull ?: return
    runCatching { wv.settings.mediaPlaybackRequiresUserGesture = effMediaMode() != 1 }
}
