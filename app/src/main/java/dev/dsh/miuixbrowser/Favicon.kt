package dev.dsh.miuixbrowser

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 抓网站 favicon。
 *  - 按规整后的域名（小写、去 www.）合并：同一域名正在请求时，后续调用共享同一个任务（调用方滚走取消也不会打断下载）；
 *  - 三级缓存：内存 → 磁盘（filesDir/favicons，文件名是域名的哈希）→ 网络；磁盘图标 14 天内直接用，过期后联网刷新，刷新失败仍用旧图；
 *  - 全局最多 4 个并发下载，冷启动时一百多个收藏不会同时抢网络；
 *  - 失败按次数退避（1 分钟 → 5 分钟 → 30 分钟 → 2 小时封顶），成功后清零。
 * 磁盘缓存放 filesDir 而不是 cacheDir：冷启动清缓存（[CacheCleaner]）会把 cacheDir 清掉，图标就白存了。
 */
object FaviconLoader {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    private class Failure(val count: Int, val at: Long)
    private val failures = ConcurrentHashMap<String, Failure>()
    private val backoffMs = longArrayOf(60_000L, 5 * 60_000L, 30 * 60_000L, 2 * 60 * 60_000L)

    private val inflight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)

    private const val DISK_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val DISK_MAX_FILES = 600

    @Volatile
    private var dir: File? = null

    /** 在 Application.onCreate 里调用一次：确定磁盘缓存目录，并在后台修剪过多的旧文件。幂等。 */
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.applicationContext.filesDir, "favicons").also { it.mkdirs() }
        dir = d
        scope.launch {
            runCatching {
                val files = d.listFiles() ?: return@runCatching
                if (files.size > DISK_MAX_FILES) {
                    files.sortedBy { it.lastModified() }.take(files.size - DISK_MAX_FILES).forEach { it.delete() }
                }
            }
        }
    }

    /** 清掉全部图标缓存（内存 + 磁盘 + 失败记录）；清除浏览数据时可调用。 */
    fun clearAll() {
        cache.clear()
        failures.clear()
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
    }

    fun normalize(host: String): String = host.trim().lowercase().trimEnd('.').removePrefix("www.")

    /** 只查内存，不会触发任何 IO：首帧就有图标可画，不闪默认图标。 */
    fun peek(host: String): ImageBitmap? = cache[normalize(host)]

    suspend fun load(host: String): ImageBitmap? {
        val key = normalize(host)
        if (key.isEmpty()) return null
        cache[key]?.let { return it }
        failures[key]?.let { f ->
            if (System.currentTimeMillis() - f.at < backoffMs[minOf(f.count - 1, backoffMs.size - 1)]) return null
        }
        // 合并：同一域名同时只跑一个任务
        val fresh = scope.async(start = CoroutineStart.LAZY) { produce(key, host.trim()) }
        val existing = inflight.putIfAbsent(key, fresh)
        val task = if (existing != null) {
            fresh.cancel()
            existing
        } else {
            fresh.invokeOnCompletion { inflight.remove(key, fresh) }
            fresh.also { it.start() }
        }
        return task.await()
    }

    private fun fileOf(key: String): File? {
        val d = dir ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }
        return File(d, "$hash.png")
    }

    private fun readDisk(file: File?, allowStale: Boolean): Bitmap? {
        if (file == null || !file.exists()) return null
        if (!allowStale && System.currentTimeMillis() - file.lastModified() > DISK_TTL_MS) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    private suspend fun produce(key: String, host: String): ImageBitmap? {
        val file = fileOf(key)
        readDisk(file, allowStale = false)?.let { b ->
            return b.asImageBitmap().also { cache[key] = it; failures.remove(key) }
        }
        Diag.log("favicon start $host")
        val bitmap = gate.withPermit {
            runCatching {
                var result: Bitmap? = null
                for (url in listOf(
                    "https://$host/favicon.ico",
                    "https://www.google.com/s2/favicons?sz=64&domain=$host",
                )) {
                    result = fetch(url)
                    if (result != null) break
                }
                result
            }.getOrNull()
        }
        Diag.log("favicon done $host -> ${bitmap != null}")
        if (bitmap != null) {
            runCatching {
                file?.outputStream()?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            return bitmap.asImageBitmap().also { cache[key] = it; failures.remove(key) }
        }
        // 联网失败：磁盘上有过期的旧图就继续用它，不要退回默认图标
        readDisk(file, allowStale = true)?.let { b ->
            return b.asImageBitmap().also { cache[key] = it }
        }
        val prev = failures[key]?.count ?: 0
        failures[key] = Failure(prev + 1, System.currentTimeMillis())
        return null
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
