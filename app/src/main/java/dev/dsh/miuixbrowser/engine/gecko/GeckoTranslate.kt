package dev.dsh.miuixbrowser.engine.gecko

import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.util.Diag
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Gecko 内核的页内翻译适配：自带一个内置 WebExtension（assets/mafari-translate-ext），
 * 内容脚本就是与 WebView 共用的 mafari_translate.js（文末有 Gecko 胶水）。做法与 [GeckoReader] 相同：
 * 每个标签的 GeckoSession 登记原生应用名 "mafariTranslate" 的消息委托，页面里的内容脚本 connectNative 过来。
 *
 *   内容脚本 → 原生：{ t:"req", id, texts } / { t:"state", phase, total, done, err? }
 *   原生 → 内容脚本：{ t:"hi" }（握手）、{ t:"start", target }、{ t:"retry" }、{ t:"original" }、{ t:"translation" }、
 *                     { t:"stop" }、{ t:"res", id, texts } / { t:"err", id, kind, detail }
 *
 * 注意：本文件依赖 GeckoView WebExtension 的消息 API，未在本环境编译；nightly 的签名若有变化只需改本文件。
 */
internal object GeckoTranslate {
    const val EXT_ID = "translate@mafari.dev"
    private const val EXT_URI = "resource://android/assets/mafari-translate-ext/"
    private const val NATIVE = "mafariTranslate"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ext: WebExtension? = null
    @Volatile private var installing = false

    /** 主线程读写：已建好的 Gecko 会话 → 标签 */
    private val sessions = LinkedHashMap<GeckoSession, BrowserTab>()

    /** 主线程。Gecko 运行时创建后调用（幂等）；拓展装好后给所有已登记会话补挂委托 */
    fun ensureInstalled(context: Context) {
        if (ext != null || installing) return
        installing = true
        val app = context.applicationContext
        val runtime = GeckoRuntimeHolder.get(app)
        runtime.webExtensionController.ensureBuiltIn(EXT_URI, EXT_ID).then({ e: WebExtension? ->
            installing = false
            if (e == null) return@then GeckoResult<Void>()
            ext = e
            allowInPrivate(runtime, e)
            sessions.forEach { (s, t) -> attach(s, t) }
            Diag.log("translate: built-in extension ready ${e.id}")
            GeckoResult<Void>()
        }, { t: Throwable ->
            installing = false
            Diag.log("translate: ensureBuiltIn failed: $t")
            GeckoResult<Void>()
        })
    }

    /** 无痕标签里也要能翻译（与 GeckoReader 一样走反射，避免 nightly 签名变化导致编译失败） */
    private fun allowInPrivate(runtime: org.mozilla.geckoview.GeckoRuntime, e: WebExtension) {
        runCatching {
            val c = runtime.webExtensionController
            val m = c.javaClass.methods.firstOrNull { it.name == "setAllowedInPrivateBrowsing" && it.parameterTypes.size == 2 }
            m?.invoke(c, e, true)
        }.onFailure { Diag.log("translate: allow in private failed $it") }
    }

    /** 主线程。新建 / 采纳 Gecko 会话时调用 */
    fun attachSession(session: GeckoSession, tab: BrowserTab) {
        sessions[session] = tab
        ext?.let { attach(session, tab) }
    }

    fun detachSession(session: GeckoSession) { sessions.remove(session) }

    private fun attach(session: GeckoSession, tab: BrowserTab) {
        val e = ext ?: return
        runCatching {
            session.webExtensionController.setMessageDelegate(e, delegate(tab), NATIVE)
        }.onFailure { Diag.log("translate: attach session failed $it") }
    }

    private fun delegate(tab: BrowserTab) = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val o = message as? JSONObject ?: return
                    main.post { tab.translate.geckoMessage(port, o) }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    main.post { tab.translate.geckoGone(port) }
                }
            })
            // 握手：内容脚本收到任何消息即视为已连上；同时让原生记下这个页面的端口
            main.post {
                runCatching { port.postMessage(JSONObject().put("t", "hi")) }
                tab.translate.geckoMessage(port, JSONObject().put("t", "hello"))
            }
        }
    }

    /** 主线程。向该标签页里的内容脚本发指令 / 译文 */
    fun send(port: WebExtension.Port, msg: JSONObject) {
        runCatching { port.postMessage(msg) }.onFailure { Diag.log("translate: send failed $it") }
    }
}
