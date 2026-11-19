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

    fun beforeNavigate(tabId: Int, url: String, frameId: Int = 0) = emit("webNavigation.onBeforeNavigate", tabId, url, frameId)
    fun committed(tabId: Int, url: String, transitionType: String = "link") = emit(
        "webNavigation.onCommitted", tabId, url, 0,
        JSONObject().put("transitionType", transitionType).put("transitionQualifiers", JSONArray()),
    )
    fun completed(tabId: Int, url: String) = emit("webNavigation.onCompleted", tabId, url)
    fun historyStateUpdated(tabId: Int, url: String) = emit("webNavigation.onHistoryStateUpdated", tabId, url)
    fun referenceFragmentUpdated(tabId: Int, url: String) = emit("webNavigation.onReferenceFragmentUpdated", tabId, url)

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
