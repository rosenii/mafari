package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 拓展的一个能接收 runtime.onMessage 的运行上下文：后台页、popup、options……（将来还有标签里的 content script）。
 * 由各宿主在页面建立连接后注册、页面销毁时注销。
 */
interface MessageEndpoint {
    val contextId: String
    val extId: String
    val kind: SenderKind

    /** 把消息交给页面；成功交出返回 true，页面已不可用返回 false。线程不限，实现自己切线程 */
    fun deliver(cid: String, message: Any?, sender: JSONObject): Boolean
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

    private val endpoints = ConcurrentHashMap<String, MessageEndpoint>()
    private val pending = ConcurrentHashMap<String, Pending>()

    fun register(ep: MessageEndpoint) {
        endpoints[ep.contextId] = ep
    }

    fun unregister(contextId: String) {
        endpoints.remove(contextId)
    }

    /** 拓展停用 / 卸载时：丢掉它的所有上下文 */
    fun unregisterExtension(extId: String) {
        endpoints.values.filter { it.extId == extId }.forEach { endpoints.remove(it.contextId) }
    }

    fun endpointCount(extId: String): Int = endpoints.values.count { it.extId == extId }

    override suspend fun sendMessage(ctx: CallContext, extensionId: String?, message: Any?): Any? {
        val extId = ctx.ext.spec.id
        val self = ctx.sender.contextId
        val targets = endpoints.values.filter {
            it.extId == extId && it.kind == SenderKind.EXTENSION_PAGE && (self == null || it.contextId != self)
        }
        if (targets.isEmpty()) throw noReceiver()

        val state = Pending(extId)
        val senderJson = senderJson(ctx)
        for (t in targets) {
            val cid = newCid()
            synchronized(state) {
                state.outstanding++
                state.cids.add(cid)
            }
            pending[cid] = state
            val delivered = try {
                t.deliver(cid, message, senderJson)
            } catch (_: Exception) {
                false
            }
            if (!delivered) settleWithoutReply(cid)
        }
        synchronized(state) {
            state.sealed = true
            if (state.outstanding == 0 && !state.result.isCompleted) state.result.completeExceptionally(noReceiver())
        }

        try {
            val reply = withTimeoutOrNull(timeoutMs) { state.result.await() }
                ?: throw ApiException(ErrorCodes.TIMEOUT, "没有收到回复")
            return reply.value
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
