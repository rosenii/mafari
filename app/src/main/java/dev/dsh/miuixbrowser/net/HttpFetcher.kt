package dev.dsh.miuixbrowser.net

import dev.dsh.miuixbrowser.i18n.tr

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 小体量 GET 的统一入口（图标、脚本、规则列表、发布信息、扩展商店 JSON 等）。
 *
 * 统一做的事：超时 / UA / Accept / Referer 可配；手动跟随重定向（含 http↔https 跳转，只放行 http(s) 目标）；
 * 大小上限（先看 Content-Length，再边读边数）；把常见网络异常翻成用户能看懂的中文提示。
 *
 * 不适用于：大文件下载（断点续传、进度，见 Downloader）、WebDAV 等非 GET 方法、带请求体的接口（见 FxaApi / WebDav）。
 * 全部是阻塞调用，必须在后台线程上调用。
 */
object HttpFetcher {

    const val UA = "Mozilla/5.0 (Linux; Android) mafari"

    /** 服务器返回了非 2xx。[code] 供调用方判断（例如 401/403 带 Referer 重试）。 */
    class HttpStatusException(val code: Int) : IOException(tr("服务器返回 HTTP {0}", code))

    class TooLargeException : IOException(tr("文件过大"))

    class Options(
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 6_000,
        val maxBytes: Int = 2 * 1024 * 1024,
        val userAgent: String = UA,
        val accept: String? = null,
        val referer: String? = null,
        val maxRedirects: Int = 5,
        /** 应用自己访问固定端点（更新、扩展商店等）时开：起始地址和每一跳重定向都必须是 https，不接受降级到 http */
        val httpsOnly: Boolean = false,
        /** 配合 [httpsOnly]：本机 / 局域网地址仍可用 http（用户自己架的服务）；公网地址和重定向降级照样拒绝 */
        val allowLocalHttp: Boolean = false,
    )

    class Response(val code: Int, val bytes: ByteArray, val contentType: String, val url: String) {
        fun text(): String = String(bytes, Charsets.UTF_8)
    }

    /** 抛 [HttpStatusException] / [TooLargeException] / 其他 [IOException]（消息已翻成中文）。 */
    fun get(url: String, options: Options = Options()): Response {
        var current = url
        for (hop in 0..options.maxRedirects) {
            if (options.httpsOnly) requireHttps(current, options.allowLocalHttp)
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = options.connectTimeoutMs
                readTimeout = options.readTimeoutMs
                instanceFollowRedirects = false // 自己跟随，才能处理 http↔https 的跳转
                setRequestProperty("User-Agent", options.userAgent)
                options.accept?.let { setRequestProperty("Accept", it) }
                options.referer?.let { setRequestProperty("Referer", it) }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException(tr("重定向缺少目标地址"))
                    current = URL(URL(current), location).toString()
                    if (!current.startsWith("http://", true) && !current.startsWith("https://", true)) {
                        throw IOException(tr("不支持的重定向目标"))
                    }
                    continue
                }
                if (code !in 200..299) throw HttpStatusException(code)
                if (conn.contentLengthLong > options.maxBytes) throw TooLargeException()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > options.maxBytes) throw TooLargeException()
                        out.write(buffer, 0, n)
                    }
                }
                val type = (conn.contentType ?: "").substringBefore(';').trim()
                return Response(code, out.toByteArray(), type, current)
            } catch (e: UnknownHostException) {
                throw IOException(tr("无法解析域名，请检查网络或链接"))
            } catch (e: SocketTimeoutException) {
                throw IOException(tr("连接超时"))
            } catch (e: SSLException) {
                throw IOException(tr("HTTPS 连接失败（证书或协议错误）"))
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e.message ?: tr("下载失败"))
            } finally {
                conn.disconnect()
            }
        }
        throw IOException(tr("重定向次数过多"))
    }

    fun getText(url: String, options: Options = Options()): String = get(url, options).text()

    /**
     * 必须是 https，否则抛 [IOException]。[allowLocal] 为 true 时，本机 / 局域网地址（localhost、回环、私网段、.local）可以用 http——
     * 它们的流量不出本地网络，开发者自己架的拓展服务器常见。
     */
    fun requireHttps(url: String, allowLocal: Boolean = false) {
        val u = runCatching { URL(url) }.getOrNull() ?: throw IOException(tr("仅允许 HTTPS 连接"))
        if (u.protocol.equals("https", ignoreCase = true)) return
        if (allowLocal && isLocalHost(u.host)) return
        throw IOException(tr("仅允许 HTTPS 连接"))
    }

    /** 本机 / 局域网主机名或 IP 字面量（不做 DNS 解析）。 */
    internal fun isLocalHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h == "::1") return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 127 || n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31)
    }
}
