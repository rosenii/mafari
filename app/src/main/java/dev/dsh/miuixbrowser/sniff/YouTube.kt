/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube 视频地址解析（纯逻辑，无 Android 依赖，有单测；网络请求在 [YouTubeResolver]）。
 *
 * YouTube 网页端用 MSE 播放（`<video>` 是 blob:）且媒体流要带 PO Token / 签名，请求拦截嗅探不到可播的地址。
 * 做法：从页面网址取出视频 id，改用 YouTube 其他客户端（无需 PO Token 的 visionOS / Android VR，以及 iOS 的 HLS）
 * 调 innertube 的 `player` 接口，直接拿到媒体流地址，列进嗅探列表，由原生播放器播放。
 *
 * 这些客户端的参数 / 限制由 YouTube 单方面决定，会不定期失效（版本被拒、开始要求 PO Token 等），
 * 所以客户端表 [CLIENTS] 单独放在最前面，失效时只改这张表；按顺序逐个尝试，第一个拿到可播流的为准。
 */
object YouTube {

    /** [SniffedMedia.source] 的前缀：本页由 YouTube 解析出来的条目，换视频时按它整体清掉 */
    const val SOURCE_PREFIX = "youtube:"

    const val ORIGIN = "https://www.youtube.com"
    const val PLAYER_ENDPOINT = "$ORIGIN/youtubei/v1/player?prettyPrint=false"

    /** innertube 客户端描述。[extra] 是 context.client 里除通用字段外的附加字段（原样写入）。 */
    class Client(
        val name: String,
        val version: String,
        val clientId: Int,
        val userAgent: String,
        val extra: Map<String, Any>,
        /** context.client 里是否带 userAgent 字段（visionOS 要带） */
        val userAgentInContext: Boolean = false,
        /** 请求带不带页面里 youtube.com 的 Cookie（登录 / 受信任会话能放出更多格式；其余客户端带了反而可能被拒） */
        val withCookies: Boolean = false,
    )

