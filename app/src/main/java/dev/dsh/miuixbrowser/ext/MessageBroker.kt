package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 拓展的一个能接收 runtime.onMessage 的运行上下文：后台页、popup、options 或指定标签里的 content script。
 * 由各宿主在页面建立连接后注册、页面销毁时注销。
 */
interface MessageEndpoint {
    val contextId: String
    val extId: String
    val kind: SenderKind
    /** CONTENT_SCRIPT endpoint 对应的浏览器标签；扩展页面为 null。 */
    val tabId: Int? get() = null
    /** WebExtension frameId；当前 WebView 宿主只注册主框架 0。 */
    val frameId: Int get() = 0
    /** runtime.connect 目标优先级：后台页最高。 */
    val portPriority: Int get() = 0
    /** Chrome runtime.ContextType；未知的扩展页面按 BACKGROUND 处理。 */
    val contextType: String get() = if (kind == SenderKind.CONTENT_SCRIPT) "TAB" else "BACKGROUND"
    /** 当前文档 URL；只由宿主从 WebView 状态提供，不能由页面消息体自报。 */
    val documentUrl: String? get() = null
    /** 当前文档 origin；只由宿主提供。 */
    val documentOrigin: String? get() = null
    /** 当前上下文关联的窗口；Mafari 当前只有一个浏览器窗口。 */
    val windowId: Int? get() = if (kind == SenderKind.CONTENT_SCRIPT) TabBridge.WINDOW_ID else null
    /** 当前实现以宿主 contextId 作为 documentId；导航后上下文会由宿主重建或更新。 */
    val documentId: String get() = contextId
    val incognito: Boolean get() = false

    /** 把 runtime.sendMessage 消息交给页面。 */
    fun deliver(cid: String, message: Any?, sender: JSONObject): Boolean

    /** Port 控制帧由具体宿主通过 WebMessageReplyProxy 投递。 */
    fun deliverPort(type: String, portId: String, name: String, sender: JSONObject, message: Any? = null): Boolean = false
}

/**
 * runtime.sendMessage 的投递与回复。
 * - content script 发的消息只送到拓展自己的页面（后台页 / popup / options），不送到别的 content script，与 Chrome 一致；
 * - 拓展页面发的消息送到「其它」拓展页面（按 contextId 排除自己）；
 * - 每个目标一个 cid，谁先给出回应谁赢；目标都回「没有监听器」或根本送不到 → NO_RECEIVER；
 * - 超时 → TIMEOUT（监听器返回 true 却一直不 sendResponse 的情况）。
 * 回复里的 cid 必须属于回复者自己的拓展，防止一个拓展替另一个拓展的消息作答。
 */
