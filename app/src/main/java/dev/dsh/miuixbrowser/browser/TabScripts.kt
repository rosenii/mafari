package dev.dsh.miuixbrowser.browser

import android.webkit.WebView
import dev.dsh.miuixbrowser.engine.gecko.GeckoGm
import dev.dsh.miuixbrowser.script.ScriptResources
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.util.Diag

/**
 * 标签页的油猴脚本注入与菜单命令（从 BrowserTab 拆出）：
 * WebView 走 document-start 脚本 + 页面加载后兜底补注入，Gecko 走内置 WebExtension（[GeckoGm]）。
 * 脚本列表、开关等配置仍是 [BrowserTab] 上的属性，改动时由它的 setter 调 [sync]。
 */
internal class TabScripts(private val tab: BrowserTab) {

    private val scriptHandlers = mutableListOf<androidx.webkit.ScriptHandler>()
    private var installedScriptsSignature = ""
    private var prefetchingScripts = false

    /** 当前标签是否应运行油猴脚本：网页增强、脚本总开关、无痕范围三者都允许 */
    fun active(): Boolean =
        tab.pageEnhancements && tab.scriptsEnabled && (!tab.incognito || tab.scriptsInIncognito)

    /**
     * 所有启用的脚本都用 addDocumentStartJavaScript 注入（覆盖 iframe、真正的 document-start），
     * 具体的 URL 匹配与 run-at 等待在运行时（assets/mafari_gm_runtime.js）里按各框架处理。
     * 脚本列表 / 缓存资源变化时重建；系统不支持该特性时由 injectMissingUserScripts 兜底。
     */
    fun sync() {
        // Gecko 内核：走内置 WebExtension（GeckoGm），与 WebView 共用同一份运行时（见 docs/GECKO_GM.md）
        if (tab.isGecko) { syncGecko(); return }
        if (tab.destroyed) return
        if (!active()) {
            scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
            scriptHandlers.clear()
            installedScriptsSignature = ""
            return
        }
        val enabled = tab.userScripts.filter { it.enabled }
        val signature = enabled.joinToString("|") {
            "${it.id}:${it.code.hashCode()}:${it.match.hashCode()}:${ScriptResources.cachedCount(tab.appContext, it.code)}"
        }

        // @require / @resource 还没缓存的先在后台取回来，取到后自动重建（失败的 60 秒后才会重试）
        val needFetch = enabled.filter { ScriptResources.missing(tab.appContext, it.code).isNotEmpty() }
        if (needFetch.isNotEmpty() && !prefetchingScripts) {
            prefetchingScripts = true
            Thread {
                val changed = needFetch.map { ScriptResources.prefetch(tab.appContext, it.code) }.any { it }
                tab.webView.post {
                    prefetchingScripts = false
                    if (changed) sync()
                }
            }.start()
        }
        if (signature == installedScriptsSignature) return

        scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
        scriptHandlers.clear()
        installedScriptsSignature = signature
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }
        enabled.forEach { script ->
            runCatching {
                val source = UserscriptEngine.buildSource(tab.appContext, script)
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(tab.webView, source, setOf("*"))
                    ?.let { scriptHandlers.add(it) }
            }.onFailure { Diag.log("userscript: install failed ${script.name}: ${it.message}") }
        }
        Diag.log("userscript: document-start handlers=${scriptHandlers.size}/${enabled.size}")
    }

    /** Gecko：注册是全局的（不是按标签），所以这里只汇报「当前脚本集 + 总开关」，无痕范围由扩展的「允许在无痕中运行」决定 */
    private fun syncGecko() {
        if (tab.destroyed) return
        val enabledAll = tab.scriptsEnabled
        GeckoGm.remember(tab.userScripts, enabledAll)
        val needFetch = tab.userScripts.filter { it.enabled && ScriptResources.missing(tab.appContext, it.code).isNotEmpty() }
        if (needFetch.isNotEmpty() && !prefetchingScripts) {
            prefetchingScripts = true
            Thread {
                val changed = needFetch.map { ScriptResources.prefetch(tab.appContext, it.code) }.any { it }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    prefetchingScripts = false
                    if (changed) syncGecko()
                }
            }.start()
        }
        GeckoGm.update(tab.appContext, tab.userScripts, enabledAll)
    }

    /** Gecko 主框架上报的菜单命令 JSON 与端口（navigation 时清空） */
    @Volatile private var gmMenuJson: String = "[]"
    @Volatile private var gmMenuPort: org.mozilla.geckoview.WebExtension.Port? = null
    private val gmRan = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun menuReported(json: String, port: org.mozilla.geckoview.WebExtension.Port) {
        gmMenuJson = json
        gmMenuPort = port
    }

    fun ranReported(id: String) { gmRan.add(id) }

    fun resetPage() {
        gmMenuJson = "[]"
        gmMenuPort = null
        gmRan.clear()
    }

    /**
     * 兜底：页面加载完后问一下页面里已经「见过」哪些脚本（运行时一进来就登记），
     * 没见过的（特性不支持、或 handler 安装晚于本次导航）再用 evaluateJavascript 补注入主框架。
     */
    fun injectMissing(view: WebView) {
        if (!active()) return
        val enabled = tab.userScripts.filter { it.enabled }
        if (enabled.isEmpty()) return
        val query = "(function(){try{return JSON.stringify(Object.keys(window.__mafariSeen||{}))}catch(e){return '[]'}})()"
        view.evaluateJavascript(query) { raw ->
            val seen = runCatching {
                val text = org.json.JSONTokener(raw ?: "null").nextValue() as? String ?: "[]"
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { array.getString(it) }.toSet()
            }.getOrDefault(emptySet())
            val pending = enabled.filter { it.id !in seen }
            pending.forEach { script ->
                runCatching { view.evaluateJavascript(UserscriptEngine.buildSource(tab.appContext, script), null) }
            }
            Diag.log("userscript: page=${tab.url} injected-late=${pending.size}/${enabled.size}")
        }
    }

    /** 当前页面里脚本通过 GM_registerMenuCommand 注册的命令（仅主框架） */
    fun queryMenuCommands(onResult: (List<ScriptMenuCommand>) -> Unit) {
        if (tab.isGecko) {
            onResult(runCatching {
                val array = org.json.JSONArray(gmMenuJson)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ScriptMenuCommand(o.optString("id"), o.optString("title"), o.optString("script"))
                }
            }.getOrDefault(emptyList()))
            return
        }
        val js = "(function(){try{return window.__mafariMenuList?window.__mafariMenuList():'[]'}catch(e){return '[]'}})()"
        tab.evaluateJs(js) { raw ->
            val list = runCatching {
                val text = org.json.JSONTokener(raw ?: "null").nextValue() as? String ?: "[]"
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ScriptMenuCommand(o.optString("id"), o.optString("title"), o.optString("script"))
                }
            }.getOrDefault(emptyList())
            onResult(list)
        }
    }

    /** 当前页面（主框架）里真正匹配并运行的脚本数量 */
    fun queryRunningScripts(onResult: (Int) -> Unit) {
        if (tab.isGecko) { onResult(gmRan.size); return }
        val js = "(function(){try{return Object.keys(window.__mafariRan||{}).length}catch(e){return 0}})()"
        tab.evaluateJs(js) { raw -> onResult(raw?.trim()?.toIntOrNull() ?: 0) }
    }

    /** 当前页面（主框架）里真正匹配并运行的脚本 id */
    fun queryRunningScriptIds(onResult: (List<String>) -> Unit) {
        if (tab.isGecko) { onResult(gmRan.toList()); return }
        val js = "(function(){try{return JSON.stringify(Object.keys(window.__mafariRan||{}))}catch(e){return '[]'}})()"
        tab.evaluateJs(js) { raw ->
            val ids = runCatching {
                val text = org.json.JSONTokener(raw ?: "null").nextValue() as? String ?: "[]"
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { array.getString(it) }
            }.getOrDefault(emptyList())
            onResult(ids)
        }
    }

    fun runMenuCommand(id: String) {
        if (tab.isGecko) { gmMenuPort?.let { GeckoGm.runMenuCommand(it, id) }; return }
        tab.evaluateJs(
            "(function(){try{window.__mafariMenuRun&&window.__mafariMenuRun(" + org.json.JSONObject.quote(id) + ")}catch(e){}})()",
            null,
        )
    }
}
