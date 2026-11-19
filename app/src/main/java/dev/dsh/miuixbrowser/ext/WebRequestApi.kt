package dev.dsh.miuixbrowser.ext

import android.webkit.WebResourceRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * WebView 可观测到的请求阶段。shouldInterceptRequest 是同步 IO 回调，但拓展后台页通过异步桥接运行，
 * 因此这里仅实现可靠的 onBeforeRequest 观察事件；绝不等待 JS listener，也不伪造 blocking 行为。
 * WebView 不提供完整 response/error 生命周期，onHeadersReceived/onCompleted/onErrorOccurred 暂不注册。
 */
class WebRequestApi(
    private val events: EventSink,
    private val extensions: () -> List<InstalledExtension>,
) {
    private val sequence = AtomicLong(1)
    private val filters = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    fun register(router: ApiRouter) {
        router.register("webRequest.addListener") { ctx, args ->
            val event = args.optString(0)
            if (event !in setOf("onBeforeRequest", "onCompleted", "onErrorOccurred")) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "当前支持 onBeforeRequest / onCompleted / onErrorOccurred")
            }
            val token = args.optString(1)
            if (token.isBlank()) throw ApiException(ErrorCodes.BAD_REQUEST, "listener token 缺失")
            val filter = args.opt(2) as? JSONObject ?: JSONObject()
            validateFilter(filter)
            val extra = args.opt(3) as? JSONArray ?: JSONArray()
            if ((0 until extra.length()).any { extra.optString(it) == "blocking" || extra.optString(it) == "requestBody" }) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "WebView 暂不支持 webRequestBlocking / requestBody")
            }
            filters["${ctx.ext.spec.id}:$event:$token"] = JSONObject(filter.toString())
            true
        }
        router.register("webRequest.removeListener") { ctx, args ->
            filters.keys.filter { it.startsWith("${ctx.ext.spec.id}:") && it.endsWith(":${args.optString(0)}") }.forEach { filters.remove(it) }
            true
        }

    }

    /** 在 WebViewClient.shouldInterceptRequest 的 IO 线程调用；request 内容由 WebView 提供。 */
    fun forget(extId: String) {
        filters.keys.filter { it.startsWith("$extId:") }.forEach { filters.remove(it) }
        // Requests already in flight may finish after an extension is disabled; their events are
        // dispatched only to currently enabled listeners, so no extension-specific state is retained.
    }

    fun beforeRequest(tabId: Int, request: WebResourceRequest) {
        val url = request.url?.toString().orEmpty()
        if (url.isBlank()) return
        val type = inferType(request)
        val payload = JSONObject()
            .put("requestId", "mafari-${sequence.getAndIncrement()}")
            .put("url", url)
            .put("method", request.method ?: "GET")
            .put("tabId", tabId)
            .put("frameId", if (request.isForMainFrame) 0 else -1)
            .put("parentFrameId", -1)
            .put("type", type)
            .put("timeStamp", System.currentTimeMillis())
        if (request.isForMainFrame) {
            inFlight.keys.filter { it.startsWith("$tabId\u0000") }.forEach { inFlight.remove(it) }
            inFlight[requestKey(tabId, url)] = JSONObject(payload.toString())
        }
        dispatch("onBeforeRequest", payload, url, tabId, type)
    }

    /** WebView exposes terminal callbacks reliably only for the main document; subresource completion is unavailable. */
    fun mainFrameCompleted(tabId: Int, url: String) {
        terminal("onCompleted", tabId, url, null)
    }

    fun mainFrameFailed(tabId: Int, url: String, error: String) {
        terminal("onErrorOccurred", tabId, url, error)
    }

    private fun terminal(event: String, tabId: Int, url: String, error: String?) {
        val payload = inFlight.remove(requestKey(tabId, url)) ?: return
        payload.put("timeStamp", System.currentTimeMillis())
        if (error != null) payload.put("error", error)
        dispatch(event, payload, url, tabId, "main_frame")
    }

    private fun requestKey(tabId: Int, url: String) = "$tabId\u0000$url"

    private fun dispatch(event: String, payload: JSONObject, url: String, tabId: Int, type: String) {
        for (ext in extensions()) {
            if (!ext.enabled || "webRequest" !in PermissionChecker.effectivePermissions(ext)) continue
            if (!PermissionChecker.hasHostAccess(ext, url)) continue
            val matchingTokens = filters.entries.mapNotNull { (key, filter) ->
                val prefix = "${ext.spec.id}:$event:"
                if (key.startsWith(prefix) && matchesFilter(filter, url, tabId, type)) key.removePrefix(prefix) else null
            }
            if (matchingTokens.isEmpty()) continue
            val eventPayload = JSONObject(payload.toString()).put("listenerTokens", JSONArray(matchingTokens))
            runCatching { events.emit(ext.spec.id, "webRequest.$event", eventPayload) }
        }
    }

    private fun validateFilter(filter: JSONObject) {
        val urls = filter.optJSONArray("urls")
        if (urls != null) for (i in 0 until urls.length()) {
            val pattern = urls.optString(i)
            if (MatchPattern.parse(pattern) == null) throw ApiException(ErrorCodes.BAD_REQUEST, "无效的 webRequest URL pattern: $pattern")
        }
    }

    private fun inferType(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "main_frame"
        val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }
            ?.value.orEmpty().lowercase()
        val path = request.url.path.orEmpty().lowercase()
        return when {
            "image/" in accept || listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico").any { path.endsWith(it) } -> "image"
            "text/css" in accept || path.endsWith(".css") -> "stylesheet"
            "javascript" in accept || path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            "xmlhttprequest" in accept || "application/json" in accept -> "xmlhttprequest"
            "text/html" in accept -> "sub_frame"
            else -> "other"
        }
    }

    companion object {
        /** 与 Firefox WebRequestFilter 的常用字段子集一致；空字段表示不过滤。 */
        fun matchesFilter(filter: JSONObject?, url: String, tabId: Int, type: String): Boolean {
            if (filter == null) return true
            val tabFilter = filter.optInt("tabId", Int.MIN_VALUE)
            if (tabFilter != Int.MIN_VALUE && tabFilter != tabId) return false
            val urls = filter.optJSONArray("urls")
            if (urls != null && !matchesAny(urls, url)) return false
            val types = filter.optJSONArray("types")
            if (types != null && !contains(types, type)) return false
            val excluded = filter.optJSONArray("excludedUrls")
            if (excluded != null && matchesAny(excluded, url)) return false
            return true
        }

        private fun matchesAny(patterns: JSONArray, url: String): Boolean {
            for (i in 0 until patterns.length()) {
                val p = patterns.optString(i)
                val match = MatchPattern.parse(p)
                if (match?.matches(url) == true) return true
            }
            return false
        }

        private fun contains(values: JSONArray, value: String): Boolean =
            (0 until values.length()).any { values.optString(it) == value }

    }
}
