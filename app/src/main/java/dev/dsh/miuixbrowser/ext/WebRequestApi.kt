package dev.dsh.miuixbrowser.ext

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream

/**
 * WebView 可观测到的请求阶段。onBeforeRequest 可观察所有资源；blocking 通过有限时长的 IO 线程等待
 * 拓展页面的同步 JS listener 决策，UI 线程不等待。WebView 不提供请求头/响应头修改及完整子资源终态。
 */
class WebRequestApi(
    private val events: EventSink,
    private val extensions: () -> List<InstalledExtension>,
    private val blockingTimeoutMs: Long = 350L,
) {
    private data class ListenerRegistration(val filter: JSONObject, val blocking: Boolean, val contextId: String?)
    private class BlockingPending(expected: Map<String, String>) {
        /** contextId -> extensionId：只接受真正注册了匹配 blocking listener 的页面上下文回复。 */
        private val expected = expected
        private val completed = HashSet<String>()
        private val responses = ArrayList<Pair<String, JSONObject>>()
        val latch = CountDownLatch(expected.size)

        @Synchronized fun resolve(extId: String, contextId: String, response: JSONObject): Boolean {
            if (expected[contextId] != extId || !completed.add(contextId)) return false
            responses.add(extId to JSONObject(response.toString()))
            latch.countDown()
            return true
        }

        @Synchronized fun forgetContext(contextId: String) {
            if (contextId in expected && completed.add(contextId)) latch.countDown()
        }

        @Synchronized fun forgetExtension(extId: String) {
            expected.filterValues { it == extId }.keys.forEach(::forgetContext)
        }

        @Synchronized fun result(): Pair<String, JSONObject>? {
            // cancel takes precedence over redirect; otherwise first redirect wins.
            responses.firstOrNull { it.second.optBoolean("cancel", false) }?.let { return it }
            return responses.firstOrNull { it.second.optString("redirectUrl").isNotBlank() }
        }
    }

    private val sequence = AtomicLong(1)
    private val filters = java.util.concurrent.ConcurrentHashMap<String, ListenerRegistration>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val blocking = java.util.concurrent.ConcurrentHashMap<String, BlockingPending>()

    fun register(router: ApiRouter) {
        router.register("webRequest.addListener") { ctx, args ->
            val event = args.optString(0)
            if (event !in setOf("onBeforeRequest", "onBeforeRedirect", "onResponseStarted", "onCompleted", "onErrorOccurred")) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "当前支持 onBeforeRequest / onBeforeRedirect / 主框架 onResponseStarted / onCompleted / onErrorOccurred")
            }
            val token = args.optString(1)
            if (token.isBlank()) throw ApiException(ErrorCodes.BAD_REQUEST, "listener token 缺失")
            val filter = args.opt(2) as? JSONObject ?: JSONObject()
            validateFilter(filter)
            val extra = args.opt(3) as? JSONArray ?: JSONArray()
            val extraSpecs = (0 until extra.length()).map { extra.optString(it) }.toSet()
            val unsupported = extraSpecs - setOf("blocking")
            if (unsupported.isNotEmpty()) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "WebView webRequest 暂不支持 extraInfoSpec: ${unsupported.joinToString()}")
            }
            val wantsBlocking = "blocking" in extraSpecs
            if (wantsBlocking) {
                if (event != "onBeforeRequest") throw ApiException(ErrorCodes.UNSUPPORTED, "blocking 目前仅支持 onBeforeRequest")
                if ("webRequestBlocking" !in PermissionChecker.effectivePermissions(ctx.ext)) {
                    throw ApiException(ErrorCodes.DENIED_PERMISSION, "使用 blocking 需要 webRequestBlocking 权限")
                }
            }
            val contextId = ctx.sender.contextId
            if (wantsBlocking && contextId.isNullOrBlank()) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "blocking listener 必须注册在有原生 contextId 的拓展页面")
            }
            filters["${ctx.ext.spec.id}:$event:$token"] = ListenerRegistration(JSONObject(filter.toString()), wantsBlocking, contextId)
            true
        }
        router.register("webRequest.removeListener") { ctx, args ->
            val token = args.optString(0)
            filters.remove("${ctx.ext.spec.id}:onBeforeRequest:$token")
            filters.remove("${ctx.ext.spec.id}:onBeforeRedirect:$token")
            filters.remove("${ctx.ext.spec.id}:onResponseStarted:$token")
            filters.remove("${ctx.ext.spec.id}:onCompleted:$token")
            filters.remove("${ctx.ext.spec.id}:onErrorOccurred:$token")
            true
        }
        router.register("webRequest.resolveBlocking") { ctx, args ->
            if (ctx.sender.kind != SenderKind.EXTENSION_PAGE ||
                "webRequestBlocking" !in PermissionChecker.effectivePermissions(ctx.ext)) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "只有获授 webRequestBlocking 的拓展页面可以回复 blocking 请求")
            }
            val token = args.optString(0)
            val response = args.opt(1) as? JSONObject ?: JSONObject()
            if (token.isBlank()) throw ApiException(ErrorCodes.BAD_REQUEST, "blocking token 缺失")
            val contextId = ctx.sender.contextId ?: throw ApiException(ErrorCodes.DENIED_PERMISSION, "缺少拓展页面上下文")
            blocking[token]?.resolve(ctx.ext.spec.id, contextId, response) ?: false
        }
    }

    /** 在 WebViewClient.shouldInterceptRequest 的 IO 线程调用。超时默认放行，绝不阻塞 UI 线程。 */
    fun beforeRequest(tabId: Int, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString().orEmpty()
        if (url.isBlank()) return null
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

        val enabled = extensions().filter { it.enabled && "webRequest" in PermissionChecker.effectivePermissions(it) && PermissionChecker.hasHostAccess(it, url) }
        val matching = LinkedHashMap<String, List<String>>()
        val blockingContexts = linkedMapOf<String, String>()
        for (ext in enabled) {
            val prefix = "${ext.spec.id}:onBeforeRequest:"
            val matches = filters.entries.mapNotNull { (key, registration) ->
                if (key.startsWith(prefix) && matchesFilter(registration.filter, url, tabId, type)) {
                    val token = key.removePrefix(prefix)
                    if (registration.blocking && "webRequestBlocking" in PermissionChecker.effectivePermissions(ext)) {
                        registration.contextId?.let { blockingContexts[it] = ext.spec.id }
                    }
                    token
                } else null
            }
            if (matches.isNotEmpty()) matching[ext.spec.id] = matches
        }

        val blockingExts = blockingContexts.values.toSet()
        val blockToken = if (blockingContexts.isNotEmpty()) "wrb-${sequence.getAndIncrement()}-${System.nanoTime()}" else null
        val pending = blockToken?.let { token ->
            BlockingPending(blockingContexts).also { state -> blocking[token] = state }
        }
        for ((extId, tokens) in matching) {
            val eventPayload = JSONObject(payload.toString()).put("listenerTokens", JSONArray(tokens))
            if (extId in blockingExts && blockToken != null) eventPayload.put("blockingToken", blockToken)
            runCatching { events.emit(extId, "webRequest.onBeforeRequest", eventPayload) }
        }

        if (pending != null && blockToken != null) {
            try {
                pending.latch.await(blockingTimeoutMs, TimeUnit.MILLISECONDS)
                pending.result()?.let { (ownerExtId, decision) ->
                    return blockingResponse(ownerExtId, decision, url, payload, tabId, type)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                blocking.remove(blockToken, pending)
            }
        }
        return null
    }

    /** onPageCommitVisible 是主文档 response-started 的近似时点；WebView 不提供真实状态码 / 响应头。 */
    fun mainFrameResponseStarted(tabId: Int, url: String) {
        val payload = inFlight[requestKey(tabId, url)]
            ?: inFlight.entries.firstOrNull { it.key.startsWith("$tabId\u0000") }?.value
            ?: return
        val copy = JSONObject(payload.toString()).put("timeStamp", System.currentTimeMillis())
        dispatch("onResponseStarted", copy, url, tabId, "main_frame")
    }

    /** WebRequest API 仅能可靠观察主文档终态；子资源终态由 WebView 不提供。 */
    fun mainFrameCompleted(tabId: Int, url: String) = terminal("onCompleted", tabId, url, null)
    fun mainFrameFailed(tabId: Int, url: String, error: String) = terminal("onErrorOccurred", tabId, url, error)

    private fun terminal(event: String, tabId: Int, url: String, error: String?) {
        val exactKey = requestKey(tabId, url)
        val payload = inFlight.remove(exactKey)
            ?: inFlight.entries.firstOrNull { it.key.startsWith("$tabId\u0000") }?.let { inFlight.remove(it.key) }
            ?: return
        payload.put("url", url)
        payload.put("timeStamp", System.currentTimeMillis())
        if (error != null) payload.put("error", error)
        dispatch(event, payload, url, tabId, "main_frame")
    }

    private fun requestKey(tabId: Int, url: String) = "$tabId\u0000$url"

    private fun dispatch(event: String, payload: JSONObject, url: String, tabId: Int, type: String) {
        for (ext in extensions()) {
            if (!ext.enabled || "webRequest" !in PermissionChecker.effectivePermissions(ext)) continue
            if (!PermissionChecker.hasHostAccess(ext, url)) continue
            val prefix = "${ext.spec.id}:$event:"
            val matchingTokens = filters.entries.mapNotNull { (key, registration) ->
                if (key.startsWith(prefix) && matchesFilter(registration.filter, url, tabId, type)) key.removePrefix(prefix) else null
            }
            if (matchingTokens.isEmpty()) continue
            val eventPayload = JSONObject(payload.toString()).put("listenerTokens", JSONArray(matchingTokens))
            runCatching { events.emit(ext.spec.id, "webRequest.$event", eventPayload) }
        }
    }

    private fun blockingResponse(ownerExtId: String, result: JSONObject, originalUrl: String, request: JSONObject, tabId: Int, type: String): WebResourceResponse? {
        val owner = extensions().firstOrNull { it.spec.id == ownerExtId && it.enabled } ?: return null
        if ("webRequest" !in PermissionChecker.effectivePermissions(owner) ||
            "webRequestBlocking" !in PermissionChecker.effectivePermissions(owner) ||
            !PermissionChecker.hasHostAccess(owner, originalUrl)) return null
        if (result.optBoolean("cancel", false)) {
            val blocked = JSONObject(request.toString()).put("error", "net::ERR_BLOCKED_BY_CLIENT")
            dispatch("onErrorOccurred", blocked, originalUrl, tabId, type)
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
        }
        val redirect = result.optString("redirectUrl").takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { Uri.parse(redirect) }.getOrNull() ?: return null
        if (!(uri.scheme?.equals("http", true) == true || uri.scheme?.equals("https", true) == true) || uri.host.isNullOrBlank()) return null
        if (redirect == originalUrl) return null
        // Redirect targets are also checked against the responding extension's granted host access.
        if (!PermissionChecker.hasHostAccess(owner, redirect)) return null
        val redirected = JSONObject(request.toString()).put("redirectUrl", redirect).put("statusCode", 302)
        dispatch("onBeforeRedirect", redirected, originalUrl, tabId, type)
        return WebResourceResponse("text/plain", "utf-8", 302, "Found",
            mapOf("Location" to redirect, "Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }

    fun forgetContext(contextId: String) {
        filters.entries.filter { it.value.contextId == contextId }.forEach { filters.remove(it.key, it.value) }
        blocking.values.forEach { it.forgetContext(contextId) }
    }

    fun forget(extId: String) {
        filters.keys.filter { it.startsWith("$extId:") }.forEach { filters.remove(it) }
        blocking.values.forEach { it.forgetExtension(extId) }
    }

    private fun validateFilter(filter: JSONObject) {
        val urls = filter.optJSONArray("urls")
            ?: throw ApiException(ErrorCodes.BAD_REQUEST, "webRequest filter 必须包含 urls")
        if (urls.length() == 0) throw ApiException(ErrorCodes.BAD_REQUEST, "webRequest filter.urls 不能为空")
        for (i in 0 until urls.length()) {
            val pattern = urls.optString(i)
            if (MatchPattern.parse(pattern) == null) throw ApiException(ErrorCodes.BAD_REQUEST, "无效的 webRequest URL pattern: $pattern")
        }
        val excluded = filter.optJSONArray("excludedUrls")
        if (excluded != null) for (i in 0 until excluded.length()) {
            val pattern = excluded.optString(i)
            if (MatchPattern.parse(pattern) == null) throw ApiException(ErrorCodes.BAD_REQUEST, "无效的 webRequest excludedUrls pattern: $pattern")
        }
    }

    private fun inferType(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "main_frame"
        val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty().lowercase()
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
