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
 *  3. 删掉 META-INF 里的签名（改过内容签名必然失效）。
 * 其它拓展原样安装，不受影响。
 *
 * 注意：改过的包没有 Mozilla 签名，需要内核允许未签名拓展（nightly 默认允许）。
 */
object FloccusPatcher {
    private const val SHIM_ASSET = "mafari-bookmarks-shim.js"
    private const val SHIM_NAME = "mafari-bookmarks-shim.js"

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
