/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.backup.BackupCategory
import dev.dsh.miuixbrowser.backup.BackupManager
import dev.dsh.miuixbrowser.browser.PasswordEntry
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
    fun allCategoriesSelectedByDefault() {
        assertTrue(BackupCategory.entries.all { it in BackupCategory.DEFAULT_SELECTION })
    }
}
