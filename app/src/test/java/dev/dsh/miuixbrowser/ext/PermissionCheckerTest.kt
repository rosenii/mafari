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

    @Test fun originsAreDistinctAndHostSafe() {
        val a = ExtensionOrigins.originFor("a@x.org")
        val b = ExtensionOrigins.originFor("{uuid}")
        assertNotEquals(a, b)
        assertTrue(Regex("^https://[0-9a-f]{32}\\.ext\\.mafari\\.internal$").matches(a))
        assertEquals("$a/p/q.html", ExtensionOrigins.urlFor("a@x.org", "/p/q.html"))
    }
}
