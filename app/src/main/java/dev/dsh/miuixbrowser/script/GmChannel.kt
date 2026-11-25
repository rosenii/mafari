/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.data.GmStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * WebView 标签的油猴原生通道（隔离世界版）。设计与协议见 docs/WEBVIEW_GM_CHANNEL.md。
 *
 * ```
 * GmChannel（原生权威：值 / xhr / cookie / 下载 / 通知 / 文件流；实现复用 GmBridge）
 *   └─ addWebMessageListener("mafariGmRelay", originRules, GM 世界)      ← 回调带浏览器给的 sourceOrigin / isMainFrame
 *        ◄── mafari_gm_relay_wv.js（GM 世界，addJavaScriptOnEvent DOCUMENT_START）
 *             ◄── 私有 MessageChannel ── mafari_gm_shim.js（页面世界）◄── mafari_gm_runtime.js（页面世界）
 * ```
 * 脚本与运行时仍在页面世界（`unsafeWindow`、`@grant none` 行为不变），页面世界里没有任何原生入口。
 *
 * 每个标签一个实例（对应一个 WebView），**所有方法必须在主线程调用**；工作线程只做 DB / 文件 / 网络，结果回主线程再投递。
 * 系统 WebView 不支持隔离世界（[isSupported] 为 false）时不要创建本类，沿用旧的 `addJavascriptInterface(GmBridge)`。
 */
