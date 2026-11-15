package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.crypto.BiometricGate
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.PasswordDb
import dev.dsh.miuixbrowser.data.PasswordPrefs
import dev.dsh.miuixbrowser.engine.webview.WebOrigin
import dev.dsh.miuixbrowser.util.Diag
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * WebView 标签的密码填充 / 保存：与 Gecko 标签用同一套界面（[LoginPrompt] → BrowserScreen 的 [LoginPromptDialog]）。
 *
 * 以前 WebView 走系统自动填充服务（[MafariAutofillService]），下拉和保存提示由系统绘制，样式改不了；
 * 现在本应用的 WebView 关掉系统自动填充，改由页面里的 `assets/mafari_login.js` 检测登录框，经 WebMessageListener
 * （对象名 [NAME]）通知这里，再弹应用自己的玻璃弹窗。
 *
 * 安全约束（同 [TrustedChannel]）：
 * - 站点只信框架给的 sourceOrigin / isMainFrame，绝不读消息里自带的地址；非主框架一律丢弃；
 * - 消息只接受字符串、长度有上限、op 在白名单内；
 * - 没有任何回传，已保存的凭据只由原生在用户点选（并按设置通过身份验证）后用 evaluateJavascript 填进页面；
 * - 站点匹配沿用 [PasswordAutofill.usable]（严格同站、https 凭据不填 http 页面、不含 HTTP 认证凭据）。
 *
 * 不引用任何 `Gecko*` 类（lite 版里不存在）。每个 WebView 标签一个实例。
 */
internal class WebLoginChannel(private val host: Host) {

    interface Host {
        val appContext: Context
        val incognito: Boolean
        fun activity(): Activity?
        /** 标签当前的网页地址（主线程读取），用来丢弃「页面已经换了」的过期请求 */
        fun currentUrl(): String
        /** 交给界面弹窗；界面还没挂上时返回 false */
        fun showPrompt(p: LoginPrompt): Boolean
        /** 在页面里执行脚本（主线程） */
        fun evalJs(js: String)
    }

    private var lastSubmitKey = 0
    private var lastSubmitAt = 0L

