/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import dev.dsh.miuixbrowser.util.Diag
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 原生（HttpURLConnection）请求被 YouTube 判成机器人（「Sign in to confirm you're not a bot」）时的兜底：
 * 改在页面自己的环境里取——同一个浏览器指纹 / TLS / Cookie / 访客身份，比原生请求更容易被放行。
 *  1. 页面播放器自己手里的 player 响应（`#movie_player.getPlayerResponse()`）：页面能播就说明它有；
 *  2. 页内同源 fetch innertube `player`（IOS / ANDROID_VR 客户端参数，带页面 Cookie）。
 * 只有 WebView 页能用（Gecko 页没有 evaluateJavascript），[eval] 由调用方包装。
 */
object YouTubePageFallback {

    private fun decode(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
    }

    private suspend fun evalString(eval: (String, (String?) -> Unit) -> Unit, js: String): String =
        withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<String> { k -> eval(js) { raw -> if (k.isActive) k.resume(decode(raw)) } }
        }.orEmpty()

    suspend fun resolve(
        videoId: String,
        visitor: String,
        eval: (String, (String?) -> Unit) -> Unit,
    ): YouTubeResolver.Result? {
        // 1. 页面播放器自己的响应
        val pageJson = evalString(eval, """(function(){try{
            var p=document.getElementById('movie_player'),r=null;
            if(p&&p.getPlayerResponse)r=p.getPlayerResponse();
            if(!r||!r.videoDetails||r.videoDetails.videoId!=='$videoId')r=window.ytInitialPlayerResponse;
            if(!r||!r.videoDetails||r.videoDetails.videoId!=='$videoId')return '';
            var vd=r.videoDetails,sd=r.streamingData||{};
            return JSON.stringify({playabilityStatus:r.playabilityStatus,
              videoDetails:{title:vd.title,lengthSeconds:vd.lengthSeconds,isLive:vd.isLive},
              responseContext:{visitorData:(r.responseContext||{}).visitorData},
              streamingData:{hlsManifestUrl:sd.hlsManifestUrl,formats:(sd.formats||[]).filter(function(x){return x.url})}});
          }catch(e){return ''}})()""")
        if (pageJson.isNotBlank()) {
            val parsed = YouTube.parse(pageJson)
            if (parsed.streams.isEmpty()) Diag.log("youtube page-player: no streams (${parsed.reason})")
            else {
                val items = YouTubeResolver.toItems(videoId, parsed, "")
                Diag.log("youtube page-player: ${items.size} streams")
                return YouTubeResolver.remember(videoId, items, parsed.title, "PAGE")
            }
        } else Diag.log("youtube page-player: no response in page")

        // 2. 页内同源 fetch（结果写到 window.__mafariYt，轮询取回：evaluateJavascript 不等 Promise）
        for (client in YouTube.CLIENTS.filter { it.name == "IOS" || it.name == "ANDROID_VR" }) {
            val body = JSONObject.quote(YouTube.playerBody(videoId, client, visitor))
            val key = "__mafariYt_" + client.name
            eval("""(function(){window.$key=null;
                fetch('/youtubei/v1/player?prettyPrint=false',{method:'POST',credentials:'include',
                  headers:{'Content-Type':'application/json','X-YouTube-Client-Name':'${client.clientId}','X-YouTube-Client-Version':'${client.version}'},
                  body:$body}).then(function(r){return r.text()}).then(function(t){window.$key=t}).catch(function(e){window.$key='ERR'});
              return ''})()""") {}
            var text = ""
            repeat(16) {
                if (text.isNotEmpty()) return@repeat
                delay(400)
                text = evalString(eval, "(function(){var v=window.$key;return v?String(v):''})()")
            }
            if (text.isBlank() || text == "ERR") { Diag.log("youtube page-fetch ${client.name}: no response"); continue }
            val parsed = YouTube.parse(text)
            if (parsed.streams.isEmpty()) { Diag.log("youtube page-fetch ${client.name}: no streams (${parsed.reason})"); continue }
            val items = YouTubeResolver.toItems(videoId, parsed, "")
            Diag.log("youtube page-fetch ${client.name}: ${items.size} streams")
            return YouTubeResolver.remember(videoId, items, parsed.title, "PAGE-" + client.name)
        }
        return null
    }
}
