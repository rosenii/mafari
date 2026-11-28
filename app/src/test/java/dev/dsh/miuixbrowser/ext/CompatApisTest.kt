/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompatApisTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sender = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(TestExt.ID), true, contextId = "popup")
    private fun ctx(ext: InstalledExtension, api: String) = CallContext(ext, sender, api)

    @Test fun commandsReadManifestShortcuts() = runBlocking {
        val ext = TestExt.make(
            tmp.newFolder("commands"),
            manifest = """{"manifest_version":3,"name":"Demo","version":"1","commands":{
                "toggle": {"description":"开关","suggested_key":{"default":"Ctrl+Shift+Y"}},
                "plain": {"description":"无快捷键"}
            }}""",
        )
        val router = ApiRouter()
        CommandsApi().register(router)
        val arr = router.find("commands.getAll")!!.handle(ctx(ext, "commands.getAll"), JSONArray()) as JSONArray
        assertEquals(2, arr.length())
        val byName = (0 until arr.length()).associate { arr.getJSONObject(it).getString("name") to arr.getJSONObject(it) }
        assertEquals("Ctrl+Shift+Y", byName.getValue("toggle").getString("shortcut"))
        assertEquals("", byName.getValue("plain").getString("shortcut"))
    }

    @Test fun managementReadsSelfAndInstalled() = runBlocking {
        val repo = FakeRepo()
        val ext = TestExt.make(
            tmp.newFolder("management"),
            manifest = """{"manifest_version":3,"name":"Demo","version":"1.2.3","description":"d","permissions":["management"]}""",
            granted = setOf("management"),
        )
        repo.put(ext)
        val router = ApiRouter()
        ManagementApi(repo).register(router)
        val self = router.find("management.getSelf")!!.handle(ctx(ext, "management.getSelf"), JSONArray()) as JSONObject
        assertEquals(TestExt.ID, self.getString("id"))
        assertEquals("Demo", self.getString("name"))
        assertEquals("1.2.3", self.getString("version"))
        assertTrue(self.getBoolean("enabled"))
        val all = router.find("management.getAll")!!.handle(ctx(ext, "management.getAll"), JSONArray()) as JSONArray
        assertEquals(1, all.length())
    }
}
