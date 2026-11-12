package dev.dsh.miuixbrowser

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.JschConfigSessionFactory
import org.eclipse.jgit.transport.OpenSshConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/*
 * Git 同步：把加密备份（.mfbak，格式见 Backup.kt）提交并推送到用户自己的 Git 仓库。
 *
 *   两种认证：
 *     · http(s) + 访问令牌 / 密码；
 *     · SSH + Deploy key（仓库级私钥，GitHub / GitLab / Gitea 都有；需勾选写权限）。
 *       主机密钥首次连接时记下（known_hosts 在应用私有目录），之后若变化则拒绝连接。
 *   仓库里只有一个文件 mafari-backup.mfbak，每次备份是一次提交；只保留最近 [GitNames.HISTORY_KEEP] 个提交，
 *   超出的最老提交在推送前被裁掉（重写历史并强制推送，所以目标分支需允许强制推送）。
 *   用 JGit（纯 Java，无需系统 git）；每次操作都在缓存目录里克隆目标分支（只取这一个分支、不取标签；
 *   JGit 5.13 的 CloneCommand 没有 setDepth），用完即删，本机不留仓库。
 *   仓库拿到的永远是密文（口令只在本机输入、不保存）；令牌 / 私钥 / 私钥口令经 [SecureStore] 加密只存本机，
 *   不进备份文件，也不进系统自动备份。
 */

class GitSyncException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** [deployKey] 非空表示用 SSH Deploy key（此时 [username] / [token] 不用）。 */
data class GitConfig(
    val url: String,
    val username: String,
    val token: String,
    val branch: String,
    val deployKey: String = "",
    val keyPassphrase: String = "",
) {
    val useSsh: Boolean get() = deployKey.isNotBlank()
}

/** 纯函数部分（地址 / 分支 / 私钥校验、错误归类），放这里便于 JVM 单测。 */
object GitNames {
    const val FILE_NAME = "mafari-backup.mfbak"
    const val DEFAULT_BRANCH = "main"

    /** 仓库里只保留最近多少个提交（= 多少份备份） */
    const val HISTORY_KEEP = 10

