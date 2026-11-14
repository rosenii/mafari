package dev.dsh.miuixbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：备份里的密码 JSON 往返；密码类别默认不勾选。 */
class PasswordBackupTest {

    private val form = PasswordEntry(
        guid = "AbCdEfGhIjKl", origin = "https://a.com", formOrigin = "https://a.com", username = "u", password = "p",
        usernameField = "user", passwordField = "pass", note = "备注",
        timeCreated = 1L, timeLastUsed = 2L, timePasswordChanged = 3L, timesUsed = 4,
    )

    @Test
    fun roundTripKeepsNoteAndKinds() {
        val any = form.copy(guid = "g2", formOrigin = null)
        val basic = form.copy(guid = "g3", formOrigin = null, httpRealm = "Router")
        val back = BackupManager.passwordsFromJson(BackupManager.passwordsToJson(listOf(form, any, basic)))
        assertEquals(listOf(form, any, basic), back)
        assertNull(back[1].formOrigin)
        assertEquals("Router", back[2].httpRealm)
    }

    @Test
    fun badRowsAreSkipped() {
        val json = """[{"id":"x","origin":"ftp://bad","password":"p"},{"id":"y","origin":"https://a.com","password":""},
            {"id":"z","origin":"https://a.com","username":"u","password":"p"}]"""
        val rows = BackupManager.passwordsFromJson(json)
        assertEquals(1, rows.size)
        assertEquals("z", rows[0].guid)
    }

    @Test
    fun passwordsAreOptIn() {
        assertFalse(BackupCategory.PASSWORDS in BackupCategory.DEFAULT_SELECTION)
        assertFalse(BackupCategory.COOKIES in BackupCategory.DEFAULT_SELECTION)
        assertTrue(BackupCategory.BOOKMARKS in BackupCategory.DEFAULT_SELECTION)
    }
}
