/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Gecko 内核的阅读模式适配：自带一个内置 WebExtension（assets/mafari-reader-ext），
 * 内容脚本就是与 WebView 共用的 mafari_reader.js（文末有 Gecko 胶水）。
 * 同一个拓展里还有 scroll-metrics.js，沿用同一条原生通道回报「距页面底部还剩多少像素」。
 *
 * 通道（与 [GeckoGm] 的会话端口同一套做法）：每个标签的 GeckoSession 登记原生应用名 "mafariReader" 的消息委托，
 * 页面里的内容脚本 connectNative 过来，原生由此知道是哪个标签：
 *   内容脚本 → 原生：{ t:"state", readerable, active, url }
 *   内容脚本 → 原生：{ t:"scroll", r }（scroll-metrics.js，r = 距页面底部的设备像素，已封顶）
 *   内容脚本 → 原生：{ t:"dock", b }（scroll-metrics.js，b = 页面带固定在底部的整宽栏）
 *   原生 → 内容脚本：{ t:"hi" }（握手）、{ t:"enter", dark }、{ t:"exit" }
 *
 * 注意：本文件依赖 GeckoView WebExtension 的消息 API，未在本环境编译；nightly 的签名若有变化只需改本文件。
 */
internal object GeckoReader {
    const val EXT_ID = "reader@mafari.dev"
    private const val EXT_URI = "resource://android/assets/mafari-reader-ext/"
    private const val NATIVE = "mafariReader"

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
            Diag.log("reader: built-in extension ready ${e.id}")
            GeckoResult<Void>()
        }, { t: Throwable ->
            installing = false
            Diag.log("reader: ensureBuiltIn failed: $t")
            GeckoResult<Void>()
        })
    }

    /** 无痕标签里也要能用阅读模式（与 GeckoExtensions.setAllowedInPrivate 一样走反射，避免 nightly 签名变化导致编译失败） */
    private fun allowInPrivate(runtime: org.mozilla.geckoview.GeckoRuntime, e: WebExtension) {
        runCatching {
            val c = runtime.webExtensionController
            val m = c.javaClass.methods.firstOrNull { it.name == "setAllowedInPrivateBrowsing" && it.parameterTypes.size == 2 }
            m?.invoke(c, e, true)
        }.onFailure { Diag.log("reader: allow in private failed $it") }
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
        }.onFailure { Diag.log("reader: attach session failed $it") }
    }

    /** 听书正文请求：id → 回调（主线程）。内容脚本回 { t:"text", id, text }。 */
    private val textRequests = ConcurrentHashMap<String, (String) -> Unit>()

    private fun delegate(tab: BrowserTab) = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val o = message as? JSONObject ?: return
                    when (o.optString("t")) {
                        "state" -> {
                            val readerable = o.optBoolean("readerable")
                            val active = o.optBoolean("active")
                            main.post { tab.geckoReaderState(port, readerable, active) }
                        }
                        // scroll-metrics.js：距页面底部还剩多少设备像素（固定在底部的导航栏据此决定何时让出位置）
                        "scroll" -> {
                            val r = o.optInt("r", -1)
                            main.post { tab.geckoScrollRemaining(r) }
                        }
                        // scroll-metrics.js：接近页面底部时的视口尺寸（仅「运行日志」开着时才处理，见 GeckoEngine.logViewport）
                        "vp" -> {
                            if (Diag.enabled) {
                                val line = o.toString()
                                main.post { tab.geckoViewportDiag(line) }
                            }
                        }
                        // scroll-metrics.js：页面是否带固定在底部的整宽栏（悬浮三段式据此决定是否让位）
                        "dock" -> {
                            val b = o.optBoolean("b")
                            main.post { tab.geckoBottomBar(b) }
                        }
                        "text" -> {
                            val id = o.optString("id")
                            val text = o.optString("text")
                            val cb = textRequests.remove(id)
                            main.post { cb?.invoke(text) }
                        }
                    }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    main.post { tab.geckoReaderGone(port) }
                }
            })
            // 握手：内容脚本收到任何消息即视为已连上，并回报一次当前状态
            main.post { runCatching { port.postMessage(JSONObject().put("t", "hi")) } }
        }
    }

    /** 听书：向该标签页要一次正文纯文本。回调在主线程；5 秒不回就当空文本。 */
    fun requestText(port: WebExtension.Port, callback: (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        textRequests[id] = callback
        send(port, JSONObject().put("t", "text").put("id", id))
        main.postDelayed({ textRequests.remove(id)?.invoke("") }, 5_000)
    }

    /** 主线程。向该标签页里的内容脚本发指令（enter / exit） */
    fun send(port: WebExtension.Port, msg: JSONObject) {
        runCatching { port.postMessage(msg) }.onFailure { Diag.log("reader: send failed $it") }
    }
}
