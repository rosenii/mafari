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
        val repo = FakeRepo().apply { put(ext) }
        val router = ApiRouter()
        PermissionsApi(repo, EventSink { _, _, _ -> }) { _, _, _ -> true }.register(router)
        val sender = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(ext.spec.id), true)
        val getAll = router.find("permissions.getAll")!!
            .handle(CallContext(ext, sender, "permissions.getAll"), org.json.JSONArray())
            as JSONObject
        assertTrue(getAll.getJSONArray("permissions").toString().contains("storage"))
        assertFalse(getAll.getJSONArray("permissions").toString().contains("alarms"))
        assertEquals("[\"https://granted.com/*\"]", getAll.getJSONArray("origins").toString())

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
    @Test fun requestAndRemoveOptionalPermissionsPersistAndEmitEvents() = runBlocking {
        val manifest = """{"manifest_version":3,"name":"Demo","version":"1","permissions":["storage"],
            "optional_permissions":["alarms"],"optional_host_permissions":["https://optional.example/*"]}"""
        val ext = TestExt.make(tmp.newFolder("optional"), manifest = manifest, granted = setOf("storage"), grantedHosts = emptyList())
        val repo = FakeRepo().apply { put(ext) }
        val emitted = mutableListOf<Pair<String, JSONObject>>()
        val events = EventSink { _, name, payload -> emitted += name to JSONObject(payload.toString()) }
        val router = ApiRouter()
        val refreshed = mutableListOf<String>()
        PermissionsApi(repo, events, { _, _, _ -> true }, { refreshed += it }).register(router)
        val sender = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(ext.spec.id), true)
        val request = router.find("permissions.request")!!
        assertEquals(true, request.handle(CallContext(ext, sender, "permissions.request"), org.json.JSONArray().put(
            JSONObject("""{"permissions":["alarms"],"origins":["https://optional.example/*"]}""")
        )))
        val granted = repo.get(ext.spec.id)!!
        assertTrue("alarms" in PermissionChecker.effectivePermissions(granted))
        assertEquals(1, granted.grantedHostPermissions.size)
        assertEquals("permissions.onAdded", emitted.single().first)
        assertEquals(listOf(ext.spec.id), refreshed)

        val remove = router.find("permissions.remove")!!
        assertEquals(true, remove.handle(CallContext(granted, sender, "permissions.remove"), org.json.JSONArray().put(
            JSONObject("""{"permissions":["alarms"],"origins":["https://optional.example/*"]}""")
        )))
        val after = repo.get(ext.spec.id)!!
        assertFalse("alarms" in PermissionChecker.effectivePermissions(after))
        assertTrue(after.grantedHostPermissions.isEmpty())
        assertEquals("permissions.onRemoved", emitted.last().first)
        assertEquals(listOf(ext.spec.id, ext.spec.id), refreshed)
    }

    @Test fun requestCannotEscalateUndeclaredPermissions() = runBlocking {
        val ext = TestExt.make(tmp.newFolder("required"))
        val repo = FakeRepo().apply { put(ext) }
        val router = ApiRouter()
        PermissionsApi(repo, EventSink { _, _, _ -> }) { _, _, _ -> true }.register(router)
        val sender = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(ext.spec.id), true)
        val err = try {
            router.find("permissions.request")!!.handle(CallContext(ext, sender, "permissions.request"), org.json.JSONArray().put(
                JSONObject("""{"permissions":["cookies"]}""")
            ))
            null
        } catch (e: ApiException) { e.code }
        assertEquals(ErrorCodes.DENIED_PERMISSION, err)
    }

}