    /**
     * 在首次加载网页之前调用。要求 WEB_MESSAGE_LISTENER 和 DOCUMENT_START_SCRIPT 都支持；
     * 返回 false 时调用方不要关掉系统自动填充，保持原来的行为。
     */
    fun install(webView: WebView): Boolean {
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (!supported) return false
        val js = runCatching {
            host.appContext.assets.open("mafari_login.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return false
        return runCatching {
            // "*" 只是让对象注入所有框架；真正的来源 / 主框架校验在 dispatch 里按框架给的值做
            WebViewCompat.addWebMessageListener(webView, NAME, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                dispatch(message, sourceOrigin.toString(), isMainFrame)
            }
            WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
            true
        }.onFailure { Diag.log("web login channel install failed ${it.message}") }.getOrDefault(false)
    }

    internal fun dispatch(message: WebMessageCompat, sourceOrigin: String, isMainFrame: Boolean) {
        if (!isMainFrame) return
        val origin = WebOrigin.parse(sourceOrigin) ?: return
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val text = message.data ?: return
        if (text.length > MAX_MESSAGE_CHARS) return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("op")) {
            "focus" -> onFocus(origin)
            "submit" -> onSubmit(origin, json.optString("u"), json.optString("p"))
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 填充：聚焦登录框 → 账号列表

    private fun onFocus(origin: WebOrigin) {
        val ctx = host.appContext
        if (!PasswordPrefs.fillEnabled(ctx)) return
        io.execute {
            val items = runCatching {
                AppDatabase.get(ctx)?.let { db ->
                    PasswordDb.list(db)
                        .filter { PasswordAutofill.usable(it.origin, it.httpRealm, origin.host, origin.scheme) }
                        .sortedWith(compareByDescending<PasswordDb.Item> { it.timeLastUsed }.thenBy { it.username.lowercase() })
                        .take(MAX_ITEMS)
                }
            }.getOrNull().orEmpty()
            if (items.isEmpty()) return@execute
            main.post { showSelect(origin, items) }
        }
    }

    private fun showSelect(origin: WebOrigin, items: List<PasswordDb.Item>) {
        // 异步期间页面已经换了：不再弹
        if (WebOrigin.parse(host.currentUrl()) != origin) return
        host.showPrompt(
            LoginPrompt.Select(
                pageHost = origin.host,
                items = items.map { LoginPrompt.Select.Item(it.origin, it.username) },
                onPick = { i -> items.getOrNull(i)?.let { pick(origin, it) } },
                onDismiss = {},
            ),
        )
    }

    private fun pick(origin: WebOrigin, item: PasswordDb.Item) {
        val ctx = host.appContext
        if (!PasswordPrefs.verifyBeforeFill(ctx)) {
            fill(origin, item)
            return
        }
        // 需要验证身份却没有可用界面：宁可不填
        val act = host.activity() ?: return
        BiometricGate.authenticate(act, "验证身份", "验证后才会填入账号和密码") { ok ->
            if (ok) fill(origin, item)
        }
    }

    private fun fill(origin: WebOrigin, item: PasswordDb.Item) {
        val ctx = host.appContext
        io.execute {
            val entry = runCatching {
                AppDatabase.get(ctx)?.let { db ->
                    PasswordDb.reveal(ctx, db, item.guid)?.also { PasswordDb.markUsed(db, item.guid) }
                }
            }.getOrNull() ?: return@execute
            main.post {
                if (WebOrigin.parse(host.currentUrl()) != origin) return@post
                host.evalJs(
                    "window.__mafariLoginFill&&window.__mafariLoginFill(" +
                        JSONObject.quote(entry.username) + "," + JSONObject.quote(entry.password) + ")",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 保存：表单提交 → 保存 / 更新密码

    private fun onSubmit(origin: WebOrigin, user: String, pass: String) {
        val ctx = host.appContext
        if (host.incognito || !PasswordPrefs.saveEnabled(ctx)) return
        if (pass.isEmpty() || pass.length > MAX_PASS_CHARS || user.length > MAX_USER_CHARS) return
        // 同一份凭据短时间内（click + submit 各报一次）只处理一次；只记哈希，不留明文
        val key = (origin.origin + "\u0000" + user + "\u0000" + pass).hashCode()
        val now = SystemClock.elapsedRealtime()
        if (key == lastSubmitKey && now - lastSubmitAt < DEDUP_MS) return
        lastSubmitKey = key
        lastSubmitAt = now
        val originStr = PasswordCsv.originOf(origin.origin) ?: return
        io.execute {
            val db = AppDatabase.get(ctx) ?: return@execute
            val old = runCatching { PasswordDb.findSaved(ctx, db, originStr, user, null) }.getOrNull()
            if (old != null && old.password == pass) return@execute // 已存且没变：不打扰
            val now2 = System.currentTimeMillis()
            val entry = PasswordEntry(
                guid = "", origin = originStr, username = user, password = pass,
                timeCreated = now2, timeLastUsed = now2, timePasswordChanged = now2, timesUsed = 1,
            )
            main.post {
                // 提交后页面常常立刻跳转，所以这里不比对当前地址：凭据本来就绑定在 originStr 上
                host.showPrompt(
                    LoginPrompt.Save(
                        origin = originStr,
                        username = user,
                        update = old != null,
                        onSave = {
                            io.execute {
                                runCatching { AppDatabase.get(ctx)?.let { PasswordDb.importEntries(ctx, it, listOf(entry)) } }
                            }
                        },
                        onDismiss = {},
                    ),
                )
            }
        }
    }

    companion object {
        const val NAME = "MafariLogin"
        private const val MAX_MESSAGE_CHARS = 4096
        private const val MAX_USER_CHARS = 256
        private const val MAX_PASS_CHARS = 1024
        private const val MAX_ITEMS = 6
        private const val DEDUP_MS = 5000L

        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mafari-weblogin").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
    }
}
