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

/** 纯 JVM：X（video.twimg.com）的视频地址规则。 */
class XVideoTest {

    private val master = "https://video.twimg.com/ext_tw_video/1850000000000000001/pu/pl/AbCdEf.m3u8?tag=14&v=123"
    private val variant = "https://video.twimg.com/ext_tw_video/1850000000000000001/pu/pl/avc1/720x1280/AbCdEf.m3u8"
    private val audio = "https://video.twimg.com/ext_tw_video/1850000000000000001/pu/pl/mp4a/128000/AbCdEf.m3u8"
    private val init = "https://video.twimg.com/ext_tw_video/1850000000000000001/pu/pl/init/avc1/720x1280/AbCdEf.mp4"
    private val mp4 = "https://video.twimg.com/ext_tw_video/1850000000000000001/pu/vid/avc1/720x1280/AbCdEf.mp4?tag=12"
    private val gif = "https://video.twimg.com/tweet_video/FxYz123.mp4"

    @Test fun hostDetection() {
        assertTrue(XVideo.isXMedia(master))
        assertFalse(XVideo.isXMedia("https://example.com/video.twimg.com/a.m3u8"))
        assertFalse(XVideo.isXMedia("https://pbs.twimg.com/media/a.jpg"))
    }

    @Test fun masterAndVariantPlaylists() {
        assertTrue(XVideo.isMasterPlaylist(master))
        assertFalse(XVideo.isVariantPlaylist(master))
        assertTrue(XVideo.isVariantPlaylist(variant))
        assertFalse(XVideo.isMasterPlaylist(variant))
        assertFalse(XVideo.isMasterPlaylist(mp4))
        assertFalse(XVideo.isVariantPlaylist("https://cdn.example.com/pl/a/b/c.m3u8"))
    }

    @Test fun noiseIsAudioPlaylistAndInitSegments() {
        assertTrue(XVideo.isNoise(audio))
        assertTrue(XVideo.isNoise(init))
        assertFalse(XVideo.isNoise(master))
        assertFalse(XVideo.isNoise(variant))
        assertFalse(XVideo.isNoise(mp4))
        assertFalse(XVideo.isNoise(gif))
        assertFalse(XVideo.isNoise("https://cdn.example.com/pl/init/a.mp4")) // 只管 X
    }

    @Test fun classifyUsesNoiseRules() {
        assertEquals("hls", MediaSniff.classify(master))
        assertEquals("hls", MediaSniff.classify(variant))
        assertEquals("mp4", MediaSniff.classify(mp4))
        assertEquals("mp4", MediaSniff.classify(gif))
        assertNull(MediaSniff.classify(audio))
        assertNull(MediaSniff.classify(init))
        assertNull(MediaSniff.classify(init, "video/mp4"))
        assertNull(MediaSniff.classify("https://video.twimg.com/ext_tw_video/1850000000000000001/pu/vid/avc1/0/3000/720x1280/seg.m4s"))
    }

    @Test fun mediaKeyIsSharedByMasterAndVariants() {
        assertEquals("1850000000000000001", XVideo.mediaKey(master))
        assertEquals(XVideo.mediaKey(master), XVideo.mediaKey(variant))
        assertEquals("1850000000000000001", XVideo.mediaKey("https://video.twimg.com/amplify_video/1850000000000000001/pl/x.m3u8"))
        assertNull(XVideo.mediaKey(gif))
    }

    @Test fun qualityLabel() {
        assertEquals("720×1280 · 2.2 Mbps", XVideo.label(mp4, 2_176_000))
        assertEquals("720×1280 · 832 kbps", XVideo.label(mp4, 832_000))
        assertEquals("720×1280", XVideo.label(mp4, 0))
        assertEquals("", XVideo.label(master, 0))
        assertEquals("", XVideo.label("https://example.com/720x1280/a.mp4", 2_000_000))
    }
}
