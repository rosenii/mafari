package dev.dsh.miuixbrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** 抓网站 favicon，带内存缓存。 */
object FaviconLoader {

    private val cache = ConcurrentHashMap<String, ImageBitmap?>()

    /** 抓取失败的主机 → 失败时间；一段时间内不再重试（历史 / 书签长列表里没有图标的站点会反复触发加载） */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val FAIL_TTL_MS = 5 * 60 * 1000L

    suspend fun load(host: String): ImageBitmap? {
        if (host.isBlank()) return null
        cache[host]?.let { return it }
        failedAt[host]?.let { t ->
            if (System.currentTimeMillis() - t < FAIL_TTL_MS) return null
            failedAt.remove(host)
        }
        Diag.log("favicon start $host")
        val bitmap = runCatching {
            val urls = listOf(
                "https://$host/favicon.ico",
                "https://www.google.com/s2/favicons?sz=64&domain=$host",
            )
            var result: Bitmap? = null
            for (url in urls) {
                result = fetch(url)
                if (result != null) break
            }
            result
        }.getOrNull()
        Diag.log("favicon done $host -> ${bitmap != null}")
        val image = bitmap?.asImageBitmap()
        // ConcurrentHashMap 不收 null，之前这里直接 NPE
        if (image != null) cache[host] = image else failedAt[host] = System.currentTimeMillis()
        return image
    }

    private fun fetch(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) mafari")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                null
            } else {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
fun rememberFavicon(url: String): ImageBitmap? {
    val host = remember(url) { hostOf(url) }
    var icon by remember(host) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(host) {
        icon = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            FaviconLoader.load(host)
        }
    }
    return icon
}

/**
 * 加载油猴 @icon 之类的远程图标。
 *
 * 比旧版多做的事（旧版只会对 http(s) 地址直接 GET 一次，失败就空白）：
 * - 地址规整：协议相对 `//`、相对 sourceUrl 的路径、GitHub 网页地址（blob/raw）→ raw 直链、去引号和片段；
 * - `data:` 图标直接解码；http 先试 https；手动跟随重定向（含 http↔https 跳转）；
 * - 403/401 时带 Referer 重试（防盗链）；GitHub 系地址并发走加速镜像；
 * - 大图按 128px 降采样，限制 2MB，BitmapFactory 失败再用 ImageDecoder（AVIF / HEIF 等）；
 * - 失败 10 分钟内不重复请求；最终回退到脚本站点的 favicon，仍失败才显示默认图标。
 * 不解码 SVG（data:image/svg+xml 与 .svg 地址直接视为失败，走站点 favicon 回退）。
 */
object RemoteIconLoader {

    private const val UA = "Mozilla/5.0 (Linux; Android) mafari"
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TARGET_PX = 128
    private const val FAIL_TTL_MS = 10 * 60 * 1000L

    private val cache = android.util.LruCache<String, ImageBitmap>(96)
    private val failedAt = ConcurrentHashMap<String, Long>()

    private class Fetch(val bitmap: Bitmap?, val code: Int)

