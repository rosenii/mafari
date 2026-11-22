package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Test

class ContentModuleBundlerTest {
    private fun reader(vararg files: Pair<String, String>): (String) -> String? {
        val m = files.toMap()
        return { m[it] }
    }

    @Test fun scriptWithoutDynamicImportIsUntouched() {
        val r = ContentModuleBundler.process("console.log(1)", reader())
        assertEquals("console.log(1)", r.rewrittenJs)
        assertNull(r.modulesJs)
    }

    @Test fun inlinesModuleReferencedByGetUrlAndRewritesImport() {
        val loader = """(async()=>{await import(chrome.runtime.getURL("content_main.js"))})();"""
        val r = ContentModuleBundler.process(loader, reader("content_main.js" to "console.log('main');"))
        assertTrue(r.rewrittenJs.contains("__mafariImport(chrome.runtime.getURL"))
        assertFalse(Regex("""(?<![\w$.])import\s*\(""").containsMatchIn(r.rewrittenJs))
        assertTrue(r.modulesJs!!.contains("\"content_main.js\":async function(__exp,__req,__url,__imp)"))
    }

    @Test fun staticImportsAndExportsAreConverted() {
        val loader = """import(chrome.runtime.getURL("a/main.js"))"""
        val main = """import{h as help}from"./util.js";import d from"../d.js";export{help as run};export default 1;"""
        val r = ContentModuleBundler.process(
            loader,
            reader("a/main.js" to main, "a/util.js" to "export const h=1;", "d.js" to "export default 2;"),
        )
        val js = r.modulesJs!!
        assertTrue(js.contains("const {h:help}=(await __req(\"a/util.js\"))"))
        assertTrue(js.contains("const d=(await __req(\"d.js\")).default"))
        assertTrue(js.contains("__d(\"run\",()=>help)"))
        assertTrue(js.contains("__exp.default=1"))
        assertTrue(js.contains("\"a/util.js\":") && js.contains("\"d.js\":"))
        assertTrue(js.contains("__d(\"h\",()=>h)"))
    }

    @Test fun unknownTargetsAreLeftForNativeImportFallback() {
        val r = ContentModuleBundler.process("""import("https://cdn.example.com/x.js")""", reader())
        assertNull(r.modulesJs)
        assertTrue(r.rewrittenJs.startsWith("__mafariImport("))
    }

    @Test fun resolveHandlesRelativeSegments() {
        assertEquals("a/b.js", ContentModuleBundler.resolve("a/main.js", "./b.js"))
        assertEquals("b.js", ContentModuleBundler.resolve("a/main.js", "../b.js"))
        assertEquals("b.js", ContentModuleBundler.resolve("a/main.js", "/b.js"))
    }

    @Test fun importTextInsideStringsCommentsAndRegexIsNotRewritten() {
        val loader = "var s=\"import(x)\"; // import(y)\nvar r=/import\\(/; import(chrome.runtime.getURL(\"m.js\"))"
        val r = ContentModuleBundler.process(loader, reader("m.js" to "1;"))
        assertTrue(r.rewrittenJs.contains("\"import(x)\""))
        assertTrue(r.rewrittenJs.contains("// import(y)"))
        assertTrue(r.rewrittenJs.contains("/import\\(/"))
        assertEquals(1, Regex("__mafariImport\\(").findAll(r.rewrittenJs).count())
    }

    @Test fun exportStarAndNamespaceReexportAreSupported() {
        val loader = """import(chrome.runtime.getURL("m.js"))"""
        val main = """export*from"./u.js";export*as NS from"./u.js";export default function run(){}"""
        val js = ContentModuleBundler.process(loader, reader("m.js" to main, "u.js" to "export const a=1;")).modulesJs!!
        assertTrue(js.contains("Object.keys("))
        assertTrue(js.contains("__d(\"NS\""))
        assertTrue(js.contains("__d(\"default\",()=>run)"))
        assertTrue(js.contains("\"u.js\":"))
    }

    @Test fun importMetaAndNestedDynamicImportInsideModule() {
        val loader = """import(chrome.runtime.getURL("m.js"))"""
        val js = ContentModuleBundler.process(loader, reader("m.js" to "const u=import.meta.url;import('./n.js');", "n.js" to "1;")).modulesJs!!
        assertTrue(js.contains("({url:__url})"))
        assertTrue(js.contains("__imp('./n.js')"))
        assertTrue(js.contains("\"n.js\":"))
    }

    @Test fun modsKeysListTheWholeDependencyClosureForLazyDelivery() {
        val loader = """import(chrome.runtime.getURL("m.js"))"""
        val r = ContentModuleBundler.process(
            loader,
            reader("m.js" to "import{a}from\"./u/a.js\";", "u/a.js" to "import\"../b.js\";export const a=1;", "b.js" to "1;"),
        )
        assertEquals(listOf("m.js", "u/a.js", "b.js"), r.mods.keys.toList())
    }

    @Test fun sameSourceIsCompiledOnceAndReused() {
        val loader = """import(chrome.runtime.getURL("m.js"))"""
        val files = reader("m.js" to "export default 1;")
        val a = ContentModuleBundler.process(loader, files).mods["m.js"]
        val b = ContentModuleBundler.process(loader, files).mods["m.js"]
        assertSame(a, b)
    }
}
