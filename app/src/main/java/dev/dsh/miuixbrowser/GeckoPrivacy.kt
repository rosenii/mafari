package dev.dsh.miuixbrowser

import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * Gecko 端的隐私 / 网络设置：与 WebView 共用同一套开关和界面（设置 → 隐私与安全），这里只负责把开关翻译成 Gecko 的做法。
 *
 * | 设置 | WebView | Gecko |
 * |---|---|---|
 * | 发送禁止跟踪请求 | 请求头 + navigator.doNotTrack | `privacy.donottrackheader.enabled` + GPC（`setGlobalPrivacyControl`） |
 * | 禁用 WebRTC | 注入脚本移除 RTCPeerConnection | `media.peerconnection.enabled = false` |
 * | 指纹保护（Canvas / WebGL / Audio / Fonts） | 注入脚本 | Gecko 自带的 Fingerprinting Protection，按四类开关选目标（见 [fpOverrides]） |
 * | 代理 | ProxyController + 本机 SOCKS5 中转 | `network.proxy.*` 偏好；SOCKS5 认证走本机中转，HTTP 认证走 [authDelegate] |
 *
 * 偏好都写在用户分支：关闭时「清除用户值」回到 Gecko 默认，而不是写死一个值。
 * 只在 Gecko 已经启动时才写（[applyIfRunning]）；Gecko 启动时由 [GeckoRuntimeHolder] 调用 [apply] 补一次，
 * 所以第一个 Gecko 页面加载前设置就位。必须在主线程调用。
 */
object GeckoPrivacy {

    private const val USER = GeckoPreferenceController.PREF_BRANCH_USER

    private val mainHandler = Handler(Looper.getMainLooper())

    // HTTP 代理认证（由 authDelegate 在 407 时提交）
    @Volatile private var authHost = ""
    @Volatile private var authUser = ""
    @Volatile private var authPass = ""
    @Volatile private var authEnabled = false

    // SOCKS5 认证：Gecko 的偏好里没有账号密码，由本机免认证中转代为向上游认证
    private var relay: Socks5Relay? = null
    private var relayKey: String? = null
    private var proxyGeneration = 0

    /** 设置变化时调用：Gecko 没启动就什么都不做（启动时会补一次） */
    fun applyIfRunning(settings: AppSettings) {
        val runtime = GeckoRuntimeHolder.peek() ?: return
        apply(runtime, settings)
    }

    fun apply(runtime: GeckoRuntime, settings: AppSettings) {
        applyDnt(runtime, settings.sendDnt)
        applyWebRtc(settings.webrtcDisabled)
        applyFingerprint(runtime)
        applyProxy(
            enabled = settings.proxyEnabled,
            type = settings.proxyType,
            rawHost = settings.proxyHost,
            port = settings.proxyPort,
            auth = settings.proxyAuth,
            user = settings.proxyUser,
            pass = settings.proxyPass,
        )
    }

    /* ------------------------------------------------------------------ */
    /* 禁止跟踪 / WebRTC                                                    */
    /* ------------------------------------------------------------------ */

    private fun applyDnt(runtime: GeckoRuntime, on: Boolean) {
        // DNT 头 + navigator.doNotTrack；GPC 头 + navigator.globalPrivacyControl
        if (on) setBool("privacy.donottrackheader.enabled", true) else clear("privacy.donottrackheader.enabled")
        runCatching { runtime.settings.setGlobalPrivacyControl(on) }
    }

    private fun applyWebRtc(disabled: Boolean) {
        // 关掉后页面里没有 RTCPeerConnection，也就不会通过 STUN 泄露真实 IP；对新加载的页面生效
        if (disabled) setBool("media.peerconnection.enabled", false) else clear("media.peerconnection.enabled")
    }

    /* ------------------------------------------------------------------ */
    /* 指纹保护                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 四个开关 → Gecko Fingerprinting Protection 的目标。overrides 以 `-AllTargets` 开头清空默认集合，
     * 再逐个 `+目标` 打开，这样只启用用户勾选的类别（与 WebView 侧「每类一个开关」一致）。
     */
    private fun fpOverrides(): String? {
        val targets = buildList {
            if (AdBlock.fpCanvas) add("CanvasRandomization")
            if (AdBlock.fpWebgl) {
                add("WebGLRenderCapability")
                add("WebGLRenderInfo")
            }
            if (AdBlock.fpAudio) {
                add("AudioSampleRate")
                add("AudioContext")
            }
            if (AdBlock.fpFonts) {
                add("FontVisibilityBaseSystem")
                add("FontVisibilityLangPack")
                add("FontVisibilityRestrictGenerics")
            }
        }
        if (targets.isEmpty()) return null
        return "-AllTargets," + targets.joinToString(",") { "+$it" }
    }

