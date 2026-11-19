package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.CompletableDeferred
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
    private val timeoutMs: Long = 30_000L,
    private val newCid: () -> String = { UUID.randomUUID().toString() },
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

    private val endpoints = ConcurrentHashMap<String, MessageEndpoint>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val ports = ConcurrentHashMap<String, PortConnection>()

    fun register(ep: MessageEndpoint) {
        endpoints[ep.contextId] = ep
    }

    fun unregister(contextId: String) {
        endpoints.remove(contextId)
        ports.values.filter { it.leftContext == contextId || contextId in it.rightContexts }
            .forEach { connection ->
                val survivors = (setOf(connection.leftContext) + connection.rightContexts)
                    .filterTo(linkedSetOf()) { it != contextId }
                closePort(connection.id, notifyContexts = survivors)
            }
    }

    /** 拓展停用 / 卸载时：丢掉它的所有上下文 */
    fun unregisterExtension(extId: String) {
        // Remove every endpoint first so extension shutdown cannot deliver disconnect events
        // into another context that is itself being torn down in the same operation.
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
        val sender = JSONObject().put("id", extId)
            .put("url", if (ctx.sender.kind == SenderKind.EXTENSION_PAGE) ctx.sender.origin else ctx.sender.origin)
        if (ctx.sender.kind == SenderKind.CONTENT_SCRIPT) {
            sender.put("frameId", 0)
            ctx.sender.tabId?.let { sender.put("tab", JSONObject().put("id", it)) }
        }
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
        val targets = endpoints.values.filter {
            it.extId == extId && it.kind == SenderKind.EXTENSION_PAGE && (self == null || it.contextId != self)
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
        val sender = JSONObject()
            .put("id", extId)
            .put("url", ExtensionOrigins.urlFor(extId, ""))
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
            val delivered = try { t.deliver(cid, message, sender) } catch (_: Exception) { false }
            if (!delivered) settleWithoutReply(cid)
        }
        synchronized(state) {
            state.sealed = true
            if (state.outstanding == 0 && !state.result.isCompleted) state.result.completeExceptionally(noReceiver())
        }
        try {
            return withTimeoutOrNull(timeoutMs) { state.result.await() }?.value
                ?: if (state.result.isCompleted) state.result.await().value else throw ApiException(ErrorCodes.TIMEOUT, "没有收到回复")
        } catch (e: ApiException) {
            throw e
        } finally {
            val left = synchronized(state) { state.cids.toList() }
            left.forEach { pending.remove(it) }
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
        synchronized(state) { state.cids.remove(cid) }
        state.result.complete(Reply(value))
    }

    private fun settleWithoutReply(cid: String) {
        val state = pending.remove(cid) ?: return
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

    private fun noReceiver() =
        ApiException(ErrorCodes.NO_RECEIVER, "Could not establish connection. Receiving end does not exist.")

    private fun senderJson(ctx: CallContext): JSONObject {
        val s = ctx.sender
        val o = JSONObject().put("id", ctx.ext.spec.id).put("origin", s.origin)
        if (s.kind == SenderKind.CONTENT_SCRIPT) {
            // 只有 content script 来自网页：帧编号取不到具体值，主框架是 0，其余统一记为 1
            o.put("frameId", if (s.isMainFrame) 0 else 1)
            s.tabId?.let { o.put("tab", JSONObject().put("id", it)) }
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
