package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TabsApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private class RecSink : EventSink {
        val got = ArrayList<Pair<String, JSONObject>>()
        override fun emit(extId: String, event: String, payload: JSONObject) {
            got.add(event to payload)
        }
        fun named(event: String) = got.filter { it.first == event }.map { it.second }
    }

    private val bridge = FakeTabBridge()
    private val sink = RecSink()
    private val repo = FakeRepo()
    private lateinit var api: TabsApi
    private lateinit var runtime: ExtensionRuntime
    private var tabA = 0 // granted.com，前台
    private var tabB = 0 // other.com
    private var tabC = 0 // granted.com

    private val pageSender by lazy {
        Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, null, "ctx1")
    }

    @Before fun setUp() {
        api = TabsApi(sink) { repo.list().filter { it.enabled } }
        val router = ApiRouter()
        api.register(router)
        runtime = ExtensionRuntime(repo, router)
        api.attach(bridge)
        tabA = bridge.add("https://granted.com/a", "Alpha", active = true)
        tabB = bridge.add("https://other.com/b", "Beta")
        tabC = bridge.add("https://granted.com/c", "Alpha Cat")
        sink.got.clear()
    }

    private fun install(granted: Set<String> = setOf("storage")) {
        repo.put(TestExt.make(tmp.newFolder(), granted = granted))
    }

    private fun call(api: String, vararg args: Any?, sender: Sender = pageSender): JSONObject = runBlocking {
        val arr = JSONArray()
        args.forEach { arr.put(it ?: JSONObject.NULL) }
        val raw = JSONObject().put("extId", TestExt.ID).put("id", "1").put("api", api).put("args", arr).toString()
        JSONObject(runtime.handle(raw, sender))
    }

    private fun JSONObject.result(): Any? = assertOk(this).opt("result")
    private fun assertOk(r: JSONObject): JSONObject {
        assertTrue(r.toString(), r.getBoolean("ok"))
        return r
    }

    private fun JSONObject.errorMessage(): String {
        assertFalse(this.toString(), getBoolean("ok"))
        return getJSONObject("error").getString("message")
    }

    private fun ids(r: JSONObject): List<Int> {
        val arr = r.result() as JSONArray
        return (0 until arr.length()).map { arr.getJSONObject(it).getInt("id") }
    }

    // ───── 权限裁剪 ─────

    @Test fun queryHidesDetailsWithoutPermission() {
        install()
        val arr = call("tabs.query", JSONObject()).result() as JSONArray
        assertEquals(3, arr.length())
        val byId = (0 until 3).associate { arr.getJSONObject(it).getInt("id") to arr.getJSONObject(it) }
        assertEquals("https://granted.com/a", byId[tabA]!!.getString("url")) // host 权限
        assertFalse(byId[tabB]!!.has("url"))
        assertFalse(byId[tabB]!!.has("title"))
        assertEquals("complete", byId[tabB]!!.getString("status")) // status 不敏感
    }

    @Test fun tabsPermissionRevealsEverything() {
        install(setOf("tabs"))
        val arr = call("tabs.query", JSONObject()).result() as JSONArray
        for (i in 0 until arr.length()) assertTrue(arr.getJSONObject(i).has("url"))
    }

    @Test fun urlAndTitleFiltersOnlyMatchVisibleTabs() {
        install()
        // other.com 看不到：即使 pattern 命中也不能出现在结果里
        assertEquals(listOf(tabA, tabC), ids(call("tabs.query", JSONObject().put("url", "<all_urls>"))))
        assertEquals(emptyList<Int>(), ids(call("tabs.query", JSONObject().put("url", "https://other.com/*"))))
        assertEquals(listOf(tabC), ids(call("tabs.query", JSONObject().put("title", "*Cat"))))
    }

    @Test fun urlFilterWithTabsPermission() {
        install(setOf("tabs"))
        val r = call("tabs.query", JSONObject().put("url", JSONArray(listOf("https://other.com/*", "https://granted.com/c"))))
        assertEquals(listOf(tabB, tabC), ids(r))
    }

    @Test fun queryBasicFilters() {
        install(setOf("tabs"))
        assertEquals(listOf(tabA), ids(call("tabs.query", JSONObject().put("active", true).put("currentWindow", true))))
        assertEquals(listOf(tabB, tabC), ids(call("tabs.query", JSONObject().put("active", false))))
        assertEquals(emptyList<Int>(), ids(call("tabs.query", JSONObject().put("currentWindow", false))))
        assertEquals(emptyList<Int>(), ids(call("tabs.query", JSONObject().put("pinned", true))))
        assertEquals(3, ids(call("tabs.query", JSONObject().put("pinned", false))).size)
        assertEquals(listOf(tabB), ids(call("tabs.query", JSONObject().put("index", 1))))
        assertEquals(emptyList<Int>(), ids(call("tabs.query", JSONObject().put("windowId", 99))))
    }

    @Test fun invalidQueryIsRejected() {
        install()
        assertTrue(call("tabs.query", JSONObject().put("url", "not a pattern")).errorMessage().contains("Invalid url pattern"))
        assertEquals(ErrorCodes.BAD_REQUEST, call("tabs.query", JSONObject().put("active", "yes")).getJSONObject("error").getString("code"))
    }

    @Test fun contentScriptsCannotCallTabs() {
        install(setOf("tabs"))
        val cs = Sender(SenderKind.CONTENT_SCRIPT, "https://a.com", true, tabA)
        val r = call("tabs.query", JSONObject(), sender = cs)
        assertEquals(ErrorCodes.DENIED_PERMISSION, r.getJSONObject("error").getString("code"))
    }

    // ───── get / create / update / remove ─────

    @Test fun getAndMissingTab() {
        install(setOf("tabs"))
        assertEquals("Alpha", (call("tabs.get", tabA).result() as JSONObject).getString("title"))
        assertEquals("No tab with id: 999.", call("tabs.get", 999).errorMessage())
        assertEquals(ErrorCodes.BAD_REQUEST, call("tabs.get", 1.5).getJSONObject("error").getString("code"))
    }

    @Test fun createChecksUrlAndOpener() {
        install(setOf("tabs"))
        val ok = call("tabs.create", JSONObject().put("url", "https://example.com/").put("active", false).put("openerTabId", tabA))
        val t = ok.result() as JSONObject
        assertEquals(tabA, t.getInt("openerTabId"))
        assertFalse(t.getBoolean("active"))
        assertEquals(tabA, runBlocking { bridge.list().first { it.active }.id }) // 后台打开不抢前台

        assertTrue(call("tabs.create", JSONObject().put("url", "javascript:alert(1)")).errorMessage().contains("javascript"))
        assertTrue(call("tabs.create", JSONObject().put("url", "file:///sdcard/x")).errorMessage().contains("file"))
        assertEquals("No tab with id: 777.", call("tabs.create", JSONObject().put("openerTabId", 777)).errorMessage())
        assertEquals("No window with id: 5.", call("tabs.create", JSONObject().put("windowId", 5)).errorMessage())
    }

    @Test fun createRelativeUrlResolvesToExtensionOrigin() {
        install(setOf("tabs"))
        val t = call("tabs.create", JSONObject().put("url", "options.html?x=1")).result() as JSONObject
        assertEquals(ExtensionOrigins.urlFor(TestExt.ID, "options.html?x=1"), t.getString("url"))
    }

    @Test fun createReportsQuota() {
        install()
        val small = FakeTabBridge(maxTabs = 1)
        small.add("https://granted.com/")
        api.attach(small)
        assertEquals(ErrorCodes.QUOTA, call("tabs.create", JSONObject()).getJSONObject("error").getString("code"))
    }

    @Test fun updateDefaultsToActiveTab() {
        install(setOf("tabs"))
        val r = call("tabs.update", null, JSONObject().put("url", "https://example.com/x")).result() as JSONObject
        assertEquals(tabA, r.getInt("id"))
        assertEquals("https://example.com/x", r.getString("url"))
    }

    @Test fun updateActivatesAndRejectsBadUrl() {
        install(setOf("tabs"))
        assertTrue((call("tabs.update", tabB, JSONObject().put("active", true)).result() as JSONObject).getBoolean("active"))
        assertEquals(ErrorCodes.BAD_REQUEST, call("tabs.update", tabB, JSONObject().put("url", "data:text/html,hi")).getJSONObject("error").getString("code"))
        assertEquals("No tab with id: 404.", call("tabs.update", 404, JSONObject().put("active", true)).errorMessage())
    }

    @Test fun removeClosesExistingAndReportsMissing() {
        install(setOf("tabs"))
        assertEquals("No tab with id: 555.", call("tabs.remove", JSONArray(listOf(tabB, 555))).errorMessage())
        // 存在的那个仍然被关闭
        assertEquals(listOf(tabA, tabC), runBlocking { bridge.list().map { it.id } })
        assertOk(call("tabs.remove", tabC))
        assertEquals(listOf(tabA), runBlocking { bridge.list().map { it.id } })
    }

    @Test fun reloadAndHistory() {
        install(setOf("tabs"))
        assertOk(call("tabs.reload", tabB, JSONObject().put("bypassCache", true)))
        assertEquals(listOf(tabB to true), bridge.reloads)
        assertOk(call("tabs.reload", null, null))
        assertEquals(tabA to false, bridge.reloads.last())
        assertOk(call("tabs.goBack", tabA))
        assertOk(call("tabs.goForward", null))
        assertEquals("No tab with id: 9.", call("tabs.goBack", 9).errorMessage())
    }

    @Test fun detachedBridgeIsUnsupported() {
        install()
        api.detach()
        assertEquals(ErrorCodes.UNSUPPORTED, call("tabs.query", JSONObject()).getJSONObject("error").getString("code"))
        assertEquals(0, bridge.listenerCount())
    }

    // ───── 事件 ─────

    @Test fun eventsAreStrippedPerPermission() {
        install() // 只有 granted.com 的 host 权限
        bridge.navigate(tabB, "https://other.com/z")
        val updated = sink.named("tabs.onUpdated")
        assertEquals(2, updated.size) // loading + complete，只剩 status
        for (u in updated) {
            val info = u.getJSONObject("changeInfo")
            assertFalse(info.has("url"))
            assertTrue(info.has("status"))
            assertFalse(u.getJSONObject("tab").has("url"))
        }
        sink.got.clear()

        bridge.setTitle(tabB, "secret") // 变化的全是看不到的字段：不推送
        assertTrue(sink.got.isEmpty())

        bridge.setTitle(tabA, "Renamed")
        val u = sink.named("tabs.onUpdated").single()
        assertEquals("Renamed", u.getJSONObject("changeInfo").getString("title"))
        assertEquals(tabA, u.getInt("tabId"))
    }

    @Test fun createdRemovedActivatedPayloads() {
        install(setOf("tabs"))
        val id = bridge.add("https://example.com/", "New")
        assertEquals("https://example.com/", sink.named("tabs.onCreated").single().getJSONObject("tab").getString("url"))
        runBlocking { bridge.update(id, TabUpdateOptions(active = true)) }
        assertEquals(id, sink.named("tabs.onActivated").last().getJSONObject("activeInfo").getInt("tabId"))
        runBlocking { bridge.remove(listOf(id)) }
        val removed = sink.named("tabs.onRemoved").single()
        assertEquals(id, removed.getInt("tabId"))
        assertFalse(removed.getJSONObject("removeInfo").getBoolean("isWindowClosing"))
    }

    @Test fun disabledExtensionGetsNoEvents() {
        repo.put(TestExt.make(tmp.newFolder(), enabled = false))
        bridge.setTitle(tabA, "x")
        assertTrue(sink.got.isEmpty())
    }

    // ───── TabUrlPolicy ─────

    @Test fun policyAllowsWebAndBlank() {
        assertEquals("https://a.com/x?y=1#z", TabUrlPolicy.normalize(TestExt.ID, "  https://a.com/x?y=1#z "))
        assertEquals("HTTP://a.com", TabUrlPolicy.normalize(TestExt.ID, "HTTP://a.com")) // scheme 大小写不敏感，原样放行
        assertEquals("about:blank", TabUrlPolicy.normalize(TestExt.ID, "ABOUT:blank"))
    }

    @Test fun policyRejectsDangerousSchemes() {
        for (u in listOf("javascript:alert(1)", "data:text/html,x", "file:///etc/hosts", "content://x/y", "intent://x#Intent;end", "blob:https://a.com/1", "about:config", "view-source:https://a.com", "")) {
            try {
                TabUrlPolicy.normalize(TestExt.ID, u)
                fail("应拒绝 $u")
            } catch (e: ApiException) {
                assertEquals(ErrorCodes.BAD_REQUEST, e.code)
            }
        }
    }

    @Test fun policyRejectsOtherExtensionsAndTraversal() {
        val other = ExtensionOrigins.urlFor("other@x.org", "page.html")
        val own = ExtensionOrigins.urlFor(TestExt.ID, "page.html")
        assertEquals(own, TabUrlPolicy.normalize(TestExt.ID, own))
        for (u in listOf(other, "../secret.html", "a/../../b", "//evil.com/x", "https://", "https:///x")) {
            try {
                TabUrlPolicy.normalize(TestExt.ID, u)
                fail("应拒绝 $u")
            } catch (e: ApiException) {
                assertEquals(ErrorCodes.BAD_REQUEST, e.code)
            }
        }
        // 用户信息伪装：真正的主机是 evil.com，应当放行为普通网址而不是被当成拓展页面
        assertEquals("https://${ExtensionOrigins.hostFor(TestExt.ID)}@evil.com/", TabUrlPolicy.normalize(TestExt.ID, "https://${ExtensionOrigins.hostFor(TestExt.ID)}@evil.com/"))
    }

    @Test fun policyRejectsControlCharacters() {
        try {
            TabUrlPolicy.normalize(TestExt.ID, "https://a.com/\u0000x")
            fail()
        } catch (e: ApiException) {
            assertEquals(ErrorCodes.BAD_REQUEST, e.code)
        }
    }
}