    private fun applyFingerprint(runtime: GeckoRuntime) {
        val overrides = fpOverrides()
        runCatching {
            val s = runtime.settings
            if (overrides == null) {
                s.setFingerprintingProtection(false)
                s.setFingerprintingProtectionPrivateBrowsing(false)
            } else {
                s.setFingerprintingProtectionOverrides(overrides)
                s.setFingerprintingProtection(true)
                s.setFingerprintingProtectionPrivateBrowsing(true)
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 代理                                                                */
    /* ------------------------------------------------------------------ */

    private fun applyProxy(
        enabled: Boolean,
        type: Int,
        rawHost: String,
        port: Int,
        auth: Boolean,
        user: String,
        pass: String,
    ) {
        val generation = ++proxyGeneration
        val host = WebViewProxy.normalizeHost(rawHost)
        authEnabled = enabled && auth && type != 1 && host.isNotEmpty()
        authHost = if (enabled) host else ""
        authUser = user
        authPass = pass

        if (!enabled || host.isEmpty() || port !in 1..65535) {
            closeRelay()
            clearProxyPrefs()
            return
        }

        if (type == 1) {
            if (auth && user.isNotEmpty()) {
                // SOCKS5 + 账号密码：本机起免认证中转，Gecko 连中转，中转向上游认证
                val key = "$host|$port|$user|$pass"
                val current = relay
                if (current != null && relayKey == key && current.isAlive) {
                    setSocks("127.0.0.1", current.port)
                    return
                }
                closeRelay()
                // 本地监听放到后台线程，避免主线程网络调用
                Thread {
                    val made = runCatching { Socks5Relay(host, port, user, pass).also { it.start() } }
                    mainHandler.post {
                        val r = made.getOrNull()
                        if (generation != proxyGeneration) {
                            r?.close()
                            return@post
                        }
                        if (r == null) {
                            Diag.log("gecko proxy: SOCKS5 本地中转启动失败：${made.exceptionOrNull()?.message}")
                            return@post
                        }
                        relay = r
                        relayKey = key
                        setSocks("127.0.0.1", r.port)
                    }
                }.apply { isDaemon = true }.start()
            } else {
                closeRelay()
                setSocks(host, port)
            }
            return
        }

        closeRelay()
        setHttp(host, port)
    }

    /** HTTP 代理：HTTP 与 HTTPS（CONNECT）都走它 */
    private fun setHttp(host: String, port: Int) {
        setInt("network.proxy.type", 1)
        setString("network.proxy.http", host)
        setInt("network.proxy.http_port", port)
        setString("network.proxy.ssl", host)
        setInt("network.proxy.ssl_port", port)
        clear("network.proxy.socks")
        clear("network.proxy.socks_port")
        clear("network.proxy.socks_version")
        clear("network.proxy.socks_remote_dns")
        Diag.log("gecko proxy: http $host:$port")
    }

    /** SOCKS5 代理：域名交给代理解析，避免 DNS 泄露 */
    private fun setSocks(host: String, port: Int) {
        setInt("network.proxy.type", 1)
        setString("network.proxy.socks", host)
        setInt("network.proxy.socks_port", port)
        setInt("network.proxy.socks_version", 5)
        setBool("network.proxy.socks_remote_dns", true)
        clear("network.proxy.http")
        clear("network.proxy.http_port")
        clear("network.proxy.ssl")
        clear("network.proxy.ssl_port")
        Diag.log("gecko proxy: socks5 $host:$port")
    }

    /** 关闭代理：清掉我们写过的用户值，回到 Gecko 默认（跟随系统代理） */
    private fun clearProxyPrefs() {
        listOf(
            "network.proxy.type",
            "network.proxy.http", "network.proxy.http_port",
            "network.proxy.ssl", "network.proxy.ssl_port",
            "network.proxy.socks", "network.proxy.socks_port",
            "network.proxy.socks_version", "network.proxy.socks_remote_dns",
        ).forEach { clear(it) }
    }

    private fun closeRelay() {
        relay?.close()
        relay = null
        relayKey = null
    }

    /**
     * HTTP 代理要账号密码时，Gecko 收到 407 会走 [GeckoSession.PromptDelegate.onAuthPrompt]。
     * 只处理「来自代理」且「设置里开了认证」的提示；其它提示（网页自己的 HTTP 认证等）仍按原来的方式忽略。
     * 已经提交过一次但被拒（PREVIOUS_FAILED）则放弃，避免账号错误时无限重试。
     */
    val authDelegate = object : GeckoSession.PromptDelegate {
        override fun onAuthPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AuthPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val flags = prompt.authOptions.flags
            val fromProxy = flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.PROXY != 0
            if (!fromProxy || !authEnabled) return null
            val failedBefore = flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.PREVIOUS_FAILED != 0
            return if (failedBefore) {
                GeckoResult.fromValue(prompt.dismiss())
            } else {
                GeckoResult.fromValue(prompt.confirm(authUser, authPass))
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 偏好读写：失败只记日志，不影响浏览                                       */
    /* ------------------------------------------------------------------ */

    private fun setBool(name: String, value: Boolean) = pref(name) {
        GeckoPreferenceController.setGeckoPref(name, value, USER)
    }

    private fun setInt(name: String, value: Int) = pref(name) {
        GeckoPreferenceController.setGeckoPref(name, Integer.valueOf(value), USER)
    }

    private fun setString(name: String, value: String) = pref(name) {
        GeckoPreferenceController.setGeckoPref(name, value, USER)
    }

    private fun clear(name: String) = pref(name) {
        GeckoPreferenceController.clearGeckoUserPref(name)
    }

    private inline fun pref(name: String, block: () -> GeckoResult<Void>) {
        runCatching { block() }.onFailure { Diag.log("gecko pref $name: ${it.message}") }
    }
}
