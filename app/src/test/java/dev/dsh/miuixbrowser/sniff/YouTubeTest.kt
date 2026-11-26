/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：YouTube 视频 id 提取与 player 接口响应解析。 */
class YouTubeTest {

    private val id = "dQw4w9WgXcQ"

    @Test fun videoIdFromCommonUrls() {
        assertEquals(id, YouTube.videoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YouTube.videoId("https://m.youtube.com/watch?feature=share&v=$id&t=10"))
        assertEquals(id, YouTube.videoId("https://music.youtube.com/watch?v=$id&list=RDAMVM$id"))
        assertEquals(id, YouTube.videoId("https://youtu.be/$id?si=abc"))
        assertEquals(id, YouTube.videoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YouTube.videoId("https://www.youtube.com/live/$id?feature=share"))
        assertEquals(id, YouTube.videoId("https://www.youtube-nocookie.com/embed/$id"))
    }

    @Test fun nonVideoPagesAreNull() {
        assertNull(YouTube.videoId("https://www.youtube.com/"))
        assertNull(YouTube.videoId("https://www.youtube.com/results?search_query=$id"))
        assertNull(YouTube.videoId("https://www.youtube.com/@someone"))
        assertNull(YouTube.videoId("https://www.youtube.com/playlist?list=PL1234567890A"))
        assertNull(YouTube.videoId("https://www.youtube.com/watch?v=short"))
        assertNull(YouTube.videoId("https://example.com/watch?v=$id"))
        assertNull(YouTube.videoId("https://notyoutube.com/shorts/$id"))
        assertNull(YouTube.videoId("about:blank"))
    }

    @Test fun parseCollectsHlsAndMuxedOnly() {
        val json = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"title":"T","lengthSeconds":"212","isLive":false},
         "streamingData":{
           "hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/x.m3u8",
           "formats":[
             {"itag":18,"url":"https://rr1.googlevideo.com/videoplayback?a=1","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","height":360,"qualityLabel":"360p","contentLength":"1000000","approxDurationMs":"212000"},
             {"itag":22,"url":"https://rr1.googlevideo.com/videoplayback?a=2","mimeType":"video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"","height":720,"qualityLabel":"720p"},
             {"itag":99,"signatureCipher":"s=abc&url=x","mimeType":"video/mp4","height":1080}
           ],
           "adaptiveFormats":[{"itag":137,"url":"https://rr1.googlevideo.com/videoplayback?a=3","mimeType":"video/mp4","height":1080}]
         }}
        """.trimIndent()
        val p = YouTube.parse(json)
        assertEquals("T", p.title)
        assertEquals(listOf("hls", "mp4", "mp4"), p.streams.map { it.type })
        assertEquals("720p · MP4", p.streams[1].label)
        assertEquals("360p · MP4", p.streams[2].label)
        assertEquals(1_000_000L, p.streams[2].size)
        assertEquals(212_000L, p.streams[0].durationMs)
    }

    @Test fun parseReportsReasonWhenNotPlayable() {
        val p = YouTube.parse("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"}}""")
        assertTrue(p.streams.isEmpty())
        assertEquals("Sign in to confirm your age", p.reason)
        assertTrue(YouTube.parse("not json").streams.isEmpty())
    }

    @Test fun playerBodyCarriesClientAndVideo() {
        val body = org.json.JSONObject(YouTube.playerBody(id, YouTube.CLIENTS.first()))
        assertEquals(id, body.getString("videoId"))
        assertEquals(YouTube.CLIENTS.first().name, body.getJSONObject("context").getJSONObject("client").getString("clientName"))
    }
}
