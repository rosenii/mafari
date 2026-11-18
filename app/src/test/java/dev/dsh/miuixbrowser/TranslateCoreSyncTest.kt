package dev.dsh.miuixbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯 JVM：页内翻译脚本在两处各放一份（WebView 用 assets/mafari_translate.js，
 * Gecko 的内置拓展内容脚本必须在拓展目录里），两份必须逐字一致；拓展 id、原生应用名也要和原生侧一致。
 * 改了其中一份，把另一份同步过去：
 *   cp src/main/assets/mafari_translate.js src/gecko/assets/mafari-translate-ext/translate.js
 */
class TranslateCoreSyncTest {
    private val webViewCopy = File("src/main/assets/mafari_translate.js")
    private val geckoCopy = File("src/gecko/assets/mafari-translate-ext/translate.js")
    private val manifest = File("src/gecko/assets/mafari-translate-ext/manifest.json")
    private val geckoKt = File("src/main/java/dev/dsh/miuixbrowser/engine/gecko/GeckoTranslate.kt")

    @Test
    fun bothCopiesAreIdentical() {
        assertTrue("缺少 ${webViewCopy.path}", webViewCopy.exists())
        assertTrue("缺少 ${geckoCopy.path}", geckoCopy.exists())
        assertEquals(
            "两份翻译脚本不一致：请把 mafari_translate.js 同步到 mafari-translate-ext/translate.js",
            webViewCopy.readText(Charsets.UTF_8),
            geckoCopy.readText(Charsets.UTF_8),
        )
    }

    @Test
    fun extensionIdAndNativeNameMatch() {
        val js = webViewCopy.readText(Charsets.UTF_8)
        val kt = geckoKt.readText(Charsets.UTF_8)
        val id = Regex("EXT_ID = \"([^\"]+)\"").find(kt)!!.groupValues[1]
        val native = Regex("NATIVE = \"([^\"]+)\"").find(kt)!!.groupValues[1]
        assertTrue("manifest 里的拓展 id 与 GeckoTranslate.EXT_ID 不一致", manifest.readText(Charsets.UTF_8).contains("\"$id\""))
        assertTrue("脚本里的拓展 id 与 GeckoTranslate.EXT_ID 不一致", js.contains("'$id'"))
        assertTrue("脚本里的原生应用名与 GeckoTranslate.NATIVE 不一致", js.contains("connectNative('$native')"))
    }

    @Test
    fun scriptIsSelfContainedForEvaluate() {
        // WebView 把脚本和调用拼在一起注入：脚本必须以分号结尾的 IIFE，且没有 export / import
        val js = webViewCopy.readText(Charsets.UTF_8).trimEnd()
        assertTrue(js.endsWith("})();"))
        assertTrue(!Regex("(?m)^\\s*(import|export)\\s").containsMatchIn(js))
    }
}
