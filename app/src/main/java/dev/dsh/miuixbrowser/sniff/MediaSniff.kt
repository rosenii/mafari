/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

/** 嗅探到的一个视频地址。[type]：hls / mp4 / webm / mkv / flv。 */
data class SniffedMedia(
    val url: String,
    val type: String,
    val host: String,
    /** 字节数，未知为 -1（Gecko 从响应头来，WebView 的请求拦截拿不到） */
    val size: Long = -1,
    /** 时长（毫秒）：-1 未知，[MediaSniff.DURATION_LIVE] 直播 / 无限时长。来自页面 `<video>` 或后台探测 */
    val durationMs: Long = -1,
    /** 页面里的 `<video>` 看起来是预览 / 装饰（循环、很小、不可见）：true 是，false 否，null 页面没报过 */
    val preview: Boolean? = null,
    /** 用户在页面里点过播放（页面报的 userActivation） */
    val userPlayed: Boolean = false,
    /**
     * 页面加载这条地址时实际带的 Referer / Origin（WebView 的请求拦截能读到请求头）。
     * iframe 里的播放器带的是播放器页自己的地址，不是顶层页——防盗链按它校验，播放时优先用它。空 = 不知道（Gecko / 脚本报的）。
     */
    val referer: String = "",
    val origin: String = "",
    /** 音频流地址：非空表示 [url] 只有画面，要和这条音频合并播放（YouTube 的自适应流，见 [YouTubeSniff]） */
    val audioUrl: String = "",
    /** 列表里显示的名字；空 = 用地址里的文件名 */
    val label: String = "",
    /** 同一个视频的多条候选（清晰度不同）共用的标识；空 = 不分组 */
    val groupKey: String = "",
    /** 同组候选的排序：数值大的更好，后到的更高才会替换已有那条 */
    val rank: Int = 0,
)

/**
 * 视频地址判定（纯函数，无 Android 依赖，有单测）。
 *
 * 只认「播放器能直接放的」：HLS 清单（.m3u8 / mpegurl）和整段文件（mp4 / webm / mkv / flv）。
 * 分片（.ts / .m4s / video/mp2t）、DASH（原生内核没带 dash 模块）、音频、图片一律不要。
 * 响应头可用时（Gecko 的 webRequest、页面脚本的 fetch / XHR）再用 Content-Type 补判没有后缀的地址。
 */
object MediaSniff {

    /** 总开关（设置里「嗅探网页视频」）；WebView 的请求拦截在热路径上，直接读这个静态值 */
    @Volatile var enabled: Boolean = true

    const val MAX_ITEMS = 12
    const val MAX_URL_CHARS = 4096

    /** 已知长度小于它的整段文件多半是广告 / 预览 / 动图，不弹（HLS 清单本来就小，不受限） */
    const val MIN_FILE_BYTES = 256L * 1024

    /** [SniffedMedia.durationMs] 的「直播 / 无限时长」 */
    const val DURATION_LIVE = -2L

    /** 自动悬浮播放：短于它的视频当作预览 / 封面循环，不自动播（列表里仍可手动点开） */
    const val AUTO_MIN_MS = 40_000L

    /** 自动悬浮播放：时长未知的整段文件小于它不自动播（短预览通常只有几百 KB 到一两 MB） */
    const val AUTO_MIN_FILE_BYTES = 2L * 1024 * 1024