class MessageBroker(
    private val timeoutMs: Long = 10_000L,
    private val newCid: () -> String = { UUID.randomUUID().toString() },
    /** 后台页刚被拉起、还没握手时，runtime.sendMessage 最多等它这么久（Firefox 的后台页是按需拉起并让消息排队的） */
    private val backgroundGraceMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : RuntimeMessenger {

    private class Reply(val value: Any?)

    private class Pending(val extId: String) {
        val result = CompletableDeferred<Reply>()
        val cids = HashSet<String>()
        var outstanding = 0
        var sealed = false
    }

    /** One caller-side Port can be observed by every live extension page context. */
    private data class PortConnection(
        val id: String,
        val extId: String,
        val leftContext: String,
        val rightContexts: CopyOnWriteArraySet<String>,
        val name: String,
        val sender: JSONObject,
    )

    /** 诊断日志（Diag）。tabs.sendMessage / runtime.sendMessage 找不到接收端时写明「谁在线、谁不在」，不含消息内容 */
    @Volatile var log: (String) -> Unit = {}

    /** extId → 等待后台页握手的截止时间。后台页握手 / 拓展停用时清掉；过期后不再等（后台页起不来就别拖慢每一次发送） */
    private val expectedBackground = ConcurrentHashMap<String, Long>()

    private val endpoints = ConcurrentHashMap<String, MessageEndpoint>()
    private val pending = ConcurrentHashMap<String, Pending>()
    /** cid -> endpoint context, so page teardown can release calls immediately instead of waiting for timeout. */
    private val pendingOwner = ConcurrentHashMap<String, String>()
    private val ports = ConcurrentHashMap<String, PortConnection>()

    fun register(ep: MessageEndpoint) {
        endpoints[ep.contextId] = ep
    }

    /** 后台页已开始加载但还没握手：此后 [backgroundGraceMs] 内，找不到接收端的 runtime.sendMessage 先等一等再判 NO_RECEIVER */
    fun expectBackground(extId: String) {
        expectedBackground[extId] = clock() + backgroundGraceMs
    }

    /** 后台页已握手（它的 endpoint 已经 register） */
    fun backgroundReady(extId: String) {
        expectedBackground.remove(extId)
    }

    fun unregister(contextId: String) {
        endpoints.remove(contextId)
        pendingOwner.entries.filter { it.value == contextId }.map { it.key }.forEach(::settleWithoutReply)
        ports.values.filter { it.leftContext == contextId || contextId in it.rightContexts }
            .forEach { connection ->
                val survivors = (setOf(connection.leftContext) + connection.rightContexts)
                    .filterTo(linkedSetOf()) { it != contextId }
                closePort(connection.id, notifyContexts = survivors)
            }
    }

    /** 拓展停用 / 卸载时：丢掉它的所有上下文 */
    fun unregisterExtension(extId: String) {
        expectedBackground.remove(extId)
        // Remove every endpoint first so extension shutdown cannot deliver disconnect events
        // into another context that is itself being torn down in the same operation.
        val contexts = endpoints.values.filter { it.extId == extId }.map { it.contextId }.toSet()
        pendingOwner.entries.filter { it.value in contexts }.map { it.key }.forEach(::settleWithoutReply)
        endpoints.values.filter { it.extId == extId }.map { it.contextId }.forEach(endpoints::remove)
        ports.values.filter { it.extId == extId }.forEach { closePort(it.id, notifyContexts = emptySet()) }
    }

    /** 建立 runtime.connect：一个调用端 Port fan-out 到所有已就绪的拓展页面上下文。 */
    fun openPort(ctx: CallContext, name: String): String {
        val extId = ctx.ext.spec.id
        val callerId = ctx.sender.contextId ?: if (ctx.sender.kind == SenderKind.CONTENT_SCRIPT) {
            ctx.sender.tabId?.let { "content:$extId:$it" }
        } else null
        if (callerId.isNullOrBlank()) throw ApiException(ErrorCodes.UNSUPPORTED, "当前上下文无法建立 Port")
        val caller = endpoints[callerId] ?: throw noReceiver()
        val targets = endpoints.values.asSequence()
            .filter { it.extId == extId && it.kind == SenderKind.EXTENSION_PAGE && it.contextId != callerId }
            .sortedByDescending { it.portPriority }
            .toList()
        if (targets.isEmpty()) throw noReceiver()
        val id = newCid()
        // 与 Chrome / Firefox 一致：sender.url 是发送页面的完整网址（带路径），不是只有 origin
        val sender = senderJson(ctx)
        if (ctx.sender.kind == SenderKind.CONTENT_SCRIPT) sender.put("frameId", 0)
        val connection = PortConnection(
            id, extId, caller.contextId, CopyOnWriteArraySet(), name, sender,
        )
        ports[id] = connection
        targets.forEach { target ->
            // Register before delivery so an immediately responding endpoint is already authorized.
            connection.rightContexts.add(target.contextId)
            val delivered = runCatching { target.deliverPort("portConnect", id, name, sender) }.getOrDefault(false)
            if (!delivered) connection.rightContexts.remove(target.contextId)
        }
        if (connection.rightContexts.isEmpty()) {
            ports.remove(id, connection)
            throw noReceiver()
        }
        return id
    }

    fun sendPortMessage(ctx: CallContext, portId: String, message: Any?) {
        val c = ports[portId] ?: throw ApiException(ErrorCodes.NO_RECEIVER, "Port 已断开")
        val senderId = ctx.sender.contextId ?: if (ctx.sender.kind == SenderKind.CONTENT_SCRIPT) {
            ctx.sender.tabId?.let { "content:${ctx.ext.spec.id}:$it" }
        } else null
        if (c.extId != ctx.ext.spec.id || (senderId != c.leftContext && senderId !in c.rightContexts)) {
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "无权使用此 Port")
        }
        if (senderId == c.leftContext) {
            val liveTargets = c.rightContexts.toList()
            var delivered = 0
            liveTargets.forEach { targetId ->
                val target = endpoints[targetId]
                val ok = target != null && runCatching {
                    target.deliverPort("portMessage", portId, c.name, c.sender, message)
                }.getOrDefault(false)
                if (ok) delivered++ else c.rightContexts.remove(targetId)
            }
            if (delivered == 0) {
                closePort(portId, notifyContexts = emptySet())
                throw ApiException(ErrorCodes.NO_RECEIVER, "Port 接收端已关闭")
            }
        } else {
            val target = endpoints[c.leftContext]
            val ok = target != null && runCatching {
                target.deliverPort("portMessage", portId, c.name, c.sender, message)
            }.getOrDefault(false)
            if (!ok) {
                closePort(portId, notifyContexts = emptySet())
                throw ApiException(ErrorCodes.NO_RECEIVER, "Port 调用端已关闭")
            }
        }
    }

    fun closePortFromContext(ctx: CallContext, portId: String) {
        val c = ports[portId] ?: return
        val senderId = ctx.sender.contextId ?: if (ctx.sender.kind == SenderKind.CONTENT_SCRIPT) {
            ctx.sender.tabId?.let { "content:${ctx.ext.spec.id}:$it" }
        } else null
        if (c.extId != ctx.ext.spec.id || (senderId != c.leftContext && senderId !in c.rightContexts)) {
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "无权断开此 Port")
        }
        val peers = if (senderId == c.leftContext) c.rightContexts.toSet() else {
            setOf(c.leftContext) + c.rightContexts.filter { it != senderId }
        }
        closePort(portId, notifyContexts = peers)
    }

    /** A locally initiated disconnect notifies peer contexts only; the initiating JS Port closes locally. */
    private fun closePort(portId: String, notifyContexts: Set<String>) {
        val c = ports.remove(portId) ?: return
        for (contextId in notifyContexts) {
            endpoints[contextId]?.deliverPort("portDisconnect", c.id, c.name, c.sender)
        }
    }

    fun endpointCount(extId: String): Int = endpoints.values.count { it.extId == extId }

    override suspend fun sendMessage(ctx: CallContext, extensionId: String?, message: Any?): Any? {
        val extId = ctx.ext.spec.id
        val self = ctx.sender.contextId
        fun livePages() = endpoints.values.filter {
            it.extId == extId && it.kind == SenderKind.EXTENSION_PAGE && (self == null || it.contextId != self)
        }
        var targets = livePages()
        val deadline = expectedBackground[extId]
        if (targets.isEmpty() && deadline != null) {
            // 常见时序：恢复标签 / 刚启动时 content script 的第一条消息比后台页握手早到
            while (targets.isEmpty() && clock() < deadline) {
                delay(50)
                targets = livePages()
            }
        }
        if (targets.isEmpty()) {
            log("ext $extId runtime.sendMessage from ${ctx.sender.kind.name.lowercase()}: no extension page online — 后台页没启动 / 启动时抛错 / 还没握手完成")
        }
        return deliverAndAwait(extId, targets, message, senderJson(ctx))
    }

    /** tabs.sendMessage：只投递到指定标签里已经注册的 content script，不广播到其它标签。 */
    suspend fun sendToTab(ctx: CallContext, tabId: Int, message: Any?, frameId: Int? = null): Any? {
        val extId = ctx.ext.spec.id
        val targets = endpoints.values.filter {
            it.extId == extId && it.kind == SenderKind.CONTENT_SCRIPT && it.tabId == tabId &&
                (frameId == null || it.frameId == frameId)
        }
        if (targets.isEmpty()) {
            val online = endpoints.values.filter { it.extId == extId }
                .joinToString { "${it.kind.name.lowercase()}${it.tabId?.let { t -> "@tab$t" }.orEmpty()}" }
            log("ext $extId tabs.sendMessage tab=$tabId: no content-script endpoint (online: ${online.ifEmpty { "none" }}) — " +
                "页面在安装 / 启用拓展之前加载的，或该页面的 content script 没有跑起来（看 content script 的 console 报错）")
        }
        val sender = JSONObject()
            .put("id", extId)
            .put("url", ExtensionOrigins.urlFor(extId, ""))
            .put("origin", ExtensionOrigins.originFor(extId))
            .put("frameId", 0)
            .put("tab", JSONObject()
                .put("id", tabId)
                .put("windowId", TabBridge.WINDOW_ID)
                .put("incognito", false))
        return deliverAndAwait(extId, targets, message, sender)
    }

    private suspend fun deliverAndAwait(
        extId: String,
        targets: List<MessageEndpoint>,
        message: Any?,
        sender: JSONObject,
    ): Any? {
        if (targets.isEmpty()) throw noReceiver()
        val state = Pending(extId)
        for (t in targets) {
            val cid = newCid()
            synchronized(state) {
                state.outstanding++
                state.cids.add(cid)
            }
            pending[cid] = state
            pendingOwner[cid] = t.contextId
            val delivered = try { t.deliver(cid, message, sender) } catch (_: Exception) { false }
            if (!delivered) settleWithoutReply(cid)
        }
        synchronized(state) {
            state.sealed = true
            if (state.outstanding == 0 && !state.result.isCompleted) state.result.completeExceptionally(noReceiver())
        }
        try {
            val reply = withTimeoutOrNull(timeoutMs) { state.result.await() }
            if (reply != null) return reply.value
            if (state.result.isCompleted) return state.result.await().value
            log("ext $extId runtime.sendMessage: timeout after ${timeoutMs}ms; targets=${targets.joinToString { it.kind.name.lowercase() + ":" + it.contextId }}")
            throw ApiException(ErrorCodes.TIMEOUT, "等待拓展页面 runtime.onMessage 回复超时（${timeoutMs}ms）")
        } catch (e: ApiException) {
            throw e
        } finally {
            val left = synchronized(state) { state.cids.toList() }
            left.forEach { pending.remove(it); pendingOwner.remove(it) }
        }
    }

    /** `runtime.reply`：页面把 onMessage 的结果送回来。[noReceiver] 表示该上下文没有监听器 */
    fun reply(extId: String, cid: String, value: Any?, noReceiver: Boolean) {
        val state = pending[cid] ?: return
        if (state.extId != extId) return
        if (noReceiver) {
            settleWithoutReply(cid)
            return
        }
        if (pending.remove(cid) == null) return
        pendingOwner.remove(cid)
        synchronized(state) { state.cids.remove(cid) }
        state.result.complete(Reply(value))
    }

    private fun settleWithoutReply(cid: String) {
        val state = pending.remove(cid) ?: return
        pendingOwner.remove(cid)
        synchronized(state) {
            if (state.cids.remove(cid)) state.outstanding--
            if (state.sealed && state.outstanding == 0 && !state.result.isCompleted) {
                state.result.completeExceptionally(noReceiver())
            }
        }
    }

    fun register(router: ApiRouter) {
        // 页面握手：拿到回复通道本身由宿主在收到第一条消息时完成，这里只需要一个不报错的处理器
        router.register("runtime.hello") { _, _ -> null }
        router.register("runtime.reply") { ctx, a ->
            val cid = a.argOrNull(0) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "reply 需要 cid")
            val opts = a.argOrNull(2) as? JSONObject
            reply(ctx.ext.spec.id, cid, a.argOrNull(1), opts?.optBoolean("noReceiver", false) == true)
            null
        }
    }

    override fun connect(ctx: CallContext, name: String): String = openPort(ctx, name)

    override fun postPortMessage(ctx: CallContext, portId: String, message: Any?) = sendPortMessage(ctx, portId, message)

    override fun disconnectPort(ctx: CallContext, portId: String) = closePortFromContext(ctx, portId)

    override fun getContexts(ctx: CallContext, filter: JSONObject): Any? = getContexts(ctx.ext.spec.id, filter)

    override fun getBackgroundPage(ctx: CallContext): Any? {
        val background = endpoints.values.firstOrNull {
            it.extId == ctx.ext.spec.id && it.kind == SenderKind.EXTENSION_PAGE && it.contextType == "BACKGROUND"
        } ?: return null
        return org.json.JSONObject().put("contextId", background.contextId)
    }

    /** Chrome MV3 runtime.getContexts 的可用子集；不暴露其它拓展的上下文。 */
    fun getContexts(extId: String, filter: JSONObject = JSONObject()): org.json.JSONArray {
        fun strings(key: String): Set<String>? {
            if (!filter.has(key) || filter.isNull(key)) return null
            val a = filter.optJSONArray(key) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是数组")
            return (0 until a.length()).map { a.optString(it).takeIf(String::isNotBlank)
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 不能包含空值") }.toSet()
        }
        fun ints(key: String): Set<Int>? {
            if (!filter.has(key) || filter.isNull(key)) return null
            val a = filter.optJSONArray(key) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是数组")
            return (0 until a.length()).map { i ->
                val n = a.opt(i) as? Number
                    ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须只包含整数")
                n.toInt().takeIf { n.toDouble() == it.toDouble() }
                    ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须只包含整数")
            }.toSet()
        }
        val contextTypes = strings("contextTypes")?.also { allowed ->
            val known = setOf("BACKGROUND", "POPUP", "TAB", "OFFSCREEN_DOCUMENT", "DEVELOPER_TOOLS", "SIDE_PANEL")
            if (allowed.any { it !in known }) throw ApiException(ErrorCodes.BAD_REQUEST, "contextTypes 包含未知类型")
        }
        val contextIds = strings("contextIds")
        val documentIds = strings("documentIds")
        val documentOrigins = strings("documentOrigins")
        // Chrome ContextFilter.documentUrls 是 URL 字符串列表，不是 MatchPattern。
        val documentUrls = strings("documentUrls")
        val tabIds = ints("tabIds")
        val windowIds = ints("windowIds")
        val frameIds = ints("frameIds")
        val incognito = if (filter.has("incognito") && !filter.isNull("incognito")) {
            filter.opt("incognito") as? Boolean ?: throw ApiException(ErrorCodes.BAD_REQUEST, "incognito 必须是布尔值")
        } else null
        val out = org.json.JSONArray()
        endpoints.values.asSequence()
            .filter { it.extId == extId }
            .filter { contextTypes == null || it.contextType in contextTypes }
            .filter { contextIds == null || it.contextId in contextIds }
            .filter { documentIds == null || it.documentId in documentIds }
            .filter { documentOrigins == null || (it.documentOrigin != null && it.documentOrigin in documentOrigins) }
            .filter { documentUrls == null || (it.documentUrl != null && it.documentUrl in documentUrls) }
            .filter { tabIds == null || (it.tabId != null && it.tabId in tabIds) }
            .filter { windowIds == null || (it.windowId != null && it.windowId in windowIds) }
            .filter { frameIds == null || it.frameId in frameIds }
            .filter { incognito == null || it.incognito == incognito }
            .sortedBy { it.contextId }
            .forEach { ep ->
                val item = JSONObject()
                    .put("contextId", ep.contextId)
                    .put("contextType", ep.contextType)
                    .put("documentId", ep.documentId)
                    .put("frameId", ep.frameId)
                    .put("incognito", ep.incognito)
                    .put("tabId", ep.tabId ?: -1)
                    .put("windowId", ep.windowId ?: -1)
                ep.documentUrl?.let { item.put("documentUrl", it) }
                ep.documentOrigin?.let { item.put("documentOrigin", it) }
                out.put(item)
            }
        return out
    }

    private fun noReceiver() =
        ApiException(ErrorCodes.NO_RECEIVER, "Could not establish connection. Receiving end does not exist.")

    private fun senderJson(ctx: CallContext): JSONObject {
        val s = ctx.sender
        val url = s.url ?: (s.origin.trimEnd('/') + "/")
        val o = JSONObject().put("id", ctx.ext.spec.id).put("origin", s.origin).put("url", url)
        if (s.kind == SenderKind.CONTENT_SCRIPT) {
            // 只有 content script 来自网页：帧编号取不到具体值，主框架是 0，其余统一记为 1
            o.put("frameId", if (s.isMainFrame) 0 else 1)
            s.tabId?.let { id ->
                // Chrome 给的是完整 Tab 对象；拓展常读 sender.tab.url / windowId。url 取自宿主记录的主框架网址，
                // 只有主框架才有（子框架的 sender.tab.url 在 Chrome 里是顶层页面网址，这里拿不到可信值，所以不给）
                val tab = JSONObject().put("id", id).put("windowId", TabBridge.WINDOW_ID).put("incognito", false)
                if (s.isMainFrame) tab.put("url", url)
                o.put("tab", tab)
            }
        }
        return o
    }
}

/** 把同一个事件扇出给多个宿主（标签页里的 content script、拓展页面……） */
class CompositeEventSink : EventSink {
    private val sinks = java.util.concurrent.CopyOnWriteArrayList<EventSink>()

    fun add(sink: EventSink) {
        sinks.add(sink)
    }

    override fun emit(extId: String, event: String, payload: JSONObject) {
        for (s in sinks) {
            try {
                s.emit(extId, event, payload)
            } catch (_: Exception) {
                // 一个宿主出错不影响其它宿主
            }
        }
    }
}
