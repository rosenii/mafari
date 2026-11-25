/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import android.webkit.WebView
import dev.dsh.miuixbrowser.engine.gecko.GeckoGm
import dev.dsh.miuixbrowser.script.GmChannel
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

    /**
     * 隔离世界通道（WebView 支持 JS_INJECTION_IN_FRAME_AND_WORLD 时）：脚本 handler 由它自己管理，
     * 此时 [scriptHandlers] 为空。不支持 / 安装失败时为 null，沿用旧的 addDocumentStartJavaScript + MafariGM 路径。
     */
    private var channel: GmChannel? = null

    private var legacyBridgeInstalled = false

    /**
     * 旧桥：`addJavascriptInterface(GmBridge, "MafariGM")`，对该标签所有框架、所有 origin 可见，所以只在确实需要时才挂：
     * 系统 WebView 不支持隔离世界，或通道安装失败退回旧路径。幂等。注意 addJavascriptInterface 对「下一次页面加载」生效。
     */
    fun ensureLegacyBridge() {
        if (legacyBridgeInstalled) return
        val bridge = tab.gmBridge ?: return
        runCatching { tab.webView.addJavascriptInterface(bridge, "MafariGM") }
            .onSuccess { legacyBridgeInstalled = true }
            .onFailure { Diag.log("userscript: legacy bridge install failed ${it.message}") }
    }

    /** 标签销毁：摘掉通道（监听器 / 中继 / 脚本 handler）。主线程 */
    fun release() {
        channel?.close()
        channel = null
        scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
        scriptHandlers.clear()
        installedScriptsSignature = ""
    }

    /** 通道版安装：true = 已由通道接管（脚本 handler 都在通道里），false = 调用方走旧路径 */
    private fun syncViaChannel(enabled: List<dev.dsh.miuixbrowser.script.UserScript>): Boolean {
        val bridge = tab.gmBridge ?: return false
        if (!GmChannel.isSupported()) return false
        val ch = channel ?: GmChannel(tab.appContext, tab.webView, bridge) { Diag.log(it) }.also { channel = it }
        if (ch.sync(enabled)) return true
        ch.close() // 装到一半失败：sync 已清理，这里确保实例不再留着
        channel = null
        return false
    }

    /**
     * Gecko 走内置 WebExtension（中继是扩展内容脚本，不在页面世界），算隔离世界；
     * WebView：通道接管（或支持隔离世界、尚未装脚本）= 隔离世界，旧桥已挂上 / 系统不支持 = 页面内。
     */
    fun bridgeMode(): dev.dsh.miuixbrowser.script.ScriptBridgeMode = when {
        tab.isGecko -> dev.dsh.miuixbrowser.script.ScriptBridgeMode.ISOLATED
        channel != null -> dev.dsh.miuixbrowser.script.ScriptBridgeMode.ISOLATED
        legacyBridgeInstalled -> dev.dsh.miuixbrowser.script.ScriptBridgeMode.IN_PAGE
        GmChannel.isSupported() -> dev.dsh.miuixbrowser.script.ScriptBridgeMode.ISOLATED
        else -> dev.dsh.miuixbrowser.script.ScriptBridgeMode.IN_PAGE
    }

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
            release()
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
            ensureLegacyBridge() // 只能靠 injectMissing 的 evaluateJavascript 补注入，脚本源码需要 MafariGM
            return
        }
        if (enabled.isEmpty()) {
            channel?.close()
            channel = null
            return
        }
        if (syncViaChannel(enabled)) {
            Diag.log("userscript: isolated-world channel scripts=${enabled.size}")
            return
        }
        ensureLegacyBridge() // 通道不可用（不支持 / 安装失败）：退回旧桥
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
        // 通道接管时脚本源码没有 MafariGM，也没有中继（中继只随 document-start 注入）：late 注入的脚本调不了任何原生能力，
        // 反而半残运行；这种情况交给下次导航的 document-start handler
        if (channel != null) return
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
