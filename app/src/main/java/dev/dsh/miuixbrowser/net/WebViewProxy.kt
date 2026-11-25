/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.net

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoProxy
import dev.dsh.miuixbrowser.util.Diag
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * WebView 代理：官方 [ProxyController]（androidx.webkit）。
 * 对进程内全部 WebView 生效；关闭时 clearProxyOverride。
 *
 * - HTTP 账号密码不进 ProxyConfig（API 不支持），由 WebViewClient
 *   [onReceivedHttpAuthRequest] 在挑战主机等于代理主机时提交。
 * - SOCKS5 官方 ProxyConfig 不支持认证：需要账号密码时，在本机起 [Socks5Relay]（127.0.0.1 随机端口，
 *   免认证入口），WebView 连它，由它向上游做用户名 / 密码认证。
 * - 失败不再被吞掉：原因写入 [lastError]（设置里的代理对话框会显示）和 [Diag]。
 */
object WebViewProxy {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val directExecutor = Executor { it.run() }

    @Volatile var host: String = ""
        private set
    @Volatile var authEnabled: Boolean = false
        private set
    @Volatile var authUser: String = ""
        private set
    @Volatile var authPass: String = ""
        private set

    /** 最近一次应用代理失败的原因；成功或关闭代理时为 null */
    var lastError by mutableStateOf<String?>(null)
        private set

    // 以下仅在主线程访问
    private var applied: String? = null
    private var relay: Socks5Relay? = null
    private var relayKey: String? = null
    private var generation = 0

    /** 是否应对该主机的 HTTP 认证挑战自动提交代理账号（仅 HTTP 代理） */
    fun shouldAutoAuth(challengeHost: String): Boolean =
        authEnabled && host.isNotEmpty() && challengeHost.equals(host, ignoreCase = true)

    /** 去掉用户误填的协议头 / 路径 / 账号 / 「:端口」/ IPv6 方括号 */
    private fun normalizeHost(raw: String): String {
        var s = raw.trim().substringAfter("://").substringBefore('/').substringAfterLast('@').trim()
        if (s.startsWith("[")) {
            val e = s.indexOf(']')
            return if (e > 0) s.substring(1, e) else s.trim('[', ']')
        }
        if (s.count { it == ':' } == 1) s = s.substringBefore(':')
        return s
    }

    private fun fail(message: String) {
        Diag.log("proxy: $message")
        lastError = message
    }

    /** type：0 = HTTP，1 = SOCKS5 */
    fun apply(
        enabled: Boolean,
        type: Int,
        host: String,
        port: Int,
        auth: Boolean = false,
        user: String = "",
        pass: String = "",
        /** false = 只算规则、只推给 Gecko，不去拿 WebView 的 [ProxyController]（那会加载 WebView 提供方）。默认内核是 Gecko 时用 */
        webView: Boolean = true,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { apply(enabled, type, host, port, auth, user, pass, webView) }
            return
        }
        val gen = ++generation
        val h = normalizeHost(host)
        this.host = if (enabled) h else ""
        this.authEnabled = enabled && auth && type != 1 && h.isNotEmpty()
        this.authUser = user
        this.authPass = pass

        val controller: ProxyController? = if (webView) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (enabled) fail(tr("当前 WebView 不支持代理，请更新 Android System WebView"))
                return
            }
            runCatching { ProxyController.getInstance() }.getOrNull() ?: run {
                if (enabled) fail(tr("无法获取 WebView 代理控制器"))
                return
            }
        } else {
            null
        }

        if (!enabled || h.isEmpty() || port !in 1..65535) {
            if (EngineSupport.GECKO_BUNDLED) GeckoProxy.applyRule(null)
            closeRelay()
            if (applied != null && controller != null) {
                runCatching { controller.clearProxyOverride(directExecutor, Runnable { }) }
                applied = null
            }
            lastError = if (enabled) tr("代理主机或端口无效，已回到直连") else null
            return
        }

        val useRelay = type == 1 && auth && user.isNotEmpty()
        if (useRelay) {
            val key = "$h|$port|$user|$pass"
            val cur = relay
            if (cur != null && relayKey == key && cur.isAlive) {
                install(controller, "socks5://127.0.0.1:${cur.port}")
                return
            }
            closeRelay()
            // 建本地监听放到后台线程，避免主线程网络调用
            Thread {
                val made = runCatching { Socks5Relay(h, port, user, pass).also { it.start() } }
                mainHandler.post {
                    val r = made.getOrNull()
                    if (gen != generation) {
                        r?.close()
                        return@post
                    }
                    if (r == null) {
                        fail(tr("SOCKS5 本地中转启动失败：{0}", made.exceptionOrNull()?.message))
                        return@post
                    }
                    relay = r
                    relayKey = key
                    install(controller, "socks5://127.0.0.1:${r.port}")
                }
            }.apply { isDaemon = true }.start()
            return
        }

        closeRelay()
        val hostPart = if (h.contains(':')) "[$h]" else h
        install(controller, if (type == 1) "socks5://$hostPart:$port" else "http://$hostPart:$port")
    }

    private fun install(controller: ProxyController?, rule: String) {
        // Gecko 标签用同一条规则（含 SOCKS5 认证用的本机中转），写进 network.proxy.*
        if (EngineSupport.GECKO_BUNDLED) GeckoProxy.applyRule(rule)
        if (controller == null) { // 没有 WebView 在用：规则只给 Gecko，之后切到 WebView 时会带 webView = true 重新应用
            lastError = null
            return
        }
        if (rule == applied) {
            lastError = null
            return
        }
        try {
            val config = ProxyConfig.Builder().addProxyRule(rule).build()
            controller.setProxyOverride(config, directExecutor, Runnable { })
            applied = rule
            lastError = null
            Diag.log("proxy: applied $rule")
        } catch (e: Exception) {
            fail(tr("代理规则无效或不被当前 WebView 支持：{0}", e.message))
        }
    }

    private fun closeRelay() {
        relay?.close()
        relay = null
        relayKey = null
    }

    fun applyFrom(settings: AppSettings, webView: Boolean = true) {
        apply(
            enabled = settings.proxyEnabled,
            type = settings.proxyType,
            host = settings.proxyHost,
            port = settings.proxyPort,
            auth = settings.proxyAuth,
            user = settings.proxyUser,
            pass = settings.proxyPass,
            webView = webView,
        )
    }
}
