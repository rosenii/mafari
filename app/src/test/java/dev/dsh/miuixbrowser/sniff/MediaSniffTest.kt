/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：嗅探的地址 / 响应头判定。 */
class MediaSniffTest {

    @Test fun hlsByExtension() {
        assertEquals("hls", MediaSniff.classify("https://cdn.example.com/a/index.m3u8"))
        assertEquals("hls", MediaSniff.classify("https://cdn.example.com/a/index.M3U8?token=abc&x=1.mp4"))
    }

    @Test fun hlsByContentTypeWithoutExtension() {
        assertEquals("hls", MediaSniff.classify("https://x.com/api/play?id=1", "application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals("hls", MediaSniff.classify("https://x.com/api/play?id=1", "application/x-mpegURL"))
    }

    @Test fun progressiveFiles() {
        assertEquals("mp4", MediaSniff.classify("https://x.com/v/1.mp4"))
        assertEquals("mp4", MediaSniff.classify("https://x.com/v/1.m4v?e=1"))
        assertEquals("webm", MediaSniff.classify("https://x.com/v/1.webm"))
        assertEquals("flv", MediaSniff.classify("https://x.com/v/1.flv"))
        assertEquals("mkv", MediaSniff.classify("https://x.com/v/1", "video/x-matroska"))
        assertEquals("mp4", MediaSniff.classify("https://x.com/v/1", "video/mp4"))
    }

    @Test fun segmentsAreRejected() {
        assertNull(MediaSniff.classify("https://x.com/seg/1.ts"))
        assertNull(MediaSniff.classify("https://x.com/seg/1.m4s"))
        assertNull(MediaSniff.classify("https://x.com/seg/1", "video/mp2t"))
        assertNull(MediaSniff.classify("https://x.com/seg/1.mp4", "video/iso.segment"))
    }

    @Test fun nonVideoIsRejected() {
        assertNull(MediaSniff.classify("https://x.com/a.js"))
        assertNull(MediaSniff.classify("https://x.com/a.jpg", "image/jpeg"))
        assertNull(MediaSniff.classify("https://x.com/a.mpd", "application/dash+xml"))
        assertNull(MediaSniff.classify("https://x.com/a", "text/html"))
        assertNull(MediaSniff.classify("blob:https://x.com/1234", "video/mp4"))
        assertNull(MediaSniff.classify("data:video/mp4;base64,AAAA"))
        assertNull(MediaSniff.classify("https://x.com/" + "a".repeat(5000) + ".mp4"))
    }

    @Test fun tinyFilesAreRejectedButSmallPlaylistsAreNot() {
        assertNull(MediaSniff.classify("https://x.com/ad.mp4", null, 50_000))
        assertEquals("mp4", MediaSniff.classify("https://x.com/v.mp4", null, 5_000_000))
        assertEquals("mp4", MediaSniff.classify("https://x.com/v.mp4", null, -1))
        assertEquals("hls", MediaSniff.classify("https://x.com/i.m3u8", null, 300))
    }

    @Test fun mediaElementWithoutAnyHint() {
        assertEquals("mp4", MediaSniff.classify("https://x.com/stream/42", null, -1, fromMediaElement = true))
        assertNull(MediaSniff.classify("https://x.com/stream/42", null, -1, fromMediaElement = false))
        assertEquals("hls", MediaSniff.classify("https://x.com/stream/42.m3u8", null, -1, fromMediaElement = true))
    }

    @Test fun hostAndDisplayName() {
        assertEquals("cdn.example.com", MediaSniff.hostOf("https://user@CDN.Example.com:8443/a/b.mp4?x=1"))
        val m = SniffedMedia("https://cdn.example.com/a/movie.mp4?x=1", "mp4", "cdn.example.com")
        assertEquals("movie.mp4", MediaSniff.displayName(m))
        val bare = SniffedMedia("https://cdn.example.com/?x=1", "mp4", "cdn.example.com")
        assertEquals("cdn.example.com", MediaSniff.displayName(bare))
    }

    private fun m(url: String, type: String = "mp4", size: Long = -1, dur: Long = -1, pv: Boolean? = null, ua: Boolean = false) =
        SniffedMedia(url, type, "x.com", size, dur, pv, ua)

    @Test fun formatDuration() {
        assertEquals("0:15", MediaSniff.formatDuration(15_000))
        assertEquals("1:05", MediaSniff.formatDuration(65_000))
        assertEquals("1:02:05", MediaSniff.formatDuration(3_725_000))
        assertEquals("0:02", MediaSniff.formatDuration(1_600)) // 四舍五入到秒
        assertNull(MediaSniff.formatDuration(-1))
        assertNull(MediaSniff.formatDuration(MediaSniff.DURATION_LIVE))
    }

    @Test fun shortPreviewsAreNotAutoPlayed() {
        assertFalse(MediaSniff.isAutoCandidate(m("https://x.com/p.mp4", dur = 12_000)))
        assertFalse(MediaSniff.isAutoCandidate(m("https://x.com/p.mp4", dur = 39_000)))
        assertTrue(MediaSniff.isAutoCandidate(m("https://x.com/v.mp4", dur = 40_000)))
        assertTrue(MediaSniff.isAutoCandidate(m("https://x.com/v.mp4", dur = 3_600_000)))
    }

    @Test fun pagePreviewFlagWins() {
        // 循环 / 很小 / 不可见的 <video>：即使时长够长也不自动播
        assertFalse(MediaSniff.isAutoCandidate(m("https://x.com/bg.mp4", dur = 600_000, pv = true)))
        assertTrue(MediaSniff.isAutoCandidate(m("https://x.com/v.mp4", dur = 600_000, pv = false)))
        // 用户自己点了播放的短视频不拦；但页面标记为预览的仍不放行
        assertTrue(MediaSniff.isAutoCandidate(m("https://x.com/s.mp4", dur = 10_000, ua = true)))
        assertFalse(MediaSniff.isAutoCandidate(m("https://x.com/s.mp4", dur = 10_000, pv = true, ua = true)))
    }

    @Test fun unknownDuration() {
        assertTrue(MediaSniff.isAutoCandidate(m("https://x.com/i.m3u8", type = "hls")))
        assertFalse(MediaSniff.isAutoCandidate(m("https://x.com/a.mp4")))
        assertFalse(MediaSniff.isAutoCandidate(m("https://x.com/a.mp4", size = 900_000)))
        assertTrue(MediaSniff.isAutoCandidate(m("https://x.com/a.mp4", size = 30_000_000)))
        assertTrue(MediaSniff.isAutoCandidate(m("https://x.com/live.m3u8", type = "hls", dur = MediaSniff.DURATION_LIVE)))
    }

    @Test fun pickPrefersLongestAndSkipsPlayed() {
        val preview = m("https://x.com/p.mp4", dur = 12_000)
        val bg = m("https://x.com/bg.mp4", dur = 900_000, pv = true)
        val main = m("https://x.com/main.mp4", dur = 1_800_000)
        val other = m("https://x.com/other.mp4", dur = 300_000)
        assertEquals(main, MediaSniff.pickAutoPlay(listOf(preview, bg, other, main)))
        assertEquals(other, MediaSniff.pickAutoPlay(listOf(preview, bg, other, main), setOf(main.url)))
        assertNull(MediaSniff.pickAutoPlay(listOf(preview, bg)))
    }

    @Test fun hlsDuration() {
        val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:9.5,\na.ts\n#EXTINF:10.0,\nb.ts\n#EXT-X-ENDLIST\n"
        assertEquals(19_500, MediaSniff.hlsDurationMs(vod))
        assertEquals(MediaSniff.DURATION_LIVE, MediaSniff.hlsDurationMs(vod.replace("#EXT-X-ENDLIST\n", "")))
        assertEquals(-1, MediaSniff.hlsDurationMs("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"))
    }

    @Test fun masterPlaylistVariant() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh/index.m3u8\n"
        assertEquals("https://x.com/v/low/index.m3u8", MediaSniff.firstVariant(master, "https://x.com/v/master.m3u8"))
        assertEquals("https://cdn.com/h.m3u8", MediaSniff.firstVariant("#EXT-X-STREAM-INF:X=1\nhttps://cdn.com/h.m3u8\n", "https://x.com/m.m3u8"))
        assertNull(MediaSniff.firstVariant("#EXTM3U\n#EXTINF:1,\na.ts\n", "https://x.com/m.m3u8"))
    }

    @Test fun listMergesLateInfo() {
        val l = SniffList()
        assertTrue(l.offer(m("https://x.com/a.mp4")))
        // 页面后来才报出时长和预览标记：并进已有那条，不算新增
        assertFalse(l.offer(m("https://x.com/a.mp4#t=1", dur = 15_000, pv = true)))
        assertEquals(1, l.items.size)
        assertEquals(15_000L, l.items[0].durationMs)
        assertEquals(true, l.items[0].preview)
        // 之后页面更正：不再是预览
        l.offer(m("https://x.com/a.mp4", pv = false))
        assertEquals(false, l.items[0].preview)
        assertEquals(15_000L, l.items[0].durationMs)
        // 探测结果不覆盖页面已报的时长
        l.setProbedDuration("https://x.com/a.mp4", 99_000)
        assertEquals(15_000L, l.items[0].durationMs)
    }

    @Test fun probeBookkeeping() {
        val l = SniffList()
        l.offer(m("https://x.com/a.mp4"))
        assertTrue(l.probePending("https://x.com/a.mp4"))
        assertTrue(l.beginProbe("https://x.com/a.mp4"))
        assertFalse(l.beginProbe("https://x.com/a.mp4")) // 正在探
        l.endProbe("https://x.com/a.mp4")
        assertFalse(l.probePending("https://x.com/a.mp4"))
        assertFalse(l.beginProbe("https://x.com/a.mp4")) // 已经探过
        l.setProbedDuration("https://x.com/a.mp4", 61_000)
        assertEquals(61_000L, l.items[0].durationMs)
    }

    // ---- X（Twitter）----

    private val xBase = "https://video.twimg.com/ext_tw_video/1700000000000000000/pu"

    @Test fun xProgressiveMp4AndMasterPlaylistAreAccepted() {
        assertEquals("mp4", MediaSniff.classify("$xBase/vid/avc1/1280x720/AbCd.mp4?tag=12"))
        assertEquals("hls", MediaSniff.classify("$xBase/pl/AbCd.m3u8?tag=12&container=fmp4"))
        assertEquals("hls", MediaSniff.classify("$xBase/pl/avc1/1280x720/AbCd.m3u8"))
    }

    @Test fun xAudioPlaylistsAndInitSegmentsAreRejected() {
        assertNull(MediaSniff.classify("$xBase/pl/mp4a/128000/AbCd.m3u8"))
        assertNull(MediaSniff.classify("$xBase/aud/mp4a/0/0/AbCd.mp4"))
        assertNull(MediaSniff.classify("$xBase/vid/avc1/0/0/1280x720/AbCd.mp4", "video/mp4"))
        assertNull(MediaSniff.classify("$xBase/vid/avc1/0/3000/1280x720/AbCd.m4s"))
        // 页面脚本从推文接口读到的正式地址不按形态排除
        assertEquals("mp4", MediaSniff.classify("$xBase/vid/avc1/1280x720/AbCd.mp4", trusted = true))
        // 不是 X 的域名不受影响
        assertEquals("mp4", MediaSniff.classify("https://cdn.example.com/vid/avc1/0/0/1280x720/a.mp4"))
    }

    @Test fun xHlsRoleAndKey() {
        assertEquals(XVideo.ROLE_MASTER, XVideo.hlsRole("$xBase/pl/AbCd.m3u8?tag=12"))
        assertEquals(XVideo.ROLE_VARIANT, XVideo.hlsRole("$xBase/pl/avc1/1280x720/AbCd.m3u8"))
        assertEquals(XVideo.ROLE_NONE, XVideo.hlsRole("https://cdn.example.com/pl/a.m3u8"))
        assertEquals("ext_tw_video/1700000000000000000", XVideo.key("$xBase/pl/AbCd.m3u8"))
        assertEquals(XVideo.key("$xBase/pl/AbCd.m3u8"), XVideo.key("$xBase/pl/avc1/640x360/Zz.m3u8"))
        assertEquals("amplify_video/42", XVideo.key("https://video.twimg.com/amplify_video/42/pl/a.m3u8"))
    }

    @Test fun xDisplayName() {
        fun name(u: String, t: String) = MediaSniff.displayName(SniffedMedia(u, t, "video.twimg.com"))
        assertEquals("X · 1280×720", name("$xBase/vid/avc1/1280x720/AbCd.mp4?tag=12", "mp4"))
        assertEquals("X · HLS", name("$xBase/pl/AbCd.m3u8", "hls"))
        assertEquals("X · HLS 640×360", name("$xBase/pl/avc1/640x360/AbCd.m3u8", "hls"))
        assertEquals("X · GIF", name("https://video.twimg.com/tweet_video/Abc.mp4", "mp4"))
    }

    @Test fun xMasterReplacesVariantsAndBlocksLateOnes() {
        fun hls(u: String) = SniffedMedia(u, "hls", "video.twimg.com")
        val variant = "$xBase/pl/avc1/1280x720/AbCd.m3u8"
        val master = "$xBase/pl/AbCd.m3u8"
        val l = SniffList()
        assertTrue(l.offer(hls(variant)))
        assertTrue(l.offer(hls(master)))
        assertEquals(listOf(master), l.items.map { it.url })
        // 主清单已在：之后到的子清单不收
        assertFalse(l.offer(hls("$xBase/pl/avc1/640x360/AbCd.m3u8")))
        assertEquals(1, l.items.size)
        // 另一个视频的子清单不受影响
        assertTrue(l.offer(hls("https://video.twimg.com/ext_tw_video/1800000000000000000/pu/pl/avc1/640x360/Zz.m3u8")))
        assertEquals(2, l.items.size)
    }

    @Test fun xFullListEvictsOldestXVideo() {
        val l = SniffList()
        l.offer(SniffedMedia("https://cdn.example.com/keep.mp4", "mp4", "cdn.example.com"))
        for (i in 1 until MediaSniff.MAX_ITEMS) l.offer(SniffedMedia("$xBase/vid/avc1/1280x720/v$i.mp4", "mp4", "video.twimg.com"))
        assertEquals(MediaSniff.MAX_ITEMS, l.items.size)
        // 满了：非 X 的新地址仍然进不来；X 的新地址挤掉最早的 X 视频
        assertFalse(l.offer(SniffedMedia("https://cdn.example.com/new.mp4", "mp4", "cdn.example.com")))
        assertTrue(l.offer(SniffedMedia("$xBase/vid/avc1/1280x720/latest.mp4", "mp4", "video.twimg.com")))
        assertEquals(MediaSniff.MAX_ITEMS, l.items.size)
        assertEquals("https://cdn.example.com/keep.mp4", l.items.first().url)
        assertTrue(l.items.none { it.url.endsWith("/v1.mp4") })
        assertTrue(l.items.last().url.endsWith("/latest.mp4"))
    }
}
