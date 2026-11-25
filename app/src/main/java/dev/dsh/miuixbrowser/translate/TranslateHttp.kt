/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.translate

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 翻译请求用的最小 HTTP 封装（GET / POST，UTF-8，不跟随重定向，响应大小有上限）。阻塞调用。 */
internal object TranslateHttp {
    class Result(val code: Int, val body: String)

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 12_000,
    ): Result {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            throw TranslateException(TranslateException.Kind.NETWORK, e.message.orEmpty(), e)
        } catch (e: IllegalArgumentException) {
            throw TranslateException(TranslateException.Kind.CONFIG, url, e)
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readLimited(it) }.orEmpty()
            return Result(code, text)
        } catch (e: TranslateException) {
            throw e
        } catch (e: IOException) {
            throw TranslateException(TranslateException.Kind.NETWORK, e.message ?: e.javaClass.simpleName, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw TranslateException(TranslateException.Kind.PARSE, "response too large")
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 2xx 之外统一抛 [TranslateException]；401 / 403 归为 AUTH，其余 HTTP */
    fun check(r: Result) {
        if (r.code in 200..299) return
        val kind = if (r.code == 401 || r.code == 403) TranslateException.Kind.AUTH else TranslateException.Kind.HTTP
        throw TranslateException(kind, "HTTP ${r.code}${snippet(r.body)}")
    }

    private fun snippet(body: String): String {
        val t = body.trim().replace(Regex("\\s+"), " ")
        return if (t.isEmpty()) "" else " " + t.take(120)
    }
}
