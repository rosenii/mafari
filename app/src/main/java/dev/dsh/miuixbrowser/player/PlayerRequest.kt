/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** 同一个视频的另一个可播放地址（脚本嗅探到多个源 / 多个清晰度的独立地址时用）。 */
data class PlayerSource(val url: String, val label: String = "", val type: String = "")

/**
 * 一次播放请求：地址加上原网页带的请求头。
 * 原生请求直接带 Referer / UA / Cookie，没有跨域问题（脚本里绕 CORS 的 hls.js loader 不再需要）。
 */
data class PlayerRequest(
    val url: String,
    val title: String = "",
    val referer: String = "",
    val ua: String = "",
    val cookie: String = "",
    /** 提示类型：hls / mp4 / 空（按地址和响应嗅探） */
    val type: String = "",
    val startMs: Long = 0,
    /** 其他可切换的视频源（不含 [url] 本身也行，会自动补上）；空 = 只有一个源，界面不显示源列表 */
    val sources: List<PlayerSource> = emptyList(),
    /** 请求来自 Gecko 引擎的页面：下载时 Cookie 要从 Gecko 取，而不是 WebView 的 CookieManager */
    val gecko: Boolean = false,
    /** 从系统「打开方式」直接打开的本地视频（见 VideoOpenActivity）：一开始就全屏、返回即退出；没有网页可浮在上面，也没有下载 / 投屏 */
    val standalone: Boolean = false,
    /** 防盗链用的 Origin；空 = 由 [referer] 推出（浏览器对跨域媒体请求也会带） */
    val origin: String = "",
) {
    /** 本地文件（content:// / file://）：没有可下载的网络地址，也不能投屏 */
    val isLocal: Boolean get() = !url.startsWith("http://", true) && !url.startsWith("https://", true)

    /** 要附加到每个网络请求上的头（UA 单独走 setUserAgent，不放这里） */
    fun headers(): Map<String, String> = buildMap {
        if (referer.isNotBlank()) put("Referer", referer)
        val o = origin.ifBlank { originOf(referer) }
        if (o.isNotBlank() && !isLocal) put("Origin", o)
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 是不是 HLS：显式声明，或地址路径里带 .m3u8（查询串里带的不算，避免把 ?u=xxx.m3u8 之类误判）。 */
    fun isHls(): Boolean {
        if (type.equals("hls", true) || type.equals("m3u8", true)) return true
        if (type.isNotBlank()) return false
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    /**
     * 完整的源列表：主地址在最前，[sources] 里与它重复的去掉，其余按地址去重。
     * 只有一个源时界面不显示源列表。
     */
    fun allSources(): List<PlayerSource> {
        val main = PlayerSource(url, label = "", type = type)
        return (listOf(main) + sources.filter { it.url.startsWith("http", true) }).distinctBy { it.url }
    }

    /**
     * 下载时用的文件名：标题去掉文件系统不接受的字符；扩展名取地址路径里的常见视频后缀，HLS 合并后统一是 mp4。
     */
    fun downloadName(): String {
        val base = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).trim('.', ' ')
            .ifBlank { "video" }
        if (isHls()) return "$base.mp4"
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val ext = path.substringAfterLast('.', "").takeIf { it in VIDEO_EXTS } ?: "mp4"
        return "$base.$ext"
    }

    fun toIntent(context: Context): Intent =
        Intent(context, NativePlayerActivity::class.java)
            .putExtra(K_URL, url).putExtra(K_TITLE, title).putExtra(K_REFERER, referer)
            .putExtra(K_UA, ua).putExtra(K_COOKIE, cookie).putExtra(K_TYPE, type).putExtra(K_START, startMs)
            .putExtra(K_GECKO, gecko).putExtra(K_STANDALONE, standalone).putExtra(K_ORIGIN, origin)
            .apply { if (sources.isNotEmpty()) putExtra(K_SOURCES, encodeSources(sources)) }

    companion object {
        private const val K_URL = "url"
        private const val K_TITLE = "title"
        private const val K_REFERER = "referer"
        private const val K_UA = "ua"
        private const val K_COOKIE = "cookie"
        private const val K_TYPE = "type"
        private const val K_START = "startMs"
        private const val K_GECKO = "gecko"
        private const val K_SOURCES = "sources"
        private const val K_STANDALONE = "standalone"
        private const val K_ORIGIN = "origin"

        /** scheme://host[:port]；不是 http(s) 地址返回空 */
        fun originOf(url: String): String {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return ""
            val scheme = url.substringBefore("://").lowercase()
            val hostPort = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            return if (hostPort.isBlank()) "" else "$scheme://$hostPort"
        }

        private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "webm", "mov", "flv", "ts", "avi", "3gp")

        fun from(i: Intent): PlayerRequest? {
            val url = i.getStringExtra(K_URL)?.trim().orEmpty()
            val standalone = i.getBooleanExtra(K_STANDALONE, false)
            val web = url.startsWith("http://", true) || url.startsWith("https://", true)
            // 本地文件只认「打开方式」入口发来的（standalone），其余来源仍只接受 http(s)
            val local = standalone && (url.startsWith("content://", true) || url.startsWith("file://", true))
            if (!web && !local) return null
            return PlayerRequest(
                url = url,
                title = i.getStringExtra(K_TITLE).orEmpty(),
                referer = i.getStringExtra(K_REFERER).orEmpty(),
                ua = i.getStringExtra(K_UA).orEmpty(),
                cookie = i.getStringExtra(K_COOKIE).orEmpty(),
                type = i.getStringExtra(K_TYPE).orEmpty(),
                startMs = i.getLongExtra(K_START, 0),
                sources = decodeSources(i.getStringExtra(K_SOURCES)),
                gecko = i.getBooleanExtra(K_GECKO, false),
                standalone = standalone,
                origin = i.getStringExtra(K_ORIGIN).orEmpty(),
            )
        }

        private fun encodeSources(list: List<PlayerSource>): String = JSONArray().also { a ->
            list.forEach { a.put(JSONObject().put("url", it.url).put("label", it.label).put("type", it.type)) }
        }.toString()

        /** 脚本 `play` 操作的 `sources` 数组（[{url,label,type}…]）和 Intent 里的编码是同一格式；坏数据一律当没有。 */
        fun decodeSources(json: String?): List<PlayerSource> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(json)
                (0 until a.length()).mapNotNull { n ->
                    val o = a.optJSONObject(n) ?: return@mapNotNull null
                    val u = o.optString("url").trim()
                    if (u.isEmpty()) null else PlayerSource(u, o.optString("label"), o.optString("type"))
                }
            }.getOrDefault(emptyList())
        }
    }
}
