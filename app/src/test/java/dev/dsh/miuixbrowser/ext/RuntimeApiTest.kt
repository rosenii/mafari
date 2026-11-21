package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeApiTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun getContextsIsExposedForManifestV3AndDelegatesFilter() = runBlocking {
        val ext = TestExt.make(tmp.newFolder("mv3"))
        val broker = MessageBroker()
        val router = ApiRouter()
        RuntimeApi(broker).register(router)
        val bg = object : MessageEndpoint {
            override val contextId = "background"
            override val extId = TestExt.ID
            override val kind = SenderKind.EXTENSION_PAGE
            override val contextType = "BACKGROUND"
            override fun deliver(cid: String, message: Any?, sender: JSONObject) = true
        }
        broker.register(bg)
        val handler = router.find("runtime.getContexts")!!
        val result = handler.handle(CallContext(ext, Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, contextId = "popup"), "runtime.getContexts"), JSONArray("[{}]")) as JSONArray
        assertEquals(1, result.length())
        assertEquals("background", result.getJSONObject(0).getString("contextId"))
    }

    @Test fun getContextsRejectsUnknownFilterFields() = runBlocking {
        val ext = TestExt.make(tmp.newFolder("unknown-filter"))
        val router = ApiRouter()
        RuntimeApi(MessageBroker()).register(router)
        try {
            router.find("runtime.getContexts")!!.handle(
                CallContext(ext, Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, contextId = "popup"), "runtime.getContexts"),
                JSONArray("[{\"unknownFilter\":true}]")
            )
            fail("expected BAD_REQUEST")
        } catch (e: ApiException) {
            assertEquals(ErrorCodes.BAD_REQUEST, e.code)
        }
    }

    @Test fun mv3BackgroundPageReferenceIsRejected() = runBlocking {
        val ext = TestExt.make(tmp.newFolder("mv3-background"))
        val router = ApiRouter()
        RuntimeApi(MessageBroker()).register(router)
        try {
            router.find("runtime.getBackgroundPage")!!.handle(
                CallContext(ext, Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, contextId = "popup"), "runtime.getBackgroundPage"),
                JSONArray()
            )
            fail("expected UNSUPPORTED")
        } catch (e: ApiException) {
            assertEquals(ErrorCodes.UNSUPPORTED, e.code)
        }
    }

    @Test fun getContextsIsRejectedForManifestV2() = runBlocking {
        val manifest = """{"manifest_version":2,"name":"Demo","version":"1","permissions":["storage"]}"""
        val ext = TestExt.make(tmp.newFolder("mv2"), manifest = manifest)
        val router = ApiRouter()
        RuntimeApi(MessageBroker()).register(router)
        val handler = router.find("runtime.getContexts")!!
        try {
            handler.handle(CallContext(ext, Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, contextId = "popup"), "runtime.getContexts"), JSONArray("[{}]"))
            fail("expected UNSUPPORTED")
        } catch (e: ApiException) {
            assertEquals(ErrorCodes.UNSUPPORTED, e.code)
        }
    }


}
