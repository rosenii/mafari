package dev.dsh.miuixbrowser

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Gecko 内核的油猴适配：自带一个内置 WebExtension（assets/mafari-gm-ext），
 * 与 WebView 共用同一份 mafari_gm_runtime.js、同一套脚本存储与同一份 GM 值（SharedPreferences "userscript_gm"）。
 *
 * 数据流（详见 docs/GECKO_GM.md）：
 *   Kotlin 拼好每个脚本的完整源码（运行时 + @require + 值快照 + shim）
 *     → 后台端口 "mafariGmBg" 全量同步 → 后台页 contentScripts.register({world:'MAIN', js:[{code}]})
 *   页面里的脚本 → shim → 中继（ISOLATED）→ 后台页（xhr / cookie）或 会话端口 "mafariGm"（通知 / 下载 / 菜单 / 写值…）
 *
 * 注意：本文件依赖 GeckoView WebExtension 的消息 API（MessageDelegate / Port），未在本环境编译，
 * 首次接入请先 `./gradlew :app:compileDebugKotlin`，nightly 的签名若有变化只需改本文件。
 */
internal object GeckoGm {

    const val EXT_ID = "gm@mafari.dev"
    private const val EXT_URI = "resource://android/assets/mafari-gm-ext/"
    private const val NATIVE_BG = "mafariGmBg"
    private const val NATIVE_SESSION = "mafariGm"
    private const val SHIM_ASSET = "mafari_gm_gecko_shim.js"
    private const val SNAPSHOT_MARK = "__MAFARI_SNAPSHOT__"

    /** 单个脚本内联的值快照上限（字符数）。超出的键不进快照，脚本读不到——见文档「已知限制」 */
    private const val SNAPSHOT_CAP = 512 * 1024

    // ---- 安全：每个脚本一个「能力令牌」（进程内随机，不落盘）
    // 页面与脚本同处 MAIN 世界，页面能看到、伪造 DOM 事件，所以 shim ⇄ 中继走私有 MessageChannel，
    // 令牌只在该通道里传；后台页 / 原生端对每条请求都校验 (脚本 id, 令牌)。脚本被停用 / 删除时令牌立刻作废，
    // 已注入页面里的旧脚本从此无法再发起任何请求。
    private val tokens = ConcurrentHashMap<String, String>()
    private val rng = SecureRandom()

    private fun tokenFor(id: String): String = tokens.getOrPut(id) {
        val b = ByteArray(16)
        rng.nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    }

    private fun authorized(o: JSONObject): Boolean {
        val sid = o.optString("sid")
        val tok = o.optString("tok")
        val expect = tokens[sid] ?: return false
        return MessageDigest.isEqual(expect.toByteArray(), tok.toByteArray())
    }

    // ---- 安全：每个脚本、每类能力的限频，防止脚本 / 被利用的脚本刷屏（弹标签、通知、下载、剪贴板…）
    private class Bucket { var start = 0L; var count = 0 }
    private val buckets = ConcurrentHashMap<String, Bucket>()

    private fun allow(sid: String, kind: String, max: Int, windowMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val b = buckets.getOrPut("$sid|$kind") { Bucket() }
        synchronized(b) {
            if (now - b.start > windowMs) { b.start = now; b.count = 0 }
            if (b.count >= max) return false
            b.count++
            return true
        }
    }

