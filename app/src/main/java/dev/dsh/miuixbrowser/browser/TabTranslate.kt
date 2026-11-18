package dev.dsh.miuixbrowser.browser

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.miuixbrowser.engine.gecko.GeckoTranslate
import dev.dsh.miuixbrowser.appContainer
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.translate.TranslateConfig
import dev.dsh.miuixbrowser.translate.TranslateException
import dev.dsh.miuixbrowser.translate.TranslateManager
import dev.dsh.miuixbrowser.translate.TranslateSourceId
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * 一个标签页的页内翻译（从 BrowserTab 拆出的协作类）：持有界面要看的状态，并在页面脚本与翻译服务之间转发。
 *
 *   页面脚本 --req--> [handle] --> [TranslateManager]（工作线程，真正发请求）--> 主线程 --res/err--> 页面脚本
 *
 * WebView：页面 → 原生走 addJavascriptInterface（[webBridge]），原生 → 页面走 evaluateJavascript；
 * Gecko：走内置拓展的原生消息端口（见 [GeckoTranslate]）。
 * 页面脚本（见 assets/mafari_translate.js）自己管 DOM；这里只管状态、配置快照和错误文案。
 */
class TabTranslate(private val tab: BrowserTab) {

    enum class Phase { IDLE, TRANSLATING, DONE, ERROR, ORIGINAL }

    private val main = Handler(Looper.getMainLooper())

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set

    /** 失败时的说明（已翻成界面语言）；其它状态为 null */
    var error by mutableStateOf<String?>(null)
        private set

    /** 本页这一轮翻译使用的配置（用来显示「用的哪个源」；换源后要点「重新翻译」才生效） */
    var config by mutableStateOf<TranslateConfig?>(null)
        private set

    /** 页面上是否有译文可以切换（已开始过翻译，且没被还原） */
    val active: Boolean get() = phase != Phase.IDLE

    /** 地址栏翻译图标是否显示：翻译进行中 / 已完成 / 正显示原文（出错时不显示，错误信息在翻译面板里） */
    val iconVisible: Boolean get() = phase == Phase.TRANSLATING || phase == Phase.DONE || phase == Phase.ORIGINAL

    /** 正在显示原文（图标变灰）；否则是译文（图标主题色） */
    val showingOriginal: Boolean get() = phase == Phase.ORIGINAL

    /** 地址栏图标点击：原文 ↔ 译文 */
    fun toggleOriginal() {
        if (phase == Phase.ORIGINAL) showTranslation() else showOriginal()
    }

    /** 「始终翻译此页面」：Gecko 内容脚本还没连上时先记着，握手后再开始 */
    private var autoPending = false

    /**
     * 页面加载完成时调用：当前页在「始终翻译」名单里、且还没开始翻译，就按当前设置自动翻译。
     * 换页 / 重新加载后文档里的译文已经没了，所以每次加载完成都会重新判断。
     */
    fun autoStartIfNeeded() {
        if (tab.closed || phase != Phase.IDLE) return
        val settings = tab.appContext.appContainer.settings
        if (!settings.isAlwaysTranslate(tab.url)) return
        if (settings.translateSource == TranslateSourceId.CUSTOM && settings.translateCustomUrl.isBlank()) return
        if (tab.isGecko && port == null) {
            autoPending = true
            return
        }
        autoPending = false
        start(settings.translateConfig())
    }

    /** 页面脚本最近一次登记的 Gecko 端口 */
    @Volatile private var port: WebExtension.Port? = null

    /** 每次开始 / 停止 / 换页 +1：丢弃过期的异步结果 */
    @Volatile private var generation = 0

    // ------------------------------------------------------------------ 对外操作（主线程）

