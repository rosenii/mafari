/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import java.net.URLDecoder

/**
 * YouTube 视频嗅探（纯函数 + 一个收集器，无 Android 依赖，有单测）。
 *
 * YouTube 网页用 MSE 播放自适应流：视频和音频是两条独立的 `googlevideo.com/videoplayback` 地址，页面里没有 m3u8 / mp4。
 * 这里不破解签名，只认页面自己发出的 videoplayback 请求（签名 / n 参数页面已经处理好）：
 *  1. [parse]：从地址里读 mime / itag / dur / clen，去掉分片参数（range / rn / rbuf），得到整条流的地址；
 *  2. [Collector]：按「同一个视频」（ei / id）把视频流和音频流配成一对，清晰度更高的后到时升级；
 *  3. 播放器用两个 ProgressiveMediaSource 合并播放（见 MafariPlayer）。
 *
 * 不认的情况（返回 null，原因见 [unsupportedReason]）：
 *  - SABR（`sabr=1`）/ UMP（`ump=1`）：YouTube 正在把网页播放器切到 SABR，没有逐格式的直链，只能 POST 二进制请求，这里取不到；
 *  - 直播 / 分段流（`live=1`、`sq=`、`otf=1`）：每个请求只是一小段。
 */
object YouTubeSniff {

    enum class Kind { MUXED, VIDEO, AUDIO }

    data class Stream(
        /** 同一个视频的标识：ei（同一次播放器响应里所有格式共用）→ id → 固定值 */
        val groupKey: String,
        val itag: Int,
        val kind: Kind,
        /** video/mp4、video/webm、audio/mp4、audio/webm */
        val mime: String,
        /** 画面高度（像素），音频或不认识的 itag 为 0 */
        val height: Int,
        /** 音频档次，越大越好（只在同一个视频的多条音频里比较） */
        val audioRank: Int,
        val durationMs: Long,
        val contentLength: Long,
        /** 去掉分片参数后的整条流地址 */
        val url: String,
    )

    /** 带音频的整条流（视频 + 音频在一个文件里） */
    private val MUXED_ITAGS = setOf(5, 6, 17, 18, 22, 34, 35, 36, 37, 38, 43, 44, 45, 46, 59, 78)

    private val HEIGHTS = mapOf(
        // 带音频
        18 to 360, 22 to 720, 37 to 1080, 38 to 2160, 43 to 360, 44 to 480, 45 to 720, 46 to 1080,
        // H.264 视频
        160 to 144, 133 to 240, 134 to 360, 135 to 480, 136 to 720, 137 to 1080, 298 to 720, 299 to 1080,
        264 to 1440, 266 to 2160, 138 to 4320,
        // VP9
        278 to 144, 242 to 240, 243 to 360, 244 to 480, 247 to 720, 248 to 1080, 302 to 720, 303 to 1080,
        271 to 1440, 308 to 1440, 313 to 2160, 315 to 2160, 272 to 4320,
        // AV1
        394 to 144, 395 to 240, 396 to 360, 397 to 480, 398 to 720, 399 to 1080, 400 to 1440, 401 to 2160, 402 to 4320,
    )

    /** 音频：AAC 优先（兼容性最好），Opus 其次，低码率最后 */
    private val AUDIO_RANKS = mapOf(139 to 1, 249 to 2, 250 to 3, 251 to 4, 140 to 5, 141 to 6)

    /** 请求里带的分片参数：整条流不需要，留着会让服务器只回一小段 */
    private val DROP_PARAMS = setOf("range", "rn", "rbuf")

    fun isVideoPlayback(url: String): Boolean {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
        val host = MediaSniff.hostOf(url)
        if (!host.endsWith(".googlevideo.com")) return false
        return url.substringBefore('#').substringBefore('?').endsWith("/videoplayback")
    }

    private fun queryOf(url: String): List<String> =
        url.substringBefore('#').substringAfter('?', "").split('&').filter { it.isNotEmpty() }

