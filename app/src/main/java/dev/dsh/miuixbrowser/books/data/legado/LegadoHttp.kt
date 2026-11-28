/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data.legado

import dev.dsh.miuixbrowser.books.data.BookContent
import dev.dsh.miuixbrowser.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.TreeMap
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/**
 * 书源自己的 Cookie 罐（只存在内存里，按书源隔离）。书源脚本会用 `cookie.setCookie / getCookie / removeCookie`
 * 固定会话（例如验证码和搜索必须同一个 PHPSESSID），所以这里按主机存「名=值」，发请求时带上同域和父域的。
 */
class LegadoCookieJar {
    private val store = HashMap<String, LinkedHashMap<String, String>>()

    @Synchronized
    fun header(url: String): String? {
        val host = hostOf(url) ?: return null
        val parts = ArrayList<String>()
        for ((domain, cookies) in store) {
            if (host == domain || host.endsWith(".$domain")) cookies.forEach { (k, v) -> parts.add("$k=$v") }
        }
        return parts.joinToString("; ").ifEmpty { null }
    }

    /** 响应里的 Set-Cookie（只取 名=值，认 Domain / Max-Age=0 / 过期） */
    @Synchronized
    fun save(url: String, setCookies: List<String>) {
        val host = hostOf(url) ?: return
        for (line in setCookies) {
            val attrs = line.split(';')
            val nv = attrs.firstOrNull()?.trim() ?: continue
            val eq = nv.indexOf('=')
            if (eq <= 0) continue
            val name = nv.substring(0, eq).trim()
            val value = nv.substring(eq + 1).trim()
            var domain = host
            var expired = false
            for (a in attrs.drop(1)) {
                val k = a.substringBefore('=').trim().lowercase()
                val v = a.substringAfter('=', "").trim()
                when (k) {
                    "domain" -> if (v.isNotEmpty()) domain = v.removePrefix(".").lowercase()
                    "max-age" -> if ((v.toLongOrNull() ?: 1L) <= 0L) expired = true
                }
            }
            val map = store.getOrPut(domain) { LinkedHashMap() }
            if (expired) map.remove(name) else map[name] = value
        }
    }