    private const val MAX_KEY_CHARS = 16 * 1024
    private val BRANCH_REGEX = Regex("""[A-Za-z0-9._/-]+""")
    private val KEY_REGEX = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]+?-----END [A-Z0-9 ]*PRIVATE KEY-----""")

    /** 规整 http(s) 仓库地址；地址里不许带账号密码（令牌请填在输入框，避免被日志 / 报错带出）。 */
    fun normalizeRepoUrl(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return null
        val rest = s.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank() || '@' in authority) return null
        if (rest.length == authority.length) return null // 只有主机名，没有仓库路径
        return s
    }

    /**
     * 规整 SSH 仓库地址，接受 `git@host:owner/repo.git` 与 `ssh://git@host[:port]/owner/repo.git`；
     * 缺用户名时补 `git@`（GitHub / GitLab / Gitea 都是 git）；用户名里不许带口令。
     */
    fun normalizeSshUrl(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (s.startsWith("ssh://", true)) {
            val rest = s.substring(6)
            val authority = rest.substringBefore('/')
            val path = rest.substringAfter('/', "")
            if (authority.isBlank() || path.isBlank()) return null
            val user = if ('@' in authority) authority.substringBeforeLast('@') else ""
            val host = authority.substringAfterLast('@')
            if (host.isBlank() || ':' in user) return null
            return "ssh://" + (if (user.isEmpty()) "git@" else "$user@") + host + "/" + path
        }
        if ("://" in s) return null
        val host = s.substringBefore(':', "")
        val path = s.substringAfter(':', "")
        if (host.isBlank() || path.isBlank() || '/' in host) return null
        val user = if ('@' in host) host.substringBeforeLast('@') else ""
        val hostname = host.substringAfterLast('@')
        if (hostname.isBlank() || ':' in user) return null
        return (if (user.isEmpty()) "git@" else "$user@") + hostname + ":" + path
    }

    /** 列表 / 摘要里显示的仓库名：去掉协议、账号、末尾斜杠和 .git。 */
    fun repoLabel(url: String): String = url.substringAfter("://").substringAfterLast('@').trimEnd('/').removeSuffix(".git")

    fun normalizeAddress(input: String, ssh: Boolean): String? = if (ssh) normalizeSshUrl(input) else normalizeRepoUrl(input)

    /** 规整粘贴 / 导入的私钥文本（OpenSSH 或 PEM）：统一换行、补结尾换行；不像私钥返回 null。 */
    fun normalizePrivateKey(input: String): String? {
        val s = input.trim().replace("\r\n", "\n").replace('\r', '\n')
        if (s.length > MAX_KEY_CHARS || !KEY_REGEX.matches(s)) return null
        return s + "\n"
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

    /** 把 JGit / JSch 异常链上的消息归类成人话。 */
    fun describeError(what: String, messages: List<String>, ssh: Boolean = false): String {
        val all = messages.joinToString(" | ").lowercase(Locale.ROOT)
        return when {
            "hostkey" in all || "host key" in all || "host identification" in all ->
                "服务器主机密钥与之前记录的不一致（可能被劫持，或服务器换了密钥）；确认无误后清除配置再重试"
            ssh && ("invalid privatekey" in all || "passphrase" in all || "keypair" in all || "end of file" in all && "key" in all) ->
                "私钥无法解析，或私钥口令不对（暂不支持 PuTTY .ppk 格式）"
            ssh && ("auth fail" in all || "auth cancel" in all || "permission denied" in all || "not authorized" in all) ->
                "Deploy key 被拒绝：请确认对应公钥已添加到该仓库，并勾选了写权限"
            "not authorized" in all || "authentication" in all || "unauthorized" in all || "forbidden" in all ->
                "用户名或令牌错误，或没有访问权限"
            "not found" in all || "does not appear to be a git repository" in all || "repository not found" in all ->
                if (ssh) "仓库不存在，或该 Deploy key 不属于这个仓库" else "仓库不存在，或令牌无权访问该仓库"
            "unknownhost" in all || "unable to resolve" in all || "no address associated" in all -> "无法解析服务器地址"
            "timed out" in all || "timeout" in all -> "连接超时"
            !ssh && ("ssl" in all || "certificate" in all || "handshake" in all) -> "TLS 连接失败（证书无效或协议不匹配）"
            else -> "${what}失败：" + (messages.firstOrNull { it.isNotBlank() } ?: "未知错误")
        }
    }
}

/** 历史裁剪：只保留最近 N 个提交。 */
object GitHistory {

    /**
     * 当前分支（HEAD）的提交数超过 [keep] 时，把最新的 [keep] 个提交按原样（同一份树、作者、时间、说明）
     * 重建成一条新的线性历史，最老的一个成为没有父提交的根，并把 HEAD 指向新的最新提交。
     * 返回是否重写了历史（重写后推送必须强制）。
     */
    fun keepLatest(repo: Repository, keep: Int): Boolean {
        require(keep >= 1)
        val recent = Git(repo).use { it.log().setMaxCount(keep + 1).call().toList() } // 新 → 旧
        if (recent.size <= keep) return false

        var parent: org.eclipse.jgit.lib.ObjectId? = null
        repo.newObjectInserter().use { inserter ->
            for (old in recent.take(keep).reversed()) {
                val builder = CommitBuilder()
                builder.setTreeId(old.tree.id)
                builder.author = old.authorIdent
                builder.committer = old.committerIdent
                builder.message = old.fullMessage
                parent?.let { builder.setParentId(it) }
                parent = inserter.insert(builder)
            }
            inserter.flush()
        }
        val update = repo.updateRef(Constants.HEAD)
        update.setNewObjectId(parent)
        update.setForceUpdate(true)
        update.setRefLogMessage("trim history to $keep commits", false)
        when (update.forceUpdate()) {
            RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NEW, RefUpdate.Result.NO_CHANGE -> Unit
            else -> throw IOException("裁剪历史失败")
        }
        return true
    }
}

class GitSyncClient(private val context: Context, private val config: GitConfig) {

    private val ssh = config.useSsh
    private val url = GitNames.normalizeAddress(config.url, ssh)
        ?: throw GitSyncException(
            if (ssh) "SSH 地址形如 git@主机:用户/仓库.git 或 ssh://git@主机/用户/仓库.git"
            else "仓库地址需以 http:// 或 https:// 开头，且不能包含账号密码",
        )
    private val branch = GitNames.normalizeBranch(config.branch) ?: throw GitSyncException("分支名不合法")
    private val privateKey = if (ssh) GitNames.normalizePrivateKey(config.deployKey) ?: throw GitSyncException("私钥格式不对") else null

    private val creds = if (ssh) null else UsernamePasswordCredentialsProvider(config.username.ifBlank { "git" }, config.token)
    private val transportCallback: TransportConfigCallback? = if (!ssh) null else {
        val factory = DeployKeyFactory(
            privateKey!!.toByteArray(Charsets.UTF_8),
            config.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            GitStore.knownHostsFile(context),
        )
        TransportConfigCallback { transport ->
            if (transport is SshTransport) transport.sshSessionFactory = factory
        }
    }

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
        throw GitSyncException(GitNames.describeError(what, messages, ssh), e)
    }

    /** 远端是否有提交；指定了分支则要求它存在（空仓库除外，首次推送会创建）。返回远端是否为空仓库。 */
    private fun checkRemote(): Boolean = guard("连接仓库") {
        val heads = Git.lsRemoteRepository()
            .setRemote(url).setHeads(true)
            .setCredentialsProvider(creds).setTransportConfigCallback(transportCallback).setTimeout(TIMEOUT_SEC).call()
        val empty = heads.none { it.name.startsWith("refs/heads/") }
        if (!empty && branch.isNotEmpty() && heads.none { it.name == "refs/heads/$branch" }) {
            throw GitSyncException("仓库里没有分支 $branch")
        }
        empty
    }

    /** 连通性、账号 / 密钥与分支检测。 */
    fun test() {
        checkRemote()
    }

    private fun cloneInto(dir: File): Git {
        val cmd = Git.cloneRepository()
            .setURI(url).setDirectory(dir)
            .setCredentialsProvider(creds).setTransportConfigCallback(transportCallback).setTimeout(TIMEOUT_SEC)
            .setNoTags().setCloneSubmodules(false)
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

    /** 提交并推送一份备份；仓库里超出 [GitNames.HISTORY_KEEP] 的旧提交一并裁掉。 */
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
                    val trimmed = GitHistory.keepLatest(git.repository, GitNames.HISTORY_KEEP)
                    val spec = RefSpec((if (trimmed) "+" else "") + "HEAD:refs/heads/$target")
                    val results = git.push()
                        .setRemote("origin").setRefSpecs(spec)
                        .setCredentialsProvider(creds).setTransportConfigCallback(transportCallback).setTimeout(TIMEOUT_SEC).call()
                    for (r in results) for (u in r.remoteUpdates) {
                        when (u.status) {
                            RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                                throw GitSyncException("远端刚有新提交，请重试一次")
                            RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                                throw GitSyncException(
                                    "推送被仓库拒绝：" + (u.message ?: "可能是分支受保护或没有写权限") +
                                        if (trimmed) "（裁剪旧历史需要强制推送，请换一个允许强推的分支）" else "",
                                )
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

/** 只用内存里的 Deploy key 登录；主机密钥记在应用私有目录的 known_hosts 里。 */
private class DeployKeyFactory(
    private val key: ByteArray,
    private val passphrase: ByteArray?,
    private val knownHosts: File,
) : JschConfigSessionFactory() {

    override fun configure(hc: OpenSshConfig.Host, session: Session) {
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", "publickey")
        session.setUserInfo(TrustNewHostOnly)
    }

    override fun getJSch(hc: OpenSshConfig.Host, fs: FS): JSch = createDefaultJSch(fs)

    override fun createDefaultJSch(fs: FS): JSch {
        val jsch = JSch()
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.absolutePath)
        jsch.addIdentity("mafari-deploy-key", key, null, passphrase)
        return jsch
    }
}

/**
 * 首次见到的主机：接受并记下（TOFU）；已记录的主机密钥变了：JSch 会问「是否删除旧密钥」，一律拒绝。
 */
private object TrustNewHostOnly : UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?) = false
    override fun promptPassphrase(message: String?) = false
    override fun promptYesNo(message: String?): Boolean {
        val m = message.orEmpty()
        return m.contains("authenticity of host", ignoreCase = true) && !m.contains("delete the old key", ignoreCase = true)
    }

    override fun showMessage(message: String?) {}
}

/** 一条已连接过的仓库。[id] 稳定不变，编辑后仍指向同一条。 */
/** [syncAlso]：自动推送时，除「当前」仓库外是否也一并同步到这个。 */
data class GitProfile(val id: String, val config: GitConfig, val syncAlso: Boolean = false)

/**
 * Git 仓库的本机存储（不进备份文件）：保存所有测试通过的仓库，并记住当前使用的一个。
 * 令牌 / 私钥 / 私钥口令经 [SecureStore] 加密；旧版单配置会在首次读取时自动迁移成列表里的第一项。
 */
object GitStore {
    private const val PREFS = "mafari_git"
    private const val K_URL = "url"          // 旧版单配置
    private const val K_USER = "user"
    private const val K_TOKEN = "token"
    private const val K_BRANCH = "branch"
    private const val K_KEY = "deploy_key"
    private const val K_KEY_PASS = "key_pass"
    private const val K_PROFILES = "profiles"
    private const val K_ACTIVE = "active"
    private const val K_LAST_PUSH = "last_push"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun knownHostsFile(context: Context) = File(context.applicationContext.filesDir, "git_known_hosts")

    private fun open(app: Context, raw: String, purpose: String): String? =
        if (raw.isEmpty()) "" else SecureStore.read(app, raw, purpose)

    private fun seal(app: Context, plain: String, purpose: String): String =
        if (plain.isEmpty()) "" else SecureStore.write(app, plain, purpose) ?: throw GitSyncException("无法用本机密钥加密保存凭据")

    /** 旧版单配置 → 列表（沿用已加密的凭据串，不必解密）。 */
    private fun migrateLegacy(app: Context) {
        val p = prefs(app)
        if (p.contains(K_PROFILES)) return
        val url = p.getString(K_URL, null)?.takeIf { it.isNotBlank() } ?: return
        val id = UUID.randomUUID().toString()
        val arr = JSONArray().put(
            JSONObject()
                .put("id", id)
                .put("url", url)
                .put("user", p.getString(K_USER, "").orEmpty())
                .put("token", p.getString(K_TOKEN, "").orEmpty())
                .put("branch", p.getString(K_BRANCH, "").orEmpty())
                .put("key", p.getString(K_KEY, "").orEmpty())
                .put("keyPass", p.getString(K_KEY_PASS, "").orEmpty()),
        )
        p.edit().putString(K_PROFILES, arr.toString()).putString(K_ACTIVE, id)
            .remove(K_URL).remove(K_USER).remove(K_TOKEN).remove(K_BRANCH).remove(K_KEY).remove(K_KEY_PASS).commit()
    }

    private fun rawArray(app: Context): JSONArray {
        migrateLegacy(app)
        return runCatching { JSONArray(prefs(app).getString(K_PROFILES, "[]")) }.getOrDefault(JSONArray())
    }

    private fun write(app: Context, arr: JSONArray, active: String?) {
        val e = prefs(app).edit().putString(K_PROFILES, arr.toString())
        if (active == null) e.remove(K_ACTIVE) else e.putString(K_ACTIVE, active)
        if (arr.length() == 0) e.remove(K_LAST_PUSH)
        if (!e.commit()) throw GitSyncException("保存失败")
    }

    /** 所有已保存的仓库（无法解密的条目会被略过）。 */
    fun list(context: Context): List<GitProfile> {
        val app = context.applicationContext
        val arr = rawArray(app)
        val out = ArrayList<GitProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val url = o.optString("url")
            if (id.isEmpty() || url.isEmpty()) continue
            val token = open(app, o.optString("token"), SecureStore.PURPOSE_GIT) ?: continue
            val key = open(app, o.optString("key"), SecureStore.PURPOSE_GIT_KEY) ?: continue
            val keyPass = open(app, o.optString("keyPass"), SecureStore.PURPOSE_GIT_KEY_PASS) ?: continue
            out += GitProfile(id, GitConfig(url, o.optString("user"), token, o.optString("branch"), key, keyPass), o.optBoolean("sync", false))
        }
        return out
    }

    /** 当前使用的仓库 id；记录失效时退回第一项。 */
    fun activeId(context: Context): String? {
        val app = context.applicationContext
        val arr = rawArray(app)
        val ids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }
        val saved = prefs(app).getString(K_ACTIVE, null)
        return if (saved != null && saved in ids) saved else ids.firstOrNull()
    }

    /** 当前使用的仓库配置。 */
    fun load(context: Context): GitConfig? {
        val id = activeId(context) ?: return null
        return list(context).firstOrNull { it.id == id }?.config
    }

    /**
     * 保存（新增或覆盖）并设为当前。[id] 为 null 表示新增；若已有同地址、同分支、同认证方式的一项，则覆盖它。
     * 返回这一项的 id。
     */
    fun save(context: Context, config: GitConfig, id: String? = null): String {
        val app = context.applicationContext
        val arr = rawArray(app)
        val obj = JSONObject()
            .put("url", config.url)
            .put("user", config.username)
            .put("token", seal(app, config.token, SecureStore.PURPOSE_GIT))
            .put("branch", config.branch)
            .put("key", seal(app, config.deployKey, SecureStore.PURPOSE_GIT_KEY))
            .put("keyPass", seal(app, config.keyPassphrase, SecureStore.PURPOSE_GIT_KEY_PASS))
        var index = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hit = if (id != null) o.optString("id") == id
            else o.optString("url") == config.url && o.optString("branch") == config.branch &&
                (o.optString("key").isNotEmpty() == config.useSsh)
            if (hit) { index = i; break }
        }
        val useId = id ?: if (index >= 0) arr.getJSONObject(index).optString("id") else UUID.randomUUID().toString()
        obj.put("id", useId).put("sync", index >= 0 && arr.getJSONObject(index).optBoolean("sync", false))
        if (index >= 0) arr.put(index, obj) else arr.put(obj)
        write(app, arr, useId)
        return useId
    }

    /** 设置某个仓库是否在自动推送时一并同步。 */
    fun setSyncAlso(context: Context, id: String, on: Boolean) {
        val app = context.applicationContext
        val arr = rawArray(app)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) o.put("sync", on)
        }
        write(app, arr, activeId(app))
    }

    fun select(context: Context, id: String) {
        val app = context.applicationContext
        val arr = rawArray(app)
        if ((0 until arr.length()).any { arr.optJSONObject(it)?.optString("id") == id }) write(app, arr, id)
    }

    /** 删除一项；若删的是当前项，则改用剩下的第一项。全删光时一并清掉已信任的主机记录。 */
    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        val arr = rawArray(app)
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        val current = prefs(app).getString(K_ACTIVE, null)
        val next = if (current != null && current != id && (0 until kept.length()).any { kept.getJSONObject(it).optString("id") == current }) current
        else kept.optJSONObject(0)?.optString("id")
        write(app, kept, next)
        if (kept.length() == 0) knownHostsFile(app).delete()
    }

    fun lastPush(context: Context): Long = prefs(context).getLong(K_LAST_PUSH, 0L)

    fun markPushed(context: Context) {
        prefs(context).edit().putLong(K_LAST_PUSH, System.currentTimeMillis()).apply()
    }
}
