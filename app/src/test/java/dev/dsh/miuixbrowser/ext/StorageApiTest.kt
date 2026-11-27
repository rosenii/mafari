/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageApiTest {
    @get:Rule val tmp = TemporaryFolder()

    // ───── JsonStorageArea ─────

    @Test fun getVariantsAndDefaults() {
        val a = JsonStorageArea(null, { 1_000_000 })
        a.set(JSONObject("""{"x":1,"y":"two","z":{"k":[1,2]}}"""))
        assertEquals(3, a.get(null).length())
        assertEquals(1, a.get("x").getInt("x"))
        assertEquals(2, a.get(JSONArray(listOf("x", "y", "nope"))).length())
        val withDefaults = a.get(JSONObject("""{"x":99,"missing":"d"}"""))
        assertEquals(1, withDefaults.getInt("x"))
        assertEquals("d", withDefaults.getString("missing"))
    }

    @Test fun setReportsOnlyRealChanges() {
        val a = JsonStorageArea(null, { 1_000_000 })
        val c1 = a.set(JSONObject("""{"n":1,"o":{"a":1,"b":2}}"""))
        assertEquals(setOf("n", "o"), c1.keys)
        assertNull(c1["n"]!!.oldValue)
        val c2 = a.set(JSONObject("""{"n":1.0,"o":{"b":2,"a":1}}"""))
        assertTrue(c2.isEmpty())
        val c3 = a.set(JSONObject("""{"n":2}"""))
        assertEquals(1, (c3["n"]!!.oldValue as Number).toInt())
        assertEquals(2, (c3["n"]!!.newValue as Number).toInt())
    }

    @Test fun removeAndClear() {
        val a = JsonStorageArea(null, { 1_000_000 })
        a.set(JSONObject("""{"a":1,"b":2,"c":3}"""))
        val r = a.remove(JSONArray(listOf("a", "zzz")))
        assertEquals(setOf("a"), r.keys)
        assertNull(r["a"]!!.newValue)
        val c = a.clear()
        assertEquals(setOf("b", "c"), c.keys)
        assertEquals(0, a.get(null).length())
    }

    @Test fun getReturnsCopies() {
        val a = JsonStorageArea(null, { 1_000_000 })
        a.set(JSONObject("""{"o":{"k":1}}"""))
        a.get("o").getJSONObject("o").put("k", 999)
        assertEquals(1, a.get("o").getJSONObject("o").getInt("k"))
    }

    @Test fun quotaIsEnforced() {
        val a = JsonStorageArea(null, { 50 })
        a.set(JSONObject("""{"k":"short"}"""))
        try {
            a.set(JSONObject().put("big", "x".repeat(100)))
            fail("应超额")
        } catch (e: ApiException) {
            assertEquals(ErrorCodes.QUOTA, e.code)
        }
        // 失败的写入不留下任何东西
        assertFalse(a.get(null).has("big"))

        val perItem = JsonStorageArea(null, { 1_000_000 }, maxItemBytes = 20)
        try {
            perItem.set(JSONObject().put("k", "x".repeat(50)))
            fail()
        } catch (e: ApiException) {
            assertEquals(ErrorCodes.QUOTA, e.code)
        }
    }

    @Test fun persistsAcrossInstances() {
        val f = File(tmp.root, "d/storage_local.json")
        JsonStorageArea(f, { 1_000_000 }).set(JSONObject("""{"k":"v"}"""))
        assertEquals("v", JsonStorageArea(f, { 1_000_000 }).get("k").getString("k"))
        f.writeText("{ broken")
        assertEquals(0, JsonStorageArea(f, { 1_000_000 }).get(null).length())
    }

    @Test fun bytesInUse() {
        val a = JsonStorageArea(null, { 1_000_000 })
        a.set(JSONObject("""{"ab":"cd"}"""))
        // key 2 + "cd" 带引号的 JSON 长度 4
        assertEquals(6L, a.getBytesInUse(null))
        assertEquals(0L, a.getBytesInUse("nope"))
    }

    // ───── 经 ExtensionRuntime 的端到端 ─────

    private class Fixture(tmp: File) {
        val repo = FakeRepo()
        val events = mutableListOf<Triple<String, String, JSONObject>>()
        val router = ApiRouter()
        val runtime = ExtensionRuntime(repo, router)
        val ext = TestExt.make(File(tmp, "ext"))

        init {
            repo.put(ext)
            StorageApi({ File(tmp, "data/$it") }, { id, ev, p -> events.add(Triple(id, ev, p)) }).register(router)
            RuntimeApi(MessageBroker()).register(router)
        }

        fun call(api: String, args: String, sender: Sender): JSONObject = runBlocking {
            JSONObject(
                runtime.handle(
                    """{"extId":"${TestExt.ID}","id":"1","api":"$api","args":$args}""",
                    sender,
                )
            )
        }
    }

    private val csSender = Sender(SenderKind.CONTENT_SCRIPT, "https://a.com", true)
    private val pageSender = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true)

    @Test fun endToEndStorageFromContentScript() {
        val f = Fixture(tmp.root)
        assertTrue(f.call("storage.local.set", """[{"a":1}]""", csSender).getBoolean("ok"))
        val got = f.call("storage.local.get", """["a"]""", csSender)
        assertEquals(1, got.getJSONObject("result").getInt("a"))
        assertEquals(1, f.events.size)
        assertEquals("storage.onChanged", f.events[0].second)
        assertEquals("local", f.events[0].third.getString("areaName"))
        assertEquals(1, f.events[0].third.getJSONObject("changes").getJSONObject("a").getInt("newValue"))
        // 没变化就不推事件
        f.call("storage.local.set", """[{"a":1}]""", csSender)
        assertEquals(1, f.events.size)
    }

    @Test fun endToEndDenials() {
        val f = Fixture(tmp.root)
        val badOrigin = f.call("storage.local.get", "[null]", Sender(SenderKind.CONTENT_SCRIPT, "https://evil.com", true))
        assertEquals(ErrorCodes.DENIED_ORIGIN, badOrigin.getJSONObject("error").getString("code"))
        val session = f.call("storage.session.get", "[null]", csSender)
        assertEquals(ErrorCodes.DENIED_PERMISSION, session.getJSONObject("error").getString("code"))
        val unsupported = f.call("tabs.query", "[{}]", pageSender)
        assertEquals(ErrorCodes.UNSUPPORTED, unsupported.getJSONObject("error").getString("code"))
        val unknown = f.call("nothing.here", "[]", pageSender)
        assertEquals(ErrorCodes.UNKNOWN_API, unknown.getJSONObject("error").getString("code"))
        val badSet = f.call("storage.local.set", """["notobject"]""", csSender)
        assertEquals(ErrorCodes.BAD_REQUEST, badSet.getJSONObject("error").getString("code"))
    }

    @Test fun endToEndRuntime() {
        val f = Fixture(tmp.root)
        assertEquals("Demo", f.call("runtime.getManifest", "[]", csSender).getJSONObject("result").getString("name"))
        assertEquals(
            ExtensionOrigins.urlFor(TestExt.ID, "a.html"),
            f.call("runtime.getURL", """["a.html"]""", csSender).getString("result"),
        )
        val sent = f.call("runtime.sendMessage", """[null,{"hi":1},null]""", csSender)
        assertEquals(1, sent.getJSONObject("result").getJSONObject("echo").getInt("hi"))
        val cross = f.call("runtime.sendMessage", """["other@x.org","m",null]""", csSender)
        assertEquals(ErrorCodes.UNSUPPORTED, cross.getJSONObject("error").getString("code"))
    }

    @Test fun missingExtensionAndGarbage() = runBlocking {
        val f = Fixture(tmp.root)
        val none = JSONObject(f.runtime.handle("""{"extId":"nope","id":"9","api":"runtime.getURL","args":["x"]}""", csSender))
        assertEquals(ErrorCodes.NO_SUCH_EXTENSION, none.getJSONObject("error").getString("code"))
        val bad = JSONObject(f.runtime.handle("""{"id":"3"}""", csSender))
        assertEquals("3", bad.getString("id"))
        assertEquals(ErrorCodes.BAD_REQUEST, bad.getJSONObject("error").getString("code"))
    }
}
