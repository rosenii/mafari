package dev.dsh.miuixbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 安装 Floccus 时的就地适配：GeckoView 没有 browser.bookmarks，所以安装前把 xpi 改一下——
 *  1. 加入 mafari-bookmarks-shim.js（assets 里那份），并让后台页 / 设置页先加载它；
 *  2. manifest 补上 nativeMessaging 权限，使垫片能通过 sendNativeMessage 找到 [BookmarksBridge]；
 *  3. 删掉 META-INF 里的签名（改过内容签名必然失效）；
 *  4. 瘦身：去掉 dist/node-tests/（Floccus 自带的测试包，约 26MB，只有 test.html 用）和全部 .map 源码映射，
 *     运行用不到；官方包解压后 62MB，去掉后约 15MB，在手机上安装 / 读取都快些。
 * 其它拓展原样安装，不受影响。
 *
 * 更新：官方更新包没有垫片，不能交给内核的 update；[latestRelease] / [downloadRelease] 去 GitHub 取最新正式版的
 * Firefox 构建包，再走同一条改包安装（同 id 覆盖安装即升级），见 GeckoExtensionStore.updateFloccus。
 *
 * 注意：改过的包没有 Mozilla 签名，需要内核允许未签名拓展（nightly 默认允许）。
 */
object FloccusPatcher {
    private const val SHIM_ASSET = "mafari-bookmarks-shim.js"
    private const val SHIM_NAME = "mafari-bookmarks-shim.js"
    private const val SIGN_PREF = "xpinstall.signatures.required"
    private const val RELEASES_API = "https://api.github.com/repos/floccusaddon/floccus/releases/latest"

    /** GitHub 上的一个 Floccus 正式版：[version] 已去掉前缀 v，[url] 是 Firefox 构建包（zip）的下载地址。 */
    data class Release(val version: String, val url: String)

    private fun flags(context: Context) = context.getSharedPreferences("mafari", Context.MODE_PRIVATE)

    /**
     * 改过的包没有 Mozilla 签名，内核默认只接受带签名的拓展（安装时拒绝，已装的在下次启动时也会被禁用）。
     * 所以只要装过改包版 Floccus，就把 Gecko 的 xpinstall.signatures.required 关掉（全局生效，所有拓展不再校验签名）。
     */
    fun allowUnsigned(context: Context, on: Boolean) {
        flags(context).edit().putBoolean("floccus_unsigned", on).apply()
        GeckoPrefs.set(SIGN_PREF, if (on) false else GeckoPrefs.CLEAR)
    }

    /** Gecko 运行时创建时调用：之前装过改包版就保持签名校验关闭。 */
    fun applyOnStartup(context: Context) {
        if (flags(context).getBoolean("floccus_unsigned", false)) GeckoPrefs.set(SIGN_PREF, false)
    }

    /**
     * 返回实际应当安装的 uri：是 Floccus 的 xpi 就返回改好的 file://，否则原样返回 [uri]。
     * 只有 file:// 或「链接里带 floccus」的 https 链接才会被检查（后者要先下载），任何失败都回退到原 uri。
     */
    fun prepare(context: Context, uri: String): String = runCatching {
        val dir = File(context.cacheDir, "floccus_patch").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val src: File = when {
            uri.startsWith("file://") -> File(uri.removePrefix("file://"))
            uri.startsWith("http", true) && uri.contains("floccus", true) -> download(uri, File(dir, "src.xpi"))
            else -> return uri
        }
        if (!isFloccus(src)) return uri
        val out = File(dir, "floccus-mafari.xpi")
        patch(context, src, out)
        Diag.log("floccus: patched xpi for bookmarks bridge")
        "file://" + out.absolutePath
    }.getOrElse {
        Diag.log("floccus: patch skipped: $it")
        uri
    }

