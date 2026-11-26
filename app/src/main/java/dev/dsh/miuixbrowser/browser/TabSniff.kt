/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.engine.gecko.GeckoGm
import dev.dsh.miuixbrowser.engine.webview.IncognitoProfile
import dev.dsh.miuixbrowser.engine.webview.OneWayBridge
import dev.dsh.miuixbrowser.player.NativePlayerActivity
import dev.dsh.miuixbrowser.player.PlayerRequest
import dev.dsh.miuixbrowser.player.PlayerSource
import dev.dsh.miuixbrowser.sniff.MediaSniff
import dev.dsh.miuixbrowser.sniff.SniffList
import dev.dsh.miuixbrowser.sniff.SniffedMedia
import dev.dsh.miuixbrowser.sniff.YouTubeSniff
import dev.dsh.miuixbrowser.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/*
 * 视频嗅探（NATIVE_PLAYER.md 阶段四）：
 *  - WebView：shouldInterceptRequest 按地址判定（热路径，只做字符串判断）+ 页面脚本 mafari_sniff.js 补响应头 / <video> 地址；
 *  - Gecko：内置扩展的 webRequest.onHeadersReceived 上报，见 engine/gecko/GeckoSniff；
 *  - 结果都落在 [BrowserTab.sniffs]，界面弹提示、点「播放」走 [playSniffed]。
 */

/** shouldInterceptRequest（IO 线程）里调用：地址像视频就记下来。主框架请求不看（页面本身不是视频文件）。 */
internal fun BrowserTab.sniffRequest(request: WebResourceRequest) {
    val u = request.url.toString()
    // 页面加载这条地址时带的 Referer / Origin：iframe 播放器的防盗链按它校验，播放时原样带上
    val h = runCatching { request.requestHeaders }.getOrNull()
    // YouTube：自适应流是 googlevideo.com/videoplayback 分片请求，要单独配对音视频（见 YouTubeSniff）
    if (YouTubeSniff.isVideoPlayback(u)) {
        val stream = YouTubeSniff.parse(u)
        if (stream == null) {
            // 取不到直链（SABR / UMP / 直播分段）：只在每个页面第一次遇到时记一行原因，方便判断是不是 YouTube 改了协议
            YouTubeSniff.unsupportedReason(u)?.let { r ->
                webSniffs.yt.firstUnsupported(r)?.let { Diag.log("youtube sniff: videoplayback without direct stream ($it)") }
            }
            return
        }
        val media = webSniffs.yt.add(stream) ?: return
        webSniffs.offer(media.copy(referer = h?.get("Referer").orEmpty(), origin = h?.get("Origin").orEmpty()))
        return
    }
    val type = MediaSniff.classify(u) ?: return
    webSniffs.offer(
        SniffedMedia(
            u, type, MediaSniff.hostOf(u),
            referer = h?.get("Referer").orEmpty(),
            origin = h?.get("Origin").orEmpty(),
        ),
    )
}

/** 安装页面脚本的消息桥并注册文档开始脚本。不支持 WebMessageListener / 文档开始脚本时只剩请求拦截这条路，不影响基本嗅探。 */
internal fun BrowserTab.installMediaSniff() {
    runCatching {
        if (!OneWayBridge.install(webView, "MafariSniff", 4200) { onSniffMessage(it) }) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val js = InjectAssets.read(appContext, "mafari_sniff.js") ?: error("mafari_sniff.js missing")
        WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
    }.onFailure { Diag.log("media sniff install failed: ${it.message}") }
}

/**
 * 页面脚本的报告（主线程；来源 / 主框架 / 长度已在 [OneWayBridge] 校验）：
 * {u, ct, len, el, d?, pv?, ua?}。d = `<video>` 的时长（秒，直播 / 无限为 -2）；pv = 页面判断这是预览 / 装饰
 * （循环、很小、不可见）；ua = 用户刚点过播放。后几项只有来自 `<video>` 的报告才有，同一地址后到的报告会并进已有那条。
 */
internal fun BrowserTab.onSniffMessage(json: String) {
    if (!MediaSniff.enabled) return
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return
    val u = o.optString("u")
    val len = o.optLong("len", -1)
    val type = MediaSniff.classify(u, o.optString("ct").ifEmpty { null }, len, o.optBoolean("el")) ?: return
    val d = o.optDouble("d", Double.NaN)
    val durationMs = when {
        d.isNaN() || d == -1.0 -> -1L
        d == -2.0 -> MediaSniff.DURATION_LIVE
        d >= 0 -> (d * 1000).toLong()
        else -> -1L
    }
    val preview = if (o.has("pv")) o.optBoolean("pv") else null
    webSniffs.offer(SniffedMedia(u, type, MediaSniff.hostOf(u), len, durationMs, preview, o.optBoolean("ua")))
}

/**
 * 给还不知道时长的嗅探项探时长（后台线程做网络，结果写回 [list]；[timeoutMs] 内没探完的下次再来）。
 *  - HLS：取清单（主清单再取第一条子清单），累加 EXTINF；没有 ENDLIST 当作直播；
 *  - 整段文件：MediaMetadataRetriever 读容器头。
 * 请求带当前页的 Referer / UA / Cookie（和播放时一致）。已经探过 / 页面已报过时长的跳过。
 * 返回时本次关心的项要么有了结果，要么确实探不出来（或超时）。
 */
