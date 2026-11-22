package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject

/**
 * WebNavigation 的 WebView 主框架实现。
 * WebView 没有公开 Firefox frame/documentId 生命周期，因此仅报告 frameId=0 的主框架事件。
 * 所有事件都同时要求 webNavigation API 权限和目标 URL 的 host 权限。
 */
class WebNavigationApi(
    private val events: EventSink,
    private val extensions: () -> List<InstalledExtension>,
    private val tabs: () -> TabBridge?,
) {
    fun register(router: ApiRouter) {
        router.register("webNavigation.getFrame") { ctx, args ->
            val details = args.argOrNull(0) as? JSONObject
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "details 必须是对象")
            val tabId = TabsApi.toInt(details.opt("tabId"), "tabId")
            val frameId = details.optInt("frameId", 0)
            if (frameId != 0) throw ApiException(ErrorCodes.UNSUPPORTED, "WebView 仅支持主框架 frameId=0")
            val tab = tabs()?.get(tabId) ?: throw TabsApi.noTab(tabId)
            if (!PermissionChecker.hasHostAccess(ctx.ext, tab.url)) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "缺少目标页面 host 权限")
            }
            frame(tab.id, tab.url)
        }
        router.register("webNavigation.getAllFrames") { ctx, args ->
            val details = args.argOrNull(0) as? JSONObject
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "details 必须是对象")
            val tabId = TabsApi.toInt(details.opt("tabId"), "tabId")
            val tab = tabs()?.get(tabId) ?: throw TabsApi.noTab(tabId)
            if (!PermissionChecker.hasHostAccess(ctx.ext, tab.url)) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "缺少目标页面 host 权限")
            }
            JSONArray().put(frame(tab.id, tab.url))
        }
    }

    private val navigationStates = java.util.concurrent.ConcurrentHashMap<Int, NavState>()

    /**
     * 由 androidx.webkit NavigationListener（[WebViewNavigationBridge]）驱动的标签。这些标签上，
     * WebViewClient 回调推出来的近似 before/committed/completed/error 事件由 [ExtensionSystem] 丢弃，避免同一次导航发两遍。
     */
    private val listenerDriven: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun setListenerDriven(tabId: Int, driven: Boolean) {
        if (driven) listenerDriven.add(tabId) else listenerDriven.remove(tabId)
    }

    fun isListenerDriven(tabId: Int): Boolean = tabId in listenerDriven

    /**
     * WebView 的回调不等价于 Gecko 的导航阶段。这里把 shouldOverrideUrlLoading / onPageStarted
     * 归一为一次 beforeNavigate，并把 onPageCommitVisible 作为 committed 的最佳近似。
     * 同一标签的重定向沿用同一导航状态，避免每次 onPageStarted 都重复报告 beforeNavigate。
     */
    @Synchronized
    fun beforeNavigate(tabId: Int, url: String, transitionType: String = "other", frameId: Int = 0) {
        if (url.isBlank()) return
        val current = navigationStates[tabId]
        if (current != null && !current.terminal && current.initialUrl == url) return
        // 新的顶层导航取代尚未结束的导航；WebView 不提供可靠的 redirect chain，因此只保留初始 URL。
        navigationStates[tabId] = NavState(initialUrl = url, transitionType = transitionType)
        emit("webNavigation.onBeforeNavigate", tabId, url, frameId)
    }

    /** onPageStarted 的兜底入口：若 shouldOverride 已登记初始 URL，重定向落点不应再生成一次 beforeNavigate。 */
    @Synchronized
    fun ensureBeforeNavigate(tabId: Int, url: String) {
        val current = navigationStates[tabId]
        if (current == null || current.terminal) beforeNavigate(tabId, url, "other")
    }

    @Synchronized
    fun committed(
        tabId: Int, url: String, transitionType: String? = null, qualifiers: List<String> = emptyList(),
    ) {
        if (url.isBlank()) return
        var state = navigationStates[tabId]
        if (state == null || state.terminal) {
            beforeNavigate(tabId, url)
            state = navigationStates[tabId]
        }
        val active = state ?: return
        if (active.terminal || active.committedUrl == url) return
        active.committedUrl = url
        val type = transitionType ?: active.transitionType
        emit(
            "webNavigation.onCommitted", tabId, url, 0,
            JSONObject().put("transitionType", type).put("transitionQualifiers", JSONArray(qualifiers)),
        )
    }

    @Synchronized
    fun completed(tabId: Int, url: String) {
        if (url.isBlank()) return
        val state = navigationStates[tabId]
        if (state?.failedUrl == url) return // WebView 可能在错误页渲染后仍回调 onPageFinished
        if (state?.completed == true && state.committedUrl == url) return // 防止重复 finish 回调重复发事件
        if (state == null || state.terminal) beforeNavigate(tabId, url)
        val active = navigationStates[tabId] ?: return
        if (active.committedUrl != url) committed(tabId, url)
        if (active.completed) return
        active.completed = true
        active.terminal = true
        emit("webNavigation.onCompleted", tabId, url)
    }

    @Synchronized
    fun errorOccurred(tabId: Int, url: String, error: String) {
        if (url.isBlank()) return
        val state = navigationStates[tabId]
        if (state == null || state.terminal) beforeNavigate(tabId, url)
        val active = navigationStates[tabId] ?: return
        if (active.terminal || active.completed) return
        active.terminal = true
        active.failedUrl = url
        emit(
            "webNavigation.onErrorOccurred", tabId, url, 0,
            JSONObject().put("error", error.ifBlank { "net::ERR_FAILED" }),
        )
    }

    /** 主框架 DOMContentLoaded（NavigationListener 的 onPageDomContentLoadedEvent）。 */
    fun domContentLoaded(tabId: Int, url: String) = emit("webNavigation.onDOMContentLoaded", tabId, url)

    fun historyStateUpdated(tabId: Int, url: String) = emit("webNavigation.onHistoryStateUpdated", tabId, url)
    fun referenceFragmentUpdated(tabId: Int, url: String) = emit("webNavigation.onReferenceFragmentUpdated", tabId, url)

    @Synchronized
    fun forgetTab(tabId: Int) {
        navigationStates.remove(tabId)
        listenerDriven.remove(tabId)
    }

    private data class NavState(
        val initialUrl: String,
        val transitionType: String,
        var committedUrl: String? = null,
        var completed: Boolean = false,
        var terminal: Boolean = false,
        var failedUrl: String? = null,
    )

    private fun emit(event: String, tabId: Int, url: String, frameId: Int = 0, extra: JSONObject = JSONObject()) {
        if (url.isBlank()) return
        val payload = JSONObject()
            .put("tabId", tabId)
            .put("url", url)
            .put("frameId", frameId)
            .put("parentFrameId", -1)
            .put("timeStamp", System.currentTimeMillis())
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            payload.put(key, extra.opt(key))
        }
        for (ext in extensions()) {
            if (!ext.enabled || "webNavigation" !in PermissionChecker.effectivePermissions(ext)) continue
            if (!PermissionChecker.hasHostAccess(ext, url)) continue
            runCatching { events.emit(ext.spec.id, event, payload) }
        }
    }

    private fun frame(tabId: Int, url: String) = JSONObject()
        .put("tabId", tabId)
        .put("frameId", 0)
        .put("parentFrameId", -1)
        .put("url", url)
        .put("errorOccurred", false)
}
