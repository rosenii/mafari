package dev.dsh.miuixbrowser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM（用 org.json 真实实现）：Firefox 同步 passwords 记录 ↔ PasswordEntry。 */
class PasswordSyncFormatTest {

    private val form = PasswordEntry(
        guid = "AbCdEfGhIjKl", origin = "https://a.com", formOrigin = "https://a.com", username = "u", password = "p",
        usernameField = "user", passwordField = "pass", note = "本地备注",
        timeCreated = 1L, timeLastUsed = 2L, timePasswordChanged = 3L, timesUsed = 4,
    )

    @Test
    fun formLoginRoundTrip() {
        val json = PasswordSyncFormat.toRecord(form)
        val o = JSONObject(json)
        assertEquals("https://a.com", o.getString("hostname"))
        assertEquals("https://a.com", o.getString("formSubmitURL"))
        assertTrue(o.isNull("httpRealm"))
        assertTrue(!o.has("note")) // 备注不进同步记录
        assertEquals(form.copy(note = ""), PasswordSyncFormat.fromRecord(json))
    }

    @Test
    fun wildcardFormAndHttpAuth() {
        val any = form.copy(formOrigin = null)
        assertEquals("", JSONObject(PasswordSyncFormat.toRecord(any)).getString("formSubmitURL"))
        assertNull(PasswordSyncFormat.fromRecord(PasswordSyncFormat.toRecord(any))!!.formOrigin)

        val basic = form.copy(formOrigin = null, httpRealm = "Router")
        val o = JSONObject(PasswordSyncFormat.toRecord(basic))
        assertTrue(o.isNull("formSubmitURL"))
        assertEquals("Router", o.getString("httpRealm"))
        assertEquals("Router", PasswordSyncFormat.fromRecord(o.toString())!!.httpRealm)
    }

    @Test
    fun deletedAndInvalid() {
        val d = PasswordSyncFormat.deletedRecord("xyz12345")
        assertNull(PasswordSyncFormat.fromRecord(d))
        assertEquals("xyz12345", PasswordSyncFormat.isDeleted(d))
        assertNull(PasswordSyncFormat.fromRecord("not json"))
        assertNull(PasswordSyncFormat.fromRecord("""{"id":"a","hostname":"","password":"p"}"""))
        assertNull(PasswordSyncFormat.fromRecord("""{"id":"a","hostname":"https://a.com","password":""}"""))
    }
    // ---- Rust logins 镜像库的登录 ↔ PasswordEntry ----

    @Test
    fun loginMappingRoundTrip() {
        val l = PasswordSyncFormat.toLogin(form)
        assertEquals("https://a.com", l.formActionOrigin)
        assertNull(l.httpRealm)
        assertEquals(form.copy(note = ""), PasswordSyncFormat.fromLogin(l))
    }

    @Test
    fun loginMappingWildcardAndHttpAuth() {
        // 「任意表单」：formActionOrigin 空串 ↔ formOrigin null
        val any = PasswordSyncFormat.toLogin(form.copy(formOrigin = null))
        assertEquals("", any.formActionOrigin)
        assertNull(PasswordSyncFormat.fromLogin(any)!!.formOrigin)

        // HTTP 认证：formActionOrigin 为 null，只带 realm
        val basic = PasswordSyncFormat.toLogin(form.copy(formOrigin = null, httpRealm = "Router"))
        assertNull(basic.formActionOrigin)
        assertEquals("Router", basic.httpRealm)
        val back = PasswordSyncFormat.fromLogin(basic)!!
        assertEquals("Router", back.httpRealm)
        assertNull(back.formOrigin)
    }

    @Test
    fun loginMappingRejectsBadRecords() {
        val ok = PasswordSyncFormat.toLogin(form)
        assertNull(PasswordSyncFormat.fromLogin(PLogin("", ok.origin, ok.formActionOrigin, null, "u", "p", "", "", 0, 0, 0, 0)))
        assertNull(PasswordSyncFormat.fromLogin(PLogin("id", ok.origin, ok.formActionOrigin, null, "u", "", "", "", 0, 0, 0, 0)))
        assertNull(PasswordSyncFormat.fromLogin(PLogin("id", "", ok.formActionOrigin, null, "u", "p", "", "", 0, 0, 0, 0)))
    }
}
