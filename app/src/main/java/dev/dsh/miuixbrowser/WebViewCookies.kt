package dev.dsh.miuixbrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 备份：导出 / 导入系统 WebView 的持久 Cookie。
 *
 * 导出 = 先 [CookieManager.flush] 把内存里的 Cookie 落盘，再把应用私有目录里 Chromium 自己的 Cookie 库
 * （app_webview/Default/Cookies 或 Default/Network/Cookies）连同 -journal / -wal 复制到临时目录，只读打开后逐行转换。
 * 这是 Chromium 内部格式而不是公开 API：路径、表结构可能随 WebView 版本变化，所以
 * 找不到文件 / 缺必需列 / 值被加密都直接抛 [IOException]，由调用方中止备份，不会生成缺 Cookie 的备份。
 *
 * 输出的 JSON 数组字段与 Gecko 内置拓展的导出完全一致
 * （name / value / domain / hostOnly / path / secure / httpOnly / sameSite / expirationDate(秒) / firstPartyDomain），
 * 所以两种内核的备份可以互相恢复。
 *
 * 导入 = 以备份为准：先清空 WebView 现有 Cookie，再逐条 setCookie，最后 flush。
 */
object WebViewCookies {

    /** Chromium 的时间戳是 1601-01-01 起的微秒；到 Unix 纪元差这么多秒。 */
    private const val WINDOWS_EPOCH_OFFSET_SEC = 11_644_473_600L

    private val REQUIRED_COLUMNS = listOf("host_key", "name", "value", "path", "expires_utc", "is_secure", "is_httponly")

    /** 导入结果：[set] 成功写入，[failed] 被 WebView 拒绝 / 数据非法，[skipped] 主动跳过（已过期、Gecko 的第一方隔离 Cookie）。 */
    class ImportResult(val set: Int, val failed: Int, val skipped: Int)

    // ------------------------------------------------------------------ 导出

    /** IO 线程：返回持久 Cookie 的 JSON 数组字符串（会话 Cookie 不含）。读不了就抛 [IOException]。 */
    fun export(context: Context): String {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("不能在主线程读取 Cookie")
        val app = context.applicationContext

        // flush() 会阻塞到落盘完成；落盘之后才能读到最新的库
        try {
            CookieManager.getInstance().flush()
        } catch (t: Throwable) {
            throw IOException("WebView 无法刷新 Cookie：${t.message}", t)
        }

        val source = findCookieDb(app)
            ?: throw IOException("找不到 WebView 的 Cookie 数据库（可能是 WebView 版本不兼容），已取消备份 Cookie")

        val tmpDir = File(app.cacheDir, "webview-cookie-export").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val copy = File(tmpDir, "Cookies")
            source.copyTo(copy, overwrite = true)
            for (suffix in listOf("-journal", "-wal", "-shm")) {
                val side = File(source.path + suffix)
                if (side.isFile) side.copyTo(File(copy.path + suffix), overwrite = true)
            }
            return readDb(copy)
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException("读取 WebView Cookie 失败：${t.message}", t)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** 两个已知位置里，取存在且最近修改的那个。 */
    private fun findCookieDb(app: Context): File? {
        val base = File(app.dataDir, "app_webview/Default")
        return listOf(File(base, "Network/Cookies"), File(base, "Cookies"))
            .filter { it.isFile && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
    }

    private fun readDb(file: File): String {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val columns = HashSet<String>()
            db.rawQuery("PRAGMA table_info(cookies)", null).use { c ->
                while (c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))
            }
            if (columns.isEmpty()) throw IOException("WebView Cookie 库里没有 cookies 表，格式不兼容")
            val missing = REQUIRED_COLUMNS.filter { it !in columns }
            if (missing.isNotEmpty()) throw IOException("WebView Cookie 库缺少列 ${missing.joinToString()}，格式不兼容")

            val hasEncrypted = "encrypted_value" in columns
            val hasSameSite = "samesite" in columns
            val select = buildString {
                append("SELECT host_key, name, value, path, expires_utc, is_secure, is_httponly")
                append(if (hasSameSite) ", samesite" else ", -1")
                append(if (hasEncrypted) ", length(encrypted_value)" else ", 0")
                if ("has_expires" in columns) append(", has_expires") else append(", 1")
                append(" FROM cookies")
            }

            val nowSec = System.currentTimeMillis() / 1000.0
            val out = JSONArray()
            db.rawQuery(select, null).use { c ->
                while (c.moveToNext()) {
                    val value = c.getString(2).orEmpty()
                    val encryptedLen = c.getInt(8)
                    // 明文值为空而 encrypted_value 有内容：这台设备的 WebView 把值加密了，读出来是密文，不能写进备份
                    if (value.isEmpty() && encryptedLen > 0) {
                        throw IOException("WebView 的 Cookie 值是加密存储的，无法备份")
                    }
                    val expiresUtc = c.getLong(4)
                    val hasExpires = c.getInt(9) != 0
                    if (!hasExpires || expiresUtc <= 0L) continue // 会话 Cookie
                    val expirationSec = expiresUtc / 1_000_000.0 - WINDOWS_EPOCH_OFFSET_SEC
                    if (expirationSec <= nowSec) continue // 已过期
                    val host = c.getString(0).orEmpty()
                    if (host.isEmpty()) continue
                    out.put(
                        JSONObject()
                            .put("name", c.getString(1).orEmpty())
                            .put("value", value)
                            .put("domain", host)
                            .put("hostOnly", !host.startsWith("."))
                            .put("path", c.getString(3).orEmpty().ifEmpty { "/" })
                            .put("secure", c.getInt(5) != 0)
                            .put("httpOnly", c.getInt(6) != 0)
                            .put("sameSite", sameSiteToWebExt(c.getInt(7)))
                            .put("expirationDate", expirationSec)
                            .put("firstPartyDomain", ""),
                    )
                }
            }
            return out.toString()
        } finally {
            db.close()
        }
    }

