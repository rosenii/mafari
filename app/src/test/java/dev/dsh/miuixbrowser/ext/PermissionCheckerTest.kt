/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PermissionCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ext(
        granted: Set<String> = setOf("storage"),
        enabled: Boolean = true,
    ) = TestExt.make(File(tmp.root, "e${System.nanoTime()}"), granted = granted, enabled = enabled)

    private fun cs(origin: String) = Sender(SenderKind.CONTENT_SCRIPT, origin, true)
    private fun page(id: String = TestExt.ID) = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(id), true)

    private fun codeOf(e: InstalledExtension, api: String, s: Sender): String? = try {
        PermissionChecker.require(e, api, s)
        null
    } catch (ex: ApiException) {
        ex.code
    }

    @Test fun contentScriptOnMatchingOriginMayUseStorage() {
        assertNull(codeOf(ext(), "storage.local.get", cs("https://a.com")))
        assertNull(codeOf(ext(), "runtime.sendMessage", cs("https://a.com")))
    }

    @Test fun originMatchingIgnoresPathButNotHost() {
        val e = ext()
        // 路径受限的 pattern（https://*.b.com/foo/*）在 origin 级别仍然成立，路径由注入时和脚本运行时收窄
        assertNull(codeOf(e, "storage.local.get", cs("https://x.b.com")))
        assertEquals(ErrorCodes.DENIED_ORIGIN, codeOf(e, "storage.local.get", cs("https://evil.com")))
        assertEquals(ErrorCodes.DENIED_ORIGIN, codeOf(e, "storage.local.get", cs("https://a.com.evil.com")))
        assertEquals(ErrorCodes.DENIED_ORIGIN, codeOf(e, "storage.local.get", cs("null")))
        assertEquals(ErrorCodes.DENIED_ORIGIN, codeOf(e, "storage.local.get", cs("https://a.com/path")))
        // 用户授予的 host 权限同样算（scripting 注入的脚本）
        assertNull(codeOf(e, "storage.local.get", cs("https://granted.com")))
    }

    @Test fun contentScriptsCannotCallPrivilegedApis() {
        val e = ext(granted = setOf("storage", "tabs", "scripting"))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(e, "tabs.query", cs("https://a.com")))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(e, "scripting.executeScript", cs("https://a.com")))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(e, "storage.session.get", cs("https://a.com")))
        assertNull(codeOf(e, "tabs.query", page()))
    }

    @Test fun apiPermissionMustBeGrantedAndDeclared() {
        val e = ext(granted = emptySet())
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(e, "storage.local.get", page()))
        // 已授予但 manifest 没声明的权限无效
        val undeclared = ext(granted = setOf("storage", "cookies"))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(undeclared, "cookies.get", page()))
        // 无需权限的 API 直接放行
        assertNull(codeOf(e, "runtime.getURL", page()))
    }

    @Test fun extensionPageMustComeFromOwnOrigin() {
        val e = ext()
        assertNull(codeOf(e, "storage.local.get", page()))
        assertEquals(ErrorCodes.DENIED_ORIGIN, codeOf(e, "storage.local.get", page("other@x.org")))
        assertEquals(ErrorCodes.DENIED_ORIGIN, codeOf(e, "storage.local.get", Sender(SenderKind.EXTENSION_PAGE, "https://a.com", true)))
    }

    @Test fun disabledAndUnknown() {
        assertEquals(ErrorCodes.DISABLED, codeOf(ext(enabled = false), "runtime.getURL", page()))
        assertEquals(ErrorCodes.UNKNOWN_API, codeOf(ext(), "madeUp.thing", page()))
        assertEquals(ErrorCodes.UNKNOWN_API, codeOf(ext(), "storagefoo.get", page()))
    }

    @Test fun tabDetailsAndHostAccess() {
        val withTabs = ext(granted = setOf("tabs"))
        assertTrue(PermissionChecker.canSeeTabDetails(withTabs, "https://zzz.com/"))
        val noTabs = ext(granted = setOf("storage"))
        assertFalse(PermissionChecker.canSeeTabDetails(noTabs, "https://zzz.com/"))
        assertTrue(PermissionChecker.canSeeTabDetails(noTabs, "https://granted.com/p"))
        assertFalse(PermissionChecker.canSeeTabDetails(noTabs, null))
    }

    @Test fun clipboardApisRequireTheirSpecificPermissionsAndExtensionPages() {
        val manifest = """{"manifest_version":3,"name":"Clip","version":"1","permissions":["clipboardRead","clipboardWrite"],"host_permissions":["https://granted.com/*"],"content_scripts":[{"matches":["https://a.com/*"],"js":["c.js"]}]}"""
        val granted = TestExt.make(File(tmp.root, "clip-granted"), manifest = manifest, granted = setOf("clipboardRead", "clipboardWrite"))
        assertNull(codeOf(granted, "clipboard.readText", page()))
        assertNull(codeOf(granted, "clipboard.writeText", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "clipboard.readText", cs("https://a.com")))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "clipboard.writeText", cs("https://a.com")))

        val readOnly = TestExt.make(File(tmp.root, "clip-read"), manifest = manifest, granted = setOf("clipboardRead"))
        assertNull(codeOf(readOnly, "clipboard.readText", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(readOnly, "clipboard.writeText", page()))
        val writeOnly = TestExt.make(File(tmp.root, "clip-write"), manifest = manifest, granted = setOf("clipboardWrite"))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(writeOnly, "clipboard.readText", page()))
        assertNull(codeOf(writeOnly, "clipboard.writeText", page()))
    }

    @Test fun notificationsRequirePermissionAndExtensionPageContext() {
        val manifest = """{"manifest_version":3,"name":"Notify","version":"1","permissions":["notifications"],"host_permissions":["https://granted.com/*"],"content_scripts":[{"matches":["https://a.com/*"],"js":["c.js"]}]}"""
        val granted = TestExt.make(File(tmp.root, "notify-granted"), manifest = manifest, granted = setOf("notifications"))
        assertNull(codeOf(granted, "notifications.create", page()))
        assertNull(codeOf(granted, "notifications.getAll", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "notifications.create", cs("https://a.com")))
        val missing = TestExt.make(File(tmp.root, "notify-missing"), manifest = manifest, granted = emptySet())
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(missing, "notifications.create", page()))
    }

    @Test fun bookmarksApiRequiresPermissionAndExtensionPageContext() {
        val manifest = """{"manifest_version":3,"name":"Marks","version":"1","permissions":["bookmarks"],"host_permissions":["https://granted.com/*"],"content_scripts":[{"matches":["https://a.com/*"],"js":["c.js"]}]}"""
        val granted = TestExt.make(File(tmp.root, "bookmarks-granted"), manifest = manifest, granted = setOf("bookmarks"))
        assertNull(codeOf(granted, "bookmarks.getTree", page()))
        assertNull(codeOf(granted, "bookmarks.create", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "bookmarks.getTree", cs("https://a.com")))
        val missing = TestExt.make(File(tmp.root, "bookmarks-missing"), manifest = manifest, granted = emptySet())
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(missing, "bookmarks.getTree", page()))
    }

    @Test fun downloadsApiRequiresPermissionAndExtensionPageContext() {
        val manifest = """{"manifest_version":3,"name":"Downloads","version":"1","permissions":["downloads"],"content_scripts":[{"matches":["https://a.com/*"],"js":["c.js"]}]}"""
        val granted = TestExt.make(File(tmp.root, "downloads-granted"), manifest = manifest, granted = setOf("downloads"))
        assertNull(codeOf(granted, "downloads.download", page()))
        assertNull(codeOf(granted, "downloads.search", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "downloads.search", cs("https://a.com")))
        val missing = TestExt.make(File(tmp.root, "downloads-missing"), manifest = manifest, granted = emptySet())
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(missing, "downloads.search", page()))
    }

    @Test fun cookiesApiRequiresPermissionAndMatchingHostAccess() {
        val manifest = """{"manifest_version":3,"name":"Cookies","version":"1","permissions":["cookies"],"host_permissions":["https://granted.com/*"],"content_scripts":[{"matches":["https://a.com/*"],"js":["c.js"]}]}"""
        val granted = TestExt.make(File(tmp.root, "cookies-granted"), manifest = manifest, granted = setOf("cookies"))
        assertNull(codeOf(granted, "cookies.get", page()))
        assertNull(codeOf(granted, "cookies.set", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "cookies.get", cs("https://a.com")))
        val missing = TestExt.make(File(tmp.root, "cookies-missing"), manifest = manifest, granted = emptySet())
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(missing, "cookies.get", page()))
    }

    @Test fun originsAreDistinctAndHostSafe() {
        val a = ExtensionOrigins.originFor("a@x.org")
        val b = ExtensionOrigins.originFor("{uuid}")
        assertNotEquals(a, b)
        assertTrue(Regex("^https://[0-9a-f]{32}\\.ext\\.mafari\\.internal$").matches(a))
        assertEquals("$a/p/q.html", ExtensionOrigins.urlFor("a@x.org", "/p/q.html"))
    }
    @Test fun mafariNativeScriptsApiRequiresExplicitCustomPermissionAndExtensionPage() {
        val manifest = """{"manifest_version":3,"name":"Native scripts","version":"1","permissions":["mafariScripts"],"host_permissions":["https://granted.com/*"]}"""
        val granted = TestExt.make(File(tmp.root, "native-scripts-granted"), manifest = manifest, granted = setOf("mafariScripts"))
        assertNull(codeOf(granted, "mafari.userscripts.getAll", page()))
        assertNull(codeOf(granted, "mafari.userscripts.install", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "mafari.userscripts.getAll", cs("https://a.com")))

        val missing = TestExt.make(File(tmp.root, "native-scripts-missing"), manifest = manifest, granted = emptySet())
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(missing, "mafari.userscripts.getAll", page()))
    }

    @Test fun offscreenApiRequiresMv3PermissionAndExtensionPage() {
        val manifest = """{"manifest_version":3,"name":"Offscreen","version":"1","permissions":["offscreen"]}"""
        val granted = TestExt.make(File(tmp.root, "offscreen-granted"), manifest = manifest, granted = setOf("offscreen"))
        assertNull(codeOf(granted, "offscreen.createDocument", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "offscreen.createDocument", cs("https://a.com")))
        val missing = TestExt.make(File(tmp.root, "offscreen-missing"), manifest = manifest, granted = emptySet())
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(missing, "offscreen.createDocument", page()))
    }

    @Test fun webRequestRequiresPermissionAndBlockingIsSeparatelyGranted() {
        val manifest = """{"manifest_version":3,"name":"Request","version":"1","permissions":["webRequest","webRequestBlocking"],"host_permissions":["https://granted.com/*"]}"""
        val granted = TestExt.make(File(tmp.root, "web-request-granted"), manifest = manifest, granted = setOf("webRequest", "webRequestBlocking"))
        assertNull(codeOf(granted, "webRequest.addListener", page()))
        assertTrue(PermissionChecker.effectivePermissions(granted).contains("webRequestBlocking"))

        val observeOnly = TestExt.make(File(tmp.root, "web-request-observe"), manifest = manifest, granted = setOf("webRequest"))
        assertNull(codeOf(observeOnly, "webRequest.addListener", page()))
        assertFalse(PermissionChecker.effectivePermissions(observeOnly).contains("webRequestBlocking"))
    }

    @Test fun declarativeNetRequestAndUserScriptsRequireTheirDeclaredPermissions() {
        val manifest = """{"manifest_version":3,"name":"DNR and userScripts","version":"1","permissions":["declarativeNetRequest","userScripts"],"host_permissions":["https://granted.com/*"]}"""
        val granted = TestExt.make(File(tmp.root, "dnr-userscripts-granted"), manifest = manifest, granted = setOf("declarativeNetRequest", "userScripts"))
        assertNull(codeOf(granted, "declarativeNetRequest.getDynamicRules", page()))
        assertNull(codeOf(granted, "userScripts.register", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(granted, "userScripts.register", cs("https://a.com")))

        val missing = TestExt.make(File(tmp.root, "dnr-userscripts-missing"), manifest = manifest, granted = emptySet())
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(missing, "declarativeNetRequest.getDynamicRules", page()))
        assertEquals(ErrorCodes.DENIED_PERMISSION, codeOf(missing, "userScripts.getScripts", page()))
    }

}
