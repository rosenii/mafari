package dev.dsh.miuixbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 纯 JVM：各家密码 CSV 的识别与解析、来源规整、Firefox 同步记录格式。 */
class PasswordCsvTest {

    @Test
    fun chrome() {
        val r = PasswordCsv.parse(
            "name,url,username,password,note\n" +
                "example.com,https://example.com/login,alice,p@ss,\"line1\nline2\"\n" +
                "dup,https://example.com/other,alice,p@ss,\n" +
                "bad,android://abc@com.app/,bob,x,\n" +
                "nopw,https://a.com,bob,,\n",
        )
        assertEquals("Chrome / Edge", r.format)
        assertEquals(1, r.entries.size) // 第二行与第一行（来源+用户名+密码）重复
        val e = r.entries[0]
        assertEquals("https://example.com", e.origin)
        assertEquals("alice", e.username)
        assertEquals("p@ss", e.password)
        assertEquals("line1\nline2", e.note)
        assertEquals(3, r.skipped)
        assertEquals(4, r.totalRows)
    }

    @Test
    fun firefox() {
        val r = PasswordCsv.parse(
            "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\r\n" +
                "\"https://a.com\",\"u\",\"pw\",\"\",\"https://a.com\",\"{1b2c3d4e-0000-4000-8000-000000000001}\",\"1600000000000\",\"1700000000000\",\"1650000000000\"\r\n" +
                "\"http://router.lan:8080\",\"admin\",\"pw2\",\"Router\",\"\",\"AbCdEfGhIjKl\",\"1600000000\",\"\",\"\"\r\n",
        )
        assertEquals("Firefox", r.format)
        assertEquals(2, r.entries.size)
        val a = r.entries[0]
        assertEquals("https://a.com", a.formOrigin)
        assertEquals("{1b2c3d4e-0000-4000-8000-000000000001}", a.guid)
        assertEquals(1_650_000_000_000L, a.timePasswordChanged)
        assertEquals(1_700_000_000_000L, a.timeLastUsed)
        val b = r.entries[1]
        assertEquals("http://router.lan:8080", b.origin)
        assertEquals("Router", b.httpRealm)
        assertNull(b.formOrigin)
        assertEquals(1_600_000_000_000L, b.timeCreated) // 秒 → 毫秒
        assertEquals(b.timeCreated, b.timePasswordChanged)
    }

    @Test
    fun bitwardenSkipsNonLogin() {
        val r = PasswordCsv.parse(
            "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
                ",0,login,Site,n,,0,https://s.com,me,secret,\n" +
                ",0,note,Memo,text,,0,,,,\n",
        )
        assertEquals("Bitwarden", r.format)
        assertEquals(1, r.entries.size)
        assertEquals("me", r.entries[0].username)
        assertEquals(1, r.skipped)
    }

    @Test
    fun safariOnePasswordLastPassKeePassDashlane() {
        assertEquals("Safari / iCloud 钥匙串", PasswordCsv.parse("Title,URL,Username,Password,Notes,OTPAuth\nA,https://a.com,u,p,,\n").format)
        assertEquals("1Password", PasswordCsv.parse("Title,Url,Username,Password,Notes,Type\nA,https://a.com,u,p,,Login\n").format)
        val lp = PasswordCsv.parse("url,username,password,totp,extra,name,grouping,fav\nhttps://a.com,u,p,,n,A,,0\n")
        assertEquals("LastPass", lp.format)
        assertEquals("n", lp.entries[0].note)
        val kp = PasswordCsv.parse("\"Account\",\"Login Name\",\"Password\",\"Web Site\",\"Comments\"\n\"A\",\"u\",\"p\",\"a.com\",\"c\"\n")
        assertEquals("KeePass", kp.format)
        assertEquals("u", kp.entries[0].username)
        assertEquals("https://a.com", kp.entries[0].origin)
        assertEquals("Dashlane", PasswordCsv.parse("title,website,username,password,note\nA,a.com,u,p,\n").format)
    }

    @Test
    fun bomSemicolonAndQuotes() {
        val r = PasswordCsv.parse("﻿url;username;password\nhttps://a.com;u;\"a;b\"\"c\"\n")
        assertEquals("通用 CSV", r.format)
        assertEquals("a;b\"c", r.entries[0].password)
    }

    @Test
    fun notAPasswordFile() {
        try { PasswordCsv.parse("a,b,c\n1,2,3\n"); fail() } catch (e: PasswordCsv.FormatException) { /* ok */ }
        try { PasswordCsv.parse(""); fail() } catch (e: PasswordCsv.FormatException) { /* ok */ }
    }

    @Test
    fun originNormalisation() {
        assertEquals("https://example.com", PasswordCsv.originOf("Example.COM"))
        assertEquals("https://example.com", PasswordCsv.originOf("https://example.com:443/a?b#c"))
        assertEquals("http://example.com:8080", PasswordCsv.originOf("http://user:pw@example.com:8080/x"))
        assertEquals("https://[::1]:9000", PasswordCsv.originOf("https://[::1]:9000/"))
        assertEquals("https://example.com:8443", PasswordCsv.originOf("example.com:8443/login"))
        assertNull(PasswordCsv.originOf("android://hash@com.app/"))
        assertNull(PasswordCsv.originOf("mailto:a@b.com"))
        assertNull(PasswordCsv.originOf("chrome://settings"))
        assertNull(PasswordCsv.originOf(""))
        assertNull(PasswordCsv.originOf("https://exa mple.com"))
        assertNull(PasswordCsv.originOf("https://a.com:99999"))
    }

    @Test
    fun decodeUtf16() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "url,password\n".toByteArray(Charsets.UTF_16LE)
        assertEquals("url,password\n", PasswordCsv.decode(bytes))
        assertTrue(PasswordCsv.decode("é".toByteArray()).startsWith("é"))
    }
}
