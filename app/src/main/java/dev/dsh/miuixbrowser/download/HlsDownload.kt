/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.download

import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * App 端的 m3u8（HLS）下载：取清单 → 主清单选最高码率 → 并行下载分片（AES-128 解密）→ 按序合并成一个文件。
 * 跑在内置下载器的任务里（前台服务），所以离开页面 / 切后台 / 关掉悬浮播放器都不影响。
 *
 * - 续传：分片按序号落盘（filesDir/downloads/<id>/hls/N.seg），失败 / 暂停后重进只补缺的分片；清单分片数变了就清空重来。
 * - 输出：fMP4（带 EXT-X-MAP）直接拼成 .mp4；TS 分片拼成 .ts 后，按设置用系统 MediaMuxer 转封装成 .mp4（不重新编码，编码不支持时保留 .ts）。
 * - 不支持：SAMPLE-AES / 其它加密、EXT-X-BYTERANGE 分片（直接报错，不会下出坏文件）。
 */
internal object HlsDownload {
    /** 任务的 mime 用它标记「这是 HLS 任务」；合并完成后会换成真实的 video/xxx */
    const val MIME_MARK = "application/x-mpegURL"

    private const val PARALLEL = 6
    private const val SEG_RETRY = 3
    /** 一轮下完后，对仍缺的分片再补几轮（网络抖动 / CDN 偶发失败不用整个任务重来） */
    private const val FINAL_ROUNDS = 2
    /** 一轮里累计失败这么多片就不再派新活，直接收尾（防止 403 / 链接过期时把所有分片都耗一遍重试） */
    private const val MAX_FAIL_PER_ROUND = 24

    fun isHls(task: DownloadTask): Boolean = task.mime.equals(MIME_MARK, ignoreCase = true)

    private class Seg(val index: Int, val url: String, val keyUrl: String?, val iv: ByteArray?)
    private class Playlist(val segs: List<Seg>, val mapUrl: String?)

    // ───────────────────────── 清单解析 ─────────────────────────

    private val ATTR = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    private fun attrs(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        ATTR.findAll(s).forEach { m[it.groupValues[1]] = it.groupValues[2].trim().trim('"') }
        return m
    }

    private fun resolve(base: String, ref: String): String = URL(URL(base), ref).toString()

    private fun hexIv(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun seqIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = (seq shr (8 * i)).toByte()
        return iv
    }

    /** 主清单：取码率最高的子清单；不是主清单返回 null */
    private fun pickVariant(base: String, text: String): String? {
        val lines = text.lines().map { it.trim() }
        var bestBw = -1L
        var best: String? = null
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue
            val bw = attrs(lines[i].substringAfter(':'))["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val uri = (i + 1 until lines.size).map { lines[it] }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (bw > bestBw) { bestBw = bw; best = resolve(base, uri) }
        }
        return best
    }