    /** 用 [cfg] 翻译当前页面（已经译过则按新配置重新翻）。返回 false 表示暂时做不到（Gecko 拓展没就绪）。 */
    fun start(cfg: TranslateConfig): Boolean {
        if (tab.closed) return false
        if (tab.isGecko && port == null) {
            android.widget.Toast.makeText(tab.appContext, tr("翻译尚未就绪，请稍后再试"), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        generation++
        config = cfg
        error = null
        phase = Phase.TRANSLATING
        done = 0
        total = 0
        if (tab.isGecko) {
            send(JSONObject().put("t", "start").put("target", cfg.target))
        } else {
            tab.evaluateJs(TranslateMode.startScript(TranslateMode.source(tab.appContext), cfg.target))
        }
        return true
    }

    fun retry() {
        if (phase != Phase.ERROR) return
        error = null
        phase = Phase.TRANSLATING
        if (tab.isGecko) send(JSONObject().put("t", "retry"))
        else tab.evaluateJs(TranslateMode.retryScript(TranslateMode.source(tab.appContext)))
    }

    fun showOriginal() {
        if (!active) return
        if (tab.isGecko) send(JSONObject().put("t", "original")) else tab.evaluateJs(TranslateMode.ORIGINAL_SCRIPT)
    }

    fun showTranslation() {
        if (phase != Phase.ORIGINAL) return
        if (tab.isGecko) send(JSONObject().put("t", "translation")) else tab.evaluateJs(TranslateMode.TRANSLATION_SCRIPT)
    }

    /** 还原网页并结束翻译 */
    fun stop() {
        if (!active) return
        generation++
        if (tab.isGecko) send(JSONObject().put("t", "stop")) else tab.evaluateJs(TranslateMode.STOP_SCRIPT)
        reset()
    }

    /** 新文档开始加载：页面上的译文和脚本状态都随旧文档消失 */
    fun onNavigation() {
        generation++
        autoPending = false
        reset()
    }

    private fun reset() {
        phase = Phase.IDLE
        done = 0
        total = 0
        error = null
    }

    // ------------------------------------------------------------------ 页面 → 原生

    /** WebView 桥：addJavascriptInterface 对所有框架、所有脚本可见，所以只在翻译进行中才受理，并限制单次大小。 */
    inner class WebBridge {
        @JavascriptInterface
        fun post(json: String?) {
            val msg = runCatching { JSONObject(json ?: return) }.getOrNull() ?: return
            main.post { handle(msg) { reply -> tab.evaluateJs(TranslateMode.recvScript(reply.toString())) } }
        }
    }

    fun webBridge() = WebBridge()

    /** Gecko：内容脚本发来的消息（主线程） */
    internal fun geckoMessage(p: WebExtension.Port, msg: JSONObject) {
        // 端口由握手消息登记；之后只受理当前端口的消息，旧页面迟到的消息不能影响新页面
        if (msg.optString("t") == "hello") {
            port = p
            if (autoPending) autoStartIfNeeded()
            return
        }
        if (p !== port) return
        handle(msg) { reply -> send(reply) }
    }

    /** Gecko：内容脚本断开（页面离开 / 重新加载）。只处理当前端口，旧页面迟到的断开不能清掉新页面的状态。 */
    internal fun geckoGone(p: WebExtension.Port) {
        if (p !== port) return
        port = null
        generation++
        reset()
    }

    private fun send(msg: JSONObject) {
        val p = port ?: return
        GeckoTranslate.send(p, msg)
    }

    private fun handle(msg: JSONObject, reply: (JSONObject) -> Unit) {
        if (tab.closed) return
        when (msg.optString("t")) {
            "state" -> onState(msg)
            "req" -> onRequest(msg, reply)
        }
    }

    private fun onState(msg: JSONObject) {
        // 只有 start() 能让标签离开空闲状态：换页 / 停止之后迟到的旧状态消息一律忽略
        if (phase == Phase.IDLE) return
        val p = when (msg.optString("phase")) {
            "translating" -> Phase.TRANSLATING
            "done" -> Phase.DONE
            "error" -> Phase.ERROR
            "original" -> Phase.ORIGINAL
            else -> Phase.IDLE
        }
        phase = p
        done = msg.optInt("done")
        total = msg.optInt("total")
        error = if (p == Phase.ERROR) describe(msg.optJSONObject("err")) else null
    }

    private fun onRequest(msg: JSONObject, reply: (JSONObject) -> Unit) {
        val cfg = config
        if (phase == Phase.IDLE || cfg == null) return
        val id = msg.optInt("id", -1)
        val arr = msg.optJSONArray("texts") ?: return
        if (id < 0 || arr.length() == 0 || arr.length() > MAX_TEXTS) return
        val texts = ArrayList<String>(arr.length())
        var chars = 0
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            chars += s.length
            texts += s
        }
        if (chars > MAX_CHARS) return
        val gen = generation
        TranslateManager.submit(cfg, texts) { result ->
            main.post {
                if (gen != generation || tab.closed) return@post
                result.onSuccess { out ->
                    reply(JSONObject().put("t", "res").put("id", id).put("texts", JSONArray(out)))
                }.onFailure { e ->
                    val te = e as? TranslateException
                    reply(
                        JSONObject().put("t", "err").put("id", id)
                            .put("kind", te?.kind?.name ?: "PARSE").put("detail", te?.detail.orEmpty()),
                    )
                }
            }
        }
    }

    /** 页面脚本回报的错误 → 界面文案 */
    private fun describe(err: JSONObject?): String {
        val kind = runCatching { TranslateException.Kind.valueOf(err?.optString("kind").orEmpty()) }.getOrNull()
        val detail = err?.optString("detail").orEmpty()
        val base = describeKind(kind)
        return if (detail.isNotEmpty() && kind != TranslateException.Kind.NETWORK) "$base（$detail）" else base
    }

    companion object {
        private const val MAX_TEXTS = 50
        private const val MAX_CHARS = 30_000

        fun describeKind(kind: TranslateException.Kind?): String = when (kind) {
            TranslateException.Kind.NETWORK -> tr("无法连接翻译服务，请检查网络或换一个翻译源")
            TranslateException.Kind.HTTP -> tr("翻译服务返回错误")
            TranslateException.Kind.AUTH -> tr("翻译服务拒绝了请求，请检查 Key 或稍后再试")
            TranslateException.Kind.CONFIG -> tr("翻译源配置不完整，请先填写服务地址")
            TranslateException.Kind.PARSE -> tr("翻译服务返回了无法识别的内容")
            null -> tr("翻译失败")
        }
    }
}
