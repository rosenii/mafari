/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/**
 * 把合并好的 TS 转封装成 MP4：只换容器、不重新编码，速度基本是读写一遍文件的时间。
 * 用系统的 MediaExtractor + MediaMuxer，不引入 ffmpeg。
 *
 * 只处理 H.264 / H.265 视频 + AAC 音频；出现其它音视频编码（MP3、AC3、E-AC3…）、没有视频轨、或任何一步出错
 * 都返回 false 并清掉半成品，调用方保留原来的 .ts，不会丢数据。字幕 / ID3 之类的元数据轨直接丢弃。
 */
internal object HlsRemux {
    private const val BUF_SIZE = 8 * 1024 * 1024

    /** 时间戳往回跳超过这个值，认为是 EXT-X-DISCONTINUITY 造成的重置，接着前面的时间往后排 */
    private const val JUMP_BACK_US = 2_000_000L

    /** @param onProgress 0f..1f，按已读字节占源文件的比例估算 */
    suspend fun tsToMp4(src: File, dst: File, onProgress: (Float) -> Unit): Boolean {
        runCatching { dst.delete() }
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        var ok = false
        try {
            ex.setDataSource(src.absolutePath)

            val picked = ArrayList<Pair<Int, MediaFormat>>()
            var hasVideo = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime == MediaFormat.MIMETYPE_VIDEO_AVC || mime == MediaFormat.MIMETYPE_VIDEO_HEVC -> { hasVideo = true; picked += i to f }
                    mime == MediaFormat.MIMETYPE_AUDIO_AAC -> picked += i to f
                    mime.startsWith("video/") || mime.startsWith("audio/") -> return false // 不支持的编码：别硬转，免得丢声音 / 画面
                    else -> {}
                }
            }
            if (!hasVideo) return false

            val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            val map = HashMap<Int, Int>()
            for ((i, f) in picked) {
                map[i] = m.addTrack(f)
                ex.selectTrack(i)
            }
            m.start()
            started = true

            val buf = ByteBuffer.allocateDirect(BUF_SIZE)
            val info = MediaCodec.BufferInfo()
            val total = src.length().coerceAtLeast(1L).toFloat()
            var base = -1L
            var shiftUs = 0L
            var maxLast = 0L
            var read = 0L
            var count = 0
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val ti = ex.sampleTrackIndex
                val out = map[ti]
                if (out != null) {
                    val t = ex.sampleTime
                    if (base < 0) base = t
                    var pts = t - base + shiftUs
                    if (pts < maxLast - JUMP_BACK_US) {
                        shiftUs += maxLast - pts
                        pts = t - base + shiftUs
                    }
                    if (pts < 0) pts = 0
                    if (pts > maxLast) maxLast = pts
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, pts, flags)
                    m.writeSampleData(out, buf, info)
                    count++
                }
                read += size
                ex.advance()
                if (count % 256 == 0) {
                    currentCoroutineContext().ensureActive()
                    onProgress((read / total).coerceIn(0f, 1f))
                }
            }
            if (count == 0) return false
            m.stop()
            started = false
            m.release()
            mux = null
            ok = dst.length() > 0
            return ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { ex.release() }
            mux?.let { m ->
                if (started) runCatching { m.stop() }
                runCatching { m.release() }
            }
            if (!ok) runCatching { dst.delete() }
        }
    }
}
