package dev.dsh.miuixbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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
    const val KEEP_BACKUPS = 10

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

    /** 列表 / 摘要里显示的服务器名：去掉协议、账号和末尾斜杠。 */
    fun hostLabel(url: String): String = url.substringAfter("://").substringAfterLast('@').trimEnd('/')

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
        // 强制 HTTP/1.1：部分 WebDAV 服务 / 前置代理在 HTTP/2 下处理 PUT 有问题（返回 405，或只创建 0 字节文件）
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            // 仅 debug：记录请求行与头（logcat 标签 OkHttp），Authorization 脱敏，不记录请求体（备份是二进制密文）
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                        redactHeader("Authorization")
                    },
                )
            }
        }
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

    /** 上传、校验大小并清理旧备份；返回上传后的文件名。 */
    fun upload(bytes: ByteArray, name: String = WebDavNames.newBackupName()): String {
        ensureDir()
        val url = WebDavNames.fileUrl(base, name)
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        call(request(url).put(body).build()) {
            // 301/302/303 重定向会把 PUT 改成 GET，此时返回 200 但文件并没有上传
            if (it.request.method != "PUT") {
                throw WebDavException("服务器把上传请求重定向到了别处（HTTP ${it.code}），请检查地址是否缺少路径或应使用 https://")
            }
            when (it.code) {
                in 200..204 -> Unit
                405 -> throw WebDavException("服务器拒绝了上传请求（HTTP 405）：该地址可能不允许 PUT，或服务器 / 代理与应用的 WebDAV 请求不兼容")
                else -> fail(it.code, "上传备份")
            }
        }
        // 校验：服务器上的大小必须与本地一致；部分服务会「成功」但只留下 0 字节文件
        val remoteSize = remoteSize(name)
        if (remoteSize != null && remoteSize != bytes.size.toLong()) {
            runCatching { delete(name) }
            throw WebDavException("上传后校验失败：服务器上的文件为 $remoteSize 字节，应为 ${bytes.size} 字节")
        }
        // 清理失败不影响上传结果
        runCatching { listBackups().drop(WebDavNames.KEEP_BACKUPS).forEach { old -> delete(old) } }
        return name
    }

    /** 读取服务器上某个备份的大小；服务器不返回 getcontentlength 时为 null（无法校验，视为通过）。 */
    private fun remoteSize(name: String): Long? = runCatching {
        val xml = """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><getcontentlength/></prop></propfind>"""
            .toRequestBody("application/xml".toMediaType())
        val req = request(WebDavNames.fileUrl(base, name)).header("Depth", "0").method("PROPFIND", xml).build()
        call(req) {
            if (it.code != 207 && it.code != 200) return@call null
            val text = it.body?.string().orEmpty()
            Regex("""<(?:\w+:)?getcontentlength[^>]*>\s*(\d+)\s*</""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toLongOrNull()
        }
    }.getOrNull()

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

/** 一条已连接过的服务器。[id] 稳定不变，编辑地址 / 账号后仍指向同一条。 */
/** [syncAlso]：自动推送时，除「当前」服务器外是否也一并同步到这台。 */
data class WebDavProfile(val id: String, val config: WebDavConfig, val syncAlso: Boolean = false)

/**
 * WebDAV 服务器的本机存储（不进备份文件）：保存所有测试通过的服务器，并记住当前使用的一台。
 * 密码经 [SecureStore] 加密；旧版单配置会在首次读取时自动迁移成列表里的第一项。
 */
object WebDavStore {
    private const val PREFS = "mafari_webdav"
    private const val K_URL = "url"          // 旧版单配置
    private const val K_USER = "user"        // 旧版单配置
    private const val K_PASS = "pass"        // 旧版单配置
    private const val K_PROFILES = "profiles"
    private const val K_ACTIVE = "active"
    private const val K_LAST_UPLOAD = "last_upload"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 旧版单配置 → 列表（沿用已加密的密码串，不必解密）。 */
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
                .put("pass", p.getString(K_PASS, "").orEmpty()),
        )
        p.edit().putString(K_PROFILES, arr.toString()).putString(K_ACTIVE, id)
            .remove(K_URL).remove(K_USER).remove(K_PASS).commit()
    }

    private fun rawArray(app: Context): JSONArray {
        migrateLegacy(app)
        return runCatching { JSONArray(prefs(app).getString(K_PROFILES, "[]")) }.getOrDefault(JSONArray())
    }

    private fun write(app: Context, arr: JSONArray, active: String?) {
        val e = prefs(app).edit().putString(K_PROFILES, arr.toString())
        if (active == null) e.remove(K_ACTIVE) else e.putString(K_ACTIVE, active)
        if (arr.length() == 0) e.remove(K_LAST_UPLOAD)
        if (!e.commit()) throw WebDavException("保存失败")
    }

    /** 所有已保存的服务器（无法解密的条目会被略过）。 */
    fun list(context: Context): List<WebDavProfile> {
        val app = context.applicationContext
        val arr = rawArray(app)
        val out = ArrayList<WebDavProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val url = o.optString("url")
            if (id.isEmpty() || url.isEmpty()) continue
            val raw = o.optString("pass")
            val pass = if (raw.isEmpty()) "" else SecureStore.read(app, raw, SecureStore.PURPOSE_WEBDAV) ?: continue
            out += WebDavProfile(id, WebDavConfig(url, o.optString("user"), pass), o.optBoolean("sync", false))
        }
        return out
    }

    /** 当前使用的服务器 id；记录失效时退回第一项。 */
    fun activeId(context: Context): String? {
        val app = context.applicationContext
        val arr = rawArray(app)
        val ids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }
        val saved = prefs(app).getString(K_ACTIVE, null)
        return if (saved != null && saved in ids) saved else ids.firstOrNull()
    }

    /** 当前使用的服务器配置。 */
    fun load(context: Context): WebDavConfig? {
        val id = activeId(context) ?: return null
        return list(context).firstOrNull { it.id == id }?.config
    }

    /**
     * 保存（新增或覆盖）并设为当前。[id] 为 null 表示新增；若已有同地址同账号的一项，则覆盖它而不重复添加。
     * 返回这一项的 id。
     */
    fun save(context: Context, config: WebDavConfig, id: String? = null): String {
        val app = context.applicationContext
        val arr = rawArray(app)
        val sealed = if (config.password.isEmpty()) "" else
            SecureStore.write(app, config.password, SecureStore.PURPOSE_WEBDAV) ?: throw WebDavException("无法用本机密钥加密保存密码")
        var index = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hit = if (id != null) o.optString("id") == id
            else o.optString("url") == config.url && o.optString("user") == config.username
            if (hit) { index = i; break }
        }
        val useId = id ?: if (index >= 0) arr.getJSONObject(index).optString("id") else UUID.randomUUID().toString()
        val keepSync = index >= 0 && arr.getJSONObject(index).optBoolean("sync", false)
        val obj = JSONObject().put("id", useId).put("url", config.url).put("user", config.username).put("pass", sealed).put("sync", keepSync)
        if (index >= 0) arr.put(index, obj) else arr.put(obj)
        write(app, arr, useId)
        return useId
    }

    /** 设置某台服务器是否在自动推送时一并同步。 */
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

    /** 删除一项；若删的是当前项，则改用剩下的第一项。 */
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
    }

    fun lastUpload(context: Context): Long = prefs(context).getLong(K_LAST_UPLOAD, 0L)

    fun markUploaded(context: Context) {
        prefs(context).edit().putLong(K_LAST_UPLOAD, System.currentTimeMillis()).apply()
    }
}