    private const val MAX_VALUE_CHARS = 2_000_000
    private const val MAX_KEY_CHARS = 256
    private const val MAX_TEXT_CHARS = 1_000_000

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "gecko-gm").apply { isDaemon = true } }

    @Volatile private var ext: WebExtension? = null
    @Volatile private var installing = false
    @Volatile private var bgPort: WebExtension.Port? = null

    private var appContext: Context? = null
    private var shimTemplate: String? = null

    // ---- 同步状态（主线程读写，除特别说明）
    private var rev = 0
    private var syncedRev = -1
    private var payload: JSONArray = JSONArray()
    private val waiters = mutableListOf<() -> Unit>()
    private val sessions = LinkedHashMap<GeckoSession, BrowserTab>()

    // ---------------------------------------------------------------- 值存储（与 WebView 的 GmBridge 共用同一个 prefs）

    private lateinit var prefs: SharedPreferences
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        val k = key ?: return@OnSharedPreferenceChangeListener
        val i = k.indexOf("::")
        if (i <= 0) return@OnSharedPreferenceChangeListener
        val id = k.substring(0, i)
        val name = k.substring(i + 2)
        val src = writers.remove(k).orEmpty()
        valuesVersion.incrementAndGet()
        broadcast(id, name, sp.getString(k, null), src)
        scheduleResync(id)
    }
    /** 记下是哪个框架写的，广播时带上 src，该框架据此忽略自己的回声（避免乱序回声把新值盖回旧值） */
    private val writers = ConcurrentHashMap<String, String>()
    private val valuesVersion = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastInputSig = ""

    @Synchronized
    private fun initPrefs(app: Context) {
        appContext = app
        if (::prefs.isInitialized) return
        prefs = app.getSharedPreferences("userscript_gm", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener) // SharedPreferences 只弱引用监听器：由单例持有
    }

    // ---------------------------------------------------------------- 入口

    /** 主线程。Gecko 运行时创建后调用一次（幂等）。[scripts] 为启动时已知的脚本，用来尽早完成首次同步 */
    fun ensureInstalled(context: Context, scripts: List<UserScript>, enabled: Boolean) {
        val app = context.applicationContext
        initPrefs(app)
        update(app, scripts, enabled)
        if (ext != null || installing) return
        installing = true
        val runtime = GeckoRuntimeHolder.get(app)
        runtime.webExtensionController.ensureBuiltIn(EXT_URI, EXT_ID).then({ e: WebExtension? ->
            installing = false
            if (e == null) return@then GeckoResult<Void>()
            ext = e
            e.setMessageDelegate(backgroundDelegate, NATIVE_BG)
            sessions.forEach { (s, t) -> attach(s, t) }
            // 无痕标签是否跑脚本：由「允许在无痕中运行」决定（注册是全局的，没法按标签门控）
            Diag.log("gm: built-in extension ready ${e.id}")
            GeckoResult<Void>()
        }, { t: Throwable ->
            installing = false
            Diag.log("gm: ensureBuiltIn failed: $t")
            GeckoResult<Void>()
        })
    }

    /** 任意线程。脚本列表 / 总开关 / 缓存资源变化时调用；内部按签名去重，源码在后台线程拼 */
    fun update(context: Context, scripts: List<UserScript>, enabled: Boolean, force: Boolean = false) {
        val app = context.applicationContext
        initPrefs(app)
        val snapshot = if (enabled) scripts.filter { it.enabled } else emptyList()
        // 停用 / 删除的脚本：令牌立即作废（已注入的旧页面从此被后台页和原生端拒绝）
        val live = snapshot.map { it.id }.toSet()
        tokens.keys.retainAll(live)
        // 多个标签会各自调用：输入没变就不重复拼源码（值变化走 force + valuesVersion）
        val sig = enabled.toString() + "#" + valuesVersion.get() + "#" + snapshot.joinToString("|") {
            "${it.id}:${it.code.hashCode()}:${it.match.hashCode()}:${ScriptResources.cachedCount(app, it.code)}"
        }
        if (!force && sig == lastInputSig) return
        lastInputSig = sig
        worker.execute {
            val arr = JSONArray()
            snapshot.forEach { s ->
                runCatching { arr.put(buildEntry(app, s)) }
                    .onFailure { Diag.log("gm: build failed ${s.name}: ${it.message}") }
            }
            main.post {
                payload = arr
                rev++
                push()
            }
        }
    }

    /** 主线程。新建 / 采纳 Gecko 会话时调用，挂上会话级端口（通知 / 下载 / 菜单 / 写值走这里，原生由此知道是哪个标签） */
    fun attachSession(session: GeckoSession, tab: BrowserTab) {
        sessions[session] = tab
        ext?.let { attach(session, tab) }
    }

    fun detachSession(session: GeckoSession) { sessions.remove(session) }

    /**
     * 首次导航前的屏障：已注册脚本尚未在后台页生效时，把 loadUri 推迟到「已同步」或超时。
     * 没有需要注入的脚本 / 已同步完成时立即回调。
     */
    fun whenReady(timeoutMs: Long = 2000, block: () -> Unit) {
        if (payload.length() == 0 || syncedRev >= rev) { block(); return }
        var done = false
        val once = { if (!done) { done = true; block() } }
        waiters += once
        main.postDelayed({ waiters.remove(once); once() }, timeoutMs)
    }

    // ---------------------------------------------------------------- 源码拼装

    private fun template(ctx: Context): String = shimTemplate ?: ctx.assets.open(SHIM_ASSET)
        .bufferedReader(Charsets.UTF_8).use { it.readText() }.also { shimTemplate = it }

    private fun valuesJson(id: String): JSONObject {
        val out = JSONObject()
        val prefix = "$id::"
        var size = 0
        // 小值优先，尽量多放；超限的不放
        val entries = prefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value is String }
            .sortedBy { (it.value as String).length }
        for (e in entries) {
            val v = e.value as String
            if (size + v.length > SNAPSHOT_CAP) { Diag.log("gm: snapshot cap hit for $id, skipped ${e.key}"); continue }
            size += v.length
            out.put(e.key.removePrefix(prefix), v)
        }
        return out
    }

    /** 运行时 + @require（UserscriptEngine.buildSource）外面再包一层：把 shim 作为闭包变量 __mafariBridge 传入 */
    private fun buildSource(ctx: Context, script: UserScript): String {
        val runtime = UserscriptEngine.buildSource(ctx, script)
        val snap = JSONObject().put("id", script.id).put("tok", tokenFor(script.id))
            .put("values", valuesJson(script.id)).toString()
        val tpl = template(ctx)
        val at = tpl.lastIndexOf(SNAPSHOT_MARK) // 只替换文末那一个，且不二次扫描 snap 的内容
        val shim = tpl.substring(0, at) + snap + tpl.substring(at + SNAPSHOT_MARK.length)
        return "(function(__mafariBridge){\n$runtime\n})($shim);"
    }

    private fun buildEntry(ctx: Context, script: UserScript): JSONObject {
        val meta = UserscriptEngine.parse(script.code)
        val code = buildSource(ctx, script)
        val spec = matchSpec(UserscriptEngine.userMatchOverride(script), meta)
        return JSONObject()
            .put("id", script.id)
            .put("sig", sha1(code))
            .put("code", code)
            .put("matches", JSONArray(spec.first))
            .put("excludeMatches", JSONArray(spec.second))
            .put("noframes", meta.noFrames)
            .put("tok", tokenFor(script.id))
            .put("connect", JSONArray(meta.raw["connect"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }))
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- 匹配预过滤
    // Gecko 的 matches 只是「预过滤」：能精确转换就转换，不能转换（正则、http*、中间通配…）就放宽为 *://*/*，
    // 精确判断仍由运行时 shouldRun 完成，所以放宽永远安全；排除则只在能精确表达时才下发。

    private val BROAD = "*://*/*"
    private val MATCH_RE = Regex("^(\\*|https?|file|ftp)://(\\*|\\*\\.[^/*:]+|[^/*:]+)(:(?:\\d+|\\*))?(/.*)$")
    private val GLOB_RE = Regex("^(\\*|https?)://(\\*\\.[^/*:?]+|[^/*:?]+)(:\\d+)?/.*$")

    internal fun matchSpec(userOverride: List<String>, meta: UserscriptMeta): Pair<List<String>, List<String>> {
        val sources: List<Pair<String, Boolean>> = // (规则, 是否 match 语法)
            if (userOverride.isNotEmpty()) userOverride.map { it to true }
            else meta.matches.map { it to true } + meta.includes.map { it to false }
        val matches = if (sources.isEmpty()) listOf(BROAD) else sources.map { (r, isMatch) ->
            if (isMatch) toMatchPattern(r) else globToPattern(r)
        }.map { it ?: BROAD }.distinct()
        val excludes = meta.excludeMatches.mapNotNull { exactMatchPattern(it) }.distinct()
        return (if (BROAD in matches) listOf(BROAD) else matches) to excludes
    }

    internal fun toMatchPattern(p: String): String? {
        val t = p.trim()
        if (t == "<all_urls>") return t
        val m = MATCH_RE.find(t) ?: return null
        return "${m.groupValues[1]}://${m.groupValues[2]}${m.groupValues[4]}" // 去端口 = 放宽
    }

    private fun exactMatchPattern(p: String): String? {
        val t = p.trim()
        if (t == "<all_urls>") return t
        val m = MATCH_RE.find(t) ?: return null
        return if (m.groupValues[3].isEmpty()) t else null // 带端口的排除无法精确表达：不下发
    }

    private fun globToPattern(g: String): String? {
        val m = GLOB_RE.find(g.trim()) ?: return null
        return "${m.groupValues[1]}://${m.groupValues[2]}/*"
    }

    // ---------------------------------------------------------------- 后台端口

    private val backgroundDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            bgPort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val o = message as? JSONObject ?: return
                    when (o.optString("t")) {
                        "hello" -> push()
                        "synced" -> {
                            val r = o.optInt("rev", -1)
                            if (r > syncedRev) syncedRev = r
                            if (syncedRev >= rev) { waiters.toList().also { waiters.clear() }.forEach { it() } }
                            if (o.optInt("failed") > 0) Diag.log("gm: ${o.optInt("failed")} script(s) failed to register")
                        }
                        "dlCookieRes" -> {
                            val rid = o.optString("rid")
                            dlCookieResults[rid] = o.optString("cookie")
                            o.optString("ua").takeIf { it.isNotBlank() }?.let { userAgent = it }
                            dlCookieWaiters[rid]?.countDown()
                        }
                        "error" -> Diag.log("gm: background error ${o.optString("where")} ${o.optString("id")} ${o.optString("msg")}")
                    }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (bgPort === port) bgPort = null
                    syncedRev = -1 // 后台页重启：contentScripts 不跨会话保留，等它重新 hello 后再同步
                }
            })
        }
    }

    // ---------------------------------------------------------------- 下载器取 Cookie

    private val dlCookieWaiters = ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()
    private val dlCookieResults = ConcurrentHashMap<String, String>()

    /** 后台页回报的 Gecko UA（取过一次 Cookie 之后可用）：登录 Cookie 常绑定 UA，下载请求要保持一致 */
    @Volatile var userAgent: String? = null
        private set

    /**
     * 供 [Downloader] 在 IO 线程调用：向后台页要 [url] 对应的 Cookie 头（"a=1; b=2"）。
     * Gecko 没有读 Cookie 的 Java API，只有内置扩展的 cookies 权限能读，所以走后台端口一来一回。
     * 扩展没就绪 / 超时 / 在主线程调用 / 没有 Cookie 都返回 null（照旧不带 Cookie 请求）。
     */
    fun cookieHeader(url: String, timeoutMs: Long = 2500): String? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null // 主线程等不得：回复要靠主线程转发
        val p = bgPort ?: return null
        val rid = java.util.UUID.randomUUID().toString()
        val latch = java.util.concurrent.CountDownLatch(1)
        dlCookieWaiters[rid] = latch
        main.post {
            runCatching { p.postMessage(JSONObject().put("t", "dlCookie").put("rid", rid).put("url", url)) }
                .onFailure { latch.countDown() }
        }
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        dlCookieWaiters.remove(rid)
        return dlCookieResults.remove(rid)?.takeIf { it.isNotBlank() }
    }

    private fun push() {
        val p = bgPort ?: return
        runCatching { p.postMessage(JSONObject().put("t", "sync").put("rev", rev).put("scripts", payload)) }
            .onFailure { Diag.log("gm: push failed $it") }
    }

    private fun broadcast(id: String, k: String, raw: String?, src: String) {
        val p = bgPort ?: return
        main.post {
            runCatching {
                p.postMessage(JSONObject().put("t", "val").put("id", id).put("k", k)
                    .put("raw", raw ?: JSONObject.NULL).put("src", src))
            }
        }
    }

    // 值变化后重建受影响的脚本快照，供「之后新开的页面」读到最新值；防抖 + 每脚本限频
    private val resyncAt = ConcurrentHashMap<String, Long>()
    private val resyncPending = ConcurrentHashMap.newKeySet<String>()
    private var latestScripts: List<UserScript> = emptyList()
    private var latestEnabled = true

    private fun scheduleResync(id: String) {
        if (!resyncPending.add(id)) return
        val gap = 1_000L
        val wait = (resyncAt[id]?.plus(gap)?.minus(System.currentTimeMillis()) ?: 0L).coerceAtLeast(300L)
        main.postDelayed({
            resyncPending.remove(id)
            resyncAt[id] = System.currentTimeMillis()
            val app = appContext ?: return@postDelayed
            update(app, latestScripts, latestEnabled, force = true)
        }, wait)
    }

    /** 供 BrowserState 在调用 [update] 前登记「当前脚本集」，值变化重同步时复用 */
    fun remember(scripts: List<UserScript>, enabled: Boolean) {
        latestScripts = scripts
        latestEnabled = enabled
    }

    // ---------------------------------------------------------------- 会话端口（通知 / 下载 / 开标签 / 菜单 / 写值 / 剪贴板）

    private fun attach(session: GeckoSession, tab: BrowserTab) {
        val e = ext ?: return
        runCatching {
            session.webExtensionController.setMessageDelegate(e, sessionDelegate(tab), NATIVE_SESSION)
        }.onFailure { Diag.log("gm: attach session failed $it") }
    }

    private fun sessionDelegate(tab: BrowserTab) = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val o = message as? JSONObject ?: return
                    runCatching { handle(tab, port, o) }.onFailure { Diag.log("gm: handle ${o.optString("t")} failed $it") }
                }
                override fun onDisconnect(port: WebExtension.Port) {}
            })
        }
    }

    private fun cb(port: WebExtension.Port, fn: String, vararg args: Any?) {
        main.post {
            runCatching {
                port.postMessage(JSONObject().put("t", "cb").put("fn", fn).put("args", JSONArray(args.toList())))
            }
        }
    }

    private fun isHttp(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)
    private val SAFE_ID = Regex("[A-Za-z0-9_]{1,80}")

    private fun handle(tab: BrowserTab, port: WebExtension.Port, o: JSONObject) {
        val app = appContext ?: return
        // 没有有效令牌的消息一律丢弃（页面伪造的通道消息、已被停用 / 删除的脚本）
        if (!authorized(o)) {
            Diag.log("gm: rejected ${o.optString("t")} (bad token)")
            return
        }
        val sid = o.optString("sid")
        when (o.optString("t")) {
            "set" -> {
                val k = o.getString("k")
                val v = o.getString("v")
                if (k.length > MAX_KEY_CHARS || v.length > MAX_VALUE_CHARS) { Diag.log("gm: set too large $sid"); return }
                if (!allow(sid, "set", 600, 10_000)) return
                val key = "$sid::$k"
                writers[key] = o.optString("src")
                prefs.edit().putString(key, v).apply()
            }
            "del" -> {
                val k = o.getString("k")
                if (k.length > MAX_KEY_CHARS || !allow(sid, "set", 600, 10_000)) return
                val key = "$sid::$k"
                writers[key] = o.optString("src")
                prefs.edit().remove(key).apply()
            }
            "toast" -> {
                if (!allow(sid, "toast", 10, 10_000)) return
                val msg = o.optString("msg").take(500)
                main.post { runCatching { android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show() } }
            }
            "copy" -> {
                if (!allow(sid, "copy", 10, 10_000)) return
                val text = o.optString("text").take(MAX_TEXT_CHARS)
                main.post {
                    runCatching {
                        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Mafari", text))
                    }
                }
            }
            "copyHtml" -> if (allow(sid, "copy", 10, 10_000)) main.post {
                runCatching {
                    val html = o.optString("html").take(MAX_TEXT_CHARS)
                    val plain = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newHtmlText("Mafari", plain, html))
                }
            }
            "openTab" -> {
                val url = o.optString("url")
                if (!isHttp(url) || url.length > 8192 || !allow(sid, "openTab", 5, 10_000)) return
                val active = runCatching { JSONObject(o.optString("opts", "{}")).optBoolean("active", true) }.getOrDefault(true)
                main.post { tab.onGmOpenTab?.invoke(o.optString("id"), url, active) }
            }
            "closeTab" -> main.post { tab.onGmCloseTab?.invoke(o.optString("id").take(100)) }
            "download" -> {
                val url = o.optString("url")
                val name = o.optString("name").take(255)
                val cbId = o.optString("cbId")
                val fn = "__mafariDlCallback_$cbId"
                if (!isHttp(url) || url.length > 8192 || (cbId.isNotEmpty() && !SAFE_ID.matches(cbId))) {
                    if (cbId.isNotEmpty() && SAFE_ID.matches(cbId)) cb(port, fn, false, "unsupported url")
                    return
                }
                if (!allow(sid, "download", 5, 10_000)) {
                    if (cbId.isNotEmpty()) cb(port, fn, false, "too many downloads")
                    return
                }
                main.post {
                    var called = false
                    val result: ((Boolean, String?) -> Unit)? = if (cbId.isEmpty()) null else { ok, err ->
                        if (!called) { called = true; if (ok) cb(port, fn, true) else cb(port, fn, false, err ?: "download failed") }
                    }
                    val gm = tab.onGmDownload
                    if (gm != null) gm(url, name, result) else result?.invoke(false, "unsupported")
                }
            }
            "notify" -> {
                val cbId = o.optString("cbId")
                if (!SAFE_ID.matches(cbId) || !allow(sid, "notify", 10, 60_000)) return
                val n = JSONObject(o.optString("json"))
                val fn = "__mafariNtfCallback_$cbId"
                runCatching {
                    GmNotifications.post(
                        context = app, title = n.optString("title").take(200), text = n.optString("text").take(2000), tag = n.optString("tag").take(100),
                        timeoutMs = n.optLong("timeout", 0L), silent = n.optBoolean("silent", false),
                    ) { kind -> cb(port, fn, kind) }
                }.onFailure { // 无通知权限：与 WebView 一致退回 toast，不伪造 ondone
                    val t = n.optString("title"); val x = n.optString("text")
                    main.post { runCatching { android.widget.Toast.makeText(app, if (t.isEmpty()) x else "$t：$x", android.widget.Toast.LENGTH_SHORT).show() } }
                }
            }
            "menu" -> tab.gmMenuReported(o.optString("list").take(100_000), port)
            "ran" -> tab.gmRanReported(o.optString("id"))
        }
    }

    /** 让某个标签的主框架执行菜单命令（经该标签上报菜单时用的端口） */
    fun runMenuCommand(port: WebExtension.Port, id: String) {
        val n = System.nanoTime()
        main.post {
            runCatching { port.postMessage(JSONObject().put("t", "menuRun").put("cid", id).put("n", n)) }
        }
    }
}