    private val HLS_CT = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl",
        "audio/mpegurl", "audio/x-mpegurl",
    )

    /** video 类型里属于分片而不是整段视频的 */
    private val SEGMENT_CT = setOf("video/mp2t", "video/mpeg2t", "video/iso.segment", "video/x-mpegts", "video/vnd.dlna.mpeg-tts")

    private val FILE_EXTS = mapOf(
        "mp4" to "mp4", "m4v" to "mp4", "mov" to "mp4",
        "webm" to "webm", "mkv" to "mkv", "flv" to "flv",
    )

    private val CT_TYPES = mapOf(
        "video/mp4" to "mp4", "video/x-m4v" to "mp4", "video/quicktime" to "mp4",
        "video/webm" to "webm", "video/x-matroska" to "mkv", "video/x-flv" to "flv",
    )

    fun isHttp(url: String): Boolean =
        url.length <= MAX_URL_CHARS && (url.startsWith("http://", true) || url.startsWith("https://", true))

    /**
     * 返回类型，不是视频返回 null。
     * [contentType] 可带参数（`; charset=`）；[length] 未知传 -1；
     * [fromMediaElement]：地址来自页面里 `<video>` 的 currentSrc，没有后缀也没有响应头时按 mp4 处理。
     * [trusted]：地址来自页面脚本从站点接口里读到的正式视频地址（X 推文的 variants），跳过按地址形态猜「是不是初始化段」的排除。
     */
    fun classify(
        url: String,
        contentType: String? = null,
        length: Long = -1,
        fromMediaElement: Boolean = false,
        trusted: Boolean = false,
    ): String? {
        if (!isHttp(url)) return null
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (ct in SEGMENT_CT) return null
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext == "ts" || ext == "m4s") return null
        // X：纯音频清单 / HLS 的 fMP4 初始化段（也是 .mp4）不能单独播
        if (!trusted && XVideo.isXVideoUrl(url) && XVideo.isNonPlayablePath(path)) return null

        if (ext == "m3u8" || ext == "m3u" || ct in HLS_CT) return "hls"

        val type = FILE_EXTS[ext] ?: CT_TYPES[ct]
            ?: if (ct.startsWith("video/") || (fromMediaElement && (ct.isEmpty() || ct == "application/octet-stream"))) "mp4" else null
        if (type == null) return null
        if (length in 0 until MIN_FILE_BYTES) return null
        return type
    }

    fun hostOf(url: String): String {
        val rest = url.substringAfter("://", "")
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()
    }

    /** 列表里显示的名字：文件名（没有就用域名） */
    fun displayName(m: SniffedMedia): String {
<<<<<<< ours
        if (m.label.isNotBlank()) return m.label
=======
        XVideo.label(m.url, m.type)?.let { return it }
>>>>>>> theirs
        val name = m.url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return name.takeIf { it.isNotBlank() && it.length <= 48 } ?: m.host
    }

    /** 时长文字：65000 → 1:05，3725000 → 1:02:05；未知返回 null，直播返回 null（由界面显示「直播」） */
    fun formatDuration(ms: Long): String? {
        if (ms < 0) return null
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** 这一条能不能自动悬浮播放：排除页面标记的预览、短视频，以及时长 / 大小都说不清的小文件 */
    fun isAutoCandidate(m: SniffedMedia): Boolean {
        if (m.preview == true) return false
        if (m.userPlayed) return true // 用户自己点了播放：不拦
        return when {
            m.durationMs == DURATION_LIVE -> true
            m.durationMs >= 0 -> m.durationMs >= AUTO_MIN_MS
            m.type == "hls" -> true // 时长没探出来的 HLS 清单：放行（预览循环几乎都是 mp4 / webm）
            else -> m.size >= AUTO_MIN_FILE_BYTES
        }
    }

    /** 自动悬浮播放选哪一条：候选里已知时长最长的（正片优先），时长相同取先出现的；[skip] 是已经自动播过的地址 */
    fun pickAutoPlay(items: List<SniffedMedia>, skip: Set<String> = emptySet()): SniffedMedia? =
        items.filter { it.url.substringBefore('#') !in skip && isAutoCandidate(it) }
            .maxByOrNull { if (it.durationMs == DURATION_LIVE) Long.MAX_VALUE else it.durationMs }

    /**
     * HLS 清单里的时长（毫秒）：-1 不是媒体清单（没有 EXTINF）；[DURATION_LIVE] 没有 ENDLIST（直播 / 进行中）。
     * 主清单（含 EXT-X-STREAM-INF）请先用 [firstVariant] 取到子清单再算。
     */
    fun hlsDurationMs(text: String): Long {
        var sum = 0.0
        var any = false
        text.lineSequence().forEach { line ->
            if (line.startsWith("#EXTINF:")) {
                val v = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                if (v != null && v >= 0) { sum += v; any = true }
            }
        }
        if (!any) return -1
        return if (text.contains("#EXT-X-ENDLIST")) (sum * 1000).toLong() else DURATION_LIVE
    }

    /** 主清单里的第一条子清单地址（相对地址按 [base] 解析）；不是主清单返回 null */
    fun firstVariant(text: String, base: String): String? {
        if (!text.contains("#EXT-X-STREAM-INF")) return null
        val lines = text.lines()
        val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        val uri = lines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return runCatching { java.net.URI(base).resolve(uri).toString() }.getOrNull()
    }
}
