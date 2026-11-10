package dev.dsh.miuixbrowser

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.security.MessageDigest
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

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "gecko-gm").apply { isDaemon = true } }

    @Volatile private var ext: WebExtension? = null
    @Volatile private var installing = false
    private var bgPort: WebExtension.Port? = null

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
        val snap = JSONObject().put("id", script.id).put("values", valuesJson(script.id)).toString()
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
        when (o.optString("t")) {
            "set" -> {
                val key = "${o.getString("id")}::${o.getString("k")}"
                writers[key] = o.optString("src")
                prefs.edit().putString(key, o.getString("v")).apply()
            }
            "del" -> {
                val key = "${o.getString("id")}::${o.getString("k")}"
                writers[key] = o.optString("src")
                prefs.edit().remove(key).apply()
            }
            "toast" -> main.post {
                runCatching { android.widget.Toast.makeText(app, o.optString("msg"), android.widget.Toast.LENGTH_SHORT).show() }
            }
            "copy" -> main.post {
                runCatching {
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Mafari", o.optString("text")))
                }
            }
            "copyHtml" -> main.post {
                runCatching {
                    val html = o.optString("html")
                    val plain = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newHtmlText("Mafari", plain, html))
                }
            }
            "openTab" -> {
                val url = o.optString("url")
                if (!isHttp(url)) return
                val active = runCatching { JSONObject(o.optString("opts", "{}")).optBoolean("active", true) }.getOrDefault(true)
                main.post { tab.onGmOpenTab?.invoke(o.optString("id"), url, active) }
            }
            "closeTab" -> main.post { tab.onGmCloseTab?.invoke(o.optString("id")) }
            "download" -> {
                val url = o.optString("url")
                val name = o.optString("name")
                val cbId = o.optString("cbId")
                val fn = "__mafariDlCallback_$cbId"
                if (!isHttp(url) || (cbId.isNotEmpty() && !SAFE_ID.matches(cbId))) {
                    if (cbId.isNotEmpty() && SAFE_ID.matches(cbId)) cb(port, fn, false, "unsupported url")
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
                if (!SAFE_ID.matches(cbId)) return
                val n = JSONObject(o.optString("json"))
                val fn = "__mafariNtfCallback_$cbId"
                runCatching {
                    GmNotifications.post(
                        context = app, title = n.optString("title"), text = n.optString("text"), tag = n.optString("tag"),
                        timeoutMs = n.optLong("timeout", 0L), silent = n.optBoolean("silent", false),
                    ) { kind -> cb(port, fn, kind) }
                }.onFailure { // 无通知权限：与 WebView 一致退回 toast，不伪造 ondone
                    val t = n.optString("title"); val x = n.optString("text")
                    main.post { runCatching { android.widget.Toast.makeText(app, if (t.isEmpty()) x else "$t：$x", android.widget.Toast.LENGTH_SHORT).show() } }
                }
            }
            "menu" -> tab.gmMenuReported(o.optString("list"), port)
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
