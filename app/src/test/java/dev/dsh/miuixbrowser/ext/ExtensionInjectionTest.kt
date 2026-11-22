package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionInjectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val shim: String by lazy { File("src/main/assets/mafari_ext_shim.js").readText() }

    private fun ext(manifest: String, vararg files: Pair<String, String>): InstalledExtension {
        val dir = File(tmp.root, "e${System.nanoTime()}")
        val e = TestExt.make(dir, manifest = manifest)
        for ((name, text) in files) {
            File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
        }
        return e
    }

    private fun mf(contentScripts: String) =
        """{"manifest_version":3,"name":"Demo","version":"1","content_scripts":$contentScripts}"""

    @Test fun prepareThenFinishMatchesBuildForEveryTab() {
        val e = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["c.js"],"run_at":"document_idle"},{"matches":["https://b.com/*"],"js":["m.js"],"world":"MAIN"}]"""),
            "c.js" to "console.log('c')",
            "m.js" to "console.log('m')",
        )
        val read = ExtensionInjection.fileReader(e.rootDir)
        // 冷启动预热只做一次 prepare，之后每个标签只 finish：结果必须和每次完整 build 逐字一致（tabId 照样进脚本）
        val prepared = ExtensionInjection.prepare(e, read)!!
        for (tabId in listOf(null, 1, 42)) {
            val expected = ExtensionInjection.build(e, shim, read, tabId = tabId)!!
            val actual = ExtensionInjection.finish(prepared, shim, tabId)
            assertEquals(expected.script, actual.script)
            assertEquals(expected.isolatedScript, actual.isolatedScript)
            assertEquals(expected.mainScript, actual.mainScript)
            assertEquals(expected.isolatedEndScript, actual.isolatedEndScript)
            assertEquals(expected.originRules, actual.originRules)
            assertEquals(expected.isolatedOriginRules, actual.isolatedOriginRules)
            assertEquals(expected.mainOriginRules, actual.mainOriginRules)
        }
        assertTrue(ExtensionInjection.finish(prepared, shim, 42).script.contains("\"tabId\":42"))
        assertFalse(ExtensionInjection.finish(prepared, shim, null).script.contains("\"tabId\""))
    }

    @Test fun buildsPlanWithRulesBridgeAndScript() {
        val e = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["c.js"],"css":["s.css"],"run_at":"document_start","all_frames":true}]"""),
            "c.js" to "console.log('hi') // no trailing newline",
            "s.css" to "body{color:red}",
        )
        val plan = ExtensionInjection.build(e, shim, ExtensionInjection.fileReader(e.rootDir))!!
        assertEquals(setOf("https://a.com"), plan.originRules)
        assertEquals(ExtensionInjection.bridgeNameFor(TestExt.ID), plan.bridgeName)
        assertTrue(Regex("^__mext_[0-9a-f]{12}$").matches(plan.bridgeName))
        assertTrue(plan.warnings.isEmpty())

        val s = plan.script
        assertTrue(s.startsWith("(" + shim))
        assertTrue(s.contains("\"extId\":\"demo@x.org\""))
        assertTrue(s.contains("\"bridge\":\"${plan.bridgeName}\""))
        assertTrue(s.contains("\"at\":\"document_start\""))
        assertTrue(s.contains("\"all\":true"))
        assertTrue(s.contains("function(browser,chrome){\nconsole.log('hi') // no trailing newline\n}"))
        assertTrue(s.contains("\"css\":[\"body{color:red}\"]"))
        assertTrue(s.contains(ExtensionOrigins.originFor(TestExt.ID)))
        assertTrue(s.endsWith(");"))
    }

    @Test fun manifestIsEmbeddedForSynchronousGetManifest() {
        val e = ext(mf("""[{"matches":["https://a.com/*"],"js":["c.js"]}]"""), "c.js" to "1")
        val plan = ExtensionInjection.build(e, "function(c,e){}", ExtensionInjection.fileReader(e.rootDir))!!
        val cfgText = plan.script.removePrefix("(function(c,e){})(").substringBefore(",[{")
        assertEquals("Demo", JSONObject(cfgText).getJSONObject("manifest").getString("name"))
    }

    @Test fun missingFilesAreReportedAndEntriesWithNothingAreDropped() {
        val e = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["gone.js","c.js"]},{"matches":["https://b.com/*"],"js":["nope.js"]}]"""),
            "c.js" to "1",
        )
        val plan = ExtensionInjection.build(e, shim, ExtensionInjection.fileReader(e.rootDir))!!
        assertEquals(setOf("https://a.com"), plan.originRules)
        assertEquals(1, plan.warnings.count { it.contains("gone.js") })
        assertEquals(1, plan.warnings.count { it.contains("nope.js") })
        // manifest 会整体嵌入（getManifest 要同步返回），所以不能按 URL 文本判断；看匹配描述符里有没有 b.com
        assertFalse(plan.script.contains("\"h\":\"b.com\""))
    }

    @Test fun nothingInjectableReturnsNull() {
        val none = ext(mf("[]"))
        assertNull(ExtensionInjection.build(none, shim, ExtensionInjection.fileReader(none.rootDir)))
        val allMissing = ext(mf("""[{"matches":["https://a.com/*"],"js":["x.js"]}]"""))
        assertNull(ExtensionInjection.build(allMissing, shim, ExtensionInjection.fileReader(allMissing.rootDir)))
        val nonWeb = ext(mf("""[{"matches":["ftp://f.org/*"],"js":["c.js"]}]"""), "c.js" to "1")
        assertNull(ExtensionInjection.build(nonWeb, shim, ExtensionInjection.fileReader(nonWeb.rootDir)))
    }

    @Test fun allUrlsCollapsesToWildcardRule() {
        val e = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["c.js"]},{"matches":["<all_urls>"],"js":["c.js"]}]"""),
            "c.js" to "1",
        )
        val plan = ExtensionInjection.build(e, shim, ExtensionInjection.fileReader(e.rootDir))!!
        assertEquals(setOf("*"), plan.originRules)
    }

    @Test fun fileReaderStaysInsideTheExtensionDirectory() {
        val dir = File(tmp.root, "ext").apply { mkdirs() }
        File(dir, "ok.js").writeText("ok")
        File(tmp.root, "secret.txt").writeText("secret")
        val read = ExtensionInjection.fileReader(dir)
        assertEquals("ok", read("ok.js"))
        assertEquals("ok", read("./ok.js"))
        assertNull(read("../secret.txt"))
        assertNull(read("a/../../secret.txt"))
        assertNull(read(File(tmp.root, "secret.txt").absolutePath))
        assertNull(read("missing.js"))
        assertNull(read(""))
    }

    @Test fun matchDescriptorsAreEmbeddedForPathNarrowing() {
        val e = ext(mf("""[{"matches":["https://*.b.com/foo/*"],"js":["c.js"]}]"""), "c.js" to "1")
        val plan = ExtensionInjection.build(e, shim, ExtensionInjection.fileReader(e.rootDir))!!
        assertEquals(setOf("https://b.com", "https://*.b.com"), plan.originRules)
        assertTrue(plan.script.contains("\"h\":\"b.com\""))
        assertTrue(plan.script.contains("\"sub\":true"))
    }

    @Test fun filesOfOneContentScriptShareOneFunctionScope() {
        val e = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["utils.js","main.js"]}]"""),
            "utils.js" to "function helper(){return 1}",
            "main.js" to "helper()",
        )
        val plan = ExtensionInjection.build(e, shim, ExtensionInjection.fileReader(e.rootDir))!!
        assertEquals(1, Regex("function\\(browser,chrome\\)\\{").findAll(plan.script).count())
        assertTrue(plan.script.contains("function helper(){return 1}\n;\nhelper()"))
    }


    @Test fun dynamicContentScriptsAreMergedIntoDocumentStartPlan() {
        val e = TestExt.make(
            File(tmp.root, "dynamic-${System.nanoTime()}"),
            manifest = mf("[]"),
            grantedHosts = listOf("https://example.com/*"),
        ).also { File(it.rootDir, "dynamic.js").writeText("window.dynamicInjected = true;") }
        val dynamic = ContentScriptSpec(
            matches = listOf(MatchPattern.parse("https://example.com/*")!!),
            excludeMatches = emptyList(),
            js = listOf("dynamic.js"),
            css = emptyList(),
            runAt = RunAt.DOCUMENT_START,
            allFrames = false,
            matchAboutBlank = false,
            world = ScriptWorld.ISOLATED,
        )
        val plan = ExtensionInjection.build(e, "function(c,e){}", ExtensionInjection.fileReader(e.rootDir), listOf(dynamic))!!
        assertTrue(plan.originRules.contains("https://example.com"))
        assertTrue(plan.script.contains("window.dynamicInjected = true;"))
    }


    @Test fun dynamicContentScriptsAreNotInjectedAfterHostPermissionRevocation() {
        val e = TestExt.make(
            File(tmp.root, "revoked-${System.nanoTime()}"),
            manifest = mf("[]"),
            grantedHosts = listOf("https://example.com/*"),
        ).also { File(it.rootDir, "dynamic.js").writeText("window.dynamicInjected = true;") }
        val dynamic = ContentScriptSpec(
            matches = listOf(MatchPattern.parse("https://example.com/*")!!),
            excludeMatches = emptyList(), js = listOf("dynamic.js"), css = emptyList(),
            runAt = RunAt.DOCUMENT_START, allFrames = false, matchAboutBlank = false, world = ScriptWorld.ISOLATED,
        )
        val revoked = e.copy(grantedHostPermissions = emptyList())
        assertNull(ExtensionInjection.build(revoked, "function(c,e){}", ExtensionInjection.fileReader(revoked.rootDir), listOf(dynamic)))
    }

    @Test fun pagePlanUsesOnlyTheExtensionOriginAndPageMode() {
        val e = ext(mf("[]"))
        val plan = ExtensionInjection.buildPage(e, "function(c,e){}")
        assertEquals(setOf(ExtensionOrigins.originFor(TestExt.ID)), plan.originRules)
        assertEquals(ExtensionInjection.bridgeNameFor(TestExt.ID), plan.bridgeName)
        assertTrue(plan.script.contains("\"page\":true"))
        assertTrue(plan.script.endsWith(",[]);"))
    }

    @Test fun splitsContentScriptsByWorld() {
        val e = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["iso.js"]},{"matches":["https://b.com/*"],"js":["main.js"],"world":"MAIN"}]"""),
            "iso.js" to "window.isoMarker=1;",
            "main.js" to "window.mainMarker=1;",
        )
        val plan = ExtensionInjection.build(e, shim, ExtensionInjection.fileReader(e.rootDir))!!
        assertTrue(Regex("^mw_[0-9a-f]{12}$").matches(plan.worldName))
        assertEquals(ExtensionInjection.worldNameFor(TestExt.ID), plan.worldName)

        val iso = plan.isolatedScript!!
        assertTrue(iso.contains("isoMarker") && !iso.contains("mainMarker"))
        assertTrue(iso.contains("\"isolatedWorld\":true") && iso.contains("\"extId\""))
        assertEquals(setOf("https://a.com"), plan.isolatedOriginRules)

        val main = plan.mainScript!!
        assertTrue(main.contains("mainMarker") && !main.contains("isoMarker"))
        assertTrue(main.contains("\"mainWorld\":true"))
        assertFalse(main.contains(plan.bridgeName) || main.contains("\"extId\"") || main.contains("\"manifest\""))
        assertEquals(setOf("https://b.com"), plan.mainOriginRules)

        // 旧路径脚本仍含全部条目
        assertTrue(plan.script.contains("isoMarker") && plan.script.contains("mainMarker"))
    }

    @Test fun mainOnlyExtensionHasNoIsolatedScript() {
        val e = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["m.js"],"world":"MAIN"}]"""),
            "m.js" to "1;",
        )
        val plan = ExtensionInjection.build(e, shim, ExtensionInjection.fileReader(e.rootDir))!!
        assertNull(plan.isolatedScript)
        assertNotNull(plan.mainScript)
    }

    @Test fun isolatedEndScriptOnlyWhenSomeEntryRunsAfterDocumentStart() {
        val onlyStart = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["s.js"],"run_at":"document_start"}]"""),
            "s.js" to "1;",
        )
        val p1 = ExtensionInjection.build(onlyStart, shim, ExtensionInjection.fileReader(onlyStart.rootDir))!!
        assertNull(p1.isolatedEndScript)
        assertFalse(p1.isolatedScript!!.contains("nativeEnd"))

        val withEnd = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["s.js"],"run_at":"document_start"},{"matches":["https://a.com/*"],"js":["e.js"],"run_at":"document_end"}]"""),
            "s.js" to "1;", "e.js" to "2;",
        )
        val p2 = ExtensionInjection.build(withEnd, shim, ExtensionInjection.fileReader(withEnd.rootDir))!!
        val hook = ExtensionInjection.endHookFor(TestExt.ID)
        assertTrue(Regex("^__mend_[0-9a-f]{12}$").matches(hook))
        assertTrue(p2.isolatedScript!!.contains("\"nativeEnd\":\"$hook\""))
        assertEquals(ExtensionInjection.endScriptFor(TestExt.ID), p2.isolatedEndScript)
        assertTrue(p2.isolatedEndScript!!.contains(hook))
        // 旧路径（页面世界）的脚本不带钩子，也不泄露钩子名
        assertFalse(p2.script.contains("nativeEnd") || p2.script.contains(hook))
    }

    @Test fun mainWorldEndEntriesDoNotCreateNativeEndScript() {
        val e = ext(
            mf("""[{"matches":["https://a.com/*"],"js":["m.js"],"world":"MAIN","run_at":"document_end"}]"""),
            "m.js" to "1;",
        )
        val plan = ExtensionInjection.build(e, shim, ExtensionInjection.fileReader(e.rootDir))!!
        assertNull(plan.isolatedEndScript)
        assertFalse(plan.mainScript!!.contains("nativeEnd"))
    }
}