internal suspend fun BrowserTab.probeDurations(list: SniffList, timeoutMs: Long) {
    val todo = list.items.filter { it.durationMs == -1L && list.probePending(it.url) }
    if (todo.isEmpty()) return
    val pageUrl = url
    val gecko = isGecko
    val ua = if (gecko) GeckoGm.userAgent.orEmpty() else runCatching { webView.settings.userAgentString }.getOrNull().orEmpty()
    // WebView 的 Cookie 在主线程取（调用方在主线程协程里）；Gecko 的要一来一回，放进下面的 IO 里
    val cookies = if (gecko) emptyMap() else todo.associate { m ->
        m.url to runCatching { IncognitoProfile.cookieManagerFor(webView).getCookie(m.url) }.getOrNull().orEmpty()
    }
    todo.forEach { m ->
        if (!list.beginProbe(m.url)) return@forEach // 别处（弹窗 / 自动播放）正在探
        // 探测放进独立协程域：阻塞的网络 / 解析不响应取消，调用方超时就不再等，结果之后到了照样写回
        probeScope.launch {
            try {
                val cookie = if (gecko) runCatching { GeckoGm.cookieHeader(m.url) }.getOrNull().orEmpty() else cookies[m.url].orEmpty()
                val ms = runCatching { probeOne(m, pageUrl, ua, cookie) }.getOrDefault(-1L)
                if (ms != -1L) list.setProbedDuration(m.url, ms)
            } finally {
                list.endProbe(m.url)
            }
        }
    }
    withTimeoutOrNull(timeoutMs) {
        while (todo.any { list.probePending(it.url) }) delay(120)
    }
}

private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun probeOne(m: SniffedMedia, referer: String, ua: String, cookie: String): Long {
    fun fetch(u: String): String? {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        return try {
            c.connectTimeout = 4000
            c.readTimeout = 5000
            if (ua.isNotBlank()) c.setRequestProperty("User-Agent", ua)
            if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
            if (cookie.isNotBlank()) c.setRequestProperty("Cookie", cookie)
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { it.readNBytes(256 * 1024).toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
    if (m.type == "hls") {
        val top = fetch(m.url) ?: return -1
        val variant = MediaSniff.firstVariant(top, m.url)
        return MediaSniff.hlsDurationMs(if (variant != null) fetch(variant) ?: return -1 else top)
    }
    val r = android.media.MediaMetadataRetriever()
    return try {
        val headers = HashMap<String, String>()
        if (ua.isNotBlank()) headers["User-Agent"] = ua
        if (referer.isNotBlank()) headers["Referer"] = referer
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        r.setDataSource(m.url, headers)
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: -1
    } finally {
        runCatching { r.release() }
    }
}

/**
 * 用原生播放器打开嗅探到的视频：请求头带上当前页的 Referer / UA / Cookie（防盗链源要靠它），
 * 其余嗅探到的地址作为可切换的源一起交给播放页。Gecko 取 Cookie 要一来一回，放到后台线程。
 */
internal fun BrowserTab.playSniffed(context: Context, selected: SniffedMedia, all: List<SniffedMedia>) {
    val pageUrl = url
    val pageTitle = title
    val gecko = isGecko
    sniffs.markLaunched()
    val others = all.filter { it.url != selected.url }.map {
        PlayerSource(it.url, label = "${it.type.uppercase()} · ${MediaSniff.displayName(it)}", type = hintOf(it), audioUrl = it.audioUrl)
    }
    fun launch(ua: String, cookie: String) {
        val req = PlayerRequest(
            url = selected.url,
            title = pageTitle,
            // 防盗链：优先用页面加载这条地址时真正带的 Referer / Origin（iframe 播放器是播放器页的地址），没有再退回顶层页
            referer = selected.referer.ifBlank { pageUrl },
            origin = selected.origin,
            ua = ua,
            cookie = cookie,
            type = hintOf(selected),
            audioUrl = selected.audioUrl,
            sources = others,
            gecko = gecko,
        )
        val intent = req.toIntent(context).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        // 已有播放页开着（卡片悬浮在网页上时从地址栏切换媒体）：先关掉旧的，换成新选的这一条
        if (NativePlayerActivity.showing) NativePlayerActivity.closeCurrent()
        runCatching { context.startActivity(intent) }.onFailure { Diag.log("sniff play failed: ${it.message}") }
    }
    if (gecko) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val cookie = runCatching { GeckoGm.cookieHeader(selected.url) }.getOrNull().orEmpty()
            val ua = GeckoGm.userAgent.orEmpty()
            main.post { launch(ua, cookie) }
        }, "sniff-play").start()
    } else {
        val cookie = runCatching {
            IncognitoProfile.cookieManagerFor(webView).getCookie(selected.url)
        }.getOrNull().orEmpty()
        val ua = runCatching { webView.settings.userAgentString }.getOrNull().orEmpty()
        launch(ua, cookie)
    }
}

/** 播放器的类型提示：只有 HLS 明确声明，其余让播放器按地址 / 响应判断 */
private fun hintOf(m: SniffedMedia): String = if (m.type == "hls") "hls" else ""
