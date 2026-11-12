package dev.dsh.miuixbrowser

import android.content.Context
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Git 同步：把加密备份（.mfbak，格式见 Backup.kt）提交并推送到用户自己的 Git 仓库（走 http / https）。
 *
 *   仓库里只有一个文件 mafari-backup.mfbak，每次备份就是一次提交，Git 历史即版本记录。
 *   用 JGit（纯 Java，无需系统 git）；每次操作都在缓存目录里浅克隆（depth 1），用完即删，
 *   所以本机不留仓库、不会出现本地与远端分叉；远端有人抢先推送时会被拒绝，重试即可。
 *   仓库拿到的永远是密文（口令只在本机输入、不保存）；账号 / 令牌只存本机，密码经 [SecureStore] 加密，
 *   不进备份文件，也不进系统自动备份。
 */

class GitSyncException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class GitConfig(val url: String, val username: String, val token: String, val branch: String)

/** 纯函数部分（地址 / 分支校验、错误归类），放这里便于 JVM 单测。 */
object GitNames {
    const val FILE_NAME = "mafari-backup.mfbak"
    const val DEFAULT_BRANCH = "main"

    private val BRANCH_REGEX = Regex("""[A-Za-z0-9._/-]+""")

    /** 规整仓库地址：只接受 http / https；地址里不许带账号密码（令牌请填在输入框，避免被日志 / 报错带出）。 */
    fun normalizeRepoUrl(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return null
        val rest = s.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank() || '@' in authority) return null
        if (rest.length == authority.length) return null // 只有主机名，没有仓库路径
        return s
    }

    /** 分支名：空串表示「用仓库默认分支」；非法返回 null。 */
    fun normalizeBranch(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return ""
        if (!BRANCH_REGEX.matches(s) || s.startsWith("-") || s.startsWith("/") || s.endsWith("/") ||
            s.endsWith(".lock") || ".." in s || "//" in s
        ) return null
        return s
    }

    fun commitMessage(now: Long = System.currentTimeMillis()): String =
        "Mafari backup " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))

    /** 把 JGit 异常链上的消息归类成人话。 */
    fun describeError(what: String, messages: List<String>): String {
        val all = messages.joinToString(" | ").lowercase(Locale.ROOT)
        return when {
            "not authorized" in all || "authentication" in all || "unauthorized" in all || "forbidden" in all ->
                "用户名或令牌错误，或没有访问权限"
            "not found" in all || "does not appear to be a git repository" in all ->
                "仓库不存在，或令牌无权访问该仓库"
            "unknownhost" in all || "unable to resolve" in all || "no address associated" in all -> "无法解析服务器地址"
            "timed out" in all || "timeout" in all -> "连接超时"
            "ssl" in all || "certificate" in all || "handshake" in all -> "TLS 连接失败（证书无效或协议不匹配）"
            else -> "${what}失败：" + (messages.firstOrNull { it.isNotBlank() } ?: "未知错误")
        }
    }
}

class GitSyncClient(private val context: Context, private val config: GitConfig) {

    private val url = GitNames.normalizeRepoUrl(config.url) ?: throw GitSyncException("仓库地址需以 http:// 或 https:// 开头，且不能包含账号密码")
    private val branch = GitNames.normalizeBranch(config.branch) ?: throw GitSyncException("分支名不合法")
    private val creds = UsernamePasswordCredentialsProvider(config.username.ifBlank { "git" }, config.token)

