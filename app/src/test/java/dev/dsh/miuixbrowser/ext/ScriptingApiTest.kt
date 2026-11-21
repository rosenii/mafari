package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptingApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bridge = FakeTabBridge()
    private val repo = FakeRepo()
    private lateinit var runtime: ExtensionRuntime
    private lateinit var extDir: File
    private lateinit var dynamicStore: DynamicContentScriptStore
    private var tabG = 0 // granted.com
    private var tabO = 0 // other.com

    private val manifest = """{"manifest_version":3,"name":"Demo","version":"1",
        "permissions":["storage","scripting"],"host_permissions":["https://granted.com/*"]}"""

    private val pageSender = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, null, "ctx1")

    @Before fun setUp() {
        val router = ApiRouter()
        dynamicStore = DynamicContentScriptStore()
        ScriptingApi({ bridge }, dynamicStore, pollIntervalMs = 1, timeoutMs = 300, newToken = { "tok" }).register(router)
        runtime = ExtensionRuntime(repo, router)
        extDir = tmp.newFolder("ext")
        install()
        tabG = bridge.add("https://granted.com/a")
        tabO = bridge.add("https://other.com/")
    }

    private fun install(granted: Set<String> = setOf("scripting")) {
        repo.put(TestExt.make(extDir, manifest = manifest, granted = granted))
    }

    private fun call(api: String, details: JSONObject?, sender: Sender = pageSender): JSONObject = callArgs(api, if (details == null) JSONArray() else JSONArray().put(details), sender)

    private fun callArgs(api: String, args: JSONArray, sender: Sender = pageSender): JSONObject = runBlocking {
        val raw = JSONObject().put("extId", TestExt.ID).put("id", "1").put("api", api).put("args", args).toString()
        JSONObject(runtime.handle(raw, sender))
    }

    /** evaluateJavascript 对「返回字符串」的原始结果：带引号的 JSON 字符串字面量 */
    private fun lit(s: String) = JSONObject.quote(s)

    private fun code(r: JSONObject) = r.getJSONObject("error").getString("code")
    private fun message(r: JSONObject) = r.getJSONObject("error").getString("message")

    private fun execOn(tabId: Int, vararg extra: Pair<String, Any?>): JSONObject {
        val d = JSONObject().put("target", JSONObject().put("tabId", tabId))
        extra.forEach { d.put(it.first, it.second) }
        return call("scripting.executeScript", d)
    }

    private fun exec(vararg extra: Pair<String, Any?>): JSONObject = execOn(tabG, *extra)

    private fun results(r: JSONObject): JSONArray {
        assertTrue(r.toString(), r.getBoolean("ok"))
        return r.getJSONArray("result")
    }

    // ───── func ─────

    @Test fun funcSyncResultAndScriptShape() {
        bridge.evalHandler = { _, _ -> lit("""{"ok":1,"v":42}""") }
        val r = results(exec("func" to "function(a,b){return a+b}", "args" to JSONArray(listOf(1, 2))))
        assertEquals(0, r.getJSONObject(0).getInt("frameId"))
        assertEquals(42, r.getJSONObject(0).getInt("result"))
        val js = bridge.evaluated.single().second
        assertTrue(js.contains("function(a,b){return a+b}"))
        assertTrue(js.contains(".apply(null,[1,2])"))
        assertTrue(js.contains("location.origin.toLowerCase()!==\"https://granted.com\"")) // 起手先核对 origin
    }

    @Test fun undefinedResultIsOmitted() {
        bridge.evalHandler = { _, _ -> lit("""{"ok":1}""") }
        val item = results(exec("func" to "function(){}")).getJSONObject(0)
        assertFalse(item.has("result"))
    }

    @Test fun thrownErrorsAndPageChange() {
        bridge.evalHandler = { _, _ -> lit("""{"ok":0,"m":"boom"}""") }
        val e = exec("func" to "function(){throw new Error('boom')}")
        assertEquals(ErrorCodes.SCRIPT_ERROR, code(e))
        assertEquals("boom", message(e))

        bridge.evalHandler = { _, _ -> lit("""{"ok":0,"guard":1}""") }
        assertEquals(ErrorCodes.DENIED_ORIGIN, code(exec("func" to "function(){}")))
    }

    @Test fun unexecutablePageIsReported() {
        bridge.evalHandler = { _, _ -> "null" } // 语法错误 / 主页：evaluateJavascript 回 null
        assertEquals(ErrorCodes.SCRIPT_ERROR, code(exec("func" to "function(){")))
    }

    @Test fun asyncResultIsPolled() {
        var n = 0
        bridge.evalHandler = { _, _ ->
            when (++n) {
                1 -> lit("""{"pending":1}""")
                2, 3 -> "null"
                else -> lit("""{"ok":1,"v":"done"}""")
            }
        }
        val r = results(exec("func" to "async function(){return 'done'}"))
        assertEquals("done", r.getJSONObject(0).getString("result"))
        assertEquals(4, bridge.evaluated.size)
        assertTrue(bridge.evaluated[1].second.contains("__mx_tok")) // 轮询的是随机槽位
    }

    @Test fun asyncTimeout() {
        var n = 0
        bridge.evalHandler = { _, _ -> if (++n == 1) lit("""{"pending":1}""") else "null" }
        assertEquals(ErrorCodes.TIMEOUT, code(exec("func" to "function(){return new Promise(()=>{})}")))
    }

    // ───── 权限 ─────

    @Test fun hostPermissionIsRequired() {
        bridge.evalHandler = { _, _ -> lit("""{"ok":1}""") }
        assertEquals(ErrorCodes.DENIED_PERMISSION, code(execOn(tabO, "func" to "function(){}")))
        assertTrue(bridge.evaluated.isEmpty())
        assertEquals("No tab with id: 99.", message(execOn(99, "func" to "function(){}")))
    }

    @Test fun apiPermissionAndCallerAreChecked() {
        install(granted = setOf("storage")) // 没有 scripting
        assertEquals(ErrorCodes.DENIED_PERMISSION, code(exec("func" to "function(){}")))
        install()
        val cs = Sender(SenderKind.CONTENT_SCRIPT, "https://granted.com", true, tabG)
        val d = JSONObject().put("target", JSONObject().put("tabId", tabG)).put("func", "function(){}")
        assertEquals(ErrorCodes.DENIED_PERMISSION, code(call("scripting.executeScript", d, cs)))
    }

    @Test fun argumentValidation() {
        bridge.evalHandler = { _, _ -> lit("""{"ok":1}""") }
        val f = "function(){}"
        assertEquals(ErrorCodes.BAD_REQUEST, code(exec()))                                   // 都没给
        assertEquals(ErrorCodes.BAD_REQUEST, code(exec("func" to f, "files" to JSONArray(listOf("a.js"))))) // 都给了
        assertEquals(ErrorCodes.BAD_REQUEST, code(exec("func" to f, "world" to "WORLD")))
        assertEquals(ErrorCodes.BAD_REQUEST, code(exec("func" to f, "args" to "x")))
        val frame1 = JSONObject().put("tabId", tabG).put("frameIds", JSONArray(listOf(1)))
        assertEquals(ErrorCodes.UNSUPPORTED, code(call("scripting.executeScript", JSONObject().put("target", frame1).put("func", f))))
        val frame0 = JSONObject().put("tabId", tabG).put("frameIds", JSONArray(listOf(0))).put("allFrames", true)
        assertEquals(ErrorCodes.UNSUPPORTED, code(call("scripting.executeScript", JSONObject().put("target", frame0).put("func", f))))
        assertEquals(ErrorCodes.BAD_REQUEST, code(call("scripting.executeScript", JSONObject().put("func", f)))) // 缺 target
        assertEquals(ErrorCodes.BAD_REQUEST, code(call("scripting.executeScript", null)))
    }

    @Test fun registerGetUpdateAndUnregisterDynamicContentScripts() {
        File(extDir, "dynamic.js").writeText("window.dynamicLoaded = true;")
        val entry = JSONObject()
            .put("id", "dynamic-1")
            .put("matches", JSONArray(listOf("https://granted.com/*")))
            .put("js", JSONArray(listOf(JSONObject().put("file", "dynamic.js"))))
            .put("runAt", "document_start")
        val register = callArgs("scripting.registerContentScripts", JSONArray().put(entry))
        assertTrue(register.toString(), register.getBoolean("ok"))
        val get = callArgs("scripting.getRegisteredContentScripts", JSONArray())
        assertEquals("dynamic-1", get.getJSONArray("result").getJSONObject(0).getString("id"))
        val update = JSONObject().put("id", "dynamic-1").put("runAt", "document_end")
        assertTrue(callArgs("scripting.updateContentScripts", JSONArray().put(update)).getBoolean("ok"))
        assertEquals("document_end", callArgs("scripting.getRegisteredContentScripts", JSONArray()).getJSONArray("result").getJSONObject(0).getString("runAt"))
        assertTrue(callArgs("scripting.unregisterContentScripts", JSONArray().put(JSONObject().put("ids", JSONArray(listOf("dynamic-1"))))).getBoolean("ok"))
        assertEquals(0, callArgs("scripting.getRegisteredContentScripts", JSONArray()).getJSONArray("result").length())
    }

    @Test fun dynamicContentScriptsCannotExceedGrantedHostPermissions() {
        val entry = JSONObject()
            .put("id", "bad")
            .put("matches", JSONArray(listOf("https://other.com/*")))
            .put("js", JSONArray(listOf("dynamic.js")))
        val result = callArgs("scripting.registerContentScripts", JSONArray().put(entry))
        assertEquals(ErrorCodes.DENIED_PERMISSION, code(result))
    }

    @Test fun dynamicContentScriptsPersistAcrossSessionsByDefault() {
        File(extDir, "dynamic.js").writeText("window.dynamicLoaded = true;")
        val entry = JSONObject()
            .put("id", "persisted")
            .put("matches", JSONArray(listOf("https://granted.com/*")))
            .put("js", JSONArray(listOf("dynamic.js")))
        assertTrue(callArgs("scripting.registerContentScripts", JSONArray().put(entry)).getBoolean("ok"))
        val reloadedStore = DynamicContentScriptStore()
        assertEquals("persisted", reloadedStore.all(repo.get(TestExt.ID)!!).single().id)
    }

    // ───── files ─────

    @Test fun filesAreReadFromExtensionDirOnly() {
        File(extDir, "inject.js").writeText("window.x=1;")
        bridge.evalHandler = { _, _ -> "7" }
        val r = results(exec("files" to JSONArray(listOf("/inject.js"))))
        assertEquals(7, r.getJSONObject(0).getInt("result"))
        val js = bridge.evaluated.single().second
        assertTrue(js.startsWith("if(location.origin.toLowerCase()===\"https://granted.com\")"))
        assertTrue(js.contains("window.x=1;"))

        assertEquals(ErrorCodes.BAD_REQUEST, code(exec("files" to JSONArray(listOf("../outside.js")))))
        assertEquals(ErrorCodes.BAD_REQUEST, code(exec("files" to JSONArray(listOf("missing.js")))))
    }

    @Test fun fileGuardMarkerMeansPageChanged() {
        File(extDir, "inject.js").writeText("1;")
        bridge.evalHandler = { _, _ -> lit("__mx_tok") }
        assertEquals(ErrorCodes.DENIED_ORIGIN, code(exec("files" to JSONArray(listOf("inject.js")))))
    }

    // ───── CSS ─────

    private fun cssCall(api: String, vararg extra: Pair<String, Any?>): JSONObject {
        val d = JSONObject().put("target", JSONObject().put("tabId", tabG))
        extra.forEach { d.put(it.first, it.second) }
        return call(api, d)
    }

    private fun keyOf(js: String): String =
        Regex("data-mx[^0-9a-f]{1,4}([0-9a-f]{32})").find(js)!!.groupValues[1]

    @Test fun insertAndRemoveCssUseTheSameKey() {
        bridge.evalHandler = { _, _ -> lit("1") }
        assertTrue(cssCall("scripting.insertCSS", "css" to "body{color:red}").getBoolean("ok"))
        assertTrue(cssCall("scripting.removeCSS", "css" to "body{color:red}").getBoolean("ok"))
        assertTrue(cssCall("scripting.removeCSS", "css" to "p{margin:0}").getBoolean("ok"))
        val (ins, rem, other) = bridge.evaluated.map { it.second }
        assertTrue(ins.contains("\"body{color:red}\""))
        assertEquals(keyOf(ins), keyOf(rem))
        assertNotEquals(keyOf(ins), keyOf(other))
    }

    @Test fun cssFromFilesAndValidation() {
        File(extDir, "a.css").writeText("a{b:c}")
        bridge.evalHandler = { _, _ -> lit("1") }
        assertTrue(cssCall("scripting.insertCSS", "files" to JSONArray(listOf("a.css"))).getBoolean("ok"))
        assertTrue(bridge.evaluated.single().second.contains("a{b:c}"))
        assertEquals(ErrorCodes.BAD_REQUEST, code(cssCall("scripting.insertCSS", "css" to "x", "files" to JSONArray(listOf("a.css")))))
        assertEquals(ErrorCodes.BAD_REQUEST, code(cssCall("scripting.insertCSS")))
        assertEquals(ErrorCodes.UNSUPPORTED, code(cssCall("scripting.insertCSS", "css" to "x", "origin" to "USER")))
        bridge.evalHandler = { _, _ -> lit("0") }
        assertEquals(ErrorCodes.DENIED_ORIGIN, code(cssCall("scripting.insertCSS", "css" to "x")))
    }

    // ───── origin 计算 ─────

    @Test fun originOfMatchesLocationOrigin() {
        val api = ScriptingApi({ null })
        assertEquals("https://a.com", api.originOf("https://User:pw@A.com:443/x?y#z"))
        assertEquals("http://a.com", api.originOf("http://a.com:80/"))
        assertEquals("http://a.com:8080", api.originOf("http://a.com:8080/p"))
        assertNull(api.originOf("file:///x"))
        assertNull(api.originOf("about:blank"))
        assertNull(api.originOf("https:///x"))
    }

    // ───── 隔离世界 ─────

    @Test fun isolatedWorldIsUsedWhenAvailable() {
        bridge.isolatedHandler = { _, _, _ -> WorldEval.Done(lit("""{"ok":1,"v":5}""")) }
        val r = results(exec("func" to "function(){return 5}"))
        assertEquals(5, r.getJSONObject(0).getInt("result"))
        assertEquals(TestExt.ID, bridge.isolatedEvaluated.single().first)
        assertTrue(bridge.evaluated.isEmpty())
    }

    @Test fun isolatedRawWithoutOuterQuotesIsAccepted() {
        bridge.isolatedHandler = { _, _, _ -> WorldEval.Done("""{"ok":1,"v":7}""") }
        assertEquals(7, results(exec("func" to "function(){return 7}")).getJSONObject(0).getInt("result"))
    }

    @Test fun fallsBackToPageWorldWhenNoIsolatedEntry() {
        bridge.evalHandler = { _, _ -> lit("""{"ok":1,"v":1}""") }
        assertEquals(1, results(exec("func" to "function(){return 1}")).getJSONObject(0).getInt("result"))
        assertEquals(1, bridge.evaluated.size)
    }

    @Test fun mainWorldNeverUsesIsolatedEntry() {
        bridge.isolatedHandler = { _, _, _ -> error("MAIN must not use the isolated world") }
        bridge.evalHandler = { _, _ -> lit("""{"ok":1,"v":2}""") }
        results(exec("func" to "function(){return 2}", "world" to "MAIN"))
        assertTrue(bridge.isolatedEvaluated.isEmpty())
    }
}
