/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionPagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ext(manifest: String, vararg files: Pair<String, String>): InstalledExtension {
        val dir = File(tmp.root, "e${System.nanoTime()}")
        val e = TestExt.make(dir, manifest = manifest)
        for ((n, t) in files) File(dir, n).apply { parentFile?.mkdirs() }.writeText(t)
        return e
    }

    private fun mf(background: String, extra: String = "") =
        """{"manifest_version":2,"name":"D","version":"1","background":$background$extra}"""

    @Test fun mv2ScriptsBecomeAGeneratedPageInOrder() {
        val e = ext(mf("""{"scripts":["a.js","lib/b.js"]}"""), "a.js" to "1", "lib/b.js" to "2")
        assertEquals(ExtensionPages.GENERATED_BACKGROUND, ExtensionPages.backgroundEntry(e)!!.path)
        val html = String(ExtensionPages.open(e, "/" + ExtensionPages.GENERATED_BACKGROUND)!!.bytes!!, Charsets.UTF_8)
        assertTrue(html.indexOf("src=\"/a.js\"") in 0 until html.indexOf("src=\"/lib/b.js\""))
        assertFalse(html.contains("sw_shim"))
    }

    @Test fun backgroundPageIsUsedAsIs() {
        val e = ext(mf("""{"page":"bg/page.html"}"""), "bg/page.html" to "<html></html>")
        assertEquals("bg/page.html", ExtensionPages.backgroundEntry(e)!!.path)
        val r = ExtensionPages.open(e, "/bg/page.html?x=1#y")!!
        assertEquals("text/html", r.mime)
        assertNotNull(r.file)
    }

    @Test fun serviceWorkerGetsShimBeforeScript() {
        val classic = ext(
            """{"manifest_version":3,"name":"D","version":"1","background":{"service_worker":"sw.js"}}""",
            "sw.js" to "1",
        )
        val h1 = String(ExtensionPages.open(classic, ExtensionPages.GENERATED_BACKGROUND)!!.bytes!!, Charsets.UTF_8)
        assertTrue(h1.indexOf("sw_shim.js") in 0 until h1.indexOf("src=\"/sw.js\""))
        assertFalse(h1.contains("type=\"module\""))
        val shim = ExtensionPages.open(classic, "/" + ExtensionPages.SW_SHIM)!!
        assertEquals("text/javascript", shim.mime)
        val shimJs = String(shim.bytes!!, Charsets.UTF_8)
        assertTrue(shimJs.contains("importScripts"))
        assertTrue(shimJs.contains("ServiceWorkerGlobalScope") && shimJs.contains("skipWaiting") && shimJs.contains("activate"))

        val module = ext(
            """{"manifest_version":3,"name":"D","version":"1","background":{"service_worker":"sw.js","type":"module"}}""",
            "sw.js" to "1",
        )
        val h2 = String(ExtensionPages.open(module, ExtensionPages.GENERATED_BACKGROUND)!!.bytes!!, Charsets.UTF_8)
        assertTrue(h2.contains("<script type=\"module\" src=\"/sw.js\">"))
        assertTrue(h2.indexOf("sw_shim.js") in 0 until h2.indexOf("type=\"module\""))
    }

    @Test fun noBackgroundMeansNoEntryAndNoGeneratedPage() {
        val e = ext("""{"manifest_version":3,"name":"D","version":"1"}""")
        assertNull(ExtensionPages.backgroundEntry(e))
        assertNull(ExtensionPages.open(e, ExtensionPages.GENERATED_BACKGROUND))
    }

    @Test fun openStaysInsideTheExtensionDirectory() {
        val e = ext(mf("""{"scripts":["a.js"]}"""), "a.js" to "1", "p.png" to "x")
        File(e.rootDir.parentFile, "secret.txt").writeText("s")
        assertNotNull(ExtensionPages.open(e, "/a.js"))
        assertEquals("image/png", ExtensionPages.open(e, "p.png")!!.mime)
        assertNull(ExtensionPages.open(e, "/../secret.txt"))
        assertNull(ExtensionPages.open(e, "a/../../secret.txt"))
        assertNull(ExtensionPages.open(e, "missing.js"))
        assertNull(ExtensionPages.open(e, ""))
        assertNull(ExtensionPages.open(e, "/"))
    }

    @Test fun scriptNamesAreEscapedInGeneratedHtml() {
        val e = ext(mf("""{"scripts":["a\"><script>x</script>.js"]}"""))
        val html = String(ExtensionPages.open(e, ExtensionPages.GENERATED_BACKGROUND)!!.bytes!!, Charsets.UTF_8)
        assertFalse(html.contains("\"><script>x"))
    }

    @Test fun mimeTypes() {
        assertEquals("text/javascript", ExtensionPages.mimeTypeFor("a/b.MJS"))
        assertEquals("application/octet-stream", ExtensionPages.mimeTypeFor("noext"))
        assertEquals("image/svg+xml", ExtensionPages.mimeTypeFor("i.svg"))
    }

    @Test fun webAccessibleResources() {
        val mv2 = ext(mf("""{"scripts":["a.js"]}""", ""","web_accessible_resources":["img/*","x.js"]"""))
        assertTrue(ExtensionPages.isWebAccessible(mv2, "/img/a/b.png", "https://any.com/"))
        assertTrue(ExtensionPages.isWebAccessible(mv2, "x.js", null))
        assertFalse(ExtensionPages.isWebAccessible(mv2, "secret.js", "https://any.com/"))
        assertFalse(ExtensionPages.isWebAccessible(mv2, "../x.js", "https://any.com/"))

        val mv3 = ext(
            """{"manifest_version":3,"name":"D","version":"1","web_accessible_resources":[{"resources":["pic.png"],"matches":["https://a.com/*"]}]}""",
        )
        assertTrue(ExtensionPages.isWebAccessible(mv3, "pic.png", "https://a.com/page"))
        assertFalse(ExtensionPages.isWebAccessible(mv3, "pic.png", "https://b.com/page"))
        assertFalse(ExtensionPages.isWebAccessible(mv3, "pic.png", null))
        assertFalse(ExtensionPages.isWebAccessible(mv3, "other.png", "https://a.com/page"))
    }
}
