package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiProtocolTest {
    private fun code(raw: String): String? = try {
        ApiProtocol.parseRequest(raw)
        null
    } catch (e: ApiException) {
        e.code
    }

    @Test fun parsesValidRequest() {
        val r = ApiProtocol.parseRequest("""{"extId":"demo@x.org","id":"7","api":"storage.local.get","args":[["a"]]}""")
        assertEquals("demo@x.org", r.extId)
        assertEquals("7", r.id)
        assertEquals("storage.local.get", r.api)
        assertEquals(1, r.args.length())
    }

    @Test fun numericIdAndMissingArgs() {
        val r = ApiProtocol.parseRequest("""{"extId":"e","id":12,"api":"runtime.getManifest"}""")
        assertEquals("12", r.id)
        assertEquals(0, r.args.length())
    }

    @Test fun rejectsMalformed() {
        assertEquals(ErrorCodes.BAD_REQUEST, code("not json"))
        assertEquals(ErrorCodes.BAD_REQUEST, code("""{"extId":"e","api":"a.b"}"""))
        assertEquals(ErrorCodes.BAD_REQUEST, code("""{"extId":"../x","id":"1","api":"a.b"}"""))
        assertEquals(ErrorCodes.BAD_REQUEST, code("""{"extId":"e","id":"1","api":"nodot"}"""))
        assertEquals(ErrorCodes.BAD_REQUEST, code("""{"extId":"e","id":"1","api":"a.b; drop"}"""))
        assertEquals(ErrorCodes.BAD_REQUEST, code("""{"extId":"e","id":"1","api":"a.b","args":{"x":1}}"""))
        assertEquals(ErrorCodes.BAD_REQUEST, code("x".repeat(ApiProtocol.MAX_MESSAGE_CHARS + 1)))
    }

    @Test fun responseShapes() {
        val ok = JSONObject(ApiProtocol.ok("1", null))
        assertTrue(ok.getBoolean("ok"))
        assertTrue(ok.isNull("result"))
        assertTrue(ok.has("result"))

        val err = JSONObject(ApiProtocol.error(null, ErrorCodes.INTERNAL, "m"))
        assertFalse(err.getBoolean("ok"))
        assertTrue(err.isNull("id"))
        assertEquals(ErrorCodes.INTERNAL, err.getJSONObject("error").getString("code"))
    }

    @Test fun bestEffortId() {
        assertEquals("5", ApiProtocol.bestEffortId("""{"id":"5","api":"bad"}"""))
        assertNull(ApiProtocol.bestEffortId("garbage"))
    }
}