    /** `a=1; b=2` 形式的整串（书源 cookie.setCookie 用） */
    @Synchronized
    fun set(url: String, cookieText: String) {
        val host = hostOf(url) ?: return
        val map = store.getOrPut(host) { LinkedHashMap() }
        for (part in cookieText.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
    }

    @Synchronized
    fun remove(url: String) {
        val host = hostOf(url) ?: return
        store.keys.removeAll { host == it || host.endsWith(".$it") }
    }

    private fun hostOf(url: String): String? = runCatching { URL(url).host.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }
}

class LegadoRequest(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** 请求（表单 / 查询）与响应共用的字符集；空则响应按 Content-Type / meta / 内容自动判断 */
    val charset: String? = null,
    val retry: Int = 0,
)

/** 供书源脚本用的响应（`java.get / post / connect` 的返回值）。 */
class LegadoResponse(
    val finalUrl: String,
    val status: Int,
    val bytes: ByteArray,
    val contentType: String,
    val headers: Map<String, List<String>>,
    private val forcedCharset: String?,
) {
    fun body(): String = decodeBody()
    fun code(): Int = status
    fun url(): String = finalUrl
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun toString(): String = decodeBody()

    private fun decodeBody(): String {
        forcedCharset?.let { return decode(bytes, it) }
        CHARSET_IN_TYPE.find(contentType)?.let { return decode(bytes, it.groupValues[1]) }
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        META_CHARSET.find(head)?.let { return decode(bytes, it.groupValues[1]) }
        return BookContent.decodeText(bytes)
    }

    private fun decode(b: ByteArray, name: String): String {
        val n = name.trim().lowercase()
        val cs = when (n) {
            "gbk", "gb2312", "gb18030" -> charset("GB18030")
            else -> runCatching { Charset.forName(n) }.getOrNull()
        } ?: return BookContent.decodeText(b)
        val s = String(b, cs)
        return s.removePrefix("\uFEFF")
    }

    private companion object {
        val CHARSET_IN_TYPE = Regex("charset=\\s*\"?([\\w-]+)", RegexOption.IGNORE_CASE)
        val META_CHARSET = Regex("<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
    }
}

/**
 * 书源用的 HTTP 客户端：GET / POST、手动跟随重定向（逐跳收 Cookie）、请求头合并、访问频率限制。
 * 阻塞调用，放 IO 线程。
 */
class LegadoHttp(
    private val jar: LegadoCookieJar,
    private val defaultHeaders: Map<String, String>,
    rateSpec: String?,
    private val timeoutMs: Int = 30_000,
) {
    private val intervalMs: Long = parseRate(rateSpec)
    private var lastSlot = 0L

    /** 两次请求至少间隔 [intervalMs]；并发调用按先后排队占位。 */
    private fun throttle() {
        if (intervalMs <= 0) return
        val wait: Long
        synchronized(this) {
            val now = System.currentTimeMillis()
            val at = maxOf(now, lastSlot + intervalMs)
            lastSlot = at
            wait = at - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    fun fetch(req: LegadoRequest): LegadoResponse {
        var attempt = 0
        while (true) {
            try {
                return fetchOnce(req)
            } catch (e: IOException) {
                if (attempt++ >= req.retry) throw e
            }
        }
    }

    private fun fetchOnce(req: LegadoRequest): LegadoResponse {
        var url = req.url
        var method = req.method.uppercase()
        var body = req.body
        for (hop in 0..MAX_REDIRECTS) {
            val parsed = runCatching { URL(url) }.getOrNull() ?: throw IOException(tr("链接无效"))
            if (!parsed.protocol.equals("http", true) && !parsed.protocol.equals("https", true)) throw IOException(tr("不支持的链接协议"))
            throttle()

            val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            headers.putAll(defaultHeaders)
            headers.putAll(req.headers)
            if (!headers.containsKey("User-Agent")) headers["User-Agent"] = DEFAULT_UA
            val jarCookie = jar.header(url)
            if (jarCookie != null) headers["Cookie"] = listOfNotNull(headers["Cookie"]?.takeIf { it.isNotBlank() }, jarCookie).joinToString("; ")

            val conn = parsed.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = minOf(timeoutMs, 15_000)
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.requestMethod = method
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                if (body != null && method != "GET" && method != "HEAD") {
                    val charset = req.charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                    if (!headers.containsKey("Content-Type")) {
                        val t = body.trimStart()
                        conn.setRequestProperty(
                            "Content-Type",
                            if (t.startsWith("{") || t.startsWith("[")) "application/json;charset=UTF-8" else "application/x-www-form-urlencoded",
                        )
                    }
                    val bytes = body.toByteArray(charset)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }

                val code = conn.responseCode
                val setCookies = conn.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
                if (setCookies.isNotEmpty()) jar.save(url, setCookies)

                if (code in 300..399 && code != 304) {
                    val location = conn.getHeaderField("Location") ?: throw IOException(tr("重定向缺少目标地址"))
                    url = URL(parsed, location).toString()
                    if (code != 307 && code != 308) {
                        method = "GET"
                        body = null
                    }
                    continue
                }

                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.use { readAll(it, conn.getHeaderField("Content-Encoding")) } ?: ByteArray(0)
                val responseHeaders = conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
                return LegadoResponse(url, code, bytes, conn.contentType.orEmpty(), responseHeaders, req.charset)
            } catch (e: SocketTimeoutException) {
                throw IOException(tr("连接超时，检查网络"), e)
            } catch (e: UnknownHostException) {
                throw IOException(tr("无法解析服务器地址，检查网络"), e)
            } catch (e: SSLException) {
                throw IOException(tr("安全连接失败"), e)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException(tr("重定向次数过多"))
    }

    private fun readAll(input: java.io.InputStream, encoding: String?): ByteArray {
        val src = if (encoding != null && encoding.contains("gzip", true)) GZIPInputStream(input) else input
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException(tr("文件过大"))
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_REDIRECTS = 8
        private const val MAX_BYTES = 16 * 1024 * 1024
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        /** `1/1500` → 1500；`3/1000` → 334；`2000` → 2000；其他 → 0（不限速）。最多限到 10 秒一次。 */
        internal fun parseRate(spec: String?): Long {
            val s = spec?.trim().orEmpty()
            if (s.isEmpty()) return 0
            val slash = s.indexOf('/')
            val v = if (slash > 0) {
                val count = s.substring(0, slash).trim().toLongOrNull() ?: return 0
                val window = s.substring(slash + 1).trim().toLongOrNull() ?: return 0
                if (count <= 0) return 0
                (window + count - 1) / count
            } else {
                s.toLongOrNull() ?: return 0
            }
            return v.coerceIn(0, 10_000)
        }
    }
}
