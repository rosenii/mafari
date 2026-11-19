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

    @Test fun pagePlanUsesOnlyTheExtensionOriginAndPageMode() {
        val e = ext(mf("[]"))
        val plan = ExtensionInjection.buildPage(e, "function(c,e){}")
        assertEquals(setOf(ExtensionOrigins.originFor(TestExt.ID)), plan.originRules)
        assertEquals(ExtensionInjection.bridgeNameFor(TestExt.ID), plan.bridgeName)
        assertTrue(plan.script.contains("\"page\":true"))
        assertTrue(plan.script.endsWith(",[]);"))
    }
}