    /** Chromium samesite 列：-1 未指定、0 None、1 Lax、2 Strict；输出与 WebExtensions 的 SameSiteStatus 对齐。 */
    private fun sameSiteToWebExt(v: Int): String = when (v) {
        0 -> "no_restriction"
        1 -> "lax"
        2 -> "strict"
        else -> "unspecified"
    }

    // ------------------------------------------------------------------ 导入

    /**
     * IO 线程：用备份的 [json]（Gecko 同款字段）替换 WebView 现有 Cookie。
     * 一条都没写进去时抛 [IOException]；部分失败只体现在返回值里。
     */
    fun importCookies(context: Context, json: String): ImportResult {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("不能在主线程写入 Cookie")
        val list = try {
            JSONArray(json)
        } catch (e: Exception) {
            throw IOException("Cookie 数据格式错误", e)
        }
        val cm = CookieManager.getInstance()
        val main = Handler(Looper.getMainLooper())

        // 以备份为准：先清空（回调在有 Looper 的线程，所以放到主线程发起并等待）
        val cleared = CountDownLatch(1)
        main.post {
            try {
                cm.removeAllCookies { cleared.countDown() }
            } catch (t: Throwable) {
                cleared.countDown()
            }
        }
        if (!cleared.await(15, TimeUnit.SECONDS)) throw IOException("清除 WebView 现有 Cookie 超时，已取消恢复 Cookie")

        val nowSec = System.currentTimeMillis() / 1000.0
        val ok = AtomicInteger()
        val bad = AtomicInteger()
        var skipped = 0
        val jobs = ArrayList<Pair<String, String>>() // url → Set-Cookie 风格字符串
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i)
            if (c == null) { bad.incrementAndGet(); continue }
            val name = c.optString("name")
            val domain = c.optString("domain")
            if (name.isEmpty() || domain.isEmpty()) { bad.incrementAndGet(); continue }
            if (c.optString("firstPartyDomain").isNotEmpty()) { skipped++; continue } // WebView 没有第一方隔离，导进去会串到普通 Cookie 里
            val exp = c.optDouble("expirationDate", 0.0)
            if (exp > 0.0 && exp <= nowSec) { skipped++; continue } // 备份后已过期
            jobs.add(toSetCookie(c, name, domain, exp))
        }

        if (jobs.isNotEmpty()) {
            val done = CountDownLatch(jobs.size)
            main.post {
                for ((url, cookie) in jobs) {
                    try {
                        cm.setCookie(url, cookie) { success ->
                            if (success) ok.incrementAndGet() else bad.incrementAndGet()
                            done.countDown()
                        }
                    } catch (t: Throwable) {
                        bad.incrementAndGet()
                        done.countDown()
                    }
                }
            }
            if (!done.await(30, TimeUnit.SECONDS)) throw IOException("写入 WebView Cookie 超时")
        }
        cm.flush()

        if (ok.get() == 0 && list.length() > 0 && skipped < list.length()) {
            throw IOException("没有任何 Cookie 能写入 WebView")
        }
        return ImportResult(ok.get(), bad.get(), skipped)
    }

    private fun toSetCookie(c: JSONObject, name: String, domain: String, exp: Double): Pair<String, String> {
        val secure = c.optBoolean("secure")
        val path = c.optString("path").ifEmpty { "/" }
        val host = domain.removePrefix(".")
        val url = (if (secure) "https" else "http") + "://" + host + path
        val sb = StringBuilder()
        sb.append(name).append('=').append(c.optString("value"))
        if (!c.optBoolean("hostOnly", !domain.startsWith("."))) sb.append("; Domain=").append(host)
        sb.append("; Path=").append(path)
        if (exp > 0.0) sb.append("; Expires=").append(httpDate((exp * 1000).toLong()))
        if (secure) sb.append("; Secure")
        if (c.optBoolean("httpOnly")) sb.append("; HttpOnly")
        when (c.optString("sameSite").lowercase(Locale.ROOT)) {
            "lax" -> sb.append("; SameSite=Lax")
            "strict" -> sb.append("; SameSite=Strict")
            // Chromium 拒绝不带 Secure 的 SameSite=None，这种情况退化为不写该属性（按浏览器默认处理）
            "no_restriction", "none" -> if (secure) sb.append("; SameSite=None")
        }
        return url to sb.toString()
    }

    private fun httpDate(millis: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(millis))
}