    /** 把 @icon 原始值规整成可请求的地址；无法规整返回 null */
    fun normalize(raw: String, base: String = ""): String? {
        var u = raw.trim().trim('"', '\'').trim()
        if (u.isEmpty()) return null
        if (u.startsWith("data:", ignoreCase = true)) return u
        u = u.substringBefore('#').replace(" ", "%20")
        u = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true) -> u
            base.startsWith("http", ignoreCase = true) ->
                runCatching { java.net.URI(base).resolve(u).toString() }.getOrNull() ?: return null
            else -> return null
        }
        // github.com/用户/仓库/blob|raw/分支/路径 返回的是网页，要换成 raw 直链
        GITHUB_PAGE.find(u)?.let { m ->
            u = "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}"
        }
        return u
    }

    private val GITHUB_PAGE = Regex("^https?://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/([^?]+)", RegexOption.IGNORE_CASE)
    private val GITHUB_HOST = Regex("^https?://(?:raw\\.githubusercontent\\.com|github\\.com)/", RegexOption.IGNORE_CASE)

    suspend fun load(raw: String, base: String = "", fallbackHost: String = ""): ImageBitmap? {
        val url = normalize(raw, base)
        if (url != null) {
            cache.get(url)?.let { return it }
            val failed = failedAt[url]
            if (failed == null || System.currentTimeMillis() - failed > FAIL_TTL_MS) {
                val bitmap = runCatching { fetchIcon(url) }.getOrNull()?.asImageBitmap()
                if (bitmap != null) {
                    cache.put(url, bitmap)
                    failedAt.remove(url)
                    return bitmap
                }
                failedAt[url] = System.currentTimeMillis()
            }
        }
        // @icon 没写 / 加载失败：退回脚本所属站点的 favicon
        return if (fallbackHost.isNotBlank()) FaviconLoader.load(fallbackHost) else null
    }

    private fun fetchIcon(url: String): Bitmap? {
        if (url.startsWith("data:", ignoreCase = true)) return decodeDataUri(url)
        if (GITHUB_HOST.containsMatchIn(url)) {
            // 直连 + 各加速镜像并发，先成功者胜出
            val fetchers = GitHubAccelerate.MirrorPrefixes.map { prefix ->
                { fetchWithReferer(GitHubAccelerate.map(url, prefix)) }
            }
            return GitHubAccelerate.raceFirst(9000, fetchers)
        }
        val candidates = if (url.startsWith("http://", ignoreCase = true)) listOf("https://" + url.substring(7), url) else listOf(url)
        for (candidate in candidates) {
            fetchWithReferer(candidate)?.let { return it }
        }
        return null
    }

    /** 先普通请求；401/403 多半是防盗链，带上图片自身站点的 Referer 再试一次 */
    private fun fetchWithReferer(url: String): Bitmap? {
        val first = download(url, null)
        if (first.bitmap != null) return first.bitmap
        if (first.code == 401 || first.code == 403) {
            val origin = runCatching { URL(url).let { "${it.protocol}://${it.host}/" } }.getOrNull()
            if (origin != null) return download(url, origin).bitmap
        }
        return null
    }

    private fun download(url: String, referer: String?): Fetch {
        var current = url
        var lastCode = -1
        for (hop in 0..5) {
            val connection = runCatching { URL(current).openConnection() as HttpURLConnection }.getOrNull()
                ?: return Fetch(null, lastCode)
            connection.apply {
                connectTimeout = 5000
                readTimeout = 6000
                instanceFollowRedirects = false // 自己跟随，才能处理 http↔https 的跳转
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8")
                if (referer != null) setRequestProperty("Referer", referer)
            }
            try {
                val code = connection.responseCode
                lastCode = code
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return Fetch(null, code)
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) return Fetch(null, code)
                val bytes = readLimited(connection.inputStream) ?: return Fetch(null, code)
                return Fetch(decode(bytes), code)
            } catch (e: Exception) {
                return Fetch(null, lastCode)
            } finally {
                connection.disconnect()
            }
        }
        return Fetch(null, lastCode)
    }

    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun decodeDataUri(uri: String): Bitmap? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma)
        val payload = uri.substring(comma + 1)
        if (meta.contains("svg", ignoreCase = true)) return null
        val bytes = if (meta.contains("base64", ignoreCase = true)) {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(payload, "ISO-8859-1").toByteArray(Charsets.ISO_8859_1)
        }
        return decode(bytes)
    }

    /** 降采样解码；BitmapFactory 不认的格式再交给 ImageDecoder */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { return it }
        }
        return runCatching {
            android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)),
            ) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > TARGET_PX * 2) decoder.setTargetSampleSize(longest / (TARGET_PX * 2))
            }
        }.getOrNull()
    }
}

/**
 * @param baseUrl 脚本来源地址，用来解析相对路径的 @icon
 * @param fallbackHost @icon 失败时回退取 favicon 的主机（见 UserscriptEngine.fallbackIconHost）
 */
@Composable
fun rememberRemoteIcon(url: String, baseUrl: String = "", fallbackHost: String = ""): ImageBitmap? {
    var icon by remember(url, baseUrl, fallbackHost) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, baseUrl, fallbackHost) {
        icon = if (url.isBlank() && fallbackHost.isBlank()) {
            null
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                RemoteIconLoader.load(url, baseUrl, fallbackHost)
            }
        }
    }
    return icon
}
