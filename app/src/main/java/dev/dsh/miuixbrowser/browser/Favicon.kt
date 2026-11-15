package dev.dsh.miuixbrowser.browser

import java.net.URL
import dev.dsh.miuixbrowser.data.IconStore
import dev.dsh.miuixbrowser.data.IconStores
import dev.dsh.miuixbrowser.net.GitHubAccelerate
import dev.dsh.miuixbrowser.net.HttpFetcher
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.hostOf
import android.content.Context
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

/**
 * 抓网站 favicon（域名规整、缓存、合并、退避都在 [IconStore] 里）。
 * 域名按小写、去 `www.` 规整成键，所以 www.github.com 和 github.com 共用一张图。
 */
object FaviconLoader {

    private const val DISK_TTL_MS = 14L * 24 * 60 * 60 * 1000

    private val store = IconStore(
        subdir = "favicons",
        ttlMs = DISK_TTL_MS,
        maxFiles = 600,
        concurrency = 4,
        normalizeKey = { normalize(it) },
    )

    private val options = HttpFetcher.Options(connectTimeoutMs = 4000, readTimeoutMs = 4000)

    /** 在 Application.onCreate 里调用一次；修剪在后台。幂等。 */
    fun init(context: Context) {
        IconStores.init(context)
        store.trimAsync()
    }

    /** 清掉全部图标缓存（内存 + 磁盘 + 失败记录）；清除浏览数据时调用。 */
    fun clearAll() = store.clear()

    fun normalize(host: String): String = host.trim().lowercase().trimEnd('.').removePrefix("www.")

    /** 只查内存，不会触发任何 IO。 */
    fun peek(host: String): ImageBitmap? = store.peek(host)

    suspend fun load(host: String): ImageBitmap? {
        if (host.isBlank()) return null
        return store.get(host) { fetch(host.trim()) }
    }

    private fun fetch(host: String): Bitmap? {
        Diag.log("favicon start $host")
        val bitmap = listOf(
            "https://$host/favicon.ico",
            "https://www.google.com/s2/favicons?sz=64&domain=$host",
        ).firstNotNullOfOrNull { url ->
            runCatching { HttpFetcher.get(url, options).bytes }.getOrNull()
                ?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
        Diag.log("favicon done $host -> ${bitmap != null}")
        return bitmap
    }
}

@Composable
fun rememberFavicon(url: String): ImageBitmap? {
    val host = remember(url) { hostOf(url) }
    // 内存里已有就第一帧直接用，不经过「默认图标 → 真图标」的闪换
    var icon by remember(host) { mutableStateOf(FaviconLoader.peek(host)) }
    LaunchedEffect(host) {
        if (icon == null) {
            icon = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FaviconLoader.load(host)
            }
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

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TARGET_PX = 128
    private const val ACCEPT = "image/avif,image/webp,image/png,image/*,*/*;q=0.8"

    // 脚本 @icon 也走三级缓存：重启后不必为每个脚本重新联网取图
    private val store = IconStore(subdir = "script_icons", ttlMs = 14L * 24 * 60 * 60 * 1000, maxFiles = 400, concurrency = 4)

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
            // data: 图标就在地址里，直接解码，不进磁盘缓存
            val image = if (url.startsWith("data:", ignoreCase = true)) {
                decodeDataUri(url)?.asImageBitmap()
            } else {
                store.get(url) { fetchIcon(url) }
            }
            if (image != null) return image
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

    private fun download(url: String, referer: String?): Fetch =
        try {
            val r = HttpFetcher.get(
                url,
                HttpFetcher.Options(
                    connectTimeoutMs = 5000,
                    readTimeoutMs = 6000,
                    maxBytes = MAX_BYTES,
                    accept = ACCEPT,
                    referer = referer,
                ),
            )
            Fetch(decode(r.bytes), r.code)
        } catch (e: HttpFetcher.HttpStatusException) {
            Fetch(null, e.code)
        } catch (e: Exception) {
            Fetch(null, -1)
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