    /**
     * GitHub 上 Floccus 最新「正式版」（releases/latest 不含预发布）的 Firefox 构建包；查不到 / 没有该包返回 null。
     * 阻塞网络调用：请在 IO 线程调用。
     */
    fun latestRelease(): Release? {
        return try {
            val c = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 mafari")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val body = try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().readText()
            } finally {
                c.disconnect()
            }
            val j = JSONObject(body)
            if (j.optBoolean("prerelease") || j.optBoolean("draft")) return null
            val version = j.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            val assets = j.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                // 形如 floccus-build-v5.13.0-firefox.zip；旧版也可能是 .xpi
                if (name.contains("firefox", true) && (name.endsWith(".zip", true) || name.endsWith(".xpi", true))) {
                    url = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                    if (url != null) break
                }
            }
            if (version.isEmpty() || url == null) null else Release(version, url)
        } catch (t: Throwable) {
            Diag.log("floccus: latest release lookup failed: $t")
            null
        }
    }

    /** [latest] 是否比 [current] 新（按 . 分段的数字比较，忽略 -beta 之类后缀）；[current] 读不出来时一律当作不更新。 */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        if (current.isBlank()) return false
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 把 [release] 下载到缓存目录（与 [prepare] 的临时目录分开，prepare 开头会清空自己的目录），
     * 并确认它确实是未打过补丁的 Floccus 包；返回文件，失败抛异常。阻塞：请在 IO 线程调用。
     */
    fun downloadRelease(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "floccus_update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = download(release.url, File(dir, "floccus-${release.version}-firefox.xpi"))
        val ok = runCatching { isFloccus(file) }.getOrDefault(false)
        if (!ok) {
            file.delete()
            error("下载的不是有效的 Floccus 包")
        }
        return file
    }

    private fun download(url: String, target: File): File {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 mafari")
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.use { i -> target.outputStream().use { i.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        return target
    }

    private fun manifestOf(zip: ZipFile): JSONObject? =
        zip.getEntry("manifest.json")?.let { JSONObject(zip.getInputStream(it).bufferedReader().readText()) }

    private fun isFloccus(file: File): Boolean = ZipFile(file).use { zip ->
        val m = manifestOf(zip) ?: return false
        val bss = m.optJSONObject("browser_specific_settings") ?: m.optJSONObject("applications")
        val id = bss?.optJSONObject("gecko")?.optString("id").orEmpty()
        id == BookmarksBridge.EXT_ID && zip.getEntry(SHIM_NAME) == null
    }

    private fun patch(context: Context, src: File, dst: File) {
        val shim = context.assets.open(SHIM_ASSET).use { it.readBytes() }
        ZipFile(src).use { zip ->
            ZipOutputStream(dst.outputStream().buffered()).use { out ->
                for (e in zip.entries()) {
                    val name = e.name
                    if (e.isDirectory || name.startsWith("META-INF/")) continue
                    if (name.startsWith("dist/node-tests/") || name.endsWith(".map")) continue
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    val patched = when {
                        name == "manifest.json" -> patchManifest(bytes)
                        name.endsWith(".html", true) -> patchHtml(name, bytes)
                        else -> bytes
                    }
                    out.putNextEntry(ZipEntry(name))
                    out.write(patched)
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry(SHIM_NAME))
                out.write(shim)
                out.closeEntry()
            }
        }
    }

    private fun patchManifest(bytes: ByteArray): ByteArray {
        val m = JSONObject(String(bytes, Charsets.UTF_8))
        val perms = m.optJSONArray("permissions") ?: JSONArray().also { m.put("permissions", it) }
        if ((0 until perms.length()).none { perms.optString(it) == "nativeMessaging" }) perms.put("nativeMessaging")
        // 后台若用 scripts 数组而不是 page，把垫片放在最前面
        m.optJSONObject("background")?.optJSONArray("scripts")?.let { scripts ->
            val next = JSONArray().put(SHIM_NAME)
            for (i in 0 until scripts.length()) next.put(scripts.get(i))
            m.getJSONObject("background").put("scripts", next)
        }
        return m.toString().toByteArray(Charsets.UTF_8)
    }

    /** 在页面里第一个 <script 之前插入垫片（路径按页面所在深度写成相对路径）。 */
    private fun patchHtml(path: String, bytes: ByteArray): ByteArray {
        val html = String(bytes, Charsets.UTF_8)
        val at = html.indexOf("<script", ignoreCase = true)
        if (at < 0) return bytes
        val depth = path.count { it == '/' }
        val rel = "../".repeat(depth) + SHIM_NAME
        val tag = "<script src=\"$rel\"></script>\n"
        val sb = ByteArrayOutputStream()
        sb.write(html.substring(0, at).toByteArray(Charsets.UTF_8))
        sb.write(tag.toByteArray(Charsets.UTF_8))
        sb.write(html.substring(at).toByteArray(Charsets.UTF_8))
        return sb.toByteArray()
    }
}
