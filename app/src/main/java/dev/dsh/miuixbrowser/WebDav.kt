package dev.dsh.miuixbrowser

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * WebDAV 同步：把加密备份（.mfbak，格式见 Backup.kt）上传到 / 下载自用户自己的 WebDAV 服务器。
 *
 *   服务器上的布局：<地址>/Mafari/mafari-backup-yyyyMMdd-HHmmss.mfbak，只保留最近 [KEEP_BACKUPS] 份。
 *   服务器拿到的永远是密文（口令只在本机输入、不保存），所以 WebDAV 服务本身不需要被信任。
 *   服务器账号只存本机：密码经 [SecureStore] 加密，且不进备份文件（换机后需要重新填写）。
 */

class WebDavException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class WebDavConfig(val url: String, val username: String, val password: String)

/** 纯函数部分（URL 规整、文件名、PROPFIND 解析），放这里便于 JVM 单测。 */
object WebDavNames {
    const val REMOTE_DIR = "Mafari"
    const val KEEP_BACKUPS = 5

    private val NAME_REGEX = Regex("""mafari-backup-\d{8}-\d{6}\.${BackupFormat.EXTENSION}""")
    private val HREF_REGEX = Regex("""<(?:[A-Za-z0-9_.-]+:)?href\s*>([^<]*)</(?:[A-Za-z0-9_.-]+:)?href\s*>""", RegexOption.IGNORE_CASE)

    /** 规整用户输入的地址：去空白和末尾斜杠；只接受 http / https；不合法返回 null。 */
    fun normalizeBaseUrl(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return null
        val host = s.substringAfter("://")
        if (host.isBlank() || host.startsWith("/") || host.startsWith("?") || host.startsWith("#")) return null
        return s
    }

    fun dirUrl(base: String) = "$base/$REMOTE_DIR/"

    fun fileUrl(base: String, name: String) = dirUrl(base) + name

    fun newBackupName(now: Long = System.currentTimeMillis()): String =
        "mafari-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + "." + BackupFormat.EXTENSION

    fun isBackupName(name: String) = NAME_REGEX.matches(name)