    /** 按优先级排列：能否使用以 YouTube 当前的放行情况为准（见类注释）。 */
    val CLIENTS: List<Client> = listOf(
        // visionOS：yt-dlp 当前默认的免 PO Token 客户端（「为儿童制作」的视频不可用）
        Client(
            name = "VISIONOS", version = "1.02", clientId = 101,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            extra = mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471"),
            userAgentInContext = true,
        ),
        // Android VR：免 PO Token 的老牌客户端，近期对部分流有选择性拒绝，放在第二位兜底（「为儿童制作」的视频不可用）
        Client(
            name = "ANDROID_VR", version = "1.65.10", clientId = 28,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extra = mapOf(
                "deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android",
                "osVersion" to "12L", "androidSdkVersion" to 32,
            ),
        ),
        // iOS：主要提供 HLS 清单（点播通常可放，直播 30 秒后要 PO Token）
        Client(
            name = "IOS", version = "21.26.4", clientId = 5,
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra = mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        ),
        // 网页端（Safari UA）：只取 HLS 清单（HLS 不要 PO Token；2026.07 起只有登录 / 受信任会话才会返回，所以带上页面 Cookie）
        Client(
            name = "WEB", version = "2.20260708.00.00", clientId = 1,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)",
            extra = emptyMap(), withCookies = true,
        ),
        // 手机网页端：同样只取 HLS
        Client(
            name = "MWEB", version = "2.20260708.05.00", clientId = 2,
            userAgent = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
            extra = emptyMap(), withCookies = true,
        ),
    )

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * 从页面网址取 YouTube 视频 id；不是 YouTube 视频页（首页 / 搜索 / 频道 / 播放列表页等）返回 null。
     * 支持：watch?v=、youtu.be/、shorts/、live/、embed/、v/，以及 m. / music. / www. 子域和 youtube-nocookie.com。
     */
    fun videoId(url: String): String? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val host = MediaSniff.hostOf(url)
        val path = rest.substringBefore('?').substringBefore('#').substringAfter('/', "").trim('/')
        val query = rest.substringBefore('#').substringAfter('?', "")
        val candidate: String? = when {
            host == "youtu.be" -> path.substringBefore('/')
            isYouTubeHost(host) -> {
                val first = path.substringBefore('/')
                when (first) {
                    "watch" -> query.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                    "shorts", "live", "embed", "v" -> path.substringAfter('/', "").substringBefore('/')
                    else -> null
                }
            }
            else -> null
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

    /** 一个解析出的可播放流 */
    data class Stream(
        val url: String,
        /** hls / mp4 / webm */
        val type: String,
        val label: String,
        val size: Long = -1,
        val durationMs: Long = -1,
    )

    /** 解析结果：[streams] 为空时 [reason] 是 playabilityStatus 给的原因（登录 / 年龄 / 地区 / 儿童内容等）。 */
    data class Parsed(val streams: List<Stream>, val title: String = "", val reason: String = "")

    fun playerBody(videoId: String, client: Client): String {
        val c = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", "en")
            .put("gl", "US")
            .put("utcOffsetMinutes", 0)
        client.extra.forEach { (k, v) -> c.put(k, v) }
        if (client.userAgentInContext) c.put("userAgent", client.userAgent)
        return JSONObject()
            .put("context", JSONObject().put("client", c))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
            .toString()
    }

    /**
     * 解析 player 接口的 JSON。只收播放器能直接放的：
     *  - hlsManifestUrl（自适应清晰度，优先）；
     *  - streamingData.formats 里自带 url 的合流（视频 + 音频）mp4 / webm，如 itag 18（360p）、22（720p）。
     * adaptiveFormats 是音视频分离的，原生播放器不能合并，不收；带 signatureCipher 没有 url 的跳过。
     */
    fun parse(json: String): Parsed {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return Parsed(emptyList(), reason = "bad json")
        val title = root.optJSONObject("videoDetails")?.optString("title").orEmpty()
        val ps = root.optJSONObject("playabilityStatus")
        val status = ps?.optString("status").orEmpty()
        if (status != "OK") {
            val reason = ps?.optString("reason").orEmpty().ifBlank { status.ifBlank { "unknown" } }
            return Parsed(emptyList(), title, reason)
        }
        val sd = root.optJSONObject("streamingData") ?: return Parsed(emptyList(), title, "no streamingData")
        val lengthMs = root.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull()?.let { it * 1000 } ?: -1L
        val out = ArrayList<Stream>()
        sd.optString("hlsManifestUrl").takeIf { it.startsWith("http", true) }?.let {
            val live = root.optJSONObject("videoDetails")?.optBoolean("isLive") == true
            out += Stream(it, "hls", "HLS", durationMs = if (live) MediaSniff.DURATION_LIVE else lengthMs)
        }
        val formats: JSONArray = sd.optJSONArray("formats") ?: JSONArray()
        val muxed = ArrayList<Pair<Int, Stream>>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val url = f.optString("url")
            if (!url.startsWith("http", true)) continue
            val mime = f.optString("mimeType").substringBefore(';').trim().lowercase()
            val type = when (mime) { "video/mp4" -> "mp4"; "video/webm" -> "webm"; else -> continue }
            val height = f.optInt("height", 0)
            val q = f.optString("qualityLabel").ifBlank { if (height > 0) "${height}p" else "" }
            val dur = f.optString("approxDurationMs").toLongOrNull() ?: lengthMs
            val label = listOf(q, type.uppercase()).filter { it.isNotBlank() }.joinToString(" · ")
            muxed += height to Stream(url, type, label, f.optString("contentLength").toLongOrNull() ?: -1, dur)
        }
        out += muxed.sortedByDescending { it.first }.map { it.second }
        return Parsed(out, title)
    }
}
