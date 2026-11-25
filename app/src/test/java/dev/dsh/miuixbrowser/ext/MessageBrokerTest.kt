/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessageBrokerTest {
    @get:Rule val tmp = TemporaryFolder()

    private open class Ep(
        override val contextId: String,
        override val extId: String = TestExt.ID,
        override val kind: SenderKind = SenderKind.EXTENSION_PAGE,
        val accepts: Boolean = true,
        override val tabId: Int? = null,
    ) : MessageEndpoint {
        override val portPriority: Int get() = if (contextId == "background") 100 else 0
        val got = ArrayList<Triple<String, Any?, JSONObject>>()
        override fun deliver(cid: String, message: Any?, sender: JSONObject): Boolean {
            got.add(Triple(cid, message, sender))
            return accepts
        }
        override fun deliverPort(type: String, portId: String, name: String, sender: JSONObject, message: Any?): Boolean {
            val payload = JSONObject().put("type", type).put("portId", portId).put("name", name).put("sender", sender)
            if (type == "portMessage") payload.put("message", message ?: JSONObject.NULL)
            got.add(Triple("port:$portId", payload, sender))
            return accepts
        }
    }

    private fun ctx(sender: Sender): CallContext {
        val ext = TestExt.make(File(tmp.root, "e${System.nanoTime()}"))
        return CallContext(ext, sender, "runtime.sendMessage")
    }

    private fun csSender(tab: Int? = 7) = Sender(SenderKind.CONTENT_SCRIPT, "https://a.com", true, tab)
    private fun pageSender(id: String) = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, null, id)

    private fun code(block: () -> Unit): String? = try { block(); null } catch (e: ApiException) { e.code }

    @Test fun contentScriptMessageReachesExtensionPageAndReplyComesBack() = runBlocking {
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c1" })
        val bg = Ep("bg")
        b.register(bg)
        val r = async { b.sendMessage(ctx(csSender()), null, JSONObject("""{"q":1}""")) }
        yield()
        assertEquals(1, bg.got.size)
        val (cid, msg, sender) = bg.got[0]
        assertEquals("c1", cid)
        assertEquals(1, (msg as JSONObject).getInt("q"))
        assertEquals(7, sender.getJSONObject("tab").getInt("id"))
        assertEquals("https://a.com", sender.getString("origin"))
        b.reply(TestExt.ID, "c1", "pong", false)
        assertEquals("pong", r.await())
    }

    // 回归：sender.url 缺失 / 只有 origin（少一个斜杠）时，uBO 的 `port.sender.url.startsWith(getURL(''))` 判定失败，
    // popup 的所有请求被当作无特权而静默忽略；篡改猴 / 沉浸式翻译读 sender.url / sender.tab.url 时也会抛 TypeError。
    @Test fun senderCarriesFullUrlAndTabDetails() = runBlocking {
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c1" })
        val bg = Ep("bg")
        b.register(bg)
        val cs = Sender(SenderKind.CONTENT_SCRIPT, "https://a.com", true, 7, null, "https://a.com/p?q=1")
        val r = async { b.sendMessage(ctx(cs), null, "x") }
        yield()
        val sender = bg.got[0].third
        assertEquals("https://a.com/p?q=1", sender.getString("url"))
        assertEquals("https://a.com/p?q=1", sender.getJSONObject("tab").getString("url"))
        assertEquals(TabBridge.WINDOW_ID, sender.getJSONObject("tab").getInt("windowId"))
        b.reply(TestExt.ID, "c1", "ok", false)
        r.await()

        // 子框架：没有可信的完整网址，退化为 origin/，且不带 tab.url
        val b2 = MessageBroker(timeoutMs = 5000, newCid = { "c2" })
        val bg2 = Ep("bg")
        b2.register(bg2)
        val frame = Sender(SenderKind.CONTENT_SCRIPT, "https://a.com", false, 7)
        val r2 = async { b2.sendMessage(ctx(frame), null, "x") }
        yield()
        val s2 = bg2.got[0].third
        assertEquals("https://a.com/", s2.getString("url"))
        assertFalse(s2.getJSONObject("tab").has("url"))
        b2.reply(TestExt.ID, "c2", "ok", false)
        r2.await()
    }

    @Test fun extensionPageSenderUrlStartsWithGetUrlRoot() = runBlocking {
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c1" })
        val bg = Ep("bg")
        b.register(bg)
        val popup = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, null, "popup")
        val r = async { b.sendMessage(ctx(popup), null, "x") }
        yield()
        val url = bg.got[0].third.getString("url")
        assertTrue(url.startsWith(ExtensionOrigins.urlFor(TestExt.ID, "")))
        b.reply(TestExt.ID, "c1", "ok", false)
        r.await()
    }

    // 后台页正在启动（已 expectBackground、还没握手）：content script 的第一条消息要等后台页，而不是立刻 NO_RECEIVER
    @Test fun sendMessageWaitsForBackgroundThatIsStillStarting() = runBlocking {
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c1" }, backgroundGraceMs = 2000)
        b.expectBackground(TestExt.ID)
        val r = async { b.sendMessage(ctx(csSender()), null, "early") }
        kotlinx.coroutines.delay(150)
        val bg = Ep("bg")
        b.register(bg)
        b.backgroundReady(TestExt.ID)
        kotlinx.coroutines.delay(150)
        assertEquals(1, bg.got.size)
        b.reply(TestExt.ID, "c1", "late-but-ok", false)
        assertEquals("late-but-ok", r.await())
    }

    @Test fun sendMessageDoesNotWaitWhenNoBackgroundIsExpectedOrGraceExpired() = runBlocking {
        val none = MessageBroker(timeoutMs = 5000)
        assertEquals("NO_RECEIVER", code { runBlocking { none.sendMessage(ctx(csSender()), null, "x") } })

        var now = 1_000L
        val expired = MessageBroker(timeoutMs = 5000, backgroundGraceMs = 100, clock = { now })
        expired.expectBackground(TestExt.ID)
        now += 101
        assertEquals("NO_RECEIVER", code { runBlocking { expired.sendMessage(ctx(csSender()), null, "x") } })
    }

    @Test fun contentScriptsAreNotTargetsAndSenderPageIsExcluded() = runBlocking {
        val b = MessageBroker(timeoutMs = 200)
        val cs = Ep("cs", kind = SenderKind.CONTENT_SCRIPT)
        val bg = Ep("bg")
        val popup = Ep("popup")
        b.register(cs); b.register(bg); b.register(popup)
        // content script 发：只有 bg 与 popup 收到
        val c1 = code { runBlocking { b.sendMessage(ctx(csSender()), null, "x") } }
        assertEquals(ErrorCodes.TIMEOUT, c1)
        assertEquals(1, bg.got.size); assertEquals(1, popup.got.size); assertEquals(0, cs.got.size)
        // popup 发：只有 bg 收到，自己不收
        bg.got.clear(); popup.got.clear()
        val c2 = code { runBlocking { b.sendMessage(ctx(pageSender("popup")), null, "y") } }
        assertEquals(ErrorCodes.TIMEOUT, c2)
        assertEquals(1, bg.got.size); assertEquals(0, popup.got.size)
    }

    @Test fun noEndpointOrUndeliverableMeansNoReceiver() = runBlocking {
        val b = MessageBroker(timeoutMs = 200)
        assertEquals(ErrorCodes.NO_RECEIVER, code { runBlocking { b.sendMessage(ctx(csSender()), null, "x") } })
        b.register(Ep("dead", accepts = false))
        assertEquals(ErrorCodes.NO_RECEIVER, code { runBlocking { b.sendMessage(ctx(csSender()), null, "x") } })
        // 另一个拓展的页面不算
        val other = MessageBroker(timeoutMs = 200)
        other.register(Ep("bgOther", extId = "other@x.org"))
        assertEquals(ErrorCodes.NO_RECEIVER, code { runBlocking { other.sendMessage(ctx(csSender()), null, "x") } })
    }

    @Test fun allContextsAnsweringNoReceiverMeansNoReceiver() = runBlocking {
        var n = 0
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c${++n}" })
        b.register(Ep("a")); b.register(Ep("b"))
        val r = async { try { b.sendMessage(ctx(csSender()), null, "x"); null } catch (e: ApiException) { e.code } }
        yield()
        b.reply(TestExt.ID, "c1", null, true)
        b.reply(TestExt.ID, "c2", null, true)
        assertEquals(ErrorCodes.NO_RECEIVER, r.await())
    }

    /** Firefox 语义：有监听器但没响应 → 发送方 resolve undefined；只有「完全没有监听器」才是 NO_RECEIVER。 */
    @Test fun listenerWithoutResponseResolvesUndefinedNotNoReceiver() = runBlocking {
        var n = 0
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c${++n}" })
        b.register(Ep("a")); b.register(Ep("b"))
        val r = async { try { b.sendMessage(ctx(csSender()), null, "x") ?: "undefined" } catch (e: ApiException) { e.code } }
        yield()
        b.reply(TestExt.ID, "c1", null, noReceiver = false, noResponse = true) // a：有监听器、没响应
        b.reply(TestExt.ID, "c2", null, noReceiver = true)                     // b：没有监听器
        assertEquals("undefined", r.await())
    }

    @Test fun firstRealReplyWinsEvenIfAnotherSaysNoReceiver() = runBlocking {
        var n = 0
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c${++n}" })
        b.register(Ep("a")); b.register(Ep("b"))
        val r = async { b.sendMessage(ctx(csSender()), null, "x") }
        yield()
        b.reply(TestExt.ID, "c1", null, true)
        b.reply(TestExt.ID, "c2", "answer", false)
        assertEquals("answer", r.await())
    }

    @Test fun anExtensionCannotAnswerAnotherExtensionsMessage() = runBlocking {
        val b = MessageBroker(timeoutMs = 300, newCid = { "c1" })
        b.register(Ep("bg"))
        val r = async { code { runBlocking { b.sendMessage(ctx(csSender()), null, "x") } } }
        yield()
        b.reply("evil@x.org", "c1", "hijack", false)
        assertEquals(ErrorCodes.TIMEOUT, r.await())
    }

    @Test fun nullReplyIsAValidAnswer() = runBlocking {
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c1" })
        b.register(Ep("bg"))
        val r = async { b.sendMessage(ctx(csSender()), null, "x") }
        yield()
        b.reply(TestExt.ID, "c1", null, false)
        assertNull(r.await())
    }

    @Test fun replyApiValidatesArguments() = runBlocking {
        val b = MessageBroker(timeoutMs = 5000, newCid = { "c1" })
        val router = ApiRouter()
        b.register(router)
        b.register(Ep("bg"))
        val ext = TestExt.make(File(tmp.root, "r"))
        val r = async { b.sendMessage(CallContext(ext, csSender(), "runtime.sendMessage"), null, "x") }
        yield()
        val reply = router.find("runtime.reply")!!
        val c = CallContext(ext, pageSender("bg"), "runtime.reply")
        try {
            reply.handle(c, org.json.JSONArray("[1]")); fail("expected")
        } catch (e: ApiException) { assertEquals(ErrorCodes.BAD_REQUEST, e.code) }
        reply.handle(c, org.json.JSONArray("""["c1",{"a":1},null]"""))
        assertEquals(1, (r.await() as JSONObject).getInt("a"))
        assertNull(router.find("runtime.hello")!!.handle(c, org.json.JSONArray()))
    }


    @Test fun tabsSendMessageTargetsOnlyTheRequestedTab() = runBlocking {
        var n = 0
        val b = MessageBroker(timeoutMs = 5000, newCid = { "tab${++n}" })
        val tab7 = Ep("content:7", kind = SenderKind.CONTENT_SCRIPT, tabId = 7)
        val tab8 = Ep("content:8", kind = SenderKind.CONTENT_SCRIPT, tabId = 8)
        val bg = Ep("background")
        b.register(tab7); b.register(tab8); b.register(bg)
        val ext = TestExt.make(File(tmp.root, "tabs-message"))
        val ctx = CallContext(ext, pageSender("popup"), "tabs.sendMessage")
        val result = async { b.sendToTab(ctx, 7, JSONObject("""{"hello":true}""")) }
        yield()
        assertEquals(1, tab7.got.size)
        assertEquals(0, tab8.got.size)
        assertEquals(0, bg.got.size)
        val cid = tab7.got.single().first
        b.reply(TestExt.ID, cid, "received", false)
        assertEquals("received", result.await())
    }

    @Test fun tabsSendMessageCanTargetSpecificFrame() = runBlocking {
        var n = 0
        val b = MessageBroker(timeoutMs = 5000, newCid = { "frame${++n}" })
        val main = object : Ep("content:main", kind = SenderKind.CONTENT_SCRIPT, tabId = 7) {
            override val frameId: Int = 0
        }
        val child = object : Ep("content:child", kind = SenderKind.CONTENT_SCRIPT, tabId = 7) {
            override val frameId: Int = 2
        }
        b.register(main); b.register(child)
        val ext = TestExt.make(File(tmp.root, "tabs-message-frame"))
        val ctx = CallContext(ext, pageSender("popup"), "tabs.sendMessage")
        val result = async { b.sendToTab(ctx, 7, "main-only", frameId = 0) }
        yield()
        assertEquals(1, main.got.size)
        assertEquals(0, child.got.size)
        b.reply(TestExt.ID, main.got.single().first, "ok", false)
        assertEquals("ok", result.await())

        val all = async { b.sendToTab(ctx, 7, "all-frames") }
        yield()
        assertEquals(2, main.got.size)
        assertEquals(1, child.got.size)
        b.reply(TestExt.ID, main.got.last().first, "main", false)
        assertEquals("main", all.await())
    }

    @Test fun tabsSendMessageWithoutContentScriptReturnsNoReceiver() = runBlocking {
        val b = MessageBroker(timeoutMs = 100)
        val ext = TestExt.make(File(tmp.root, "tabs-message-none"))
        val ctx = CallContext(ext, pageSender("popup"), "tabs.sendMessage")
        assertEquals(ErrorCodes.NO_RECEIVER, code { runBlocking { b.sendToTab(ctx, 123, "x") } })
    }

    @Test fun unregisteringPendingReceiverReleasesSendMessageImmediately() = runBlocking {
        val b = MessageBroker(timeoutMs = 10_000)
        val bg = Ep("bg")
        b.register(bg)
        val result = async {
            try { b.sendMessage(ctx(csSender()), null, "waiting"); null }
            catch (e: ApiException) { e.code }
        }
        yield()
        assertEquals(1, bg.got.size)
        b.unregister("bg")
        assertEquals(ErrorCodes.NO_RECEIVER, result.await())
    }

    @Test fun unregisterRemovesContexts() {
        val b = MessageBroker()
        b.register(Ep("a")); b.register(Ep("b", extId = "o@x"))
        b.unregister("a")
        assertEquals(0, b.endpointCount(TestExt.ID))
        b.unregisterExtension("o@x")
        assertEquals(0, b.endpointCount("o@x"))
    }

    @Test fun runtimeConnectCreatesBidirectionalPortAndNotifiesOnlyPeerOnDisconnect() {
        val b = MessageBroker(newCid = { "port-1" })
        val content = Ep("content:${TestExt.ID}:7", kind = SenderKind.CONTENT_SCRIPT, tabId = 7)
        val background = Ep("background")
        val popup = Ep("popup")
        b.register(content); b.register(background); b.register(popup)
        val ext = TestExt.make(File(tmp.root, "port-connect"))
        val ctx = CallContext(ext, csSender(), "runtime.connect")
        val id = b.openPort(ctx, "native-test")
        assertEquals("port-1", id)
        assertEquals(1, background.got.size)
        assertEquals(1, popup.got.size)
        val connectPayload = background.got.single().second as JSONObject
        assertEquals("portConnect", (popup.got.single().second as JSONObject).getString("type"))
        assertEquals("portConnect", connectPayload.getString("type"))
        assertEquals("native-test", connectPayload.getString("name"))

        b.sendPortMessage(ctx, id, JSONObject("""{"from":"content"}"""))
        val backgroundMessage = background.got.last().second as JSONObject
        val popupMessage = popup.got.last().second as JSONObject
        assertEquals("portMessage", backgroundMessage.getString("type"))
        assertEquals("content", backgroundMessage.getJSONObject("message").getString("from"))
        assertEquals("portMessage", popupMessage.getString("type"))
        assertEquals("content", popupMessage.getJSONObject("message").getString("from"))

        val pageCtx = CallContext(ext, pageSender("background"), "runtime.port.postMessage")
        b.sendPortMessage(pageCtx, id, "reply")
        val contentMessage = content.got.last().second as JSONObject
        assertEquals("portMessage", contentMessage.getString("type"))
        assertEquals("reply", contentMessage.getString("message"))
        // A reply from any receiving context is routed to the original caller.
        val popupCtx = CallContext(ext, pageSender("popup"), "runtime.port.postMessage")
        b.sendPortMessage(popupCtx, id, "popup-reply")
        assertEquals("popup-reply", (content.got.last().second as JSONObject).getString("message"))

        b.closePortFromContext(pageCtx, id)
        // The caller explicitly disconnected; only its peer receives onDisconnect.
        assertEquals("portDisconnect", (content.got.last().second as JSONObject).getString("type"))
        assertEquals(0, background.got.count { (it.second as? JSONObject)?.optString("type") == "portDisconnect" })
        assertEquals(ErrorCodes.NO_RECEIVER, code { b.sendPortMessage(ctx, id, "after-close") })
    }

    @Test fun runtimeConnectFansOutToAllReadyExtensionPagesAndRoutesRepliesToCaller() {
        val b = MessageBroker(newCid = { "port-fanout" })
        val content = Ep("content:${TestExt.ID}:7", kind = SenderKind.CONTENT_SCRIPT, tabId = 7)
        val background = Ep("background")
        val popup = Ep("popup")
        val options = Ep("options")
        b.register(content); b.register(background); b.register(popup); b.register(options)
        val ext = TestExt.make(File(tmp.root, "port-fanout"))
        val ctx = CallContext(ext, csSender(), "runtime.connect")
        val id = b.openPort(ctx, "fanout")
        assertEquals("port-fanout", id)
        listOf(background, popup, options).forEach { endpoint ->
            assertEquals("portConnect", (endpoint.got.single().second as JSONObject).getString("type"))
        }
        b.sendPortMessage(ctx, id, "broadcast")
        listOf(background, popup, options).forEach { endpoint ->
            assertEquals("broadcast", (endpoint.got.last().second as JSONObject).getString("message"))
        }
        val popupCtx = CallContext(ext, pageSender("popup"), "runtime.port.postMessage")
        b.sendPortMessage(popupCtx, id, "reply")
        assertEquals("reply", (content.got.last().second as JSONObject).getString("message"))
    }

    @Test fun contentSideDisconnectNotifiesOnlyTheBackgroundPeer() {
        val b = MessageBroker(newCid = { "port-3" })
        val content = Ep("content:${TestExt.ID}:7", kind = SenderKind.CONTENT_SCRIPT, tabId = 7)
        val background = Ep("background")
        b.register(content); b.register(background)
        val ext = TestExt.make(File(tmp.root, "port-disconnect-content"))
        val contentCtx = CallContext(ext, csSender(), "runtime.port.disconnect")
        val id = b.openPort(contentCtx, "")
        b.closePortFromContext(contentCtx, id)
        assertEquals(0, content.got.count { (it.second as? JSONObject)?.optString("type") == "portDisconnect" })
        assertEquals("portDisconnect", (background.got.last().second as JSONObject).getString("type"))
    }

    @Test fun unregisteringPortEndpointClosesItsConnections() {
        val b = MessageBroker(newCid = { "port-2" })
        val content = Ep("content:${TestExt.ID}:7", kind = SenderKind.CONTENT_SCRIPT, tabId = 7)
        val background = Ep("background")
        b.register(content); b.register(background)
        val ext = TestExt.make(File(tmp.root, "port-unregister"))
        val ctx = CallContext(ext, csSender(), "runtime.connect")
        val id = b.openPort(ctx, "")
        b.unregister("background")
        assertEquals("portDisconnect", (content.got.last().second as JSONObject).getString("type"))
        assertEquals(ErrorCodes.NO_RECEIVER, code { b.sendPortMessage(ctx, id, "x") })
    }

    @Test fun compositeSinkIsolatesFailures() {
        val c = CompositeEventSink()
        val seen = ArrayList<String>()
        c.add(EventSink { _, _, _ -> throw IllegalStateException("boom") })
        c.add(EventSink { id, ev, _ -> seen.add("$id:$ev") })
        c.emit("x", "storage.onChanged", JSONObject())
        assertEquals(listOf("x:storage.onChanged"), seen)
    }
    @Test fun runtimeGetContextsReturnsOnlyOwnExtensionContextsAndAppliesFilters() {
        val b = MessageBroker()
        val background = object : Ep("bg") {
            override val contextType = "BACKGROUND"
            override val documentUrl = "https://extension.invalid/background.html"
            override val documentOrigin = "https://extension.invalid"
        }
        val popup = object : Ep("popup") {
            override val contextType = "POPUP"
            override val documentUrl = "https://extension.invalid/popup.html"
            override val documentOrigin = "https://extension.invalid"
        }
        val content = object : Ep("content:${TestExt.ID}:7", kind = SenderKind.CONTENT_SCRIPT, tabId = 7) {
            override val contextType = "TAB"
            override val documentUrl = "https://site.example/page"
            override val documentOrigin = "https://site.example"
        }
        b.register(background); b.register(popup); b.register(content)
        b.register(Ep("other", extId = "other@x.org"))

        val all = b.getContexts(TestExt.ID)
        assertEquals(3, all.length())
        val popupOnly = b.getContexts(TestExt.ID, JSONObject("""{"contextTypes":["POPUP"]}"""))
        assertEquals(1, popupOnly.length())
        assertEquals("popup", popupOnly.getJSONObject(0).getString("contextId"))
        val tabOnly = b.getContexts(TestExt.ID, JSONObject("""{"tabIds":[7],"documentOrigins":["https://site.example"],"documentUrls":["https://site.example/page"]}"""))
        assertEquals(1, tabOnly.length())
        assertEquals("https://site.example/page", tabOnly.getJSONObject(0).getString("documentUrl"))
        assertEquals("https://site.example", tabOnly.getJSONObject(0).getString("documentOrigin"))
        assertEquals(7, tabOnly.getJSONObject(0).getInt("tabId"))
        assertEquals(TabBridge.WINDOW_ID, tabOnly.getJSONObject(0).getInt("windowId"))
        assertEquals(0, b.getContexts(TestExt.ID, JSONObject("""{"documentUrls":["https://other.example/page"]}""")).length())
        assertEquals(0, b.getContexts(TestExt.ID, JSONObject("""{"contextTypes":["DEVELOPER_TOOLS"]}""")).length())
    }

    @Test fun runtimeGetContextsRejectsMalformedFilters() {
        val b = MessageBroker()
        try {
            b.getContexts(TestExt.ID, JSONObject("""{"tabIds":"7"}"""))
            fail("expected BAD_REQUEST")
        } catch (e: ApiException) {
            assertEquals(ErrorCodes.BAD_REQUEST, e.code)
        }
    }

}