    /**
     * 解析 PROPFIND（Depth: 1）的 207 响应，取出备份文件名，新的在前。
     * 不同服务器的命名空间前缀不一（D: / d: / 无前缀），所以只按 href 元素的本地名匹配。
     */
    fun parseBackupNames(xml: String): List<String> =
        HREF_REGEX.findAll(xml)
            .map { decodeXml(it.groupValues[1].trim()) }
            .map { it.trimEnd('/').substringAfterLast('/') }
            .map { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
            .filter { isBackupName(it) }
            .distinct()
            .sortedDescending()
            .toList()

    private fun decodeXml(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}

class WebDavClient(private val config: WebDavConfig) {

    private val base: String = WebDavNames.normalizeBaseUrl(config.url) ?: throw WebDavException("服务器地址需以 http:// 或 https:// 开头")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun request(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (config.username.isNotEmpty()) b.header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
        return b
    }

    private fun <T> call(req: Request, block: (Response) -> T): T {
        try {
            http.newCall(req).execute().use { return block(it) }
        } catch (e: WebDavException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw WebDavException("服务器地址格式不正确", e)
        } catch (e: javax.net.ssl.SSLException) {
            throw WebDavException("TLS 连接失败（证书无效或协议不匹配）", e)
        } catch (e: IOException) {
            throw WebDavException("无法连接服务器：" + (e.message ?: e.javaClass.simpleName), e)
        }
    }

    private fun fail(code: Int, what: String): Nothing = throw WebDavException(
        when (code) {
            401 -> "用户名或密码错误"
            403 -> "没有权限：$what"
            404 -> "地址不存在：$what"
            405 -> "服务器不支持 WebDAV：$what"
            409 -> "上级目录不存在：$what"
            507 -> "服务器空间不足"
            else -> "$what 失败（HTTP $code）"
        },
    )

    private fun propfind(url: String, depth: Int): Request {
        val body = """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
            .toRequestBody("application/xml".toMediaType())
        return request(url).header("Depth", depth.toString()).method("PROPFIND", body).build()
    }

    /** 连通性与账号检测：先看地址本身能否 PROPFIND，再确保备份目录存在。 */
    fun test() {
        call(propfind("$base/", 0)) { if (it.code != 207 && it.code != 200) fail(it.code, "连接服务器") }
        ensureDir()
    }

    private fun ensureDir() {
        val dir = WebDavNames.dirUrl(base)
        val exists = call(propfind(dir, 0)) {
            when (it.code) {
                207, 200 -> true
                404 -> false
                else -> fail(it.code, "访问 ${WebDavNames.REMOTE_DIR} 文件夹")
            }
        }
        if (exists) return
        call(request(dir).method("MKCOL", null).build()) {
            // 201 新建；405 已存在（部分服务器 PROPFIND 与 MKCOL 判断不一致）
            if (it.code != 201 && it.code != 405 && it.code != 200) fail(it.code, "创建 ${WebDavNames.REMOTE_DIR} 文件夹")
        }
    }

    /** 服务器上的备份文件名，新的在前；文件夹还不存在则为空。 */
    fun listBackups(): List<String> {
        val dir = WebDavNames.dirUrl(base)
        return call(propfind(dir, 1)) {
            when (it.code) {
                207, 200 -> WebDavNames.parseBackupNames(it.body?.string().orEmpty())
                404 -> emptyList()
                else -> fail(it.code, "读取备份列表")
            }
        }
    }

    /** 上传并清理旧备份；返回上传后的文件名。 */
    fun upload(bytes: ByteArray, name: String = WebDavNames.newBackupName()): String {
        ensureDir()
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        call(request(WebDavNames.fileUrl(base, name)).put(body).build()) {
            if (it.code !in 200..204) fail(it.code, "上传备份")
        }
        // 清理失败不影响上传结果
        runCatching { listBackups().drop(WebDavNames.KEEP_BACKUPS).forEach { old -> delete(old) } }
        return name
    }

    fun download(name: String): ByteArray {
        require(WebDavNames.isBackupName(name))
        return call(request(WebDavNames.fileUrl(base, name)).get().build()) {
            if (!it.isSuccessful) fail(it.code, "下载备份")
            val stream = it.body?.byteStream() ?: throw WebDavException("服务器返回了空内容")
            try {
                BackupManager.readAll(stream)
            } catch (e: CryptoException) {
                throw WebDavException(e.message ?: "备份文件过大", e)
            }
        }
    }

    private fun delete(name: String) {
        call(request(WebDavNames.fileUrl(base, name)).delete().build()) { }
    }

    /** 下载服务器上最新的一份；没有备份则抛 [WebDavException]。 */
    fun downloadLatest(): Pair<String, ByteArray> {
        val name = listBackups().firstOrNull() ?: throw WebDavException("服务器上还没有备份")
        return name to download(name)
    }
}

/** WebDAV 账号的本机存储（不进备份文件）。 */
object WebDavStore {
    private const val PREFS = "mafari_webdav"
    private const val K_URL = "url"
    private const val K_USER = "user"
    private const val K_PASS = "pass"
    private const val K_LAST_UPLOAD = "last_upload"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): WebDavConfig? {
        val p = prefs(context)
        val url = p.getString(K_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val raw = p.getString(K_PASS, "").orEmpty()
        val pass = if (raw.isEmpty()) "" else SecureStore.read(context.applicationContext, raw, SecureStore.PURPOSE_WEBDAV) ?: return null
        return WebDavConfig(url, p.getString(K_USER, "").orEmpty(), pass)
    }

    fun save(context: Context, config: WebDavConfig) {
        val app = context.applicationContext
        val sealed = if (config.password.isEmpty()) "" else
            SecureStore.write(app, config.password, SecureStore.PURPOSE_WEBDAV) ?: throw WebDavException("无法用本机密钥加密保存密码")
        if (!prefs(app).edit()
                .putString(K_URL, config.url)
                .putString(K_USER, config.username)
                .putString(K_PASS, sealed)
                .commit()
        ) throw WebDavException("保存失败")
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun lastUpload(context: Context): Long = prefs(context).getLong(K_LAST_UPLOAD, 0L)

    fun markUploaded(context: Context) {
        prefs(context).edit().putLong(K_LAST_UPLOAD, System.currentTimeMillis()).apply()
    }
}
