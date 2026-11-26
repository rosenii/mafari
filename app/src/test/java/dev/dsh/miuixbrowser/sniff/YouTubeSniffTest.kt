/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：YouTube videoplayback 地址解析与音视频配对。 */
class YouTubeSniffTest {

    private fun u(itag: Int, mime: String, extra: String = "", ei: String = "EI1", dur: String = "212.041") =
        "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&ei=$ei&id=o-X&itag=$itag&mime=$mime&dur=$dur&clen=1234567&sig=AAA$extra"

    @Test fun parsesVideoOnlyStreamAndDropsRangeParams() {
        val s = YouTubeSniff.parse(u(137, "video%2Fmp4", "&range=0-1048575&rn=3&rbuf=9000"))!!
        assertEquals(YouTubeSniff.Kind.VIDEO, s.kind)
        assertEquals(1080, s.height)
        assertEquals(212_041L, s.durationMs)
        assertEquals(1_234_567L, s.contentLength)
        assertEquals("yt:EI1", s.groupKey)
        assertFalse("range=" in s.url || "rn=" in s.url || "rbuf=" in s.url)
        assertTrue("itag=137" in s.url && "sig=AAA" in s.url)
    }

    @Test fun audioAndMuxedKinds() {
        assertEquals(YouTubeSniff.Kind.AUDIO, YouTubeSniff.parse(u(140, "audio%2Fmp4"))!!.kind)
        assertEquals(YouTubeSniff.Kind.MUXED, YouTubeSniff.parse(u(18, "video%2Fmp4"))!!.kind)
        assertEquals(720, YouTubeSniff.parse(u(22, "video%2Fmp4"))!!.height)
    }

    @Test fun rejectsStreamsWithoutDirectUrl() {
        assertNull(YouTubeSniff.parse(u(137, "video%2Fmp4", "&sabr=1")))
        assertEquals("sabr", YouTubeSniff.unsupportedReason(u(137, "video%2Fmp4", "&sabr=1")))
        assertEquals("ump", YouTubeSniff.unsupportedReason(u(137, "video%2Fmp4", "&ump=1")))
        assertEquals("live", YouTubeSniff.unsupportedReason(u(137, "video%2Fmp4", "&live=1")))
        assertEquals("segmented", YouTubeSniff.unsupportedReason(u(137, "video%2Fmp4", "&sq=12")))
        assertEquals("no-format", YouTubeSniff.unsupportedReason("https://rr1---sn.googlevideo.com/videoplayback?expire=1&ei=a"))
    }

    @Test fun ignoresOtherHostsAndPaths() {
        assertFalse(YouTubeSniff.isVideoPlayback("https://example.com/videoplayback?itag=18&mime=video%2Fmp4"))
        assertFalse(YouTubeSniff.isVideoPlayback("https://rr1---sn.googlevideo.com/api/other?itag=18"))
        assertNull(YouTubeSniff.parse("https://example.com/videoplayback?itag=18&mime=video%2Fmp4"))
        assertNull(YouTubeSniff.unsupportedReason("https://example.com/videoplayback?sabr=1"))
    }

    @Test fun pairsVideoWithAudioAndUpgradesQuality() {
        val c = YouTubeSniff.Collector()
        // 只有视频：配不成对
        assertNull(c.add(YouTubeSniff.parse(u(134, "video%2Fmp4"))!!))
        // 音频到了：360p + 音频
        val first = c.add(YouTubeSniff.parse(u(140, "audio%2Fmp4"))!!)!!
        assertEquals("YouTube · 360p", first.label)
        assertTrue(first.audioUrl.contains("itag=140"))
        assertTrue(first.url.contains("itag=134"))
        // 清晰度升到 1080p：rank 更大
        val up = c.add(YouTubeSniff.parse(u(137, "video%2Fmp4"))!!)!!
        assertEquals("YouTube · 1080p", up.label)
        assertTrue(up.rank > first.rank)
        // 同一视频后来又来了低清晰度的请求：仍是最好的那条
        assertEquals("YouTube · 1080p", c.add(YouTubeSniff.parse(u(135, "video%2Fmp4"))!!)!!.label)
    }

    @Test fun muxedStreamPlaysAloneAndLosesToHigherSplitStream() {
        val c = YouTubeSniff.Collector()
        val m = c.add(YouTubeSniff.parse(u(22, "video%2Fmp4"))!!)!!
        assertEquals("", m.audioUrl)
        assertEquals("YouTube · 720p", m.label)
        c.add(YouTubeSniff.parse(u(137, "video%2Fmp4"))!!)
        val best = c.add(YouTubeSniff.parse(u(140, "audio%2Fmp4"))!!)!!
        assertEquals("YouTube · 1080p", best.label)
        assertTrue(best.audioUrl.isNotEmpty())
    }

    @Test fun differentVideosStaySeparate() {
        val c = YouTubeSniff.Collector()
        c.add(YouTubeSniff.parse(u(137, "video%2Fmp4", ei = "A"))!!)
        assertNull(c.add(YouTubeSniff.parse(u(140, "audio%2Fmp4", ei = "B"))!!))
    }

    @Test fun preferAacOverOpusAndKeepsWebmType() {
        val c = YouTubeSniff.Collector()
        c.add(YouTubeSniff.parse(u(251, "audio%2Fwebm"))!!)
        c.add(YouTubeSniff.parse(u(140, "audio%2Fmp4"))!!)
        val m = c.add(YouTubeSniff.parse(u(248, "video%2Fwebm"))!!)!!
        assertEquals("webm", m.type)
        assertTrue(m.audioUrl.contains("itag=140"))
    }

    @Test fun sniffListKeepsOneEntryPerVideoAndUpgrades() {
        val list = SniffList()
        list.yt.add(YouTubeSniff.parse(u(134, "video%2Fmp4"))!!)
        val low = list.yt.add(YouTubeSniff.parse(u(140, "audio%2Fmp4"))!!)!!
        assertTrue(list.offer(low))
        val high = list.yt.add(YouTubeSniff.parse(u(137, "video%2Fmp4"))!!)!!
        assertFalse(list.offer(high)) // 同一个视频：替换而不是新增
        assertEquals(1, list.items.size)
        assertTrue(list.items[0].url.contains("itag=137"))
        // 已经自动播过的那条不再被替换
        list.markAutoPlayed(list.items[0].url)
        val higher = list.yt.add(YouTubeSniff.parse(u(299, "video%2Fmp4"))!!)!!
        list.offer(higher)
        assertTrue(list.items[0].url.contains("itag=137"))
        assertNotNull(list.items[0].audioUrl.takeIf { it.isNotEmpty() })
    }
}
