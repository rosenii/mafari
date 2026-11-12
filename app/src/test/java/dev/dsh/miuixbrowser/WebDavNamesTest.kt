package dev.dsh.miuixbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：WebDAV 地址规整、备份文件名、PROPFIND 响应解析。 */
class WebDavNamesTest {

    @Test
    fun normalizeBaseUrl() {
        assertEquals("https://dav.example.com/remote.php/dav/files/me", WebDavNames.normalizeBaseUrl("  https://dav.example.com/remote.php/dav/files/me/ "))
        assertEquals("http://192.168.1.2:5005", WebDavNames.normalizeBaseUrl("http://192.168.1.2:5005//"))
        assertNull(WebDavNames.normalizeBaseUrl("dav.example.com"))
        assertNull(WebDavNames.normalizeBaseUrl("ftp://dav.example.com"))
        assertNull(WebDavNames.normalizeBaseUrl("https://"))
        assertNull(WebDavNames.normalizeBaseUrl(""))
    }

    @Test
    fun hostLabelStripsSchemeAuthAndSlash() {
        assertEquals("dav.example.com/remote.php/dav", WebDavNames.hostLabel("https://me:pw@dav.example.com/remote.php/dav/"))
        assertEquals("192.168.1.2:5005", WebDavNames.hostLabel("http://192.168.1.2:5005"))
    }

    @Test
    fun backupNameShapeAndUrls() {
        val name = WebDavNames.newBackupName(1_700_000_000_000L)
        assertTrue(name, WebDavNames.isBackupName(name))
        assertFalse(WebDavNames.isBackupName("../mafari-backup-20240101-000000.mfbak"))
        assertFalse(WebDavNames.isBackupName("mafari-backup-20240101-000000.mfbak.tmp"))
        assertFalse(WebDavNames.isBackupName("other.mfbak"))
        assertEquals("https://h/dav/Mafari/", WebDavNames.dirUrl("https://h/dav"))
        assertEquals("https://h/dav/Mafari/$name", WebDavNames.fileUrl("https://h/dav", name))
    }

    @Test
    fun parsesNewestFirstAcrossNamespacePrefixes() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/Mafari/</D:href></D:response>
              <D:response><D:href>/dav/Mafari/mafari-backup-20240101-101010.mfbak</D:href></D:response>
              <D:response><D:href>http://h/dav/Mafari/mafari-backup-20240305-090000.mfbak</D:href></D:response>
              <D:response><D:href>/dav/Mafari/notes.txt</D:href></D:response>
              <D:response><D:href>/dav/Mafari/mafari-backup-20231231-235959.mfbak</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals(
            listOf("mafari-backup-20240305-090000.mfbak", "mafari-backup-20240101-101010.mfbak", "mafari-backup-20231231-235959.mfbak"),
            WebDavNames.parseBackupNames(xml),
        )
        val bare = """<multistatus xmlns="DAV:"><response><href>/Mafari/mafari-backup-20240101-000000.mfbak</href></response></multistatus>"""
        assertEquals(listOf("mafari-backup-20240101-000000.mfbak"), WebDavNames.parseBackupNames(bare))
    }

    @Test
    fun parsesPercentEncodedAndDuplicateHrefs() {
        val xml = """<d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/Mafari/mafari-backup-20240101-000000%2Emfbak</d:href></d:response>
            <d:response><d:href>/Mafari/mafari-backup-20240101-000000.mfbak</d:href></d:response>
            <d:response><d:href>/Mafari/mafari-backup-20240101-000000.mfbak</d:href></d:response>
        </d:multistatus>"""
        assertEquals(listOf("mafari-backup-20240101-000000.mfbak"), WebDavNames.parseBackupNames(xml))
        assertTrue(WebDavNames.parseBackupNames("<nothing/>").isEmpty())
    }
}
