package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.util.Diag
import android.content.Context
import android.webkit.WebView
import java.io.File
import dev.dsh.miuixbrowser.download.Downloader

/**
 * 清理应用缓存，范围与系统「应用详情 → 清除缓存」一致：内部缓存目录 + 外部缓存目录
 * （含 WebView 的网页缓存）。不碰设置、收藏、历史、脚本、Cookie、已下载的文件。
 *
 * 例外（有用的缓存保留）：
 * - 下载列表里仍存在的任务（进行中 / 暂停 / 失败可重试）对应的分片文件；
 * - 手动清理时，最近 10 分钟内改动过的 mafari_ 临时文件（可能是正在抽取的 blob）。
 *
 * 冷启动清理发生在任何 WebView 创建之前：先把缓存目录里的内容整体「改名」移走（瞬间完成），
 * 再在后台线程删除，不拖慢启动，也不会和 WebView 同时读写缓存。
 */
object CacheCleaner {

    private val dlPattern = Regex("^mafari_dl_(\\d+)\\.(part|seg)$")
    private const val MANUAL_MIN_AGE_MS = 10 * 60_000L
    private const val TRASH_DIR = "cache_trash"

    /** WebView 自己管理的缓存目录：运行中不直接删文件，改用 WebView.clearCache */
    private val webViewDirs = setOf("WebView", "org.chromium.android_webview")

    @Volatile
    private var coldStartDone = false

    /** 冷启动清理：每个进程一次，必须在 setContent / 创建 WebView 之前调用 */
    fun runOnColdStart(context: Context) {
        if (coldStartDone) return
        coldStartDone = true
        val app = context.applicationContext
        val trash = File(app.dataDir, TRASH_DIR)
        // 先清掉上次没删完的垃圾桶
        val startedAt = System.currentTimeMillis()
        runCatching {
            trash.mkdirs()
            // 内部缓存：整体改名移到同一文件系统下的垃圾桶（不依赖删除速度）
            app.cacheDir.listFiles()?.forEach { f ->
                val target = File(trash, "${System.nanoTime()}_${f.name}")
                if (!f.renameTo(target)) deleteRecursively(f)
            }
        }
        Thread {
            runCatching { trash.listFiles()?.forEach { deleteRecursively(it) } }
            // 外部缓存目录 WebView 不用，直接在后台删
            runCatching { app.externalCacheDirs.filterNotNull().forEach { d -> d.listFiles()?.forEach { deleteRecursively(it) } } }
            Diag.log("cache cleanup on cold start done in ${System.currentTimeMillis() - startedAt}ms")
        }.start()
    }

    /** 与「设置 → 清理缓存」完全一致的一键清理（菜单里的「清理缓存」也走这里）：返回释放的字节数 */
    suspend fun cleanAll(context: Context): Long {
        val clearWeb: () -> Unit = {
            // 没用过 WebView（Gecko 默认）就不为了清缓存去创建一个：那会加载整个 Chromium
            if (dev.dsh.miuixbrowser.engine.EngineSupport.webViewNeeded(context)) {
                runCatching { WebView(context.applicationContext).apply { clearCache(true); destroy() } }
            }
            if (dev.dsh.miuixbrowser.engine.EngineSupport.GECKO_BUNDLED) dev.dsh.miuixbrowser.engine.gecko.GeckoRuntimeHolder.clearCaches()
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cleanNow(context) { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) { clearWeb() } }
        }
    }

    /** 手动清理：返回释放的字节数。必须在主线程之外调用，内部会切回主线程清 WebView 缓存 */
    fun cleanNow(context: Context, clearWebViewCache: () -> Unit): Long {
        val app = context.applicationContext
        val before = sizeOf(app, manual = true)
        runCatching { clearWebViewCache() }
        for (root in roots(app)) {
            root.listFiles()?.forEach { f ->
                if (f.name in webViewDirs && root == app.cacheDir) return@forEach
                if (isProtected(f)) return@forEach
                deleteRecursively(f)
            }
        }
        return (before - sizeOf(app, manual = true)).coerceAtLeast(0L)
    }

    /** 当前可清理的大小（与系统应用详情页里的「缓存」口径一致，已排除受保护的文件） */
    fun reclaimableBytes(context: Context): Long = sizeOf(context.applicationContext, manual = true)

    private fun roots(app: Context): List<File> =
        (listOf(app.cacheDir) + app.externalCacheDirs.filterNotNull()).distinct()

    private fun sizeOf(app: Context, manual: Boolean): Long {
        var total = 0L
        for (root in roots(app)) {
            root.listFiles()?.forEach { f -> if (!isProtected(f)) total += lengthOf(f) }
        }
        return total
    }

    /** 仍被下载任务引用的分片 / 刚创建的 mafari_ 临时文件 */
    private fun isProtected(f: File): Boolean {
        if (!f.name.startsWith("mafari_")) return false
        if (System.currentTimeMillis() - f.lastModified() < MANUAL_MIN_AGE_MS) return true
        val id = dlPattern.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        val live = runCatching { Downloader.tasks.toList().map { it.id } }.getOrDefault(emptyList())
        return id in live
    }

    private fun lengthOf(f: File): Long =
        if (f.isDirectory) (f.listFiles()?.sumOf { lengthOf(it) } ?: 0L) else f.length()

    private fun deleteRecursively(f: File) {
        runCatching { f.deleteRecursively() }
    }
}
