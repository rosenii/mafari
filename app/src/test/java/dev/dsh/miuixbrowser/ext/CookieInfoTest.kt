package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieInfoTest {
    @Test fun domainCookieKeepsRealAttributes() {
        val c = CookieInfo.parse("sid=abc=def; Domain=.Example.com; Path=/app; Secure; HttpOnly; SameSite=None; Expires=Wed, 21 Oct 2037 07:28:00 GMT", "www.example.com")!!
        assertEquals("sid", c.getString("name"))
        assertEquals("abc=def", c.getString("value"))
        assertEquals(".example.com", c.getString("domain"))
        assertFalse(c.getBoolean("hostOnly"))
        assertEquals("/app", c.getString("path"))
        assertTrue(c.getBoolean("secure"))
        assertTrue(c.getBoolean("httpOnly"))
        assertEquals("no_restriction", c.getString("sameSite"))
        assertFalse(c.getBoolean("session"))
        assertEquals(2139722880.0, c.getDouble("expirationDate"), 0.0)
        assertEquals("0", c.getString("storeId"))
    }

    @Test fun hostOnlySessionCookieUsesRequestHostAndDoesNotInventAttributes() {
        val c = CookieInfo.parse("a=1", "Example.com")!!
        assertEquals("example.com", c.getString("domain"))
        assertTrue(c.getBoolean("hostOnly"))
        assertEquals("/", c.getString("path"))
        assertFalse(c.getBoolean("secure"))
        assertFalse(c.getBoolean("httpOnly"))
        assertEquals("unspecified", c.getString("sameSite"))
        assertTrue(c.getBoolean("session"))
        assertFalse(c.has("expirationDate"))
    }

    @Test fun maxAgeBeatsExpires() {
        val before = System.currentTimeMillis() / 1000.0
        val c = CookieInfo.parse("a=1; Max-Age=3600; Expires=Wed, 21 Oct 2037 07:28:00 GMT", "x.com")!!
        val exp = c.getDouble("expirationDate")
        assertTrue(exp >= before + 3599 && exp <= before + 3700)
    }

    @Test fun nameLessCookieAndBlankInput() {
        val c = CookieInfo.parse("justvalue; Path=/", "x.com")!!
        assertEquals("", c.getString("name"))
        assertEquals("justvalue", c.getString("value"))
        assertNull(CookieInfo.parse("   ", "x.com"))
    }

    @Test fun dateFormatsWithDashesParse() {
        assertNotNull(CookieInfo.parseHttpDate("Wed, 21-Oct-2037 07:28:00 GMT"))
        assertNull(CookieInfo.parseHttpDate("not a date"))
    }

    private fun cookie(domain: String, path: String = "/", secure: Boolean = false, session: Boolean = true, name: String = "n") =
        JSONObject().put("name", name).put("domain", domain).put("path", path).put("secure", secure).put("session", session)

    @Test fun filterMatchesDomainBySuffixAndOtherFields() {
        val c = cookie(".sub.example.com", "/a", secure = true, session = false)
        assertTrue(CookieInfo.matches(c, null, "example.com", null, null, null))
        assertTrue(CookieInfo.matches(c, "n", ".sub.example.com", "/a", true, false))
        assertFalse(CookieInfo.matches(c, null, "ample.com", null, null, null))
        assertFalse(CookieInfo.matches(c, "other", null, null, null, null))
        assertFalse(CookieInfo.matches(c, null, null, "/b", null, null))
        assertFalse(CookieInfo.matches(c, null, null, null, false, null))
        assertFalse(CookieInfo.matches(c, null, null, null, null, true))
    }
}
