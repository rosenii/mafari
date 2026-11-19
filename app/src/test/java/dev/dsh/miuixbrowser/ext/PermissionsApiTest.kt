package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PermissionsApiTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun getAllAndContainsUseEffectiveGrants() = runBlocking {
        val manifest = """{"manifest_version":3,"name":"Demo","version":"1","permissions":["storage","tabs"],
            "optional_permissions":["alarms"],"host_permissions":["https://granted.com/*"]}"""
        val ext = TestExt.make(
            tmp.newFolder("ext"), manifest = manifest,
            granted = setOf("storage", "tabs"),
            grantedHosts = listOf("https://granted.com/*")
        )
        val router = ApiRouter()
        PermissionsApi().register(router)
        val sender = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(ext.spec.id), true)
        val getAll = router.find("permissions.getAll")!!
            .handle(CallContext(ext, sender, "permissions.getAll"), org.json.JSONArray())
            as JSONObject
        assertTrue(getAll.getJSONArray("permissions").toString().contains("storage"))
        assertFalse(getAll.getJSONArray("permissions").toString().contains("alarms"))
        assertEquals("[\\"https://granted.com/*\\"]", getAll.getJSONArray("origins").toString())

        val contains = router.find("permissions.contains")!!
        assertEquals(true, contains.handle(
            CallContext(ext, sender, "permissions.contains"),
            org.json.JSONArray().put(JSONObject("""{"permissions":["storage"],"origins":["https://granted.com/*"]}"""))
        ))
        assertEquals(false, contains.handle(
            CallContext(ext, sender, "permissions.contains"),
            org.json.JSONArray().put(JSONObject("""{"permissions":["alarms"]}"""))
        ))
    }
}
