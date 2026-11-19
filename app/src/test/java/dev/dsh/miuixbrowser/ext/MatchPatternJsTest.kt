package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 与 tools/ext-shim-test/run.js 共用同一份 match_cases.json：Kotlin 的 matches() 与 JS 的 matchDesc() 都要对得上 */
class MatchPatternJsTest {
    private fun cases(): JSONArray {
        val text = javaClass.getResourceAsStream("/ext/match_cases.json")!!.bufferedReader().use { it.readText() }
        return JSONArray(text)
    }

    @Test fun descriptorsAndKotlinMatchingAgreeWithFixture() {
        val all = cases()
        assertTrue(all.length() > 0)
        for (i in 0 until all.length()) {
            val c = all.getJSONObject(i)
            val pattern = c.getString("pattern")
            val parsed = MatchPattern.parse(pattern)
            assertNotNull("无法解析 $pattern", parsed)
            val p = parsed!!
            val want = c.getJSONObject("desc")
            val got = MatchPatternJs.descriptor(p)

            val wantSchemes = (0 until want.getJSONArray("s").length()).map { want.getJSONArray("s").getString(it) }.toSet()
            val gotSchemes = (0 until got.getJSONArray("s").length()).map { got.getJSONArray("s").getString(it) }.toSet()
            assertEquals(pattern, wantSchemes, gotSchemes)
            for (k in listOf("h", "p", "r")) {
                assertEquals("$pattern / $k", want.isNull(k), got.isNull(k))
                if (!want.isNull(k)) assertEquals("$pattern / $k", want.getString(k), got.getString(k))
            }
            assertEquals("$pattern / sub", want.getBoolean("sub"), got.getBoolean("sub"))

            val urls: JSONObject = c.getJSONObject("urls")
            for (url in urls.keys()) {
                assertEquals("$pattern vs $url", urls.getBoolean(url), p.matches(url))
            }
        }
    }

    @Test fun pathRegexEscapesMetaCharacters() {
        assertEquals("^\\/a\\.b\\?x=.*&y$", MatchPatternJs.pathRegex("/a.b?x=*&y"))
        assertEquals("^\\/\\(x\\)\\[1\\]\\{2\\}\\|\\+\\^\\\\$", MatchPatternJs.pathRegex("/(x)[1]{2}|+^\\"))
    }
}
