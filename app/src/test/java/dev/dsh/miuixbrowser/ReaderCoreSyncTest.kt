package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.browser.ReaderMode
import dev.dsh.miuixbrowser.engine.gecko.GeckoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯 JVM：阅读模式的核心脚本在两处各放一份（WebView 用 assets/mafari_reader.js，
 * Gecko 的内置拓展内容脚本必须在拓展目录里），两份必须逐字一致；拓展 id 也要和原生侧、脚本里写的一致。
 * 改了其中一份，把另一份同步过去即可（cp src/main/assets/mafari_reader.js src/gecko/assets/mafari-reader-ext/reader.js）。
 */
class ReaderCoreSyncTest {
    // Gradle 单元测试的工作目录是模块目录（app/）
    private val webViewCopy = File("src/main/assets/mafari_reader.js")
    private val geckoCopy = File("src/gecko/assets/mafari-reader-ext/reader.js")
    private val manifest = File("src/gecko/assets/mafari-reader-ext/manifest.json")

    @Test
    fun bothCopiesAreIdentical() {
        assertTrue("缺少 ${webViewCopy.path}", webViewCopy.exists())
        assertTrue("缺少 ${geckoCopy.path}", geckoCopy.exists())
        assertEquals(
            "两份阅读脚本不一致：请把 mafari_reader.js 同步到 mafari-reader-ext/reader.js",
            webViewCopy.readText(Charsets.UTF_8),
            geckoCopy.readText(Charsets.UTF_8),
        )
    }

    @Test
    fun extensionIdMatches() {
        val id = GeckoReader.EXT_ID
        assertTrue("manifest 里的拓展 id 与 GeckoReader.EXT_ID 不一致", manifest.readText(Charsets.UTF_8).contains("\"$id\""))
        assertTrue("脚本里的拓展 id 与 GeckoReader.EXT_ID 不一致", webViewCopy.readText(Charsets.UTF_8).contains("'$id'"))
    }

    @Test
    fun scriptsAreSelfContainedForEvaluate() {
        // WebView 把脚本和调用拼在一起注入：脚本必须以分号结尾的 IIFE，且没有 export / import
        val js = webViewCopy.readText(Charsets.UTF_8).trimEnd()
        assertTrue(js.endsWith("})();"))
        assertTrue(!Regex("(?m)^\\s*(import|export)\\s").containsMatchIn(js))
        assertTrue(ReaderMode.detectScript(js).endsWith("window.__mafariReader.detect();"))
    }
}
