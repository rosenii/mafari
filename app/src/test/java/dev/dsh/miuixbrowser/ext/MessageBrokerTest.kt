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

    private class Ep(
        override val contextId: String,
        override val extId: String = TestExt.ID,
        override val kind: SenderKind = SenderKind.EXTENSION_PAGE,
        val accepts: Boolean = true,
    ) : MessageEndpoint {
        val got = ArrayList<Triple<String, Any?, JSONObject>>()
        override fun deliver(cid: String, message: Any?, sender: JSONObject): Boolean {
            got.add(Triple(cid, message, sender))
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

    @Test fun unregisterRemovesContexts() {
        val b = MessageBroker()
        b.register(Ep("a")); b.register(Ep("b", extId = "o@x"))
        b.unregister("a")
        assertEquals(0, b.endpointCount(TestExt.ID))
        b.unregisterExtension("o@x")
        assertEquals(0, b.endpointCount("o@x"))
    }

    @Test fun compositeSinkIsolatesFailures() {
        val c = CompositeEventSink()
        val seen = ArrayList<String>()
        c.add(EventSink { _, _, _ -> throw IllegalStateException("boom") })
        c.add(EventSink { id, ev, _ -> seen.add("$id:$ev") })
        c.emit("x", "storage.onChanged", JSONObject())
        assertEquals(listOf("x:storage.onChanged"), seen)
    }
}