    private fun parseMedia(base: String, text: String): Playlist {
        var seq = 0L
        var method = "NONE"
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        var mapUrl: String? = null
        val segs = ArrayList<Seg>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(line.substringAfter(':'))
                    method = (a["METHOD"] ?: "NONE").uppercase()
                    keyUri = a["URI"]?.let { resolve(base, it) }
                    keyIv = a["IV"]?.let { hexIv(it) }
                    if (method != "NONE" && method != "AES-128") throw IllegalStateException(tr("不支持的加密方式：{0}", method))
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(line.substringAfter(':'))
                    if (a.containsKey("BYTERANGE")) throw IllegalStateException(tr("不支持按字节范围切分的 m3u8"))
                    mapUrl = a["URI"]?.let { resolve(base, it) }
                }
                line.startsWith("#EXT-X-BYTERANGE") -> throw IllegalStateException(tr("不支持按字节范围切分的 m3u8"))
                line.startsWith("#") -> {}
                else -> {
                    val aes = method == "AES-128" && keyUri != null
                    segs += Seg(segs.size, resolve(base, line), if (aes) keyUri else null, if (aes) (keyIv ?: seqIv(seq)) else null)
                    seq++
                }
            }
        }
        return Playlist(segs, mapUrl)
    }

    // ───────────────────────── 网络 ─────────────────────────

    private suspend fun fetchBytes(task: DownloadTask, url: String): ByteArray {
        val c = Downloader.connect(task, null, null, url)
        try {
            return Downloader.withConn(c) {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                c.inputStream.use { it.readBytes() }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        fun go(padding: String): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/$padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(data)
        }
        return try {
            go("PKCS5Padding")
        } catch (e: javax.crypto.BadPaddingException) {
            // 个别源的分片末尾没按 PKCS7 补齐：按无填充解，播放器能容忍尾部多几个字节
            if (data.size % 16 == 0) go("NoPadding") else throw e
        }
    }

    /**
     * 部分站点把 TS 分片伪装成图片（前面垫一段 PNG / JPEG / GIF / BMP 头），播放器识别不了。
     * 只有开头是这几种图片魔数、且头部 16KB 内能找到连续三个 188 字节间隔的 0x47 同步字节时才裁掉前缀，不碰正常分片。
     */
    private fun stripFakeImageHeader(f: File) {
        val n = minOf(f.length(), 16L * 1024).toInt()
        if (n < 400) return
        val head = ByteArray(n)
        java.io.RandomAccessFile(f, "r").use { it.readFully(head) }
        fun b(i: Int) = head[i].toInt() and 0xFF
        val image = (b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47) ||
            (b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) ||
            (b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38) ||
            (b(0) == 0x42 && b(1) == 0x4D)
        if (!image) return
        var off = -1
        for (i in 1 until n - 376) {
            if (b(i) == 0x47 && b(i + 188) == 0x47 && b(i + 376) == 0x47) { off = i; break }
        }
        if (off <= 0) return
        val out = File(f.path + ".strip")
        f.inputStream().use { i ->
            i.skip(off.toLong())
            out.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
        }
        if (!f.delete() || !out.renameTo(f)) { runCatching { out.delete() }; throw IllegalStateException(tr("无法写入文件")) }
    }

    private suspend fun fetchSeg(task: DownloadTask, seg: Seg, dest: File, tmp: File, keys: ConcurrentHashMap<String, ByteArray>, stripTs: Boolean) {
        var last: Exception? = null
        repeat(SEG_RETRY) { n ->
            try {
                val c = Downloader.connect(task, null, null, seg.url)
                try {
                    Downloader.withConn(c) {
                        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                    }
                } finally {
                    c.disconnect()
                }
                if (seg.keyUrl != null && seg.iv != null) {
                    val key = keys[seg.keyUrl] ?: fetchBytes(task, seg.keyUrl).also { keys[seg.keyUrl] = it }
                    tmp.writeBytes(decrypt(tmp.readBytes(), key, seg.iv))
                }
                if (stripTs) stripFakeImageHeader(tmp)
                if (!tmp.renameTo(dest)) throw IllegalStateException(tr("无法写入文件"))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                runCatching { tmp.delete() }
                delay(500L * (n + 1))
            }
        }
        throw last ?: IllegalStateException(tr("分片下载失败"))
    }

    // ───────────────────────── 主流程 ─────────────────────────

    /** 下完所有分片并合并成 data.part；返回合并后的文件（已标记 fetched、mime / 文件名已改成真实类型）。入库由调用方做 */
    suspend fun run(id: Long): File {
        val task = Downloader.tasks.firstOrNull { it.id == id } ?: throw CancellationException()
        val dir = Downloader.taskDir(Downloader.app, id).apply { mkdirs() }
        val hdir = File(dir, "hls").apply { mkdirs() }

        var url = task.url
        var text = String(fetchBytes(task, url), Charsets.UTF_8)
        repeat(3) {
            if (text.contains("#EXT-X-STREAM-INF")) {
                url = pickVariant(url, text) ?: throw IllegalStateException(tr("无法解析 m3u8 清单"))
                text = String(fetchBytes(task, url), Charsets.UTF_8)
            }
        }
        if (!text.contains("#EXTM3U")) throw IllegalStateException(tr("不是有效的 m3u8 清单"))
        val pl = parseMedia(url, text)
        if (pl.segs.isEmpty()) throw IllegalStateException(tr("m3u8 清单里没有分片"))

        // 续传依据：分片数没变才沿用已下载的分片
        val sig = File(hdir, "count")
        if (sig.exists() && sig.readText() != pl.segs.size.toString()) hdir.listFiles()?.forEach { it.delete() }
        sig.writeText(pl.segs.size.toString())

        val keys = ConcurrentHashMap<String, ByteArray>()
        val initFile = File(hdir, "init.bin")
        if (pl.mapUrl != null && !initFile.exists()) {
            val tmp = File(hdir, "init.tmp")
            tmp.writeBytes(fetchBytes(task, pl.mapUrl))
            tmp.renameTo(initFile)
        }

        fun segFile(i: Int) = File(hdir, "$i.seg")
        val total = pl.segs.size
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        pl.segs.forEach { s -> segFile(s.index).takeIf { it.exists() }?.let { done.incrementAndGet(); bytes.addAndGet(it.length()) } }

        var lastUi = 0L
        fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUi < 300) return
            lastUi = now
            val d = done.get()
            val b = bytes.get()
            val estTotal = if (d > 0) b * total / d else 0L
            Downloader.update(id) { it.copy(bytes = b, total = estTotal) }
            val progress = d.toFloat() / total
            DownloadService.instance?.update(task.name, progress, "${(progress * 100).toInt()}% · ${Downloader.sizeText(b)}", id)
        }
        report(force = true)

        val sem = Semaphore(PARALLEL)
        val stripTs = pl.mapUrl == null
        var failedCount = 0
        var lastErr: Exception? = null
        for (round in 0..FINAL_ROUNDS) {
            val todo = pl.segs.filter { !segFile(it.index).exists() }
            if (todo.isEmpty()) break
            if (round > 0) delay(1500L * round)
            val errs = ConcurrentHashMap<Int, Exception>()
            coroutineScope {
                todo.map { seg ->
                    async {
                        sem.withPermit {
                            currentCoroutineContext().ensureActive()
                            if (errs.size >= MAX_FAIL_PER_ROUND) return@withPermit
                            val dest = segFile(seg.index)
                            try {
                                fetchSeg(task, seg, dest, File(hdir, "${seg.index}.tmp"), keys, stripTs)
                                done.incrementAndGet()
                                bytes.addAndGet(dest.length())
                                report()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errs[seg.index] = e
                            }
                        }
                    }
                }.awaitAll()
            }
            failedCount = pl.segs.count { !segFile(it.index).exists() }
            lastErr = errs.values.firstOrNull() ?: lastErr
            if (failedCount == 0 || errs.size >= MAX_FAIL_PER_ROUND) break
        }
        if (failedCount > 0) {
            report(force = true)
            Downloader.persist(id)
            throw IllegalStateException(tr("{0} 个分片下载失败：{1}", failedCount, lastErr?.message ?: ""))
        }
        report(force = true)
        Downloader.persist(id)

        // 合并：fMP4（有 init 段）→ .mp4，其余 TS → .ts
        val part = File(dir, "data.part")
        runCatching { part.delete() }
        java.io.FileOutputStream(part).use { out ->
            val buf = ByteArray(256 * 1024)
            fun append(f: File) = f.inputStream().use { i ->
                while (true) {
                    val r = i.read(buf)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                }
            }
            if (pl.mapUrl != null) append(initFile)
            for (s in pl.segs) {
                currentCoroutineContext().ensureActive()
                append(segFile(s.index))
            }
            out.flush()
            out.fd.sync()
        }
        // TS → MP4：只换封装不重新编码；不支持的编码 / 出错时返回 false，原样保留 .ts
        var remuxed = false
        if (pl.mapUrl == null && Downloader.hlsToMp4) {
            val mp4 = File(dir, "data.mp4tmp")
            var lastNotify = 0L
            val ok = HlsRemux.tsToMp4(part, mp4) { p ->
                val now = System.currentTimeMillis()
                if (now - lastNotify >= 500) {
                    lastNotify = now
                    val pct = (p * 100).toInt()
                    DownloadService.instance?.update(task.name, p, tr("转封装为 MP4… {0}%", pct), id)
                }
            }
            // renameTo 会原子覆盖目标：失败时 part 仍是完整的 TS，不会丢数据
            if (ok && mp4.renameTo(part)) remuxed = true else runCatching { mp4.delete() }
        }
        val len = part.length()
        val isMp4 = pl.mapUrl != null || remuxed
        val ext = if (isMp4) "mp4" else "ts"
        val base = task.name.replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "").ifBlank { "video" }
        Downloader.update(id) { it.copy(name = "$base.$ext", mime = if (isMp4) "video/mp4" else "video/mp2t", total = len, bytes = len) }
        Downloader.markFetched(id)
        runCatching { hdir.deleteRecursively() }
        return part
    }
}
