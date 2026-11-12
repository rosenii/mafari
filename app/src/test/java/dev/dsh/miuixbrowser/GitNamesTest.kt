package dev.dsh.miuixbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：Git 同步的地址 / 分支校验与错误归类。 */
class GitNamesTest {

    @Test
    fun repoUrl() {
        assertEquals("https://github.com/me/mafari-sync.git", GitNames.normalizeRepoUrl(" https://github.com/me/mafari-sync.git/ "))
        assertEquals("http://192.168.1.2:3000/me/sync", GitNames.normalizeRepoUrl("http://192.168.1.2:3000/me/sync"))
        assertNull("不能把令牌写进地址", GitNames.normalizeRepoUrl("https://me:token@github.com/me/sync.git"))
        assertNull(GitNames.normalizeRepoUrl("git@github.com:me/sync.git"))
        assertNull(GitNames.normalizeRepoUrl("ssh://github.com/me/sync.git"))
        assertNull("缺少仓库路径", GitNames.normalizeRepoUrl("https://github.com"))
        assertNull(GitNames.normalizeRepoUrl(""))
    }

    @Test
    fun branch() {
        assertEquals("", GitNames.normalizeBranch("  "))
        assertEquals("main", GitNames.normalizeBranch("main"))
        assertEquals("backup/mobile-1.x", GitNames.normalizeBranch("backup/mobile-1.x"))
        for (bad in listOf("-x", "/x", "x/", "a..b", "a//b", "x.lock", "has space", "a:b", "a~1", "a\nb")) {
            assertNull(bad, GitNames.normalizeBranch(bad))
        }
    }

    @Test
    fun errorClassification() {
        assertEquals("用户名或令牌错误，或没有访问权限", GitNames.describeError("推送备份", listOf("https://h/x.git: not authorized")))
        assertEquals("仓库不存在，或令牌无权访问该仓库", GitNames.describeError("连接仓库", listOf("https://h/x.git: not found")))
        assertEquals("无法解析服务器地址", GitNames.describeError("连接仓库", listOf("Cannot connect", "java.net.UnknownHostException: h")))
        assertEquals("连接超时", GitNames.describeError("连接仓库", listOf("Read timed out")))
        assertTrue(GitNames.describeError("拉取备份", listOf("boom")).startsWith("拉取备份失败"))
        assertTrue(GitNames.commitMessage(0L).startsWith("Mafari backup "))
    }

    @Test
    fun sshUrl() {
        assertEquals("git@github.com:me/sync.git", GitNames.normalizeSshUrl(" git@github.com:me/sync.git "))
        assertEquals("git@github.com:me/sync.git", GitNames.normalizeSshUrl("github.com:me/sync.git"))
        assertEquals("ssh://git@host.example:2222/me/sync.git", GitNames.normalizeSshUrl("ssh://git@host.example:2222/me/sync.git"))
        assertEquals("ssh://git@host.example/me/sync.git", GitNames.normalizeSshUrl("ssh://host.example/me/sync.git"))
        assertNull("口令不能写进地址", GitNames.normalizeSshUrl("ssh://git:pw@host.example/me/sync.git"))
        assertNull(GitNames.normalizeSshUrl("https://github.com/me/sync.git"))
        assertNull(GitNames.normalizeSshUrl("github.com"))
        assertNull(GitNames.normalizeSshUrl("git@github.com:"))
        assertNull(GitNames.normalizeSshUrl("git@github.com:me/a b.git"))
        assertEquals("git@h:me/r.git", GitNames.normalizeAddress("git@h:me/r.git", true))
        assertNull(GitNames.normalizeAddress("git@h:me/r.git", false))
    }

    @Test
    fun privateKey() {
        val body = "-----BEGIN OPENSSH PRIVATE KEY-----\r\nb3BlbnNzaC1rZXktdjEAAAAA\r\n-----END OPENSSH PRIVATE KEY-----"
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n",
            GitNames.normalizePrivateKey("  $body  \n"),
        )
        assertTrue(GitNames.normalizePrivateKey("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----") != null)
        assertNull("公钥不是私钥", GitNames.normalizePrivateKey("ssh-ed25519 AAAAC3Nza... me@x"))
        assertNull("PuTTY ppk 暂不支持", GitNames.normalizePrivateKey("PuTTY-User-Key-File-3: ssh-ed25519\nPrivate-Lines: 1"))
        assertNull(GitNames.normalizePrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----\nabc"))
        assertNull(GitNames.normalizePrivateKey(""))
    }

    @Test
    fun sshErrorClassification() {
        assertEquals(
            "Deploy key 被拒绝：请确认对应公钥已添加到该仓库，并勾选了写权限",
            GitNames.describeError("推送备份", listOf("git@github.com:me/x.git: Auth fail"), ssh = true),
        )
        assertEquals(
            "私钥无法解析，或私钥口令不对（暂不支持 PuTTY .ppk 格式）",
            GitNames.describeError("连接仓库", listOf("invalid privatekey: [B@1"), ssh = true),
        )
        assertTrue(GitNames.describeError("连接仓库", listOf("reject HostKey: github.com"), ssh = true).contains("主机密钥"))
        assertEquals("仓库不存在，或该 Deploy key 不属于这个仓库", GitNames.describeError("连接仓库", listOf("repository not found"), ssh = true))
    }
}