internal class GmChannel(
    private val context: Context,
    private val webView: WebView,
    private val bridge: GmBridge,
    private val log: (String) -> Unit = {},
) {

    companion object {
        /** GM 世界的名字（同一个 WebView 上与拓展的世界互不相通） */
        const val WORLD_NAME = "mafari-gm"
        const val LISTENER_NAME = "mafariGmRelay"
        private const val RELAY_ASSET = "mafari_gm_relay_wv.js"

        /** 单条消息上限，与中继的 MAX_MSG_CHARS 一致（xhr 请求体 base64 / 文件流分片） */
        private const val MAX_MSG_CHARS = 64 * 1024 * 1024
        private const val MAX_KEY_CHARS = 256
        private const val MAX_VALUE_CHARS = 2_000_000
        private const val MAX_TEXT_CHARS = 1_000_000
        private const val MAX_URL_CHARS = 8192

        /** 帧注册表上限：只用于广播，超出时淘汰最早登记的（它们多半是已销毁的帧） */
        private const val MAX_FRAMES = 128

        /** 值变化后重建该脚本 handler 的最小间隔，与 Gecko 一致 */
        private const val RESYNC_GAP_MS = 1_000L
        private const val RESYNC_MIN_MS = 300L

        private val SAFE_ID = Regex("[A-Za-z0-9_]{1,80}")

        /** 需要 addWebMessageListener（带 world）+ addJavaScriptOnEvent + 隔离世界，同一个特性位 */
        fun isSupported(): Boolean = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        }.getOrDefault(false)

        private val main = Handler(Looper.getMainLooper())

        /** GM 值读写串行：写在前、读在后，同一个脚本的读写顺序不会乱；DB 不占主线程 */
        private val valueWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "gm-values").apply { isDaemon = true } }

        /** 文件流（GM.openFileStream）串行：逐段写盘，不占主线程 */
        private val ioWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "gm-stream").apply { isDaemon = true } }

        // ---- 值变化分发：GmStore 的变化（本通道 / 其它标签 / 旧桥 / Gecko 任一方写入）→ 所有活着的 GmChannel
        private val instances = CopyOnWriteArraySet<GmChannel>()

        /** 「脚本 id::键」→ 写入方的 src（shim 的实例标识）。广播时带上，写入方据此忽略自己的回声 */
        private val writers = ConcurrentHashMap<String, String>()

        private val storeListener: (String, String, String?) -> Unit = { sid, key, value ->
            val src = writers["$sid::$key"].orEmpty()
            instances.forEach { it.onValueChanged(sid, key, value, src) }
        }

        private fun attach(c: GmChannel) {
            if (instances.isEmpty()) GmStore.addListener(storeListener)
            instances.add(c)
        }

        private fun detach(c: GmChannel) {
            instances.remove(c)
            if (instances.isEmpty()) GmStore.removeListener(storeListener)
        }
    }

    // ------------------------------------------------------------------ 状态（均在主线程）

    private val guard = GmGuard()

    /** 每个脚本的 @connect 策略（随令牌一起登记 / 作废） */
    private val connectPolicies = HashMap<String, GmConnectPolicy>()
    private var closed = false

    private var gmWorld: JavaScriptExecutionWorld? = null
    private var pageWorld: JavaScriptExecutionWorld? = null

    /** 当前装着的 listener / 中继对应的 origin 规则；变化（脚本集的 origin 并集变了）就整体重装 */
    private var listenerRules: Set<String>? = null
    private var relayHandler: ScriptHandler? = null

    private class Hosted(val key: String, val handler: ScriptHandler?)
    private val hosted = LinkedHashMap<String, Hosted>()

    /** 每个脚本的值版本：值变一次 +1，进脚本 handler 的签名，所以只有受影响的脚本会重建 */
    private val valVer = HashMap<String, Int>()
    private var lastScripts: List<UserScript> = emptyList()
    private val resyncPending = HashSet<String>()
    private val resyncAt = HashMap<String, Long>()

    /** 帧注册表：回复代理 → 已绑定的脚本 id。只用于值广播；校验不依赖它（不假设同一帧每次回调给同一个实例） */
    private class Frame(val reply: JavaScriptReplyProxy) { val sids = HashSet<String>() }
    private val frames = ArrayList<Frame>()

    init {
        attach(this)
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 让 WebView 上的通道与 [scripts]（已启用的）一致。可反复调用：脚本集 / 代码 / 匹配 / 资源缓存 / 值变化时，
     * 只重建受影响的脚本 handler；脚本的 origin 并集变了才整体重装监听器与中继。
     * 返回 false = 安装失败（已清理干净），调用方应退回旧路径。
     */
    fun sync(scripts: List<UserScript>): Boolean {
        if (closed) return false
        lastScripts = scripts
        if (scripts.isEmpty()) {
            uninstallAll()
            guard.clear()
            connectPolicies.clear()
            return true
        }
        val scopes = scripts.associate { it.id to scopeOf(it) }
        val rules = GmOriginScope.unionRules(scopes.values)
        if (listenerRules != rules) {
            uninstallAll()
            // 顺序（见规格）：监听器必须先于中继，中继必须先于各脚本 handler
            if (!installBase(rules)) return false
        }

        val live = scripts.map { it.id }.toSet()
        hosted.keys.filter { it !in live }.forEach { id -> hosted.remove(id)?.handler?.let { runCatching { it.remove() } } }
        guard.retain(live)
        connectPolicies.keys.retainAll(live)

        val pageWorld = pageWorld ?: return false
        for (script in scripts) {
            val scope = scopes.getValue(script.id)
            val key = "${script.code.hashCode()}:${script.match.hashCode()}:${ScriptResources.cachedCount(context, script.code)}:${valVer[script.id] ?: 0}"
            val token = guard.register(script.id, scope) // 沿用已有令牌：已打开的页面快照里的旧令牌继续有效
            connectPolicies[script.id] = GmConnectPolicy(UserscriptEngine.parse(script.code).raw["connect"].orEmpty())
            if (hosted[script.id]?.key == key) continue
            hosted.remove(script.id)?.handler?.let { runCatching { it.remove() } }
            try {
                val source = UserscriptEngine.buildChannelSource(context, script, token)
                val handler = WebViewCompat.addJavaScriptOnEvent(webView, source, WebViewCompat.INJECTION_EVENT_DOCUMENT_START, scope.originRules(), pageWorld)
                hosted[script.id] = Hosted(key, handler)
            } catch (e: Exception) {
                log("gm channel: install ${script.name} failed ${e.message}")
                close()
                return false
            }
        }
        log("gm channel: scripts=${hosted.size} rules=${rules.take(6)} frames=${frames.size}")
        return true
    }

    private fun scopeOf(script: UserScript): GmOriginScope =
        GmOriginScope.of(UserscriptEngine.userMatchOverride(script), UserscriptEngine.parse(script.code))

    private fun installBase(rules: Set<String>): Boolean {
        return try {
            val gm = WebViewCompat.getExecutionWorld(webView, WORLD_NAME)
            val page = WebViewCompat.getExecutionWorld(webView, JavaScriptExecutionWorld.PAGE_WORLD_NAME)
            val relay = dev.dsh.miuixbrowser.browser.InjectAssets.read(context, RELAY_ASSET) ?: error("$RELAY_ASSET missing")
            WebViewCompat.addWebMessageListener(webView, LISTENER_NAME, rules, gm) { _, message, sourceOrigin, isMainFrame, reply ->
                onMessage(message, sourceOrigin.toString(), isMainFrame, reply)
            }
            gmWorld = gm
            pageWorld = page
            listenerRules = rules
            relayHandler = WebViewCompat.addJavaScriptOnEvent(webView, relay, WebViewCompat.INJECTION_EVENT_DOCUMENT_START, rules, gm)
            true
        } catch (e: Exception) {
            log("gm channel: base install failed ${e.message}")
            uninstallAll()
            false
        }
    }

    /** 摘掉监听器、中继和所有脚本 handler（令牌不动：已打开的页面仍可用，直到脚本被停用 / 删除） */
    private fun uninstallAll() {
        hosted.values.forEach { h -> h.handler?.let { runCatching { it.remove() } } }
        hosted.clear()
        relayHandler?.let { runCatching { it.remove() } }
        relayHandler = null
        gmWorld?.let { w -> runCatching { WebViewCompat.removeWebMessageListener(webView, w, LISTENER_NAME) } }
        gmWorld = null
        pageWorld = null
        listenerRules = null
    }

    /** 标签销毁 / 脚本总开关关闭 / 降级旧路径时调用；之后本实例不可再用 */
    fun close() {
        if (closed) return
        closed = true
        detach(this)
        uninstallAll()
        guard.clear()
        connectPolicies.clear()
        frames.clear()
        resyncPending.clear()
    }

    // ------------------------------------------------------------------ 上行：中继 → 原生

    private fun onMessage(message: WebMessageCompat, origin: String, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        if (closed || message.type != WebMessageCompat.TYPE_STRING) return
        val text = message.data ?: return
        if (text.length > MAX_MSG_CHARS) return
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        val t = m.optString("t")
        val sid = m.optString("sid")
        val pid = m.optInt("pid", -1)
        // 每条消息都校验：令牌 + 浏览器给出的 sourceOrigin 在该脚本匹配范围内（页面伪造不了 sourceOrigin，也偷不到别的脚本的令牌）
        if (!guard.authorize(sid, m.optString("tok"), origin)) {
            if (t == "bind") send(reply, JSONObject().put("t", "bound").put("pid", pid).put("ok", false))
            log("gm channel: rejected $t (token/origin) origin=$origin")
            return
        }
        try {
            dispatch(t, sid, pid, m, isMainFrame, origin, reply)
        } catch (e: Exception) {
            log("gm channel: handle $t failed ${e.message}")
        }
    }

    private fun dispatch(t: String, sid: String, pid: Int, m: JSONObject, isMainFrame: Boolean, origin: String, reply: JavaScriptReplyProxy) {
        when (t) {
            "bind" -> {
                register(reply, sid)
                send(reply, JSONObject().put("t", "bound").put("pid", pid).put("ok", true))
            }
            "set" -> {
                val k = m.optString("k")
                val v = m.optString("v")
                if (k.isEmpty() || k.length > MAX_KEY_CHARS || v.length > MAX_VALUE_CHARS) return
                if (!guard.allow(sid, "set", 600, 10_000)) return
                // 再按字符数限：10s 内最多写 16M 字符，防止少量次数的大值刷存储 / 广播
                if (!guard.allow(sid, "setBytes", 16_000_000, 10_000, (k.length + v.length).toLong())) return
                writeValue(sid, k, m.optString("src")) { GmStore.set(context, sid, k, v) }
            }
            "del" -> {
                val k = m.optString("k")
                if (k.isEmpty() || k.length > MAX_KEY_CHARS || !guard.allow(sid, "set", 600, 10_000)) return
                writeValue(sid, k, m.optString("src")) { GmStore.delete(context, sid, k) }
            }
            "getValue" -> { // 大值按需拉取：只回给发起的这个框架
                val k = m.optString("k")
                if (k.isEmpty() || k.length > MAX_KEY_CHARS || !guard.allow(sid, "get", 300, 10_000)) return
                valueWorker.execute {
                    val raw = runCatching { GmStore.get(context, sid, k) }.getOrNull()
                    send(reply, JSONObject().put("t", "valRes").put("pid", pid).put("id", sid).put("k", k).put("raw", raw ?: JSONObject.NULL))
                }
            }
            "xhr" -> {
                val reqId = m.optString("reqId")
                val opts = m.optString("opts")
                if (reqId.isEmpty() || reqId.length > 200 || opts.isEmpty()) return
                // 桥内的请求键：脚本 id + 运行时生成的随机 reqId（运行时的 reqId 本身就以脚本 id 开头）
                val key = "$sid#$reqId"
                val policy = connectPolicies[sid] ?: GmConnectPolicy(emptyList())
                val pageHost = pageHostOf(origin)
                bridge.xhrExec(sid, key, opts, m.optString("body"), { host -> policy.xhrAllows(host, pageHost) }) { json ->
                    send(reply, JSONObject().put("t", "xhrRes").put("pid", pid).put("reqId", reqId).put("json", json))
                }
            }
            "xhrAbort" -> {
                val reqId = m.optString("reqId")
                if (reqId.isNotEmpty() && reqId.length <= 200) bridge.abortXhr("$sid#$reqId")
            }
            "cookieList" -> {
                val url = m.optString("url")
                val cbId = m.optString("cbId")
                if (url.length > MAX_URL_CHARS || cbId.isEmpty() || cbId.length > 100 || !guard.allow(sid, "cookie", 120, 10_000)) return
                // GM_cookie 比 xhr 更严：只给同站或 @connect 明确列出的主机，其余按「没有 Cookie」回复（不报错、不泄露存在与否）
                val arr = if (cookieAllowed(sid, url, origin)) {
                    runCatching { JSONArray(bridge.cookieList(url)) }.getOrDefault(JSONArray())
                } else JSONArray()
                send(reply, JSONObject().put("t", "cookieRes").put("pid", pid).put("cbId", cbId).put("arr", arr))
            }
            "cookieSet" -> {
                val opts = m.optJSONObject("opts") ?: return
                if (!guard.allow(sid, "cookie", 120, 10_000)) return
                if (!cookieAllowed(sid, opts.optString("url"), origin)) return
                bridge.cookieSet(opts.toString())
            }
            "toast" -> if (guard.allow(sid, "toast", 10, 10_000)) bridge.toast(m.optString("msg").take(500))
            "copy" -> if (guard.allow(sid, "copy", 10, 10_000)) bridge.copy(m.optString("text").take(MAX_TEXT_CHARS))
            "copyHtml" -> if (guard.allow(sid, "copy", 10, 10_000)) bridge.copyHtml(m.optString("html").take(MAX_TEXT_CHARS))
            "openTab" -> {
                val url = m.optString("url")
                if (url.length > MAX_URL_CHARS || !guard.allow(sid, "openTab", 5, 10_000)) return
                bridge.openTabWithId(m.optString("id").take(100), url, m.optString("opts", "{}"))
            }
            "closeTab" -> bridge.closeTab(m.optString("id").take(100))
            "download" -> download(sid, pid, m, reply)
            "notify" -> notify(sid, pid, m, reply)
            "host" -> {
                val cbId = m.optString("cbId")
                val op = m.optString("op")
                if (!SAFE_ID.matches(cbId) || op.length > 20 || !guard.allow(sid, "host", 60, 10_000)) return
                bridge.hostTo(op, m.optString("json").take(20_000), cbId) { fn, args -> cb(reply, pid, fn, args) }
            }
            "stream" -> stream(sid, pid, m, reply)
            // 菜单命令 / 「本页运行了哪些脚本」：运行时仍在页面世界，原生靠 evaluateJavascript 读 window.__mafariMenuList / __mafariRan
            // （TabScripts.queryMenuCommands / queryRunningScripts），不需要中继上报；只校验是主框架，其余丢弃
            "menu", "ran" -> if (!isMainFrame) return
            else -> {} // 白名单之外（中继已过滤一次）：丢弃
        }
    }

    /**
     * 发起请求的页面主机：优先用浏览器给出的 sourceOrigin；about:blank / srcdoc / 不透明 origin（`null`）的子框架
     * 没有主机，退回标签当前地址（与 Gecko 侧「退回标签地址」一致）。主线程。
     */
    private fun pageHostOf(origin: String): String =
        GmConnectPolicy.hostOf(origin).ifEmpty { GmConnectPolicy.hostOf(webView.url) }

    private fun cookieAllowed(sid: String, url: String, origin: String): Boolean {
        if (url.length > MAX_URL_CHARS) return false
        val host = GmConnectPolicy.hostOf(url)
        if (host.isEmpty()) return false
        return (connectPolicies[sid] ?: GmConnectPolicy(emptyList())).cookieAllows(host, pageHostOf(origin))
    }

    private fun download(sid: String, pid: Int, m: JSONObject, reply: JavaScriptReplyProxy) {
        val url = m.optString("url")
        val name = m.optString("name").take(255)
        val cbId = m.optString("cbId")
        val withCb = cbId.isNotEmpty()
        val fn = "__mafariDlCallback_$cbId"
        if (withCb && !SAFE_ID.matches(cbId)) return
        if (url.length > MAX_URL_CHARS) {
            if (withCb) cb(reply, pid, fn, JSONArray().put(false).put("unsupported url"))
            return
        }
        if (!guard.allow(sid, "download", 5, 10_000)) {
            if (withCb) cb(reply, pid, fn, JSONArray().put(false).put("too many downloads"))
            return
        }
        if (withCb) bridge.downloadTo(url, name, cbId) { f, args -> cb(reply, pid, f, args) } else bridge.download(url, name)
    }

    private fun notify(sid: String, pid: Int, m: JSONObject, reply: JavaScriptReplyProxy) {
        val cbId = m.optString("cbId")
        if (!SAFE_ID.matches(cbId) || !guard.allow(sid, "notify", 10, 60_000)) return
        val json = m.optString("json")
        runCatching {
            bridge.notificationTo(json, cbId) { fn, args -> cb(reply, pid, fn, args) }
        }.onFailure { // 无通知权限：退回 toast，不伪造 ondone（与旧桥里运行时的降级一致）
            val n = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            val title = n.optString("title")
            val text = n.optString("text")
            bridge.toast((if (title.isEmpty()) text else "$title：$text").take(500))
        }
    }

    private fun stream(sid: String, pid: Int, m: JSONObject, reply: JavaScriptReplyProxy) {
        val op = m.optString("op")
        val cbId = m.optString("cbId")
        fun res(r: String) = send(reply, JSONObject().put("t", "streamRes").put("pid", pid).put("cbId", cbId).put("res", r))
        when (op) {
            "open" -> {
                if (!guard.allow(sid, "streamOpen", 10, 10_000)) { res(JSONObject().put("error", "too many streams").toString()); return }
                val name = m.optString("name").take(255)
                val mime = m.optString("mime").take(100)
                ioWorker.execute { res(bridge.streamOpenImpl(name, mime)) }
            }
            "write" -> {
                val id = m.optString("id").take(100)
                val b64 = m.optString("b64")
                ioWorker.execute { res(bridge.streamWriteImpl(id, b64)) }
            }
            "close" -> {
                val id = m.optString("id").take(100)
                ioWorker.execute { res(bridge.streamCloseImpl(id)) }
            }
            "abort" -> {
                val id = m.optString("id").take(100)
                ioWorker.execute { bridge.streamAbortImpl(id) }
            }
        }
    }

    /**
     * 写值（工作线程，串行）。记下写入方 src 再写库；GmStore 在值真的变化后会 post 一次通知到主线程，
     * 紧接着我们 post 的清理在它之后执行——值没变（不会通知）时同样会被清掉，不会残留。
     */
    private fun writeValue(sid: String, key: String, src: String, op: () -> Unit) {
        val wk = "$sid::$key"
        valueWorker.execute {
            writers[wk] = src
            try { op() } finally { main.post { writers.remove(wk, src) } }
        }
    }

    // ------------------------------------------------------------------ 下行：原生 → 中继

    private fun post(reply: JavaScriptReplyProxy, text: String): Boolean = try {
        reply.postMessage(text)
        true
    } catch (_: Throwable) {
        false // 帧已销毁 / 后退缓存里
    }

    /** 任意线程。带 pid 的只发给发起的那个端口；不带 pid 的（val / menuRun）由中继按脚本 id 或全体已绑定端口分发 */
    private fun send(reply: JavaScriptReplyProxy, o: JSONObject) {
        val s = o.toString()
        if (Looper.myLooper() == Looper.getMainLooper()) post(reply, s) else main.post { if (!closed) post(reply, s) }
    }

    /** 一次性回调（下载 / 通知 / 宿主）：不再用 evaluateJavascript 推到主框架，所以 iframe 里的脚本也能收到 */
    private fun cb(reply: JavaScriptReplyProxy, pid: Int, fn: String, args: JSONArray) {
        send(reply, JSONObject().put("t", "cb").put("pid", pid).put("fn", fn).put("args", args))
    }

    private fun register(reply: JavaScriptReplyProxy, sid: String) {
        val frame = frames.firstOrNull { it.reply === reply } ?: Frame(reply).also {
            frames.add(it)
            while (frames.size > MAX_FRAMES) frames.removeAt(0)
        }
        frame.sids.add(sid)
    }

    /** 主线程（GmStore 通知）。写入方的 src 随广播带上，该框架据此忽略自己的回声 */
    private fun onValueChanged(sid: String, key: String, value: String?, src: String) {
        if (closed || sid !in hosted) return
        valVer[sid] = (valVer[sid] ?: 0) + 1
        val o = JSONObject().put("t", "val").put("id", sid).put("k", key).put("src", src)
        // 大值不广播内容（每个框架都会收到一份）：接收方按需 getValue 拉取
        if (value != null && value.length > GmSnapshot.INLINE_VALUE_MAX) o.put("big", true) else o.put("raw", value ?: JSONObject.NULL)
        val s = o.toString()
        frames.removeIf { it.sids.contains(sid) && !post(it.reply, s) }
        scheduleResync(sid)
    }

    /** 值变化后重建该脚本的 handler，让「之后新开的页面」读到最新快照；防抖 + 每脚本限频 */
    private fun scheduleResync(sid: String) {
        if (!resyncPending.add(sid)) return
        val wait = ((resyncAt[sid]?.plus(RESYNC_GAP_MS)?.minus(System.currentTimeMillis())) ?: 0L).coerceAtLeast(RESYNC_MIN_MS)
        main.postDelayed({
            resyncPending.remove(sid)
            if (closed) return@postDelayed
            resyncAt[sid] = System.currentTimeMillis()
            sync(lastScripts)
        }, wait)
    }
}
