/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

/**
 * X（Twitter）视频的地址规则（纯函数，无 Android 依赖，有单测）。
 *
 * X 的视频都在 video.twimg.com：
 *  - 整段 mp4：`/ext_tw_video/<id>/pu/vid/avc1/1280x720/<hash>.mp4`（推文接口里的 variants）；
 *  - HLS 主清单：`/ext_tw_video/<id>/pu/pl/<hash>.m3u8`；各清晰度的子清单 `/pl/avc1/1280x720/<hash>.m3u8`；
 *    音频单独一条 `/pl/mp4a/128000/<hash>.m3u8`；
 *  - HLS 的 fMP4 初始化段 / 分片：`/vid/avc1/0/0/1280x720/<hash>.mp4`、`/vid/avc1/0/3000/1280x720/<hash>.m4s`（后者本来就被排除）；
 *  - GIF：`/tweet_video/<name>.mp4`。
 * 请求拦截只看地址，所以这里把「不能单独播的」（纯音频清单 / 初始化段）认出来排掉，
 * 并让主清单顶掉同一个视频的子清单，列表里一个视频只留一条自适应 HLS。
 */
object XVideo {

    /** 初始化段 / 分片的路径形态：…/<起始 ms>/<结束 ms>/<宽>x<高>/<hash>.mp4 */
    private val SEGMENT_MP4 = Regex("""/\d+/\d+/\d+x\d+/[^/]+\.mp4$""")
    private val RES = Regex("""/(\d{2,4})x(\d{2,4})(?:/|$)""")
    private val KEY = Regex("""^/([a-z_]*video)/(\d+)/""")

    const val ROLE_NONE = 0
    const val ROLE_MASTER = 1
    const val ROLE_VARIANT = 2

    fun isHost(host: String): Boolean =
        host == "video.twimg.com" || (host.startsWith("video-") && host.endsWith(".twimg.com"))

    /** 热路径先用这个便宜的判断挡掉绝大多数地址 */
    fun isXVideoUrl(url: String): Boolean = url.contains("twimg.com") && isHost(MediaSniff.hostOf(url))

    /** 不能单独播的：纯音频清单 / 音频段、HLS 的 fMP4 初始化段（[path] 已小写、不含 query） */
    fun isNonPlayablePath(path: String): Boolean =
        path.contains("/aud/") || path.contains("/mp4a/") || SEGMENT_MP4.containsMatchIn(path)

    private fun pathOf(url: String): String = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "").let { "/$it" }.lowercase()

    /** HLS 清单的角色：主清单 `/pl/<hash>.m3u8`，子清单 `/pl/<codec>/<分辨率>/<hash>.m3u8` */
    fun hlsRole(url: String): Int {
        if (!isXVideoUrl(url)) return ROLE_NONE
        val path = pathOf(url)
        if (!path.endsWith(".m3u8")) return ROLE_NONE
        val rest = path.substringAfter("/pl/", "")
        return when {
            rest.isEmpty() -> ROLE_NONE
            '/' in rest -> ROLE_VARIANT
            else -> ROLE_MASTER
        }
    }

    /** 同一个视频的标识（`ext_tw_video/<id>`），主清单和它的子清单相同；认不出返回 null */
    fun key(url: String): String? =
        KEY.find(pathOf(url))?.let { it.groupValues[1] + "/" + it.groupValues[2] }

    /** 列表里显示的名字（与语言无关）：`X · 1280×720` / `X · HLS` / `X · GIF`；不是 X 的视频返回 null */
    fun label(url: String, type: String): String? {
        if (!isXVideoUrl(url)) return null
        val path = pathOf(url)
        if (path.startsWith("/tweet_video/")) return "X · GIF"
        val res = RES.find(path)?.let { "${it.groupValues[1]}×${it.groupValues[2]}" }
        return when {
            type == "hls" -> if (res != null) "X · HLS $res" else "X · HLS"
            res != null -> "X · $res"
            else -> "X · " + type.uppercase()
        }
    }
}