    private fun valueMap(pairs: List<String>): Map<String, String> {
        val m = HashMap<String, String>()
        for (p in pairs) m.putIfAbsent(p.substringBefore('='), p.substringAfter('=', ""))
        return m
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** 这条 videoplayback 为什么取不到直链；可以用返回 null 的 [parse] 配合它打一行诊断日志 */
    fun unsupportedReason(url: String): String? {
        if (!isVideoPlayback(url)) return null
        val m = valueMap(queryOf(url))
        return when {
            m["sabr"] == "1" || "sabr" in m -> "sabr"
            m["ump"] == "1" -> "ump"
            m["live"] == "1" || m["source"] == "yt_live_broadcast" -> "live"
            "sq" in m || m["otf"] == "1" -> "segmented"
            "mime" !in m || "itag" !in m -> "no-format"
            else -> null
        }
    }

    /** 是 YouTube 的整条媒体流返回 [Stream]，否则 null（含取不到直链的各种情况）。 */
    fun parse(url: String): Stream? {
        if (unsupportedReason(url) != null || !isVideoPlayback(url)) return null
        val pairs = queryOf(url)
        val m = valueMap(pairs)
        val mime = decode(m["mime"] ?: return null).lowercase()
        val itag = m["itag"]?.toIntOrNull() ?: return null
        val kind = when {
            mime.startsWith("video/") -> if (itag in MUXED_ITAGS) Kind.MUXED else Kind.VIDEO
            mime.startsWith("audio/") -> Kind.AUDIO
            else -> return null
        }
        val clean = url.substringBefore('#').substringBefore('?') + "?" +
            pairs.filter { it.substringBefore('=') !in DROP_PARAMS }.joinToString("&")
        return Stream(
            groupKey = "yt:" + (m["ei"] ?: m["id"] ?: "-"),
            itag = itag,
            kind = kind,
            mime = mime,
            height = HEIGHTS[itag] ?: 0,
            audioRank = AUDIO_RANKS[itag] ?: 0,
            durationMs = m["dur"]?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1L,
            contentLength = m["clen"]?.toLongOrNull() ?: -1L,
            url = clean,
        )
    }

    /**
     * 一个页面里的 YouTube 流收集器（跟 [SniffList] 同生命周期，页面换了一起清掉）。
     * [add] 返回这个视频目前最好的一条可播放项（分离流配成对 / 带音频的整条流），还配不成对返回 null。
     */
    class Collector {
        private val video = HashMap<String, Stream>()
        private val audio = HashMap<String, Stream>()
        private val muxed = HashMap<String, Stream>()
        private var loggedReason: String? = null

        @Synchronized
        fun add(s: Stream): SniffedMedia? {
            val k = s.groupKey
            when (s.kind) {
                Kind.VIDEO -> if (s.height >= (video[k]?.height ?: -1)) video[k] = s
                Kind.AUDIO -> if (s.audioRank >= (audio[k]?.audioRank ?: -1)) audio[k] = s
                Kind.MUXED -> if (s.height >= (muxed[k]?.height ?: -1)) muxed[k] = s
            }
            return best(k)
        }

        private fun best(k: String): SniffedMedia? {
            val v = video[k]
            val a = audio[k]
            val m = muxed[k]
            return when {
                // 分离流（清晰度通常更高），音视频都到了才能播
                v != null && a != null && (m == null || v.height >= m.height) -> SniffedMedia(
                    url = v.url,
                    type = typeOf(v.mime),
                    host = MediaSniff.hostOf(v.url),
                    size = -1,
                    durationMs = maxOf(v.durationMs, a.durationMs),
                    audioUrl = a.url,
                    label = labelOf(v.height),
                    groupKey = k,
                    rank = v.height * 10 + a.audioRank,
                )
                m != null -> SniffedMedia(
                    url = m.url,
                    type = typeOf(m.mime),
                    host = MediaSniff.hostOf(m.url),
                    size = -1,
                    durationMs = m.durationMs,
                    label = labelOf(m.height),
                    groupKey = k,
                    rank = m.height * 10,
                )
                else -> null
            }
        }

        /** 第一次遇到某种「取不到直链」的原因时返回它（用来只打一次诊断日志），之后返回 null。 */
        @Synchronized
        fun firstUnsupported(reason: String): String? =
            if (loggedReason == null) reason.also { loggedReason = it } else null

        @Synchronized
        fun clear() {
            video.clear(); audio.clear(); muxed.clear(); loggedReason = null
        }

        private fun typeOf(mime: String) = if (mime.endsWith("webm")) "webm" else "mp4"

        private fun labelOf(height: Int) = if (height > 0) "YouTube · ${height}p" else "YouTube"
    }
}
