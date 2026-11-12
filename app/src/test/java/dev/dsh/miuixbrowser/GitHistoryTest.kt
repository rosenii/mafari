package dev.dsh.miuixbrowser

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM（JGit 本身可在 JVM 跑）：裁剪历史只留最近 N 个提交，且内容不变。 */
class GitHistoryTest {

    private fun commits(git: Git, dir: File, count: Int) {
        val who = PersonIdent("t", "t@example.com")
        for (i in 1..count) {
            File(dir, "f").writeText("v$i")
            git.add().addFilepattern("f").call()
            git.commit().setMessage("c$i").setAuthor(who).setCommitter(who).call()
        }
    }

    @Test
    fun trimsToLatestN() {
        val dir = Files.createTempDirectory("mafari-git-test").toFile()
        try {
            Git.init().setDirectory(dir).call().use { git ->
                commits(git, dir, 13)
                val treeBefore = git.repository.resolve("HEAD^{tree}")

                assertTrue(GitHistory.keepLatest(git.repository, 10))

                val log = git.log().call().toList()
                assertEquals(10, log.size)
                assertEquals("c13", log.first().shortMessage)
                assertEquals("c4", log.last().shortMessage)
                assertEquals("最老的一个成为根提交", 0, log.last().parentCount)
                assertEquals("内容不变", treeBefore, git.repository.resolve("HEAD^{tree}"))
                assertEquals("v13", File(dir, "f").readText())

                assertFalse("已经只有 10 个，不再重写", GitHistory.keepLatest(git.repository, 10))
                assertEquals(10, git.log().call().count())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun leavesShortHistoryAlone() {
        val dir = Files.createTempDirectory("mafari-git-test").toFile()
        try {
            Git.init().setDirectory(dir).call().use { git ->
                commits(git, dir, 3)
                val head = git.repository.resolve("HEAD")
                assertFalse(GitHistory.keepLatest(git.repository, 10))
                assertEquals(head, git.repository.resolve("HEAD"))
                assertEquals(3, git.log().call().count())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