    init {
        prepare(context)
    }

    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: GitSyncException) {
        throw e
    } catch (e: CryptoException) {
        throw GitSyncException(e.message ?: "备份文件过大", e)
    } catch (e: Exception) {
        val messages = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message ?: it.javaClass.simpleName }.toList()
        throw GitSyncException(GitNames.describeError(what, messages), e)
    }

    /** 远端是否有提交；指定了分支则要求它存在（空仓库除外，首次推送会创建）。返回远端是否为空仓库。 */
    private fun checkRemote(): Boolean = guard("连接仓库") {
        val heads = Git.lsRemoteRepository()
            .setRemote(url).setHeads(true).setCredentialsProvider(creds).setTimeout(TIMEOUT_SEC).call()
        val empty = heads.none { it.name.startsWith("refs/heads/") }
        if (!empty && branch.isNotEmpty() && heads.none { it.name == "refs/heads/$branch" }) {
            throw GitSyncException("仓库里没有分支 $branch")
        }
        empty
    }

    /** 连通性、账号与分支检测。 */
    fun test() {
        checkRemote()
    }

    private fun cloneInto(dir: File): Git {
        val cmd = Git.cloneRepository()
            .setURI(url).setDirectory(dir).setCredentialsProvider(creds).setTimeout(TIMEOUT_SEC)
            .setDepth(1).setNoTags().setCloneSubmodules(false)
        if (branch.isNotEmpty()) cmd.setBranch("refs/heads/$branch").setBranchesToClone(listOf("refs/heads/$branch"))
        return cmd.call()
    }

    private fun <T> withWorkDir(block: (File) -> T): T {
        val dir = File(context.cacheDir, "git-sync-" + System.nanoTime())
        if (!dir.mkdirs()) throw GitSyncException("无法创建临时目录")
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 提交并推送一份备份。 */
    fun upload(bytes: ByteArray) {
        val empty = checkRemote()
        withWorkDir { dir ->
            guard("推送备份") {
                cloneInto(dir).use { git ->
                    File(dir, GitNames.FILE_NAME).writeBytes(bytes)
                    git.add().addFilepattern(GitNames.FILE_NAME).call()
                    val who = PersonIdent("Mafari", "mafari@localhost")
                    git.commit().setMessage(GitNames.commitMessage()).setAuthor(who).setCommitter(who).call()
                    val target = branch.ifEmpty { if (empty) GitNames.DEFAULT_BRANCH else git.repository.branch }
                    val results = git.push()
                        .setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/$target"))
                        .setCredentialsProvider(creds).setTimeout(TIMEOUT_SEC).call()
                    for (r in results) for (u in r.remoteUpdates) {
                        when (u.status) {
                            RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                                throw GitSyncException("远端刚有新提交，请重试一次")
                            RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                                throw GitSyncException("推送被仓库拒绝：" + (u.message ?: "可能是分支受保护或令牌没有写权限"))
                            else -> throw GitSyncException("推送失败：${u.status}" + (u.message?.let { " $it" } ?: ""))
                        }
                    }
                }
            }
        }
    }

    /** 取出仓库里最新的备份文件内容。 */
    fun download(): ByteArray {
        if (checkRemote()) throw GitSyncException("仓库还是空的，没有备份")
        return withWorkDir { dir ->
            guard("拉取备份") {
                cloneInto(dir).use { }
                val file = File(dir, GitNames.FILE_NAME)
                if (!file.isFile) throw GitSyncException("仓库里没有 ${GitNames.FILE_NAME}")
                file.inputStream().use { BackupManager.readAll(it) }
            }
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 45

        @Volatile
        private var ready = false

        /** JGit 在 Android 上读不到 user.home，会在找全局配置时出错：指到应用私有目录。 */
        private fun prepare(context: Context) {
            if (ready) return
            synchronized(this) {
                if (ready) return
                runCatching { FS.DETECTED.setUserHome(context.applicationContext.filesDir) }
                ready = true
            }
        }
    }
}

/** Git 账号的本机存储（不进备份文件）。 */
object GitStore {
    private const val PREFS = "mafari_git"
    private const val K_URL = "url"
    private const val K_USER = "user"
    private const val K_TOKEN = "token"
    private const val K_BRANCH = "branch"
    private const val K_LAST_PUSH = "last_push"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): GitConfig? {
        val p = prefs(context)
        val url = p.getString(K_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val raw = p.getString(K_TOKEN, "").orEmpty()
        val token = if (raw.isEmpty()) "" else SecureStore.read(context.applicationContext, raw, SecureStore.PURPOSE_GIT) ?: return null
        return GitConfig(url, p.getString(K_USER, "").orEmpty(), token, p.getString(K_BRANCH, "").orEmpty())
    }

    fun save(context: Context, config: GitConfig) {
        val app = context.applicationContext
        val sealed = if (config.token.isEmpty()) "" else
            SecureStore.write(app, config.token, SecureStore.PURPOSE_GIT) ?: throw GitSyncException("无法用本机密钥加密保存令牌")
        if (!prefs(app).edit()
                .putString(K_URL, config.url)
                .putString(K_USER, config.username)
                .putString(K_TOKEN, sealed)
                .putString(K_BRANCH, config.branch)
                .commit()
        ) throw GitSyncException("保存失败")
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun lastPush(context: Context): Long = prefs(context).getLong(K_LAST_PUSH, 0L)

    fun markPushed(context: Context) {
        prefs(context).edit().putLong(K_LAST_PUSH, System.currentTimeMillis()).apply()
    }
}
