/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import java.util.Locale

/**
 * X（原 Twitter）视频地址的判定规则（纯函数，无 Android 依赖，有单测）。
 *
 * X 网页端用 MSE 播放（`<video>` 是 blob:），背后请求 `video.twimg.com` 上的 HLS：
 *  - 主清单 `/ext_tw_video/<id>/pu/pl/<name>.m3u8`（带清晰度选择）；
 *  - 子清单 `/pu/pl/<codec>/<宽x高>/<name>.m3u8`（单档视频）和音频清单 `/pu/pl/mp4a/<码率>/<name>.m3u8`；
 *  - fMP4 的初始化段 / 分片（`.m4s`、`/init/`）。
 * 通用的 [MediaSniff.classify] 会把音频清单、初始化段当成视频、把每个子清单单独列出来，所以这里补几条 X 专用规则。
 * 另外，接口 JSON 里带 `video_info.variants`（各档 mp4 直链 + 主清单），由 `mafari_sniff.js` 读出来上报，
 * 这里负责给这些直链生成列表里的清晰度标签。
 */
object XVideo {

    const val CDN_HOST = "video.twimg.com"

    /** 地址在 X 的视频 CDN 上 */
    fun isXMedia(url: String): Boolean = MediaSniff.hostOf(url) == CDN_HOST

    private fun pathOf(url: String): String {
        val rest = url.substringAfter("://", "")
        return "/" + rest.substringBefore('?').substringBefore('#').substringAfter('/', "")
    }

    private fun extOf(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /**
     * X 视频 CDN 上不是「一条能放的视频」的地址：音频清单（`/mp4a/`）、fMP4 初始化段（`/init/`，
     * 以及清单目录 `/pl/` 下的 `.mp4`）。WebView 的请求拦截拿不到长度，不能靠 [MediaSniff.MIN_FILE_BYTES] 挡掉它们。
     */
    fun isNoise(url: String): Boolean {
        if (!isXMedia(url)) return false
        val path = pathOf(url)
        if (path.contains("/mp4a/") || path.contains("/init/")) return true
        return extOf(path) == "mp4" && path.contains("/pl/")
    }

    /** 子清单：`/pl/` 后面还有目录（`/pl/avc1/720x1280/x.m3u8`），只是主清单里的一档 */
    fun isVariantPlaylist(url: String): Boolean {
        if (!isXMedia(url)) return false
        val path = pathOf(url)
        if (extOf(path) != "m3u8") return false
        return path.substringAfter("/pl/", "").contains('/')
    }

    /** 主清单：X 的 m3u8 里不是子清单的那种（带清晰度选择） */
    fun isMasterPlaylist(url: String): Boolean =
        isXMedia(url) && extOf(pathOf(url)) == "m3u8" && !isVariantPlaylist(url)

    private val MEDIA_ID = Regex("/(?:ext_tw_video|amplify_video|dm_video)/(\\d+)/")

    /** 同一条视频的各个地址共用的媒体 id（路径里的数字）；`tweet_video`（GIF）这类没有则 null */
    fun mediaKey(url: String): String? = MEDIA_ID.find(pathOf(url))?.groupValues?.get(1)

    private val RES = Regex("(\\d{3,4})x(\\d{3,4})")

    /**
     * 列表里的清晰度标签：「720×1280 · 2.2 Mbps」。分辨率取自地址路径（没有就省略），码率来自接口（[bitrate] 单位 bps，未知传 0）。
     * 不是 X 的地址、或两样都没有时返回空串。
     */
    fun label(url: String, bitrate: Long = 0): String {
        if (!isXMedia(url)) return ""
        val parts = ArrayList<String>(2)
        RES.find(pathOf(url))?.let { parts += "${it.groupValues[1]}×${it.groupValues[2]}" }
        if (bitrate >= 1_000_000) parts += String.format(Locale.ROOT, "%.1f Mbps", bitrate / 1_000_000.0)
        else if (bitrate > 0) parts += "${bitrate / 1000} kbps"
        return parts.joinToString(" · ")
    }
}
